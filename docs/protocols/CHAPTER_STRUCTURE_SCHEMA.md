# CHAPTER_STRUCTURE_SCHEMA.md — 章节结构数据模型（TASK-020 §52/§53）

**日期：2026-08-12** | Room 落地在 TASK-040；字段级冻结。

## 数据对象

```text
ChapterCandidate    candidate_id / line_no / rule_id / family / target / raw_line /
                    raw_title / clean_title / serial_raw / serial_value /
                    regex_score / rule_priority_score / length_score / spacing_score /
                    style_score / sequence_score / neighbor_score / toc_score /
                    volume_score / final_score / evidence[]

SameLineCandidateGroup   line_no / winning / alternatives[]（一行至多一个同层级 anchor，G6）

Volume              volume_id / book_id / volume_index / serial_raw / serial_value /
                    title / start_line / end_line? / confidence

Chapter             chapter_id / book_id / volume_id? / chapter_index（书内顺序，连续递增）/
                    serial_value? / serial_part?（上/中/下 UPPER/MIDDLE/LOWER）/
                    title_raw / title_display / anchor_line / anchor_byte_start /
                    anchor_codepoint_start / content_start_line / content_end_line? /
                    state（CONFIRMED/PROVISIONAL/REJECTED/TOC_ENTRY）/ final_score / evidence[]

TocBlock / TocEntry / TocBodyLink（TOC 与正文链接仅作 evidence，不凭空造章）

StructureRevision   revision_id / book_id / createdAt / reason / volumes / chapters /
                    tocBlocks / parent_revision_id?（版本化回滚）

StructureOverride   override_id / book_id / type（ADD_CHAPTER/REMOVE_CHAPTER/CHANGE_TYPE/
                    CHANGE_TITLE/ASSIGN_VOLUME）/ target_line / payload / user_locked
```

## Chapter SourceSpan 约定（§53）

```text
anchor_line / anchor_byte_start / anchor_codepoint_start   锚点（TASK-010 双轨 offset）
content_start_line = anchor + 1
content_end_line = 下一结构 anchor - 1（由解析推导，不复制正文到 Chapter 表）
```

- serial_value 可重复（卷内重置/作者重复编号）；chapter_index 恒为书内顺序。
- 卷重置合法（VolumeResolver）；"上/中/下" 用 serial_part，不强行 12/13/14。
- 无编号短标题章节（"雨夜"）：V1 不强行确认（§29），留用户规则/高可信上下文。
- 多行标题：只记录 POTENTIAL_MULTILINE_TITLE（TASK-020 不合并，TASK-030 处理）。
