# ID_AND_IDENTITY_POLICY.md — ID 与身份策略（TASK-040 §120/§45-§48）

**日期：2026-08-12** | ADR-022。

## Hybrid ID（§45-§47）

| 对象 | ID 策略 |
|---|---|
| Book / SourceRevision / VoicePack / ModelRevision | **stable UID**（跨系统、可迁移） |
| PhysicalLine / LineBoundary / ParagraphRevision / Span / Transform | **INTEGER PRIMARY KEY**（内部热表，避免 36 字符 UUID 膨胀） |

- SQLite `INTEGER PRIMARY KEY` 即可（无 AUTOINCREMENT 额外开销，§48）；rowid 复用由 SQLite 管理。
- 复合唯一：`(source_revision_pk, line_no)`、`(structure_revision_pk, chapter_index)`、`(paragraph_revision_pk, order_index)` 等。
- 引用完整性：核心用户数据 FK RESTRICT；派生数据 CASCADE（§42/§43）。

## Paragraph Identity（§28-§30）

```text
logical_paragraph.paragraph_uid（stable，跨 revision）
paragraph_revision.fingerprint = source_revision_id + ordered source spans + structural role（§30）
```
- 自动 reparse：source spans 与 block type 相同 → 尽量复用 paragraph_uid，产生新 revision 或不变（§29）。
- 跨 SourceRevision：**新 Paragraph Revision namespace**（§97）；override NEEDS_REBIND（§33）。

## 可复现性契约（§89/§92）

- 每个 revision 保存 BuildManifest：algorithm versions + input hash + app version + created at——回答"这本书为什么和另一台手机不同"。
- Integrity verifier：FK / ACTIVE heads / dependency cycle / paragraph spans 有序 / chapter range / source hash（§92-§96）。
