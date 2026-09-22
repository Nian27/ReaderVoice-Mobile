# PLAN-20260821-052 — MOBILE-001：真实章节结构编译接入

## Goal

让 Android Book Package 在保留原始 `source.txt` 的前提下，复用 TASK-020 的 LegacyChapterRulePack + GlobalStructureResolver，生成真实章节导航，而非 `PENDING_STRUCTURE` 全文占位。

## Current Verified Facts

- TASK-020 已通过：`parser/src/main/resources/legacy/txtTocRule.json` 有 26 条冻结规则；`ChapterStructureCompiler` 以规则候选、TOC、卷、风格与全局 Resolver 生成 `CONFIRMED` 章节，不能单独用一条 Regex 代替。
- 用户给出的“目录(去空白)”是规则 `id=-1`；其语义与完整规则包一致。
- `parser` 是 JVM 21 模块，`ImportPipeline.kt` 使用 `java.nio.file`，不适合直接作为 Android 依赖；章节和源扫描代码仅依赖 Kotlin/Java 基础 API 与 `org.json`。
- `app-android` 当前只创建 `PENDING_STRUCTURE` 全文锚点；真机 Book Package 已存在，源 SHA 不可变化。

## Non-goals

- 不迁移 JVM `ImportPipeline`、SQLite JDBC、Paragraph Recovery、ReaderDirector、TTS 或播放。
- 不改写 `txtTocRule.json`、不复制/简化单条 Regex、不改变用户原始 TXT。
- 不对已有非 `PENDING_STRUCTURE` / 用户修订结构做自动覆盖。

## Invariants

- Raw source immutable；Regex Match != Chapter；同一 PhysicalLine 至多一个 anchor；User Locked 高于自动结构；Book Package 保持单 `book.db`；核心表不用 `INSERT OR REPLACE`。

## Scope

- 新增 `parser-core`：复用现有 parser 的章节/源扫描源码，排除 JVM-only `ImportPipeline` 与 paragraph 路径。
- `app-android`：打包冻结规则 asset，导入时真实编译、持久化 confirmed chapter index；仅升级 PENDING 包。
- 测试、M1 App Shell ExecPlan、项目状态和本计划。

## Baseline

- 真机已能 SAF 导入，但书架只显示 `1 项 · PENDING_STRUCTURE`，章节页仅有“全文”占位。
- `:parser:test` 的 TASK-020 报告记录 Legacy enabled examples candidate recall 12/12、Gold confirmed chapters 10、G1-G12 PASS。

## Milestones

- M0：`parser-core` Android-consumption build Gate；APK 依赖该模块并能打包规则 asset。
- M1：新导入从 `source.txt` 生成 confirmed chapter index，manifest 记录真实结构状态与数目。
- M2：只迁移 PENDING 包；source SHA 保持不变，已有真实/用户结构不重写。
- M3：Host + USB 真机 Gate：真实 TXT 有多章节导航、标题可见、重复 SAF 导入不新增 package。

## Progress

- M0 ✅ 2026-08-21：`parser-core` 以同一章节/源扫描源码通过 `:parser-core:compileKotlin` 与 `:app-android:assembleDebug`；冻结规则 asset 已在 APK 内。首次 core test 因离线缓存缺 `kotlin-test-junit` 失败，已改用项目现有 JUnit 5；最终 `:parser:test :parser-core:test :app-android:testDebugUnitTest :app-android:assembleDebug --offline` PASS。
- M1 ✅ 2026-08-21：新导入已在 staging 内以完整 Compiler 写入 CONFIRMED chapter index；Host 回归与 Android 单测通过。
- M2 ✅ 2026-08-21：USB 真机自动生成包已升级为 v3，`source.txt` SHA 前后相同，真实 Legacy RulePack 结果为 620 个 CONFIRMED anchor；章节页显示完整 `titleRaw`。v1 UAT 的残句标题失败和 v2 的 571 项回归均已保留在失败记录。
- M3 ✅ 2026-08-21：用户在 USB 真机通过 SAF 再次选择同一 TXT，确认没有产生第二个 Book Package（ExactDuplicate）。Host/USB 结构、原文不变、标题可见与去重 Gate 均通过。

## Decisions

- D1：`parser-core` 与 `parser` 指向同一份章节/源扫描 Kotlin 源，保证规则逻辑单一事实源；不 fork 解析器。
- D2：规则 JSON 的 canonical path 仍为 `parser/src/main/resources/legacy/txtTocRule.json`，Android assets 从该目录打包。
- D3：初始结构只持久化 `CONFIRMED` 章节用于导航；TOC/PROVISIONAL/REJECTED 不伪装为可播放章节。
- D4：仅 `PENDING_STRUCTURE` 或 `structure_origin=AUTO_LEGACY_RULEPACK` 且旧格式的自动生成包允许后台结构升级；`USER_EDITED` 结构永不自动重编译。失败保留原文和明确状态。

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | Android 消费 JVM Kotlin parser-core | PENDING 占位 | APK 打包规则 asset，Host build/test PASS | PASS |
| E2 | 真实 TXT 结构迁移 | 1 个全文锚点 | USB 真机 620 confirmed，v3 titleRaw 可见，source SHA 未变；同一 SAF 文件重复导入仍为单 Book Package | PASS |

## Validation

- `:parser:test` 继续 PASS；新增核心结构测试保持 Legacy rules/Resolver 口径。
- `:parser-core:compileKotlin`、`:app-android:testDebugUnitTest`、`:app-android:assembleDebug` PASS。
- 真机：manifest/source SHA 不变；`chapter_index` 为多个 CONFIRMED 标题；书架数目与 DB 一致；重复同一 SAF 原文仍为 ExactDuplicate。

## Rollback

- 删除 `parser-core` 依赖并保留已有 `source.txt` / `book.db`；迁移只对 PENDING 自动结构进行，不能删除或覆盖用户修订。

## Artifacts

- `parser-core/`、`app-android/` 结构编译接入、`runs/mobile_001_structure_device_gate/`（完成后）。

## Open Issues

- Android 运行时 `parser-core`/`org.json`/asset 兼容性已由 APK + USB 编译通过验证。
- 离线测试依赖：`kotlin-test-junit:2.3.10` 不在本机缓存；parser-core 使用已缓存 JUnit 5，避免把环境问题误记为解析失败。

## Handoff

- M3 PASS 后，M1 占位状态废除；M2 cache-first playback 可以消费 confirmed chapter index，但仍不得假称 TTS/Director 已接入。
