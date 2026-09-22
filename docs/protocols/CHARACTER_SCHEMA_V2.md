# CHARACTER_SCHEMA_V2.md — Schema v2（TASK-060 §3/§65）

**日期：2026-08-12** | v1 → v2 = 仅新增 character 表族（SQLite 兼容；v1 表 100% 保留）。

## v2 新增表（10）

```text
character_observation / narrative_entity / mention / identity_evidence /
identity_cluster_membership / identity_lineage / embodiment_interval /
voice_phase_interval / temporary_voice_event / character_attribute
```

## 关键约束

- **book_pk NOT NULL 全部角色表**（cross-book isolation 是 DB constraint，§19/ADR-009）
- identity_evidence：CHECK (entity_a != entity_b)；relation_type 枚举
- mention.paragraph_revision_pk NOT NULL（§5 文本事实）
- CHARACTER ArtifactType（artifact_revision 通用）
- 禁止 INSERT OR REPLACE（ADR-025 延续，G66）

## Migration

`SchemaMigrationV2.migrate()` + `verifyV1Preserved`（迁移测试 G2/G65：Book/Source/Chapter/Paragraph/Correction 100% 保留）。
v1 seed DB 保留（migration harness 从 v1 起步）。
