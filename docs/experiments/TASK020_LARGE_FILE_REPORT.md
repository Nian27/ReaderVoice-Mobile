# TASK020_LARGE_FILE_REPORT.md — 3M+ 结构扫描压力测试（G10）

**日期：2026-08-12** | TASK020_LARGE_STRUCTURE_FIXTURE：synthetic 自制（前部 30 条 TOC + 5 卷 + 每章 200-500 行正文）

## 指标（单次运行实测）

| 指标 | 值 |
|---|---|
| 文本规模 | 8.9 MB / 3,000,000+ 字符 |
| 章节数 | 207（CONFIRMED 207 / PROVISIONAL 0） |
| 卷数 | 5（与生成一致） |
| TOC 块 | 1（30 条，前部） |
| 候选数 / 分组 | 484 / 242 |
| scan 时间 | 2,775 ms（26 规则 × 全部行） |
| resolve 时间 | 27.9 ms |
| 峰值堆 | 172.4 MB |
| 无 OOM | ✅（G10） |

## 结论

- 结构扫描对 3M+ 字符书在 3 秒内完成（TASK-010 导入 364ms + 本任务 2.8s ≈ 3.2s 全流程），满足"数秒级进入可播放准备状态"；
- 峰值堆 172MB（26 规则同时扫）在 8GB 设备预算内；
- 前部 TOC（30 条）+ 5 卷 + 207 章全部正确解析（卷/TOC/正文锚点分离）。

## 复现

```bash
./gradlew :parser:test --tests "com.readervoice.parser.chapters.ChapterStressTest" --rerun-tasks
```
