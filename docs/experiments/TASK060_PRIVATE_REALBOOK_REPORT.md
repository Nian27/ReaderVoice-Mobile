# TASK060_PRIVATE_REALBOOK_REPORT.md — 真实书集成（TASK-060 §50-§52/G19）

**日期：2026-08-12** | PRIVATE_REALBOOK_001 = 《娱乐：从1990年开始》

## 本轮覆盖

- **结构/持久化层**（TASK-040 已跑通）：463 章 / 35,969 段 / persist 107.8MB
- **Character candidate**（TASK-050 统计）：13,793 引号对白 / 9,526 cue / 1,528 关系称谓 / 15 临时换声触发——**candidate 非 ground truth**
- **G19 集成**：synthetic 观察流经 CharacterCompiler 全链路（MENTION→entity→evidence→merge→CHARACTER head）在数据层验证

## 明确不宣称（§52）

程序不崩 ≠ Character Accuracy；Character Gold 未建立（§53 仅定义格式，大规模标注留后续）。

## 后续

真实书 CharacterObservation 流（由上游规则/Exporter 产生）→ CharacterCompiler 的端到端 private integration，在 TASK-070（Rule-only baseline）时进行。
