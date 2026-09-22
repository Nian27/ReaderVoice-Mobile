# TASK030_PARAGRAPH_REPORT.md — 段落恢复报告（TASK-030）

**日期：2026-08-12** | 指标来自 `./gradlew :parser:test`（全套件含历史测试全绿）

## 各 fixture 段落结果（§101）

| fixture | 段落数 | 正确性 | 说明 |
|---|---|---|---|
| fixed_width_20 | 2 | ✅ Exact Match（G4） | 20 字断行合并，中文无空格 |
| fixed_width_40 | 2 | ✅ Exact Match | |
| paragraph_per_line | 3 | ✅ | 不误合并（全角缩进段） |
| blank_line_paragraph | 3 | ✅ | 空行分段 |
| poetry | 4 | ✅（G5） | 每行独立 + POETRY 块 |
| script | 3 | ✅（G5） | 每行独立 + SCRIPT 块 |
| message_log | 4 | ✅ | 时间行独立 + 内容行独立 |
| list | 3 | ✅ | 每项独立 + LIST 块 |
| separator | 3 | ✅ | 分隔符行不产段（3 正文段） |
| speech_cue | 2 | ✅（G6） | cue 两行保留 + SPEECH_CUE link 0.98 |
| quote_cross | 1 | ✅ | 短引语合并（QUOTE_CONTINUES 证据） |
| english_wrap | 2 | ✅ | 英文加空格合并 |
| mixed_join | 2 | ✅ | "使用 Transformer 模型进行分析。" |

## 指标（§61-§65）

```text
JOIN/BREAK：由 13 个 gold fixture 断言覆盖（Exact Match 判定）
Exact Paragraph Match：fixed_width_20/40 100%（G4）
SpeechCueLink：100%（G6，0.98 置信）
OverMerge/OverSplit：paragraph_per_line 3/3 无合并；script/poetry 无 reflow（G5）
NormalizedSourceMap round-trip：非合成字符 100%（G7）；synthetic 空格 source=null（G8）
```

## 可解释性（§78）

```text
Line 120 → 121  Decision: SOFT_WRAP  Confidence: 0.99
Evidence: FIXED_WIDTH_PROFILE / PREV_AT_WRAP_WIDTH / PREV_NO_TERMINAL / QUOTE_CONTINUES
```

## 错误分类（§76）

- BOUNDARY_RULE_ERROR：右引号终止判定（修复）；SCRIPT/MESSAGE 判定顺序（修复）
- SPECIAL_BLOCK_ERROR：行对级 poetryLike 误伤 → 组级（修复）
- QUOTE_STATE_ERROR：实例状态泄漏 → 外部推进（修复）
- TEST_EXPECTATION_ERROR：fixture 内容变更后断言未同步（修复）；nearModeRatio 死断言（删除）
- GOLD_LABEL_ERROR：fixed40 阈值期望（末行不满为常态）

## Gate 摘要

G1 ✅（TASK-010 round-trip 全过）｜ G2 ✅（chapter anchor 不跨段）｜ G3 ✅（USER lock 测试）｜ G4 ✅（hard-wrap Exact 100%）
G5 ✅（poetry/script/message 不 reflow）｜ G6 ✅（SPEECH_CUE 100%）｜ G7 ✅（normalized→source 100%）｜ G8 ✅（synthetic 不伪造 offset）
G9 ✅（TASK-010 回归全过）｜ G10 ✅（3M+ 无 OOM）｜ G11 ✅（无 Speaker/LLM/TTS）｜ G12 ✅（secret_scan/verify_baselines PASS）
**G13 = OPEN**（20-30 真实 TXT Gold 未建立）
