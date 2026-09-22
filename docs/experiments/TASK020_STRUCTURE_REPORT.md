# TASK020_STRUCTURE_REPORT.md — 结构解析报告（TASK-020）

**日期：2026-08-12** | 全部指标来自 63 个自动化测试（`./gradlew :parser:test`）

## Legacy 审计（§56）

- 26 条规则（12 enabled / 14 disabled）逐条审计：`docs/baseline/CHAPTER_RULE_AUDIT.md`；
- serialNumber = 排序键（从 TxtTocRuleDao/TextFile.kt 源码确认）；标题 = 匹配原文（无 replacement）；
- regex 特征：lookbehind×12 / lookahead×13 / alternation×14 / 全锚点 / 无 backref-named-unicode；
- 高风险：-5/-6/-7（纯数字）、-18（任意顶格短行）、-25（激进通用）、-100（**空 regex**，REJECTED）。

## Regex 安全（§5）

- Hybrid 策略（ADR-017）：26 条 TRUSTED_LEGACY + 行长度上限 1024 + 用户规则 danger-pattern 拒绝；
- `RegexSafetyAnalyzer`：NESTED_QUANTIFIER / AMBIGUOUS_ALTERNATION / EMPTY_REGEX / UNBALANCED_PAREN 全部有测试（G9）；
- 不换 RE2/J（19/26 含 lookaround，兼容优先）。

## Numeral Parser（§10/§11）

- 支持：一/十一/十二/二十/一百零八/两千三百/一万零三/壹佰贰拾/〇一二/零一二/0012/１２３（全角）；
- 只答"序号是多少"，不答"是不是章节"；"二零二六"→2026 由 Resolver 负例拒绝。

## Candidate 层（§45）

| 指标 | 值 |
|---|---|
| enabled legacy examples candidate recall（G1） | **12/12 = 100%**（-1/-14 需前置空白上下文，CONTEXT_AUGMENTED 记录，非静默修正） |
| gold 书 candidates / 千行 | 125 / 59 行（正文行误报候选包含在内，由 Resolver 过滤） |
| 同线冲突 | 一行多规则 → 1 winner（G6，alternatives 保留） |

## TOC（§18-20）

- gold：1 块 / 12 条 / 前部 15% 内 / serial 连续；**不重复生成正文 Chapter（G5）**；
- 判据：密度 + monotonic + nearFront + VOLUME 终止 + serial==null 终止 + 间隔密度 ≤30%。

## Volume（§21-22）

- gold：2 卷（serial 1/2）正确；卷后 serial 重置合法（G4：卷二第1章保留）；
- 卷识别独立判据（第N卷/卷N 词），与 family 解耦。

## Final Resolver（§35-37）

- gold：CONFIRMED 10 / PROVISIONAL 0 / TOC_ENTRY 12 / 卷 2（正文行全部 REJECTED）；
- evidence 可解释（REGEX_* +0.7 / SERIAL_CONTINUITY +1.2 / NUMBER_ONLY_LINE -1.5 ...）；
- 序列缺号（97,98,100,101）不拒绝（4/4）；番外/尾声正常；PURE_NUMBER 陷阱全拒。

## Override / Preview（§38-40）

- Override API：ADD/REMOVE/CHANGE_TYPE/CHANGE_TITLE/ASSIGN_VOLUME，USER_LOCKED 优先（G8 测试通过）；
- Preview：`检测到 12 章（CONFIRMED 10...）/ 卷 2 / 目录块 1 / 规则分布 / 低置信列表`。

## 错误分类（§56 要求）

| 类别 | 记录 |
|---|---|
| implementation bug | CRLF 幽灵行（TASK-010）、-17 family 误标、TOC 块吞正文、序列邻域污染、全角数字 append(Int) |
| legacy-rule bug | -100 空 regex；-17 词表含"正文"致正文行误报（legacy 靠匹配数竞争容忍） |
| test bug | 0/1-based 行号、emoji 偏移期望、卷数断言、方法名冒号、`$i章` 模板 |
| gold-label bug | gold 卷断言（cleanTitle 语义） |

## Gate 摘要

G1 ✅ 100% recall ｜ G2 ✅ recall 不降（12/12）｜ G3 ✅ 陷阱行 0 误报 ｜ G4 ✅ 卷重置 ｜ G5 ✅ TOC 不重复造章
G6 ✅ 一行一锚点 ｜ G7 ✅ round-trip ｜ G8 ✅ override 优先 ｜ G9 ✅ 病态 regex 拒绝 ｜ G10 ✅ 3M+ 无 OOM
G11 ✅ 无 Paragraph Recovery ｜ G12 ✅ secret_scan + verify_baselines PASS
