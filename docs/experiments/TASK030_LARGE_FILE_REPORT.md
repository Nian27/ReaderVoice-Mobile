# TASK030_LARGE_FILE_REPORT.md — 3M+ 段落恢复压力测试（G10）

**日期：2026-08-12** | synthetic：80% 40 字断行 + 20% 正常段落（§68）

## 指标（单次实测）

| 指标 | 值 |
|---|---|
| 文本规模 | 8.67 MB / 166,009 物理行 |
| 段落数 | 59,289 |
| profile 时间 | 39,574 ms |
| boundary 时间 | 370 ms |
| build 时间 | 980 ms |
| 总时间 | 40,924 ms |
| 峰值堆 | 154.4 MB |
| 平均行/段 | 1.8 |

## 结论

- 无 OOM（G10），峰值堆 154MB 远低于 8GB 目标设备预算；
- **profile 是热点（39.6s）**：regions() 滑动窗口重复统计所致——一次性导入路径可接受（非实时），TASK-040 后优化（增量窗口统计/抽样）；
- boundary+build 仅 1.35s（166k 行）——逐边界路径高效；
- 段落语义：hard-wrap 段（2 行）+ 正常段（1 行）混合正确恢复。

## 存储 benchmark（§71，ADR-019）

| 指标 | A：全存 normalized | B：spans+transforms |
|---|---|---|
| DB size | 2.87 MB | **1.51 MB** |
| import | 189 ms | **83 ms** |
| query 1000 段 | 75.9 ms | **64.8 ms** |

**决策：Option B**（spans+transforms，需要时重建）；全文搜索影响留 TASK-040 FTS 评估。

## 复现

```bash
./gradlew :parser:test --tests "com.readervoice.parser.paragraph.ParagraphStressTest" --rerun-tasks
./gradlew :parser:test --tests "com.readervoice.parser.paragraph.ParagraphStorageBenchmarkTest" --rerun-tasks
```
