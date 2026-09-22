# LOGICAL_PARAGRAPH_SCHEMA.md — 逻辑段落模型（TASK-030 §41-§44/§49-§52/§86）

**日期：2026-08-12**

## LogicalParagraphRevision（§41 冻结）

```text
paragraph_id          稳定语义 ID（用户修正时尽量保持）
revision_id           版本
book_id / chapter_id
source_revision_id    绑定 SourceRevision（ADR-016/§96——换书源后旧 offset 失效）
paragraph_index       章内顺序
source_spans[]        行级区间（byte + codepoint 双轨，不要求连续 normalized offset，§44）
normalized_text       JoinPolicy 拼接（段首缩进已移除）
block_type            PROSE/POETRY/LYRICS/SCRIPT/MESSAGE_LOG/LIST/LETTER/SCREEN_TEXT/
                      SEPARATOR/BOILERPLATE/CHAPTER_TITLE/VOLUME_TITLE/TOC_ENTRY/UNKNOWN
read_policy           NORMAL/CHAPTER_TITLE/VOLUME_TITLE/TOC_ENTRY/SKIP_READ（§49/§50）
boundary_confidence
parent_revision / revision_reason（AUTO_REFLOW/USER_JOIN/USER_SPLIT/RULE_UPDATE/
                      LAYOUT_PROFILE_UPDATE/SOURCE_REVISION，§42）
active
```

## SourceSpan（§44）

```text
line_no / byte_start / byte_end / char_start / char_end
一个段落 = 多个行级 span（hard-wrap 合并）；不要求连续 normalized string offset。
```

## 空行表示（§86 冻结，ADR-021）

- 连续空行**不生成可朗读 LogicalParagraph**；只作为 Boundary 证据（BLANK_GAP）。
- SpacingBlock{start_line,end_line,blank_count} 仅记录区间（本任务预留结构）。
- **SEPARATOR / BOILERPLATE 行同样不产段落**（§29/§35：不朗读；与空行一致）。

## 块类型继承（BlockGrouper，§30/§32/§33）

- 单行确定类型：SEPARATOR/BOILERPLATE/LYRICS/SCREEN_TEXT/CHAPTER_TITLE/VOLUME_TITLE/TOC_ENTRY/LETTER。
- 组级类型（连续行）：SCRIPT ≥2 行、LIST ≥2 行、MESSAGE_LOG ≥1 行（时间行）、POETRY ≥3 行（短行+无终止+长度接近+**无引号**）。
- 优先级：具体组 > POETRY；POETRY 不覆盖已有组。
- 段落 blockType 继承段内行的组类型。

## 特殊语义

- **Chapter 标题行**：CHAPTER_TITLE block + read_policy=CHAPTER_TITLE（不进入正文段落，§49）；多行标题第二行标 CHAPTER_TITLE_CONTINUATION（TASK-020 POTENTIAL_MULTILINE_TITLE 处理，§51/§52）——本任务记录行级 blockType，display title 合并留 UI/后续任务。
- 超长单行（整章一行）= 1 个 LogicalParagraph（§91：Paragraph 结构与 TTS RenderUnit 长度限制分离）。
- 短行（"嗯。"）不因短自动 JOIN（§92/§93）。
- 结构层与 POV 无关（§94：不依赖人名开头）。

## 用户修正（§53-§55）

```text
joinParagraphs(left,right) / splitParagraph(paragraph, sourceBoundary)
→ CorrectionEvent + USER_JOIN/USER_BREAK + user_locked（自动重解析不覆盖，§54）
→ DependencyInvalidationEvent{paragraph_ids, invalidated_domains=[cross_links,semantic_segment,render_unit]}（局部失效，§55）
旧 SourceRevision 的 USER_JOIN 不能直接套新版本 → NEEDS_REBIND（§98，仅记录设计）
```

## 存储（§71/§72，ADR-019）

benchmark（1M 字符 / 18,182 段，SQLite 实测）：

| 指标 | A：全存 normalized | B：spans+transforms |
|---|---|---|
| DB size | 2.87 MB | **1.51 MB**（-47%） |
| import | 189 ms | **83 ms**（-57%） |
| query 1000 段 | 75.9 ms | **64.8 ms**（-15%） |

**决策：Option B**——DB 只存 source spans + boundary transforms，normalized_text 需要时重建；TASK-040 Room 落地时沿用，并评估全文搜索（FTS 索引可独立于段落表）。
