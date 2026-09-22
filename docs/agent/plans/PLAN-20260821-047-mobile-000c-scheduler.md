# PLAN-20260821-047 — MOBILE-000C：本地音频生产调度器（Audio Cache Compiler 核心）

## Goal
引擎无关的离线音频生产调度器：RenderUnit → GenerationJob → Renderer → AudioAsset → PlaybackQueue 全链路状态机，SQLite 持久化（book.db），增量失效，优先级调度，可抢占取消——纯 JVM 可测，后续接入 CosyVoice3-MNN / Audio8（000D）。

## Current Verified Facts
- RenderUnit v1 冻结（docs/protocols/RENDERUNIT_V1_SCHEMA.md §1/§5）：9 字段；cache.key = sha256(tts_config.model + "|" + character.profile_hash + "|" + render_instruction.mode + "|" + normalizedInstruction + "|" + tts_config.speed + "|" + tts_config.sample_rate + "|" + text.normalized)；字段级失效；AudioAsset 绑定 voice_revision_id（AGENTS 不变量 13）。
- 编译器架构冻结（docs/protocols/VOICE_RUNTIME_ARCH.md §7）：句子级粒度；三层缓存（热 1000 句 wav / 冷 seed 重生成 / opus 20-40kbps）；Book Package：book.db + render_units/ + audio/ + voices/ + cache/；增量更新原子替换；当前章前 20 分钟优先。
- 状态机与优先级（2026-08-21 会话定案）：UNPLANNED/PLANNED/QUEUED/GENERATING/READY/FAILED/STALE；优先级 当前播放 > 下一句 > 当前章 > 下一章 > 其他。
- 模块惯例（parser/data-room build.gradle.kts）：Kotlin JVM 2.3.10 + java-library + jvmToolchain(21) + JUnit5 + sqlite-jdbc 3.46.1.3；settings.gradle.kts 需注册新模块。
- DB 红线（AGENTS ADR-025/G11）：核心持久化表禁止 INSERT OR REPLACE；用 UPSERT（ON CONFLICT DO UPDATE）。

## Non-goals
- 不实现任何 TTS 引擎/编解码（Renderer 为可插拔接口；CosyVoice/Audio8 适配器属 MOBILE-000D；opus 编码器后续接入）。
- 不做 Android UI / Room 迁移（本模块纯 JVM；Android 侧经同一接口换 Room 实现）。
- 不做 LLM 推理、不做网络。
- 不做 PC 新算法扩展（用户 2026-08-21 纪律）。

## Invariants
- AGENTS 1 raw 不可变（text_normalized 只读）；9 Playback P0（播放窗口优先）；10 协作式取消（禁杀线程）；11 PCM Gate 属集成阶段，本模块 Gate = 状态机/优先级/失效正确性；13 AudioAsset 绑定 voice_revision_id。
- 确定性：同输入同调度结果（时钟/随机注入）。

## Scope
- 新增 scheduler/ 模块（Kotlin JVM）：domain + 状态机 + 优先级 + SQLite 持久层 + 失效 + 可插拔 Renderer + 测试。
- settings.gradle.kts 注册 :scheduler。
- 本文档；后续更新 docs/PROJECT_STATE.md。

## Baseline
- scheduler/ 为空（.gitkeep）；无 book.db 缓存 schema；无调度逻辑。

## Milestones
- M1：模块骨架 + domain（RenderUnitRef/GenerationJob/AudioAsset/JobState/Priority）+ cache key 公式 + SQLite schema + plan/queue/claim/complete/fail 状态机 + 增量失效 + 优先级排序 + fake renderer 全链路测试。
- M2：协作式取消（coroutine ensureActive + renderer cancelled() 契约）+ 指数退避重试 + STALE 重规划 + 热缓存 LRU(1000) 骨架 + 并发 worker 池（每 renderer 并发上限）。
- M3：PlaybackQueue 交接契约（READY 资产按优先级出队/seek）+ 冷缓存 seed 策略接口 + 与 RenderUnit 生产者（导入管线）对接契约测试。
- M4（阻塞于 000B/000D）：真机 CosyVoice3-MNN Renderer 适配器 + opus 层（集成阶段）。

## Progress
- M1 ✅ 2026-08-21：模块骨架 + domain + cache key 公式 + SQLite schema + 状态机（plan/promote/claim/complete/fail）+ 增量失效 + 优先级 + 指数退避 + fake renderer 全链路测试，**13/13 测试通过**（含红线静态检查）。
- M2 ⏳ 协作式取消验证 / 多 worker 并发 / 热缓存 LRU / STALE 重规划完善。

## Decisions
- D1：scheduler 为纯 JVM Kotlin 模块（不依赖 Android），PC 可测，Android 复用同一接口（Room 实现换持久层）。
- D2：每书一个 book.db（Book Package 冻结结构）；调度器实例全局，按 book_id 分区（当前 M1 以单 book 为准，字段预留）。
- D3：job 与 render_unit 一对一（render_unit_id UNIQUE）；cache 命中（asset.cache_key == unit.cache_key 且 voice_revision_id 匹配）不建 job。
- D4：FAILED 为终态，需显式 re-plan；STALE 由失效触发，随下次 plan() 以新 cache_key 重建。
- D5：并发 = 每 renderer 独立 worker 池（引擎可各自限流）；M1 单 worker，M2 多 worker。

## Experiments
| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | 优先级排序 | FIFO | M1 测试 | 进行中 |
| E2 | 失效矩阵（文本/档案/指令/速度/模型版本） | 无 | M1 测试 | 进行中 |
| E3 | 失败重试退避 | 无重试 | M1 测试 | 进行中 |

## Validation
- 单测：cache key 公式（同输入同 key，任字段变 key 变）；状态机全转移；优先级顺序正确；失效只影响相关句；voice_revision 变更 → 资产替换；cache 命中不建 job；UPSERT 静态检查（schema 无 INSERT OR REPLACE）。
- 集成测试：fake renderer 驱动 100 unit 全链路（含失败/重试/失效/优先级混合）。
- Gate：全部通过 = M1 PASS。

## Rollback
- 移除 settings.gradle.kts 中 :scheduler 行 + 删除 scheduler/ 目录；DB 为每书独立文件，删除即回退，无全局副作用。

## Artifacts
- scheduler/build.gradle.kts；scheduler/src/main/kotlin/com/readervoice/scheduler/*.kt；scheduler/src/test/kotlin/com/readervoice/scheduler/*.kt。

## Open Issues
- opus 编码接入点（M2/M3 决定 codec 策略接口）；热缓存 LRU 淘汰策略精确化；跨书调度分区；播放窗口（前 20 分钟）→ 优先级映射在 M3 由播放器位置驱动。
- 2026-08-21 全量 Gradle 回归观察到 `SchedulerStateMachineTest.transientFailureRetriesThenSucceeds` 虽 PASS，但 worker 在测试 teardown 后打印 `java.sql.SQLException: database connection closed`（`SchedulerDb.upsertJob → failJob`）。这是 Scheduler.close/db.close 竞态诊断，不能作为静默正常日志；M2 协作式取消/close Gate 应复现并修复。

## Handoff
- MOBILE-000D（TTS Router）消费 Renderer 接口；MOBILE-000B Android runtime 通过后接 M4。
