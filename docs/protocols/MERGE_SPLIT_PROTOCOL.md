# MERGE_SPLIT_PROTOCOL.md — 合并/拆分协议（TASK-060 §11-§16/ADR-029）

**日期：2026-08-12**

## Non-destructive merge（§12）

```text
禁止：DELETE entity_B + UPDATE mentions → entity_A
采用：IdentityClusterMembership（entity 挂 cluster）+ IdentityLineage（MERGED_INTO/SPLIT_FROM/SUPERSEDES）
旧 entity / evidence / mention 永远保留
```

## Merge Event（§13）

merge_event_id / character_revision / source_entities[] / target_identity / reason / evidence_ids[] / automatic|user / confidence

## Split（§14-§15）

无需"从备份恢复一切"（v90.7 的硬伤）——新 revision + cluster membership 变更；Mention/Evidence 不丢；merge→split 关键状态可逆（G9 测试）。

## 与 v90.7 对比（§49 硬收益）

| 维度 | v90.7 | ReaderVoice |
|---|---|---|
| 跨书污染 | 4 全局文件（L3 复现） | book_pk NOT NULL DB 约束 |
| merge | 非原子 + 启发式恢复 | 非破坏式 + lineage + revision |
| 临时状态 | 不写卡（L3） | 事件表 + 有效区间，Base 不变 |
| 修正 | 仅 voice-lock 持久化 | CorrectionEvent + OverrideRule + USER_LOCKED |
