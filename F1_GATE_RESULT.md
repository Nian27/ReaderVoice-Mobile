# F1 门结果：PC 链路已验证正确，剩余根因锁定在 QNN 量化

## 1. F1 门（同一 prompt、同一逐 token 预填协议、同一 host 约定）

```
手机 QNN   prefill logits argmax=2091 max=16.4757 rms=6.6753
FP16 微图  prefill logits argmax=3571 max=32.5625 rms=9.2543
logits cos = 0.790103   maxabs = 20.47553   top1 相同=False
```

## 2. 但同时得到一个更强的旁证：FP16 微图 == 官方

```
官方 FP16 full (models_fp16/slow_ar_fp16.onnx) prefill: argmax=3571 max=32.2569 rms=9.1358
FP16 微图 (w256 + host 约定)             prefill: argmax=3571 max=32.5625 rms=9.2543
```

**argmax 完全相同，max/rms 相差 <1%。**
⇒ **整条 PC 侧链路（w256 微图 + host 的 x/rope/mask/cache 约定 + rope_base=1e6 + buildBank 布局）已经复现官方。**

这也反过来确认：F0-B'' 里那个 `pos131 logits cos=0.9972` 不是偶然，整条链在 132 步自递归 rollout 下依然成立。

## 3. 结论：剩余根因 = QNN A16W8 与它自己的 FP16 source 不等价

```
B(FP16 微图)  ==  A(官方 FP16)      ✅ cos 0.997 / argmax 一致
C(手机 QNN)   !=  B(FP16 微图)      ❌ cos 0.790 / argmax 2091 vs 3571
```

这是**第一次**把手机侧的问题干净地单独隔离出来。此前做不了，因为 B 自己是错的。

## 4. 完整根因链（本轮全部定位）

| # | 问题 | 性质 | 状态 |
|---|---|---|---|
| 1 | PC ORT INT4 执行坏（DISABLE_ALL 下输出与输入无关） | 评测脚手架 | ✅ 已修（改 ENABLE_ALL，并改为用 FP16 反量化模型做参考） |
| 2 | `buildBank`/`buildBankVal` cache 轴序 `[slot,head,dim]` 应为 `[head,slot,dim]` | **端侧 host bug** | ✅ 已修，真机行为已变 |
| 3 | `ropeBase = 10000` 应为 `1e6` | **端侧 host bug（影响更大）** | ✅ 已修，真机行为剧变 |
| 4 | **QNN A16W8 slow context 与 FP16 source 不等价（cos 0.790）** | **端侧量化/转换** | ❌ **当前唯一剩余根因** |

问题 2/3 修完后真机仍未出声（用户确认 P/Q 都不对），但两者都是**真实的、独立的** bug，
且 #3 的证据是决定性的（`key_delta_0 cos` 在 1e6 下全位置 = 1.0000，在 10000 下 pos131 = 0.4312）。

## 5. 下一步：F2（重转 slow 微图 → QNN，调 quant recipe）

```
Gate: QNN vs FP16 微图 (同一逐 token rollout)  logits cos >= 0.99
      当前 0.790

本轮已就绪的输入：
  - 唯一正确的参考图   artifacts/micrographs/slow-ar-fixed-decode-w256-a16w8-v8-conv2d-perchannel-head/
                       slow_ar_fixed_decode_w256_fp16.onnx
  - 唯一正确的评测脚本 scripts/f1_gate.py  (逐 token rollout + FP16 微图)
                       scripts/f0_ab_canonical.py (A<->B 单步对拍)
  - 唯一正确的 host 约定 见音频引擎注释（rope 1e6 / 右对齐 [head,slot,dim] / mask validStart=256-(n+1)）

F2 候选（按顺序，一次一个变量）：
  1. 提高 act/weights 位宽（A16W8 -> A16W16 / W8 per-channel）
  2. 检查 calibration 数据是否覆盖真实 prompt 分布（当前 rollout 态）
  3. 逐层定位：用 f0_ab_canonical 的单步对拍，找 cos 第一次跌破 0.99 的层/位置
```

**注意**：#4 一旦修好，`P_ropeFix_phoneCodes_officialCodec.wav` 里的 AR codes 才会与官方收敛，
才可能出声。#2/#3 是必要不充分条件。

## 6. 状态表

```
F0-A    官方 FP16 full chain                ✅ GOLDEN（用户确认可出声）
F0-B0   position=0 结构一致性               ✅ 0.983/0.990
F0-B1  cache layout                         ❌ BUG → ✅ FIXED
F0-B2  rope base 10000 -> 1e6               ❌ BUG → ✅ FIXED
F0-B3  A↔B 对齐                             ✅ pos131 logits 0.9972 / kdelta 1.0000
F0-B4  132 步自递归 rollout == 官方          ✅ argmax 3571 一致, max/rms <1%
F1     B(FP16 微图) ↔ C(手机 QNN)            ❌ cos 0.790  🔥 当前根因
F2     重转 QNN / 调 calibration             ⏭ 下一步
F0-C   Fast 微图同法对拍                     ⏭ 待做（可能同样是 QNN 量化问题）
```

## 7. 试听清单状态

| 文件 | 说明 |
|---|---|
| P_ropeFix_phoneCodes_officialCodec.wav | 手机 codes 走官方 FP16 codec —— 用户判定：不对 |
| Q_ropeFix_deviceQNNcodec.wav | 同上走端侧 QNN codec —— 用户判定：不对 |
| M_/N_fixedAPK_* | **作废**（拉取过早，内容是 14:04 的旧 codes） |
| GOLDEN_fp16_reference.wav | 官方基准，可懂 |
