# F3-HALO — Codec bucket/halo 质量研究（PC，官方 FP16，动态 T）

日期：2026-09-15
脚本：scripts/f3_halo_study.py
产物：runs/audio8-perf-f3/halo_result.json、halo_C*_H*.wav、halo_REF_fullT.wav
输入：runs/audio8-perf-f3/ar_codes.raw（设备真实 62 帧 codes）

## Gate 结论：**FAIL（halo 方案被证伪）** —— 但同时得到一条更重要的正面结论

## 一、halo 扫描结果（cos vs full-T 参考）

```text
C=8   H=0    cos_global=0.7905  cos_min=0.2598  bdisc=7.35
C=8   H=2    0.8895  0.7107   6.17
C=8   H=4    0.9570  0.8185   7.15
C=8   H=8    0.9594  0.8778   5.24
C=8   H=16   0.9695  0.9078   2.26
C=8   H=32   0.9932  0.9751   0.60   <-- 最好的非平凡配置

C=16  H=0    0.9173  0.8376   9.89
C=16  H=4    0.9587  0.8459  10.49
C=16  H=16   0.9757  0.9498   3.51
C=16  H=32   0.9956  0.9878   0.73   <-- 最好的非平凡配置

C=32  H=0    0.9615  0.9109  35.55
C=32  H=16   0.9863  0.9736   9.17
C=32  H=32   1.000000 1.000000 1.90  <-- 窗口覆盖全序列，等价 full-T
```

**判读：解码器的有效感受野 ≈ 64 帧量级。** 要让 cos ≥ 0.99，总窗口 (C+2H) 必须 ≥ 48~64 帧，
与 chunk 大小 C 基本无关。这与官方 runtime_manifest 的 `stream_context_frames: 128` 一致——
**官方 streaming 本来就用超大 context，T64 已经是"偏小"而不是"偏大"。**

## 二、为什么 halo 是死路（冗余算账）

```text
配置            窗口帧数   块数   总帧解码量   相对 full-T 冗余
full-T            62        1        62          1.0x
C=32 H=32         96        2       192          3.1x
C=16 H=32         80        4       320          5.2x
C=8  H=32         72        8       576          9.3x
```

感受野 64 帧 vs 希望 chunk 8 帧 => 冗余必然 ≥ 8x。**halo 不可能省算力，只会更贵。**
结论：**放弃 halo，Codec 保持大窗口（T64，甚至应该考虑 T128）。**

## 三、更重要的正面结论：T64 的 7.5s 是 HTP 后端问题，不是模型问题

同一份官方 FP16 ONNX，PC 上 ORT CPU 的**逐样本耗时随 T 完全线性**：

```text
T=4    0.1096 s   13.38 us/sample
T=8    0.2002 s   12.22
T=16   0.4115 s   12.56
T=32   0.8334 s   12.72
T=64   1.6664 s   12.71     <-- 与 T=4 同单价，无超线性
full-T(62)  1.535 s  12.09 us/sample
```

（第二次在机器同时跑 WSL context 构建时复测，绝对值升到 ~24 us/sample，但**线性关系不变**。）

对比设备 HTP：

```text
HTP  T=64 :  7557 ms / 131072 samples = 57.7 us/sample
PC   CPU  :  12.1 ~ 24 us/sample

=> HTP 比桌面 CPU 慢 2.4 ~ 4.8 倍
=> HTP 上"T64 比 4xT16 慢一倍"纯粹是 kernel/shape 选型问题，模型本身是 O(T)
```

**这条直接给出了 Codec 的解法方向：把 codec 挪出 HTP（CPU 或 GPU）。**
- 桌面 CPU 跑 62 帧 = 1.5 s（codec 单独 RTF ≈ 0.52）
- 即使手机 CPU 比桌面慢 2x，也是 ~3 s，仍优于 HTP 的 7.5 s

而 F4 已经证明 HTP 侧 codec 的 prep/readback 只占 0.03%，所以换 backend 是唯一有效手段。

## 三-B、必须纠正一个被广泛引用的数字

流传的对照是：

```text
4 × T16 ≈ 3.6 s
1 × T64 ≈ 7.5 s
"输入 4x，时间 8x" => 推断 T64 shape 在 HTP 上退化
```

**这个对照是错的，两个数不是同一个模型：**
- `3.6 s` 来自 **`codec_student_m5_trained_v1_ft2_t16`** —— 我们自己蒸馏出来的小 student
- `7.5 s` 来自 **官方 codec_decoder**（本次替换后）

小 student 与官方 decoder 参数量/结构不同，**不能用来推断 shape scaling**。

而 PC 上的直接实测已证明官方 decoder 是**严格线性**的（T=4..64 单价平坦 12~13 us/sample），
**不存在 T64 shape 退化**。7.5 s 的成因是"HTP 比 CPU 慢 2.4~4.8 倍"，不是"T64 特别差"。

### 由此得到的 halo 判据（用用户听感标定）

F3 早期诊断里 `segment cos = 0.9673 / 0.7074 / 0.5181` 的产物，用户听感是**"有点小问题"**。
所以 **cos ≈ 0.96 就是"听得出来"的区间**。

对照 halo 扫描（C=16）：
```text
H=2  cos_global=0.9352   <-- 落在听得出问题的区间
H=4  cos_global=0.9587   <-- 同上
H=8  cos_global=0.9634   <-- 同上，仍在 0.96 附近
H=32 cos_global=0.9956   <-- 只有这里才够，但冗余 5.2x
```

**=> halo=2/4/8 全部不达标；能达标的 halo 必然比 full-T 更贵。halo 方案彻底排除。**

## 四、一个必须记录的数据陷阱

**设备端 `files/models/ar_codes.raw` 是 int32，不是 float32。**

按 float32 读会得到 denormal（≈1e-42），`astype(int64)` 后**全部变成 0**，
脚本不会报错，会安静地产出"cos=0.9"之类看起来很合理的无意义结论（本次第一版就是这样跑出了
C=8 H=0 cos=0.9079 的假结果）。脚本已加入自动 dtype 判定 + 首值断言（首 code = 3571，与 F2-B 一致）。

## 五、复现

```text
python scripts/f3_halo_study.py
# 输入 codes 需要先从设备拉取：
adb exec-out "run-as com.readervoice.audio8qnnprobe cat files/models/ar_codes.raw" > runs/audio8-perf-f3/ar_codes.raw
```
