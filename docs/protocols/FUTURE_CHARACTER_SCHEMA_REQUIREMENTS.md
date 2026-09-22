# FUTURE_CHARACTER_SCHEMA_REQUIREMENTS.md — Character Schema Requirements（TASK-050 S5）

**日期：2026-08-12** | 仅 requirements（§100），不建表（§101，TASK-060 实现）。

## 必选能力（来自 v90.7 行为契约 + TASK-040 schema）

```text
positive evidence        正证（锚点分级 A/B/C + 阈值 1.5）
negative evidence        负证（soft 1.0 触发复核 / hard 4.0 强排除）
merge lineage            合并可回滚（CorrectionEvent + Lineage）
temporary voice interval 临时换声区间（TemporalStateInterval：start/end segment）
fixed voice              固定音色（VoiceBinding.lock_mode，USER_LOCKED 最高）
voice age evidence       音龄证据（audit→commit 两阶段）
```

## 硬性约束（v90.7 教训）

1. **book 隔离**：全部角色/证据/状态表带 book_id 外键（v90.7 4 个全局文件跨书污染 L3 复现——ReaderVoice 禁止）
2. **确定性 ID**：recordId 去 random/time（v90.7 漂移教训）
3. **事件溯源**：merge/split/临时状态变更走 CorrectionEvent（不可变）+ 当前态视图（v90.7 逐步修改非原子）
4. **语义/声学分离**：Character != VoicePack；临时换声不写角色卡（v90.7 L3 确认行为保留）
5. **UNKNOWN 合法**：v90.7"未知+旁白兜底"语义保留为显式 UNKNOWN_SPEAKER

## 建议表（TASK-060 时评审）

```text
narrative_entity（含 importance/status/uid）
identity_evidence（边表：sign/score/阈值档/锚点分级/evidence span）
voice_binding（role_id + voice_revision_id + lock_mode + valid_from/to）
voice_state（BASE/PHASE/TEMPORARY_OVERRIDE + start/end segment）
voice_age_evidence（audit→commit 状态）
character_lineage（MERGED_INTO/SPLIT_FROM/SUPERSEDES）
```

## 与 v90.7 差异声明

- 不做：3084 固定池、多远程 API 投票、全局角色卡、recordId 随机化、usageCount 双语义
- 做：三 age 分离（chronological/appearance/voice，v5 §18）、确定性置信（删 Math.random）、按书+Revision 体系（TASK-040）
