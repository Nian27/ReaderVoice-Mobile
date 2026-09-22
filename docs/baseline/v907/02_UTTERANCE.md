# 02_UTTERANCE.md — v90.7 对话/发声判定域（TASK-050 Domain 02）

**日期：2026-08-12** | 证据：L2（L7797-8357/L6094-6169 控制流）

## Purpose

引号块提取与"引用≠讲话"判定（quote == speech 的否定路径）。

## Entry points

`parseNameAnalysisOuterQuoteBlocks`(L8217) / `repairNameAnalysisBatchText`(L8206) / `cleanDialogText`(L7797) / `generateBatchSeqContent`(L8335) / `graphSpecialSpeakerType`(L2532)

## Functions

- `parseNameAnalysisOuterQuoteBlocks`(L8217) — 只解析最外层完整“…”块，同形嵌套不重复编号（depth 计数 L8219-8241）
- `repairNameAnalysisBatchText`(L8206) — 清音效/【n】/跨行修复引号对/删除『』「」‘’变体
- `cleanDialogText`(L7797) — 缓存匹配用清洗：**删除所有引号字符**（缓存键忽略引号类型）
- `mapNameAnalysisDialoguesToBlocks`(L8247) — LCS 严格对齐，重复短对白歧义"宁可失败不猜"（L8310-8318）

## "引用≠讲话"判定（核心发现）

**本地无启发式函数**（`looksLikeNonSpeechEvidence` 不存在，L11469 注释"本地 speaker/action 槽位语义判定代码已删除"）——非发声判定**整体委托 LLM**（姓名分析 prompt 第一步，L6094-6103）：引号块须先判实际发声 vs 叙述宾语/定语/同位语/术语/称号/引用词/强调词/物品名/生物名；非发声 → name=旁白、gender=特殊、age=旁白，且**禁止把物品/法宝/地点/事件/动作对象/术语/生物名作为 name**（L6102-6103）。
本地两道硬性兜底：
1. `ENABLE_NARRATION_OBJECT_NAME_FIX`(L7310-7315)：age=旁白 且 name≠旁白 → 强制改名"旁白"（物品/地点/事件名永不进角色列表）
2. `ENABLE_SPECIAL_SPEAKER_BYPASS`(L7316-7321)：旁白/系统不进角色列表/不走别名校验/不写图谱

书名号《》、墙上文字、心理活动、系统消息同样在 prompt 规则区处理（L6101-6169），本地无正则。

## 已知风险

- `cleanDialogText` 删引号 → 缓存匹配对引号类型变化不敏感（旁白引用可能误命中对白缓存）
- `repairNameAnalysisBatchText` 删所有单引号变体——单引号真实对白会被抹掉且不入序号
- 心理活动（心想/暗道）是否算"发声"在 prompt 无显式规则——**行为未定义**

## ReaderVoice mapping

**REDESIGN**：本地需自建"引用≠讲话"判定（Rule First），或保留 LLM prompt 契约；ReaderVoice 应比 v90.7 更确定（书内引用/墙上文字/系统消息可规则化）。

## Open questions

- 心理活动判定契约缺失（TASK-060/070 需定义）；
- 单引号对白的处理策略。
