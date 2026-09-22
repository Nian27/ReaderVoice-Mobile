# TASK_TEMPLATE.md — ExecPlan 模板与最终汇报模板

## 一、ExecPlan 模板（v5 §147）

文件路径：`docs/agent/plans/PLAN-YYYYMMDD-xxx.md`。ExecPlan 是 living document——每完成一个 milestone 立即更新 Progress。

```text
# PLAN-YYYYMMDD-xxx — <任务名>

## Goal
一句话目标。

## Current Verified Facts
本任务依赖的已核验事实（引用路径/行号），禁止未经验证的假设。

## Non-goals
明确不做的事（防止范围蔓延）。

## Invariants
本任务必须遵守的项目不变量（引用根 AGENTS.md）。

## Scope
涉及的文件/模块清单。

## Baseline
当前基线状态（指标或行为），任务完成后与之对比。

## Milestones
M1..Mn：每个 milestone 的交付物与验收标准。

## Progress
M1 ✅ 2026-08-12 …（边做边更新）
M2 ⏳ …

## Decisions
任务内做出的决策（重要者同步进 docs/DECISIONS.md）。

## Experiments
实验表：id / 变量 / 基线 / 结果 / 结论。

## Validation
Gate 定义与验证方法/命令。

## Rollback
回退方案（DB 迁移、文件、行为）。

## Artifacts
产物路径清单。

## Open Issues
未决问题与待核验事实。

## Handoff
给下一个任务的交接信息。
```

## 二、最终汇报模板（v5 Appendix V）

任何复杂任务完成后，Agent 最终回复/报告必须包含：

```text
# 任务
TASK-xxx

## 本轮结论
一句话说明是否过 Gate。

## 修改
- file（+新增/-修改/-删除 或"新增/修改"）

## 原因
为什么这样改。

## 运行命令
完整命令。

## 测试结果
指标与证据。

## Gate
PASS / FAIL（附条件）。

## 失败/异常
明确列出，不隐藏。

## 产物
路径。

## 文档更新
AGENTS / PLAN / STATE / DECISIONS / baseline 等。

## 剩余风险
尚未解决的。

## 下一步
唯一推荐下一正式动作。
```

禁止只回复"已完成"。
