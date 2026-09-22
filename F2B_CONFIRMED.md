# F2-B/F2-C 结果：calibration 假设 CONFIRMED（自递归 rollout 0.790 → 0.9978）

## 唯一变量

```
只改了 calibration 的【位置覆盖】：
  旧: --sequence-count 2 --frames-per-sequence 16   => position 0..15
  新: 132 样本覆盖 position 0..131（真实 rollout 状态）

未改：位宽(A16W8, per-channel 不变)、host 代码、微图、rope、mask、QNN 前端参数、QAIRT 版本(2.48.40.260702)
```

## 产物链

```
scripts/f2b_calibration.py
  -> artifacts/calibration/slow-w256-realchain-v3-pos131/   (132 样本 / 6734 文件 / 830.9 MB)
scripts/02-convert-qnn.ps1  (A16W8, per-channel, bias 32)
  -> artifacts/qnn/slow-w256-v3-pos131-a16w8-20260915-183525/audio8_qnn_model.cpp+.bin
     MACs 369,545,472  Params 361,529,217
wsl scripts/wsl/03-build-htp-context.sh  (ANDROID_NDK_ROOT=/home/vicentrent/toolchains/android-ndk-r27d)
  -> htp-v81-context/audio8_htp.bin.SM8850.bin   366,048,568 B
     SHA256 f114699248c7446d5bf07aa415c29c664d1aacf772f1e1531bac06d1bc2851b1
设备：am force-stop -> 备份 slow.bin.pre_f2b (c1cb4848...) -> cp 新 bin -> sha256sum 核对一致
```

## F2-B-4 canonical Gate（升级前 → 升级后）

```
pos  | QNN argmax | B argmax | logits cos            | hidden cos
0    | 3765       | 113      | 0.994158 -> 0.995248  | 0.993236 -> 0.995890
8    | 2247       | 2247     | 0.991185 -> 0.992625  | 0.992434 -> 0.995368
32   | 3962       | 689      | 0.939275 -> 0.995912  | 0.952796 -> 0.996184   (+0.057)
131  | 3571       | 3571     | 0.965117 -> 0.998732  | 0.929393 -> 0.996568   (+0.034)

4/4 位置 cos >= 0.99   ✅
pos131 argmax = 3571  == B == 官方首帧 semantic   ✅（升级前是 255）
```

## F2-C 主 Gate（自递归 rollout，f1_gate.py）

```
手机 QNN  prefill logits argmax=3571 max=31.5701 rms=9.2394
FP16 微图  prefill logits argmax=3571 max=32.5625 rms=9.2543
logits cos      = 0.997808   (升级前 0.790103)
maxabs          = 2.19453
top1 相同        = True
slow_hidden cos = 0.993048
```

**0.790 -> 0.9978，argmax 完全一致。**

## 音频侧证据（与官方 golden 的统计画像对比）

```
                     rms       peak      acf(lag1,16,128,512,2048)              flat     zcr
官方 golden (L)      0.05769   0.43097   [0.9862,-0.2866, 0.1617,-0.0651,0.0259] 0.0569   0.0865
新 context (R)       0.06050   0.48793   [0.9876,-0.3196, 0.1869, 0.0144,0.0251] 0.0541   0.0836
旧 context (P)       0.02035   0.09077   [0.9948, 0.2980, 0.3144, 0.2135,0.0793] 0.0331   0.0244

帧数            62（cb0 唯一值 56/62，无固定点循环）    旧: 64 帧 / cb0 唯一 21/64
首帧 cb0        3571 == 官方 golden 首帧 3571
```

新的 rms/peak/flat/zcr 与官方 golden **基本重合**；旧版的 rms/peak 低 3 倍、zcr 低 3.5 倍、
acf 高频段形态完全不同（那是嗡鸣，不是语音）。

## 结论

```
calibration coverage hypothesis  -> CONFIRMED
根因链第 4 项（QNN 与 FP16 source 不等价）的主因 = calibration 位置覆盖不足，并非位宽不足
```

这也与历史一致：`perTensorW16_v5` / `asymW16_v6` 两条 a16w16 路线体积翻倍却未解决问题 ——
**位宽不是瓶颈**。

## 试听（工作区 listen-kit-20260915/）

| 文件 | 内容 |
|---|---|
| **R_f2b_phoneCodes_officialCodec.wav** | 新 context 手机 codes 走官方 FP16 codec（隔离 AR） |
| **S_f2b_deviceQNNcodec.wav** | 同一批 codes 走端侧 QNN codec |
| GOLDEN_fp16_reference.wav | 官方基准 |

## 后续（按你的意见，本实验只做单变量，以下不冻结）

```
1. 若 R 已可懂 -> calibration 方向冻结，但【不要】就此冻结最终 calibration：
   w256 的完整运行分布要到 position 132..255 以及满窗口 steady-state。
   下一版正式 calibration 应覆盖 early 0..15 / mid 16..127 / deep 128..254 / full 255，
   更理想是直接采真实完整 synthesis rollout（含 prompt 之后生成的帧）。
2. 若 R 仍不可懂 -> 再查 Fast（F0-C）与 codec 侧；Slow 侧已到 0.9978 不是瓶颈。
3. Gate 排序（按你的建议）：self-rollout cos >= 0.99 优先，其次 canonical cos，
   最后才看 argmax；argmax 不一致时先查 top1-top2 margin 再判 FAIL。
```

## 状态表

```
F0-A/B  语义桥 A == B(微图)              ✅ argmax 3571 一致, max/rms <1%
F1      deployed Slow QNN 自递归          ❌ 0.790  ->  ✅ 0.9978
F2-A1   canonical 位置隔离               ✅ 单步 0.939~0.999 -> 状态累积分支
F2-A2   首个坏层定位                      ✅ L0~3 干净, ~L6 起累积退化, V 比 K 差
F2-B-1  扩位置覆盖 calibration            ✅ 132 样本 / 0..131
F2-B-2  QNN 前端转换                      ✅ PASS
F2-B-3  HTP context (WSL)                ✅ PASS  SHA f1146992...
F2-B-4  canonical Gate                    ✅ 4/4 cos >= 0.99, pos131 argmax 3571
F2-C    self-rollout Gate                 ✅ 0.9978 (was 0.790)
F2-D    试听 R/S                          🔥 现在
Fast F0-C                                ⏭ Slow 定案后
```

## 回滚

```powershell
& $adb shell "run-as com.readervoice.audio8qnnprobe cp files/models/slow.bin.pre_f2b files/models/slow.bin"
```
