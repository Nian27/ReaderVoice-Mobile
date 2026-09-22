# VOICE_STATE_PROTOCOL.md — 语音状态协议（TASK-060 §27-§37/ADR-031）

**日期：2026-08-12**

## 三层分离（§27）

```text
Base Character Attributes（gender_style / chronological_age / appearance_age / voice_age，带证据 §28-§29）
VoicePhase（CHILD/TEEN/YOUNG/MIDDLE/OLD interval，§30）
TemporaryVoiceState（事件表，§31-§33）
```

## TemporaryVoiceEvent（§32）：事实来源

```text
event_id / identity_entity_pk / action(START|CONTINUE|REPLACE|END) / state_type / payload
narrative_position / scope / evidence_id / provenance
```
有效区间可编译（EffectiveInterval），但**事件日志是真相**（§33）。

## 不变量（§34/G12）

```text
Base voice/voice_age before temp == after temp ends（除非独立 phase update）
```

## Narrative vs User Constraint（§35-§36/ADR-031）

Narrative VoiceState（故事内声音状态）与 VoiceBindingConstraint（用户 VoicePack 选择）分离：
"张三压低声音"（TEMP_LOW_VOICE）+ "用户固定 Voice A" 可共存 → TTS Planner = Voice A + temporary performance control。
**INTENTIONAL REDESIGN**（v90.7 fixed 拒 temp 仅作 LEGACY_BEHAVIOR 记录，§37）。

## 因果（§22/G14）

position p 查询只允许 position ≤ p 的事件/证据（DB 谓词强制）。
