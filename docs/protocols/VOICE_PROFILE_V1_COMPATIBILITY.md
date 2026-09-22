# VOICE_PROFILE_V1_COMPATIBILITY.md — CosyVoice3-MNN 零样本接入契约（M0.5）

**日期：2026-08-20** ｜ 状态：**FROZEN（M0.5 Voice Interface Check 产物）** ｜ 来源：E 盘 CosyVoice3-MNN-formal v1.1.0（README/RELEASE_NOTES/MEMORY）、mnn-cosyvoice3 lab（VOICE_ENROLLMENT_AND_INSTRUCT_PLAN.md / RESEARCH_MEMORY.md）、CosyVoice-main。

> 本文件是 **VoiceFactory（TASK-RV-045）的硬约束**：任何 VoiceProfile 若不能满足本契约，就无法被 CosyVoice3-MNN 消费。生成器（VoiceDesign）只需满足"产生稳定 reference wav"，其余全部由本契约定义。

## 1. 运行时音色档案（VoiceProfile 落盘格式，冻结）

每个音色一个目录，两种命名并存（**正式 App v1.1.0** 与 **lab 规划** 是同一内容的两种命名，本契约以正式 App 为准，lab 名作注释）：

```text
voices/<profile-id>/
  profile.json                    # 元数据（必含：modelId/promptText/promptTokenCount/promptFrameCount/speakerEmbeddingSize/profileHash）
  prompt-speech-tokens.csv        # 参考语音 token（逗号分隔数字）  [lab: prompt-speech-tokens.i32]
  prompt-cond.bin                 # 参考语音 Conditioner 输出（80ch × frame × 4B）  [lab: runtime-prompt-cond.f32]
  spks.bin                        # 说话人嵌入（运行时 80ch × 4B = 320B）  [lab: runtime-flow-spks.f32]
  source.wav（可选，仅开发资产）    # 原始参考音频
  rand-noise.bin（symlink）        # 指向模型目录共享噪声
```

**profile.json 必含字段**（lab 口径，冻结）：

```json
{
  "schemaVersion": 1,
  "modelId": "Fun-CosyVoice3-0.5B-2512-RL-distilled",
  "voiceId": "uuid",
  "displayName": "药老",
  "promptText": "参考音频的准确文本",
  "promptTokenCount": 87,
  "promptFrameCount": 174,
  "speakerEmbeddingSize": 80,
  "profileHash": "sha256",
  "createdAt": 0
}
```

**硬约束**：
- `modelId` 必须与引擎模型匹配（档案不跨模型版本混用）。
- `promptTokenCount ≤ 125`（实时限制，MP3 创建超限必须"实时版"截断）；`promptFrameCount = 2 × promptTokenCount`。
- `spks.bin` 运行时固定 **80 维 float32**（320B）；**192 维 CAM++ 原始 embedding 是开发资产，不直接进运行时**（需经 affine 投影）。

## 2. 注册（enrollment）输入链（冻结）

```
reference wav（3-15s，单人、无配乐、无削波；MP3 经 MediaCodec 解码）
  ├─ 16kHz → Whisper 128-bin Mel → speech-tokenizer-v3 → prompt speech tokens（≤125）
  ├─ 16kHz → Kaldi 80-bin fbank（均值归一化）→ CAM++ → 192-dim speaker embedding
  │        → affine 投影（flow-speaker-affine weight/bias）→ 80-dim runtime spks
  └─ 24kHz → 80-bin Mel → prompt speech feature（prompt-feat）
       → Conditioner → runtime-prompt-cond（80ch × frames）
```

- token 数限制：实时 prompt ≤125 tokens / 250 frames；注册时超限截取前 125。
- 对齐：`prompt_frames = 2 × prompt_tokens`，多余裁掉。
- 注册是**低频离线**操作（PC 优先；手机端 30-60s 实测），实时合成**不得**再跑 tokenizer/CAM++/Mel 提取（只加载档案）。
- JNI 入口（CosyVoiceEnrollmentJni）：`enroll(tokenizerModelPath, campPlusModelPath, affineWeightPath, affineBiasPath, sourceWavPath, outputDirectory, threads)`；错误码 10-42（10=WAV 不可读、11=时长越界、20-28=tokenizer、30-37=说话人、40=token 超限、42=写入失败）。

## 3. 运行时合成输入（冻结）

每句合成（CosyVoice3-MNN 内部四阶段）：

```
text + speaker condition + instruction
  → [LLM] speech tokens（cosyvoice_ras 采样：仅 6561 合法 token + EOS，top-p 0.8 / top-k 25 / 10-token 窗口）
  → [Conditioner] prompt-cond → mel 特征
  → [Flow] 2 步蒸馏（OpenCL High；seq 桶 512/768/1024/1280/1536/2048；spks+cond 输入）
  → [HiFT]（CPU High/6 线程；480 samples/frame）→ 24kHz mono float WAV
```

**LLM prompt 两种模式（冻结）**：
- `ZERO_SHOT`：`prompt_text + <|endofprompt|> + target_text`，并**追加档案的 prompt speech tokens**（appendPromptSpeechTokens=true）。
- `INSTRUCT2`：`You are a helpful assistant. <instruction><|endofprompt|><text>`，**不追加**参考 token；Flow 仍读同一档案（身份/表演分离）。
- **只传目标文本不传 prompt_text 会立即 EOS**（必坑）；Flow token 输入必须 **int32**（按 int64 写误差 ~6.44）。

**JNI 入口（4 库）**：
| 库 | 签名 | 说明 |
|---|---|---|
| cosy_llm_jni | `run(configPath, promptsPath, maxTokens, promptSpeechTokensPath, outputDirectory, appendPromptSpeechTokens): Int` + `reset()` | promptSpeechTokensPath = 档案 csv；append = ZERO_SHOT 标志 |
| cosy_flow_jni | `run(modelPath, manifestPath, backend, precision, threads, reportPath, cachePath): Int` | backend=OpenCL/CPU；precision=High（Normal/Low 非有限值，禁止） |
| cosy_hift_jni | 同 Flow 模式 | CPU High |
| cosy_enrollment_jni | `enroll(...)`（§2） | 注册 |

**数值健康门槛（合成后必须检查）**：PCM finite、非静音、峰值 < 削波；LLM token 合法（6561 集 + EOS）；Flow 输出对桌面 ONNX 参考误差在容差内。

## 4. 模型包依赖（17 文件，1,399,083,563 B，sha B1C74DFC…）

关键文件：`llm.mnn.weight`（336.5MB）+ `embeddings_bf16.bin`（271.2MB）+ `llm_config.json`（456B，缺则 LLM Reshape 错误）+ `flow.cfg-student-2step.batch1.fp16.mnn.weight`（632.4MB，外部权重，图/权重基名必须一致）+ `flow-conditioner.fp32.mnn` + `hift-core/f0` + `rand-noise.bin` + `source-linear-weight/bias` + 内置基准档案（tokens/cond/spks）。

## 5. VoiceProfile → 运行时档案 映射契约（VoiceFactory 必须满足）

| VoiceProfile（VOICE_PROFILE_SCHEMA） | 运行时档案 | 约束 |
|---|---|---|
| assets.prompt_tokens | prompt-speech-tokens.csv | ≤125 token；逐 token 与 tokenizer 输出一致（数值一致性验证） |
| assets.cond | prompt-cond.bin | 80ch × 2×tokens × 4B；与 Conditioner 输出一致 |
| assets.spks | spks.bin | **80 维**（CAM++ 192 维须先 affine 投影） |
| assets.reference_wav（开发资产） | source.wav（可选） | 3-15s 单人干净语音 |
| persona.* | 不落运行时（metadata 层） | 仅开发/管理用 |
| hash | profileHash | 目录内容 sha256 |

**手机只携带运行时资产**（spks.bin + prompt-speech-tokens.csv + prompt-cond.bin + profile.json）；reference.wav / 原始 192 维 embedding / persona 元数据留在 PC 开发库（可重生成）。

## 6. 兼容性验收（M0.5 acceptance）

1. 任一已注册档案（内置基准 profile）在 CosyVoice3-MNN 运行时直接加载合成成功（真机或 PC 各 1 次）。
2. 数值一致性：PC 端 enrollment 产物（tokens/cond/spks）与桌面 ONNX 参考逐值对齐（tokenizer 逐 token、CAM++ 余弦 ≥0.999 对参考实现）。
3. 新档案（VoiceFactory 产物）通过 §5 映射契约全部字段校验。
4. 反向验证：一个不满足契约的档案（如 200 token / 192 维 spks / 错 modelId）必须被校验器拒绝。
