# F2-B 进度：calibration 已扩到 position 0..131，QNN 前端转换 PASS（卡在 WSL context 生成）

## 1. 本轮定位到的单变量切口

现有 slow calibration 由 `scripts/prepare_slow_ar_fixed_runtime_calibration.py` 生成，参数为：

```
--sequence-count 2  --frames-per-sequence 16
⇒ 只覆盖 position 0..15
```

而 F2-A1/A2 测得的失效**恰恰随位置加深**：

```
pos0   logits cos 0.9941   layer0~3 全干净
pos16  logits cos 0.9790
pos32  logits cos 0.9393   layer6 起 K/V 明显退化
pos131 logits cos 0.9651   V 最低 0.8435 / K 最低 0.9483
```

⇒ **深层（cache 接近填满 255 槽）的激活量程从未进入 calibration。**

顺带核实（避免误判）：`mask_mode="renorm"` 在 `fixed_inputs` 里与 `"mask"` 等价（都是 0/1，
`mask_value=-10000` 被忽略）⇒ **掩码没问题**；`--rope-base` 默认已是 **1e6** ⇒ **rope 参数没问题**。
所以唯一缺的就是 **位置覆盖**。

## 2. 已完成

### (a) 生成 position 0..131 的 calibration 集

脚本 `scripts/f2b_calibration.py`，数据源 = **B(FP16 w256 微图) 的真实 rollout**
（B 已在 F0-B3/B4 证明 == 官方 FP16：argmax 3571 一致、max/rms <1%）。

```
输出：artifacts/calibration/slow-w256-realchain-v3-pos131/
  calibration_input_list.txt   132 行（每行 51 个 name:=path 绑定）
  *.raw                        6734 个文件，830.9 MB
  report.json                  positions [0,131] / window 256 / rope_base 1e6 / mask 0-1
```

### (b) QNN 前端转换 PASS

```
artifacts/qnn/slow-w256-v3-pos131-a16w8-20260915-183525/
  audio8_qnn_model.cpp / audio8_qnn_model.bin
  Total MACs: 369545472   Total Params Count: 361529217
```

命令：

```powershell
& scripts/02-convert-qnn.ps1 -Component slow_ar \
    -InputModel artifacts/micrographs/slow-ar-fixed-decode-w256-a16w8-v8-conv2d-perchannel-head/slow_ar_fixed_decode_w256_fp16.onnx \
    -CalibrationInputList artifacts/calibration/slow-w256-realchain-v3-pos131/calibration_input_list.txt \
    -ActBitwidth 16 -WeightsBitwidth 8 -BiasBitwidth 32 -UsePerChannelQuantization
```

## 3. 卡住的一步（需要 WSL）

`scripts/03-build-htp-context.ps1` **设计上直接 throw**：

```
"QAIRT Windows host cannot build an Android model library ... Run the Linux-host script through WSL instead"
⇒ scripts/wsl/03-build-htp-context.sh
```

历史可用的同类产物（说明这条 WSL 链路是通的）：

```
artifacts/qnn/slow_ar_fixed_decode_w256_realchain_conv2d_perchannel_v3-a16w8-20260824-162726   <- 疑似当前部署的 v8 来源
artifacts/qnn/slow_ar_fixed_decode_w256_realchain_perTensorW16_v5-a16w16-20260824-181740
artifacts/qnn/slow_ar_fixed_decode_w256_realchain_asymW16_v6-a16w16-20260824-183359
（每个下面有 model-lib/aarch64-android/libaudio8_qnn_model.so + htp-context.log）
```

## 4. 剩余步骤（照抄即可）

```bash
# 4.1 WSL 里生成 HTP context（把 QnnConversionDir 指到本轮新目录）
wsl bash E:/AndroidStudioProjects/audio8tts-mnn/scripts/wsl/03-build-htp-context.sh \
    /mnt/e/AndroidStudioProjects/audio8tts-mnn/artifacts/qnn/slow-w256-v3-pos131-a16w8-20260915-183525
# 产出 htp-v81-context/*.bin（约 366 MB，对应设备上的 slow.bin）
```

```powershell
# 4.2 推上设备（adb 端点 10.40.131.244:41487）
$adb = "C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$pkg = "com.readervoice.audio8qnnprobe"
& $adb push <<new context bin>> /data/local/tmp/slow_v3.bin
& $adb shell "run-as $pkg cp /data/local/tmp/slow_v3.bin files/models/slow.bin"
# 备份旧 slow.bin 先！

# 4.3 复跑 canonical 单步对拍（本轮新增能力，已编译进 APK）
$env:PYTHONPATH=""
$env:POS="0,8,32,131"
& "C:/Users/Administrator/AppData/Local/Programs/Python/Python310/python.exe" \
    E:/AndroidStudioProjects/audio8tts-mnn/scripts/f2a1_phase2.py
```

## 5. Gate（双判据，不要只看 cos）

```
升级前（本轮实测）：
  pos131  QNN argmax=255    B argmax=3571   cos 0.9651   <- FAIL
  pos32   QNN argmax=3962   B argmax=689    cos 0.9393   <- FAIL
  pos0    QNN argmax=3765   B argmax=113    cos 0.9941   <- FAIL
  9 个位置只有 pos1/4/16 的 argmax 一致

升级后 Gate：
  1) 所有测试位置 logits cos >= 0.99
  2) argmax 一致率 >= 0.95
  3) 最终：C 自递归 rollout（f1_gate.py）cos 从 0.790 提到 >= 0.99
```

## 6. 为什么先做这一步而不是全图 A16W16

按预设判读分支，F2-A1 已经命中「canonical 单步高 + self-rollout 低 → calibration/state 分布问题」，
所以**先补真实 rollout calibration**，而不是加位宽。这样做：

- 单变量（只改 calibration 覆盖）
- 可证伪（若 argmax 仍错，则说明不是覆盖问题，再考虑位宽）
- 不浪费（历史上的 a16w16 路线（v5/v6）体积翻倍但并未解决，说明位宽不是瓶颈）

## 7. 状态表

```
F0-A/B  语义桥 A == B(微图)                  ✅ argmax 3571 一致, max/rms <1%
F1      deployed Slow QNN 自递归             ❌ 0.790
F2-A1   canonical 位置隔离                   ✅ 单步 0.939~0.999 → 状态累积分支
F2-A2   首个坏层定位                          ✅ L0~3 干净, ~L6 起累积退化, V 比 K 差
F2-B-1  扩位置覆盖的 calibration 生成          ✅ 132 样本 / 0..131 / 830.9 MB
F2-B-2  QNN 前端转换                          ✅ PASS (361.5M params)
F2-B-3  HTP context 生成 (WSL)                ⏳ 需要 WSL 步骤
F2-B-4  推设备 + re-run canonical Gate        ⏳
F2-C    C 自递归 rollout Gate >= 0.99         ⏳
Fast F0-C                                    ⏸ Slow 修完后
```

## 8. 设备状态

```
adb 端点 10.40.131.244:41487   (serial A3TE025B03242, BKQ-AN90)  已连接可用
App 内已装：nativeCanonicalStep + slowStepRaw/readDelta + SIMD 修复后的 buildBank/ropeBase=1e6
files/CANON marker 驱动，无需 UI
```
