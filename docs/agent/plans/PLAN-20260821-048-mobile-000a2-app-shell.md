# PLAN-20260821-048 — MOBILE-000A.2：ReaderVoice App 壳层（书架 / 导入 / 播放）

## Goal
真机可用的 ReaderVoice App 最小闭环：书架（Book Package 扫描）→ 导入（TXT → 解析 → 调度生成）→ 缓存播放（cache-first 顺序播放），打通 MOBILE-000B（MNN runtime）+ MOBILE-000C（调度器）两个核心，先于 000D/000E。

## Current Verified Facts
- Book Package 结构冻结（docs/protocols/VOICE_RUNTIME_ARCH.md §7）：book/ 下 manifest.json + source.txt + book.db + render_units/ + audio/ + voices/ + cache/。`book.db` 维持单库，后续 render job/asset 的原子替换不得拆成 chapters.db/render.db。
- 缓存三层冻结：热 1000 句 wav / 冷 seed 重生成 / opus 20-40kbps。
- 调度器状态机与优先级冻结（PLAN-20260821-047，M1 已交付 13/13 测试）：CURRENT_PLAYBACK > NEXT_UP > CURRENT_CHAPTER > NEXT_CHAPTER > BACKGROUND。
- 导入管线冻结（TASK-010/020/030 PASS）：编码探测 → 章节/段落恢复 → LogicalParagraph；ReaderDirector（MENTION/SPEAKER/EVENT）→ CharacterResolver v3 → VoiceResolver → RenderUnit（RENDERUNIT_V1_SCHEMA）。
- 真机约束：Android 17 强制 16KB ELF 对齐（2026-08-21 实测）；CosyVoice3-MNN v1.1.0 主链（LLM CPU + Flow CPU/OpenCL + HiFT CPU）RTF 0.79-0.96。
- 探针 App mnn-llm-probe 已跑通构建链路（AGP 8.13.2 / gradle 8.14.4 / NDK r27 / VS ninja）。
- ReaderDirector CPU MNN int4 真机推理已验证；Qwen3.5 hybrid 的 MNN 3.6.1 QNN offline context
  仅为 BLOCKED 优化支线，不是 App Shell 的前置 Gate。
- Audio8 当前只有 PC 诊断，按 RTF<1 且内存<1.5GB 的 Gate 为 FAIL；Android ORT spike 未做，
  不得在本计划中把它列为可用 Fast Renderer。

## Non-goals
- 不做 TTS 引擎接入（000D）；不做 opus 编解码器（M2 缓存层）；不做 UI 美化；不做多书并发调度（M18）。
- 不做 VoiceDesign 端侧化（MOBILE-VF 已降优先级）。

## Invariants
- AGENTS 9 Playback P0（播放窗口优先）；10 协作式取消；13 AudioAsset 绑定 voice_revision_id；DB 红线 UPSERT。
- 原文不可变（TASK-010）；导入副本由用户显式选择（SAF），不扫描全盘。

## Scope
- app-android/（新模块）：书架（BookShelfActivity + BookPackageStore）、导入（SAF picker → 不可变 source.txt → manifest/book.db）、章节索引占位与章节列表；播放/设置留给后续 M2。
- scheduler 模块集成（依赖 :scheduler、:parser、:data-room）。

## Baseline
- app-android/ 仅 AGENTS.md；无任何代码。

## Milestones
- M1：工程骨架 + BookShelf（扫描 books/ 目录列出 Book Package：书名/结构状态）+ 导入入口（SAF 选 TXT → 原子复制到 books/<id>/source.txt + manifest/book.db 初始化）+ 章节列表。未经 Android 兼容化的 TASK-020 Parser 前，book.db 只写 `PENDING_STRUCTURE` 的全文锚点，禁止假称已完成章节编译。
- M2：缓存播放消费端（固定 fixture：预生成 assets 的书）——顺序播放、缓存命中、无缓存明确待生成；不在本计划中伪接 TTS。
- MOBILE-002（PLAN-20260821-053）：先冻结 Speech Runtime Contract；随后并行打通 Audio8 旁白链和 CosyVoice 角色链，最后才统一调度。
- M3：导入管线接通（编码探测→章节/段落→ReaderDirector PC 侧预生成 or 端侧 MNN——依 000B Gate 结果定）、角色解析与声线绑定、整章后台生成（BACKGROUND 优先级）。

## Progress
- M1 ✅ DEVICE GATE PASS 2026-08-21：Android 工程骨架、SAF 不可变导入、Book Package、单 `book.db` 和真实 Legacy RulePack 章节编译已落地；USB 真机 v3 显示 620 个 CONFIRMED anchor、完整原始标题，source SHA 未变；用户通过 SAF 再选同一 TXT，确认未产生第二个 Book Package（ExactDuplicate）。fixture cache-first 播放属 M2；真机真实 TTS 与端侧 Director 分别仍受 000D / 000B CPU integration Gate 约束。
- M2 ⚠️ CACHE DISCOVERY / EMPTY-CACHE PASS 2026-08-21：Android 仅扫描 Book Package `audio/` 下的 WAV，登记到单 `book.db` 的 `audio_asset` 表，并用 `MediaPlayer` 串行消费已有缓存；Host scanner unit test + APK PASS，USB 真机确认无缓存时按钮禁用且不触发 TTS。连续 WAV 播放尚未验证：`run-as` 无权从 `/sdcard` 读取临时 WAV，测试 fixture 未写入私有 Book Package，空目录已清理。不得标记 M2 完成。

## Decisions
- D1：书架数据源 = books/ 目录扫描（每书一个 Book Package 目录），不另建全局 DB（Book Package 自包含，多书并列）。
- D2：导入复制进 books/<id>/（防用户移动/删除源文件导致书损坏）；raw.txt 只读（不变量 1）。
- D3：播放器 = 顺序队列消费 PlaybackQueue.available()；优先级映射函数 P(unitId, playPosition) 由播放器位置驱动（M3 正式化）。
- D4：App 复用 16KB 对齐的 MNN 构建产物（mnn-3.6.1-android-arm64-16k），与探针同一套 jniLibs。
- D5：M1 维持 `book.db` 单库而不是 chapters.db/render.db；这是 future RenderUnit/job/asset 原子状态的边界。Android 通过 `parser-core` 复用 TASK-020 的章节/源扫描源码与完整 Legacy RulePack，仅持久化 CONFIRMED anchor；导航标题保存 `titleRaw`，不把派生的 `titleDisplay` 当作原文标题。
- D6：M2 Android 不直接依赖 JVM `SchedulerDb`（含 JDBC）；先实现 Book Package 文件缓存的消费端和 `audio_asset` 边界。缓存缺失只能明确显示待生成，不能在 UI 线程调用 TTS 或伪造任务完成。

## Experiments
| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | 书架扫描性能（100 书） | 全量扫描 | M1 测试 | — |
| E2 | 播放→调度优先级映射 | 无 | M2 测试 | — |
| E3 | 缓存命中率（顺序播放） | 0% | M2 测试 | — |

## Validation
- M1：书架列出/隐藏书籍正确；导入后 Book Package 结构完整（manifest.json + source.txt + book.db + audio/voices/cache/render_units）；单元测试覆盖原字节保留、布局和 SHA-256 exact duplicate。设备 Gate：真机 SAF 导入后读回 manifest/source SHA、章节列表显示 `PENDING_STRUCTURE` 全文锚点。
- M2：20 句 fixture 顺序播放无跳句、无重复；未命中句自动入队并播放；播放中关闭 App 重进断点恢复。
- M3：导入 1 章真实 TXT → 生成 → 播放全链通过（角色正确率 > 95% 沿用 PC-001 口径）。
- Gate 全部通过才更新 PROJECT_STATE。

## Rollback
- app-android 模块从 settings.gradle.kts 移除即可；books/ 目录删除即清书架，无全局副作用。

## Artifacts
- app-android/src/main/java/com/readervoice/app/**（书架/导入/播放）；测试在 app-android/src/test + androidTest。

## Open Issues
- 端侧 ReaderDirector 是否可用（B1.8 结果决定 M3 用 PC 预生成还是端侧推理）；opus 解码库选型（M2/M4）。
- 连续缓存播放 Device Gate：需由 app 内 debug fixture 或真实 CosyVoice 产物安全落入私有 `audio/` 后验证；禁止用 `run-as` 从 `/sdcard` 复制作为既定测试路径。
- 本轮 `:data-room:test` 的既有 `RealPoolPipelineTest` 正在执行全书 Chapter regex/Global Resolver 长测；线程栈确认 CPU 活跃、非死锁，但本计划交付时尚无退出结果。它不替代 M1 的 App 单测/APK 证据。

## Handoff
- 本计划 M1/M2 cache consumer 已与 Speech Runtime 拆分；真正 Renderer 由 PLAN-20260821-053 的 Audio8 旁白链 / CosyVoice 角色链接入，000B CPU integration → M3 端侧 Director；QNN 仅在后续作为 Director Accelerator 插入。
