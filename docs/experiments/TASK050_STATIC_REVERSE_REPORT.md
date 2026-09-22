# TASK050_STATIC_REVERSE_REPORT.md — v90.7 静态逆向报告（TASK-050 S1）

**日期：2026-08-12** | 工具：`tools/v907-analyzer/`（acorn@8 仅 parse 不 execute，§8）

## AST / Symbol Inventory（G1）

| 指标 | 值 |
|---|---|
| 函数 | 345 |
| state-like 变量 | 927（全局 var 288 + 对象属性态 639） |
| 普通变量 | 2,574 |
| caller 节点 | 283 |
| 存储键 | 429 |
| 域分布（前 6） | IDENTITY 812 / OTHER 1949 / VOICE 238 / API 156 / CACHE 128 / CONFIG 123 |

产物：`V907_SYMBOL_INDEX.json` / `V907_STATE_INVENTORY.json` / `V907_CALL_GRAPH.json`（docs/baseline/v907/）。

## State 分类（§11 完成度）

- 全局态：characterRecords / globalVoiceUsage / nameToMainNameMap（内存）/ dialogCache / recentChapterContext
- 按书态：aliasPositiveGraph / aliasNegativeGraph / aliasCooccurStats / mergedRecords / voiceAgeEvidence（`*.v907.<bookKey>.json`）
- 对象属性态：this.characterRecords（75 次写）/ this.temporaryVoiceStates（68）/ this.aliasPositiveGraph（19）等
- **Persistence 分类**：MEMORY_ONLY（nameToMainNameMap）/ JSON_PERSISTED（角色卡+图谱+备份）/ CACHE_PERSISTED（dialog_cache）/ REMOTE_OPTIONAL（remote queue）

## 12 域完成（G2）

全部 12 域具备 entry/read/write/failure/fallback/mapping（docs/baseline/v907/01-12），证据 ≥L2。

## 关键发现

1. **本地说话人/非发声判定代码已删除**（L11469）——quote 性质/说话人/别名/冲突四层全由 LLM 裁决，本地只做管道/投票/block/持久化
2. 三处硬伤：投票兜底 Math.random()（L973-974）/ recordId 含随机数（L11160）/ bingfa=1 使投票形同虚设（L462）
3. 确定性最强：图谱（加权边+双阈值+复核修复）——可整体移植
4. 4 个全局文件无 book 维度（characterRecords/dialog_cache/globalVoiceUsage/alias_recent_chapters）

## 性能（§117）

AST parse <1s；inventory 输出 <1s；内存 <200MB（14k 行脚本）。
