# TASK060_CHARACTER_REPORT.md — 角色系统报告（TASK-060）

**日期：2026-08-12** | 实现：CharacterStore / CharacterCompiler / LegacyBehaviorAdapter / SchemaMigrationV2 + schema.v2.sql（10 表）。

## Identity Model（ADR-028）

Model B 冻结（Mention→Entity→Identity(cluster)→Embodiment→VoiceState）；7 case 消融淘汰 Model A（无法无补丁表达 surface/body ≠ acting identity）。

## v90.7 Hard Regression Pack（ReaderVoice target behavior）

| 测试 | Gate | 结果 |
|---|---|---|
| G2/G65 schema v1→v2 migration 保留 v1 数据 | G2 | ✅ 1 book/1 source/1 paragraph 100% 保留 + 10 v2 表 |
| G3 cross-book isolation（DB 约束 + 查询隔离） | G3 | ✅ 书 2 看不到书 1 证据 |
| G4 hard negative 禁 auto merge | G4 | ✅ ENTITY_NEQ → DIFFERENT_PERSON hard → mergeCandidate false |
| G5 relationship ≠ same person | G5 | ✅ KINSHIP/SOCIAL 非 SAME；仅关系证据不触发 merge |
| G6 group ≠ person | G6 | ✅ GROUP 类型隔离 |
| G7 control/embodiment ≠ identity | G7 | ✅ CHECK 约束 + whoIsActingThrough 正确 |
| G8 UNKNOWN deterministic | G8 | ✅ uid 确定性、provisional 非永久 |
| G9 merge→split 可逆（cluster） | G9 | ✅ split 后独立 cluster、旧实体保留 |
| G10 embodiment interval query | G10 | ✅ start/end 包含语义 |
| G11-G12 temp voice 事件 + Base 不变 | G11/G12 | ✅ START/CONTINUE/REPLACE/END causal 查询正确；Base voice_age 不变 |
| G13 identity future evidence | G13 | ✅ whole-book 反推 |
| G14 performance future leak = 0 | G14 | ✅ Ch2 看不到 Ch10 事件 |
| G17 legacy adapter provenance | G17 | ✅ LEGACY_* 保留；未知类型不映射 |
| G19 synthetic 全链路（compiler+persist+promote） | G19 | ✅ 3 obs → 2 mentions + 1 evidence + 1 merge + CHARACTER head |

## 与 v90.7 硬收益（§49）

无 cross-book pollution（DB 约束）/ merge non-destructive / temp 不改 base / correction persistent+revisioned。

## 指标（§54）

状态系统正确性指标全部由上述 Gate 覆盖；**不设 Speaker Accuracy**（Character Gold 未建立，§52/§53 仅格式）。
