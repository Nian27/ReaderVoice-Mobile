# CHARACTER_CAUSALITY_POLICY.md — 因果边界政策（TASK-060 §21-§23/§62-§63/ADR-030）

**日期：2026-08-12**

## 两记忆分离（v5 §55 落地）

| 记忆 | 允许证据范围 | API |
|---|---|---|
| Identity Memory | **whole-book**（未来"张老师=张明"可反推早期） | resolveIdentity(entity, revision) |
| Performance Memory | **causal**（position ≤ p） | queryEffectiveVoiceState(identity, position, revision) |

## Gate（G13/G14 同时存在）

- G13：Ch20 证据 → 早期张老师解析为张明 ✓
- G14：Ch10 临时换声 → 查 Ch2 不得看到 ✓

## 禁止

共用一个 `getLatestCharacterState()`——Character build 到 Ch20 时 Ch2 查询必须还是 Ch2 的状态。
