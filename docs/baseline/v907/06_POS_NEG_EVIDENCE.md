# 06_POS_NEG_EVIDENCE.md — v90.7 正负证据域（TASK-050 Domain 06）

**日期：2026-08-12** | 证据：L2（L2740-2789/L3152-3267/L3327-3439/L4733-4874）

## Purpose

正/反证据加权图：结构、双阈值、冲突复核修复、证据范围。**本域确定性最强，可整体移植**。

## Edge 结构（graphAddWeightedEdge L2740）

```json
{ "score": 0..99, "count": n, "reasons": [≤12], "lastSeen": "...",
  "chapters": [≤60], "evidenceSamples": [≤20, 按 chapter+reason+key 去重] }
```
双向写边（a→b 与 b→a，L2746-2747）；score 封顶 99 累加；章去重 ENABLE_GRAPH_CHAPTER_DEDUP（上限 3000）。

## 写入 Gate（recordPositiveAliasEdge L3327 三重拦截）

1. 群体/单人 block（graphAliasMergeBlockReason L2468）
2. 关系描述 block（graphRelationDescriptorBlockReason L2689，70 词表：师徒/亲属/主从…）
3. **directPairEvidenceGate(L3343-3355)**：A 档（直接对文本锚点/白名单变体/已有严格正证）放行；B 档（身份替代/纯模型总结/无锚点）→ 冲突复核；C 档（桥接推断）拒绝

负边（recordNegativeAliasEdge L3376）：无 gate，仅冲突复核 + 章去重。

## 双阈值（§31 确切作用）

- **SOFT_BLOCK=1.0**：①反边≥1.0 构成"正边冲突"触发复核（L3166）；②最近章反图提示最低分（L5230）。**不是合并否决线**——合并由别名 LLM+gate 决定
- **HARD_BLOCK=4.0**：①正链闭合遇反边≥4.0 必须复核（L3434-3439）；②提示中标注"强排除"（L5407）。同样不单独否决合并

## 冲突复核（verifyGraphConflictAndFix L3152）

- 触发：forceVerify 或（正边且反≥1.0）或（反边且正≥1.5）
- prompt 带正反边快照+共现+上下文 2600 字；confidence<80 或失败 → defaultAllow 放行（L3230-3231）
- same_person：删反边+加正边 4.5 分（关系描述跳过）+阻断 incoming negative（L3242-3257）
- different_person：删正边 + **splitAliasByConflict** + 加反边 + 阻断 incoming positive（L3258-3267）——**冲突复核直通 merge/split**

## 证据范围（§32）

- **Global**：characterRecords.json / globalVoiceUsage
- **Book**：aliasPositiveGraph/NegativeGraph/CooccurStats/mergedRecords/voiceAgeEvidence（graphBookCacheFile L2170 按书）
- **Chapter**：边内 chapters[]（≤60）；章去重
- **Recent window**：alias_recent_chapters.json（最近 5 章，**单文件带 bookKey 字段**——换书可能污染，只进 prompt 不评分）

## 已知问题

- 双向往返写边在初始化顺序不当时可能双计（L2746-2747 vs L2788-2789）
- evidenceSamples 截断 20 条丢失早期证据
- 纯统计负边（同句 min(2.5,n×0.45)/相邻 0.7）是启发式，prompt 自认"共现误伤"需 wrongSide 纠正

## ReaderVoice mapping

**KEEP**：边结构、双阈值、复核修复语义可整体移植（→ identity_evidence 边表）；建议去掉双向往返冗余、统计负边自动落边降级为候选提示。

## Open questions

- graphConflictVerifyBudgetOk（L3172）预算上限未展开
- directPairEvidenceGate 白名单变体清单（graphIsWhitelistedNameVariant L2623）内容未展开
