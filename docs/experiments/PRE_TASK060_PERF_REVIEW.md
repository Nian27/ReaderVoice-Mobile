# PRE_TASK060_PERF_REVIEW.md — TASK-060 前内存重审（S0-S6 分阶段测量）

**日期：2026-08-12** | 方法：3.3M synthetic 分阶段构建 + 强制 GC 后 heap snapshot（无 JFR 环境的等价方案，§PRE-060）

## 分阶段结果

| 阶段 | heap | 说明 |
|---|---|---|
| S0 DB reopen 无书 | 5 MB | 空库基线 |
| S1 PhysicalLine | 43 MB | 183,796 行 |
| S2 Structure | 45 MB | 3,038 候选 |
| S3 Paragraph recovery | **124 MB** | 60,759 段（编译器峰值） |
| S4 persistence 完成 | 115 MB | 编译器引用仍在；DB 327.6MB |
| **S5 置空+GC** | **6 MB** | **编译器集合释放 109MB** |
| S6 reopen+随机访问 | 6 MB | **persistence/query 基线 ≈6MB** |

## 结论（PERF060-P1 达成）

1. **1.59GB 不是产品架构问题**：旧测量（PersistenceStressTest 同一方法内 text+bytes+lines+struct+para+persist 全驻留 + 未前置 GC）的瞬时 used 叠加。分阶段证明：**编译器生命周期峰值仅 ~124MB**，置空可达引用后 GC 回收 109MB，**持久化+查询基线 ~6MB**。
2. **DB 327.6MB 是存储问题（PERF-040-01，OPEN 不变）**——非 heap；TASK-060 schema v2 时按 PERF060-P4 做 Character 存储规模估算。
3. 编译器驻留 = PhysicalLine[] + Candidate[] + Paragraph[] 等（S4-S5 delta 109MB）——**属 compiler lifetime 问题，接口层可控制**（PERF060-P2/P3：CharacterCompiler 流式契约）。

## Gate 判定

| Gate | 状态 |
|---|---|
| PERF060-P1 dominant owners identified | ✅ 编译器集合 ~109MB 可释放；persistence 基线 6MB；DB 327MB 存储问题 |
| PERF060-P2 无 whole-book 全驻留 API | ✅ TASK-060 CharacterCompiler 接口定为 `Sequence<CharacterObservation>` 流式（不变量写入代码注释与 ADR） |
| PERF060-P3 streaming contract | ✅ chapter/window → batch persist → release（TASK-060 实现） |
| PERF060-P4 Character evidence 规模估算（schema v2 前） | ⏳ TASK-060 内完成（100k mentions/300k edges/10k entities stress） |
| PERF060-P5 Android memory gate | ⏳ OPEN（无 Android 环境） |

## 复现

```bash
./gradlew :data-room:test --tests "com.readervoice.data.PreTask060PerfReviewTest" --rerun-tasks
```
