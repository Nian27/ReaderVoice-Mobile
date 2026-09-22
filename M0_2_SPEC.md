# M0-2 规格冻结（36 spans + 多音色 condition）

日期：2026-09-16    状态：**语料已抽，等 reference voice 资产**

## 1. 语料（已完成）

来源：E:\小说\山河镇狱 真实小说，按 book-level 分三本：

    bookA = 第一卷（30 章）
    bookB = 第二卷（90 章）
    bookC = 第三卷（90 章）

每本 12 条 = 4 旁白 + 4 单人对话 + 4 旁白/对话混合；优先**同章连续短段落**，不把句子打散随机抽。

产出：runs/audio8-m0/m0_spans.json
    spans_digest = e8832b8c29e4ed254c51a3fafa4b84e1158d4748a9f32230f76659c6cc7875b8

    bookA 可用池  narration 485 / dialogue 61 / mixed 40
    bookB 可用池  narration 1206 / dialogue 88 / mixed 239
    bookC 可用池  narration 1251 / dialogue 11 / mixed 43    <- bookC dialogue 偏少，需人工确认抽样

抽取脚本：scripts/m0_pick_spans.py
注：段落长度下限必须 <= 60（用 120 会把单人对话池清空：bookA 只剩 8 条）。

## 2. 音色 condition（待补）

规格：>= 3 个 reference voice，每个 >= 8~10 条；同一文本至少几组不同 voice；
同一 voice 覆盖多种文本。目的是验证 student 学的是 (text + voice conditioning) 而不是
把某个音色和某批文本绑定死。

现有资产：
    p1-runner/preset_voice_01/reference_codes.bin + reference_text.txt   <- voice 1（设备在用）
    artifacts/runs/audio8-minimal-app-20260824-124903/host-voices/female_zh/codes.npy  <- voice 2（候选）

**编码器已确认存在**，这是多音色的解锁点：
    runs/audio8_feasibility/model/codec_encoder_fp16.onnx (+.data 414MB)
    runs/audio8_feasibility/model/registration/codec_encoder_fp16.onnx

=> 只要有 reference 音频 + 其对应文本，就能编出新的 reference_codes。

**缺的是 reference 音频与转写文本。** 需要 2~3 段（每段 ~3.5s / 75 帧以上）带转写的音频。

## 3. voices/ 目录结构（冻结）

    voices/
      voice_001/
        reference.wav
        reference_text.txt
        reference_codes.npy
        meta.json
      voice_002/ ...

sample 只引用 reference_id，不复制音频。

## 4. sample meta.json 字段（冻结）

    已有: prompt_shape / frames / seed / precision / model_fingerprint / manifest
    新增:
      book_id            第一卷/第二卷/第三卷
      chapter_id         int
      source_span_id     bookA-ch002-p000
      span_kind          narration | dialogue | mixed
      voice_id           voice_001
      reference_id       voice_001
      reference_text     参考音频对应的转写文本
      reference_audio_sha256
      reference_codes_sha256
      teacher_model_digest     slow_ar_fp16.onnx 的 sha256
      prompt_digest            prompt.npy 的 sha256

## 5. DISTILLATION INVARIANT（写进 ExecPlan）

    Student must preserve the teacher's complete voice-conditioning protocol.
    No distillation stage may collapse, remove, average, or bypass
    reference-voice conditioning.

配套 Gate（不能只有 logits cos / hidden cos / codes agreement）：

    1. 同 reference / 不同 text   -> speaker similarity 保持
    2. 不同 reference / 同 text   -> 声音必须可区分
    3. Teacher vs Student         -> speaker embedding cosine
    4. VoiceDesign 生成的新 reference（训练集没见过）-> Student 仍能跟随

第 4 条是硬要求：**不能把音色克隆蒸馏成有限 speaker ID 分类**，
必须保留 open-set reference conditioning。

## 6. book-level split（M0-3 正式生成 split_manifest.json）

    同一本书绝不跨 train/val/test。
    M0-2 只用 3 本做 smoke；M0-3 起数据管线不允许 sample 自己随机 split。

## 7. 当前阻塞

M0-2 的文本侧（36 spans）已完成；音色侧需要 2~3 段**带转写的 reference 音频**。
在此之前，M0-2 只能以单音色跑通管线，无法满足 (text + voice) 交叉验证的规格。
