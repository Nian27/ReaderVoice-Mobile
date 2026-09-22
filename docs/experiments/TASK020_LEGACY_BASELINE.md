# TASK020_LEGACY_BASELINE.md — Legacy Raw Regex vs 新 Candidate+Resolver 对比（E1/E2）

**日期：2026-08-12**

## E1：Legacy Raw Regex Baseline（getTocRule 语义复刻）

legado 原始行为（TextFile.kt 501-528 确认）：每条 enabled 规则在全文跑，选**匹配数最多**的规则（`csNum ≥ numE×3 且 > 前规则+overRuleCount`，>70 提前停）；标题 = 匹配文本原样；间隔 <100 字计"卷级误识别"。

| 指标 | 值 |
|---|---|
| gold 书 raw 匹配数（最优规则） | 37（含正文行误报） |
| 陷阱行处理 | **无**（2026/520/10:30/二零二六年 全部被算入匹配） |

## E2：新 Candidate+Resolver

| 指标 | Legacy baseline | 新管线 |
|---|---|---|
| gold 正文 Chapter | 37 个"匹配"（含误报） | **12 个确认**（10 章 + 番外 + 尾声，与人工真值一致） |
| 陷阱行（2026/520/10086/1920/二零二六年/10:30/2026-08-12） | 全部误报 | **全部拒绝**（NUMBER_ONLY_LINE / TIME_DATE_LINE / serial 证据） |
| 卷识别 | 无（-17 混在通用匹配里） | 2 卷正确（VolumeResolver 独立判据） |
| TOC | 无 | 1 块 12 条识别 + 不重复生成正文章（G5） |
| 可解释性 | 无 | evidence 列表（REGEX_*/SERIAL_CONTINUITY/NUMBER_ONLY_LINE...） |

## G2/G3 结论

- **G2 PASS**：新 Resolver 正文 Chapter Recall 不降（gold 12/12 与人工真值一致）；
- **G3 PASS**：高风险 PURE_NUMBER/日期/时间 false positive 从"全部误报"降到 0。

## 教训（实现期）

1. **-17 规则 family 误标（VOLUME → STANDARD_ZH）**：同线冲突被优先级放大，导致所有章节行变"卷"、TOC 全灭——**family 必须按规则真实语义审计，不能只看 example**。
2. **正文行误报**："正文(?!完|结)" 词使"正文内容…"正文行成为候选——legacy 靠匹配数竞争容忍，新管线用 HAS_SENTENCE_END + PROSE_LINE_LIKE + serial 邻域过滤。
3. **序列是证据**：97,98,100,101（作者漏号）不得拒绝（4/4 保留）；卷重置合法。
