# PLAN-20260918-062 — 产品模型切换：Reader-first（阅读器优先，Director 是剧本缓存层）

## Goal

把 ReaderVoiceMobile 从**实验程序**（导入书 → 点"分析整章" → 等 5 分钟 → 看 JSONL）切换成
**一款完整的本地小说阅读器**；ReaderDirector 降级为它的「**智能剧本缓存层**」：
用户正常翻书，系统只在阅读位置附近**按批次**理解、**持久化**、**预取**；角色与音色随后复用这些持久数据。

一句话验收：**打开一本书就能读；读到哪，剧本准备到哪，永久保存到哪；退出/被杀再进来接着读。**

## Current Verified Facts

- 参考产品形态：解包 APK `io.legado.app` 3.30.6（`ReadBookActivity` / `TocActivity` /
  `TxtTocRuleActivity` / `CacheBookService` / `TTSReadAloudService` / `CharacterManagerActivity` /
  `VoicePickerActivity`）。
- 其内置 `txtTocRule.json`：**26 条规则中 14 条 `enable=false`**，激进规则（`特殊符号 标题(成对)`、
  `特定字符 标题 特定符号`、`通用规则`、`顶格标题`）**默认关闭**。
- 本项目规则包同样带 `enable` 标记，但 `ChapterCandidateScanner` 旧实现**只对 `regexRisk >= HIGH`
  的禁用规则跳过** ⇒ 禁用中的低/中风险宽泛规则照样产出候选并进入解析序列。
  **已修（2026-09-18）**：`enable=false` 一律不扫描；用户在【章节规则】页可显式启用。
- `ConfirmedChapterView`（产品口径）三本真实书实测：CONFIRMED 1286 / 902 / 850，
  不变式违规 **0**（raw candidate 违规 1781/962/889 —— 说明"生产只吃 canonical"是必要的）。
- 真机落盘已是**按章分离**的 Book 数据：`files/books/<bookId>/scripts/<chapterId>/{current.jsonl,state.json,revisions/}`，
  稳定键 = `bookId + chapterId + sourceRevisionId`。
- 真机剧本一轮实测：76/76 段、19 次模型调用、0 REJECT、非人说话人 **0**。

## Non-goals

- **不接 TTS**（Audio8 / CosyVoice / VoiceBinding 本轮不动）。
- **不做**完整 Legado 级阅读器功能（书签/翻页动画/字体主题/WebDAV 同步）。
- **不重写** parser 的章节正则引擎（只改"哪些规则参与"）。
- 不动已冻结口径：四态 / 15 个失败码 / C18 `SpeakerRef` / `script_lines` schemaVersion=2 / ADR-055。

## Invariants（根 AGENTS）

- 不变量 1：原文只建映射，不改写。
- 不变量 5：UNKNOWN 是合法结果，禁止强造。
- 不变量 9：**Playback P0** —— 当前播放/阅读窗口永远优先于未来生成（本 PLAN 的核心）。
- 不变量 10：native 协作式取消，禁止杀线程。
- 不变量 12：多书按需，未激活书不跑 LLM。

## Scope

| 层 | 目标 |
|---|---|
| `parser` | canonical chapters（`ConfirmedChapterView` 成为唯一产品口径）；规则包默认保守 |
| `data-room` | `ScriptRepository` 升级为 **Book 持久数据 + prepared range + source hash 命中判断** |
| `data-room` | `ChapterDirectorRunner` → **Window/Batch Director**（8–16 段 + 前后上下文） |
| `app-android` | `BookState`（阅读位置一级数据）+ `DirectorPreparationService` + Reader 驱动预取 |
| `app-android` | 章节列表/剧本/角色档案 UI 只消费 canonical 数据 |

## Baseline

| 指标 | 现状（切换前） |
|---|---|
| 生产章节来源 | `ConfirmedChapterView`（已正确），但规则包禁用规则仍参与扫描（已修） |
| 剧本持久化 | 按章 JSONL + state.json（存在，但无 prepared range 索引、无 source hash 命中判断） |
| Director 触发 | **手动整章运行**（前台服务），不是阅读驱动 |
| 阅读位置 | Activity 状态（`ReaderActivity` 的选中章），**未持久化** |
| 真机单章耗时 | ~190–600s（整章 76 段） |

## Milestones

- **R1 canonical chapters + 默认保守规则**（地基）
  - 规则包 `enable=false` 不参与扫描（**已完成**）。
  - 章节列表/阅读/剧本/导演全部只消费 `ConfirmedChapterView`（`confirmedChapters()`）。
  - `revision.chapters` 降为**诊断视图**，生产代码不得直接引用（加静态扫描测试）。
  - Gate：`ChapterBoundaryGateTest`（3 书 × 4 抽样章）confirmed 违规 = 0；且**生产代码零引用** `revision.chapters`。
- **R2 Script 升级为 Book 持久数据**
  - `ScriptRepository`：`get/put/putBatch/invalidate/preparedRange(bookId, chapterId)`；
    索引含 `sourceRevisionId / scriptRevision / protocolVersion / characterRevision / status / speakerRef`。
  - 内容仍物理存 `files/books/<bookId>/director/chapter_<stableId>.jsonl`（按章一个文件）。
  - **缓存命中判据**：`script exists AND sourceSpanHash 相同 AND protocolVersion 相同 AND characterRevision 兼容`。
  - 失效条件：正文变化 / 用户改章节规则 / 用户修正角色 / 身份合并 / 模型或协议升级。
  - Gate：JVM 用例（命中→不重算；四类失效各触发重算）+ 真机（重启后剧本仍在，且不重新调用模型）。
- **R3 Window/Batch Director**
  - `BatchPlanner`：`target` 8–16 段（首版 12），`previous context` 6，`lookahead` 2。
  - 每个 batch 完成即落盘（复用 P0-B 提交点），batch 内仍是 shortcut/model 混合。
  - Gate：单 batch 端到端（真机）产出 ≤16 行且 checkpoint 前进；crash 后重算不产生重复 `segment_id`。
- **R4 阅读驱动预取**
  - `BookState`：`currentChapterId / currentParagraphId+offset / readingPosition / directorPreparedUntil` 持久化。
  - `DirectorPreparationService`（前台服务）：读到 S37、已备到 S54 ⇒ 后台补 S55–S66；退出页面继续跑。
  - Gate：真机——连续翻页时剧本始终领先；`am force-stop` 后重进从 checkpoint 续跑、无重复段。
- **R5 整章分析降级为预缓存功能**
  - UI 文案与入口改为「缓存本章剧本 / 缓存后 10 章剧本」，与阅读主链路解耦。
  - Gate：删除/隐藏该入口后，正常阅读链路仍完整可用。

## Progress

- **R1 ✅ 2026-09-18（完成）**
  - 规则包 `enable=false` 不再参与扫描（`ChapterCandidateScanner`）；`:parser:test` 章节族全绿。
  - `CanonicalChapterDisciplineGateTest`（新）：生产源码零 `revision.chapters` 引用（允许
    `CANONICAL-JOIN` 标注的诊断/override 层与 `*ParityActivity`）；抽查 Reader/Director 确实消费 canonical 视图。
  - `Persister` 只持久化 canonical chapters（旧实现写 `CONFIRMED || PROVISIONAL` —— 候选状态泄漏进产品数据）。
  - `BookDatabase.replaceWithConfirmedChapters` 入口改为 `List<ConfirmedChapter>`（过滤必须发生在调用方之前），
    并修掉真实 bug：`ordinal` 主键旧用 `chapterIndex`（实测有 8→0 回退），现用 canonical ordinal；
    `anchor_byte` 即章节稳定 id（= chapterId）。
  - `ConfirmedChapter` 增 `titleRaw`（导航标签保留源样）。
  - 目录页空状态引导：未识别到章节时说明"激进规则默认关闭"并给【去选择章节规则】按钮。
  - `:app-android:assembleDebug` 通过。
- R2 ⏳ 进行中：`ScriptCache`（命中判据 + 7 类失效原因）、`ScriptState` 增
  `chapterTitle/chapterOrdinal/sourceSpanHash/protocolVersion/characterRevision/preparedUntil`、
  `ScriptRun.cacheHit/markPreparedUntil/isPreparedThrough` 已落地；Service 接线与真机"重启后 0 次模型调用"待验。
- **R4 🔶 部分完成（app 设计主线）**
  - `ReadingState`/`ReadingStateStore`：阅读位置 = **书的数据**（`files/books/<id>/reading_state.json`），
    存结构位置（chapterId + paragraphIndex + segmentOrdinal + charOffset），不存屏幕/时间位置（同书签纪律）。
  - 阅读器重做（`ReaderActivity`）：`LazyColumn` 段落渲染 ⇒ `firstVisibleItemIndex` 即精确段落序号；
    滚动停止 600ms 落盘；重进回原位；字号 A-/A+ 持久化（`reader_prefs.json`）；上/下一章。
  - **打开就快**：`book.db` 增正文区间列 ⇒ 阅读器只读需要的行，不再为显示一章重编译整本结构。
  - 书架：每本书显示「读到第 N 章」+ 继续阅读/开始阅读 + 目录。
  - 目录：主操作改为【阅读】（按**章节稳定 id** 而非 ordinal）、当前章高亮、空状态引导。
  - ⚠️ **真机事故与修复（教训）**：新增区间列后读路径直接按新列 SELECT，旧包 `book.db` 无该列 ⇒
    `SQLiteException: no such column` ⇒ **点啥闪退啥**（ChapterListActivity/ReaderActivity onCreate）。
    修复：`ChapterIndexQuery`（data-room 纯函数）按**实际存在的列**拼 SQL，缺列降级 -1；
    并补 `ChapterIndexCompatGateTest`（旧/新/半迁移/空列 4 条）把"加列"事故钉在 JVM 上。
  - 真机验证：安装后不再闪退；`reading_state.json` 已按预期落盘。
- R3/R5 ⏳ 未开始。
- **app 设计剩余**：角色档案页（年龄/性别/身份 + 证据 + 未知可编辑）、阅读器中间点击菜单、
  目录页把导演/调试入口收进二级菜单、剧本缓存状态（"已备到 x/y 段"）展示。

## Decisions

- **D1**：产品目标重定义为"阅读器优先，Director 是剧本缓存层"（用户 2026-09-18 定案）。
- **D2**：章节规则默认保守（对齐 Legado 3.30.6）：激进规则默认关闭，用户在【章节规则】显式启用。
- **D3**：`ConfirmedChapterView`（canonical chapters）是**唯一**生产口径；
  `PROVISIONAL/REJECTED` 只存在于诊断视图，生产 UI 不得知晓。
- **D4**：剧本 = **这本书的数据**（Book 的一部分），不是 run 产物；`ScriptRepository` 提供范围查询与失效判断。
- **D5**：Director 的触发是**阅读位置驱动**，整章分析只是"预缓存"高级操作（不变量 9）。

## Validation

```bash
# R1
./gradlew.bat --offline :parser:test --tests "com.readervoice.parser.chapters.*"
./gradlew.bat --offline :data-room:test --tests "com.readervoice.data.m3.*"
# 真机
adb -s <endpoint> shell am start -n com.readervoice.app/.MainActivity   # 书架→书→目录→正文→翻页
```

## Rollback

- R1：`ChapterCandidateScanner` 单行条件回退即可（无数据迁移）。
- R2/R4：新索引为**附加**文件（`director/index.json`），删除即回到现状；不改动既有 JSONL 格式。

## Artifacts

- `parser/.../ChapterCandidateScanner.kt`（默认保守）
- `runs/mobile_005_director_real/ch0_chapter_gate/report.md`（章节门）
- `runs/mobile_005_director_real/candidate_gate/report.md`（剧本质量）

## Open Issues

- 规则包 `enable=false` 后，某些书（纯数字标题、无"第N章"）会**没有章节** ⇒ 必须有
  "未识别到章节"的空状态引导用户去【章节规则】启用规则（R1 的 UI 待办）。
- `chapterId` 目前是内容派生（anchor byteStart），**换规则后会变** ⇒ R2 的失效判断必须记住这一条。
- 阅读位置以 `segment_id` 还是 `(chapterId, paragraphId, offset)` 为键？倾向后者 + 前者冗余（书签不变量 6）。

## Handoff

- 下一位接手 R2：先读 `data-room/.../script/ScriptRepository.kt` 与
  `data-room/.../semantic/ChapterDirectorRunner.kt`，再读本 PLAN 的 D4/D5。
- 任何"整章分析"相关改动都必须先问：**这会不会让正常阅读必须等待？** 会 ⇒ 不做。
