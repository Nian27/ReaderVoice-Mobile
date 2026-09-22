# TASK050_PRIVATE_REALBOOK_REPORT.md — 真实书私有分析（TASK-050 S8）

**日期：2026-08-12** | **PRIVATE_REALBOOK_001 = 《娱乐：从1990年开始》（作者候选：咖啡香草）**

## 统计（candidate，非 ground truth，§115）

| 指标 | 值 |
|---|---|
| source_hash 前缀 | 6150b721cc8015a8 |
| size / 行数 | 2,972,771 B / 36,948 |
| 引号对白候选 | 13,793 |
| 说话 cue 候选 | 9,526 |
| 关系称谓行候选 | 1,528 |
| 临时换声触发候选 | 15 |
| 空行数 | 342 |

## 结论

- 真实书结构化管线（TASK-040 实测）：463 章（313 确认+150 provisional）、35,969 段、persist 107.8MB——**candidate 供 TASK-060/070 数据准备与 Hard Case 提取**
- 关系称谓 1,528 行是 RELATION_AS_ALIAS 类的潜在难例来源；15 处临时换声触发适合 TEMP_VOICE Hard Case
- 广告头（群号/资源声明）被部分识别（SCREEN_TEXT ✓ / BOILERPLATE 模式漏）——记录为真实泛化待改进项
- 抽样人工审计（20-50 条/类）为后续任务；**本报告不构成 Human Gold**（§116）

## 数据保护（§72/§127）

- 真实书 gitignored（版权）；报告只含聚合统计与 ID，不含大段原文
- 私有导出（含 context text）写 research/private/（gitignored）

## 复现

```bash
node tools/v907-exporter/realbook-stats.js   # 输出 TASK050_PRIVATE_REALBOOK_REPORT.json
./gradlew :data-room:test --tests "com.readervoice.data.RealBookTest"  # 结构管线（无书时跳过）
```
