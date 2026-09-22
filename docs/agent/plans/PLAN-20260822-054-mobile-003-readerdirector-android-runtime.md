# PLAN-20260822-054 — MOBILE-003：ReaderDirector Android Runtime

## Goal

定位并恢复冻结 Qwen3.5-0.8B ReaderDirector MNN int4 的 Android CPU `load → generate`，再将其抽取到 ReaderVoice 的 `LogicalParagraph/fixture → structured RenderUnit` 边界。

## Current Verified Facts

- 历史 `E:\AndroidStudioProjects\mnn-llm-probe` 的 30 条 CPU PASS 使用的是 `qwen3.5-base-mnn-v2-fp16`，不是当前 423MB ReaderDirector int4 v2 artifact；不得再写成后者已通过 Android CPU Gate。
- 当前冻结 ReaderDirector int4 的六文件 SHA 已记录在 `runs/mobile_003_director_runtime/p0a_runtime_provenance.md`。它在 ReaderVoice common runtime 与历史 Probe runtime 中均于 `Llm::load()` 返回 false。
- QNN HTP 对当前冻结 Qwen3.5 hybrid 图的 offline context 转换失败；在同一 int4 artifact 的 CPU load/generate 未通过前，QNN 不进入本计划。
- `app-android` 目前只含 Book Package、章节索引、缓存 WAV consumer 与 TTS Contract，尚无 MNN JNI、tokenizer、RenderUnit persistence 或 Director UI。
- CosyVoice 与独立 Director probe 分别携带不同 SHA-256/大小的同名 `libMNN.so`、`libMNN_Express.so` 与 `libllm.so`；同一 APK 不可能同时打包两套。必须先验证一个共同 runtime，而不是分别证明两个单 App 能运行。

## Non-goals

- 不尝试 QNN/HTP、不修改冻结 LoRA/模型导出、不把 fixture 或 LLM raw text 当 RenderUnit。
- 不进行全书实时分析；先完成固定输入、可取消、可审计的最小段落 Gate。

## Invariants

- 原文不变；RenderUnit 绑定输入来源与版本；UNKNOWN 合法；用户锁不被模型覆盖；推理不在 UI 线程；Current Book=1；QNN 不冒充 CPU 验证。

## Scope

- `app-android/`：MNN JNI bridge、模型安装/校验边界、Director request/result adapter、RenderUnit SQLite schema 与测试入口。
- `docs/PROJECT_STATE.md`、本计划、`MEMORY.md`、`runs/mobile_003_director_runtime/`。

## Baseline

- 当前冻结 423MB ReaderDirector int4 artifact 尚无 Android CPU `load → generate` PASS；历史 Probe 的 FP16 base PASS 只能作为导出控制，不是功能基线。

## Milestones

- M1：probe 源/ABI/模型 manifest snapshot，建立共同 MNN runtime cohabitation Gate：以 CosyVoice 当前 runtime 加载 Director CPU fixture。
- M2：ReaderVoice MNN JNI CPU adapter，固定 fixture 得到原始模型输出；load/generate/error telemetry 可审计。
- M3：MNN GPU capability Gate：验证当前 Qwen3.5 hybrid 图、MNN backend 和 Android APK ABI 的实际 backend 命中与同条件性能；GPU 不能加载、输出失真或无收益即保留 CPU。
- M4：严格结构化 RenderUnit parser + host validation；无效输出 fail-closed。
- M5：真机单段 Gate（加载、输出、结构、内存、取消/切书边界）；不以 APK build 代替。

## Progress

- P0-A ✅ classification Gate（2026-08-22）：历史 Probe 已重新安装；把同一六个 int4 文件逐项 SHA 校验后复制到其独立目录，单提示以 `load=FAIL` 在 3ms 返回。共同 runtime 与历史 runtime 同时失败，排除“ReaderVoice common-runtime 替换”作为主因；历史 PASS 实为不同的 3.5GB FP16 base artifact。证据：`runs/mobile_003_director_runtime/p0a_runtime_provenance.md`。
- M1 ⚠️ PARTIAL / common-runtime load FAIL（2026-08-22）：NDK r27 以 16KB flags 重建的 MNN 3.6.1 shared runtime 已通过 ELF Gate，ReaderVoice APK 可安装且无 16KB warning；但同一 runtime 在真机对冻结 Qwen3.5 model `load()` 返回 false。模型 6 文件存在，补齐 `config.json` / `llm.mnn.json` 后仍失败。旧独立 probe 的 CPU PASS 不能外推到该重建 runtime。
- M2 ⚠️ JNI bridge / package Gate PASS，CPU model Gate FAIL：debug-only ADB probe 与 `readervoice_director_jni` 建成；目录末尾 `/` 漏失曾造成首次 load fail，修正后仍 FAIL，因此不是路径/缺文件问题。停止 GPU Gate。
- M3 ⏳
- M4 ⏳
- M5 ⏳

## Decisions

- D1：先复用经过真机验证的 CPU probe 配置（greedy + `enable_thinking=false`），不得改采样后直接比较旧等价性结论。
- D2：MNN native/model assets 与 CosyVoice assets 分开安装、单独版本化；8GB 模式下 Director 与 TTS 不并发常驻。
- D3：MNN GPU 只在 M3 真机 capability Gate 后成为可选 backend；`<2s` 是目标阈值，不是已验证性能承诺。
- D4：共同 runtime Gate 未过时，不在同一 ReaderVoice APK 同时宣布 Director/CosyVoice 已集成；应重编其中一方到共同 MNN build 或保留独立进程实验。
- D5：Android 16KB 是 native artifact gate，不是 zipalign gate。每一个打入 APK 的 arm64 `.so` 都必须以 `llvm-readelf -lW` 显示所有 `PT_LOAD` 为 `0x4000`；仅 ZIP `zipalign -P 16` 通过不构成 ELF PASS。NDK r27 用 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`。
- D6：P0 后续从“runtime 重建”改为“int4 export artifact audit”。先冻结并比较 FP16 historical control 与 ReaderDirector int4 的 source/merge/export/embedding/config/quantization manifest；在同一 int4 artifact CPU `load → generate` PASS 前，不更换 runtime、不测 GPU/QNN。

## Validation

- Host：Android unit tests + debug APK build。
- Device：固定 fixture 的 load、结构化结果、模型版本记录与内存记录；任一无效/超时/不合规输出为 FAIL。

## Rollback

- 独立新增 runtime adapter 与 assets；不迁移 Book Package 原文。关闭 feature gate 即可回到现有 App Shell。

## Artifacts

- `runs/mobile_003_director_runtime/`。
- `runs/mobile_003_director_runtime/p0a_runtime_provenance.md`。

## Open Issues

- 【已解决 2026-08-22】int4 "两套 runtime 都 FAIL" 归因作废：根因是 app APK（09:18 构建）误打包 1.2MB 陈旧版 `libllm.so`；clean rebuild 打包正确 23.2MB 库后，精简库 / strip 全功能库 / common runtime 三者均 load=OK 且 VS8 输出正确。证据：`runs/mobile_003_director_runtime/cpu_gate_pass.md`、`p0a_clean_replay.md`。ReaderDirector int4 已通过 Android CPU Gate。
- Cosy 的 `libcosy_conditioner_exec.so` 【2026-08-22 勘误】并非无源码：它是改名打包的 ET_EXEC 可执行文件，内嵌 Usage 字符串与输出 `conditioner-android.json` 与 `mnn-jni/CosyVoiceFlowConditionerBenchmark.cpp`（CMake 目标 `CosyVoiceFlowConditionerBenchmark.out`）完全一致。BLOCKED 真实理由仅剩 4KB ELF；用 16KB flags 重编该目标并按原名打包即可解除。其余 4 个 cosy JNI 库在 `mnn-jni/CMakeLists.txt` 均有构建目标。
- 需定义 RenderUnit 结构校验与 Book Package SQLite revision 迁移，不能直接保存模型原文。

## Handoff

- M1 完成后先做 M2 固定 fixture，不与全书批处理或 QNN 路线耦合。
