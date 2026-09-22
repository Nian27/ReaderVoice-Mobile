# CHARACTER_IDENTITY_MODEL.md — Identity Model（TASK-060 §1/ADR-028）

**日期：2026-08-12** | 消融结论：Model B 冻结。

## Model B（冻结）

```text
Mention（文本事实，永删不）
  ↓
NarrativeEntity（book 隔离，type: PERSON/GROUP/NARRATOR/SYSTEM/NON_PERSON_AGENT/UNKNOWN）
  ↓
Identity（IdentityClusterMembership，非破坏式）
  ↓
Embodiment（interval：identity ≠ body）
  ↓
VoiceState（Base/Phase/Temporary，与 User Voice Constraint 分离）
```

## 消融 7 case（Model A 淘汰）

| case | Model A | Model B |
|---|---|---|
| 普通别名（张明=张教授） | ✓ | ✓ |
| 群体（众人≠张明） | ✓（需补丁） | ✓（type 隔离） |
| 关系（妈妈≠女儿） | ✓（需拦截） | ✓（relation ≠ SAME） |
| 控制（魔尊控制林雪） | ✗ 需特殊补丁 | ✓（embodiment interval） |
| 附身（借身体说话） | ✗ 需特殊补丁 | ✓（whoIsActingThrough） |
| 冒充（张明模仿李四） | ✗ | ✓（IMITATION） |
| UNKNOWN 后确认 | ⚠️ | ✓（cluster update） |

Model A 无法无补丁表达 surface/body ≠ acting identity → 冻结 Model B（ADR-028）。

## 实体状态

CANDIDATE / PROVISIONAL / CONFIRMED（§11）——Merge/Split 不是 entity 状态，用 cluster membership + lineage 表达。
