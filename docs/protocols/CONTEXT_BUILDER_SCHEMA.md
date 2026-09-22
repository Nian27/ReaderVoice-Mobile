# CONTEXT_BUILDER_SCHEMA — ReaderDirector 结构化上下文（TASK-070 §4）

**状态：FROZEN（v1）** | 2026-08-12 | 实现：`data-room/.../semantic/ContextBuilder.kt`

## 1. DirectorContext

```json
{
  "target": { "text": "“你来了。”", "segment_type": "speech", "position": 123 },
  "recent_segments": [ { "text": "…", "segment_type": "narration", "position": 120 }, … ],
  "candidate_speakers": [
    { "id": "cluster-…", "name": "李四", "aliases": ["老李"], "recent_turn_distance": 1 }
  ],
  "identity_constraints": [ "uid-张三 != uid-李四", "uid-张三 == uid-老王头" ],
  "embodiment": [ { "surface": "林雪", "acting_identity": "uid-mozun", "state_type": "POSSESSION" } ],
  "user_locks": [ "fixed_voice=…" ]
}
```

| 字段 | 规则 | 说明 |
|---|---|---|
| target | 段内首个 SPEECH/INNER_MONOLOGUE（无则末段） | 预测目标 |
| recent_segments | 窗口内前段 + 当前段 target 之前，上限 `windowSize*3`（默认 24） | 纯文本+类型+position |
| candidate_speakers | **仅窗口内活跃说话人**（G6：candidate restriction） | 不给全书角色表；id=identity（cluster/uid），aliases=同 cluster 成员，recent_turn_distance=距当前轮数（1=最近） |
| identity_constraints | 候选两两：同 id → `==`；异 id → `!=` | 防模型坍缩（手册 §66 Hard Negative 精神） |
| embodiment | 候选实体被附身（`actingThroughWithState`） | v1 只取 POSSESSION 类 |
| user_locks | 调用方传入 + 候选 voice state 中 `user_override=1` 的属性 | `k=v` 列表，去重 |

## 2. G6 candidate restriction 语义

候选 = 最近 `windowSize`（默认 8）段内实际开口的说话人（近→远），
与全书角色实体总数无关。测试：书中注册 6 个实体，窗口仅 张三/李四 → 候选恰为 2 人。

## 3. 消费约定

- ReaderDirector/TASK-080 三条线（Rule-only / 0.8B zero-shot / 2B upper）输入同一 DirectorContext 结构。
- `recent_turn_distance` 单调：候选列表顺序即轮换顺序（近→远）。
- v1 无 emotion/dialect 字段（见 NARRATION_IR_SCHEMA §2）。
