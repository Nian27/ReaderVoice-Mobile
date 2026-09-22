# M1 Round 5 — 建立 speaker Gate，并发现音色池的根本约束

日期：2026-09-16    状态：Gate 可用；音色池不足，需决策

## 1. WavLM speaker encoder 建立并验证

    模型: microsoft/wavlm-base-plus-sv  (405 MB, 经 hf-mirror 下载)
    放置: assets/local/wavlm-base-plus-sv
    脚本: scripts/m1_gate_speaker.py

**必须先做控制实验**（否则无法区分「数据同人」与「embedding 不可分」）：

    SAME speaker (同音频切两半)  cos = 0.986 0.914 0.916 0.911 0.951   mean 0.936
    DIFF speaker                cos = mean 0.641  min 0.411  max 0.886
    分离度 gap = 0.295

=> encoder 可分；**阈值应在 0.80~0.85，不是 0.60**。

### 一个正确的用法纠正

先用 `WavLMModel.last_hidden_state` 求均值当 speaker embedding -> off-diagonal 0.837~0.918，不可分。
正确用法是 **`WavLMForXVector`**（TDNN + attentive stats pooling 的 x-vector 头）。

## 2. 重大发现：官方 demo 的 reference 音色只有极少数不同说话人

用 x-vector 对音色聚类（thr 见下）：

    voices01b (15 个中文 reference，来自 docs/0.1B/audio/reference/zh_01..15)
        thr=0.75 -> 2 簇
        thr=0.80 -> 2 簇
        thr=0.85 -> 3 簇
        thr=0.90 -> 5 簇   (0.90 已高于同人下界 0.936 附近，不可信)

    voicesml (11 个多语言 reference，来自 docs/multilingual/reference)
        thr=0.80 -> 2 簇
        thr=0.85 -> 4 簇

    voices (最早的 8 个)
        {voice_001, voice_008} 一簇；{voice_002..007} 另一簇

**结论：官方 demo 的 reference 素材实际只覆盖约 4~6 个不同说话人。**
它们是 demo 资产，由少数配音员录制。

## 3. 这对目标的影响（必须上报）

用户要求：
    >= 3 个 reference voice，每个 >= 8~10 条；同文本至少几组不同 voice
    并把音色分为 train / val / open-set，open-set 至少 3~5 个从未进训练

现实中：
    可用不同说话人总数约 4~6
    若 open-set 占 3 个，训练只剩 1~3 个 -> 不满足「>=3 个训练音色」

=> **G-separation / G-open-set 目前在素材上不可满足。**
   这是资源约束，不是实现问题。

## 4. 新注册的音色池（已落盘）

    runs/audio8-m0/voices01b/   15 个中文 reference (v01b_001..015) + codes + 转写
    runs/audio8-m0/voicesml/    11 个多语言 reference (ml_zh/nl/en/fr/de/it/ja/ko/pl/es/yue)
    脚本: scripts/m0_register_01b_voices.py / m0_register_ml_voices.py

    注意 ml_zh 与 voice_001 是同一素材（302 帧 / 14.02s）。

## 5. 建议的解法（需用户决策）

A. **接受 4~6 个说话人**，把 open-set 缩到 1~2 个，Gate 相应放宽
   （代价：G-open-set 的说服力下降）
B. **引入外部中文语音做 reference**（Apache/CC 授权的公开语料，如 AISHELL-3 子集、
   Common Voice zh），用 codec_encoder 注册；这能轻松拿到 10+ 个不同说话人
   （代价：需要额外下载，且需确认授权）
C. **用 VoiceDesign 生成新音色**（本机有 Qwen3-TTS-1.7B-VoiceDesign），
   作为 open-set 的来源 —— 这恰好也是用户最终想要的场景

我倾向 **B 补足训练/验证音色 + C 提供 open-set**，因为这正对应真实产品里
「VoiceDesign 设计出从没见过的新音色」的用法。

## 6. 下一轮

1. 等 m0v2 数据重生成完成（后台进行中）
2. 按用户决策补足音色池
3. 重新组织 split（train/val/openset 三维：book × voice × span_kind）
