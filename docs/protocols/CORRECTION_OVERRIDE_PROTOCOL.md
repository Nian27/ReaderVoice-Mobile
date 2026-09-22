# CORRECTION_OVERRIDE_PROTOCOL.md — 修正与覆盖协议（TASK-040 §119/§34-§40）

**日期：2026-08-12** | 接口对 Character 等未来模块可直接复用。

## CorrectionEvent（不可变日志，§34/§35）

```text
correction_id / book_id / source_revision_id? / type（PARAGRAPH_JOIN/PARAGRAPH_SPLIT/CHAPTER_OVERRIDE/...）
target_type / target_id / old_payload / new_payload / user_locked / created_at / compiled_override_id?
```
- **禁止 UPDATE correction**：用户再改 → 新事件；旧事件保留。
- Undo（§40）：新增 `UNDO:<type>` 事件（old/new 对调）+ Override head 切换——不删除历史。

## OverrideRule（当前生效约束，§36/§37）

```text
rule_id / book_id? / scope（GLOBAL/BOOK/SERIES）/ rule_type / match_payload / action_payload
priority（USER_LOCKED > USER_OVERRIDE > SYSTEM_CONFIRMED > AUTO，§39）
status（ACTIVE/SUPERSEDED/DISABLED/NEEDS_REBIND，§38）/ user_locked / valid_from / valid_to
```
- CorrectionEvent = "用户做过什么"；OverrideRule = "Compiler 现在必须遵守什么"（§36）。
- Correction → Override Compiler（§37）：如 USER_JOIN P100 P101 → ParagraphBoundaryOverride；自动 Parser 先加载 Override。
- **优先级语义（实现修正）**：priority 强 = ordinal 小（USER_LOCKED 最强）；同 match 同 type：新优先级不弱于旧才 supersede 旧，弱于旧 → 新规则 DISABLED 不生效（USER_LOCKED 永不被低优先级覆盖）。
- SourceRevision 切换：旧 override → NEEDS_REBIND（§33/§38），Source Alignment 未来单独做。

## ParagraphLineage（§31/§32）

```text
MERGED_INTO / SPLIT_FROM / SUPERSEDES
```
- 用户 Join P1+P2→P3：记录 P1→P3、P2→P3；书签/用户修正/Audio cache 绑定旧段时可重定位。

## 删除语义（§40/§41）

- 撤销 = 新增事件；Book 删除 = 软删（deleted_at）；PURGE 单独操作；核心用户数据 FK RESTRICT。
