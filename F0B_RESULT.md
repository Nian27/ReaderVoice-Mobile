# F0-B 结果：A ↔ B（纯 FP16 语义桥）

## 实验设置（严格单变量）

```
A = 官方 FP16 full Slow   models_fp16/slow_ar_fp16.onnx          valid_prefix cache, 2048, multi-token prefill
B = 当前端侧微图 FP16     artifacts/micrographs/slow-ar-fixed-decode-w256-a16w8-v8-conv2d-perchannel-head/
                          slow_ar_fixed_decode_w256_fp16.onnx    右对齐 255 槽, host 构造 x/rope/mask

无 QNN、无 HTP、无 A16W8、无手机。两边都是 FP16 ONNX，ort ENABLE_ALL。
prompt = cells/RG/prompt.txt (T=132)，只跑第一帧。
```

## 结果：**FAIL**

```
官方第一帧 codes = [3571, 843, 990, 923, 539, 856, 476, 344, 924, 1019]
官方 step logits argmax=2946 max=32.6207 rms=9.5196   hidden rms=2.5614
微图      logits argmax=3324 max=12.5938 rms=4.0099   hidden rms=2.1138

logits       cos = 0.543856   maxabs = 25.45557
slow_hidden  cos = 0.562903   maxabs = 14.06250
key_delta    cos: min=0.1901  mean=0.4604   (L0=0.4245  L23=0.5736)
value_delta  cos: min=0.2983  mean=0.6149   (L0=0.9990  L23=0.7202)
semantic     官方 top1=2946  微图 top1=3324
```

## 读数

1. **在完全不涉及 QNN 的前提下，A↔B 就不成立。** 这直接支持你的判断：
   `0.5068` 那个数字**不能**用来指控量化/NPU。
2. **一个强线索**：L0 的 `value_delta cos = 0.9990`（几乎完美），而同层 `key_delta cos = 0.4245`。
   V 通路不含 RoPE，K 通路含 RoPE ⇒ 差异集中在 **RoPE / K 通路**，而不是整体权重或 FFN。
3. RoPE 约定已核对：微图构造脚本 `prepare_fast_ar_layer0_pos1.rope_matrix` 与 host 侧
   `audio8_engine.h::build_rope_matrix` **索引摆放完全一致**（唯一差别是构造脚本对 cos/sin 做了 bf16_round）。
   所以不是“我用了错的 rope 公式”。

## 尚未排除的一项（必须先排掉再定罪微图）

**这次 A→B 的输入是我按 host 约定手写的桥**（x / rope_position / attention_mask / cache bank 右对齐）。
虽然逐条对照了 `audio8_engine.h`（`buildBank` 的 `((SLOTS-n+p)*2+h)*64+d`、
`validStart=256-(n+1)`、`build_rope_matrix`），但**桥本身没有被独立验证过**。

正确的做法（下一步，仍然纯 FP16、几分钟）：

```
用微图构造脚本自带的 fixed_inputs(position, source, rope_base, window, mask_value, mask_mode)
（prepare_slow_ar_fixed_decode.py:463）来生成 B 的输入，
把 source 换成官方 FP16 在 position=T 的真实状态（x / cache / deltas）。
这样桥即“构造者本人写的桥”，A↔B 才是对微图的干净判决。
```

若换成 `fixed_inputs` 后仍然 cos≈0.5 ⇒ **微图/导出/桥接本身没有复现官方 Slow**，重新量化毫无意义。
若换成 `fixed_inputs` 后 cos≈1.0 ⇒ 是我的手写桥错了，需修桥后重测。

## 顺带发现的一个真实缺陷（已确认，与本 Gate 无关但需修）

`scripts/int4_to_fp16.py` 对 10 个 `GatherBlockQuantized` 节点各生成了一份同名 initializer
`model.codebook_embeddings.weight_Q4_fp16`（原图是 **1 张 [40960,896] 堆叠表**，10 个节点只是用不同
`/Add_k_output_0` 偏移去 gather）。结果：

```
同名 initializer 重复 10 份 = 浪费约 660 MB（models_fp16 的 1.74GB data 里有 660MB 是重复）
ONNX 要求 initializer 名唯一 -> 属违规，ORT 目前容忍（FP16 全链仍能出声，已由试听确认）
```

修法：转换时按 `n.input[0]` 去重，只建一次初始值。

## 下一步（严格顺序）

```
F0-B'  用 fixed_inputs 重建桥 -> 重跑 A↔B            🔥 现在
F0-C   同法做 Fast（pos0 prime + cb1..cb9）           A↔B 通过后
F1     B(FP16 微图) ↔ C(手机 A16W8 QNN)              仅当 F0-B'/F0-C PASS
F2     重转 QNN / 调 calibration                     仅当 F1 FAIL

新 Slow 设计（2048 + multi-token + valid_prefix）冻结为 V2 架构方案，不在本轮 root-cause 路径上。
```
