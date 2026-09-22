# tts-cosyvoice/ AGENTS.md

CosyVoice3-MNN 工程化抽取（V1 不重训 TTS 主权重，不重蒸 Flow）。源仓库：`E:\AndroidStudioProjects\CosyVoice3-MNN-formal`（v1.1.0 / MNN 3.6.1）。

## 冻结事实（源仓库已核验）

```text
主链冻结：LLM → Conditioner(独立可执行子进程) → 2-step Flow → HiFT → 24kHz WAV
后端：LLM CPU（SM8850 仅第 0 层 q_proj NPU）；Flow CPU/OpenCL；HiFT 正式 CPU
性能：Magic8 Pro 热态 RTF 0.79–0.96；进程内存 ~947MB（VmRSS；PSS 2.25GB 口径待统一）
预编译 so 直接打包（16 个），MNN 3.6.1 非 Maven 依赖
```

## 抽取边界与隐患

- 可搬：`CosyVoiceRuntime` + `CosyVoiceStore` + 4 个 Native 对象 + jniLibs + enrollment。
- **JNI 符号名漂移**：mnn-jni 符号为 `Java_io_legado_app_cosy_*`，Kotlin 包名是 `com.cosyvoice.app`——重编 so 前必须先验证实际导出符号（nm/dumpbin），沿用现有 so 时不得改包名。
- **Conditioner 是改名 .so 的可执行文件**，由 ProcessBuilder 启动：V1 保留此形态，V1.5 再 JNI 化。
- **无原生取消**：cooperative cancel（QUEUED_CANCEL / COOPERATIVE_CANCEL / IN_FLIGHT_STALE）是本项目新设计；LLM 每 token 检查、Flow 两步间检查、HiFT 算完按 epoch 丢弃。禁止 kill 线程。
- 源目录是 WSL worktree，Windows 侧无 git 历史：抽取前先对源仓库做独立 git 快照。

## 不变量

```text
1. 最终 PCM Gate："能跑" ≠ "通过"（finite/非静音/内容正确/音色保持/听感/RTF 全过）
2. Voice raw（source audio + transcript）必须保留，模型升级 re-enroll 而不是旧 embedding 锁死
3. VoicePack 逻辑对象下有 VoiceProfileRevision[]（绑 cosyvoice_model_version/speaker_encoder/speech_tokenizer/frontend + prompt hash）
4. AudioAsset 绑 voice_revision_id，不绑 voice_id；Merge 后 voice 不同必须标 ACOUSTIC_STALE + lazy rebuild，禁止改 metadata 冒充
5. 只有 PerformanceInstructionCompiler 能生成 INSTRUCT2 文本；业务代码禁止自由写"请用非常…"
6. ReaderDirector 输出结构化（language/accent/emotion/intensity/speed/volume），由 Compiler 转有限模板
7. 长句存在 HiFT 非线性退化：RenderUnit 长度必须由真机 profiling 得到 preferred/safe/hardmax，禁止拍脑袋"每 50 字切"
8. 不迁移 v90.7 的远程上传/远程日志（ADR-012）；LLM key 等凭据禁止进代码
```

## 改造任务映射（v5 Appendix I）

CV-001 Engine 抽取 / CV-002 VoiceProfileRevision / CV-003 TtsRequest-TtsResult / CV-004 Instruction Compiler / CV-005 Priority Scheduler（P0 当前播放 > P1 后2-3min > P2 后10min > P3 speculative，单 execution slot 不并发打爆内存）/ CV-006 Generation Epoch / CV-007 Native cooperative cancel / CV-008 Quality Gate（cheap：NaN/duration/silence/RMS/clipping/repeat tail；可疑才 ASR）/ CV-009 Length profiler / CV-010 Voice×Instruct matrix / CV-011 temp GC / CV-012 memory pipeline（V1.5）/ CV-013 Conditioner JNI（V1.5）。
