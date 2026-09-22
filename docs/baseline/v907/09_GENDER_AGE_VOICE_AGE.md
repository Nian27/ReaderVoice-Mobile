# 09_GENDER_AGE_VOICE_AGE.md — v90.7 性别/年龄/音龄域（TASK-050 Domain 09）

**日期：2026-08-12** | 证据：L2（L184-209/L8659-8694/L11984-12002/L12454-12546/L13492-13585）

## Purpose

12 档性别年龄体系、音龄证据审计、固定音色与自然变声仲裁。

## 12 档真实定义（§41 确认）

- MAIN_ROLES_CONFIG(L184-187)：主角男主×20 / 女主×20（age 字段="男主"/"女主"）
- BATCH_ROLES(L189-202)：**10 档 × 300**（少女/少年/女青年/男青年/女中年/男中年/女老年/男老年/女童/男童）+ 特殊男/特殊女 ×20
- SPECIAL_ROLES(L204-209)：系统/特殊/旁白 ×20
- 总计 3084 个 voice tag（GENSHIN_CHARACTERS L211-244）

## Age 归一（graphV907NormalizeAgeForVoice L8668）

关键词级联：旁白→系统→童(幼/童/小孩)→少女/少年(按性别)→老(婆/妪)→中年(壮年)→青年(年轻/成年/女子/修士/道士)；未识别返回原值；空值默认女青年/男青年/系统。

## age 与 voiceAge 是两个真实概念（§43 CONFIRMED_L2）

- `record.age`：卡片年龄（merge/genderAgeHistory/别名流程可改）
- `voiceAgeVerified / verifiedVoiceAgeStage`：审计验证过的自然音龄状态
- 两者在 applyPersistentVoiceAgeEvidence 中同步更新（L13534-13557），但 graphV907VoiceSegmentKey 只以 age 归段（L8690-8694）

## 音龄更新（applyPersistentVoiceAgeEvidence L13492）

- 更新条件：accepted && stateAction==="persistent_update"
- 拒绝条件：**fixed guard 拒绝（L13496-13501）**、证据哈希去重（L13503-13509）、同段跳过不重分配（L13520-13525）
- 跨段执行：saveAgeVoiceBindingBackup（上限 12）→ findAgeVoiceBindingBackup 恢复旧绑定 → 否则 assignVoice 重分配（L13531-13547）
- cue 冲突仲裁：graphV907VoiceAgePriority = voice=3 / direct_age=2 / appearance=1 / 其他 0（L11984）

## 已知问题

- age 与 voiceAge 双字段可能漂移（只有一处同步点）
- 同段证据被永久跳过，后续段定义变化无法重审
- age 与 voiceAge 冲突时以 evidence.finalVoiceAgeStage 为准但 record.age 被直接覆盖

## ReaderVoice mapping

**KEEP** 音龄证据审计两阶段（audit→commit）+ fixed guard；建议 ReaderVoice 统一 age/voiceAge 为单一概念（v5 §18 三 age 分离：chronological/appearance/voice 各自独立，比 v90.7 更清晰）。

## Open questions

- 音龄证据与卡片 age 冲突仲裁的最终语义
