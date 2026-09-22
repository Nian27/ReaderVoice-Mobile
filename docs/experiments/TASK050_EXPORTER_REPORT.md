# TASK050_EXPORTER_REPORT.md — Legacy Behavior Exporter 报告（TASK-050 S4）

**日期：2026-08-12** | 工具：`tools/v907-exporter/`

## Export Schema（G11）

`LEGACY_BEHAVIOR_EXPORT_SCHEMA` 见 export.js 内 makeRecord：record_id（确定性 sha256）/ book_uid_hash / source_revision_hash / chapter_index / paragraph_uid / narrative_position / behavior_type / input_refs / legacy_output / legacy_state_before/after / evidence / provenance / confidence_level / quality / legacy_version / extractor_version / schema_version / context_text（仅 private）。

- **quality 恒为 LEGACY_LABEL**（§85/§162：仅 HUMAN_AUDITED 才可能 Gold）
- **record_id 确定性**（§126）：hash(book_uid|paragraph_uid|behavior_type|legacy_version|evidence)——G12 验证两次导出行级一致
- 非法 behavior_type 丢弃（§83：不创造字段）
- Metadata export 不保留正文（§127）；private export 写 research/private/（gitignored）

## 测试（node --test，3/3 PASS）

| 测试 | Gate | 结果 |
|---|---|---|
| G11 schema 完整 | G11 | ✅ 必填字段全、LEGACY_LABEL、确定性 hash |
| G12 determinism | G12 | ✅ 两次导出 record 行完全一致（除 timestamp） |
| G13 DB 只读 | G13 | ✅ readOnly 打开语义验证 |

## 数据链路（§C）

```text
v90.7 → Harness trace → LegacyBehaviorRecord（JSONL）→ Human/Rule/Teacher Audit → Gold/Silver/Hard → ReaderDirector Dataset（TASK-080）
绝不：v90.7 输出 → 直接 LoRA（§C 红线）
```

## 统计（§132 部分）

- 12 域映射：KEEP_SEMANTICS 4 / REDESIGN 10 / DROP_IMPLEMENTATION 2 / DROP 2
- 4 关键问题：A CONFIRMED_L3 / B L2 / C CONFIRMED_L3 / D RUNTIME_ONLY_CORRECTION_FOUND
- 函数 345 / state 927 / 12 域文档 / Harness 7 测试 / Exporter 3 测试 / synthetic fixtures 12 / 真实书 1 本

## Open Questions（§133）

汇总于 `docs/baseline/v907/OPEN_QUESTIONS.md`（12 域文档尾部各条 + 本报告）。
