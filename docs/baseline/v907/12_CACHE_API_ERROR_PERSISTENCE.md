# 12_CACHE_API_ERROR_PERSISTENCE.md — v90.7 缓存/API/错误/持久化域（TASK-050 Domain 12）

**日期：2026-08-12** | 证据：L2（L441-577/L1801-1804/L2411-2423/L5532/L7740-7787/L8442-8481/L11880-11903）

## Purpose

全部持久化文件与 book 维度、API 并发细节、失败保护分类、fallback 链。

## 持久化文件清单（§56/§57，book 维度标注）

| 文件 | book 维度 | 说明 |
|---|---|---|
| characterRecords.json | **无** | 角色卡全局单文件（L5529）——跨书污染点 |
| dialog_cache.json | **无** | 对白缓存（L7742），含 cacheCreatedChapterId + relationEvidence + temporaryVoiceSnapshot——跨书污染点 |
| globalVoiceUsage.json | **无** | 全局音色计数（L5449-5459）——有意跨书 |
| alias_recent_chapters.json | **无** | 最近章提示（L1558，带 bookKey 字段）——潜在污染 |
| alias_positive/negative_graph.v<ver>.<bookKey>.json | **有** | 图谱（L1518-1520/L2170） |
| alias_cooccur_stats.v<ver>.<bookKey>.json | **有** | 共现（L2170） |
| mergedRecords.v<ver>.<bookKey>.json | **有** | 合并备份（L11188） |
| voice_age_evidence.v<ver>.<bookKey>.json | **有** | 音龄证据（L1536/L11882，dataVersion 双校验） |
| nameKeyIndex.txt / aliasKeyIndex.txt / miyue.txt | 全局 | 密钥轮换（L267-271） |
| graph_remote_chapter_queue.json | 全局 | 远程队列（L70） |
| nameToMainNameMap | **仅内存** | 不落盘（rebuildNameToMainNameMap L5618） |

**cache key 审计结论**：bookId 不参与 4 个全局文件（characterRecords/dialog_cache/globalVoiceUsage/alias_recent_chapters）；图谱/备份/音龄按书隔离（ENABLE_GRAPH_BOOK_CACHE=1）。

## API（concurrentApiRequest L441）

- 每 key 一个 Java Thread + CountDownLatch；timeout 18000ms（姓名 120000/别名 120000/冲突 45000）
- 多结果模式需 WAIT_API_RESULT_COUNT≥3 且 bingfa≥3（默认 bingfa=1 → 单结果）
- 无 API key → 立即失败（L473-476）；超时按已收集结果（L556-560）；0 成功 → success:false

## 失败保护分类（§58）

| 类别 | 处理 |
|---|---|
| API timeout | errors 收集 → 重试 ≤8 轮 → analyzeCharacterFallback（duihua 封链路） |
| parse failure | responseParser 抛错入 errors |
| empty response | success:false → 兜底 |
| bad JSON | graphReadJsonSafe fallback；readDialogCache 安全默认结构 |
| cache missing | cold_start 分类（L8478-8481） |
| state inconsistent | loadRecords catch→[] + repairDuplicateAliasMainRecords；dialogCache 过滤脏 list |
| merge failure | 返回 false + 备份段 try/catch |
| voice unavailable | restoreVoiceWithFallback → assignVoice → duihuaA/B |

## Fallback 链（§59）

```text
API failure → 缓存命中（matchDialogFromCache ±2 索引容错 L8446-8447）→ 重试耗尽 → 未知+duihua（封死缓存/建卡/图谱）
音色：assignVoice 逐级降级 → null → "default"
图谱：冲突校验失败不落边（defaultAllow）
```

## Remote（§60）

ENABLE_REMOTE_UPLOAD=0 / ENABLE_GRAPH_REMOTE_UPLOAD=0（默认关）；graphRemoteEnabled(L1801) 三开关同真才启用；队列上限 3000/章边 30。**NOT_PORTED**。

## 已知问题

- 4 个全局文件无 book 维度——多书切换互斥覆盖（§57 污染点确认）
- ttsrv 单文件写无锁，长文本并发写竞态
- 投票模式默认配置下永不生效（WAIT≥3 且 bingfa≥3）

## ReaderVoice mapping

**REDESIGN**：统一 book 命名空间 KV 存储（TASK-040 schema 已具备）；单文件 JSON 缓存与全局/局部混存是主要脆弱点；API 层抽象（并发+投票+超时）可复用为协程模型。

## Open questions

- dialog_cache 全局覆盖下切书后旧书缓存哈希误命中（cleanDialogText 去引号后）
- GRAPH_REMOTE_EDGE_LIMIT=30 是否导致远程观察失真（不迁移，仅记录）
