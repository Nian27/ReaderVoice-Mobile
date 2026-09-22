# LINE_BOUNDARY_SCHEMA.md — 行边界模型（TASK-030 §4/§5/§17-§21/§78/§79）

**日期：2026-08-12**

## 类型（§4 冻结）

```text
SOFT_WRAP          物理换行，非作者真实段落（hard-wrap 内断行）
HARD_PARAGRAPH     真实段落边界
AUTHOR_LINE_BREAK  作者故意换行但属同一语义块（诗歌/剧本/聊天/列表）
STRUCTURAL_BREAK   章节/卷/分隔符/场景分界（不进普通评分，§19）
UNCERTAIN          证据不足（合法状态，P5）
USER_JOIN / USER_BREAK  用户硬覆盖（自动重解析不覆盖）
```

## 数据（§5）

```text
boundary_id / book_id / chapter_id / left_line_no / right_line_no
type / confidence / evidence_mask(compact Long) / evidence[](调试展开)
layout_profile_id / rule_version / user_locked / created_revision
```

## 特征向量（§17，每对相邻非空行）

```text
prev_length / next_length / prev_blank / next_blank
prev_indent / next_indent / prev_terminal / prev_colon / prev_comma / prev_semicolon
next_starts_quote / next_starts_dialogue_like(cue 词)
quote_depth_before / quote_depth_after（外部 QuoteStack 逐行推进，防实例状态泄漏）
prev_near_wrap_width / next_near_wrap_width
prev_in_special_group / next_in_special_group（BlockGrouper 组级判定）
chapter_boundary / volume_boundary / separator（作为 STRUCTURAL_BREAK，不评分）
```

## 评分（§19/§20/§21 初始权重，Gold 校准）

```text
FIXED_WIDTH_PROFILE +2.0 | PREV_AT_WRAP_WIDTH +1.2 | PREV_NO_TERMINAL +1.4
PREV_ENDS_COMMA +1.0 | PREV_ENDS_SEMICOLON +0.5 | QUOTE_CONTINUES +0.8
PREV_TERMINAL(含右引号) -1.0(break) | NEXT_STRONG_INDENT -2.5(break)
NEXT_QUOTE_INDENTED -1.8(break)
SPEECH_CUE_KEEP：prev cue/冒号 + next 引号 → HARD 0.95（不 JOIN，保留 adjacency，P2/§38）
confidence：|diff|≥3→0.99 / ≥2→0.92 / ≥1→0.82 / else→0.6
type：diff≥0.5→SOFT / ≤-0.5→HARD / else→UNCERTAIN
```

## 约定

- 空行是强边界（BLANK_LINE_PARAGRAPH profile 下 0.97，其他 0.85）。
- 终止符集含右引号（'”' '」' '』'）："他说道：”" 行尾引号视为句末。
- 特殊块组（POETRY/SCRIPT/MESSAGE/LIST）→ AUTHOR_LINE_BREAK（不 prose reflow）。
- evidence 运行期存 compact mask，调试/报告展开字符串（§79）。
