# PLAN-TASK-010 — Immutable TXT Ingestion Layer

**日期：2026-08-12（TASK-000.1 后修订 v2，按 TASK-010 36 节规格）** | **状态：执行中**

## Goal

实现 ReaderVoice 的不可变 TXT 导入底层：TXT bytes → safe import → immutable local source → charset detection/user override → decoded character stream → PhysicalLine index → byte/char source mapping。任何派生数据必须能无歧义回溯原始字节与文字。

## Current Verified Facts

- 实现栈：**Kotlin/JVM 纯逻辑模块**（parser/，Gradle 8.14.4 + Kotlin 2.3.10 + JUnit5，本机缓存齐全、网络可用）；不依赖 Android SDK；TASK-040 时迁 Room。
- JVM 内置 Charset：UTF-8/GBK/GB18030/UTF-16LE/UTF-16BE 全部可用；GBK 是 GB18030 子集，判据 = 是否含 GB18030 4 字节序列。
- Gradle wrapper 8.14.4 可由 `CosyVoice3-MNN-formal/gradle/wrapper/` 复制（同版本，dist 已在 `~/.gradle/wrapper/dists/`）。
- v5 §7/§8/§154；TASK-010 规格 36 节（本 Plan 输入）。

## Non-goals

- Chapter/TOC/Volume（TASK-020）、LogicalParagraph（TASK-030）、Room DB（TASK-040）、Speaker/LLM/TTS/Android 播放——全部禁止。
- Android SAF UI（本任务仅保证 backend API 可支持预览/override/重解析，不实现 UI）。
- 全文 diff（revised edition 只记录 different source revision + schema 预留 parent_source_file_id）。

## Invariants（根 AGENTS.md + 本任务 I1-I5）

I1 Raw source immutable（原始字节不 trim/normalize/rewrite）；I2 layout 信息保留（U+3000/ASCII space/Tab/trailing 全保留）；I3 PhysicalLine != Paragraph（禁止创建 LogicalParagraph）；I4 source offsets stable（byte+char 双轨，后续 normalization 不改变基准）；I5 import crash-safe（状态机原子化，禁止 DB=IMPORTED 但文件半截）。

## Scope

```text
parser/                 Kotlin/JVM 模块（source/ 包 + 单测）
tests/fixtures/txt/     自制/synthetic fixture（不含私人小说与 v90.7）
docs/protocols/         TXT_SOURCE_SCHEMA.md + SOURCE_OFFSET_CONVENTION.md
docs/experiments/       TASK010_ENCODING_REPORT.md + TASK010_LARGE_FILE_REPORT.md
```

## Baseline

无既有实现；验收以本任务 12 项 Gate（G1-G12）为准。

## Milestones

| # | 交付物 | 验收 |
|---|---|---|
| M1 | Gradle 骨架（settings + parser/build.gradle.kts + wrapper）+ 空测试跑通 | `./gradlew :parser:test` 绿 |
| M2 | EncodingDetector（BOM→strict UTF8→UTF16 heuristics→GB18030 candidate→scoring）+ EncodingResult | 6 编码 fixture + 乱码样本正确/或 AMBIGUOUS；置信度输出 |
| M3 | PhysicalLineScanner（流式 byte+char 双轨、newline span、BOM 处理、EOF-no-newline）+ SourceMap round-trip | 16 边界用例测试；round-trip 100% |
| M4 | ImportPipeline（状态机 NEW→…→IMPORTED/FAILED、原子 rename、EXACT_DUPLICATE、revised edition 记录、错误枚举） | crash/recovery 测试；duplicate 识别 |
| M5 | Raw-text 存储 benchmark（Option A 全存 vs Option B offsets+lazy，SQLite-JDBC 实测） | 报告决策 + ADR |
| M6 | 3M+ 字符 synthetic 大文件 import（峰值内存/耗时/行数） | 无 OOM；指标入 LARGE_FILE_REPORT |
| M7 | 4 份 formal artifacts + PROJECT_STATE/DECISIONS 更新 | Gate G1-G12 全过 |

## Progress

- M1 ⏳ 2026-08-12（正在搭骨架）
- M2-M7 ⏳

## Decisions

- offset 口径：存储 byte_offset + unicode_codepoint_offset 双轨；UTF-16 index 不存储（派生计算，文档给公式）——待 SOURCE_OFFSET_CONVENTION.md 冻结。
- raw_text 存储：待 M5 benchmark 后定（倾向 Option B lazy read + 短行缓存权衡）。
- decode revision：user override 后产生新 decode revision，raw file 不变。

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | 编码评分权重（Han ratio vs 中文标点 vs control/NUL vs replacement） | 起始权重 0.4/0.3/0.2/0.1 | 待跑 | — |
| E2 | raw_text 全存 vs offsets+lazy（SQLite 实测：size/import/query/memory） | 无 | 待跑 | M5 决策 |

## Validation

- 16 边界用例（空文件/单行/EOF-no-newline/CRLF/LF/mixed/BOM/GB18030/UTF-16/超长行/空行/U+3000/Tab/emoji/坏 UTF-8/二进制伪装）；
- round-trip 随机抽样 100%；
- 3M+ 字符无 OOM；
- crash：模拟中断后重新 import 成功；
- secret scan + verify_baselines 仍 PASS。

## Rollback

- Gradle 模块：删除 parser/build 目录即可；schema 文档先行不落库；
- Import 中间态：tmp 文件可清理，状态文件标记 FAILED_IMPORT。

## Artifacts

代码 + 单测 + fixtures + 4 份正式文档 + 本 Plan 更新。

## Open Issues

- GBK vs GB18030 声称规则需样本校准（E1）；
- SQLite-JDBC 依赖引入（benchmark 用，测试隔离）；
- 超长单行（整章一行）拆分策略：本任务仅记录不拆分（TASK-030 处理）。

## Handoff

- TASK-020 输入：`physical_lines` 索引（含 chapter_candidate 疑似位）+ SourceMap + fixture 集；
- TASK-040 输入：TXT_SOURCE_SCHEMA.md（表结构）+ 存储决策 ADR。
