# PLAN-20260918-060 — CH-0 chapter-batched director runtime

> 用户定案：**先不接 TTS**。当前瓶颈是章节/批处理/长任务可靠性，不是有没有声音。
> 架构真相源：Normify `2026-09-18-ch-0-chapter-batched-director-runtime`（in_progress）。

## Goal

把整章导演从「一个大任务一口气跑到底」改成 **三级执行模型** `Chapter → Batch → Segment`：
按段切批 + 滑动上下文 + 提交点 + 断点续跑，并用真实书把**章节边界**钉死。

## Current Verified Facts

| 事实 | 证据 |
|---|---|
| 现在已是**逐 segment** 调模型（不是整章一次） | `ChapterDirectorRunner.run` 逐段：`skipDecision` / `decider.decide(prompt)` |
| 但**调度/持久化仍是整章粒度**：一次性 `subList(from,to)` 跑到底，只有最后写 `stats.txt` | `ChapterDirectorService.runChapter`；真机一次 ≈310s |
| 切后台/系统调度曾导致任务中断（两次 Freezer 事故） | `runs/mobile_005_director_real/{fgs_host,close_gate_attempts}/report.md` |
| 剧本现在**每轮归档**（历史不再被清空），但**没有 checkpoint/续跑** | `scripts/<bookId>_<scope>_<ts>.jsonl`；`ChapterRunState` 缺失 |
| 章节边界是所有下游的地基（段落→片段→角色上下文→批次进度→剧本归档） | 下游全依赖 `Chapter.contentStartLine/contentEndLine` |
| 真实书规则命中已可用但未系统体检 | `legacy/txtTocRule.json` + `chapter_rules.json` + `GlobalStructureResolver/ChapterStructureCompiler` |
| 上下文当前是**窗口**（`ContextBuilder` 的 window）但未按"前 4~8 + 后 1~2"验证 | `ChapterDirectorRunner` 维护 `ArrayDeque(window)` 传入 `ContextBuilder` |
| 已冻结口径不得动：四态 / 15 失败码 / C18 / schemaVersion=2 | `docs/DECISIONS.md` ADR-055；`rvm.directing.*` |

## Non-goals

- ❌ 不接 TTS（Audio8 / CosyVoice / VoiceBinding / RenderUnit 一律不动）
- ❌ 不重写 parser（只加真实书 Gate）
- ❌ 不改 Director 协议与失败码
- ❌ 不做"一次请求判 2~4 个连续对白段"（先保稳定，之后再说）
- ❌ 不引入 LLM KV 跨批复用（每段 reset 保持）

## Invariants

根 `AGENTS.md`：9（Playback P0 的反面：**当前任务窗口优先于未来批**）、10（Native cooperative cancel 禁止杀线程）、15（无自动远程上传）。
契约：C1 原文不可变、C2 物理行≠逻辑段、C9/C10（REJECT 不偷改）、**C17**（已 COMMITTED 不被静默替换 —— batch commit 正是它的落地）、C18（局部 ID 不过边界）。
新增本任务约束：**REJECT ≠ RUN FAILED**；崩溃后重算当前批，**不得产生重复 ScriptLine**。

## Scope

**新增**
```
rvm.directing.batch-planner      BatchPlanner（12 段/batch；Batch = 调度+持久化单位）
rvm.directing.run-checkpoint     ChapterRunState + RunStatus + CheckpointStore（commit/resume）
rvm.tooling.chapter-gate         真实书章节边界 Gate（抽样 + 不变式）
```
**修改**
```
rvm.directing.runner             支持 batching：每批结束回调 onBatchCommitted
rvm.understanding.context        上下文窗口定标：前 4~8 + 当前 + 后 1~2
rvm.orchestration.foreground-service  批次日志 + 退出原因 + checkpoint + 恢复
rvm.orchestration.chapter-run-ui      进度改为"批次/段" + 恢复提示
rvm.runtime.engine-watchdog      退出原因分类（D: watchdog 停闸）
rvm.ingestion.chapter.{compiler,resolver}  章节边界不变式（不重写，只加固/记录）
rvm.tooling.gate-fixtures        Gate 夹具接入
```
**不动（保留）**
```
DirectorDecisionV2 / ProtocolNormalizer / DirectorOutputValidator / SpeakerRef / ScriptLineBuilder
parser 的章节规则引擎（只做 Gate 验证）
```

## Baseline

| 指标 | 当前值 | 证据 |
|---|---|---|
| 单次整章（140 段）耗时 | ≈308/318/329 s（3 次） | `close_gate/run_{1,2,3}/stats.json` |
| 中断代价 | **整章重跑**（无 checkpoint） | 无 `ChapterRunState` |
| 中断可观测性 | 只有 logcat（该机型还被加密）+ 通知 | `dumpsys`/通知文本 |
| 章节边界体检 | **未做** | — |

**目标**：中断最多损失 **1 个 batch**（12 段）；恢复后显示「已完成 84/140，从 S84 继续」；章节边界 4 条不变式在真实书 0 违规。

## Milestones

**M0（P0）章节边界真实书 Gate**
- 交付：`ChapterGate.sample` + `assertInvariants`；选一本真实书抽第 1/50/100/249 章
- 验收：不重叠 / 无空洞 / 顺序单调 / anchorByte 单调 = 0 违规；输出 7 个字段（title、anchorByteStart、contentStartLine、contentEndLine、首100字、尾100字、下一章首100字）
- 若发现违规：只**记录并定位规则**，不重写 parser（改规则或加覆盖）

**M1（P1）BatchPlanner + 提交点**
- 交付：`BatchPlanner.plan(segments, batchSize=12)`；runner 支持 `onBatchCommitted(batch, lines)`
- 验收：每批结束**原子追加** + checkpoint；人为杀进程后重进：当前批不算 committed 且**无重复行**

**M2（P2）ChapterRunState + 退出原因**
- 交付：`ChapterRunState`/`RunStatus`/`CheckpointStore`；`BATCH_START`/`BATCH_COMMIT` 日志；退出原因 A–F
- 验收：`status=RUNNING` 但 service 不存在 ⇒ UI 显示「上次异常中断，已完成 N/M，从 S_N 继续」并可续跑

**M3（P3）上下文窗口定标**
- 交付：前 4~8 + 当前 + 后 1~2；跨批只带摘要（RecentSpeakerHistory/ActiveCharacterIds/LastN/UserLocks/SceneHints）
- 验收：prompt 长度与 3 次运行的**决策一致性**对比（同章 3 次统计逐项一致仍是硬指标）

**M4（P4，不阻塞）** 剩余 REJECT 分析 + candidate pollution 指标单列

## Progress

```
M0 ⏳ 未开始（下一步第一件事）
M1 ⏳ 未开始
M2 ⏳ 未开始
M3 ⏳ 未开始
M4 ⏳ 未开始
前置：MOBILE-005 已 CLOSED（C18 + 真机 3 次 + 取消轮全绿）
```

## Decisions

| # | 决策 | 理由 |
|---|---|---|
| D1 | 三级模型：`Chapter → Batch → Segment`；**Batch = 调度/持久化单位，Segment = 决策单位** | 稳定性来自"有界工作单元 + 提交点"，不是把多个段塞给模型 |
| D2 | 首版 `batchSize = 12` segment（约 12 批/140 段） | 足够小以限制损失，足够大以免提交过频 |
| D3 | **批完成即提交点**：原子 append + checkpoint；crash 时当前批重算 | 避免"写一半"，也避免重复 ScriptLine（呼应 ScriptLine 的 PENDING/COMMITTED 思想） |
| D4 | `REJECT` 是 segment outcome，**不等于** `RunStatus.FAILED` | 否则整章会被一个坏段判死 |
| D5 | 上下文只用滑动窗口（前 4~8 + 后 1~2），不塞半章全文 | 慢，且 0.8B 更易被远处人名干扰 |
| D6 | 跨批只保存**摘要状态**，不保存 LLM KV；每段 reset 保持 | 现有行为已验证（3 次逐项一致）；KV 复用会引入不确定 |
| D7 | 退出原因必须可判定（A–F） | "做着做着退出"必须能归因到 Activity/Service/LMK/hang/异常/用户取消 |
| D8 | 不接 TTS | 可用性瓶颈在前段；先让 ReaderDirector 具备"可分析一本书"的工程形态 |

## Experiments

| id | 变量 | 基线 | 预期 | 结论 |
|---|---|---|---|---|
| E1 | batchSize=12 vs 整章 | 中断=整章重跑 | 中断≤12 段 | 待做 |
| E2 | 杀进程后恢复 | 无恢复 | 从 S84 续跑，0 重复行 | 待做 |
| E3 | 上下文窗口（现有 window vs 前4~8+后1~2） | 未定标 | 统计一致性不降 | 待做 |
| E4 | 章节边界不变式 | 未体检 | 真实书 0 违规 | 待做 |

## Validation

```
V1 M0：真实书第 1/50/100/249 章 7 字段 dump + 4 条不变式 0 违规
V2 M1：kill -9 进程后重进 ⇒ 当前批重算、无重复行、已完成行保留
V3 M2：A–F 六种退出原因各构造一次，日志/状态可区分；RUNNING 但无 service ⇒ 显示恢复提示
V4 M3：同章 3 次运行统计逐项一致（沿用 MOBILE-005 的硬指标）
V5 回归：C18 真机 0 局部 ID 泄漏 / 四态与失败码不变 / schemaVersion=2 不变
V6 不回归性能：单章耗时与基线同量级（≈310s）
```

## Rollback

- 批次化是**新增层**（BatchPlanner/Checkpoint），可开关回退为整章一次跑
- checkpoint 文件可删（`files/chapter_director/runstate_*.json`），不影响已有归档
- 不动数据库 schema；不动已有 JSONL schemaVersion

## Artifacts

```
预期：rvm.directing.{batch-planner,run-checkpoint} 实现 + rvm.tooling.chapter-gate
      tools/mobile005/chapter_gate.js（或 JVM 测试）· runs/mobile_005_director_real/ch0_*/{report.md,evidence}
规划（本轮）：Normify change + 本 ExecPlan
```

## Open Issues

1. 恢复时"当前批重算"会不会与已 append 的行冲突？需要**批内先缓冲、批末一次原子 append**（而非逐行）⇒ 与现有"逐行增量落盘"取舍需明确（倾向：逐行写 **归档**，批末写 **committed 段标记**）。
2. `ChapterRunState` 存哪里：JSON 侧车（先）还是 SQLite（后）？先 JSON，避免 DB 迁移。
3. 用户跳章时旧任务是 `PAUSED` 还是 `CANCELED`（用户原话两者都提过）—— 倾向 **PAUSED**（可回来续），显式取消才 `CANCELED`。
4. 章节边界"正文空洞"的判定口径：`contentEndLine` 为 null（最后一章）如何处理。
5. 后文 1~2 个 segment 是否会影响因果性（不变量 7：表演状态只用当前时间点之前证据）—— 后文只用于**说话人线索**，不得作为表演证据。

## Handoff

- 下一步唯一动作：**M0 真实书章节边界 Gate**（P0，所有下游的地基）
- 开工前复读：本文件 D1–D8、Normify change `2026-09-18-ch-0-...` 的 9 条 acceptance
- 禁止：为了"看起来稳定"降低 Gate；为了省事把若干 segment 合成一次模型请求（那是 P4 之后的独立实验）
