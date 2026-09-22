# 10_TEMPORARY_VOICE_STATE.md — v90.7 临时换声状态域（TASK-050 Domain 10，最高价值域）

**日期：2026-08-12** | 证据：L2（L12051-12150/L13622-13860/L13867-13950）

## Purpose

临时伪装/换声状态机：字段、动作枚举、scope、跨章携带、"不写角色卡"确证。

## 状态字段（创建于 L13793-13804）

```text
schema="v907_temporary_voice_state" / stateId / bookKey / chapterId / recordId
roleName / characterId / gender
temporaryVoiceAgeStage / temporaryVoiceTag / naturalVoiceTag / naturalAgeStage
startEvidenceHash|Text|Summary / startChapterId / startSeq
latestAcceptedEvidenceHash|Text|Summary / lastConfirmedChapterId|Seq
roleDialogueCount / startedAt / lastUsedAt / lastDialogueHash / lastReviewDecision
crossChapterCarryPending
```

## applyScope 真实枚举（§47 CONFIRMED_L2，prompt L6132-6134 + 强校验 L12516-12521）

```text
persistent       ↔ stateAction=persistent_update
current_dialogue ↔ stateAction=one_shot
scene            ↔ stateAction=start / continue / replace
uncertain        特殊兜底
```

## stateAction 真实枚举（§48 CONFIRMED_L2，L6133-6137）

```text
persistent_update / one_shot / start / end / continue / replace
（review decision 另含 not_applicable；coverageMode: natural_persistent/current_dialogue/through_seq/beyond_batch）
```

## "不写角色卡"确证（§49 CONFIRMED_L2/L3 路径）

`applyAuditedVoiceAgeForDialogue`(L13670) 全流程：
```text
fixed guard(L13677) → persistent_update 落卡(L13689-13695，唯一写卡路径 → applyPersistentVoiceAgeEvidence)
→ 快照恢复(L13704) → end-before(L13746) → continue(L13762) → one_shot(L13786)
→ start/replace 建/换 state(L13790-13810) → 无新证据复用 + roleDialogueCount(L13813-13828)
→ end-after(L13830) → replace-after(L13841)
```
临时动作（start/one_shot/continue/replace/end）全程只计算 **finalTag** 写入 temporaryVoiceStates/temporaryVoiceAppliedEvents，**从不触碰 record.voice/record.age**（L13704 起）；外层用返回 tag 覆盖朗读标签（L13993-13994）。**"只覆盖朗读标签不写角色卡" CONFIRMED_L2**（→ L3 由 Harness 复现）。

## 跨章（§50）

- 携带条件：同书 + **顺序下一章**（ENABLE_TEMPORARY_VOICE_CROSS_CHAPTER=1，chapterId≠当前章 → crossChapterCarryPending L13719-13729）
- 跳章/回退/换书 → 删除状态（book_mismatch L13715/disabled L13725）
- 快照持久化：dialog_cache.json 的 temporaryVoiceSnapshot（schema v907_temporary_voice_snapshot + bookKey + chapterId + lastProcessedIndex + hash 校验 L13909-13949）；恢复严格校验 schema+book+chapter+同句 hash，每快照仅尝试一次
- 安全阀：TEMPORARY_VOICE_MAX_ROLE_DIALOGUES=30 强制过期回自然音色（L13813-13828）

## 已知问题

- 状态机庞大，大部分路径只有日志无状态校验（continue 无 state 时静默）
- 30 句上限对长场景伪装提前失效
- 恢复依赖严格哈希，App 重启批边界匹配失败率高
- **crossChapterCarryPending 标记创建后从未被读取消费（仅写日志）——疑似死字段**

## ReaderVoice mapping

**KEEP**：临时换声不写卡设计正确且具差异化价值（验证 v5 §41-43 两层分离）；ReaderVoice 用显式事件表 + 单一状态源（TASK-040 Correction/Override + future voice_states 表），砍掉批内 seq 扩展复杂度；crossChapterCarryPending 死字段不迁移。

## Open questions

- crossChapterCarryPending 是否为死字段（Harness 验证）
- 30 句上限的合理替代（ReaderVoice TemporalStateInterval 按 segment 区间）
