# 04_CHARACTER_RECORD.md — v90.7 角色记录域（TASK-050 Domain 04）

**日期：2026-08-12** | 证据：L2（L5528-5562/L5639-5670/L7595-7607/L11156-11351）

## Purpose

角色卡真实 schema、创建条件、合并/拆分修改。

## 真实 record schema（新建于 L7595-7607）

```json
{
  "name": "...",
  "aliases": ["..."],           // 初始=name
  "gender": "...",
  "age": "...",
  "voice": "...",
  "usageCount": 100,            // CONFIG.resetUsageCount
  "chapters": ["当前章"],
  "genderAgeHistory": [],
  "voiceAgeVerified": false,
  "voiceAgeProvisional": true,
  "voiceAgeSource": "initial_model_without_audited_evidence",
  "recordId": "char_<hash(bookKey|name|voice|chapters|now|random)>",  // L11156-11160，含随机数
  "fixedVoice*": ["voiceLocked","fixedVoiceLocked","manualVoiceLocked","fixedVoiceTag",...],  // L8736-8790
  "mergedRecords": [{"source": "...", "restored": false}],  // 上限 20（L5641-5647）
  "ageVoiceBindingBackups": []   // L9969 保留字段
}
```

## 创建条件（Character Creation Gate，§26）

- `newCharacterName !== "未知"` 且未匹配已有记录（findCharacterRecord L7333 → 别名校验 L7338 → isAlias+mainName 有目标卡则复用不新建 L7397）
- 旁白/系统 BYPASS 不建卡（L7316-7321）；物品/地点/事件名强制改名"旁白"不建卡（L7310-7315）
- **误创建风险**：物体名/称谓若被判为正常说话人且别名校验判非别名 → 正常建卡（如"范夫人"建独立卡）——只有 age=旁白 路径被拦截

## Merge/Split 对 record 的修改

- merge：source 深拷贝进 target.mergedRecords（上限 20）→ aliases 并集（跳过群体块）→ chapters 并集 → genderAgeHistory 拼接 → splice 移除 source → rebuildNameToMainNameMap
- split：removeAliasFromRecord → 优先从 v87 绑定备份恢复原卡（restorePolicy="only_when_splitting_this_target_alias_after_conflict_different"）→ 否则记录内 mergedRecords 备份 → 再无则 assignVoice 新建

## 已知问题

1. **recordId 含 Math.random()+now**（L11160）——同一记录重建前 ID 不稳定，备份绑定漂移
2. **characterRecords.json 全局无 book 隔离**（跨书污染点，§57）
3. **usageCount 双语义**：100→0 重置（L7686-7687）与 usageCount===100 提前 return（L7642/7647）叠加

## ReaderVoice mapping

**REDESIGN**：schema 可用但需确定性 recordId（去 random/time）、按书隔离（TASK-040 schema 已具备）、去 usageCount 双语义；Entity Admission Gate 需比 v90.7 更严（称谓/物体名规则化）。

## Open questions

- merge 后 target.recordId 是否保持稳定（v87EnsureRecordId 只对无 id 者生成）
- 称谓误建卡的消解策略（TASK-060 Entity Admission Gate 输入）
