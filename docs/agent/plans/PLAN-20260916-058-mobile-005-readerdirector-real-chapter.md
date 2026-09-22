# PLAN-20260916-058 — MOBILE-005：真实章节 ReaderDirector 端到端（Segment-ID 选择协议）

## Goal

把「真实章节文本」在设备上跑成**逐 SemanticSegment 的可校验结构化 Director 结果**：
模型只做**选择题**（segment_id / speaker=C# / evidence=E#），正文永远取 canonical sourceSpan。

## Current Verified Facts

```
① App 现状：只有单段手输的 Debug 台（ReaderDirectorActivity + free-form DirectorPrompt）。
② 语义层在桌面 JVM：data-room/{semantic,character}（Segmenter/RuleSpeaker/CandidateCompiler/
   ContextBuilder/NarrationIRBuilder/CharacterStore），sqlite-jdbc，ROOM_RUNTIME_GATE=OPEN。
③ 段落恢复在 parser/paragraph（11 文件/908 LOC），App 只依赖 parser-core ⇒ 拿不到 LogicalParagraph。
④ 可移植性：paragraph 唯一 JVM 依赖 java.lang.Character.UnicodeScript（Android API24+ 可用）；
   semantic 的 JVM-only 只在 DatasetExporter；character 的 JVM-only 只在 CharacterStore(JDBC)。
⑤ SemanticSegment 已含 sourceStart/sourceEnd（段落 normalized text 内偏移）= 不可变 canonical span。
   DirectorContext 缺 segmentId 与 evidence 列表（M2 需加）。
⑥ ★ M0 PASS（2026-09-16）：rd-mnn-v2 稳定执行 selection-style —— P1/P2/P3/P4 全 25/25、
   P5 gold 一致率 76%、3 次运行 100% 一致 ⇒ M5 不触发（详见 runs/.../m0_protocol_probe/report.md）。
⑦ 时延（上一轮）：HTP decode 稳定 60~72 ms/token，CPU decode 15~263 ms/token（抖动）；
   逐段 reset 不跨段累积 KV。
```

## Non-goals

```
× 不接 TTS（CosyVoice/Audio8 合成与播放）  × 不动模型权重（M5 已判定不触发）
× 不做完整 Room 迁移（设备侧注入只读角色快照）  × 多书并发/调度器/缓存
× 不改 raw source；无远程上传
```

## Invariants

```
根 AGENTS 1/2/3/4/8/10/15；★ 新增（写入 DECISIONS）：
"Director 输出只允许引用既有对象（segment_id / 候选 ID / evidence ID），不得产生生产文本"
```

## Scope

```
新增  parser 段落包 → Android 可消费（优先就地扩 parser-core）
新增  语义层核心 → Android（不含 DatasetExporter；CharacterStore 抽接口 + Snapshot 实现）
新增  app-android: ChapterDirectorRunner / DirectorSelectionPrompt / DirectorOutputValidator /
                   NarrationIRBuilderAndroid / SnapshotCharacterStore / ChapterDirectorActivity
改    data-room: ContextSegment += segmentId；DirectorContext += evidenceItems（确定性 ID 分配）
新增  docs/protocols/DIRECTOR_SELECTION_V1.md
新增  tools/mobile005/{paragraph_parity.py,semantic_parity.py,judge_selection.js,run_m0.ps1}
```

## Baseline

```
现状：无章节级 Director；单段 free-form；G_APP5 实测语义 ≥3/5 错（speaker 误判 / 文本漂移），
      CPU 对照同型错误 ⇒ 模型/prompt 层。M0 后协议已定为选择题，且模型可稳定执行。
```

## Milestones

```
M0 ✅ 2026-09-16  协议探针（selection-style 可执行性）—— PASS，M5 不触发
M1 ✅ 2026-09-16  设备端段落化 + G1 逐段一致（34566 段位级等价）
M2 ✅ 2026-09-17  设备端语义层与桌面位级一致（canonical md5 相同）
M3 🔶 步骤 1/1b/2/3+4/5+6 PASS；仅剩"补跑剩余 prompt + App 内直连"
M4 🔶 部分完成：真实章节端到端已跑通（coverage 79.6%，缺 23 条 prompt 未执行）；**整章 Gate（CPU default + P50/P95 + rule-only 对照）尚未做** 整章 Gate（CPU default）+ 指标 P50/P95 + 与 rule-only 对照
M5 ⛔ 不触发（M0 已判定模型可稳定做选择题）
```

## Progress

```
M0 ✅ 25 目标 × 3 次；P1/P2/P3/P4 = 25/25，P5 = 19/25，P6 = 0%，一致性 25/25。
     产物 runs/mobile_005_director_real/m0_protocol_probe/**。
     三条观察带入 M3：① Validator 需容忍裸 ID/枚举（cosmetic 计数）；② UNKNOWN 触发条件需显式评估；
     ③ 错例全为候选内选错（语义层问题）。
M1 ✅ 删掉 parser-core 的 paragraph exclude（依赖闭合已核验）；设备端 dump 34566 段与桌面逐段一致（含 sha256）⇒ G1 PASS；设备端整本 24.6 s / ≈0.7 ms 段。
M2 ✅ 接口抽取（CharacterReadStore 9 只读）+ semantic-core 模块 + 记录/回放机制（M2ParityRunner）完成；**G2 PASS：desktop/device canonical md5 均为 36EA14E6…（47 条逐字节一致）**；回放自校验 PASS（traceKeys=96）。
M3 🔶 **步骤 1 PASS（2026-09-17）**：评价口径冻结（ADR-055：规则输出永不充当 gold，MANUAL_GOLD 才能进硬 Gate）
   + cue 污染修复（delivery cue → evidence，不进候选/不当 speaker；实体集安全阀）
   + 动词短语残留修复（CUE_VERB 扩展 + 实体前缀消歧）
   + ContextBuilder 改用 Candidate Compiler（CUE ∪ RECENT_MENTION ∪ SCENE_ACTIVE）
   + EvidenceItem/segmentId 落地；G2 在新语义下重跑仍 PASS；8 新测试 + 42 既有测试全绿
   ★ 用户冻结的 M3 产品语义与四层 Gate（G3a 锚定安全 / G3b 剧本覆盖 / G4 只在 MANUAL_GOLD 上评质量 / G5 整章）
   步骤 2 ✅ C#/E# 局部 ID 落 ContextBuilder（本地 ID + 确定性 permutation + sources）+ 共享 DirectorSelectionPrompt
   步骤 3-6：冻结 DirectorDecisionV2 → ProtocolNormalizer/Validator/ScriptLineBuilder
             → ChapterDirectorRunner → 先 5 段 → 20~30 段人工校正 → 整章
```

## Decisions

```
D1 推理单元 = SemanticSegment（不是 LogicalParagraph）；paragraph 只是 context container
D2 模型不产出任何生产文本；NarrationIR.text 恒 = canonical sourceSpan（Anchor safety 结构性成立）
D3 evidence 与 speaker 都是 ID 选择题；Validator 只做成员性校验 + fail-closed，不做语义修正
D4 候选 = 生产候选（compiler）+ 每样本确定性 permutation（沿用 DECISIONS 冻结设计）
D5 整章 Gate 以 CPU 为正式后端；HTP 只做语义 parity 抽样（因 CPU/HTP logits 逐字节相同，M0 借用 HTP 加速）
D6 M5 关闭
```

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | selection 协议可执行性 | 无 | P1–P4 25/25，P5 19/25，3× 一致 | PASS，M5 不触发 |
| E2 | 素材公平性（候选是否含 gold） | 初版召回 0/25 | 扩大实体集（17→65）后 25/25 | 素材问题非管线缺陷 |
| E3 | 协议卫生 | — | cosmetic=150（裸 ID/枚举） | Validator 需容忍 |

## Validation

```
G1 段落化一致（设备 vs 桌面 parser，逐段 sha256）
G2 语义层一致（设备 vs 桌面 data-room，逐条）
G3a Anchor safety = 100%（结构不变量：text 必为 canonical span 切片）
G3b segment_id 绑定正确     G4 speaker/evidence 成员性     G5 acceptance rate + fail-closed 率
G6 整章（CPU）：无 crash/OOM、可取消、指标完整（P50/P95/UNKNOWN/acceptance/内存）
命令见 PLAN-058 §9（tools/mobile005/*）
```

## Rollback

```
全部为新增文件 + 独立 Activity 入口；旧 Debug 台 / QwenEngine 契约 / 模型 / .so 均不动 ⇒ 回滚 = 不启动新入口
```

## Artifacts

```
runs/mobile_005_director_real/{m0_protocol_probe,m1_paragraph_parity,m2_semantic_parity,m3_gate,m4_chapter}/
docs/protocols/DIRECTOR_SELECTION_V1.md
```

## Open Issues

```
1) UNKNOWN 触发条件（M0 观测 0%）—— M3 需给出显式判据，否则"假确定"
2) 候选集偏小（2~4）且质量依赖实体发现（TASK-100 Candidate Recall 是独立 Gate）
3) 设备端与桌面行为对齐的字符类/规则细节（G1/G2 会暴露）
4) 逐 segment reset + 窗口上下文是否伤害语义（M3 acceptance/UNKNOWN 观察）
```

## Handoff

```
下一步 M1：把 parser 段落包做成 Android 可消费，并跑 G1 逐段对拍（books: 第 1/50/249 章）。
注意：M1 开工前先决定"就地扩 parser-core"还是"新增 parser-paragraph 模块"，取改动面更小者。
```







