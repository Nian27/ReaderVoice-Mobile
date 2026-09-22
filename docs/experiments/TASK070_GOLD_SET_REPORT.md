# TASK-070 GOLD SET — 语义切分 / Rule-only Speaker / NarrationIR v1 / Context / Dataset

**状态：PASS（G1-G12 全绿，14 测试）** | 2026-08-12
模块：`data-room/src/main/kotlin/com/readervoice/data/semantic/`（5 文件 + 测试 1 文件）
前置：TASK-060 Character（dual query API / embodiment / voice state）— 本任务消费之。

## 0. 范围（刻意压缩为 5 件事）

1. SemanticSegment：LogicalParagraph → SemanticSegment[]（quote stack / speech cue / 标点规则，无 LLM）
2. Rule-only Speaker Baseline：显式 cue → **Easy Gold**；跨段 cue 链接；轮换追踪；UNKNOWN 不乱猜
3. NarrationIR v1：**emotion/dialect 恒 null**（不提前实现 Emotion/Dialect 模型）
4. ContextBuilder：target / recent_segments / candidate_speakers / identity_constraints / embodiment / user_locks
5. Dataset Exporter：train/dev/test.jsonl，按 book 划分，provenance 6 值枚举

**MODEL ENTRY FREEZE（见 §5）生效：TASK-070 与 TASK-080 之间不得插入新前置任务。**

## 1. 实现映射

| 文件 | 职责 |
|---|---|
| `SemanticModel.kt` | SegmentType / SemanticSegment / NarrationIR / SpeakerRef / DirectorContext / CandidateSpeaker / EmbodimentHint / DirectorSample |
| `SemanticSegmenter.kt` | 引号栈切分；INNER_MONOLOGUE（心想/暗道/寻思/琢磨/盘算/嘀咕/自言自语 cue）；段文本=原文精确切片 |
| `RuleSpeakerBaseline.kt` | EXPLICIT_SPEECH_CUE（CONFIRMED 0.95 / Easy）→ CROSS_PARAGRAPH_CUE（0.85）→ TURN_TRACKING（PROVISIONAL 0.5）→ UNKNOWN |
| `NarrationIRBuilder.kt` | resolveIdentity（whole-book, G9）/ whoIsActingThrough（G7）/ queryEffectiveVoiceState（causal, G8） |
| `ContextBuilder.kt` | 窗口候选（G6）、约束、附身、锁 |
| `DatasetExporter.kt` | 3 任务样本、provenance 映射、book-level split、确定性 JSONL |
| `CharacterStore.kt`（+3 API） | entityByCanonicalName / entityByPk / aliasesOf / actingThroughWithState |

## 2. 关键设计决策

- **段文本含引号（source-exact）**：G1 要求拼接=原文。去引号是渲染层（TASK-120+）消费端职责。
- **名字提取宁缺毋滥（Easy Gold 质量）**：2-4 字 CJK + 非停用词 + 非动作短语结尾（推开门/站起来/点点头/看了看…全拒）。
  宁把样本降级为 UNKNOWN/Hard，也不污染 Gold（§2A/B/C 精神）。
- **Cue 动词取最后一个**："李四开口说" → "说"，前置叠词（开口/回答/说道）在名字提取前剥除。
- **TURN_TRACKING 不进 Gold**：provenance=UNKNOWN，保留为 Hard 样本。
- **Legacy 记录 ≠ Gold（G10）**：legacy 存在 → provenance=LEGACY_V907[_HARNESS]、legacy_label=true、target 仅含 legacy_output。
- **org.json 拷贝构造坑**：`JSONObject(JSONObject)` 在 20240303 版不复制内容（返回空对象）——全部显式 put（代码注释已记录）。

## 3. Gate 结果（14 测试全绿）

| Gate | 内容 | 测试 |
|---|---|---|
| G1 | round-trip：拼接=原文、偏移连续、segmentId 确定性 | PASS |
| G2 | "旁白+对白+旁白" 100% 正确；手册 §29 四段；内心独白启发 | PASS |
| G3 | 显式 cue → CONFIRMED/Easy Gold | PASS |
| G4 | 跨段 cue 链接（上段末尾"张三说道："+ 下段对白） | PASS |
| G5 | 无证据 → UNKNOWN，绝不猜 | PASS |
| G6 | 候选限制在窗口内（6 实体书中仅窗口 2 人入候选） | PASS |
| G7 | embodiment：surface=林雪、acting=魔尊、voice 归属附身者 | PASS |
| G8 | voice state causal：position 50 看不到 100 的 temp 事件 | PASS |
| G9 | identity whole-book：position 500 证据反推 position 10 段 → cluster | PASS |
| G10 | provenance 枚举严格；legacy 恒 legacy_label、target 不伪装 gold | PASS |
| G11 | book-level split：4 书各整体入 train/dev/test，无跨 split | PASS |
| G12 | JSONL 确定性（两次导出字节相等）、字段齐全、sample_id=16 hex | PASS |

全模块回归：`./gradlew :data-room:test` BUILD SUCCESSFUL（含 TASK-060 CharacterSystemTest 14 项、性能/真实书测试）。
Secret scan：`node tools/secret_scan.js` PASS（268 文件无命中）。

## 4. Dataset 产物格式（G12 冻结）

```json
{
  "sample_id": "<sha256(book|ref|task) 前 16 hex>",
  "task": "SPEAKER | IDENTITY | VOICE_STATE",
  "book_hash": "…",
  "input": {
    "text": "“你来了。”", "segment_type": "speech", "text_ref": "paragraph/123/segment/1",
    "recent_context": ["…"], "scene_roles": ["cluster-…"], "rule_candidates": [{"role": "…", "distance": 1}],
    "identity_constraints": ["a != b"], "embodiment": [{"surface": "林雪", "acting": "uid-mozun", "state": "POSSESSION"}],
    "overrides": []
  },
  "target": { "speaker": "cluster-…", "surface": "张三", "status": "CONFIRMED", "confidence": 0.95 },
  "provenance": "EXPLICIT_RULE_GOLD",
  "legacy_label": false
}
```

provenance 枚举（G10）：`EXPLICIT_RULE_GOLD / HUMAN_GOLD / LEGACY_V907 / LEGACY_V907_HARNESS / TEACHER / UNKNOWN`。
HUMAN_GOLD/TEACHER 为占位来源（人工标注/教师蒸馏），v1 不产生。
划分（手册 §64）：按书 70/15/15；小语料下限 train/dev ≥1（书数 ≥2/3 时）；系列书整体同 split。

## 5. MODEL ENTRY FREEZE（冻结记录）

- **冻结内容**：TASK-070 与 TASK-080（Baseline 三线：Rule-only / 0.8B zero-shot / 2B upper bound）之间，
  不得插入任何新的前置任务；唯一例外是 TASK-070 Gate 无法满足时的修复任务。
- **TASK-080 首测顺序**（手册 §TASK-080）：同一 locked test 上先跑 Rule-only baseline → 0.8B zero-shot → 2B upper bound，
  再决定任何 LoRA 的必要性与配置。
- **理由**：Gold/上下文格式未冻结前训练会废；三条线数据形态一致（DirectorContext），对比才有意义。
- **落点**：DECISIONS.md ADR-033；PROJECT_STATE.md 里程碑 M6。

## 6. 待办（TASK-080 不阻塞，登记在案）

- 真实书 Easy Gold 统计（PRIVATE_REALBOOK_001 全量跑 baseline + exporter，产出 jsonl 到 gitignored 目录）
- Hard 样本人工标注入口（HUMAN_GOLD 首个来源）
- SpecialBlock（LETTER/MESSAGE/SCREEN_TEXT）→ SegmentType 映射（v1 压缩掉）
