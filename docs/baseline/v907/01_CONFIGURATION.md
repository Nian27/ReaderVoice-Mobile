# 01_CONFIGURATION.md — v90.7 配置域（TASK-050 Domain 01）

**日期：2026-08-12** | 证据：L2（L14-156/L1453-1470 逐行核对）

## Purpose

v90.7 全部行为开关与阈值参数面（Legado 面板可调，脚本重载生效）。

## 参数清单

### BEHAVIOR_REQUIREMENT（行为需求，ReaderVoice 应保留语义）

| 参数 | 值 | 语义 |
|---|---|---|
| ENABLE_ALIAS_VOTE_MERGE | 1 | 别名投票合并 |
| ENABLE_ALIAS_GRAPH / POSITIVE / NEGATIVE / COOCUR | 1/1/1/1 | 图谱四件套 |
| GRAPH_POSITIVE_HINT_MIN | 1.5 | 正边提示最低分 |
| GRAPH_NEGATIVE_SOFT_BLOCK | 1.0 | 反边软阻断（触发冲突复核） |
| GRAPH_NEGATIVE_HARD_BLOCK | 4.0 | 反边硬阻断（强排除，正链闭合须复核） |
| COOCUR_MAX_NAMES / MAX_SENTENCES | 50 / 260 | 共现扫描窗口 |
| COOCUR_NEG_SENTENCE_MIN / NEG_ADJACENT_MIN | 2 / 2 | 同句/相邻反边阈值 |
| ENABLE_GRAPH_CHAPTER_DEDUP | 1 | 章内证据去重（上限 3000） |
| ENABLE_ALIAS_GROUP_MEMBER_MERGE_BLOCK | 1 | 群体/单人禁互并 |
| ENABLE_GRAPH_POSITIVE_CHAIN_CLOSURE | 1 (+1.2 分) | 正链闭合 |
| ENABLE_GRAPH_BOOK_CACHE | 1 | 图谱按书隔离 |
| ENABLE_GRAPH_CONFLICT_MODEL_VERIFY | 1 | 冲突复核（45s 超时/conf≥80/修复分 4.5） |
| ALIAS_RECENT_CHAPTER_RANGE | 5 | 最近章提示窗口 |
| ENABLE_RELATION_DESCRIPTOR_POSITIVE_BLOCK | 1 | 关系称谓禁入正图 |
| ENABLE_SPECIAL_SPEAKER_BYPASS | 1 | 旁白/系统旁路 |
| ENABLE_ALIAS_CHECK_REASON_CONSISTENCY | 1 | 别名原因一致性 |
| ENABLE_VOICE_AGE_EVIDENCE / AUDIT | 1/1 | 音龄证据与审计 |
| ENABLE_TEMPORARY_VOICE_STATE | 1 | 临时换声状态机 |
| ENABLE_FIXED_VOICE_HARD_LOCK | 1 | 固定音色硬锁 |
| GRAPH_MODEL_*_SCORE | 4.0~6.5 | 模型证据分（name=5.0/dialogue=4.5/action=4.0/social=5.0/co_presence=4.0/explicit_different=6.5） |

### LEGACY_IMPLEMENTATION_DETAIL（实现细节，不迁移）

| 参数 | 值 | 说明 |
|---|---|---|
| WAIT_API_RESULT_COUNT | 5 | 多结果模式需 ≥3 且 bingfa≥3——默认 bingfa=1 时形同虚设（L462） |
| bingfa | 1 | 并发数（Java Thread） |
| xiawen / shouci | 1300 / 800 | 下文缓存字数 |
| NAME/ALIAS_ANALYZE_TIMEOUT | 120000 | API 超时 |
| CONFIG.resetUsageCount / activeRecordLimit / contextHistoryLength / apiTemperature | 100/200/1500/0.1 | 内部参数 |
| CHARACTER_ANALYZE_RETRY_MAX | 8 | 重试轮 |
| MAX_ALIAS_CHECK_CHARACTERS | 100 | **注释(50)与代码(100)不符——已知不一致** |

### REMOTE（§60 NOT_PORTED）

ENABLE_REMOTE_UPLOAD=0 / ENABLE_GRAPH_REMOTE_UPLOAD=0 / GRAPH_REMOTE_ENDPOINT="https"（占位）——默认全关，ReaderVoice 不迁移。

## ReaderVoice mapping

**KEEP 参数面**（转配置 schema）；**DROP** remote/日志开关；投票数/并发为协程模型重设计。

## Open questions

- `GRAPH_REMOTE_TOKEN` redacted 版为空（原版为凭据，已脱敏）；
- 参数注释与代码不一致（MAX_ALIAS_CHECK_CHARACTERS）提示该规则长期手改——迁移时以代码为准。
