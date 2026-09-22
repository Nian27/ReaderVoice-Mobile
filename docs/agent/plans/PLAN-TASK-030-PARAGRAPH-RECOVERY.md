# PLAN-TASK-030 — TXT Layout Profiling & Logical Paragraph Recovery

**日期：2026-08-12（TASK-020 PASS 后）** | **状态：执行中**

## Goal

PhysicalLine + Chapter/Volume anchors → Layout Profile → Boundary Evidence → LineBoundary → SpecialBlock → LogicalParagraphRevision → CrossParagraphLink。任何自动 Join/Split 可解释、可回滚、可映射回 SourceSpan。

## Current Verified Facts

- TASK-010：PhysicalLine 双轨 offset、raw_text 不可反推 byte（必须构建时同步生成映射——§46）、Option B lazy read。
- TASK-020：Chapter/Volume anchors + TOC 块（结构硬边界 P4）；POTENTIAL_MULTILINE_TITLE 待处理（§51/§52）。
- 前置冻结：TASK-020 generalization OPEN（20-30 真实书）；legacy 规则 THIRD_PARTY_GPL/DISTRIBUTION_REVIEW_REQUIRED。

## Non-goals

- Speaker/Character/Alias/Identity/Director/Emotion/Dialect/Voice/TTS/Timeline——全部禁止（§2）。
- 不做真实 Scene Understanding（§40）；不做完整 HTML Parser（§88）；不解决跨 SourceRevision 对齐（§96-98，只记录设计）。
- 超长单行不强制断段（§91：Paragraph 结构与 TTS RenderUnit 长度限制分离）。

## Invariants（§3 P1-P5）

P1 newline is evidence not paragraph；P2 false split 比普通 false join 更破坏 Speaker context（不确定时保留 adjacency）；P3 raw source 永不改；P4 Paragraph 不跨 Chapter/Volume anchor（用户 override 除外）；P5 UNCERTAIN 合法。空行表示法必须冻结（§86）。

## Scope

```text
parser/src/main/.../paragraph/    LayoutProfiler/QuoteStack/SpecialBlockDetector/JoinPolicy/
                                  BoundaryClassifier/LogicalParagraphBuilder/
                                  NormalizedSourceMapBuilder/CrossParagraphLinker/
                                  ParagraphOverrideStore/ParagraphRecoveryPipeline
tests/fixtures/paragraph/          布局 Gold + Hard/FALSE_SPLIT_TRAPS/FALSE_JOIN_TRAPS
docs/protocols/LAYOUT_PROFILE_SCHEMA.md + LINE_BOUNDARY_SCHEMA.md +
               LOGICAL_PARAGRAPH_SCHEMA.md + NORMALIZED_SOURCE_MAP.md
docs/experiments/TASK030_LAYOUT_BASELINE.md + TASK030_PARAGRAPH_REPORT.md +
               TASK030_LARGE_FILE_REPORT.md
```

## Milestones

| # | 交付物 | 验收 |
|---|---|---|
| M1 | LayoutProfiler（Book/Chapter/Local 三层 + LayoutRegion + fixed-width modes + 空行行为） | profile fixture 分类正确 |
| M2 | QuoteStack + SpecialBlockDetector + JoinPolicy | 嵌套引号/诗歌/脚本/聊天/列表/分隔符/广告测试 |
| M3 | BoundaryFeatureExtractor + BoundaryClassifier（weighted evidence + confidence + evidence[] + compact mask） | 可解释输出；AUTO/PROVISIONAL/UNCERTAIN |
| M4 | LogicalParagraphBuilder + NormalizedSourceMapBuilder（piecewise spans + transform） | normalized→source 100% round-trip（非合成字符）；synthetic 空格标记 |
| M5 | CrossParagraphLinker + ParagraphOverrideStore（USER_JOIN/BREAK + DependencyInvalidationEvent） | SPEECH_CUE 100%；override 100% 尊重 |
| M6 | Gold/Hard/Trap fixtures + 12 类测试 + Storage A/B benchmark | G1-G12；OverMerge/OverSplit 报告 |
| M7 | 7 份 artifacts + PROJECT_STATE/DECISIONS + Gate | 报告 |

## Progress

（边做边更新）

## Decisions（预计，编号按实际调整）

- ADR-018 Boundary confidence 模型（sigmoid(|join-break|) bootstrap，Gold 校准）
- ADR-019 LogicalParagraph 存储策略（A/B benchmark 后定）
- ADR-020 synthetic whitespace SourceMap 约定（INSERTED_SPACE synthetic=true，不伪造 byte offset）
- ADR-021 空行表示法（SpacingBlock 或仅 boundary evidence，冻结一种）

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | fixed-width 检测（mode 集中度阈值） | 无 | 待跑 | 20/40/80 字 hard-wrap |
| E2 | JoinPolicy 中文/英文/混合 | 无 | 待跑 | 空格策略 |
| E3 | 存储 A（全存 normalized）vs B（spans+transforms） | TASK-010 B 结论 | 待跑 | ADR-019 |

## Validation

G1-G12（§66）+ G13=OPEN（20-30 真实书未就绪）。Error taxonomy（§76）：PROFILE/BOUNDARY_RULE/JOIN_POLICY/QUOTE_STATE/SPECIAL_BLOCK/SOURCE_MAP/TEST_EXPECTATION/GOLD_LABEL。

## Rollback

纯解析层无 DB；ParagraphRevision 版本化（parent_revision + reason），用户 override 永不覆盖。

## Artifacts

代码 + 12 类测试 + fixtures + 7 文档。

## Handoff

- TASK-040（Room Schema/Revision/Dependency DAG 冻结——用户建议的路线调整）：输入 = Book→Chapter→LogicalParagraph 层级 + 本任务 schema 文档。
