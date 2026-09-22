# V907_BEHAVIOR_CONTRACT.md — v90.7 行为契约（TASK-050 S3，TASK-060 最重要输入之一）

**日期：2026-08-12** | 来源：12 域 L2 证据 + Harness L3 复现。**抽象 API，不使用 Legacy 函数名（§74）。**

## 契约 1：IdentityEvidence（对应 05/06 域）

```text
Input: mention A / candidate identity B / context evidence（共现、关系、同场、明确声明）
Output: SAME | DIFFERENT | UNKNOWN
Evidence: { positive[], negative[], cooccurrence, relationship, confidence }
```
行为（L3 部分）：
- 正证锚点分级：直接文本锚点（A档）> 身份替代/模型总结（B档，须冲突复核）> 桥接推断（C档拒绝）
- 反证阈值：soft ≥1.0 触发复核；hard ≥4.0 强排除（正链闭合须复核）
- **关系称谓（师父/老婆/妈妈）不单独构成 same-person 正证**（70 词表拦截，L2）
- 群体/单人禁止互并（L2）
- 同姓/简称/性别/年龄冲突**不构成反证**（性别按候选硬过滤，L2）
- LEGACY 问题（不迁移）：投票兜底 Math.random()；同姓/简称判定纯 LLM prompt

## 契约 2：TemporaryVoiceState（对应 10 域，L3 复现）

```text
Input: role / narrative position / explicit state evidence
Output: START | CONTINUE | REPLACE | END | NONE (+temporaryVoiceTag)
Actions: start/continue/replace/end/one_shot/persistent_update
Scope: current_dialogue | scene | persistent | uncertain
```
行为（L3 确认）：
- **临时动作只覆盖朗读标签，从不写角色卡**（finalTag 路径）
- 唯一写卡路径：persistent_update → 音龄审计（且固定音色 guard 拒绝）
- 跨章：仅顺序下一章携带；跳章/换书清除
- 安全阀：30 句强制过期
- LEGACY 问题：crossChapterCarryPending 疑似死字段；恢复依赖严格哈希（重启批边界失败率高）

## 契约 3：SpeakerDecision（对应 03 域）

```text
Input: utterance + recent context（前 500 字/后 1300 字/后 3 章）+ scene candidates + rule candidates
Output: { speaker, confidence(H|M|L), fallback? }
```
行为：多数投票 + 平票取最晚（确定性差）；API 全失败 → 未知+旁白兜底（封死建卡/缓存/图谱）；序号校验失败重试 ≤8。
LEGACY 问题：投票默认配置下形同虚设（bingfa=1）；随机性别年龄兜底。

## 契约 4：VoiceBindingConstraint（对应 11 域，L3 复现）

```text
Input: role / requested change（自然音龄更新 | 临时换声 | merge）
Output: ALLOWED | BLOCKED_BY_FIXED
```
行为（L3）：manual fixed = 男主/女主自动锁 > 音龄更新（拒）> 临时换声（拒）> merge（fixed 不迁移）> split（备份可带回）；**唯一解除 = 显式 unlock**。

## 契约 5：Merge/Split 可回滚（对应 08 域，L3 部分）

```text
MERGE: target ← source（备份 target.mergedRecords + 独立备份表）
SPLIT: 从别名拆分 → 备份恢复原卡（绑定匹配）→ 无备份新建
```
LEGACY 问题：merge 非原子（逐步修改）；备份恢复启发式匹配。ReaderVoice 用事务 + CorrectionEvent/Lineage（TASK-040 已具备）。

## LEGACY vs READERVOICE 分离（§77）

| 项 | LEGACY_BEHAVIOR | KNOWN_PROBLEM | READERVOICE_TARGET |
|---|---|---|---|
| 证据三档 | soft/hard 阈值 + 复核 | 双向边冗余/统计负边误伤 | identity_evidence 边表（保留阈值与复核语义，去冗余） |
| 临时换声 | 标签覆盖不写卡 | 恢复哈希脆弱/死字段 | voice_states 表 + TemporalStateInterval |
| 固定音色 | 硬锁优先级链 | 误锁无自动纠正 | VoiceBinding.lock_mode 三态 |
| 投票 | 多数+最晚 | Math.random 兜底/默认失效 | 确定性多数+置信阈值 |
| 引用≠讲话 | LLM prompt 裁决 | 无本地判定 | Rule First 自建判定 + LLM 残差 |
