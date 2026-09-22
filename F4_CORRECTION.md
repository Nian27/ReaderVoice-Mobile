# F4-CORRECTION — HTP 存在 ~5x 的「冷/热」状态差，F4 的绝对值全部作废

日期：2026-09-16
触发：f4_measure_rigorous.ps1 的 A/B/A/B 可重复性检验
原始产物：runs/audio8-perf-f3/perf_A1/A2/B1/B2_*.txt

## Gate 结论：**FAIL —— F4 的绝对数值不可用；但得到一条更重要的系统性发现**

## 一、A/B/A/B 实测（每格都做了 sha 校验 + perf.txt mtime 新鲜度校验）

    A1 base_a16w8      sha=f114699248c7446d match=True
       slow exec=73.94   repSlow p50=78.74  mean=74.22   codec exec=7650.1  repCodec=7587.4
    B1 a16w8_pertensor sha=957be708963b4671 match=True
       slow exec=29.63   repSlow p50=14.64  mean=14.64   codec exec=1493.7  repCodec=1497.3
    A2 base_a16w8      sha=f114699248c7446d match=True   <-- 与 A1 完全同一个 context
       slow exec=15.69   repSlow p50=15.62  mean=15.65   codec exec=1500.1  repCodec=1590.2
    B2 a16w8_pertensor sha=957be708963b4671 match=True
       slow exec=76.23   repSlow p50=16.23  mean=37.94   codec exec=1513.0  repCodec=1516.2
                         repSlow p95=82.34  min=15.73   <-- 同一次 100x 循环内双峰

## 二、判读

**A2 与 A1 是同一个 context（sha 相同、match=True），但 A2 比 A1 快 4.7x。**
**B2 的 repSlow 在同一个 100 次循环内 min=15.7 / p95=82.3，双峰。**

=> 差异**不是**来自量化方式，而是来自 **SoC/HTP 的冷-热状态**。

规律：
- 每次 am force-stop + 重新 launch 后，nativeInit 要跑 **~45 s**（加载 1.7 GB context），
  这段时间 DSP 几乎不做计算 => **降频**。
- 重新开始计算后，DSP 需要**持续负载数十次 execute** 才升回高频。
- 表现：每次 run 的前 ~20 次 execute 是 **慢态（~74-82 ms）**，之后进入 **快态（~15.7 ms）**。

这一条同时解释了原 F4 run（安装后首次运行 = 全程冷态）为什么得到 74 ms/exec 和 7555 ms/codec。

## 三、被推翻的结论（我此前给出过的）

| 此前结论 | 状态 |
|---|---|
| slow 74 ms/exec，每层 3.0 ms | **作废**。热态是 **15.7 ms/exec，每层 ~0.65 ms** |
| 有效权重带宽 ~5 GB/s，只有 DRAM 的 10% | **作废**。热态 14.9 MB / 0.65 ms ≈ **23 GB/s** |
| 固定项 1.7 ms + 每层 3.0 ms 的两点拟合 | **作废**（用的是两个不同热态的数据点） |
| A8W8 比 A16W8 快 1.49x | **作废**（那是冷态 vs 热态，不是量化差异） |
| per-channel 量化是杀手，codec 快 4.7x | **作废**（同上；codec context 根本没换过） |
| HTP 比桌面 CPU 慢 2.4~4.8x（codec） | **作废**。热态 HTP codec = 1500 ms / 131072 = **11.4 us/sample**，桌面 CPU 12.1 us/sample —— **HTP 反而略快** |
| codec 7.5 s 是后端问题，应该换 backend | **作废**。热态 codec = 1.5 s，单独 RTF ≈ 0.52 |

**仍然成立的结论：**
- halo 是死路（纯 PC 上的 FP16 数值实验，与设备热态无关）
- 官方 decoder 在 PC 上严格 O(T)（同上）
- host 侧 prep/readback 不是瓶颈（A2 热态：host 31.4%、exec 68.6%）
- codec 的 11.4 us/sample 是真实计算，不是 host 开销

## 四、修正后的预算（用热态单价）

                  冷态(原 F4)   热态(修正)
    prefill        12.05 s      待测（应有 ~4.7x 缩放，~2.6 s）
    slow            6.70 s      62 x ~22 ms ≈ 1.4 s
    fast            8.55 s      620 x ~2.7 ms ≈ 1.7 s
    codec           7.53 s      1.50 s
    ------------------------------------------
    total          34.83 s      ≈ 7.2 s   ->  RTF ≈ 2.4   (不是 11.7)

再做 multi-token prefill + FastBlock10 之后约 4.2 s（RTF 1.4），
再叠加 Slow 减层蒸馏 + Fast one-shot 蒸馏才进 RTF < 1。

**路线没变，但起点比原以为的好 2.5x，而且「每层 0.65 ms」说明硬件利用率是正常的，不需要先解决什么「带宽墙」。**

## 五、新增的工程要求（必须写进后续所有测量）

1. **任何性能数字必须标注冷/热**。默认报**热态**。
2. **预热协议**：跑测量前先执行 >=100 次 execute 的暖机并丢弃；或只取 repXxx 的 p50。
3. **perf.txt 必须带 mtime 新鲜度校验**，否则会读到上一次的陈旧结果
   （本次 a8w8 第一次跑就复用了 base 的 perf.txt，8 个数字逐位相同）。
4. **每次换 context 后必须校验 sha**（本次已加）。

## 六、待办

- [ ] 用热态重测 in-app 端到端（SYNTHTEXT_OK），得到真实 RTF
- [ ] 冷启动是否可缓解（init 后做一次 warm-up pass？）—— 影响首次出声体验
- [ ] a8w8 / a16w4 两个 context 连续两次 NO_FRESH_RESULT，需单独查（可能加载即崩）
- [ ] 重跑 F4 的 in-app 冷/热对照
## 七、in-app 冷态端到端实测（2026-09-16 10:05）

    SYNTHTEXT_OK frames=64 audio_s=2.972 prefill_ms=16914.4 slow_ms=8105.5 fast_ms=9577.1 codec_ms=7590.6 total_ms=42191.6 RTF=14.196

这是一次**完全冷启动**的合成（进程内没有先做过任何热身）：

    组件        实测        除帧数得到单价      热态预期      倍数
    prefill   16914 ms    132 exec -> 128 ms    ~20 ms       6.4x
    slow       8105 ms     64 frame -> 127 ms    ~22 ms       5.8x
    fast       9577 ms    640 exec ->  15.0 ms   ~2.7 ms      5.6x
    codec      7591 ms     1 call               1500 ms       5.1x

**四个组件一致地慢 5.1~6.4x** —— 这正是「整机/DSP 处于低频」的特征，
而不是某个模块的问题。这进一步坐实了冷/热态解释。

注意：`flog` 的输出写进 `files/init.log`（**不是 logcat**）。
此前几次用 logcat 抓 app 日志抓不到，就是踩了这个坑。

## 八、仍未拿到：in-app 热态 RTF

`AUTO_RUN`（runTest3）本次只产出了 **1 次** SYNTHTEXT_OK 就结束，
所以只有冷态数字。**热态端到端 RTF 是下一步唯一必须补的测量**：

- 需要让同一进程连续做 >=3 次合成，取第 2/3 次
- 或者确认 runTest3 为何只跑 1 次（可能中途被系统回收）
## 九、第二个污染源：AR checkpoint dump 硬编码常开（已修复）

audio8_jni.cpp 的 nativeInit 里有 C2 调试期遗留：

    g_a8ArDumpDir = g_modelsDir;        // 常开，无 marker 控制
    LOGI("C2 AR dump ENABLED (always-on)");

它让**每一次 slow execute 都写 51 个输入 + 50 个输出张量文件**。
in-app 合成时 prefill 有 132 个位置 => 132 x 101 = 上万个文件写。

影响范围：
- **被污染**：in-app 的 prefill_ms / slow_ms（SYNTHTEXT_OK 行）
- **未污染**：nativePerfSplit 的全部数字（走 slowStepRaw，不带 dump）

=> 之前 in-app 冷态 prefill=16914.4ms / slow=8105.5ms **含大量磁盘 I/O，不是纯 DSP 时间**。

已改为 marker 控制（ARD_DUMP），默认关闭。修复后设备上 `grep -c 'C2 IN'` = 0，确认生效。

## 十、当前状态

- 修复后的 APK 已安装，dump 已关闭（已验证）
- warm-3 测量（同一进程连跑 3 次合成）已启动，但 app 卡在 INIT_07 与 INIT_08 之间
  （EMBCHECK 读 1.2 GB safetensors 阶段）超过 5 分钟，未能完成
- 下一步：先确认 EMBCHECK 为何偶发卡死；它是纯 CPU 读盘，与 DSP 无关
## 十一、三条合成同一进程连跑 —— 冷/热模型也不成立

dump 已关闭（日志确认 `C2 AR dump DISABLED (default)`），同一进程连跑 3 次合成：

    #1  prefill=11794.6  slow=7093.9  fast=9757.2  codec=7713.6  total=36362.6  RTF=12.234
    #2  prefill=15813.5  slow=6787.5  fast=9198.2  codec=7687.8  total=39491.4  RTF=13.287
    #3  prefill=15856.7  slow=5363.8  fast=6996.2  codec=7494.9  total=35712.0  RTF=12.016

**第 2、3 次没有变快，prefill 甚至更慢。** 所以：
- AR dump 不是主因（已关，数字没变好）
- **「冷启动预热后能快 4.7x」的模型不成立**（至少对 in-app 合成路径不成立）

## 十二、仍未解释的核心矛盾

    路径                 slow 单价
    nativePerfSplit      15.6 ms/exec   (A2, repSlow p50)
    in-app 合成          96~114 ms/frame (#1~#3)
    倍数                 6~7x

两条路径绑同一批张量、跑同一个 graph。差异只在：
1. slowStepRaw 用**外部给定的固定 cache**；in-app 用 slowCache.buildBank 动态构建
2. in-app 还要 build_rope_matrix + 采样 + 日志

但 1+2 按 F4 的 host 计时只值 ~16-21 ms，解释不了 6~7x。

**结论：在解释清楚这个矛盾之前，任何 RTF 数字都不可采信。**
我此前给出的「热态 RTF ≈2.4」也一并撤回——它建立在 nativePerfSplit 的 15.6 ms 上，
而 in-app 的真实数字是 12~13。

另记一个 app bug：`nativeInit` 没有用 g_mtx 保护，
`PERF_SPLIT` 与 `AUTO_RUN` 两个 marker 同时存在时会有两个线程并发 nativeInit，
各申请 ~1.8 GB，导致进程卡在 S(sleeping)、VmRSS 25 秒不变。去掉一个 marker 即可绕开。
