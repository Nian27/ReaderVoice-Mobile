# CHARACTER_OBSERVATION_SCHEMA.md — 角色观察协议（TASK-060 §4）

**日期：2026-08-12** | 输入协议：上游（规则/Exporter/未来 ReaderDirector）→ CharacterCompiler。

## CharacterObservation

```json
{
  "type": "MENTION|SAME_IDENTITY_EVIDENCE|DIFFERENT_IDENTITY_EVIDENCE|RELATIONSHIP|CO_PRESENCE|
            CONTROL|POSSESSION|EMBODIMENT|IMITATION|VOICE_AGE_EVIDENCE|TEMP_VOICE_EVENT",
  "paragraph_revision_id": 1024,
  "source_start": 12880, "source_end": 12882,
  "surface": "张教授", "entity_hint": "PERSON",
  "payload": { "entity_a": "...", "entity_b": "...", "strength": 2.0, "hard_block": true, ... },
  "narrative_position": 12880,
  "provenance": "EXPLICIT_RULE|LEGACY_*|HUMAN_GOLD|..."
}
```

## 关键语义

- 观察是**事实**，不是裁决——Mention 永不删除（§5）；证据带 provenance（LEGACY_* ≠ Gold，§47/§85）。
- `narrative_position` = source codepoint 位置（TASK-010 双轨 offset），供 causal 查询。
- LegacyBehaviorAdapter：LegacyBehaviorRecord → Observation（保留 LEGACY_* provenance）。

## 消费端

CharacterCompiler（流式，ADR-032）→ narrative_entity / mention / identity_evidence / embodiment_interval / voice_phase_interval / temporary_voice_event / character_attribute 表（schema v2）。
