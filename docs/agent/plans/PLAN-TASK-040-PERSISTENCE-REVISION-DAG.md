# PLAN-TASK-040 — Room Persistent Schema / Revision Graph / Dependency DAG

**日期：2026-08-12（TASK-030 PASS 后）** | **状态：执行中**

## Goal

把内存中的 Book/Source/Chapter/Paragraph 升级为可持久化、可恢复、可版本化、可局部失效、可回滚、可迁移的正式数据层。**冻结 ReaderVoice 的版本系统和依赖传播规则**（§0）。

## Current Verified Facts（环境探测 2026-08-12）

- **无 Android SDK**（ANDROID_HOME 未设置、默认 SDK 位置不存在）；AGP 8.13.2 与 androidx.room 依赖在 Gradle 缓存（来自 CosyVoice3-MNN-formal）。
- → 按规格 §70：**canonical SQLite schema + Kotlin persistence interfaces + JVM migration/invariant tests**；`ROOM_DEVICE_GATE = OPEN`（不能假装 Room 已验证）。
- sqlite-jdbc 已在 parser 依赖（3.46.1.3）。
- 输入冻结：TASK-010（PhysicalLine 双轨 offset）、TASK-020（Chapter/Volume/TOC）、TASK-030（Paragraph/Boundary/Span/Transform/Link）domain 模型。
- 前置：PERF-030-01（profiler 39.6s = performance debt，本任务不顺手优化）、GEN-030-01（20-30 真实书 OPEN）。

## Non-goals

- v90.7 迁移（TASK-050）、Character、ReaderDirector、LLM、CosyVoice、TTS、Playback——禁止（§82）。
- 不优化 LayoutProfiler（§152）；不建立完整 Job Scheduler（§113，只做 unfinished build 恢复）。
- 不为未来模块建业务表（§5 预留 artifact_type 枚举即可）。
- 不做 Generic Artifact 框架（§155 坑 1：只有 Stage Revision 用通用 DAG，业务实体仍是真实表）。

## Invariants（§2-§44）

DB-01 Book != SourceRevision（ADR-016 继续冻结）；SourceRevision ACTIVE 后不可变（编码 override = 新 decode revision）；derived data 不 inplace 覆盖（revision 化）；Revision Promotion 原子（§7）；ArtifactHead 唯一 ACTIVE（§8/§80）；Generic DAG 管 stage、domain 表管 item（§10/§11）；**核心持久化表禁止 INSERT OR REPLACE**（§44，G11）；ID Hybrid（§45-§48）；FK 策略 RESTRICT 核心用户数据 / CASCADE 派生（§42/§43）；CorrectionEvent 不可变日志（§34/§35）；删除 = 历史保留（§40）；Book 软删除（§41）；RevisionSnapshot 一致性（§102-§105）。

## Scope

```text
data-room/（新 Gradle JVM 模块，依赖 :parser）
  database/schema.sql                canonical schema（设计源，§123）
  Kotlin domain interfaces + SQLite 实现（§99-§101）
  Revision/Invalidation/Correction/Lineage/Snapshot/Integrity/Recovery
tests/（22 类，§129）+ stress + FTS prototype + storage benchmark
docs/protocols/DATABASE_SCHEMA_V1.md + REVISION_PROTOCOL.md + DEPENDENCY_DAG.md +
             CORRECTION_OVERRIDE_PROTOCOL.md + ID_AND_IDENTITY_POLICY.md
docs/experiments/TASK040_PERSISTENCE_REPORT.md + TASK040_STORAGE_REPORT.md +
             TASK040_CRASH_RECOVERY_REPORT.md
```

## Milestones

| # | 交付物 | 验收 |
|---|---|---|
| M1 | 环境探测 + PLAN + :data-room 模块 + canonical schema.sql | schema 可 create/close/reopen（G2） |
| M2 | Revision 核心：ArtifactRevision/Head/Dependency + 原子 Promotion + BuildSession/crash 恢复 | G4/G5/G6/G13 |
| M3 | Invalidation：事件 + Scope + 传播矩阵 + RevisionSnapshot | G7/G8/G9 |
| M4 | Correction：不可变事件 + Override 编译/优先级/UNDO + ParagraphLineage | G10 + §37-§40 |
| M5 | 业务持久化 + Mapper + Stores（Book/Source/Line/Structure/Chapter/Paragraph/Spans/Transforms/Links） | G3 FK 有效 |
| M6 | 22 类测试 + 3.3M stress + FTS prototype + Integrity verifier | G12/G14 |
| M7 | 8 份 artifacts + ADR-022~027 + Gate G1-G17 + 报告 | 全过 |

## Progress

（边做边更新）

## Decisions（预计）

ADR-022 Hybrid integer PK + stable UID；ADR-023 Stage-level ArtifactRevision DAG；ADR-024 Immutable CorrectionEvent + compiled Override；ADR-025 No INSERT OR REPLACE；ADR-026 Derived FTS is rebuildable cache；ADR-027 RevisionSnapshot consistency。

## Validation

G1-G17（§130-§146）：回归全过 / create-close-reopen / FK 有效 / head 唯一 / 失败不替换 Active / cycle 拒绝 / whole source 传播 / 局部修正不误伤 / 元数据变更不 stale / USER_LOCKED 保留 / 无 REPLACE / 3.3M 无 OOM / crash 恢复 / integrity 100% / secret+baseline / 无 Character-Director-TTS / ROOM_DEVICE_GATE=OPEN。

## Rollback

Revision 体系天然支持：旧 ACTIVE 保留（§90 不 GC）；删除=软删；migration harness 从 v1 起步（§124）。

## Artifacts

代码 + 22 类测试 + fixtures（seed db 用 synthetic，§125/§126）+ 8 文档。

## Handoff

- TASK-050（v90.7 Deep Reverse + Exporter）：输入 = 冻结的 Book/Paragraph/Revision/Correction 体系（§155）。
