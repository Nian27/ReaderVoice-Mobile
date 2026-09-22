# LAYOUT_PROFILE_SCHEMA.md — 布局画像模型（TASK-030 §7-§14/§82）

**日期：2026-08-12** | 由 LayoutProfiler 产出；必须先做 Profile 再判 Boundary（§6）。

## TextLayoutProfile（§7）

```text
PARAGRAPH_PER_LINE / FIXED_WIDTH_HARD_WRAP / BLANK_LINE_PARAGRAPH / INDENTED_PARAGRAPH /
MIXED / SCRIPT_DIALOGUE / POETRY_LIKE / UNKNOWN
```

> POETRY_LIKE 是 Layout Profile；POETRY 是 BlockType——两个概念不混用。

## BookLayoutProfile 统计字段（§9 冻结）

```text
nonblank_line_count / median_line_length / mean_line_length / std_line_length
length_histogram（5 字宽桶）/ dominant_modes[]（Top3）
blank_line_ratio / leading_fullwidth_indent_ratio / leading_ascii_indent_ratio / leading_tab_ratio
terminal_punctuation_ratio / colon_end_ratio / colon_contain_ratio / quote_open_ratio / quote_cross_line_ratio
separator_ratio / very_short_line_ratio（≤6）/ very_long_line_ratio（>1024）
```

## 分类规则（初始工程值，Gold 校准）

```text
separator > 0.05                          → MIXED
veryShort>0.5 且 terminal<0.3 且 med<24 且 std<8 → POETRY_LIKE
colonContain>0.5 且 med<30                → SCRIPT_DIALOGUE
nearMode≥0.45 且 mode≥8 且 terminal<0.55 且无缩进 → FIXED_WIDTH_HARD_WRAP
blankRatio≥0.15                           → BLANK_LINE_PARAGRAPH
fwIndent∈0.3..0.95 且 terminal>0.5        → INDENTED_PARAGRAPH
terminal>0.6 且 blank<0.12 且 (std>2 或 unique>3) → PARAGRAPH_PER_LINE
veryLong>0.1                              → UNKNOWN
否则                                      → MIXED
```

## 分层与 Region（§8/§81/§82/§83）

- 三层：BookLayoutProfile → ChapterLayoutProfile（Chapter 是天然 Region 边界，但不假设每章同格式）→ LocalLayoutProfile（滑动窗口 200 行）。
- LayoutRegion{start_line,end_line,profile_type,confidence}；每 Boundary 引用所属 region。
- 阈值/窗口大小均标注为 bootstrap，TASK-030 Gold 后校准。

## 已知行为

- 全行缩进（>95%）→ 缩进失去段首区分力 → 回退 PARAGRAPH_PER_LINE 判定（§14）。
- fixed-width 末行不满 wrap 宽度是常态（nearMode 阈值 0.45）；段末句号落行尾是常态（terminal < 0.55）。
