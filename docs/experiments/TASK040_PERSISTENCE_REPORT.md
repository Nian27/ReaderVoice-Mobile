# TASK040_PERSISTENCE_REPORT.md — 持久化报告（TASK-040 §147/§154）

**日期：2026-08-12** | 实现栈：**JVM SQLite reference**（sqlite-jdbc 3.46.1.3）+ Kotlin domain interfaces；`ROOM_RUNTIME_GATE = OPEN`（无 Android SDK，Room 未实测，不混称"手机性能"）。

## Schema v1（§116）

27 张表 / 26 FK / 5 索引 / Hybrid ID（ADR-022）/ FK 全开（§74）/ WAL（§77 评估：后台写+前台读适用，实测已用）。

## Revision lifecycle（§118）

- ArtifactRevision：BUILDING→ACTIVE→SUPERSEDED / FAILED / STALE（§6）；
- **原子 Promotion（§79）**：事务内 旧 head SUPERSEDED → 新 ACTIVE → head 切换；失败回滚 R1 保持（G5）；
- **head 唯一 ACTIVE（G4）**：DB 约束 + 测试；
- **Idempotent build（§86）**：同 input_hash+algorithm → 复用（测试通过）；
- **Crash 恢复（G13/§114）**：BUILDING 无 RUNNING session → FAILED_INTERRUPTED，旧 ACTIVE 不变（测试通过）。

## Dependency DAG（§117）

SOURCE→STRUCTURE→PARAGRAPH_RECOVERY（future 链已冻结）；**Cycle 拒绝（G6）**——BFS 沿"被依赖链"检测（实现期修正方向 bug）；whole source invalidation 全链 STALE（G7）、scoped Join 局部（G8）、metadata-only 不 stale（G9）——全部测试通过。

## Correction/Override（§119）

CorrectionEvent 不可变日志 + Undo（新增事件）+ Override 优先级（**USER_LOCKED 不被低优先级覆盖**——实现期修正 supersede 语义）+ ParagraphLineage（MERGED_INTO/SPLIT_FROM）+ NEEDS_REBIND（§33）——全部测试通过。

## Book/SourceRevision（§49/§50）

Book!=SourceRevision（ADR-016）；同 sha256 唯一约束拒绝重复导入；Book 软删除；FK RESTRICT 核心数据（测试通过）。

## Paragraph lineage / Integrity（G14）

- fingerprint（source_revision+spans+role）；spans order 严格递增校验；chapter_index 有序校验；
- DatabaseIntegrityVerifier：FK orphan / heads 唯一 / cycle / span gap / chapter gap——集成测试 100% PASS。

## 错误分类（§148）

- SCHEMA_DESIGN_ERROR：schema.sql 行尾注释（`-- §75`）导致 CREATE INDEX 被语句拆分器吞掉——**按分号切分修复**
- HEAD_SWITCH_ERROR：promote 需 BUILDING 状态校验
- DAG_CYCLE_ERROR：cycle 检测方向（下游→上游）实现期修正
- INVALIDATION_SCOPE_ERROR：ownerScope 双重前缀（book:book:1）统一
- OVERRIDE 语义：低优先级同 match 应 DISABLED 而非 supersede USER_LOCKED
- TEST_ERROR：lineage 缺前置 FK 数据；reopen 表数断言
- INDEX_PERFORMANCE_ERROR / STORAGE：见 STORAGE_REPORT（3.3M → 322MB，debt）

## Gate 摘要（§130-§146）

G1✅ 回归全过（parser+data-room 全套）｜ G2✅ create-close-reopen 一致｜ G3✅ FK orphan 拒绝｜ G4✅ head 唯一｜ G5✅ 失败不替换 Active｜ G6✅ cycle 拒绝｜ G7✅ whole source 传播｜ G8✅ 局部修正不误伤｜ G9✅ metadata 不 stale / anchor 局部｜ G10✅ USER_LOCKED 保留（override 语义修正后）｜ G11✅ 无 INSERT OR REPLACE（核心代码）｜ G12✅ 3.3M 持久化无 OOM｜ G13✅ crash 恢复｜ G14✅ integrity 100%｜ G15✅ secret_scan/verify_baselines｜ G16✅ 无 Character/Director/TTS｜ **G17 ROOM_RUNTIME_GATE = OPEN**（环境无 Android SDK，不假装 Room 通过）
