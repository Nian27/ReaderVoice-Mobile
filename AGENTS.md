# ReaderVoice-Mobile 根 AGENTS.md

## Mission

完全端侧、有状态、可增量编译、可局部修正、可版本迁移的多角色有声书编译系统：
真实 TXT 结构恢复 + 规则/数据库降低问题复杂度 + 专项 ReaderDirector 处理语义残差 + CosyVoice3-MNN 工程化 + 可抢占/可修正调度。

**总源文档：`ReaderVoice-Mobile_v5.0_Master_完整执行手册.md`（v5 Master Manual）。本文件只放不变量与纪律，细节在手册与模块 AGENTS。**

## Read order（每个 Agent 开工前必须执行）

```text
1 /AGENTS.md
2 docs/PROJECT_STATE.md
3 docs/DECISIONS.md
4 最近模块的 AGENTS.md（parser/ core-character/ training/ tts-cosyvoice/ app-android/）
5 相关 ExecPlan（docs/agent/plans/）
6 实际代码/测试
```

不依赖工具自动发现嵌套文件；命令/路径使用前必须验证真实存在。

## Core invariants（不可破坏）

```text
1.  Raw source immutable —— 原文永不修改，只建立 source map
2.  PhysicalLine != LogicalParagraph（\n 是证据，不是段落）
3.  Mention != Identity != Embodiment != VoiceState
4.  Character != VoicePack（只能通过绑定关联）
5.  UNKNOWN 是合法结果，禁止强造角色名
6.  False Merge > False Split（merge 需语义 gate + damage gate）
7.  Performance causality —— 表演状态只用当前时间点之前的证据
8.  User Locked 最高 —— 用户修正即时生效、不被模型覆盖
9.  Playback P0 —— 当前播放窗口永远优先于未来生成
10. Native cooperative cancel —— 禁止杀线程
11. 最终 PCM Gate —— "能跑"不等于"通过"（finite/内容/音色/听感/RTF 全过）
12. 多书按需 —— 未激活书不运行 LLM/TTS
13. 版本化 Voice —— AudioAsset 必须绑定 voice_revision_id
14. 训练数据按书 split，严禁同书切 train/test；严禁用 Test Set 调参
15. 无自动远程上传 —— 小说/角色图谱/Voice/分析状态默认全本地（ADR-012）
```

## Planning rules

- 必须写 ExecPlan：DB 迁移 / Parser 重构 / 训练蒸馏协议变更 / Native backend 变更 / Scheduler-cancel / 跨 2+ 核心模块 / 冻结 Gate 变化。
- 路径 `docs/agent/plans/PLAN-YYYYMMDD-xxx.md`，格式见 `docs/agent/TASK_TEMPLATE.md`（15 字段）。
- ExecPlan 是 living document：每完成一个 milestone 立即更新 Progress，禁止最后才补。
- 长任务清单见 `docs/agent/PLANS.md`；当前正式任务见 `docs/PROJECT_STATE.md`。

## Change discipline

- 一次实验只改一个主要变量；固定数据 split 与 seed。
- 禁止 `git reset --hard` 清理问题；禁止删除 research/runs/模型证据/用户数据。
- 修改核心架构时同步检查相关 AGENTS 是否过期。
- 文档冲突优先级：实际代码/测试 > PROJECT_STATE > DECISIONS > Master Manual > ExecPlan > 旧版本文档 > 聊天过期方案；改结论先更新 DECISIONS/PROJECT_STATE 再修手册。
- 禁止为了过测试降低 Gate；禁止把未知结果写成确定事实。

## Evaluation discipline

- 冻结 Test Book Set；所有 run 落 `runs/<run-id>/`（run.json / metrics.json / command.txt / environment.txt / report.md，训练另加 dataset digest + checkpoint）。
- 训练前先 100-step smoke（记录 VRAM/tokens-per-sec/loss/grad norm）。
- TTS/模型实验：禁止只报速度不报音质；tensor cosine 不能代替任务级指标。
- 失败路线与成功路线同等记录。
- **关键词搜索 = discovery，不是 proof**：冻结事实必须依赖结构化解析或针对性验证。本项目两次实证教训：(a) grep 统计 v90.7 行数得 57,126（误），node JSON.parse 实测 14,058；(b) 首轮安全扫描漏检单引号硬编码 key。扫描必须覆盖单/双引号与供应商特定模式。

## Validation

- 每个任务结束必须给出 Gate 结果（PASS/FAIL + 证据），不能以"能跑"代替"通过"。
- 发现命令/路径过期：先验证 → 更新最近一级 AGENTS，禁止继续传播错误命令。

## Documentation

- 根 AGENTS 保持约 8KB 内，模块 AGENTS 4–6KB；一次性任务细节写 ExecPlan，不塞 AGENTS。
- 新规则必须可执行、可验证，禁止空话。
- 同类 Agent 错误重复出现 ≥2 次 → 评估更新最近一级 AGENTS。
- `AGENTS.override.md` 只用于临时实验覆盖，不作长期规范。

## Secrets / third-party / user data

- 禁止把 secrets 写进仓库；发现凭据按 V907_SECURITY_REVIEW.md 规则处理（不打印完整值、记录类别与位置）。
- `research/third_party/` 第三方资产（含 v90.7 原始 JSON 与展开 JS）：RESEARCH_ONLY、LICENSE_UNKNOWN，不进入公开 Git / APK assets / 模型发布包 / 公开数据集；公开材料只允许自写分析。
- 用户数据（角色、修正、VoicePack 原始音频）不可破坏；VoicePack raw 不被自动缓存 GC 删除。
- **DB 红线（ADR-025/G11）**：核心持久化表禁止 `INSERT OR REPLACE`（会 DELETE+INSERT 触发 rowid 变化与 FK 级联误删）；用 `@Upsert` 或 `INSERT ... ON CONFLICT DO UPDATE`。静态扫描测试保证。

## Definition of Done

一次任务完成必须同时满足：

```text
功能完成 + 相关测试运行 + Gate 结果记录 + 失败明确记录
+ 无用户数据破坏 + 无无关大改 + 文档/状态更新
```

最终回复必须按 `docs/agent/TASK_TEMPLATE.md` 的汇报模板报告（任务/结论/修改/原因/命令/测试/Gate/失败/产物/文档/风险/下一步），禁止只说"已完成"。
