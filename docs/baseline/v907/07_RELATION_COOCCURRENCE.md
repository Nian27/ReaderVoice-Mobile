# 07_RELATION_COOCCURRENCE.md — v90.7 共现/关系域（TASK-050 Domain 07）

**日期：2026-08-12** | 证据：L2（L4733-4874/L4074-4138/L3810-3901/L11695-11797）

## Purpose

共现统计与模型关系证据 → 正反图 → **直通 merge/split**。

## 调用链（L2）

```text
updateAliasGraphsFromCache(L4733)：每批对白/正文扫描
  → 相邻说话 ≥2 次 → 反边 0.7（L4836-4838）
  → 同句共现 ≥2 句 → 反边 min(2.5, n×0.45)（L4872-4874，同 pair 每章最多 6 次 L4865）
applyModelRelationEvidence(L4074)：模型 __relations → shape 预检(L662) → 暂存，等待 A+B+C 合并审计（L4125-4138，不直接落边）
applyAuditedNameSemanticRelations(L3810)：审计采纳后按 reason 落边
  → same_person → 正边 GRAPH_MODEL_NAME_IDENTITY_SCORE=5.0（L3888）
  → 反边按 reason 4.0~6.5（L3893-3901）；tier C 拒收 / tier B 转冲突复核
commitPendingGraphAuditV907(L13254)：模块不完整/审计缺失 → 整批 rejected（默认丢弃）
recordPositiveAliasEdge(包装 L11745)：落正边后 graphV87PositiveReasonCanMerge(L11731)（排除本地单源 reason，要求 L3 复合认证）
  → 直接 v87ReconcileExistingRecords("same_person") → mergeCharacterRecords —— 关系证据直通 merge 最近路径
v87RunCompoundRecordReconciliation(L11758)：复合证据 L3 认证（≥3 独立证据族+当前章锚点）→ 直接 merge/split
verifyGraphConflictAndFix(包装 L11718)：模型判 same/different 且 conf≥80 → 直接 reconcile → merge(11706)/split(11713)
```

## 常量语义（§33 确认）

| 常量 | 值 | 实际语义 |
|---|---|---|
| COOCUR_MAX_NAMES | 50 | 每章扫描角色名上限（L4765 截断） |
| COOCUR_MAX_SENTENCES | 260 | 同句共现扫描句子上限（L4843） |
| COOCUR_NEG_SENTENCE_MIN | 2 | 同句共现 ≥2 次 → 反边（L4872） |
| COOCUR_NEG_ADJACENT_MIN | 2 | 相邻说话 ≥2 次 → 反边 0.7（L4836） |

## 关系描述拦截（§35 确认）

- `graphRelationDescriptorBlockReason`(L2689)：名字含师徒/亲属/主从等 **70 词表**（L2679）→ 拒绝正边（L3337-3342）
- 群体/单人互斥块（L2468）在 L3331-3336 拦截；merge 时排除被 block 别名（L5658）
- **"师父/老婆/妈妈 不会单独成为 same-person 正证据" CONFIRMED_L2**（此前 L1 → 升级）

## 已知问题

- 统计反边是纯启发式（共现误伤需 wrongSide 纠正，L3059）
- applyModelRelationEvidence 的 defer 使图谱落地滞后于角色卡创建
- 合并审计失败 → 整批不落边（本地无降级路径，L13262-13266）

## ReaderVoice mapping

**KEEP**：共现统计 + 模型关系证据 + 冲突复核的"提示驱动 merge"架构；建议纯统计负边自动落边改为候选提示；ReaderVoice 的 relation evidence 表直接对应。

## Open questions

- 合并审计失败是否应本地降级而非整批丢弃
- 正链闭合产生的边（applyPositiveChainClosure L3408）是否应默认低置信
