# F4 — PERF-SPLIT 归因结果

日期：2026-09-15
设备：Honor BKQ-AN90 / SM8850 / HTP V81 / QAIRT 2.48.40.260702
APK：android-qnn-probe（新增 nativePerfSplit + engine 三段计时）
原始产物：runs/appv1-realtext/cells/perf_f4_live.log，设备 files/canon/perf.txt

## Gate 结论：**PASS（判别完成，假设被证伪/证实明确）**

## 一、实测原始数据

```text
slow : n=20  prep=15.794  exec=71.986  read=0.949  total=88.729  ms/step
fast : n=1   prep= 0.495  exec=10.190  read=0.087  total=10.772  ms/step
codec: n=1   prep= 0.305  exec=7555.256 read=1.868 total=7557.430 ms/call

repSlow n=100 first=71.042 min=50.349 p50=79.440 p95=82.580 mean=74.145  (输入冻结、不读输出)
repFast n=100 first= 8.995 min= 8.898 p50=14.376 p95=17.503 mean=13.831  (同上)
repCodec n=3  mean=7502.320

derived: slow_host_pct=18.9  slow_exec_pct=81.1
derived: fast_host_pct= 5.4  fast_exec_pct=94.6
```

对照：真实合成一轮 baseline
```text
frames=62 audio_s=2.972 prefill_ms=12048.0 slow_ms=6697.4 fast_ms=8550.5 codec_ms=7527.2 total_ms=34826.0 RTF=11.717
```

## 二、你的三条分支判定

| 分支 | 你的判据 | 实测 | 结论 |
|---|---|---|---|
| prepare 大 | host 量化/buildBank 是主因 | slow prep 15.79/88.73 = **17.8%** | **否**，是次要项，最多能省 ~12ms/step |
| readback 大 | 48 个 delta 输出 + 反量化 | slow read 0.95/88.73 = **1.1%** | **否**，完全不是问题 |
| **execute 大** | QNN/HTP 同步边界本身是主因 | slow exec **81.1%**，fast **94.6%**，codec **99.98%** | **是** |

**最关键的一条对照**：frozen-input 重复 execute（不改输入、不读输出）得到
```text
repSlow mean 74.15 ms   vs   正常路径 exec 71.99 ms   -> 几乎相同
repFast mean 13.83 ms   vs   正常路径 exec 10.19 ms   -> 同一量级
repCodec mean 7502 ms   vs   正常路径 exec 7555 ms   -> 几乎相同
```

也就是说：**把 host 侧准备工作全部免掉，耗时不变。** 你说的"如果仍然 80–100 ms，那 QNN/HTP synchronous submit 本身就是主要成本"——就是这个结果。

但还要再往下一层拆：**它不是 submit/sync 的固定开销，而是 DSP 真的在算。**

## 三、再往下一层：固定开销 ≤ 9 ms，其余是实打实的 DSP 时间

用两个已知图做线性拟合（execute 时间 vs 层数）：

```text
slow : 24 层 -> 74.15 ms
fast :  4 层 -> 13.83 ms
=> 固定项 a = 1.74 ms ，每层 c = 3.02 ms
拟合残差极小，两个点都在线上
```

**如果存在 ~60 ms 的固定 RPC/submit 开销，fast 不可能只有 13.8 ms。**
所以"同步边界固定开销"被证伪：固定项只有 ~1.7 ms（fast 的 first-exec 9.0 ms 也印证首次准备成本在这个量级）。

真正的成本是 **每层 ~3 ms**。而每秒层的权重是：

```text
单层参数量 ≈ 14.9 M   (896x896 q/o + 896x128x2 kv + 896x4864x3 mlp)
INT8 权重 = 14.9 MB
14.9 MB / 3.02 ms ≈ 4.9 GB/s
```

**两个图的有效权重带宽都落在 ~5 GB/s。**
SM8850 的 LPDDR5X 理论带宽 ~60–77 GB/s，可用 ~40–50 GB/s。
**也就是说 slow/fast 的图只跑到了内存带宽的 ~10%。**

## 四、由 F4 得出的排他结论

1. **host cache packing / 量化不是主要瓶颈**（prep 17.8%，且优化上限 ~12 ms/step）。→ 你原计划里的"native/NEON cache state"是**过渡优化，不是解药**，优先级下调。
2. **减少输出张量不是瓶颈**（readback 1.1%）。→ 该分支删除。
3. **异步/流水不能解决 slow**：74 ms 是 DSP 真在跑，不是等 submit。
4. **codec 的 7.5 s 是 100% 纯 HTP 计算**（prep 0.3 ms / read 1.9 ms）。T64 这个 shape 在 HTP 上就是 7.5 s，和 host 无关。

## 五、RTF < 1 的预算重算（用实测单价，不再用估计值）

```text
目标：2.972 s 音频，总预算 2.972 s

组件        现在        结构优化后（只做次数削减）        用实测单价算
prefill   12.05 s     132 exec -> 1 exec               ~0.15 s
slow       6.70 s     62 exec，次数无法再减             62 x 74 ms = 4.59 s   <-- 墙
fast       8.55 s     620 exec -> 62 exec              62 x 13.8 ms = 0.86 s
codec      7.53 s     1 次调用，次数无法再减             7.50 s               <-- 墙
--------------------------------------------------------------
合计      34.83 s                                      12.9 s  ->  RTF 4.3
```

**即使 prefill / fast 做到理论最优，slow + codec 两堵墙加起来就是 12.1 s = RTF 4.1。**
结论：**prefill multi-token 和 FastBlock10 是"必做但不充分"，必须同时把慢图本身的 3 ms/层 打下来。**

## 六、下一步（按信息量/成本排序，均已启动或就绪）

| # | 实验 | 单变量 | 预期判别 | 成本 |
|---|---|---|---|---|
| 1 | **slow A8W8**（act 16->8，其余不变） | 激活精度 | 若显著变快 => A16 在 HTP 上引入了额外转换 | 已启动 pwsh-66 |
| 2 | **slow 去掉 per-channel 量化**（改 per-tensor） | 量化粒度 | per-channel 反量化若未向量化，会直接压死带宽 | 一个 flag |
| 3 | **slow W4**（W8->W4，权重字节减半） | 权重字节 | 若时间近似减半 => 坐实带宽受限 | 一个 flag，但需验 cos |
| 4 | **张量打包微图**：48 个 cache 输入 -> 2 个，48 个 delta 输出 -> 2 个 | 边界张量数 | 与"每层 3 ms"共线，无法用现有两点分离，需单独造图 | 中 |
| 5 | **codec T64 -> T8/T16/T32 bucket 扫描** | 帧桶 | T64 的 7.5 s 是否 shape 退化 | 中 |
| 6 | **Slow CPU A/B**（ORT Android + INT4 ONNX） | backend | 74 ms 是 HTP 的问题还是模型的问题 | 大 |

**注意 4 的病态**：slow(24 层/101 张量) 与 fast(4 层/24 张量) 在"层数"和"张量数"上完全共线，
所以现有两个数据点**无法分离**"每层计算"与"每张量边界"。要分离必须造一个层数相同、张量数不同的图（即实验 4 本身）。

## 七、复现命令

```text
# 构建 + 安装
gradlew.bat --project-dir android-qnn-probe assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 放置 marker 并启动（canon/ 下需已有 F2-A1 的 canonical 状态）
adb shell "run-as com.readervoice.audio8qnnprobe touch files/PERF_SPLIT"
adb shell "monkey -p com.readervoice.audio8qnnprobe -c android.intent.category.LAUNCHER 1"

# 取结果（约 90s：init 33s + 三段计时 + 100x repeat + 3x codec）
adb shell "run-as com.readervoice.audio8qnnprobe cat files/canon/perf.txt"
```

## 八、风险/注意

- init 本身 33 s（load slow 366MB + weights 1.2GB + codec 167MB），不计入 RTF，但影响迭代速度。
- repSlow min=50.3 / p50=79.4，**离散度 ~30 ms**，说明有 DVFS/调度抖动；对比实验必须看 p50 而不是单次。
- flog 只写 logcat，本次未落盘到 init.log；perf.txt 是唯一可靠产物，已 pull。
