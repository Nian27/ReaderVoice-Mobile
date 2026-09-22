# COSYVOICE3_MNN_BASELINE.md — CosyVoice3-MNN 基线冻结

**更新：2026-08-12（TASK-000）** | 源仓库：`E:\AndroidStudioProjects\CosyVoice3-MNN-formal`（v1.1.0）

## 身份

| 项 | 值 |
|---|---|
| 仓库 | CosyVoice3-MNN-formal（Nian27/CosyVoice3-MNN 正式版 worktree；Windows 侧无 git 历史） |
| 版本 | v1.1.0（versionCode 2）/ MNN 3.6.1 / AGP 8.13.2 / Kotlin 2.3.10 / minSdk 26 / targetSdk 36 |
| 形态 | 单模块 :app，Kotlin + Compose M3；MNN 为本地预编译 so（16 个）非 Maven |

## 主链（冻结，V1 不改）

```text
LLM → Conditioner(独立可执行子进程,ProcessBuilder) → 2-step Flow → HiFT → 24kHz WAV
```

## 后端现状

- LLM：CPU；SM8850 经 Hexagon 分段过滤仅第 0 层 q_proj 上 NPU（`MNN_HEXAGON_LAYERS/OPS/NAME`）；整 LLM NPU = Token 坍缩，禁止。
- Flow：CPU / OpenCL（精度 normal/high）。
- HiFT：正式 CPU；OpenCL 可用；QNN/HTP 未过 PCM 质量门。
- 无 QNN 预编译库（需自编 `-DMNN_QNN=ON`）。

## 真机性能（荣耀 Magic8 Pro / SM8850 / 12GB，README 冻结）

```text
热态 RTF 0.79–0.96 | 冷态 ~1.7（首次 GPU kernel 编译 10-15s 属正常）
LLM ~1.6s | Flow GPU 热 0.8-1.1s / CPU 2.5-3.5s | HiFT CPU 1.4-2.0s
进程内存 ~947MB（VmRSS 口径；"2.25GB PSS"为旧口径，待 F1 统一）
NPU A/B: LLM wall time -5.94% / decode TPS +5.93%（3/3 命中）
```

## 抽取边界（TASK-120 输入）

- 可搬：`CosyVoiceRuntime`(387行) + `CosyVoiceStore`(632行,17文件清单+SHA256) + 4 Native 对象 + jniLibs 16 so + enrollment（speech-tokenizer-v3+CAM+++flow-speaker-affine ~974MB）。
- 缺口（=CV-001~013）：TtsRequest/优先级/epoch/cooperative cancel/QC cheap gate/VoiceProfileRevision/Prompt Selector/Instruction Compiler。
- 三隐患：JNI 符号漂移（`Java_io_legado_app_cosy_*` vs `com.cosyvoice.app`）；无原生取消；Conditioner 子进程。
- 测试现状：仅 1 个单元测试（HardwarePolicy）；research/cases 12 组 ONNX 对照可用。
- 模型包：`mnn-model-3.6.1`（int4 LLM 353MB + fp16 Flow2step 663MB + fp32 HiFT 70MB + conditioner），c4fuse 变体更小；`mnn-model-llm-w4` 未完成不可部署。

## 行为红线

- V1 不重训/不重蒸（ADR-004）；最终 PCM Gate（finite/内容/音色/听感/RTF）。
- Voice raw 必保留；AudioAsset 绑 voice_revision_id；Merge 后音色不同标 ACOUSTIC_STALE。
- 远端能力 NOT PORTED（ADR-012）。
