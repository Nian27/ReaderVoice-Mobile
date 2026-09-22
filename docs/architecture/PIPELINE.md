# ReaderDirector 管线（AS-IS，2026-09-18）

> 以**当前代码**为准；未接线的部分明确标注 ❌，不写成"已完成"。

## 全景

```
┌─ 0 导入 ────────────────────────────────────────────────────────────────┐
│  SAF 选择 TXT                                                           │
│    → BookRepository.importDocument(uri)                                 │
│    → BookPackageImporter：复制原文 + 摘要去重                            │
│    → 私有 Book Package = files/books/<bookId>/{source.txt, manifest.json}│
│    → BookRepository.compileStructure → 章节索引（BookDatabase）         │
└──────────────────────────────┬──────────────────────────────────────────┘
                               ▼
┌─ 1 章节结构 ────────────────────────────────────────────────────────────┐
│  PhysicalLineScanner.scan(bytes, charset) → PhysicalLine[]              │
│  ChapterStructureCompiler(rules).compile(lines, bookId) → StructureRevision
│    rules = legacy/txtTocRule.json  ⊕ 用户选择（chapter_rules.json）      │
│  Chapter = {chapterId, chapterIndex, titleDisplay,                      │
│             anchorByteStart, contentStartLine, contentEndLine, state}   │
│  ← 「章节规则」界面可重分章（原子替换索引；原文/规则包不改，C1）          │
└──────────────────────────────┬──────────────────────────────────────────┘
                               ▼
┌─ 2 段落恢复（Ingestion→Understanding 的边界）──────────────────────────┐
│  ParagraphRecoveryPipeline.run(lines, bookId)                           │
│    LayoutProfiler → BlockGrouper → BoundaryClassifier/QuoteStack/       │
│    JoinPolicy → LogicalParagraphBuilder                                 │
│  → LogicalParagraph{ paragraphId, chapterId, paragraphIndex,            │
│                      sourceSpans, normalizedText, readPolicy }          │
│  契约：C2 物理行 ≠ 逻辑段；C1 原文不可变（只建 source map）              │
└──────────────────────────────┬──────────────────────────────────────────┘
                               ▼
┌─ 3 理解（纯规则/DB，不含 LLM）─────────────────────────────────────────┐
│  SemanticSegmenter.segment(p) → SemanticSegment[]{text, sourceStart/End}│
│  RuleSpeakerBaseline(store.canonicalNamesOf(bookPk)).assign(p, segs,…)  │
│    ├─ 显式 cue / 后置 cue / 跨段 cue（extractTrailingCue）              │
│    ├─ DeliveryCue：状语/方式碎片 → 表演提示（不当人名）                  │
│    └─ NameEvidence：群体名 / 别名 / 矛盾（有界文本证据）                 │
│  SpeakerCandidateCompiler：CUE ∪ RECENT_MENTION ∪ SCENE_ACTIVE          │
│  ContextBuilder.build(...) → DirectorContext                            │
│    ├─ candidateSpeakers: localId(C0/C1…) + identityId(内部，不入 prompt)│
│    ├─ evidenceItems: E0/E1…（DELIVERY_CUE/SPEAKER_CUE/RULE…）           │
│    ├─ identityConstraints / userLocks / recentSegments(window)          │
│    └─ 契约：C4 模型只见局部 C#；C5 证据只见 E#                          │
└──────────────────────────────┬──────────────────────────────────────────┘
                               ▼
┌─ 4 导演（Directing：唯一用 LLM 的一段）────────────────────────────────┐
│  逐 SemanticSegment（顺序、可协作式取消）：                              │
│   a. DirectorSelectionPrompt.skipDecision(ctx)                          │
│        ├─ 〔旁白〕 → NARRATOR（不调模型，省调用 + 免"提到谁就选谁"）      │
│        └─ 对白且候选为空 → UNKNOWN（C8 合法结果，不猜名字）              │
│   b. 否则 DirectorSelectionPrompt.render(ctx) → prompt                  │
│   c. decider(prompt)：EngineWatchdog.generate(…, maxTokens=96)          │
│        → QwenEngine(JNI) → MNN(HTP 0:23) → 原始 JSON 文本               │
│   d. ProtocolNormalizer.normalize(raw) → NormalizedOutput               │
│        （只修**表示**：裸 token 引号/枚举大小写/扁平 delivery/围栏…，C10）│
│   e. DirectorOutputValidator.validate(…) → ValidationResult             │
│        硬失败→REJECT（悬空 C#、type 冲突、segment 不匹配…，C9 不偷改）   │
│        软失败→DOWNGRADE（情绪/表演参数回安全默认）                       │
│   f. ★ LocalCandidateResolver.toSpeakerRef(decision, CandidateMap)      │
│        C# → CHARACTER:<稳定 id>；NARRATOR→Narrator；UNKNOWN→Unknown     │
│        （C18：局部 ID 到此为止；解析不出 ⇒ fail-closed）                 │
│   g. ScriptLineBuilder.build(paragraphText, segment, ctx, result)       │
│        text = paragraphText.substring(sourceStart, sourceEnd)  ← C7 断言 │
│        speaker = SpeakerRef；route = AUDIO8 / COSYVOICE / UNKNOWN_FALLBACK│
│        status = PENDING（ACCEPTED_ALL ≠ COMMITTED）                     │
└──────────────────────────────┬──────────────────────────────────────────┘
                               ▼
┌─ 5 产物（增量、可回溯）────────────────────────────────────────────────┐
│  files/chapter_director/scripts/<bookId>_<chN|p from-to>_<ts>.jsonl  ← 每轮归档│
│  files/chapter_director/script_lines.jsonl        ← 最新一轮镜像（工具读它）│
│  files/chapter_director/stats.txt                 ← 统计（coverage/结局/失败码）│
│  files/chapter_director/characters_<bookId>.json  ← 角色发现缓存           │
│  schemaVersion=2：speakerRef ∈ {NARRATOR, UNKNOWN, CHARACTER:<id>}       │
└──────────────────────────────┬──────────────────────────────────────────┘
                               ▼
┌─ 6 渲染 / 播放 ────────────────────────────────────────────────────────┐
│  ❌ **尚未接线**：ScriptLine 目前只被测试消费（grep 验证）               │
│  已存在的零件（未串起来）：                                             │
│    · NarrationIRBuilder（ScriptLine 之前的一代：从规则结果建 NarrationIR）│
│    · RenderUnitBuilder / RenderInstructionCompiler / SpeechRuntimeContract│
│    · CosyVoice 角色音色栈（Runtime/Store/Profile/Natives/Decoder）        │
│    · scheduler 模块（JobState/RenderUnitRef/PlaybackQueue）—— 无人引用 ✗ │
│    · Audio8 旁白后端 —— 目录是空占位 ✗                                   │
│  ⇒ 路由已定：NARRATOR→Audio8（C12）/ 角色→CosyVoice（C13）/ UNKNOWN→兜底   │
└─────────────────────────────────────────────────────────────────────────┘
```

## 运行时（平台能力，被编排注入）

```
编排层 Orchestration
  ├─ ChapterListActivity【开始导演 / 导演本章】→ startForegroundService
  ├─ ChapterDirectorService（前台服务 dataSync）
  │    ★ 为什么是服务：Activity 不可见 ⇒ 进程 cached ⇒ 被 Freezer 冻结（实测两次）
  │    职责：读原文 → 章节 → 段落 → 发现角色(带缓存) → 建 store → 载模型
  │          → 跑 ChapterDirectorRunner(逐段) → 归档/统计 → 通知进度 + 取消动作
  ├─ ChapterDirectorActivity（运行台）：进度条 + 实时日志 + 取消(epoch) + 查看剧本 + 完成回跳
  ├─ ReaderActivity（原文） / ChapterRulesActivity（重分章） / ScriptViewActivity（剧本）
  └─ 总线 ChapterRunBus(log/summary/running/done/total/lines) 供 UI 镜像

Runtime
  ├─ QwenEngine（有状态 native session）+ qwen_engine_jni.cpp
  ├─ EngineWatchdog：native 调用跑独立线程 + 30s deadline + 15s grace
  │    → TIMEOUT/CANCELED ⇒ 本段 NO_OUTPUT（fail-closed，runner 继续）
  │    → 超出 grace ⇒ ENGINE_STUCK ⇒ 停止后续模型调用（不再堆请求）
  ├─ MNN fork：AR 循环每 token 检查 USER_CANCEL/timeout_ms（协作式，绝不杀线程，C15）
  ├─ QwenBackendPolicy：默认 CPU；Hexagon 仅经 NPU_EXPERIMENTAL（C14）
  └─ 模型目录 files/director-model/{llm.mnn, llm.mnn.weight, llm_config.json}
```

## 契约落点（谁保证哪一条）

| 契约 | 落点 |
|---|---|
| C1 原文不可变 | `BookPackageImporter`（复制原文）+ 所有层只读 source |
| C2 物理行≠逻辑段 | `paragraph/`（BoundaryClassifier/QuoteStack/JoinPolicy） |
| C3 提及≠身份 | `CharacterModel` 类型分离（一等 Mention 层待 CH-1） |
| C4 只见局部 C# | `ContextBuilder`（localId 置换）+ 测试断言 prompt 无 identityId |
| C5 只见 E# | `ContextBuilder.evidenceItems` + Validator 成员性校验 |
| C6 导演不产文本 | `DirectorDecisionV2` 无文本字段（旧 freeform 路径 policy 阻断） |
| C7 text=canonical span | `ScriptLineBuilder` 运行时断言（不一致即抛） |
| C8 UNKNOWN 合法 | `skipDecision` + 四态 `VERIFY` |
| C9 悬空 C# 硬失败 | `DirectorOutputValidator` → REJECT（绝不偷改） |
| C10 只修表示 | `ProtocolNormalizer`（9 条允许/禁止测试） |
| C11 用户锁定最高 | `CorrectionStore` + `userLocks` 进 prompt（身份/音色层待 CH-1/CH-2） |
| C12/C13 路由 | `TtsRoute`（后端未接线） |
| C14 后端不改协议 | App↔shell logits 逐字节相等验证 |
| C15 协作式取消 | MNN `requestCancel` + JNI deadline + `EngineWatchdog` |
| C16 全本地 | 无网络上传路径 |
| C17 COMMITTED 不被替换 | `ScriptLineStatus` 已定；调度侧未接线（CH-4） |
| C18 局部 ID 不过边界 | `SpeakerRef`/`CandidateMap`/`LocalCandidateResolver`（真机 464 行 0 泄漏） |

## 一次真实运行的量级（USB 真机，章节 60..200）

```
segments=140  shortcut=111（旁白不问模型）  model_calls=29  no_output=0
ACCEPTED_ALL=137  DOWNGRADE=1  REJECT=2   coverage=98.6%   anchor=100%
watchdog: timeouts=0 canceled=0 stuck=0   3 次运行统计逐项一致   ≈310s/次
```

## 明显的设计取向（为什么这样做）

1. **能不用模型就不用**：旁白 shortcut 省掉 79% 调用；候选为空直接 UNKNOWN。
2. **模型只做选择题**：只回 `C#/NARRATOR/UNKNOWN` + 表演参数；文本、身份、路由都不由它定。
3. **两个 ID 严格分离**：模型看 `C#`（每样本重排），内部用稳定 id；`C#` 在校验边界后立刻死亡（C18）。
4. **fail-closed 优先于覆盖率**：宁可 REJECT/NO_OUTPUT 并计入统计，也不产出可疑剧本。
5. **口径冻结**：失败码、四态结局、JSONL schemaVersion 都冻结并写进架构 policy。
6. **长任务离开 Activity**：前台服务承载，用户可切走/锁屏。
