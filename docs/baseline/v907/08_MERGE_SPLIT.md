# 08_MERGE_SPLIT.md — v90.7 合并/拆分域（TASK-050 Domain 08）

**日期：2026-08-12** | 证据：L2（L5639-5670/L11178-11351）

## Purpose

角色合并/拆分全流程：trigger/candidate/verification/state mutations/backup/restore。

## Merge（mergeCharacterRecords L5639）

```text
trigger：别名投票 isAlias+mainName 有目标卡（L7397）或冲突复核 same_person（L11706/11722）
流程：
  source 深拷贝 → target.mergedRecords（上限 20，L5646-5647）
  → 额外独立备份表（storeMergedRecordBackup L11191：key=merge_<hash>，schema v87_bound_merge_backup，
     restorePolicy="only_when_splitting_this_target_alias_after_conflict_different"，上限 240 按时间淘汰）
  → aliases 并集去重 + 群体 block 过滤（L5650-5659）
  → chapters 并集 + trim → genderAgeHistory 拼接（L5666-5668）
  → splice 移除 source（L5669）→ rebuildNameToMainNameMap（L5670）
  → saveRecords（L5700）
```

**merge 是否原子（§37）：否** — 单对象逐步原地修改（先改 target 再删 source），无事务/回滚；try/catch 仅包备份段（L5642-5649），后续异常留下半合并态。**Legacy inconsistency risk 确认**。

## 备份（§38）

- 时机：每次 merge 双写（记录内 20 条 + 独立表 240 条）
- 位置：`mergedRecords.v907.<bookKey>.json`（按书，L11188）
- 恢复：仅 splitAliasByConflict 且该别名确为被合并 target 的别名（v87FindBoundMergeBackup L11234：status="merged"+同 book+target/source 双向命中）
- **fixed 字段不迁移**：merge 不动 target.voice；source 固定音色随卡删除丢失（L9969 只保留字段）

## Split（splitAliasByConflict L11286）

```text
trigger：冲突复核 different_person（L3260/L11713）
流程：removeAliasFromRecord(L11299)
  → v87FindBoundMergeBackup(L11234) 命中 → 克隆恢复原卡 + status="restored"（L11308-11316）
  → 否则记录内 mergedRecords 备份（L11322-11334）
  → 再无 → assignVoice 新建 + restoreVoiceWithFallback（L11336-11346）
  → 加 4.5 反边（L5696）→ rebuildNameToMainNameMap + saveRecords（L11350-11351）
```

voice/history 恢复：split 用 backup 克隆（含 voice）；无备份则新建分配。

## 已知问题

- 非原子（半合并态风险）；备份恢复是启发式匹配（必须命中 merge 绑定）
- v87ChooseMergeTarget(L11686) 权重 = chapters×10+aliases×3+usageCount/100——可能把含固定音色卡作为 source 吞掉
- 8 秒内重复 merge 仅告警不阻断（L11272-11284）

## ReaderVoice mapping

**REDESIGN**：事务式 merge（先备份后原子提交）+ 可回滚历史表（TASK-040 CorrectionEvent/Lineage 已有基础）；拆分恢复绑定显式 recordId 而非启发式匹配；fixed voice 迁移策略需显式定义。

## Open questions

- merge 后 target.recordId 稳定性（v87EnsureRecordId 只对无 id 者生成，合并不换 id）
- fixed voice 在 merge 时的迁移策略缺失是否有意为之（TASK-060 需定义）
