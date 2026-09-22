# NARRATION_IR_SCHEMA — NarrationIR v1（TASK-070 §3）

**状态：FROZEN（v1）** | 2026-08-12 | 实现：`data-room/.../semantic/NarrationIRBuilder.kt` + `SemanticSegmenter.kt`

## 1. SemanticSegment（段落内语义切片）

```kotlin
data class SemanticSegment(
    val segmentId: Long,          // paragraphId * 1000 + segmentIndex（确定性，G1 可重建）
    val paragraphRevisionId: Long,// = 段落稳定 ID（round-trip 基准）
    val segmentIndex: Int,
    val type: SegmentType,        // NARRATION/SPEECH/INNER_MONOLOGUE/GROUP_SPEECH/QUOTE/UNKNOWN
    val text: String,             // 原文精确切片（**含引号**，source-exact）
    val sourceStart: Int,         // normalizedText 内偏移
    val sourceEnd: Int,
    val quoteDepth: Int,          // v1 恒 0（嵌套引号不打断）
)
```

规则切分（无 LLM）：
- 引号（`“” 「」 『』`）内 → SPEECH；引号前 cue 为 `心想/暗道/寻思/琢磨/盘算/嘀咕/自言自语`（可带 `心里/暗自/不由/默默` 前缀）→ INNER_MONOLOGUE。
- 引号外 → NARRATION。多段共段："张明推开门，说："你来了。"李雪抬头："嗯。"" → 4 段（手册 §29）。
- **段文本 = 原文切片（含引号）**：保证 G1 round-trip 无损（拼接=normalizedText、偏移连续）。渲染层需要去引号是消费端问题（TASK-120+）。

## 2. NarrationIR v1

```json
{
  "segment_id": 1230001,
  "segment_type": "speech",
  "text_ref": "paragraph/123/segment/1",
  "speaker": { "identity_id": "cluster-…", "status": "CONFIRMED", "confidence": 0.95 },
  "acting_identity_id": "uid-mozun",
  "surface_entity_id": "uid-linxue",
  "voice_state_ref": "phase=ADULT;temp=START:{…};age=45",
  "language": "zh",
  "emotion": null,
  "dialect": null,
  "provenance": ["EXPLICIT_SPEECH_CUE"]
}
```

冻结字段语义（v1 最小结构，**emotion/dialect 恒 null**——模型未实现前禁止提前定义）：

| 字段 | 来源 | 说明 |
|---|---|---|
| speaker.identity_id | `resolveIdentity(bookPk, entityPk)`（**whole-book**，ADR-030） | clusterId（合并后）否则 entityUid；实体未建退化为 surface |
| speaker.status | RuleSpeakerBaseline | CONFIRMED/PROVISIONAL/UNKNOWN |
| acting_identity_id | `whoIsActingThrough(bodyEntityPk, position)` | 附身：林雪身体 → 魔尊（G7） |
| surface_entity_id | 实体 uid | 说话的表面实体 |
| voice_state_ref | `queryEffectiveVoiceState(voiceEntityPk, position)`（**causal**） | 声音归属=附身者（若有）否则 surface；摘要格式 `k=v;k=v` |
| provenance | baseline 结果 | EXPLICIT_SPEECH_CUE/CROSS_PARAGRAPH_CUE/TURN_TRACKING/UNKNOWN/RULE_NARRATION |

## 3. 双查询边界（G8/G9，ADR-030）

- identity：whole-book（后文证据可反推早期，G9 已测：position 500 证据 → position 10 段已解析到 cluster）。
- voice state：`position` 之前证据（G8 已测：position 50 段看不到 position 100 的 temp 事件）。

## 4. Round-trip（G1）

`segments.joinToString("") { it.text } == paragraph.normalizedText`；
`seg[i].sourceEnd == seg[i+1].sourceStart`；`text == normalizedText.substring(start, end)`。
由 `Task070GateTest.G1` 覆盖。
