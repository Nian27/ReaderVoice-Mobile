# RENDERUNIT_V1_SCHEMA.md — RenderUnit v1 冻结（TASK-RV-044-A）

**日期：2026-08-20（v2 版，对齐用户冻结字段表）** ｜ 状态：**FROZEN（待 ADR-044 正式化）** ｜ 前置：VS2-VS8 冻结（ADR-040/041/042）、CosyVoice3-MNN v1.1.0 引擎事实、VS8 checkpoint-361。

## 0. 管道与核心原则（冻结）

```
小说文本 → SemanticEvent（LLM）→ VoiceEvent（LLM, VS8）
  → VoiceStateReducer（VS4 确定性）→ RenderInstructionCompiler（确定性）
  → RenderUnit v1（纯数据）→ CosyVoice3-MNN（声音生成）
```

- **R1 身份与表演分离**：身份（voice_profile：年龄/性别/timbre/speaker identity）与表演（voice_event：情绪/语速/音量/风格）由不同层产出，在 RenderUnit 合流，互不越界。
- **R2 LLM 不直接控制 TTS**：禁止 `emotion:"angry"` / `voice:"sad"` 直通。LLM 只输出结构化事件 + 证据；表演标签/instruction 由确定性编译层产生（同输入必同输出，映射表冻结）。
- **R3 角色不绑定模型**：`角色 → VoiceProfile → SpeechRoute → CosyVoice3`；引擎可替换（CosyVoice3 / IndexTTS / 其他 TTS），RenderUnit 不含任何引擎私有字段。
- **R4 纯数据**：RenderUnit 无执行逻辑，可校验、可缓存、可重放、可审计。
- **R5 状态只维护一次**：声音状态由 VoiceStateReducer 维护；TTS 不重新理解文本（不接收原文）。

## 1. 顶层字段（冻结，9 段）

```json
{
  "id": "book_a:ch_003:seg_00023",
  "segment_type": "DIALOGUE",
  "text": { "raw": "别告诉别人。", "normalized": "别告诉别人。" },
  "character": { "character_id": "C023", "voice_profile_id": "vp_zhangsan_base", "profile_hash": "sha256:…" },
  "voice_state": { "state": "BASE", "source": "REDUCER" },
  "voice_event": [
    { "event_id": "ev_00023_1", "type": "PERFORMANCE", "style": "WHISPER",
      "confidence": 0.86, "scope": "UTTERANCE", "evidence_span": "压低声音" }
  ],
  "semantic_intent": { "intent": "secrecy", "confidence": 0.82, "evidence_span": "别告诉别人" },
  "render_instruction": {
    "language": "zh", "accent": "mandarin", "mode": "INSTRUCT2",
    "instruction": "请用耳语般、轻声、缓慢、略带神秘感的方式说这句话。"
  },
  "tts_backend": "cosyvoice",
  "voice_profile": {
    "spks": "voices/vp_zhangsan_base/spks.bin",
    "prompt_tokens": "voices/vp_zhangsan_base/prompt-speech-tokens.csv",
    "cond": "voices/vp_zhangsan_base/prompt-cond.bin"
  },
  "tts_config": { "model": "cv3-mnn-v1", "sample_rate": 24000, "speed": 1.0 },
  "cache": { "key": "sha256(cv3-mnn-v1|vp_hash|instruct2|inst_hash|1.0|24000|text_sha)" }
}
```

> 注：`semantic_intent` 为用户早期草案的 SemanticEvent 层产物，保留为**可选**顶层字段（封闭枚举 + 证据门槛）；如确认不需要可在 ADR-044 时移除。

## 2. 字段表（v1）

| 路径 | 类型 | 必填 | 生产者 | 规则 |
|---|---|---|---|---|
| id | string | ✓ | Planner | 书:章:段 作用域唯一 |
| segment_type | enum | ✓ | SegmentClassifier（确定性规则为主，LLM 补充） | NARRATION \| DIALOGUE（见 §3.5） |
| text.raw | string | ✓ | Source | 原文不可变（AGENTS 不变量 1） |
| text.normalized | string | ✓ | 确定性 | v1 恒等于 raw；保留字段 |
| character.character_id | string | ✓ | Character Resolver + GroundingResolver(VS6) | 冻结角色 ID |
| character.voice_profile_id | string | ✓ | Character Resolver + CharacterVoiceDB | 必须存在（VOICE_PROFILE_SCHEMA） |
| character.profile_hash | string | ✓ | DB | 档案内容 sha256；防陈旧引用 |
| voice_state.state | enum | ✓ | VoiceStateReducer | BASE | OVERRIDE | UNKNOWN |
| voice_state.source | enum | ✓ | Reducer | REDUCER | USER_CORRECTION |
| voice_event[] | array | ✓（可空） | LLM(VS8) | 本段事件；NONE 时空数组 |
| voice_event[].type | enum | ✓ | LLM | 四类（§4） |
| voice_event[].style | enum | 条件 | LLM | PERFORMANCE 必填 |
| voice_event[].confidence | float | ✓ | LLM | [0,1]；<0.5 的 PERFORMANCE 不产生指令片段 |
| voice_event[].scope | enum | ✓ | LLM | UTTERANCE | UNTIL_CLEAR |
| voice_event[].evidence_span | string | ✓ | LLM | **逐字 ∈ text.raw**（VS2 纪律） |
| semantic_intent | object | 可选 | LLM | 封闭枚举；confidence<0.7 或证据缺失 → 丢弃 |
| render_instruction.language | enum | ✓ | Compiler | §4（9 语言）；默认 zh |
| render_instruction.accent | enum | ✓ | Compiler | §4（18 方言+standard）；默认 mandarin |
| render_instruction.mode | enum | ✓ | Compiler | INSTRUCT2（默认）| ZERO_SHOT |
| render_instruction.instruction | string | ✓ | Compiler（确定性） | §5 模板；ZERO_SHOT 时可空 |
| tts_backend | enum | ✓ | VoiceResolver | cosyvoice \| edge \| system；角色=cosyvoice；旁白由 NarratorProvider 决定（VOICE_RUNTIME_ARCH §2） |
| voice_profile.spks/prompt_tokens/cond | path | ✓ | Compiler+DB | 引用档案运行时文件（VOICE_PROFILE_SCHEMA assets） |
| tts_config.model | string | ✓ | Compiler | 引擎+模型版本（cv3-mnn-v1）；引擎可替换 |
| tts_config.sample_rate | int | ✓ | Compiler | 24000（CosyVoice 输出） |
| tts_config.speed | float | ✓ | Compiler | 默认 1.0；仅用户设置覆盖 |
| cache.key | string | ✓ | Compiler | §6 公式 |

## 3. 枚举冻结（v1 词汇表）

- **voice_state.state**：`BASE` ｜ `OVERRIDE`（VS4 语义，UTTERANCE 自动过期）｜ `UNKNOWN`（角色未绑定声线 → RenderUnit 不合成，走降级策略）。
- **voice_event.type**：`VOICE_OVERRIDE_SET` ｜ `VOICE_OVERRIDE_CLEAR` ｜ `PERFORMANCE` ｜ `NONE`（VS2 四类）。
- **style**（PERFORMANCE，v1 封闭表）：`LOW_VOLUME` `WHISPER` `LOUD` `TREMBLING` `COLD` `SEDUCTIVE` `MUMBLED` `CHEERFUL` `SAD` `ANGRY` `FAST` `SLOW`。
- **scope**：`UTTERANCE` ｜ `UNTIL_CLEAR`。
- **semantic_intent**（v1 封闭表）：`secrecy` `urgency` `joy` `sorrow` `anger` `fear` `calm` `neutral`；**仅 secrecy/urgency 进入指令限定**。
- **render_instruction.language**（9）：zh/en/ja/ko/de/es/fr/it/ru。
- **render_instruction.accent**（18+standard）：普通话、广东话、闽南话、四川话、东北话、陕西话、上海话、天津话、山东话、宁夏话、甘肃话、贵州话、河南话、湖北话、湖南话、江西话、山西话、云南话、standard。
- **render_instruction.mode**：`INSTRUCT2`（默认；LLM 收指令文本、Flow 读同档案——身份/表演分离已验证）｜ `ZERO_SHOT`（原样复刻兜底）。

## 3.5 SegmentType 与 VoiceResolver（FROZEN，2026-08-20 旁白架构补充）

- **段落路由（冻结）**：`文本段落 → SegmentClassifier → NARRATION \| DIALOGUE`。
  - DIALOGUE（对白/引语）→ CharacterProfile（角色档案）
  - NARRATION（其余全部）→ NarratorProfile（旁白，一级 Voice Role，非普通角色）
- **旁白占比事实**：网文 50-80%、文学小说 70%+ —— 旁白是音频主体，独立设计（耐听/稳定/清晰/不抢戏/低疲劳）。
- **VoiceResolver（确定性）**：`SegmentType + 角色归属 → NarratorProfile | CharacterProfile → tts_backend`。
  - 角色 = cosyvoice（零样本档案）；旁白 = NarratorProvider（Edge 默认 / CosyVoice 本地 / 用户自定义，VOICE_RUNTIME_ARCH §2）。
- 旁白不参与 VoiceEvent/情绪表演（NARRATION 的 voice_event 为空；情绪克制，由 NarratorProvider 参数控制）。

## 4. RenderInstruction 编译规则（确定性，compiler_version=rvic-v1）

- style→片段（冻结）：LOW_VOLUME→轻声；WHISPER→耳语般；LOUD→大声；TREMBLING→声音发颤；COLD→语气冰冷；SEDUCTIVE→带着诱惑的语气；MUMBLED→含糊地；CHEERFUL→开心地；SAD→伤心地；ANGRY→生气地；FAST→语速快；SLOW→语速慢。
- intent→限定（v1 仅 2 个）：secrecy→略带神秘感；urgency→带着紧迫感。
- 模板：`请用{片段1}、{片段2}、{限定}的方式说这句话。` 片段按事件顺序、去重、最多 3 个；空 → instruction 为空。
- mode：instruction 非空 → INSTRUCT2；空且档案存在 → ZERO_SHOT；档案缺失 → 降级不合成。
- 语言/方言：默认 zh/mandarin；仅原文证据（外语/方言词）+ LLM 检测才覆盖（证据纪律，默认不表演化）。

## 5. 缓存规则

```
cache.key = sha256(tts_config.model + "|" + character.profile_hash + "|" + render_instruction.mode
  + "|" + normalizedInstruction + "|" + tts_config.speed + "|" + tts_config.sample_rate
  + "|" + text.normalized)
```

字段级失效；改档案/指令/速度只失效相关片段，禁整章/整本清缓存；模型版本变化全局失效；音频 + SQLite 索引（key 主键，AudioAsset 绑定 voice_revision_id，AGENTS 不变量 13）。

## 6. 版本规则

- schema_version 整数递增；v1 冻结后只允许 additive（新枚举值/可选字段）；破坏性变更升 major + 迁移说明。
- 新 style/intent = ① 词汇表 ② 映射表 ③ 校验器+缓存键（随 instruction 文本自动变）三步齐改。
- producer 版本溯源：RenderUnit 落库时附带 director_version（LLM checkpoint）/ compiler_version / schema_version。
- 变更顺序：本文档 → 校验器测试 → 代码。

## 7. 校验器与验收（044-A acceptance）

1. ≥100 合法/非法样例校验器单测（证据不在原文/profile 缺失/枚举越界/指令与映射表不一致）。
2. 映射表 golden 测试（每 style×intent 一个确定性断言）。
3. 缓存键测试（同输入同 key；任一字段变 key 变）。
4. VS8↔RenderUnit 映射测试（VS7_DEV_FRESH 265 条 gold → 编译 → 指令组装正确率 100% 目标）。
5. R2/R3 静态检查（RenderUnit 无 TTS 侧推断字段、无引擎私有字段）。
