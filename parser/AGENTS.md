# parser/ AGENTS.md

TXT → PhysicalLine → 章节/卷 → LogicalParagraph → SemanticSegment 的结构编译链。语义（说话人/情绪）不在此模块，见 core-character / core-director。

## 不变量

```text
1. Raw source immutable —— 任何处理只建映射，不改原文
2. 先提取 layout feature 后 trim；禁止 readLine().trim() 再分析
3. \n 只是一个证据，不等于 Paragraph；LineBoundary 必须带 confidence + evidence
4. Chapter Regex 只负责 Candidate 生成，最终判断归 Global Resolver
5. 原文 offset（byte + char）全程可回溯，任何 reflow 不得破坏 source map
6. UNCERTAIN / UNKNOWN 是合法输出，不强猜
7. 用户 JOIN/BREAK 修正 = USER_LOCKED，后续升级不得覆盖
8. 诗歌/歌词/剧本等 SpecialBlock 不自动 prose reflow
9. LLM 不做大规模 reflow；只有低置信残差才进 ReaderDirector
```

## 顺序（不可颠倒）

```text
bytes → encoding → PhysicalLine → raw structural anchors
→ Layout Profile(Book→Chapter→Window) → Paragraph Recovery → LogicalParagraph
→ text normalization → SemanticSegment
```

## 迁移源（已冻结基线）

- 章节正则：`E:\AndroidStudioProjects\FUN-legado\app\src\main\assets\defaultData\txtTocRule.json`（26 条规则）→ `LegacyChapterRulePack`；执行参考 `model/localBook/TextFile.kt:getChapterList`（Pattern.MULTILINE）。
- 行为细节：v5 Master Manual §7–27、Appendix C、M（BoundaryScore 线性打分只是初始参数，必须在 Gold Set 上校准）。

## 指标（Parser Gate，v5 §25）

```text
Chapter Boundary F1 / TOC False Positive
JOIN Precision/Recall / BREAK Precision/Recall
LogicalParagraph Exact Match
SpeakerCueBreakError / Paragraph-Induced Speaker Error Rate（关键）
```

## Do / Don't

- Do：先把 BookProfile 算出来再判 Boundary；引号用 Quote Stack（含嵌套）不用计数；硬换行拼接用中文 JoinPolicy（HAN+HAN 不加空格）。
- Don't：不要为过测试修改源书正文；不要把"一行=一段"当默认假设；不要给 PURE_NUMBER 章节候选直接判真（必须序列/密度证据）。
