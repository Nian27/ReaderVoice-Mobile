# PLAN-TASK-020 — Chapter / TOC / Volume Structure Compiler

**日期：2026-08-12（TASK-010 PASS 后）** | **状态：执行中**

## Goal

真实 TXT 中的卷/章/序章/楔子/番外/后记/目录条目 → 稳定解析为结构化 Book Navigation（Book └ Volume └ Chapter），任何 Chapter Boundary 可 round-trip 回 TASK-010 Source offsets。

## Current Verified Facts（TASK-020 Step 0 审计结论）

- Legacy 资产：`FUN-legado/app/src/main/assets/defaultData/txtTocRule.json`，26 条规则（12 enabled / 14 disabled），每条 `{id, enable, name, rule, example, serialNumber}`。
- **serialNumber = 规则排序键**（`TxtTocRuleDao order by serialNumber`，TxtTocRule.kt:17），非章节序号——语义已从执行代码确认。
- **标题 = 匹配文本原样**：txtTocRule.json 无 replacement 字段 → `TextFile.replacement()` 返回原始匹配（replacement:552-573 空则原样）。
- **规则竞争 = 匹配数量**：`getTocRule`（TextFile.kt:501-528）：csNum（匹配数）≥ numE×3 且超过前规则+overRuleCount 才胜出；>70 提前停止；间隔 <100 字计 numE（"卷级误识别"）。
- Regex feature（26 条实测）：lookbehind ×12、lookahead ×13、alternation ×14、全部含 ^/$ 锚点；**无 backref / named group / unicode class**。→ RE2/J 不兼容面 = lookbehind/lookahead（ADR-017 Hybrid 策略）。
- 输入冻结：TASK-010 PhysicalLine + SourceMap（byte/codepoint 双轨）。

## Non-goals

- LogicalParagraph Recovery（TASK-030）、SemanticSegment/Speaker/Character/Director/TTS/Playback——全部禁止。
- 不重写 TASK-010（readAllBytes 记 PERF-010-01，不阻塞）。
- 不修改原 txtTocRule.json（Legacy 不可变）。

## Invariants

- Regex Match != Chapter（候选 → 全局 Resolver，§2.1）；Raw structure scan 先于 Paragraph Recovery（§2.2）；同一 PhysicalLine 至多一个同层级 anchor（G6）；任何最终 Chapter 可 round-trip（G7）；User Override 不被重新解析覆盖（G8）；Legacy 兼容与安全同时满足（ADR-017）。

## Scope

```text
parser/src/main/.../chapters/    ChapterRule/ChineseNumeralParser/RegexSafety/Scanner/
                                 TOC/Volume/Style/GlobalResolver/Override/Preview
parser/src/main/resources/legacy/  txtTocRule.json（来源声明 NOTICE：gedoor/Legado GPL-3.0）
tests/fixtures/chapter/            Legacy example fixtures + Gold Set + pathological regex
docs/baseline/CHAPTER_RULE_AUDIT.md
docs/protocols/CHAPTER_RULE_SCHEMA.md + CHAPTER_STRUCTURE_SCHEMA.md + REGEX_SAFETY_POLICY.md
docs/experiments/TASK020_LEGACY_BASELINE.md + TASK020_STRUCTURE_REPORT.md + TASK020_LARGE_FILE_REPORT.md
```

## Milestones

| # | 交付物 | 验收 |
|---|---|---|
| M1 | 规则资源 + CHAPTER_RULE_AUDIT.md + REGEX_SAFETY_POLICY.md | 26 条逐条审计，feature 实测 |
| M2 | ChineseNumeralParser（一~一万零三/壹佰贰拾/〇一二/0012/１２３；日期不误解析） | 全用例测试 |
| M3 | ChapterRule + LegacyAdapter + Scanner（26 规则 × 行，同线冲突，prefilter 可选） | Legacy enabled examples Candidate Recall 100% |
| M4 | TOCDetector + VolumeResolver + StyleProfiler | 密度/连续性/前部/重置语义测试 |
| M5 | GlobalStructureResolver（评分+evidence+状态）+ Override API + Preview | 可解释性输出；Override 优先级 |
| M6 | Gold Set（fixtures/chapter/）+ 指标两层 + Legacy baseline 对比 | 报告 P/R/F1 |
| M7 | 3M+ stress + pathological regex + 7 份 artifacts + Gate | G1-G12 |

## Progress

- M1 ⏳（规则审计中）
- M2-M7 ⏳

## Decisions

- 规则资源：复制入 `parser/src/main/resources/legacy/` + NOTICE 声明（gedoor/Legado GPL-3.0 来源，测试/审计用；项目 license 决策留 TASK-170）。
- 标题语义：title_raw = 匹配文本原样；title_clean = 去序号/空白（展示用，不改 source）。
- 超长行策略：>1024 字符仅跑"明确安全锚定低复杂度规则"（TRUSTED_LEGACY 白名单），阈值 Gold 校准。

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | Legacy raw regex（getTocRule 语义复刻） | 无 | 待跑 | 对比基线 |
| E2 | New Candidate+Resolver vs Legacy baseline | E1 | 待跑 | Gate G2/G3 |
| E3 | prefilter（长度/前缀/字典）| 全规则扫描 | 待跑 | 要求 recall 不降 |

## Validation

- G1-G12（见任务规格 §46）；Legacy example fixtures 100% candidate recall；3M+ 无 OOM + 时间报告；pathological regex 被拒绝不卡死；secret_scan + verify_baselines PASS。

## Rollback

- 全部纯解析层，无 DB；规则资源可从 FUN-legado 再生成。

## Artifacts

7 份文档 + 代码/测试 + fixtures。

## Open Issues

- 无编号短标题章节（"雨夜"类）：V1 不强行确认（§29），留用户规则/高可信上下文。
- 多行标题：只记 POTENTIAL_MULTILINE_TITLE，不合并（§30）。
- license 决策（GPL-3.0 规则数据）留 TASK-170。

## Handoff

- TASK-030 输入：Chapter/Volume anchors（anchor_line/byte/codepoint）+ content range 边界；Paragraph Recovery 独立进行。
