# TASK010_LARGE_FILE_REPORT.md — 3M+ 大文件与存储策略实验报告

**日期：2026-08-12** | 硬件：本机（Java 21 / 无特殊配置）| 数据：synthetic 自制文本（无私人素材）

## 1. 3M+ 字符导入（G6）

synthetic：3,300,040 中文字符（每行约 28 字，U+3000 缩进 + 句末标点，LF 行尾）。

| 指标 | 值 |
|---|---|
| file bytes | 9,667,176（~9.7MB UTF-8） |
| characters | 3,300,040（>3M ✓） |
| physical lines | 116,472 |
| encoding | UTF-8（strict-utf8，conf 1.0） |
| import wall time | **364 ms**（hash + decode + index + 原子提交全流程） |
| peak heap used | **269 MB**（JVM 堆内） |
| 平均行字符 | 28.3 |

结论：**单遍流式扫描（不构造 N 份全文 String copy）+ 手工解码器**在 3M 字符规模无 OOM，峰值堆 269MB 远低于 8GB 目标设备预算；116,472 行索引 364ms 满足"数秒级进入可播放准备状态"。

## 2. Raw-text 存储策略 benchmark（M5，SQLite 实测）

输入：同一 116,472 行 / 9.7MB 文本。
- Option A：`raw_text` 全存 DB
- Option B：只存 offsets（byte/char 区间），文本 RandomAccessFile lazy read + 按需解码

| 指标 | A（全存） | B（lazy read） | 结论 |
|---|---|---|---|
| DB size | 12.29 MB | **2.70 MB** | B 小 78% |
| import | 229 ms | **99 ms** | B 快 57% |
| query 1000 行（随机） | 60.0 ms | 61.3 ms | 持平（+2% 可忽略） |
| 内容一致性 | sumLen=28000 | sumLen=28000 | A/B 解码文本一致 ✓ |

### 决策（ADR-014）

**Option B（offsets + lazy read）冻结为 TASK-040 Room 落地策略**：
- 3M 字书：DB 2.7MB vs 12.3MB；百书规模差距 ×100（~270MB vs 1.2GB）；
- 查询代价可忽略（+2%），且可加短行内存缓存进一步优化；
- 符合"不可变源 + 派生视图"架构（raw 永不改动，任何行可随时还原）。

## 3. 存储估算（百书）

```text
Option B:  100 本书 × 2.7MB ≈ 270MB DB + 源文件 ~1GB（不可变副本）→ 8GB 设备可行
Option A:  100 本书 × 12.3MB ≈ 1.23GB DB（源文件另计）→ 风险
```

## 4. 复现

```bash
./gradlew :parser:test --tests "com.readervoice.parser.source.LargeFileTest" --rerun-tasks
./gradlew :parser:test --tests "com.readervoice.parser.source.RawTextStorageBenchmark" --rerun-tasks
```
