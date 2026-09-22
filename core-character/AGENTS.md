# core-character/ AGENTS.md

角色语义系统：Mention → NarrativeEntity → Identity → Embodiment → VoiceState。**本模块不含音色/模型**（Voice 见 tts-cosyvoice，模型见 core-director）。

## 不变量

```text
1. Character != VoicePack —— 只通过 VoiceBinding 关联（lock_mode: AUTO/USER_SELECTED/USER_LOCKED）
2. Mention != Identity != Embodiment != VoiceState —— 五层分离，各表独立
3. UNKNOWN 合法：UNKNOWN_SPEAKER_003 可后续回填，禁止强造"神秘男子/黑衣人"
4. False Merge > False Split：merge 需要语义 gate + damage gate（影响已生成内容越大阈值越保守）
5. 关系称谓（师父/老婆/妈妈）默认是关系 Mention，不得自动成为 same-person 正证据（v90.7 有拦截层，见下）
6. Group(众人/二老) 与 Person 禁止自动互并
7. 任何 Merge 可回滚：保留 Mention 原证据，不物理删除；损坏回滚依赖备份（v90.7 mergedRecords 模式）
8. 用户锁定（USER_LOCKED）不被模型/规则覆盖
9. Performance Memory 因果性：情绪/关系/秘密等状态只能用当前时间点之前的证据
```

## 三层 VoiceState（v5 §41-43，v90.7 已验证）

```text
USER_LOCKED > TEMPORARY_OVERRIDE > VOICE_PHASE > BASE
临时状态（伪装/模仿/压嗓）只覆盖朗读层标签，不写角色卡（v90.7 TEMPORARY_VOICE_STATE 行为）
年龄分 chronological / appearance / voice 三份，VoiceMatcher 只看 voice_age
```

## 迁移语义（v90.7 行为真源）

- 正负证据阈值三档：正 1.5 / 负 soft 1.0 / 负 hard 4.0（强排除）；正反图冲突复核、关系描述拦截、群体互并拦截 —— 详见 `research/third_party/legado-v907/V907_CAPABILITY_MATRIX.md` §2B/§2C。
- v90.7 跨书污染教训：**Room 全部表强制 bookId 非空**，禁止 "default" 回退键；全局跨书共享仅允许音色使用计数（globalVoiceUsage 语义）。

## Do / Don't

- Do：Alias 需要正反证据双向累计；Same Name != Same Person（保留场景/章节/关系证据）；跨章状态用 TemporalStateInterval（start/end segment），不依赖用户顺序阅读。
- Don't：不要在没有证据时 Merge；不要用表面主语判断发声主体（附身/傀儡：speaker_identity 跟真正发声者）；不要删除 Mention 原证据。
