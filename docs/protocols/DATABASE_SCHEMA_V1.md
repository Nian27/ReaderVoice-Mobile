# DATABASE_SCHEMA_V1.md — ReaderVoiceDatabase v1（TASK-040 §116）

**日期：2026-08-12** | Canonical：`data-room/database/schema.sql`（设计源，§123）。27 张表 / FK 全开 / WAL。
实现栈：JVM SQLite（sqlite-jdbc）+ Kotlin domain interfaces；**ROOM_DEVICE_GATE = OPEN**（本机无 Android SDK，Room 未实测）。

## 表清单（27）

| 域 | 表 |
|---|---|
| Book/Source | book / source_revision / physical_line |
| Revision 核心 | artifact_revision / artifact_head / artifact_dependency / build_session |
| Structure | structure_revision / chapter_candidate / volume / chapter / toc_block / toc_entry / toc_body_link |
| Paragraph | paragraph_recovery_revision / layout_profile / layout_region / line_boundary / logical_paragraph / paragraph_revision / paragraph_source_span / normalized_transform / cross_paragraph_link / paragraph_lineage |
| Correction | correction_event / override_rule |
| Invalidation | invalidation_event |

## 关键设计（§49-§67 落地）

- **ID Hybrid（ADR-022）**：book/source_revision 有 stable UID（跨系统）；physical_line/paragraph 等热表 INTEGER PRIMARY KEY（无 AUTOINCREMENT，§48）；`(source_revision_pk, line_no)` 等复合 UNIQUE（§47/§75）。
- **SourceRevision 不可变（§3）**：sha256+byte_size+charset 冻结；parent 仅显式 SOURCE_REVISION（ADR-016）。
- **Stage Revision（§5/§26）**：artifact_revision 粗粒度（SOURCE/STRUCTURE/PARAGRAPH_RECOVERY）；业务实体（chapter/paragraph/span）是真实表（§155 坑 1：不做 Generic Artifact 框架）。
- **Paragraph 双层（§27/§28）**：logical_paragraph（稳定语义头）+ paragraph_revision（版本）；fingerprint = source_revision+spans+role（§30）；parent_revision + revision_reason（§62）。
- **Option B 存储（ADR-019）**：spans + transforms；synthetic 空格 source_span=NULL + synthetic_text（§64/§65）。
- **FK 策略（§42/§43）**：核心用户数据 RESTRICT（book/source_revision/logical_paragraph/paragraph_lineage）；派生子表 CASCADE（spans/transforms/boundaries）。
- **索引（§75，5 个）**：physical_line(src,line_no) / chapter(struct,index) / paragraph_revision(pr,chapter,index) / span(para,order) / boundary(pr,left)。
- **空行/分隔符**：不产段落（ADR-021），结构证据保留在 line_boundary/layout_region。

## 已知问题（诚实记录）

- **3.3M 字书 DB ≈ 322-337MB**（183k 行 / 61k 段 / 122k spans+transforms，实测含 WAL checkpoint 后主文件）：~810k 行 × ~415B/行，远超理论估算。**判定为 STORAGE_PERF_DEBT**（§148：INDEX_PERFORMANCE_ERROR/STORAGE），优化方向（page_size、行级压缩、spans/transforms 合并 blob、删除冗余索引）留 StorageManager/后续任务——TASK-040 不顺手优化（§152 精神）。

## Migration（§72/§124/§125）

- 禁止 fallbackToDestructiveMigration；v1 为所有未来 migration 起点。
- Harness：seed db fixture（`reader_v1_seed.db`，全 synthetic，§125/§126：1 book/1 source/2 volumes/若干 chapters/paragraphs/user join/superseded revision）。
- 未来 v1→v2：copy v1 fixture → migrate → integrity verify。
