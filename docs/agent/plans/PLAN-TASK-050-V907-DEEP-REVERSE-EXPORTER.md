# PLAN-TASK-050 — v90.7 Deep Reverse Engineering + Legacy Behavior Exporter

**日期：2026-08-12（TASK-040 PASS 后）** | **状态：执行中**

## Goal

把 14,058 行 Legacy JS（mingwuyan_v907）转换成可验证的行为规范 + 状态机规范 + 证据规范 + Hard Case + ReaderVoice 可消费的 Legacy Behavior Dataset。**旧系统行为编译器，不是移植**（§1）。

## Current Verified Facts

- 分析输入：`research/third_party/legado-v907/v90.7.code.redacted.js`（14,058 行，SHA-256 `35c7d6…842e`）；默认不读 `research/private/`（§2/§3，raw 仅 redaction 影响控制流时显式申请）。
- 前置冻结：V907_CAPABILITY_MATRIX（12 域/13 决策）、V907_SECURITY_REVIEW（硬编码 key POTENTIALLY_COMPROMISED）、TASK-040 schema v1、PRIVATE_REALBOOK_001（《娱乐：从1990年开始》）。
- 已知线索（L1）：投票轮询（WAIT_API_RESULT_COUNT）、别名正负图（1.5/1.0/4.0）、共现（50/260/2）、临时换声状态机、固定音色优先级、mergedRecords 备份、跨书隔离部分（bookKey 文件）+ 污染点（characterRecords/dialog_cache/nameToMainNameMap）。

## Non-goals（§5）

- 不复制 v90.7 算法到产品模块；不实现 Character DB/ReaderDirector/训练/智谱 API/远程上传/网络请求/CosyVoice/TTS；第三方 JS 不当训练语料；真实小说不入 Git。
- 允许：定义 future Character/Identity schema **requirements**（不建表，§100/§101）。
- 不设"准确率"指标（§161）；Legacy 输出 = LEGACY_LABEL 非 Gold（§85/§162）。

## 八阶段

```text
S1 静态逆向（AST Symbol/State/CallGraph Inventory，L1/L2）
S2 Offline Legacy Harness（shims + API stub + 网络 kill switch + fixtures，L3）
S3 Behavior Contract（V907_BEHAVIOR_CONTRACT.md，抽象 API 非 Legacy 函数名）
S4 Legacy Behavior Exporter（LegacyBehaviorRecord JSONL + schema + manifest + determinism + DB read-only）
S5 Mapping Matrix（V907_TO_READERVOICE_MAPPING.md + FUTURE_CHARACTER_SCHEMA_REQUIREMENTS.md）
S6 Error Taxonomy（V907_ERROR_TAXONOMY.md + failure/fallback graph）
S7 Capability Regression Set（tests/fixtures/v907/ synthetic）
S8 真实书私有分析（PRIVATE_REALBOOK_001 candidate 报告 + 抽样审计，非 Gold）
```

## Evidence Level（§6）

L1 STRING_DISCOVERY（grep/AST 发现）→ L2 STATIC_CONTROL_FLOW（完整路径）→ L3 OFFLINE_BEHAVIOR_REPRODUCED（Harness）→ L4 REAL_BOOK_OBSERVED。PROJECT_STATE 关键事实 ≥L2；核心迁移规则尽量 L3。

## 四关键问题最终状态（§135-§139）

A Cross-book → **L3**（Harness 两本书动态验证）；B Relationship → ≥L2 目标 L3；C Fixed voice → **L3**；D User correction → 完整审计后结论（PERSISTED/RUNTIME_ONLY/NO_PERSISTED_PATH_FOUND_AFTER_COMPLETE_AUDIT 三选一）。

## Scope

```text
tools/v907-analyzer/   AST + inventory（Node + acorn，仅 parse 不 execute）
tools/v907-harness/    shims/stubs/fixtures/traces（网络阻断）
tools/v907-exporter/   LegacyBehaviorRecord JSONL（read-only DB）
docs/baseline/v907/    01-12 域 + SYMBOL_INDEX/STATE_INVENTORY/CALL_GRAPH/FAILURE_FALLBACK/OPEN_QUESTIONS
docs/protocols/        V907_BEHAVIOR_CONTRACT + LEGACY_BEHAVIOR_EXPORT_SCHEMA + V907_TO_READERVOICE_MAPPING + FUTURE_CHARACTER_SCHEMA_REQUIREMENTS
docs/experiments/      TASK050_STATIC_REVERSE/HARNESS/EXPORTER/PRIVATE_REALBOOK reports
tests/fixtures/v907/   synthetic（自写，非第三方片段）
```

## Milestones

| # | 交付物 | Gate |
|---|---|---|
| M1 | analyzer 工具 + Symbol/State/CallGraph Inventory | G1 |
| M2 | 12 域文档（entry/read/write/fallback/mapping 全） | G2 |
| M3 | Harness + 网络阻断 + fixtures + 四关键问题动态验证 | G3/G4/G5/G6/G7/G8/G9 |
| M4 | Behavior Contract + Exporter + Mapping + Error Taxonomy + Regression Set | G10-G13 |
| M5 | 真实书私有分析 + 四问题最终结论 + 报告 | G14-G17 |
| M6 | 全 Gate + 无 G18 违禁 + commit | G18 |

## Decisions

- 工具链：Node + acorn（仅 parse；Legado 方言 fallback scanner）。
- Exporter：deterministic record_id = hash(book_uid, paragraph_uid, behavior_type, legacy_version, evidence)；metadata export 可 commit，private training export gitignored（§127）。
- Harness：API stub 按 request signature → deterministic response；不含真实 key。
- DB 访问：Exporter 对 ReaderVoice DB 只读（SELECT），不污染 Active revision（§124）。

## Validation

G1-G18（§143-§160）。回归：TASK-010/020/030/040 tests + secret_scan + verify_baselines。

## Rollback

纯分析工具 + 文档 + synthetic fixtures；不触碰产品代码与 DB schema。

## Artifacts

工具 3 组 + 12 域文档 + 4 协议 + 4 报告 + inventory JSON + fixtures + manifest。

## Handoff

- TASK-060（Character/Identity/Embodiment/VoiceState）：输入 = Behavior Contract + Mapping + FUTURE_CHARACTER_SCHEMA_REQUIREMENTS + Error Taxonomy Hard Case 优先级；TASK-060 前需 PRE-TASK060 PERF REVIEW（PERF-040-01/02）。
