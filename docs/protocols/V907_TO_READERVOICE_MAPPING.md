# V907_TO_READERVOICE_MAPPING.md — v90.7 → ReaderVoice 映射矩阵（TASK-050 S5）

**日期：2026-08-12** | 每项：Legacy → Current problem → ReaderVoice destination → KEEP/REDESIGN/DROP → TASK owner → Migration risk。

| Legacy State/Behavior | Current problem | ReaderVoice destination | 判定 | TASK | 风险 |
|---|---|---|---|---|---|
| nameToMainNameMap（内存三处构建） | 冗余易漂移、全局无书 | Mention 解析 + Identity Evidence Graph（按书物化） | REDESIGN | TASK-060 | 中 |
| aliasPositive/NegativeGraph（按书 JSON） | 双向边冗余、单文件写竞态 | identity_evidence 边表（book_id 外键，阈值 1.5/1.0/4.0 + 冲突复核） | KEEP | TASK-060 | 低 |
| aliasCooccurStats | 统计负边误伤 | 共现表（负证仅候选提示） | REDESIGN | TASK-060 | 低 |
| characterRecords.json（全局） | **跨书污染（L3 复现）** | narrative_entity 表（book 隔离，TASK-040 schema 已有） | REDESIGN | TASK-060 | 中 |
| mergedRecords 备份 | 启发式恢复、上限 20/240 | CorrectionEvent + ParagraphLineage + CharacterLineage（事务回滚） | REDESIGN | TASK-060 | 中 |
| temporaryVoiceStates（不写卡，L3） | 恢复哈希脆弱、死字段 | voice_states 表 + TemporalStateInterval（start/end segment） | KEEP_SEMANTICS | TASK-060 | 低 |
| fixed voice 硬锁（L3 优先级链） | 误锁无自动纠正 | VoiceBinding.lock_mode（AUTO/USER_SELECTED/USER_LOCKED） | KEEP_SEMANTICS | TASK-060 | 低 |
| globalVoiceUsage（least-used 跨书） | 换书失真 | voice 使用计数表（全局表可保留） | KEEP | TASK-120+ | 低 |
| 3084 个 GENSHIN 池 | 巨大固定池不可迁移 | VoiceCatalog/Matcher（音色池+降级链，保留 least-used） | DROP_IMPLEMENTATION / KEEP_DISTINCTIVENESS | TASK-120 | 低 |
| WAIT_API_RESULT_COUNT=5 投票 | 默认配置下失效 | 协程并发 + 确定性多数（删 Math.random） | REDESIGN | TASK-070+ | 低 |
| 智谱 API（多 key 轮换） | 外部依赖 | 端侧 ReaderDirector（无外部 API） | DROP | TASK-070+ | 低 |
| remote graph upload（默认关） | 隐私 | 无 | DROP（ADR-012） | — | 无 |
| dialog_cache.json（全局） | 跨书污染、去引号误匹配 | DB 段落缓存（book 隔离 + source_revision 键） | REDESIGN | TASK-070 | 中 |
| applyPersistentVoiceAgeEvidence（audit→commit） | age/voiceAge 双字段漂移 | voice_age 证据表 + 三 age 分离（v5 §18） | KEEP | TASK-060 | 低 |
| Character recordId 含 random | 重建漂移 | 确定性 uid（TASK-040 已有） | REDESIGN | TASK-060 | 低 |
| usageCount 双语义 | 冷门不淘汰 | importance 计算（mention/dialogue/chapter span） | REDESIGN | TASK-060 | 低 |

## 统计（§132 口径冻结）

- **12 capability domains → 16 migration decisions = 4 KEEP + 10 REDESIGN + 2 DROP**（DROP_IMPLEMENTATION 计入 REDESIGN 侧语义保留 / 智谱 API 计入 DROP）
- 四关键问题：A 跨书 CONFIRMED_L3 | B 关系 L2 | C 固定音色 CONFIRMED_L3 | D 见 TASK050_HARNESS_REPORT 精确结论
- 四关键问题：A 跨书 CONFIRMED_L3（污染复现）| B 关系 L2（拦截层确认，直通 merge 路径 L2）| C 固定音色 CONFIRMED_L3 | D 用户修正待完整审计结论
