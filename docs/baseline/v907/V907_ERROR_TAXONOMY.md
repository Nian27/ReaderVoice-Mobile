# V907_ERROR_TAXONOMY.md — v90.7 错误分类与 Hard Case 优先级（TASK-050 S6）

**日期：2026-08-12** | 依据：12 域 L2 证据 + Harness L3 复现。

## 错误分类（§102）

| 类别 | 旧系统行为 | 频率* | 严重度* | 训练价值* | 规则可修性* |
|---|---|---|---|---|---|
| FALSE_SPEAKER | 投票兜底/随机性别年龄 | UNKNOWN | HIGH | HIGH | 中（确定性裁决可修） |
| FALSE_ALIAS_MERGE | 同姓/简称误合并（纯 prompt） | UNKNOWN | HIGH | HIGH | 低（需 Identity 规则） |
| FALSE_ALIAS_SPLIT | 同场共现误伤反边 | UNKNOWN | 中 | 中 | 中 |
| RELATION_AS_ALIAS | 关系称谓误建卡（"范夫人"独立卡） | UNKNOWN | 中 | 中 | 高（词表拦截已存在） |
| GROUP_PERSON_CONFUSION | 群体/单人（有 block，残余） | UNKNOWN | 中 | 中 | 高 |
| CHARACTER_DUPLICATION | recordId 漂移/全局卡 | UNKNOWN | 中 | 低 | 高 |
| CROSS_BOOK_POLLUTION | **4 个全局文件（L3 复现）** | UNKNOWN | HIGH | 低 | **高（book 隔离）** |
| TEMP_VOICE_LEAK | 恢复哈希失败/死字段 | UNKNOWN | 中 | 中 | 高（区间模型） |
| TEMP_VOICE_NOT_ENDED | 30 句强制过期 | UNKNOWN | 低 | 低 | 高 |
| VOICE_AGE_WRONG | age/voiceAge 双字段漂移 | UNKNOWN | 中 | 中 | 中 |
| FIXED_VOICE_OVERRIDE | 误锁无自动纠正 | UNKNOWN | 低 | 低 | 高 |
| QUOTE_ALIGNMENT_FAIL | LCS 对齐歧义拒猜 | UNKNOWN | 低 | 中 | 中 |
| CACHE_STALE | 去引号误命中/全局缓存 | UNKNOWN | 中 | 低 | 高 |
| API_VOTE_CONFLICT | 平票取最晚 | UNKNOWN | 中 | 中 | 中 |
| REMOTE_DEPENDENCY | 默认关 | 无 | 无 | 无 | NOT_PORTED |
| STATE_RECOVERY_FAIL | 半合并态/备份启发式 | UNKNOWN | HIGH | 低 | 高（事务） |

\* 初始允许 UNKNOWN（§103：不瞎填数量）；后续真实书审计填频率。

## 失败/回退图（§59，V907_FAILURE_FALLBACK_GRAPH）

```text
API failure → 缓存命中(±2 索引) → 重试 ≤8 → 未知+duihua（封死建卡/缓存/图谱）
音色失败 → assignVoice 逐级降级 → null → "default"
图谱冲突校验失败 → defaultAllow 放行（不落边）
合并审计不完整 → 整批不落边（无本地降级）
临时状态恢复失败 → 回自然音色
```

## ReaderDirector 候选（§104/§105）

- **training_value=HIGH**：复杂 Speaker（FALSE_SPEAKER）、Identity（FALSE_ALIAS_MERGE/RELATION_AS_ALIAS）、TEMP_VOICE（LEAK）、VOICE_AGE
- **规则解决（不喂 LLM）**：CROSS_BOOK（book 隔离）、FIXED_VOICE（优先级链）、CACHE（版本化）、GROUP_PERSON（词表 block）

## Hard Case 样本清单（§107-§109，synthetic fixtures 对应）

tests/fixtures/v907/：alias-cases / merge-split-cases / voice-state-cases / cross-book / api-failure / cache
