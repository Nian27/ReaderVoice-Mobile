# REVISION_PROTOCOL.md — 版本协议（TASK-040 §118/§5-§8/§86-§90/§113-§114）

**日期：2026-08-12**

## ArtifactRevision（stage-level，§5/§26）

```text
revision_id / artifact_type（SOURCE/STRUCTURE/PARAGRAPH_RECOVERY，预留 CHARACTER/SEMANTIC/NARRATION/RENDER/AUDIO/SEARCH_INDEX）
owner_scope（book:<pk>）/ book_id / source_revision_id / parent_revision_id
input_hash / algorithm_version（如 chapter_resolver_v1，独立于 App 版本，§88）
status / created_at / activated_at / superseded_at / reason
```

## 状态机（§6 冻结）

```text
BUILDING   正在构建（可被 crash 恢复打断）
ACTIVE     当前有效
SUPERSEDED 有更新版本替代，但旧版本本身完整有效
FAILED     构建失败
STALE      依赖已改变，不能视作当前正确
```

## Promotion（§7/§79，原子）

```text
create R2 BUILDING → 写入全部 derived rows → validate
BEGIN → 旧 head ACTIVE→SUPERSEDED → R2 BUILDING→ACTIVE → ArtifactHead→R2 → COMMIT
失败 → R2=FAILED，R1 仍 ACTIVE（绝无新旧各半）
```

## ArtifactHead（§8/§80）

- owner+artifact_type → 当前 active revision；**禁止 ORDER BY created_at 猜当前**。
- 唯一 ACTIVE 不变量：DB 约束 + 测试（G4）。

## Idempotent Build（§86/§87）

- 同 owner+type+input_hash+algorithm_version → 复用已有 revision（不创建 R103/R104/R105）。
- Structure input hash = source hash + rulepack version + resolver version + overrides hash；Paragraph = structure rev + layout/boundary/join/block 版本 + overrides hash。

## BuildSession / Crash 恢复（§113/§114）

- 启动时：BUILDING 且无 RUNNING session → FAILED_INTERRUPTED；旧 ACTIVE 不变（G13）。
- 完整 Job Scheduler 不在 TASK-040（§113）。

## Revision GC（§90/§91）

- 开发期 KEEP old superseded；被 User Correction/Bookmark/active Audio 引用的 revision 永不删；GC 归 StorageManager。

## RevisionSnapshot（§102-§105）

```text
source_revision_id + structure_revision_id + paragraph_recovery_revision_id
（未来扩展 character/semantic/voice_binding）
```
- 一次任务（如 ReaderDirector 请求）读取 heads 后**一直使用这组 ID**，不中途 getLatest——防"前半旧状态+后半新状态"（§104）。
