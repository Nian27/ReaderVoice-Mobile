# TASK030_LAYOUT_BASELINE.md — 布局画像基线（TASK-030）

**日期：2026-08-12** | fixtures：`tests/fixtures/paragraph/`（13 个 synthetic，自制）

## Profile 识别结果（E1）

| fixture | 期望 | 实际 | 说明 |
|---|---|---|---|
| fixed_width_20.txt | FIXED_WIDTH_HARD_WRAP | ✅ | 20 字段落内断行，段末句号落行尾 |
| fixed_width_40.txt | FIXED_WIDTH_HARD_WRAP | ✅ | 40 字段落（末行不满宽度 OK） |
| paragraph_per_line.txt | INDENTED/PER_LINE | ✅（PER_LINE） | 全行缩进 → 缩进无区分力 → 回退 |
| blank_line_paragraph.txt | BLANK_LINE_PARAGRAPH | ✅ | 空行强边界 |
| poetry.txt | POETRY_LIKE | ✅ | 短行无终止 |
| script.txt | SCRIPT_DIALOGUE | ✅ | 行内含冒号（非行尾） |
| english_wrap.txt | FIXED_WIDTH_HARD_WRAP | ✅ | 英文 hard-wrap |
| list.txt | — | ✅（POETRY_LIKE 画像，LIST 块） | 画像与块类型分离 |

## Boundary 分类（E1 关键教训）

| # | 问题 | 修复 |
|---|---|---|
| 1 | 行尾右引号 '”' 被当"无终止" → 误 JOIN（"他说道：”"） | TERMINAL 集加右引号（classifier/grouper/detector 三处统一） |
| 2 | 行对级 poetryLike 误伤所有 4-22 字行（英文/引语/时间行） | 改为 BlockGrouper 组级判定（≥3 行 + 无引号 + 无终止） |
| 3 | "张三 20:31" 被 SCRIPT_RE 抢先（含冒号） | MESSAGE_RE 判定优先于 SCRIPT_RE |
| 4 | quoteDepth 实例字段跨边界泄漏 | 外部 QuoteStack 逐行推进传入 |
| 5 | 分隔符行作为左行未判 STRUCTURAL | left/right 两侧都检查 |
| 6 | POETRY 组覆盖 LIST 组 | POETRY 不覆盖已有组 |

## 冻结阈值（bootstrap，Gold 校准）

fixed-width：nearMode≥0.45（末行不满常态）、terminal<0.55（段末句号常态）；per-line：std>2 或 unique>3；veryShort≤6。
