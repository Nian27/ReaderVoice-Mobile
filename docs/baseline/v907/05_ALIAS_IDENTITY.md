# 05_ALIAS_IDENTITY.md — v90.7 别名/身份域（TASK-050 Domain 05）

**日期：2026-08-12** | 证据：L2（L893-924/L1149-1187/L4907-4915/L5618-5635/L6558-6601）

## Purpose

Mention-like 名字 → 归一 → 别名映射 → 正负证据 → 裁决 → merge 候选。

## 真实调用链

```text
新名字
→ getAllCharacterNamesAndAliases(L6558)：同性别过滤 → 前 100（MAX_ALIAS_CHECK_CHARACTERS）→ 主名+别名 → 重建 nameToMainNameMap(L6601)
→ checkAliasByApi(L6608)：同性别本地列表 + 图谱提示 + 最近 N 章提示 + 关系/年龄审计合并请求
→ voteAliasAnalyzeResult(L1149)：mainName 计数 → 平票取最晚 → 无有效结果取最晚
→ graphAliasBuildCandidateMap(L4907)：候选集合 + nameToMainNameMap 展开
→ 本地 block：graphAliasMergeBlockReason(L2468 群体/单人) / graphAliasCheckReasonContradiction(L2568 原因自相矛盾→强制非别名 L6853)
→ 裁决：isAlias? → merge 候选
```

## nameToMainNameMap ≠ Identity Graph（§28 确认）

- **nameToMainNameMap**：内存"本地名→主名"重定向表（L924 写 characterManager），**不持久化**，三处重复构建逻辑（L893-927/L1156-1187/L5618）易漂移
- **positive/negative evidence graph**：持久化加权边（按书），不同数据结构 ✓

## Negative evidence 形成判定（§29）

| 场景 | 是否反证 | 依据 |
|---|---|---|
| 同场出现/共现 | ✅ 反边 | 模型 co_presence(L6167) + 本地 same_sentence 反边(L4872) |
| 对话互称 | ✅ 反边 | dialogue_relation(L6164) |
| 关系称谓（师父/弟子/道侣） | ✅ 反边 + 禁入正图 | social_relation(L6166) + ENABLE_RELATION_DESCRIPTOR_POSITIVE_BLOCK(L118/L3337-3342) |
| 同姓 | ❌ 不反证 | 仅 prompt "必须判非别名"（L6670-6677 王局≠王建国），本地无反边 |
| 简称/昵称 | ❌ 不反证 | 只作正证候选（模型 __relations name_identity） |
| 性别冲突 | ❌ 不反证 | 候选列表按性别**硬过滤**（L6563-6568），跨性别不进入比对 |
| 年龄冲突 | ❌ 不反证 | 别名校验 prompt 无年龄字段；年龄走独立 voice age 审计 |

## ReaderVoice mapping

**INVESTIGATE**：映射表逻辑冗余且同姓/简称判定完全靠 prompt；ReaderVoice 建议 Mention 解析 + Identity Evidence Graph（TASK-060），性别硬过滤保留为候选约束而非反证。

## Open questions

- 同姓判定是否纳入确定性规则（ReaderVoice 目标）
- nameToMainNameMap 三处构建逻辑是否统一为单一物化视图
