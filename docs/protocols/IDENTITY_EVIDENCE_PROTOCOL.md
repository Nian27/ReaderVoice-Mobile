# IDENTITY_EVIDENCE_PROTOCOL.md — 身份证据协议（TASK-060 §7-§10）

**日期：2026-08-12**

## IdentityEvidence（§7）

```text
entity_a / entity_b / relation_type / sign(POSITIVE|NEGATIVE|NEUTRAL)
strength / hard_block / source_paragraph / narrative_position
valid_from / valid_to / provenance / confidence / legacy_weight
```

## 关系类型（§8）

SAME_PERSON / DIFFERENT_PERSON / ALIAS / TITLE / KINSHIP / SOCIAL_RELATION / CO_PRESENCE / POSSESSION / CONTROL / EMBODIMENT / IMITATION

**KINSHIP/SOCIAL_RELATION/POSSESSION/CONTROL/EMBODIMENT/IMITATION 默认绝不转换为 SAME_PERSON**（v90.7 关系拦截层行为需求，测试 G5/G6/G7 回归）。

## Hard Negative（§9/G4）

DIFFERENT_PERSON + hard_block：同场互言 / 明确"不是" / 用户 ENTITY_NEQ / 身份类型冲突 → auto merge 禁止。

## False Merge > False Split（§10/ADR-005）

证据不足 → PROVISIONAL/UNKNOWN，不挑最高分硬合并。mergeCandidate = 无 hard negative + positive SAME/ALIAS ≥ 阈值。

## 双查询（§21-§23/ADR-030）

- `resolveIdentity(entity, revision)`：**whole-book**（未来证据可反推，G13）
- `queryEffectiveVoiceState(identity, position, revision)`：**causal**（只允许 position 前，G14）
