# TASK060_PERSISTENCE_REPORT.md — 持久化报告（TASK-060）

**日期：2026-08-12**

## Schema v2（§3/§65）

- v1 → v2 仅增量（10 新表）；迁移测试：v1 seed（Book/Source/Paragraph/Correction 路径）→ migrate → 数据 100% 保留（G2/G65）
- canonical：data-room/database/schema.v2.sql；Room 环境仍 OPEN（JVM SQLite reference）

## 约束延续

- book_pk NOT NULL（cross-book 隔离，§19）；INSERT OR REPLACE 红线（G66，静态检查沿用）
- CHARACTER ArtifactType + RevisionSnapshot 扩展（source/structure/paragraph/character）

## 双查询 API（§23/ADR-030）

- resolveIdentity：whole-book（identity_evidence + cluster 查询）
- queryEffectiveVoiceState：causal（voice_phase/temporary_voice_event/character_attribute 带 position 谓词）

## 复现

```bash
./gradlew :data-room:test --tests "com.readervoice.data.character.*"
```
