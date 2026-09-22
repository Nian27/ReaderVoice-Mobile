# ReaderVoice-Mobile

> **一款完全端侧、有状态、可增量编译、可局部修正的多角色有声书编译器。**
> 首先它是一款**能正常读书的本地小说阅读器**；ReaderDirector（端侧 ReaderDirector 剧本引擎）是它的**智能剧本缓存层**。

本仓库是 **ReaderVoice-Mobile v5** 的公开快照：从 TXT 导入 → 章节结构恢复 → 段落/语义切片 → 端侧 Director 判定「谁在说、怎么说」→ 持久化剧本 → （后续）本地 TTS 朗读。

```
书架 ──▶ 目录 ──▶ 阅读正文 ──▶ 阅读位置持久化
                     │
                     └──▶ Director 剧本缓存（只准备"读到的位置附近"，按批次落盘）
                                │
                                └──▶ 角色档案（年龄/性别/身份 + 原文证据）
                                           │
                                           └──▶ 音色绑定（CosyVoice3-MNN，冻结中）
```

---

## 1. 这是什么（产品定位）

**不是**"导入一本书 → 点『分析整章』→ 等 5 分钟 → 看 JSONL 的 AI 工具"。

**是**一款正常好用的阅读器，只是在阅读链路里悄悄接上 ReaderDirector、角色库与本地 TTS：

```
用户读到第 126 章第 37 段
        │
        ▼
剧本已备到 第 54 段？──是──▶ 什么都不做（0 次模型调用）
        │否
        ▼
后台按批准备：前 4~8 段上下文 + 目标 8~16 段 + 后 1~2 段 lookahead
        │
        ▼
每批完成立即落盘（进程被杀也只丢当前批）
```

核心不变量（节选自 `AGENTS.md`）：

| # | 不变量 |
|---|---|
| 1 | 原文永不修改，只建立 source map |
| 2 | `\n` 是证据，不是段落 |
| 3 | `Mention != Identity != Embodiment != VoiceState` |
| 5 | **UNKNOWN 是合法结果，禁止强造角色名** |
| 8 | User Locked 最高优先级，模型不得覆盖 |
| 9 | **Playback/阅读窗口永远优先于未来生成** |
| 11 | "能跑"不等于"通过"（最终 PCM Gate） |

### 与实验原型的关键差别（本项目踩过的最大一个坑）

| | 实验原型思路（错） | 本仓库（Reader-first） |
|---|---|---|
| 章节 | `revision.chapters[50]`（候选集合，含 REJECTED/PROVISIONAL） | `book.chapters[50]`（canonical，用户真能看到的一章） |
| 触发 | 手动"整章分析"（前台服务跑 10 分钟） | **阅读位置驱动**的窗口预取 |
| 剧本 | `runs/xxx/script_lines.jsonl`（run 产物） | **这本书的数据**（`books/<id>/scripts/<chapterId>/`） |
| 位置 | Activity 临时状态 | **一级 Book 状态**（`reading_state.json`） |
| 角色 | 后台抽象 `CharacterStore` | 用户可见的**角色档案页**（含证据、可手工修正并锁定） |

---

## 2. 现状（已实现 / 冻结中）

**已实现并真机验证**

- 章节结构恢复：26 条 legacy 规则包 + **激进规则默认关闭**（与阅读器产品语义一致）
- Canonical chapters 唯一产品口径（`ConfirmedChapterView`，含重算的正文区间）
- 语义切片 / 规则说话人基线 / 候选人名合法性闸门 / 上下文构建
- 端侧 ReaderDirector 协议 `DIRECTOR_SELECTION_V1`（四态判定 + 15 个失败码 + fail-closed）
- 剧本持久化：按章 JSONL + `state.json` 检查点 + revision 目录 + 缓存命中判据
- **阅读器**：段落级精确位置、重启回到原位、字号、上下章
- **书架**：读到第 N 章 + 继续阅读
- **目录**：canonical 章节、当前章高亮、空状态引导
- **角色档案**：年龄/性别/身份 + 原文证据 + 置信度 + 未知可编辑（修正即锁定）

**冻结中（明确不做）**

- TTS / Audio8 / CosyVoice3 / VoiceBinding —— 当前瓶颈不是"有没有声音"

---

## 3. 架构

```
parser/            TXT → PhysicalLine → 章节/卷 → LogicalParagraph（纯 JVM，无 Android 依赖）
parser-core/       sourceSet 视图（复用 parser 源码，供 Android 侧编译）
data-room/         语义层 + 持久化：Segmenter / RuleSpeakerBaseline / CandidateCompiler /
                   NameLegitimacy 闸门 / CharacterDiscovery / ContextBuilder /
                   ChapterDirectorRunner / ScriptRepository / ScriptCache / 角色档案抽取
semantic-core/     sourceSet 视图（data-room 的语义部分）
scheduler/         调度（可抢占/可修正）—— 尚未接线
audio-renderer/    音频渲染契约
core-character/    角色域契约
core-director/     Director 域契约
tts-cosyvoice/     CosyVoice3-MNN 集成（冻结）
app-android/       Android 阅读器（Compose + 前台服务承载长任务）
voicedesign-app/   音色设计 App（独立产品线）
tools/             离线分析/审计/Gate 脚本
docs/              状态、决策（ADR）、架构、ExecPlan
```

### 数据流（当前）

```
TXT ──▶ PhysicalLineScanner ──▶ ChapterStructureCompiler ──▶ ConfirmedChapterView
                                                                    │
                                                                    ▼
                                          ParagraphRecoveryPipeline ──▶ LogicalParagraph
                                                                    │
                    ┌───────────────────────────────────────────────┘
                    ▼
          SemanticSegmenter ──▶ RuleSpeakerBaseline（规则先判断）
                    │                     │
                    │                     └──▶ CharacterDiscovery（含人名合法性闸门）
                    ▼
          ContextBuilder ──▶ SpeakerCandidateCompiler ──▶ 候选 C0..Cn
                    │
                    ▼
          ReaderDirector（端侧 MNN，只判残差）──▶ DirectorOutputValidator（四态）
                    │
                    ▼
          ScriptLineBuilder ──▶ LocalCandidateResolver（C# → 稳定 CharacterId）
                    │
                    ▼
          ScriptRepository（按章 JSONL + 检查点）──▶ 阅读器 / 角色档案
```

---

## 4. 快速开始

### 构建

```bash
# JDK 21 + Android SDK（compileSdk 36 / minSdk 26）
./gradlew.bat --offline :app-android:assembleDebug     # Windows
./gradlew :app-android:assembleDebug                   # Linux/macOS
```

### 测试（纯 JVM，不需要设备）

```bash
./gradlew.bat :parser:test :data-room:test
```

关键 Gate（每个都对应一个真实事故，详见 `docs/PITFALLS.md`）：

```
ChapterBoundaryGateTest                 真实书章节边界不变式（3 书 × 4 抽样章）
CanonicalChapterDisciplineGateTest      生产代码零引用 revision.chapters
ChapterIndexCompatGateTest              加列不得让旧包崩溃（读路径兼容）
NameLegitimacyGateTest                  人名合法性闸门 + 产品路径端到端
CharacterDiscoveryCrossBookGateTest     闸门跨书泛化（含已知局限）
SpeakerRecoveryGateTest                 规则层说话人回收（冒号型 cue / 名字不在最后块）
CharacterProfileGateTest                角色档案：抽得到 + 不编造
C18SpeakerRefGateTest                   局部 C# 不得越界持久化
```

### 真机

```bash
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
# 导入 TXT → 书架【开始阅读】→ 目录【阅读】→ 正文
```

---

## 5. 里程碑与证据

| 里程碑 | 内容 | 状态 |
|---|---|---|
| TASK-000~040 | 治理 / TXT 摄取 / 章节结构 / 段落恢复 / Room Schema | ✅ |
| TASK-060~070 | 角色身份（Model B）/ Gold Set / 语义基线 | ✅ |
| TASK-080~095 | ReaderDirector 0.8B LoRA / Candidate 架构 v2 | ✅（095 有 CONDITIONAL FAIL 项） |
| MOBILE-004 | 端侧 ReaderDirector 引擎（MNN + Hexagon） | ✅ |
| MOBILE-005 | 真实章节 ReaderDirector（协议冻结 + 真机全绿） | ✅ |
| CH-0 | 章节批次化运行时 + **质量修复①②** | 🔶 进行中 |
| **R1** | **canonical chapters + 规则默认保守** | ✅ |
| **R2** | **剧本 = Book 持久数据 + 缓存命中** | 🔶 部分 |
| **R4** | **阅读位置一级数据 + 阅读器/书架/目录** | 🔶 部分 |
| R3 / R5 | Window/Batch Director / 整章分析降级为预缓存 | ⏳ |
| CH-1~4 | 角色身份晋升 / 音色绑定 / 模块边界 / 调度接线 | ⏳ |

**真机实测数据（`book-d22a7b3878ee`，4.33 MB，475 确认章节）**

```
章节：rawCandidates 497 → confirmed 475，不变式违规 0
剧本：76/76 段，19 次模型调用，0 REJECT，耗时 605s → 190s
非人说话人：9/20 → 0/76
角色闸门：371 个"名字" → 81 个（手工标注真名 74/74 保留，290/297 污染拦下）
```

---

## 6. 坑（最重要的部分）

**完整清单见 [docs/PITFALLS.md](docs/PITFALLS.md)** —— 从章节识别、候选人名污染、协议设计，到真机环境与工具链，把踩过的每个坑、错误假设、以及"为什么当时没发现"都写下来了。摘要：

| 类别 | 最贵的一个坑 |
|---|---|
| 章节 | 规则包的 `enable=false` **读了却没完全执行** ⇒ 4.33MB 书产出 16,060 个假章节（`====`、`《…》`） |
| 架构 | `revision.chapters` 是**候选集合**，按下标当章节序 ⇒ 假章节 + 错范围 ⇒ "Director 看起来特别慢" |
| 数据 | 跨层字段改了**接口没改数据流** ⇒ 按章导演产出 0 段（单测全绿） |
| 质量 | 候选名字 80% 是 cue 切片 ⇒ **协议指标全绿，但 20 段里 9 段说话人不是人** |
| 评价 | 没有人工标注（MANUAL_GOLD）就**不允许报准确率** —— 我们曾用"协议合规率"冒充质量 |
| 真机 | logcat 加密 / cached-app Freezer / 厂商安装确认 / 无线 adb 不稳 / 另一个 debug app 吃 4GB |
| 工具链 | PowerShell 单引号里 `` `n `` 是字面量、`[sxsy]` 文件名被当通配符 —— 两次把 CRLF 写进源码 |

---

## 7. 数据与合规声明

本仓库**不包含**：

- 任何书籍原文（`books_private/`、根目录 `*.txt` 小说均已排除）
- 第三方资产（`research/third_party/`，含 v90.7 原始规则 JSON，`LICENSE_UNKNOWN`，**RESEARCH_ONLY**）
- 模型权重与转换产物（`*.mnn` / `*.onnx` / `*.raw` / `*.so`）
- 用户数据（角色修正、VoicePack 原始音频）
- 任何凭据（`tools/test-fixtures/fixture.secrets.js` 是**合成假样本**，用于 secret 扫描器自测）

关于参考实现：本项目对第三方工程一贯采用「**逆向 → 自研重建，不搬运代码**」。阅读器产品语义（章节规则默认开关、阅读进度作为书籍持久状态、角色/发音人管理）参考了 Legado 系阅读器的**产品形态**，实现全部为本仓库自研；仅 `parser/src/main/resources/legacy/txtTocRule.json` 为规则数据（26 条正则），其来源与许可证见 `docs/baseline/`。

## 8. 相关仓库

- [CosyVoice3-MNN](https://github.com/Nian27/CosyVoice3-MNN) —— 端侧 CosyVoice3 的 MNN 部署
- [VoiceDesign-MNN](https://github.com/Nian27/VoiceDesign-MNN) —— 音色设计

## 9. 许可

代码见 `LICENSE`。第三方规则数据与模型资产不随本仓库分发。
