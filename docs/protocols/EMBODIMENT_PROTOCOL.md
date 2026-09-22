# EMBODIMENT_PROTOCOL.md — 附身/控制协议（TASK-060 §24-§26）

**日期：2026-08-12**

## EmbodimentInterval（§26）

```text
identity_entity_pk（魔尊）/ body_entity_pk（林雪）/ state_type(CONTROL|EMBODIMENT|IMITATION|POSSESSION)
start_position / end_position? / evidence_id / confidence
```

## 不变量（§25/G7 回归写死）

```text
魔尊 controls 林雪 → identity A != identity B（CHECK 约束 + 测试）
possesses / imitates / embodies 均 ≠ SAME_PERSON
```

## 查询

`whoIsActingThrough(body, position)` → acting identity（或 null）。Speaker 层得到 surface mention + acting identity 双字段。
