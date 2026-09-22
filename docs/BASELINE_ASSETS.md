# ReaderVoice-Mobile 基线资产盘点报告（Baseline Asset Inventory）

**日期：2026-08-12**
**依据：`ReaderVoice-Mobile_v5.0_Master_完整执行手册.md`（Master Source of Truth）**
**方法：对 `E:\AndroidStudioProjects` 下相关仓库的只读探查（未修改任何文件）**
**优先级说明：按 v5 Appendix W，本报告属于"实际代码/测试"层级，高于手册；若与手册冲突，以本报告为准并应回写手册。**

---

## 0. 盘点结论摘要（TL;DR）

| 基线 | 手册假设 | 实际状态 | 结论 |
|---|---|---|---|
| 多角色 v90.7 | 角色语义状态机（发音人轮询+别名检验） | ✅ **存在**：独立 legado 朗读规则 JSON `mingwuyan_v907`（raw 在 `research/private/legado-v907/`，脱敏分析副本 14,058 行 JS），微信分发、不在任何仓库；FUN-legado 仓库内另有自研 Kotlin 系统（预处理 v16） | ⚠️ 手册对能力的描述基本准确，但**资产位置与"70 万行 JS"不准确**（实测 14,058 行），需修订 §3.2/§63 |
| CosyVoice3-MNN | v1.1.0 / MNN 3.6.1 / SM8850 受限 NPU | ✅ 与手册完全一致，正式版仓库完整可用 | ✅ 可直接作为 tts-cosyvoice 抽取源 |
| 目录 Regex | 现有规则整体迁入 LegacyChapterRulePack | ✅ `txtTocRule.json` 26 条规则 + `TextFile.getChapterList` 执行 | ✅ 迁移输入明确 |
| 模型仓库 | — | ✅ 完整移动端模型包（int4 LLM + fp16 Flow 2step + fp32 HiFT）齐全，含 enrollment 扩展与胡桃音色样例 | ✅ 可直接部署 |
| 训练实验室 | Flow 2step 蒸馏已完成 | ✅ PoC 通过（非发布门槛），教师权重 9.1G 在库 | ✅ 但环境无可复现性文件 |
| TTS 内存基线 | "三模块常驻 PSS 约 2.25 GB"（v4 手册） | 正式 README 表列"进程内存 ~947 MB" | ⚠️ 口径不一致，需核验 |

---

## 1. 资产总览表

| 资产 | 身份 | 版本/状态 | 对 ReaderVoice 的用途 | 风险 |
|---|---|---|---|---|
| `research/private/legado-v907/v90.7.original.json` | **v90.7 本体**：legado 朗读规则 JSON（code 字段 = JS 脚本） | `tts-rule-2.85-v907`，version 907，作者"命無言、萌新、M"；1.94MB / 28,564 行 / JS 14,058 行（脱敏副本在 third_party） | **TASK-050 v90.7 Exporter 的权威输入**（能力语义迁移的唯一真源）；raw 默认不读，用 redacted | 来源为微信文件，需确认授权与后续更新渠道；含硬编码凭据（见安全审查） |
| `CosyVoice3-MNN-formal/` | CosyVoice3-MNN 正式版 Android App | v1.1.0 / MNN 3.6.1 / Kotlin 2.3.10 / minSdk 26 | TTS Engine 抽取源（engine/model/voicepack/instruction/native） | JNI 符号名与 Kotlin 包名漂移；无原生取消 |
| `FUN-legado/` | legado(阅读) fork，**另一套**自研 Kotlin 多角色朗读系统 | 非 git 仓库；预处理 v16、缓存 schema v22、speech-route-v6 | 工程模式参考（修正回灌/失败保护/硬锁）+ 目录 Regex 迁移源 | 非 git 无法看历史；**不是 v90.7** |
| `legado-archive-v3-3.26.06071119/` | legado 存档 | 有 AGENTS/MEMORY | 对照参考 | 无 v90.7（已核验） |
| `mnn-cosyvoice3/` | 模型转换 + 蒸馏 + 真机基准工作区 | 活跃；stage3 蒸馏 PoC 完成 | 模型产物权威来源、蒸馏可复现脚本 | 无环境锁文件 |
| `mnn-model-3.6.1/`（及 3 个同内容副本 + 4 个 zip） | 全量移动端 MNN 模型包 | fp16 Flow + int4 LLM + fp32 HiFT，manifest.json 齐全 | 直接作为模型仓库内容 | — |
| `mnn-model-c4fuse/` | Flow c4fuse 变体包 | 07-24 更新，Flow 图仅 948KB | 体积更优备选 | — |
| `mnn-model-llm-w4/` | W4 block64 LLM 实验 | **未完成**（weight 0 字节） | 仅实验参考 | 不可部署 |
| `cosyvoice3-distill-lab/` | 蒸馏训练实验室（权重+依赖沙箱 ~10G） | 教师 `Fun-CosyVoice3-0.5B-2512-RL` 9.1G + 5 个 LLM 量化变体 + python-deps 沙箱 | 未来 TTS 微调研究环境；LLM 量化备选 | 无 environment.yml/pip-freeze |
| `MNN-3.6.1/` | MNN SDK 源码 | 源码完整；本地 build 未产出 .so | converter 源码；未来自编 QNN 时用 | 预编译包无 QNN/HTP 库 |
| `mnn-3.6.1-android/` + `-prebuilt.zip` | MNN 3.6.1 android 预编译 so | armv7+arm64，9+ 个 .so，无 QNN | JNI 依赖 | — |
| `TTS_Server_Android_Original/` | jing332/tts-server-android compose 分支 | git 副本，HEAD 2025-07-14 | TTS Server 集成基础（非 CosyVoice 专用） | 与 CosyVoice 不同源 |
| `tts-server-android-compose/` | 同上无 git 快照 | 07-06 | 对照/备份 | — |
| `Fun-CosyVoice/` | CosyVoice3-MNN 前身工作区 | 提交至 2026-07-23 | 历史参考 | — |
| `CosyVoice-main/` | 官方 CosyVoice python 源码 | 无权重 | 教师推理/特征提取参考 | — |
| `hf-*` ×5 | HF 托管镜像（重复副本） | 已备 sha256 | 分发 | 大量重复占盘 |
| `hutao-cosyvoice3-voice-profile.zip` | 胡桃音色档案 | profile.json + tokens/cond/spks/source.wav | 首个可验收 VoicePack 样例 | schemaVersion 需对齐 Revision 设计 |
| `cosyvoice3-mnn-enrollment-extension.zip` | 音色注册扩展包 | 998MB，3 模型文件 | Enrollment 管线 | — |
| `ttsrv-backup-*` ×2 | tts-server 备份 | — | 参考 | — |
| `v2.48.40.260702/` | qairt 相关目录 | 未深入 | 未知 | 待查 |

---

## 2. CosyVoice3-MNN-formal（TTS Engine 抽取源）

### 2.1 工程事实

- 单 Gradle 模块 `:app`；Kotlin + Compose Material3；compileSdk 36 / targetSdk 36 / minSdk 26；MNN 依赖为**本地预编译 so**（非 Maven）；release 用 signing.properties 签名；`isMinifyEnabled=false`。
- 原生侧 `mnn-jni/`（C++ ~3.8k 行）：LLM / Flow / HiFT / Enrollment 各自 `*Jni.cpp` + `*PersistentBenchmark.cpp` 常驻会话；**Conditioner 无 JNI，是独立可执行文件改名 `.so`，由 Kotlin `ProcessBuilder` 启动**（`CosyVoiceRuntime.runCommand`）。
- 推理后端：LLM CPU（SM8850 走 Hexagon 分段过滤，仅第 0 层 q_proj 上 NPU）；Flow CPU/OpenCL；HiFT 正式 CPU 为主；无 QNN（CMake 开关存在但 TTS 路径未用）。
- 性能（荣耀 Magic8 Pro / SM8850）：热态 RTF 0.79–0.96，冷态 ~1.7；LLM ~1.6s、Flow GPU 热 0.8–1.1s、HiFT CPU 1.4–2.0s；NPU A/B：LLM wall time P50 -5.94%，decode TPS +5.93%；README 表列**进程内存 ~947 MB**（VmRSS 口径）。
- 测试薄弱：单元测试仅 1 个（`CosyVoiceHardwarePolicyTest`，3 用例）；无 androidTest；`research/mnn-cosyvoice3/cases/` 有 12 组 ONNX 对照数据（seq32–seq516）。

### 2.2 按 v5 §89 的抽取映射（tts-cosyvoice/ 七个子目录）

| 目标 | 现成可搬 | 缺口（= CV-001~013 输入） |
|---|---|---|
| engine/ | `CosyVoiceRuntime`（Mutex 串行编排 LLM→Conditioner→Flow→HiFT，NPU 失败整句回退 CPU，RTF 报告） | TtsRequest/TtsResult 协议、priority、generation_epoch、Cooperative Cancel、Job 幂等 |
| model/ | `CosyVoiceStore`（17 文件清单 + SHA256 + 下载校验 + ZIP 导入导出 + LLM runtime config） | 模型版本化（ModelManifest v5 §138）、staging/active 切换 |
| voicepack/ | `CosyVoiceVoiceProfile`（schemaVersion 1：profile.json + prompt-speech-tokens.csv + prompt-cond.bin + spks.bin + source.wav）；Enrollment 完整（speech-tokenizer-v3 + CAM++ + flow-speaker-affine，5 文件 ~974MB） | **VoiceProfileRevision**（v5 §46，绑定模型/前端版本）；Prompt Selector（当前"截前 N token"，v5 §49）；VoiceMatcher；quality_score |
| instruction/ | `CosyVoiceInstruction`（11 条 INSTRUCT2 预设：方言/情绪/语速/响度等） | **PerformanceInstructionCompiler**（ReaderDirector 结构化输出 → 有限模板，v5 §92）；Voice×Instruct Matrix 实验 |
| scheduler/ | ❌ 无（只有全局 Mutex） | TtsPriorityScheduler（P0–P3）、buffer 水位、Emergency Backend |
| quality/ | 部分：`CosyVoiceLlmOutputQuality`（Token 坍缩/连续重复）+ `hexagonFailureReason` + HiFT 报告检查（pcmFinite/peak/rms） | cheap gate 补全（duration ratio/silence/RMS/clipping/repeat tail）、ASR 二级门（按需） |
| native/ | 16 个 .so（jniLibs）+ hexagon skeleton + 4 个 Native 对象 | **`requestCancel(jobId)`**；Conditioner JNI 化（v1.5）；清理 run temp 中间文件 |

### 2.3 三个抽取隐患（已证实）

1. **JNI 符号名漂移**：`mnn-jni` 符号为 `Java_io_legado_app_cosy_CosyVoice{Llm,Flow,HiFT}Native_*`，而 Kotlin 包名是 `com.cosyvoice.app`。抽取时若重编 so 必须同步；若沿用现有 so，必须确认实际导出符号（`dumpbin`/`nm` 验证后再动）。
2. **无原生取消**：JNI `run()` 同步阻塞；Kotlin 只能在阶段间 `ensureActive()`，Conditioner 子进程可 `destroyForcibly()`。→ 直接影响 v5 §113–115 的 Cooperative Preemption。
3. **Conditioner 子进程形态**：每 RenderUnit 有进程启动开销；v5 §107 允许 V1 保留 Reference，V1.5 JNI 化。
4. **git 不可达**：该目录是 worktree，`.git` 指向 WSL 路径（`/home/vicentrent/work/...`），Windows 侧无历史。→ 抽取时应先对源仓库做一次**独立 git 快照**。

---

## 3. 模型仓库（可直接部署）

### 3.1 mnn-model-3.6.1 全量包（1.4G，与 `cosyvoice3-mnn-3.6.1.zip` / `mobile-fp16-complete` 等相同）

| 文件 | 大小 | 说明 |
|---|---|---|
| `llm.mnn` + `llm.mnn.weight` | 420KB + 353MB | LLM，int4 weight-only |
| `embeddings_bf16.bin` | 284MB | 词嵌入 |
| `tokenizer.mtok` | 4MB | 分词器 |
| `flow.cfg-student-2step.batch1.fp16.mnn` + weight | 1.34MB + 663MB | 2-step Flow 学生模型（fp16） |
| `flow-conditioner.fp32.mnn` | 4.4MB | Conditioner |
| `hift-core.fp32.mnn` / `hift-f0.fp32.mnn` | 70MB / 13MB | HiFT 主模型 / F0 模型 |
| 辅助 bin + `manifest.json` | — | prompt-cond / rand-noise / spks / source-linear-* |

- `mnn-model-c4fuse/`：唯一差别是 Flow 图换成 c4fuse 变体（948KB），07-24 更新，LLM/HiFT 与 3.6.1 逐字节相同。
- `mnn-model-llm-w4/`：**未完成**（`llm.mnn.weight` 0 字节），不可部署，只作 W4 路线参考。
- enrollment 扩展（998MB）：`speech-tokenizer-v3.fp32.inline.mnn`（970MB）+ `campplus.fp32.mnn` + `flow-speaker-affine` weight/bias。
- 预编译 MNN 库：`mnn-3.6.1-android-prebuilt.zip`（6.2MB，armv7+arm64）**不含 QNN/HTP 库**——QNN 路线需自编 `-DMNN_QNN=ON`（MNN-3.6.1 源码在库）。

### 3.2 对项目的影响

- V1 模型包**无需重新下载/转换**，直接作为 ModelManager 的种子资产（v5 §138）。
- 胡桃音色 zip（396KB）可直接导入验证 VoicePack 全链路（enroll 产物 → 播放），是首条端到端验收样例。
- W4 LLM 若未来需要（8GB 内存压力），是**未完成路线**，需在 `training/runs/` 另立实验重做。

---

## 4. 训练/蒸馏实验室（现状与缺口）

### 4.1 Flow 2-step 蒸馏

- 位置：`mnn-cosyvoice3/scripts/`（`train_cfg_student.py`、`cache_cfg_teacher.py`、`build_two_step_cache.py`、`evaluate_cfg_student_audio.py` 等）。
- 结论（`STAGE3_FEASIBILITY.md`，07-15）：**工程级 PoC 通过、非发布门槛**。原始 2 步输出被拒（RMS +5.36 dB、speaker cosine 0.916）；student 在 held-out 上 mel cosine 0.99882 / SNR 26.26 dB。
- 训练范式：CFG velocity → single-branch student → 2 macro-step trajectory；**无独立 LoRA 脚本**（这是 CosyVoice 侧，与 ReaderDirector 的 Qwen3.5 LoRA 不是一回事）。

### 4.2 教师与依赖

- 教师：`Fun-CosyVoice3-0.5B-2512-RL`（9.1G：flow.pt / hift.pt / llm.pt / llm.rl.pt / campplus.onnx / speech_tokenizer_v3.onnx / cosyvoice3.yaml / hf_merged/），另有 5 个 LLM 量化导出变体（`mnn-llm-*`，266–353MB）。
- 依赖：`python-deps/`（692MB site-packages 含 MNN python 绑定），通过 PYTHONPATH 引用；另有 `.venv/`、`.conda-cosyvoice/`。
- **缺口：无 environment.yml / pip-freeze / requirements.txt** —— 违反 v5 §34/Appendix Q 的"版本冻结"纪律，未来再跑蒸馏前必须先锁环境。

### 4.3 与 ReaderDirector 训练的关系

- ReaderDirector（Qwen3.5-0.8B LoRA，ms-swift）是**全新训练栈**：需要新建 WSL2/conda 环境、下载 Qwen3.5 模型、写 dataset exporter。现有 distill-lab 无法直接复用，但其"先 smoke 后正式、按书 split、runs/ 留档"的经验应写进 `training/AGENTS.md`。
- `cosyvoice3-distill-lab` 里的 `whisper/base.pt` 可复用于 Quality Gate 的 ASR 二级门（v5 §103）。

---

## 5. 多角色系统真相（v90.7 已定位）

### 5.1 核心事实（修正版）

- **v90.7 真实存在，但不在任何 git 仓库中**：它是作者"命無言、萌新、M"发布的 **legado 朗读规则 JSON**（`ruleId: mingwuyan_v907`，`version: 907`，`source: "tts-rule-2.85-v907"`），通过微信等渠道分发，用户导入 legado 使用。原文件在微信接收目录，已隔离为私有资产 `research/private/legado-v907/v90.7.original.json`（1.94MB / 28,564 行；含凭据，默认不读；日常分析用脱敏副本）。
- **结构**：顶层键 `id/isEnabled/name/version/ruleId/author/code/module*/project*/source/tags/tagsData/order`；核心是 `code` 字段（第 8 行单个超长物理行，JSON.parse 展开后 **14,058 行 JS** / 779,255 字符）。"70 万行"的表述不准确，已作废。
- **手册 §3.2 对 v90.7 能力的描述基本准确**（发音人轮询、别名正/负图谱、共现、合并/拆分、性别年龄、临时伪装换声、多 API 投票、失败保护均可在此文件中找到证据）。
- **FUN-legado 仓库内是另一套自研 Kotlin 系统**（`AiReadAloudRoleService` 等），与 v90.7 规则脚本**不是同一代码**。两者都需要迁移评估：v90.7 是"能力语义真源"（TASK-050 输入），FUN-legado 是"工程模式参考"（修正回灌/失败保护/硬锁的实现方式）。

### 5.2 v90.7 JSON 本体验证（关键词证据 + 头部配置变量）

| 能力 | 证据（配置变量/关键词命中） |
|---|---|
| 多 API 投票轮询 | `WAIT_API_RESULT_COUNT=5`（≥3 按投票规则选最优，超时按实际返回数）；"投票"×22、"轮询"×8 |
| 别名正/负证据图谱 | `ENABLE_ALIAS_GRAPH=1 / ENABLE_ALIAS_POSITIVE_GRAPH=1 / ENABLE_ALIAS_NEGATIVE_GRAPH=1`，`GRAPH_NEGATIVE_SOFT_BLOCK=1.0 / HARD_BLOCK=4.0`；"别名"×263 |
| 共现统计 | `ENABLE_ALIAS_COOCUR_STATS=1`，`COOCUR_MAX_NAMES=50 / COOCUR_MAX_SENTENCES=260 / COOCUR_NEG_SENTENCE_MIN=2 / COOCUR_NEG_ADJACENT_MIN=2`；"共现"×34 |
| 临时伪装/换声状态机 | `ENABLE_TEMPORARY_VOICE_STATE=1`（"临时状态只覆盖朗读标签，不写角色卡"）；`applyScope`、`stateAction=end`、`endTiming` 等字段（对应手册 §43 TemporalStateInterval 的原型）；"伪装"×15、"变声"×3 |
| 性别/年龄分析 | `NAME_ANALYZE_TIMEOUT=120000`（姓名性别年龄分析 API）；"年龄"×115、"性别"×38；tagsData 含 12 档性别年龄（男/少年…女/老年） |
| 合并/拆分 | "合并"×73、"拆分"×18（含 `ENABLE_ALIAS_VOTE_MERGE=1` 别名投票合并开关） |
| 发音人判定 | `localSpeakerCandidates` 类逻辑，"发音人"×78 |
| 失败保护 | "失败"×43、"重试"×28、"超时"×9（API 超时与降级逻辑） |
| 缓存 | "缓存"×57（`xiawen=1300` 下文缓存字数、`shouci=800` 首次缓存） |
| 姓名分析 | `NAME_ANALYZE_NEXT_CONTEXT_EXTRA_MAX=3000`、`NEXT_CHAPTER_COUNT=3`（跨章上下文） |
| 远程上传（可选） | `ENABLE_REMOTE_UPLOAD=0`（默认关）、`GRAPH_REMOTE_ENDPOINT` 等——涉及外发数据，**迁移时默认保持关闭** |

### 5.3 17 项能力矩阵（v90.7 JSON = 真源，FUN-legado = 参考）

| # | 能力 | v90.7 JSON（真源） | FUN-legado（参考） |
|---|---|---|---|
| ① | 是否真实发声 | ✅ 有证据（非发言判定） | ⚠️ 部分 |
| ② | Speaker Attribution | ✅ 发音人轮询/判定核心 | ✅ 核心 |
| ③ | 角色复用（跨书） | ❓ 书内规则，未验证 | ❌ 未找到 |
| ④ | Alias | ✅ 别名图谱 + 投票合并 | ✅ |
| ⑤ | 正身份证据 | ✅ `POSITIVE_GRAPH` | ⚠️ 非结构化 |
| ⑥ | 负身份证据 | ✅ `NEGATIVE_GRAPH`（soft/hard block） | ❌ 仅黑名单近似 |
| ⑦ | 共现 | ✅ `COOCUR_STATS` | ❌ 未找到 |
| ⑧ | 关系证据 | ❓ 未验证（需读码确认） | ⚠️ 有表未入判定 |
| ⑨ | Merge/Split | ✅ 合并×73/拆分×18（投票合并） | ❌ 无角色级回滚 |
| ⑩ | 发声音龄 | ✅ 性别年龄分析（12 档） | ❌ 仅 ageBucket 替代 |
| ⑪ | 自然年龄阶段 | ✅ 同上 | ⚠️ 部分 |
| ⑫ | 临时变声 | ✅ `TEMPORARY_VOICE_STATE` 状态机 | ❌ 未找到 |
| ⑬ | 固定音色硬锁 | ❓ 未验证（规则侧无角色卡概念） | ✅ SOURCE_MANUAL |
| ⑭ | 跨章状态持久化 | ✅ 跨章缓存（NEXT_CHAPTER_COUNT=3） | ✅ Room 缓存 |
| ⑮ | 模型审计/多 API 投票 | ✅ 投票轮询（WAIT_API_RESULT_COUNT） | ⚠️ 审计有、投票无 |
| ⑯ | 失败保护 | ✅ 超时/重试/降级 | ✅ 缓存状态机 |
| ⑰ | 用户修正 | ❓ 规则 JSON 不含修正存储（legado 侧配置） | ✅ 两级回灌 |

**迁移结论（修正）**：v90.7 JSON 覆盖手册 §3.2 的能力清单（仅③⑧⑬⑰ 需读码深挖/另找实现）；FUN-legado 提供 Room 持久化、硬锁、失败保护、修正回灌的**工程模式**。二者合并才是完整迁移源。

### 5.4 FUN-legado 工程细节（参考实现）

- 真实版本体系：预处理 `builtin-dialogue-cluster-v16`、角色缓存 schema v22、路由缓存 `speech-route-v6-single-stage-loudness`、TTS 数据版本 11、音色种子 10。
- 当前系统 = `AiReadAloudRoleService`（`help/ai/AiReadAloudRoleService.kt`，4902 行 object）+ `ReadAloudRolePreprocessor` + `ReadAloudSpeechPlanner` + `SpeechVoiceAssigner`。Kotlin 1247 文件 ~28 万行；JS 仅 19 个文件 ~2.4k 行（前端资源）。legado 式"规则脚本"嵌在 `assets/defaultData/*.json`（`httpTTS.json`、`ttsServerPlugins.json`、`ttsServerSpeechRules.json`、`txtTocRule.json`）。

### 5.2 17 项能力矩阵（对照 v5 §3.2 列表）

| # | 能力 | 状态 | 位置 |
|---|---|---|---|
| ① | 是否真实发声 | ⚠️ 部分（kind 分类 + looksLikeNonSpeechEvidence 关键词） | `ReadAloudRolePreprocessor.kt`、`AiReadAloudRoleService.kt:3447` |
| ② | Speaker Attribution | ✅ 核心 | `AiReadAloudRoleService.kt`（UnitResolution / routeForSegment / localSpeakerCandidates 打分 130~18 档）、`ReadAloudSpeechPlanner.kt` |
| ③ | 角色复用（跨书） | ❌ 未找到（角色按 bookUrl=work:作者/书名 隔离，仅换源迁移） | `BookCharacterIdentityMigrator.kt` |
| ④ | Alias | ✅ | `BookCharacterNameMatcher.aliasKeys()`、`AiReadAloudRoleService.parseCandidateAliases:3700` |
| ⑤ | 正身份证据 | ⚠️ 非结构化（evidence 自由文本随 prompt 送 AI） | `CharacterCandidate.evidence` 等 |
| ⑥ | 负身份证据 | ❌ 未找到（仅有 invalidSpeakerNames 黑名单近似） | `ReadAloudRolePreprocessor.kt:67-74` |
| ⑦ | 共现 | ❌ 未找到（0 命中） | — |
| ⑧ | 关系证据 | ⚠️ 数据模型有但**未接入判定**（AI 角色判定不消费 relation） | `BookCharacterRelation.kt`、`AiBookCharacterTool.kt` |
| ⑨ | Merge/Split 可回滚 | ❌ 未找到（33 处 merge 均为批处理/缓存语义；角色删除无回滚） | — |
| ⑩ | 发声音龄 voiceAge | ❌ 无此概念；替代为 ageBucket 五档（child/teen/young/middle/old） | `SpeechModels.kt:558-568` |
| ⑪ | 自然年龄阶段 | ⚠️ 部分（ageKeywords 匹配 + AI 档案 age 字段） | `SpeechModels.kt:570+` |
| ⑫ | 临时变声（伪装/模仿/压嗓） | ❌ 未找到（0 命中） | — |
| ⑬ | 固定音色硬锁 | ✅ SOURCE_MANUAL 不走合理性检查 | `SpeechModels.kt:85`、`AiReadAloudRoleService.kt:1634-1637` |
| ⑭ | 跨章状态持久化 | ✅ `ai_read_aloud_role_caches`（bookUrl+chapterIndex+contentHash 双索引 + characterHash/voiceHash 校验） | `AiReadAloudRoleCache.kt` |
| ⑮ | 模型审计 / 多 API 投票 | ⚠️ 审计有（用量表），投票无——是"主模型+备用模型+重试" | `AiReadAloudUsageRecord.kt`、`AiReadAloudRoleService.kt:2388` |
| ⑯ | 失败保护 | ✅ 缓存状态机 PENDING/RUNNING/SUCCESS/FALLBACK/FAILED + 失败按旁白播放 + 不写假缓存 | `AiReadAloudRoleService.kt` |
| ⑰ | 用户修正存储与回灌 | ✅ 两级：片段级写 segmentsJson（直接回灌播放）；角色级写 book_characters（硬锁） | `ReadAloudPlayerPanel.kt:680`、`AiReadAloudRoleService.kt:280-368` |

**结论：可完整迁移的语义 = ②④⑬⑭⑯⑰；需重新设计/补建的 = ③⑥⑦⑨⑩⑫（跨书复用、负证据、共现、Merge 回滚、voiceAge、临时变声）；手册所列 v90.7 能力中有 6 项是"目标态"而非"已有资产"。**

### 5.5 用户修正机制（FUN-legado 工程模式，值得搬）

- 片段级：Room `ai_read_aloud_role_caches.segmentsJson`，播放时 `cacheForPlayback` 优先读，**修正立即生效**。
- 角色级：`book_characters`（speechRouteJson + profile），SOURCE_MANUAL 硬锁。
- 规则级：SharedPreferences 预处理规则 JSON（上限 8000 字），UI 可编辑/试运行/恢复内置。
- **不存在"修正自动编译成硬规则"**（v5 §123 Correction Compiler 是新设计）。
- v90.7 JSON 侧的"修正"形态：`tagsData` 里的 `dialogue/duihua/localSound1/括号*` 等标签体系（角色名、性别年龄 12 档、性格等），是用户在 legado 界面配置的标签数据，随规则文件分发——迁移时作为"用户可配置的角色元数据 schema"参考。

### 5.6 目录 Regex（LegacyChapterRulePack 输入）

- 数据：`app/src/main/assets/defaultData/txtTocRule.json`，26 条默认规则。
- 执行：`model/localBook/TextFile.kt`（`getChapterList` 89–110 行选规则；501–528 行 `Pattern.MULTILINE` 匹配 + 标题处理 + 正则语法错误捕获）。
- family（规则 name）：目录(去空白)/目录/目录(匹配简介)/目录(古典、轻小说备用)/数字/大写数字/数字混合/数字+分隔符+标题/大写数字+分隔符+标题/数字混合+分隔符+标题/正文 标题 序号/Chapter-Section-Part-Episode 序号 标题/Chapter(去简介)/特殊符号 序号 标题/特殊符号 标题(成对、单个)/章 卷 序号 标题/顶格标题/双标题(前向、后向)/书名 括号 序号/书名 序号/特定字符 标题 特定符号/字数分割 分节阅读/通用规则/默认分章规则。
- 网络书源目录解析走 Rhino JS 引擎（`modules/rhino`），ReaderVoice 不需要迁移这一条。

### 5.7 FUN-legado TTS 调用侧（接口参考）

- 链路：`SpeechRoute`(engineType: http/system/local-voice/tts-server-plugin) → `HttpReadAloudService` → `tts/core/TtsManager.getOrSynthesizeFile(TtsRequest)` → `TtsEngine.synthesize(): ByteArray`。
- HTTP TTS 主力：legado 模板 URL + JS 引擎，变量 `{{speakText}}/{{currentToneID}}/{{currentSpeakerName}}/{{currentEmotionTag}}/{{currentSpeechRouteJson}}`；引擎定义在 `httpTTS.json`。
- CosyVoice 仅存在于 `cosytest` flavor（`help/cosy/*` MNN JNI + `LocalVoiceRouteRepository`）——正式 app 不含。

### 5.8 文档资产

- `FUN-legado/MEMORY.md`（57KB，项目记忆：多角色 AI/TTS 缓存/响度/CosyVoice 全部口径）——**能力迁移工作开始前必须通读**。
- `FUN-legado/AGENTS.md`（9.7KB，含会话历史，最新 2026-08-03 QNN 里程碑）。
- `docs/COSYVOICE3_LOCAL_INTEGRATION.md`（CosyVoice3 本地集成方案）。
- 无测试书单/难例归档 → v5 Milestone 5 的 Hard Case Dataset 需要新收集；v90.7 作者后续版本（2.86+）可作迭代参考。

---

## 6. 其他相关资产

- `TTS_Server_Android_Original/`：tts-server-android（jing332）compose 分支本地 git 副本（HEAD 2025-07-14）。与 CosyVoice3-MNN-formal **不同源**。若 ReaderVoice 需要"系统级 TTS Server 集成"可参考，V1 主链不需要。
- `Fun-CosyVoice/`：CosyVoice3-MNN 前身/兄弟工作区，git 提交至 2026-07-23；formal 版之外的实验态历史。
- `hf-cosy-voice-mnn/`（2.7G）+ `hf-cvm/hf-new/hf-repo/hf-repo2`（各 4.5G）：HF 托管镜像重复副本，仅用于分发，工程上可忽略（占盘 ~21G，可在确认 sha256 后清理——**清理需用户确认**）。
- `legado-signing/`：仅一个签名 keystore（`vicentrent-release.jks`）——不迁移、不入库。
- `v2.48.40.260702/qairt/`：Qualcomm AI Runtime 相关，未深入（可能用于 QNN 路线，待查）。

---

## 7. 对项目启动的影响与建议

1. **手册修订（先于 TASK-000 的正式内容）**：
   - v5 §3.2 / §63 / Milestone 5 中"v90.7"的资产形态修订为：**独立 legado 朗读规则 JSON**（raw：`research/private/legado-v907/v90.7.original.json`；分析副本：`research/third_party/legado-v907/v90.7.code.redacted.js`，mingwuyan_v907，14,058 行 JS），非仓库代码；"70 万行 JS"删除（实测 14,058 行）；
   - 能力语义迁移清单以 §5.3 为准：v90.7 JSON 已覆盖手册 §3.2 大部分能力（含负证据、共现、临时变声、多 API 投票），③跨书复用/⑧关系证据/⑬硬锁/⑰用户修正 需读码深挖或由 FUN-legado 工程模式补足；
   - 内存基线：先统一"947MB（VmRSS）vs 2.25GB（PSS 假设）"口径，再写入 PROJECT_STATE。
2. **TASK-000（Milestone 0）应把本报告摘要写入 `PROJECT_STATE.md`，并把"v90.7 资产定位"作为 DECISIONS 条目**（按 v5 Appendix W：先更新 DECISIONS/PROJECT_STATE，再修 Master Manual）。
3. **TASK-020（Chapter）**：`LegacyChapterRulePack` 的初始规则直接导入 `txtTocRule.json` 26 条（转 Kotlin 数据类 + family 标注），无需重写。
4. **TASK-050（v90.7 迁移）**：Exporter 的**权威输入 = v90.7 JSON 的 `code` 字段**（解析 JS 提取状态机语义：投票轮询、正负图谱、共现、临时换声、年龄档位、失败保护）；FUN-legado 的 Room 表（`book_characters`、`ai_read_aloud_role_caches`、`book_character_relations`）与 MEMORY.md 作为参考实现补充；v90.7 的 `ENABLE_REMOTE_UPLOAD/GRAPH_REMOTE_*` 为外发数据能力，迁移时默认关闭。
5. **TASK-120（CosyVoice Engine）**：抽取边界 = `CosyVoiceRuntime + CosyVoiceStore + 4 Native 对象 + 16 .so + enrollment`；先做独立 git 快照（worktree 无历史）；动 so 前先验证 JNI 导出符号。
6. **训练环境**：ReaderDirector 训练栈全新搭建（ms-swift + Qwen3.5），照 v5 Appendix G 模板，先 100-step smoke；现有 distill-lab 仅作 TTS 微调研究参考，并补环境锁文件。

---

## 8. 待核验事实清单（Open Facts）

| # | 事实 | 状态 |
|---|---|---|
| F1 | TTS 常驻内存口径：947MB（VmRSS/进程内存） vs 2.25GB（PSS） | 不一致，需真机重新测量后冻结 |
| F2 | JNI 导出符号与 `Java_io_legado_app_cosy_*` 声明是否一致（so 实际链接符号） | 需 dumpbin/nm 验证 |
| F3 | Conditioner 子进程启动开销（每 RenderUnit 平均耗时） | 需真机 profiling |
| F4 | v90.7 资产定位 | ✅ 已解决：微信分发规则 JSON（raw 在 `research/private/`），不在任何仓库；权威统计 14,058 行 JS（旧"70 万行/~5.7 万行"作废） |
| F5 | v90.7 JSON 中 ③跨书复用/⑧关系证据/⑬硬锁/⑰用户修正 四项的确切实现 | 待 TASK-050 读码深挖（本次仅关键词级验证） |
| F5 | hutao voice profile 的 schemaVersion 1 与 VoiceProfileRevision 设计的兼容性 | 设计对齐时验证 |
| F6 | W4 LLM（block64）是否值得重做（8GB 内存预算） | 待 8GB 模式设计时决定 |
| F7 | `v2.48.40.260702/qairt/` 内容与用途 | 未深入 |
| F8 | 模型升级后旧书声音一致性（SynthesisEpoch）目前无任何既有实现 | 全新设计 |

---

*本报告为只读盘点产物；所有路径基于 2026-08-12 探查。后续 Agent 若发现与仓库现状不符，按 v5 Appendix W 优先更新本文档所在层的结论。*
