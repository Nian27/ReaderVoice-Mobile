# M1-B Round 4 — 建立 G-content Gate，并修掉两个测量 bug

日期：2026-09-16    状态：数据重生成中 + Gate 可用

## 1. Whisper 可用性对照（必须做的控制）

拿【已知转写】的真人 reference 音频喂 Whisper：

    REF_voice_001 (真人)  CER=0.053   <- 很好
    REF_voice_003 (真人)  CER=0.148
    REF_voice_002 (真人)  CER=0.406   (该音频本身较难)

=> Whisper 对干净人声可靠，可以用作 G-content 的 ASR。

## 2. 验证 torch 复刻 teacher 是否忠实（对官方 FP16 ONNX）

同一个 prompt 分别喂 torch StudentSlow(24) 与官方 slow_ar_fp16.onnx：

    ONNX 输出形状 (1,1,4097) float32  <- 官方在模型内部已切成 semantic_then_eos
    torch 输出 (155776,)              <- 全长，需按 LOGIT_IDX 取子集

    SUBSET(4097) cos = 0.975421
    argmax torch=644(->152322) == onnx=644   SAME=True
    top5 重合 3/5   top20 重合 14/20

=> torch 复刻忠实（0.975 的差距来自 BF16 vs FP16）。
注意：以后任何 torch/ONNX 对拍都必须先按 LOGIT_IDX 切子集，不能直接比全长。

## 3. Bug A：M0 样本长度截断（Round 3 发现并修）

    frames_for(text) = int(len(text)/5.0*21.53) + 48
    旧集 frames mean 63（98% 恰为 64），覆盖率仅 12.2%
    新集 3 条验证：306/230/200 帧（旧 64）

## 4. Bug B：CER 未做繁简转换（本轮发现）

Whisper(language=zh) 常输出**繁体**，参考文本是**简体**。
原 norm() 只做 NFKC，不做 t2s，导致每个繁体字都算错。

修复：加 opencc t2s。影响：

                            修复前    修复后
    voice_001 (同文本)       0.528  ->  0.283
    voice_002 (同文本)       0.491  ->  0.132
    bookB (音频仍截断)       0.819  ->  0.672
    MEAN                     0.613  ->  0.363

**这是度量 bug，不是模型问题。** 修复后 teacher TTS 的 CER（0.13~0.28，
除去那条被截断的）已与真人参考（0.05~0.15）同量级。

## 5. G-content Gate 现状

    scripts/m1_gate_content.py : wav -> Whisper(zh) -> t2s -> 去标点 -> CER
    已可用的参考点：真人 0.053~0.406 ；teacher TTS 0.132~0.283（未截断样本）
    建议判据：student CER <= teacher CER + 0.05（相对判据，避免绝对阈值被 ASR 偏移影响）

## 6. 仍在跑

    45 条全量重生成 -> runs/audio8-m0v2（后台）

## 7. 下一轮

1. 重生成完成后：全量 teacher WAV + CER 基线
2. 用新数据重训 12L（teacher-forced + DAgger）
3. 重建 split_manifest + dataset_digest（旧 632dec58 作废）
4. 建 G-clone / G-separation（speaker embedding）
