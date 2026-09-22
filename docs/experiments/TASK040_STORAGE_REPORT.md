# TASK040_STORAGE_REPORT.md — 存储与 FTS benchmark（TASK-040 §109/§110）

**日期：2026-08-12**

## 3.3M 字符全量持久化（G12）

| 指标 | 值 |
|---|---|
| 文本 | 9.53 MB / 183,336 物理行 / 61,112 段 |
| source 写入 | 123 ms |
| physical_line 批量写入 | 600 ms |
| DB 总大小（WAL checkpoint 后） | **321.9-337 MB** |
| 峰值堆 | 1.59-1.69 GB（test JVM 2G 堆） |
| integrity | 100% PASS |

表规模分解（§109）：physical_line 183k / line_boundary 183k / paragraph_source_span 122k / normalized_transform 122k / logical_paragraph 61k / paragraph_revision 61k / cross_paragraph_link 61k。

**判定：STORAGE_PERF_DEBT**——~810k 行 × ~415B/行远超理论估算（3.3M 字书 DB 322MB，100 本书不可接受）。初步原因：大量 INTEGER 列 + 每行独立索引项 + SQLite 页填充；优化方向（page_size 调整、spans/transforms 合并、精简索引、行压缩）**不在 TASK-040 范围**（§90/§152 精神：不顺手优化），归 StorageManager/后续任务。**诚实记录，不宣称达标。**

## FTS prototype（§69/§110）

| 指标 | 值 |
|---|---|
| 无 FTS DB | 1.13 MB（gold 书规模） |
| 有 FTS（派生）DB | 1.18 MB（+4.4%） |
| FTS build | 4 ms |
| FTS query | 2.8 ms |

**结论（ADR-026）**：FTS 作为 **rebuildable 派生缓存**（SearchIndex 不是 source of truth，§68）——Paragraph 数据仍是权威，FTS 可随时重建；TASK-040 不强制上线。

## 批量插入（§107）

- 采用 batch + transaction（single transaction per stage）；`persistPhysicalLines` 183k 行 600ms；
- batch 大小（500/1000/5000）专项对比留未来（当前单事务全量已满足工程需求）。

## 复现

```bash
./gradlew :data-room:test --tests "com.readervoice.data.PersistenceStressTest" --rerun-tasks
```
