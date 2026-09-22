# VOICE_PROFILE_SCHEMA.md — VoiceProfile v1 冻结（TASK-RV-044-A 附件 / TASK-VF-000）

**日期：2026-08-20（v3，TASK-VF-000 冻结版）** ｜ 状态：**FROZEN** ｜ 前置：CosyVoice3-MNN v1.1.0 音色档案格式、VOICE_PROFILE_V1_COMPATIBILITY.md、M1 实测（vp_yaolao_qwen3tts_c3）。

## 0. 定位

VoiceProfile = **角色声音的完整保存体**（reference identity + CosyVoice assets + metadata + validation 记录）。
角色 → VoiceProfile → SpeechRoute → 引擎（R3，引擎可替换）。schema/hash/version 冻结后不可随意改动。

## 1. Schema v1（FROZEN，用户定案字段）

```json
{
  "voice_id": "vp_yaolao_001",
  "schema_version": 1,
  "type": "character",
  "source": {
    "type": "generated",
    "generator": { "model": "Qwen3-TTS-12Hz-1.7B-VoiceDesign",
                   "version": "qwen-tts-0.1.1",
                   "device": "pc", "quant": "bf16" },
    "prompt_text": "参考音频的准确文本",
    "created_at": "2026-08-20T…"
  },
  "persona": {
    "gender": "male", "age": "elder", "pitch": "low",
    "timbre": "rough", "texture": "hoarse",
    "style": ["wise", "calm", "humorous"]
  },
  "dev_assets": { "reference_wav": "source.wav",
                  "embedding_raw": "speaker-embedding-192d.f32",
                  "prompt_feat": "prompt-feat.f32" },
  "runtime_assets": { "spks": "spks.bin", "prompt_tokens": "prompt-speech-tokens.csv",
                      "cond": "prompt-cond.bin" },
  "runtime": { "prompt_token_count": 124, "prompt_frame_count": 248,
               "speaker_embedding_size": 80, "realtime_ready": true,
               "cosyvoice_version": "cv3-mnn-v1.1.0" },
  "validation": {
    "campplus_same": 0.847,
    "campplus_cross": 0.198,
    "drift": 0.021,
    "short_sentence_noise": "KNOWN（<=2s 句 CAM++ 不可靠，mean 0.770）"
  },
  "hash": "sha256:…"
}
```

### NarratorProfile v1（旁白，一级 Voice Role，FROZEN 2026-08-20）

```json
{
  "voice_id": "narrator_default_001",
  "schema_version": 1,
  "type": "narrator",
  "engine": "cosyvoice",
  "style": { "gender": "male", "age": "adult", "tone": "steady_clear", "speed": 1.0 },
  "provider": { "default": true, "fallback": ["system"] },
  "hash": "sha256:…"
}
```

- **全离线原则（FROZEN，2026-08-20 用户定案）**：整个系统（含旁白）必须完全本地运行，**不使用 Edge/云端 TTS**。
- **旁白 = VoiceFactory 生成的本地档案**（与角色同管线）：VoiceDesign 生成"云健感"（沉稳清晰中年男声）候选 → enrollment → NarratorProfile（assets = 同角色档案结构）。旁白不是特例，是 VoiceProfile 的 `type: narrator` 实例。
- **旁白 ≠ 普通角色**：要求耐听/稳定/清晰/不抢戏/低疲劳（长文本主体，网文 50-80%、文学 70%+）；不参与 VoiceEvent 表演（style 参数控制，无情绪变化）。
- engine 枚举（保留抽象但默认本地）：`cosyvoice`（默认，本地）｜ `system`（兜底）。`edge_tts` 移出默认（仅作开发期对照，不进入产品路线）。
- 角色档案必须 `type: character` + `source`（cosyvoice_zero_shot）；旁白 `type: narrator`（assets 同结构 + style 参数）。

## 2. 字段表（FROZEN）

| 路径 | 类型 | 必填 | 规则 |
|---|---|---|---|
| voice_id | string | ✓ | `vp_<角色>_<序号>` / `narrator_<名称>_<序号>` 全局唯一；不可变 |
| type | enum | ✓ | character \| narrator（一级区分；旁白独立模块化，Android 端旁白可先行轻量化） |
| schema_version | int | ✓ | =1；破坏性变更升 major + 迁移 |
| source.type | enum | ✓ | generated | enrollment | builtin |
| source.generator.model/version/device/quant | string | ✓ | 声音来源全溯源（未来横向比较 IndexTTS/GPT-SoVITS/自研） |
| source.prompt_text | string | ✓ | 参考音频准确文字（CER 校验用） |
| persona.gender/age/pitch/timbre | enum | ✓ | 设计层描述，**不直接进 TTS** |
| persona.texture/style | string/list | ✓ | style 为**数组**（wise+calm+humorous） |
| dev_assets.* | path | 可选 | 创建期资产（wav/192d/feat）；可删除（重生成用） |
| runtime_assets.* | path | ✓ | 运行时资产（spks 80 维 / tokens ≤125 / cond 2×frames） |
| runtime.* | 数值 | ✓ | token/frame 数、embedding 维、realtime_ready、cosyvoice_version |
| validation.* | 数值 | ✓ | 生成后实测：campplus_same/cross/drift；**档案自证身份质量** |
| hash | string | ✓ | 目录内容 sha256（防陈旧引用；RenderUnit 引用） |

## 3. Hash 规则（FROZEN）

```
profileHash = sha256(
  model_id + prompt_prefix + prompt-speech-tokens.csv bytes
  + prompt-cond.bin bytes + spks.bin bytes)
```

（与 lab build_voice_profile.py 完全一致；hash 覆盖运行时资产 + 提示文本，不覆盖 dev_assets/persona——重生成 dev 不影响运行时身份。）

## 4. Version 规则（FROZEN）

- voice_id 不可变；内容变化 = 新 voice_id（vp_yaolao_002）+ 新 hash。
- 引擎/模型版本变化（cosyvoice_version / model_id）→ 档案视为旧版，需按新版本重注册（或迁移验证）。
- 枚举新增（style/age…）= additive；删除/重命名 = schema_version 升级。
- 变更顺序：本文档 → 校验器 → 代码。

## 5. 生命周期（FROZEN，M1 实测版）

```
Persona(LLM 描述) → VoiceDesign(3 候选 wav) → CandidateRanker v0.1
  （稳定性 0.5 + 生成性 0.5）→ enrollment（tokens/cond/spks，≤125 约束）
  → validation 实测（A same/B cross/C drift 写入 validation 块）→ Activate（CharacterVoiceDB）
```

- 手机端：Mode 2 创建 → Mode 3 注册（VOICE_RUNTIME_ARCH §3.5）；PC 只做转换/训练/测试。
- 自动筛选禁人工；不达标重生成 ≤2 轮 → 降级 Tier 2 共享池。

## 6. 验收（VF-000 acceptance）

1. 校验器：schema 字段/枚举/hash 单测 ≥50 例。
2. M1 档案（vp_yaolao_qwen3tts_c3）按本 schema 重写并通过校验（反向兼容 lab 格式）。
3. hash 规则与 lab build_voice_profile.py 输出一致（同一输入同 hash）。
4. version 规则测试：改 persona → 新 voice_id + 新 hash；改 runtime 资产 → hash 变。
