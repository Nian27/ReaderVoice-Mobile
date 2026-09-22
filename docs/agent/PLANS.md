# PLANS.md — ReaderVoice-Mobile 任务总览与执行规则

**更新：2026-08-12（TASK-000）** | 长任务规划入口；当前正式任务见 `docs/PROJECT_STATE.md`。

## 任务序列（v5 §179，按序执行，禁止跳级）

| # | 任务 | 状态 |
|---|---|---|
| TASK-000 | Repo/Agent Governance + Baseline Freeze | ✅ PASS 2026-08-12 |
| TASK-000.1 | Baseline Hardening / Secret Quarantine / Git Bootstrap | ✅ PASS 2026-08-12 |
| TASK-010 | Immutable TXT Ingestion（编码/PhysicalLine/SourceMap/导入管线） | ✅ PASS 2026-08-12 |
| TASK-020 | Chapter / TOC / Volume（LegacyChapterRulePack 迁移） | ✅ PASS 2026-08-12 |
| TASK-030 | Paragraph Recovery（Layout Profile → Boundary → LogicalParagraph） | ✅ PASS 2026-08-12 |
| TASK-040 | Room Schema / Revision / Dependency DAG 冻结（JVM SQLite，ROOM GATE OPEN） | ✅ PASS 2026-08-12 |
| TASK-050 | v90.7 Deep Reverse Engineering + Legacy Behavior Exporter | ✅ PASS 2026-08-12 |
| TASK-060 | Revisioned Character Identity & Narrative State System | ✅ PASS 2026-08-12 |
| TASK-070 | SemanticSegment + Speaker + NarrationIR（Rule-only baseline 先行） | 下一任务 |
| TASK-020 | Chapter / TOC / Volume（LegacyChapterRulePack 迁移） | 未开始 |
| TASK-030 | Paragraph Recovery（Layout Profile → Boundary → LogicalParagraph） | 未开始 |
| TASK-040 | DB Schema（Room：Revision / Override / Invalidate，fake data 跑通） | 未开始 |
| TASK-050 | v90.7 深度逆向 / Exporter（LegacyV907Exporter + Error Taxonomy + Hard Case） | 逆向已启动（V907_CAPABILITY_MATRIX） |
| TASK-060 | Character / Identity / Embodiment / VoiceState（Room 实现 + 手工案例 Gate） | 未开始 |
| TASK-070 | ReaderDirector Dataset（Gold/Silver/Hard，book split，provenance） | 未开始 |
| TASK-080 | ReaderDirector Baseline（0.8B few-shot / Post / 2B，冻结 Test Set） | 未开始 |
| TASK-090 | 0.8B LoRA（100-step smoke → formal SFT） | 未开始 |
| TASK-100 | Teacher Distill（4B→9B→human，同 adapter） | 未开始 |
| TASK-110 | MNN Director（HF→MNN PC→Android，逐级任务级 Gate） | 未开始 |
| TASK-120 | CosyVoice Engine 抽取（CV-001~CV-013） | 未开始 |
| TASK-RV-044 | RenderUnit v1 + VoiceProfile + Runtime 冻结（协议三件套） | ✅ FROZEN（2026-08-20, git 12aebbf） |
| TASK-RV-045 | Voice Persona + Profile Factory（Qwen3-TTS VoiceDesign → CosyVoice enrollment） | 进行中（PLAN-20260820-045） |
| TASK-RV-046 | PC 端闭环验证（3 类小说 → RenderUnit → wav；100 句盲听） | 未开始 |
| TASK-RV-047 | Android 集成（RenderUnit Queue → CosyVoice3-MNN，Director/Renderer 模式） | PC 闭环后启动 |
| TASK-130 | RenderPlanner / Timeline / PausePlanner / Loudness | 未开始 |
| TASK-140 | Scheduler / Playback Bootstrap（三 Frontier、epoch、cancel） | 未开始 |
| TASK-150 | Multi-book / Storage（50-100 本，Hot/Warm/Cold，quota） | 未开始 |
| TASK-160 | 8GB 真机验收（飞行模式 60min，25 场景） | 未开始 |
| TASK-170 | Beta APP | 未开始 |

> 注：v5 里程碑 M0-M19 与本表等价（M1=TASK-010…）；本表为执行粒度。

## ExecPlan 规则

- 必写 Plan 的场景（根 AGENTS.md Planning rules）：DB 迁移 / Parser 重构 / 训练蒸馏协议变更 / Native backend / Scheduler-cancel / 跨 2+ 核心模块 / 冻结 Gate 变化。
- 路径：`docs/agent/plans/PLAN-YYYYMMDD-xxx.md`；格式：`docs/agent/TASK_TEMPLATE.md`。
- ExecPlan 是 living document：每完成一个 milestone 更新 Progress，禁止最后才补；完成后归档保留（不删除）。

## 任务边界纪律

- 禁止从 TASK-090 直接开始训练；禁止在 TASK-120 前动 CosyVoice 模型代码。
- 每个任务结束按 TASK_TEMPLATE 汇报模板提交 Gate 结果；失败路线记录进 PROJECT_STATE。
- 任务间依赖：050 依赖 040（Room）；120 依赖 010-040（结构产物）与基线快照；140 依赖 120/130。

## 关键输入资产

- 总源：`ReaderVoice-Mobile_v5.0_Master_完整执行手册.md`
- 基线：`docs/BASELINE_ASSETS.md`（盘点）、`docs/baseline/*`（冻结）、`research/third_party/legado-v907/*`（v90.7 逆向）
- 迁移源：`FUN-legado`（txtTocRule.json、Room 表结构、MEMORY.md）、`CosyVoice3-MNN-formal`（Engine 抽取源）、`mnn-cosyvoice3`（蒸馏脚本/模型包）
