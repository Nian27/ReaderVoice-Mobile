# PLAN-20260821-050 — MOBILE-000D：Audio8 0.6B Fast/Bulk Renderer 可行性（四步拆分）

## Goal
Audio8-TTS-Preview-0.6B（官方 ONNX INT4，zero-shot cloning）作为 ReaderVoice Fast/Bulk Renderer 的真机可行性判定：官方 ORT INT4 直跑 Magic8 Pro，采集冷启动/RTF/内存/音质/registration 数据；音质用 VoiceFactory Benchmark 判定；最后冻结 SpeechRouter。

## Current Verified Facts（用户 2026-08-21 核验 + 本会话补充）
- 官方包：HF Audio8/Audio8-TTS-Preview-0.6B-ONNX-INT4（~1.02GB：slow_ar_int4 24L / fast_ar_int4 4L / codec_decoder_fp16 / registration+codec_encoder_fp16（约 415MB）/ tokenizer）；weight-only INT4，activation/KV cache FP16。
- 官方 ONNX runtime（GitHub Audio8-AI/Audio8_TTS/onnx_runtime）：CPU，独立于 PyTorch；支持 zero-shot registration、streaming PCM、HTTP/CLI；在线 session 内存约 1GiB；建议单段 ≤150 字；11 语言（含 zh/yue/en）。
- zero-shot 接口 = reference_audio + reference_text（需匹配）；**CosyVoice VoiceProfile ≠ Audio8 VoiceProfile**（engine-specific assets 不共享）。
- 架构决策（用户冻结）：CharacterVoiceIdentity（canonical reference wav/text）共享；CosyVoiceAdapter（tokens/cond/spks）与 Audio8Adapter（registration state/codes）为引擎私有资产。同一"药老"可在两引擎间切换。
- 优先级（用户调整）：P0 = 000B Step3 等价性 + 000C；P0.5 = 000D.0/.1（**现在就开**）；P1 = App Shell + VoiceDesign 手机化；Director 5→20 tok/s 排在等价性之后（先正确→稳定→再快）。
- RenderUnit 架构契合：单 RenderUnit → 单 audio asset → Timeline 拼接，绕开 Audio8 长文本短板；无需长篇连续生成。

## Non-goals
- **不做 Audio8→MNN 先行**（ORT Android 是最低风险路径，先拿真机数据再决定）。
- 不做模型微调/量化改动；不提前规定 SpeechRouter 路线（测完再定）。
- 000D.2 之前不做 CosyVoice 侧改动。

## Invariants
- Voice identity 共享、engine assets 隔离（本计划核心冻结项）；AGENTS 13（AudioAsset 绑定 voice_revision_id）；听感/指标 Gate 不全凭速度。

## Scope
- runs/voice_factory 下新 runs：audio8_feasibility/；PC venv audio8（onnxruntime CPU）；Android 探针扩展（ORT AAR）或新 App；docs/agent/plans 本文档；VOICE_PROFILE_SCHEMA 增补（Audio8Adapter 资产类型，000D.3 冻结）。

## Baseline
- Audio8 未测过任何指标；CosyVoice3-MNN 基线 RTF 0.79-0.96 / 内存 ~947MB（BKQ-AN90）。

## Milestones
- M0：环境与数据就绪——官方 ONNX 包 + onnx_runtime 脚本 + audio8 venv + 测试集（普通中文/药老 c3/年轻女声/西幻英文 + 100 句池）。
- M1（000D.0）：PC 官方 ONNX baseline：5 组测试，记录 CER / speaker similarity（CAM++ 已有管线）/ RTF / cold load / RAM。Gate：全部跑通 + 指标记录完整。
- M2（000D.1）：Android ORT Spike：ORT Android AAR + 官方 INT4 推 Magic8 Pro；cold（process start→first PCM）/ warm RTF / peak PSS / 100 句 thermal / registration 复用。Gate：RTF<1 且内存<1.5GB → ORT 路线可行（否则 MNN 化候选）。
- M3（000D.2）：ReaderVoice Quality A/B：Audio8 vs CosyVoice3-MNN，同 RenderUnit/VoiceIdentity/text；narrator/T1/T2/超短句/20-150字/英文西幻/中文 hard text；输出"Audio8 可安全替代的 RenderUnit 集合"。
- M4（000D.3）：SpeechRouter 冻结：Narrator/T1/T2/Bulk pregen/Low battery/Low-end → 引擎分配表 + Audio8Adapter 资产格式 + VOICE_PROFILE_SCHEMA 增补。

## Progress
- M0 ✅ 2026-08-21：官方 ONNX INT4 包、Audio8 仓库脚本、独立 onnx_runtime venv、3 个 voice registration 已就绪。
- M1 ⚠️ 2026-08-21：PC smoke 已生成 10 个 WAV；cold load 4.58s，单句 RTF 1.714–1.993。报告原始 RSS 为 0.0，不能作为内存证据。
- M1 memory diagnostic ⚠️ 2026-08-21：默认 arena 10 句 RSS/USS 910.8/899.2MB→2278.5/2273.5MB，avg RTF 1.472；reinit-5 仍 2191.5/2186.5MB；slow-only arena off 仍 2252.2/2247.2MB；全 arena off 稳定 919.0/907.4MB，但 avg RTF 2.210。按 RTF<1 且内存<1.5GB 的 Gate，M1 = FAIL，不能进入 Audio8 替代结论。
- M2 ⏳：Android ORT Spike 尚未开始；2026-08-21 已无线连接 BKQ-AN90（SM8850 / Android 17），但 Audio8 ORT APK、PSS/冷启动/热态数据仍缺失。

## Decisions
- D1：先 ORT 直跑，不做 Audio8→MNN（用户定案）。
- D2：身份层冻结为 CharacterVoiceIdentity 共享 + adapter 资产隔离（用户定案，000D.3 正式化）。
- D3：测试音质用 VoiceFactory Benchmark 口径（CAM++ same/cross + CER），不只看官方 Seed-TTS 表。
- D4：Android 探针复用 mnn-llm-probe 工程模式（无 androidx 纯 Activity + JNI/ORT Java API + result 文件输出）。

## Experiments
| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | PC 普通中文/多角色/西幻英文 | CosyVoice3 PC 基线 | M1 | — |
| E2 | Android cold/warm/thermal/registration | CosyVoice3-MNN 真机基线 | M2 | — |
| E3 | A/B 质量（RenderUnit 级） | CosyVoice3-MNN | M3 | — |
| E4 | ORT allocator 模式（默认 / reinit-5 / slow-only off / all off） | 默认 arena | M1 memory diagnostic | all off 控内存但 RTF 2.210；其余仍约 2.2GB |

## Validation
- M1：5 组全跑通，CER/sim/RTF/cold/RAM 记录入 report；M2：RTF<1 + PSS<1.5GB 为 ORT 可行 Gate；M3：每 RenderUnit 类别给出替代判定（安全/有条件/不可替代）；M4：Router 表冻结 + schema 更新。

## Rollback
- venv/包删除即回退；Android 探针独立于产品 App；无模型/数据破坏。

## Artifacts
- runs/audio8_feasibility/（模型包引用 + 结果 + report.md）；audio8 venv；探针扩展（onnxruntime-android）。

## Open Issues
- ORT Android AAR 版本与官方模型算子兼容性；registration encoder 415MB 的加载策略（注册后释放，官方 runtime 同款设计）；中文方言覆盖度（官方 Preview 声明）。

## Handoff
- 000D.3 输出 → MOBILE-000C（Router 决定 Renderer 选择）+ VOICE_PROFILE_SCHEMA v1.1；同时并行 000B Step3 等价性 harness 与 000C M2。
