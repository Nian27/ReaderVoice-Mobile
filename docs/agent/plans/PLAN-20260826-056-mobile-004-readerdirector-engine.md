# PLAN-20260826-056 — MOBILE-004：ReaderDirector QwenEngine 稳定服务 + 结构化输出

## Goal

把 Qwen3.5-0.8B 从"单次 gate 探针"升级为 App 内**有状态、可取消、可审计**的 QwenEngine 稳定 service，并输出**结构化 Director 结果**（而非自由文本），使上层（ReaderDirector → RenderUnit）与 backend（CPU/OpenCL/Hexagon）解耦。

## Current Verified Facts

- CPU Gate 已 PASS（2026-08-22）：423MB ReaderDirector int4 在 BKQ-AN90 真机 common runtime `load=OK` + VS8 协议输出正确（`runs/mobile_003_director_runtime/cpu_gate_pass.md`）。
- 更新进展（2026-08-26 MEMORY）：模型切换到 `readerdirector-mnn-v2`（tie_embeddings 自包含），CPU 输出正确中文（prefill 274 tok/s, decode 64.77 tok/s）。设备路径 `/data/local/tmp/MNN/model_rd/`。
- 现 JNI `app-android/src/main/cpp/director_gate_jni.cpp` 只做单次 `runDirectorGate`（一次性 load+response+report），无状态复用、无 reset/cancel、无结构化输出。
- `llm.hpp` API 已具备：`Llm::createLLM/load/reset/response/generate/stoped/tokenizer_encode/decode/getContext/dump_config/set_config`。
- App 侧已有 `SpeechRuntimeContract.kt` 与 `ReaderVoiceCosyRuntime.kt`（CosyVoice adapter），但**无 QwenEngine/Director adapter**。
- RenderUnit v1 schema 已冻结（`docs/protocols/RENDERUNIT_V1_SCHEMA.md`）。
- Hexagon HTP 支线状态（不阻塞本任务）：Branch A 成立（lm_head 输入 cos=-0.0067），问题在 L23/final-hidden 链，非 lm_head 算子。HTP 修复后仅替换 backend，不改上层架构。

## Non-goals

- 不修 Hexagon HTP 正确性（并行支线，单独任务）。
- 不接入 CosyVoice/Audio8 合成链路。
- 不做全书批处理、Scheduler、缓存持久化。
- 不把 LLM 自由文本当 RenderUnit。

## Invariants

- 推理不在 UI 线程（根 AGENTS 不变量 10 Native cooperative cancel）。
- 禁止杀线程（cancel = 协作式 stop 标记，非 kill）。
- 原文不变；UNKNOWN 合法；用户锁不被模型覆盖。
- backend 可插拔：CPU 默认 correctness path；Hexagon=Experimental，上层不感知。
- 一次实验一个变量；不以"能跑"代替"通过"。

## Scope

- `app-android/src/main/cpp/`：新增 `qwen_engine_jni.cpp`（有状态 native service）。
- `app-android/src/main/java/com/readervoice/app/`：新增 `QwenEngine.kt`、`ReaderDirectorSchema.kt`、`ReaderDirectorEngine.kt`。
- `docs/PROJECT_STATE.md`、`runs/mobile_004_director_engine/`。

## Baseline

- 现 JNI 单次 gate：load 617ms（含 load），decode ~7 token，VS8 输出正确但无状态、无结构化、无 cancel。

## Milestones

- M1：QwenEngine native service（load/reset/generate/cancel/release/getBackend/getMetrics）编译通过 + host JVM 单元测试。
- M2：真机 M1 Gate——CPU backend 固定 fixture 结构化输出 + evidence_span 校验 + cancel/reset 语义，结果落盘 files/qwen_gate_result.txt。
- M3：backend 选择层（AUTO/CPU/NPU-Experimental）+ hexagonCorrectnessPassed=false 固定。
- M4：ReaderDirector 语义适配（DirectorContext → prompt → 结构化解析 → RenderUnitSynthesisInput 候选），host 校验器单测。
- M5：真机单段 Gate，报告 + PROJECT_STATE 更新。

## Progress

- 已确认方向（2026-08-26）：App 主线 + HTP 支线并行。
- M1 ✅ 2026-08-26：QwenEngine 有状态 native service + Kotlin 封装 + ReaderDirectorSchema host 校验器 + ReaderDirectorEngine 适配层；host JVM 单测 5/5 绿；assembleDebug 成功，两个 JNI so 16KB ELF 对齐全过。
- M2 ✅ 2026-08-26：真机 Gate PASS。APK 装到 BKQ-AN90；JNI smoke（native_create_ok + load=FAIL 无模型）PASS；部署 423MB 四件套到 App internal 目录（hash 全核验）；完整闭环 load(744ms) → generate A(190ms, V=NONE;E=null) → parse PASS → reset → generate B → release，无 crash。排障：模型部署位置错配（run-as 写 internal vs Activity 读 external）已修复。证据：runs/mobile_004_director_engine/m2_gate_pass.md。
- M3 ✅ 2026-08-26：backend 选择层落地 `QwenBackendPolicy`（AUTO/CPU/NPU_EXPERIMENTAL）。硬不变量：`HEXAGON_CORRECTNESS_PASSED=false` 固定，AUTO 永远回落 CPU；NPU_EXPERIMENTAL 被标记 `experimental=true`，`isTrustworthyForCommit()` 拒绝对实验 backend 放行；未知 native 名 fail-closed。`QwenBackendMode`/`QwenBackend`/`QwenBackendSelection` 与 JNI 字符串契约对齐（cpu/opencl/hexagon）。host 单测 6/6 绿。
- M4 ✅（核心）2026-08-26：`RenderInstructionCompiler`（ric-v1，确定性映射表，未知取值不静默丢弃、tone 做注入防护）+ `RenderUnitBuilder`（DirectorResult → RenderUnit v1 候选 + `RenderUnitSynthesisInput`，冻结 cacheKey 公式 sha256(model|profile_hash|mode|inst_hash|speed|sample_rate|text_sha)）+ `RenderUnitValidator`（V1–V10 fail-closed）。`ReaderDirectorEngine.analyzeToRenderUnit()` 提供 DirectorContext→Prompt→解析→候选 的整条 seam，校验违规即 Failed 并带回 prompt/raw。host 单测 18/18 绿（Compiler 8 + Builder 10）。**未做**：VoiceStateReducer（R5 状态只维护一次）与 RenderUnit→CosyVoice 真机合成，留待 M5 之后。

## Decisions

- D1：QwenEngine 采用有状态 native service（复用 MNN Llm 实例）。
- D2：cancel = 协作式（stop 标记 + generate 循环检查）。
- D3：backend 默认 CPU；Hexagon 标记 Experimental。

## Validation

- Host：JVM 单测。
- Device：真机 M2/M5 Gate，结果落盘 files/ 目录。

## Rollback

- 新增 service/adapter 独立文件，不动现有 DirectorM1GateActivity。

## Artifacts

- `app-android/src/main/cpp/qwen_engine_jni.cpp`
- `app-android/src/main/java/com/readervoice/app/QwenEngine.kt`
- `app-android/src/main/java/com/readervoice/app/ReaderDirectorSchema.kt`
- `app-android/src/main/java/com/readervoice/app/ReaderDirectorEngine.kt`
- `app-android/src/main/java/com/readervoice/app/QwenBackendPolicy.kt`（M3）
- `app-android/src/main/java/com/readervoice/app/RenderInstructionCompiler.kt`（M4）
- `app-android/src/main/java/com/readervoice/app/RenderUnitBuilder.kt`（M4，含 RenderUnitValidator）
- `app-android/src/test/java/com/readervoice/app/QwenBackendPolicyTest.kt`（6）
- `app-android/src/test/java/com/readervoice/app/RenderInstructionCompilerTest.kt`（8）
- `app-android/src/test/java/com/readervoice/app/RenderUnitBuilderTest.kt`（10）
- `runs/mobile_004_director_engine/`

## Open Issues

- 423MB int4 模型在 App 内如何安装/校验。
- Hexagon HTP G1 PASS 后如何切换 backend。
- ReaderDirector 结构化输出 schema 与 NARRATION_IR_SCHEMA 对齐。

## Handoff

- M1 完成后先做 M2 真机 Gate。
- HTP 支线 G1 PASS 后仅替换 backend，不改上层。

## M6（HTP 收口）Progress — 2026-09-16：App HTP Gate G_APP1–G_APP5

> 本轮把 P16-FIX（RoPE）+ P16-3（D2H repack）之后的 native 栈真正接进 QwenEngine 并过 App Gate。
> 全文：`runs/mobile_004_director_engine/app_htp_gate_20260916.md`；shell golden：同目录 `golden_shell_htp_0_23.md`。

- **根因级发现（解释 M2 的 cos=-0.0067）**：app 原 `libMNN_htpops_skel.so`（1.94 MB）与它自己的
  `libMNN.so` **不是同一 ABI 产物**。M2 时把"lm_head 输入 cos=-0.0067"归到 L23/final-hidden 链，
  实际是 host/skel 不匹配。本轮换成同一构建树的匹配对（16KB 对齐）。
- 修改：jniLibs（libMNN `02f43bad…`/libllm `30302400…`/skel `4e38fd00…`/Express `19F6BAD8…`）；
  JNI 环境（`MNN_HEX_LAYER_HTP` 12:23→**0:23**、`LA_FORCE`→0、显式 `LA_OFF=1` 钉住 LA 在 CPU）；
  显式实验开关 `readerdirector.backend=cpu|hexagon`（默认 cpu，经 QwenBackendPolicy）；
  gate 通道 `rawprompt/maxtokens/autorun`；`M23_LOGITS_DUMP_DIR`；APK `keepDebugSymbols` 保 skel 原字节；
  去掉 `HexagonExecution::onExecute` 的逐 op 日志（改 `MNN_HEX_TRACE=1`）。
- **Gate**：G_APP1 PASS（load ok/无 crash；LMK 只杀其它 App）｜
  **G_APP2 PASS 逐字节**（App HTP logits MD5 = shell HTP `233FEE53…`；App CPU = shell CPU `5C242819…`）｜
  G_APP3 PASS（药老 fixture 与 golden 逐字一致）｜
  G_APP4 PASS（App prefill HTP 1.15~1.32 s vs CPU 1.53~1.81 s，三轮交替全胜）｜
  **G_APP5 FAIL**（真实书连续 5 段：格式 5/5 合格，语义 ≥3/5 错 —— speaker 误判/文本漂移到相邻段/type 误判；
  **CPU 对照复现同型错误 ⇒ 模型+prompt 能力问题，不是 Hexagon 回归**）。
- **重要副产物**：对真实 Director 请求 HTP 不是无条件更快 —— decode 占 78%，
  HTP decode 稳定 ~4.3 s 而 CPU decode 最好 1.5 s、争用下退化到 16.7 s。
  HTP 的价值 = prefill 快 + 稳定；下一步应打 **L=1 的提交/同步定长开销**，而不是继续修数值。
- 未变：`QwenBackendPolicy.HEXAGON_CORRECTNESS_PASSED` 仍为 **false**（发布默认 cpu）。
  翻真需要：HTP decode 不再拖累端到端 + G_APP5 语义门通过（或明确只用于 prefill 阶段）。