# Audio8 官方链路 · 根因确认与基准固化（2026-09-15 终版）

## 0. 根因（用户听感确认）

| 版本 | 是否含 INT4 计算 | 用户判定 |
|---|---|---|
| L_FP16_fullChain.wav | **否**（slow/fast 已离线反量化成 FP16） | **没问题 = 可懂人话** |
| A/E/F/G（同协议、同 prompt、同 codec） | 是 | 不行 |
| B（官方 codec 解真实 reference 码，纯 FP16） | 否 | 可以 |

==> **本机 ONNX Runtime 对 INT4(MatMulNBits/GatherBlockQuantized) 的执行是坏的。**
    一切 FP16 路径都正常，一切 INT4 路径都出噪音。

旁证（同一份官方 runtime.py 原文、同一 prompt，只换 ORT）：

| ORT | 帧数 | rms | acf(2048) |
|---|---|---|---|
| 1.23.2 | 66 | 0.0577 | 0.0259 |
| 1.20.1 / 1.21.1 / 1.22.1 | 256（不收敛） | 0.0040 | 0.9977（46Hz 蜂鸣） |
| 1.19.2 | 加载失败：com.microsoft:GatherBlockQuantized 未注册 | | |

以及 ORT_DISABLE_ALL 下 INT4 输出**与输入无关的常量**（T=30 与 T=132 的 logits md5 都是 9dd12252860d）。

## 1. 现在拥有的可信基准链路

```
models_fp16/
  slow_ar_fp16.onnx(+.data 1.74GB)    <- slow_ar_int4.onnx 离线反量化
  fast_ar_fp16.onnx(+.data 134MB)     <- fast_ar_int4.onnx 离线反量化
  codec_decoder_fp16.onnx(+.data)     <- 官方原件
  runtime_manifest.json               <- default/available precision = fp16
third_party/arktts_official/runtime.py   官方 runtime 原文（逐字）
scripts/run_official_runtime.py          驱动：MODEL_DIR=models_fp16 + 端侧 dump 的 prompt 矩阵
```

产出：可懂语音。**这就是 F1/F2 要逼近的目标。**

## 2. 反量化实现要点（scripts/int4_to_fp16.py）

| op | 量化位宽 | 打包 | zp 偏置（数据自动判定） |
|---|---|---|---|
| GatherBlockQuantized（embedding 表） | **8-bit**（不是 4） | 1 值/字节 | 无偏置（d=0.0091 vs 128.01） |
| MatMulNBits（线性层） | 4-bit | 2 值/字节沿 K | 无偏置（d=0.0013 vs 8.001） |
| 零点存储 | — | **2 值/字节沿 block 轴**，形状 [rows, ceil(nb/2)] | 与权重打包方式**不同**，易错点 |

## 3. C2 门（用有效基准重测）

端侧 QNN prefill dump vs 官方 FP16 prefill，同一份 prompt 矩阵：

```
官方 FP16 : argmax=3571  max=32.257  rms=9.136
端侧 QNN  : argmax=1766  max=15.720  rms=5.751
C2 logits      : cos = 0.5068   maxabs = 24.86
C2 slow_hidden : cos = 0.4071   maxabs = 13.50
```

=> **端侧 Slow 图确实与官方不等价（此前 0.5021 是在坏基准下测的，现在 0.5068，结论不变但这次基准可信）。**

## 4. QNN 差距的三个已知来源（可量化验证）

1. **cache 语义**：官方 = valid_prefix（按绝对位置写 cache[:,:,pos,:]=delta，实测 cos=1.000000）；
   端侧 = 滑动窗口左移（实测 cos=0.925844）
2. **prefill 形态**：官方 = 多 token 一次前向（sequence 维动态）；端侧 = 逐 token 132 次
3. **序列长度**：官方 = 2048；端侧 = 255 槽窗口

## 5. 下一步（F1）

```
用 slow_ar_fp16.onnx（不是 INT4！）重导 QNN context：
  - codes 输入 [1,11,sequence] 支持多 token prefill
  - cache 写回按绝对位置（valid_prefix），不要左移
  - 序列长度 2048
Gate: 同 prompt 下 QNN logits vs 官方 FP16 cos >= 0.99

F2  fast_ar_fp16.onnx 同样重导（fast cache 位置索引，pos 0..9）
F3  codec artifact 重测/重导（官方 FP16 codec 已知可用）
F4  端侧接官方采样器（代码已就绪：MODE_OFFICIAL，含双采样+重复判据）
F5  多 token prefill 顺带解决 P9-b 的 132 次 execute 开销
```

## 6. 本轮修复的两个真实环境坑（保留）

1. PC 侧脚本原先用 ORT_DISABLE_ALL -> INT4 输出退化成常量。全部改官方同款 ORT_ENABLE_ALL。
2. 官方 KV cache 是 valid_prefix 位置索引，不是左移窗口（实测 cos 1.000000 vs 0.925854）。

## 7. 试听清单

复制到 E:\AndroidStudioProjects\ReaderVoiceMobile\listen-kit-20260915\

| 文件 | 内容 | 判定 |
|---|---|---|
| L_FP16_fullChain.wav | FP16 官方全链路 | **没问题（正解）** |
| B_officialCodec_on_realReference75.wav | 官方 codec 解真实 reference 码 | 可以 |
| A/C/E/F/G | INT4 链路各变体 | 不行 |
| H/I/J | ORT 1.22/1.20/1.21 | 不行（46Hz 蜂鸣） |
| D | 手机 QNN | 不行 |
