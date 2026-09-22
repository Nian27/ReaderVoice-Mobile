# PLAN-20260820-045-voice-factory-closed-loop.md — Mobile VoiceFactory Feasibility（TASK-RV-045/046，2026-08-20 修订）

> **目标修正（用户定案）**：最终 App 必须完全本地运行，不依赖 PC 创造角色声音。
> 手机端完成「创造声音 → 注册声音 → 使用声音」完整闭环；PC 只承担模型转换/编译/训练/测试，不参与用户运行流程。
> 原"PC VoiceFactory → 手机 TTS"路线**取消**。

**日期：2026-08-20** ｜ 状态：ACTIVE（用户定案）｜ 前置：TASK-RV-044 冻结（RENDERUNIT_V1_SCHEMA / VOICE_PROFILE_SCHEMA / VOICE_RUNTIME_ARCH / 双模型架构）

## 0. 总目标

实现「任意小说 TXT → 自动角色分析 → **手机端自动生成角色声音** → 自动朗读」完整链路。

**第一验收目标（冻结，替代原 PC 闭环）**：
> 一台手机，在无网络情况下，根据角色文本描述生成一个新的 VoiceProfile，并立即用于 CosyVoice3-MNN 朗读。

**PC 角色**：模型转换、编译（MNN）、训练、测试；不参与用户运行流程。

## 1. TASK-RV-045：Mobile VoiceFactory（手机端声音创造闭环）

> 解决："没有参考音频时，如何**在手机上**创建小说角色声音。"

### 手机端三模式（冻结，非三模型常驻）

| 模式 | 常驻 | 流程 |
|---|---|---|
| Mode 1 阅读模式 | ReaderDirector + CosyVoice | TXT → RenderUnit → TTS（日常） |
| Mode 2 角色创建模式 | 加载 VoicePersona Generator（VoiceDesign） | 生成候选 → 卸载 |
| Mode 3 注册模式 | 加载 enrollment 模块 | candidate.wav → CAM++ → spks.bin → VoiceProfile → 卸载 → 恢复阅读 |

内存峰值 = **一个阶段一个模型**，不是 Director+CosyVoice+VoiceDesign 三者叠加。

### 045-A Character Voice Persona（LLM 出描述，不出声音）
- 输入：小说角色信息（如 药老：白发老者/阅历丰富/声音苍劲/喜欢调侃）。
- 输出（冻结 schema，见 VOICE_PROFILE_SCHEMA persona 段）：gender/age/pitch/timbre/texture/style。
- 来源：ReaderDirector（Qwen3.5-0.8B，checkpoint-361 系；必要时加 persona 专用微调数据）。

### 045-B VoicePersona Backend（Qwen3-TTS VoiceDesign，接口可替换，**手机端运行**）
- **接口（冻结）**：`Persona Description → Voice Candidate wav`（**类型 2：生成参考语音**）。后端可替换：Qwen3-TTS / IndexTTS / GPT-SoVITS / 自研——RenderUnit 与 VoiceProfile 完全不变（SpeechRoute 可替换原则）。
- **两类文本→音色确认（冻结）**：类型 1（直接生成 speaker latent）要求与 CosyVoice embedding 空间一致，需跨模型 adapter——**属后续研究，非产品路线**；类型 2（生成 5s 参考 wav → CosyVoice zero-shot enrollment）易接入——**第一版走类型 2，全部在手机完成**。
- 输入：persona 描述（elder male, deep rough, wise, humorous）。
- 输出：reference_voice.wav；每主要角色 **3 个候选**（candidate_01..03.wav）。
- **Qwen3-TTS 不是 CosyVoice 替代品，是 CosyVoice 的"声音设计器"**：VoiceDesign 只需产出质量稳定的 reference wav。
- 设备化约束：目标 Android/MNN 运行（int4 量化优先）；若 Qwen3-TTS 无法设备化 → 备用路线：小模型 persona→speaker embedding（需训练，另立任务）。

### 045-C CosyVoice Enrollment（复用已验证管线，**手机端已具备**）

> 注：手机 enrollment 已由 CosyVoice3-MNN v1.1.0 验证（音色创建扩展：tokenizer-v3 + CAM++ 均在 MNN 上，30-60s 实测）——P3 基本已证，本任务为集成与自动筛选。
- reference.wav → tokenizer（speech-tokenizer-v3）→ CAM++ speaker encoder → affine → spks.bin + prompt tokens + prompt-cond.bin。
- 产出目录：`voice_profile/<name>_v1/{reference.wav, spks.bin, prompt.csv, cond.bin, metadata.json}`（VOICE_PROFILE_SCHEMA 结构）。
- PC 端用 CosyVoice-main（Python）跑注册；数值一致性对照桌面 ONNX 参考（E 盘 lab 纪律）。
- **自动候选筛选（禁人工，手机内完成）**：① 三候选 CAM++ 两两自一致性 → ② LLM/VLM persona 匹配评价 → ③ enrollment 后生成测试句（CER + speaker similarity）→ 得分最高者激活；不达标重生成（≤2 轮）。

### 045-D Mobile VoiceFactory Runtime（新增，冻结）
- **VoiceFactoryManager** 生命周期：`load(VoiceDesign) → generate(candidates) → select(auto) → enroll → save(VoiceProfile) → unload`；每步结束即卸载，内存峰值单模型。
- 与阅读模式互斥：创建/注册期间暂停合成队列（Scheduler 协作，Playback P0 优先）；完成后恢复。
- 产物：runtime_assets（spks/cond/tokens/profile.json）落 CharacterVoiceDB；dev_assets（wav/192 维 embedding）创建后按配额保留或清理。
- 失败处理：任一候选不达标 → 重生成 ≤2 轮 → 仍失败则角色降级 Tier 2 共享池。

### Voice Allocation Policy（冻结）
| 层级 | 条件 | 声音来源 |
|---|---|---|
| Tier 1 主要角色 | 出场次数 > 50 且 对话比例 > 5% 且 有明确身份 | 自动生成（Mobile VoiceFactory） |
| Tier 2 次要角色 | 其余有名角色 | 共享声音池（路人男/路人女/士兵/老人…） |
| Tier 3 旁白/龙套 | 无身份要求 | 旁白声线 |

> 理由：网文常 1000+ 人物，全量生成 speaker profile 无意义；策略按证据（出场/对话比例）决定，不靠模型猜测。

### 045 验收
| 项 | 目标 |
|---|---|
| persona 生成成功率 | >95%（LLM 出 schema 合法 JSON） |
| 候选 enrollment 成功率 | >90%（3 候选至少 2 个过一致性门槛） |
| 档案结构/字段/hash 校验 | 100% |
| CAMPPlus 同角色一致性 | >0.8 |

## 2. TASK-RV-046：PC 端完整闭环验证（第一阶段禁手机）

### 链路
```
TXT → Chapter Parser → Character Resolver → Voice Persona Generator
  → Voice Profile Factory → RenderUnit → CosyVoice-main → wav
```

### 测试小说 benchmark/（3 类）
| 类型 | 特点 | 关注 |
|---|---|---|
| 类型 1 网络小说 | 多角色、对话频繁 | 角色区分/快速切换 |
| 类型 2 文学小说 | 情绪变化 | 情绪匹配/表演克制 |
| 类型 3 西幻 | 英文/非中文角色名 | 名字转写、TTS 语言 |

### 验收指标（冻结）
| 指标 | 目标 |
|---|---|
| 角色识别（归属正确率） | >95% |
| 角色连续一致性（同角色跨句） | >95% |
| VoiceProfile 生成成功率 | >90% |
| CAMPPlus：同角色 | >0.8 |
| CAMPPlus：异角色 | <0.6 |
| CER（Whisper，繁转简） | <5% |
| 100 句盲听（身份一致/自然度/情绪匹配） | 记录并过审 |

### 关键判据（项目成败）
> **AI 生成的角色声音，经过 CosyVoice 后是否还能保持角色身份。** CAMPPlus 同角色 >0.8 / 异角色 <0.6 为硬门槛；盲听身份一致为软确认。

## 3. TASK-RV-047（延后，不在此计划内执行）

Android 集成：RenderUnit 队列（SQLite）→ CosyVoice3-MNN（常驻）→ Audio Stream；LLM 可卸载（Director/Renderer 模式，VOICE_RUNTIME_ARCH §4）。PC 闭环通过后才启动。

## 4. 执行顺序（风险排序，冻结，2026-08-20 修订 v2）

0. RenderUnit v1 冻结 ✅（TASK-RV-044，git 12aebbf）
0.5 **M0.5 Voice Interface Check** ✅（VOICE_PROFILE_V1_COMPATIBILITY.md，git ce17808）
1. **M0.6 Mobile Voice Creation Feasibility（三个 P 问题，当前最关键实验）**
   - **P1** Qwen3-TTS VoiceDesign 能否 Android/MNN 运行？（0.6B int4 优先；MNN 转换可行性）
   - **P2** 生成的 5s 参考 wav 质量是否足够（→ enrollment 后 CAMPPlus 可辨识）？
   - **P3** 手机 enrollment（wav → tokens/cond/spks）实时性 —— **基本已证**（CosyVoice3-MNN v1.1.0 手机创建音色 30-60s），本阶段做集成验证。
   - P1 不行 → 路线调整：小模型 persona + 内置 voice bank（另立任务）；P1 可以 → 方案成立。
2. **M1 Mobile VoiceFactory Prototype**：药老 persona →（手机流程）VoiceDesign 3 候选 → 自动筛选 → enrollment → VoiceProfile → CosyVoice 生成 100 句 → CAMPPlus 验证。验收 = 手机无网络完成「描述 → 新 VoiceProfile → 立即朗读」
3. **M2 三类角色扩展**：老年男性 / 年轻女性 / 西幻角色（纳威隆巴顿、英文名、中间点名——验证 persona 不是中文规则）
4. PC TXT 闭环（046，回归验证用）
5. 100 角色压力测试（046 扩展，含 Voice Allocation Policy Tier 验证）
6. Android 集成（047：三模式 + VoiceFactoryManager）

不先优化 NPU，不先做手机 UI（VoiceFactoryManager 除外）。剪枝（Flow attention/FFN/block、HiFT INT8）属第三阶段。

## 5. 里程碑与 Gate

| 里程碑 | 输出 | Gate |
|---|---|---|
| M0.5 接口检查 | VOICE_PROFILE_V1_COMPATIBILITY.md | 契约 4 项 acceptance |
| M1 链路验证 | 1 角色（药老）persona→3 候选→自动筛选→enroll→档案→100 句生成 | 自动筛选完成 + CAMPPlus>0.8 |
| M2 Factory | 5 角色全流程 + 失败处理 | 成功率>90% |
| M3 PC 闭环 | 3 类小说各 1 章 → RenderUnit → wav | 角色正确率>95% + CER<5% |
| M4 压力测试 | 100 角色 | CAMPPlus 同>0.8/异<0.6 |
| M5 盲听 | 100 句盲听记录 | 身份一致通过 |

## 6. 与既有任务的关系

- **TASK-096（VoicePersona 试点）合并入本计划**：096 的 6-persona 试点与 Qwen0.6B-clone 对比臂降级为可选消融；主线 = Qwen3-TTS VoiceDesign → CosyVoice enrollment（用户 2026-08-20 定案）。
- TASK-110/120/130（MNN Director / CosyVoice 抽取 / RenderPlanner）：044-B §7 关系修正生效；RenderPlanner = RenderInstructionCompiler + RenderUnit 队列。

## 7. 环境与算力安排

- PC：RTX 4060 Ti 16GB。
  - ReaderDirector：训练已完成（VS8 checkpoint-361），后续只需 LoRA 微调。
  - VoiceDesign：Qwen3-TTS 0.6B 可试跑；4B 需 Q4/offload。
  - CosyVoice：**不训练**，只 PC inference（CosyVoice-main）。
- CosyVoice-main（E 盘，Python）；Qwen3-TTS（HF，hf-mirror/代理）；task080 venv 或独立 venv（不污染现有训练环境）。
- **PC 只做**：Qwen3-TTS → MNN 转换（int4 优先）与可行性验证、模型编译、训练（如需小模型 embedding 备用路线）、测试；**不参与用户运行流程**。
- 产物落 `runs/voice_state/voice_factory/`（新增）：开发验证产物（PC）与手机侧契约测试产物分离。
- 文档纪律：先冻结协议，后写代码；ADRs（044/045）在 A+B 冻结确认后生成。

---

## 8. 六阶段执行路线（2026-08-20 用户定案，M1 PASS 后）

> 核心判断：风险已从"模型能力"转为"系统工程闭环"；不再训练模型，先把已验证模块组合成可运行产品。

| 阶段 | 任务 | 内容 | 目标 |
|---|---|---|---|
| Phase 0 | TASK-VF-000 | VoiceProfile v1 协议冻结（schema/hash/version） | 角色声音保存什么，一锤定音 |
| Phase 1 | VF-001/002/003 | M1.5 严格验证：concat 长音频、情绪保持、三角色泛化 | 证明非药老特例（mean>0.85/std<0.05；情绪同人>0.8；同>0.8/异<0.6/漂移<0.05） |
| Phase 2.0 | NarratorProfile v1 + Edge TTS 默认旁白 | 旁白一级 Voice Role（NarratorProvider 抽象 + 本地缓存 + speed/pitch 参数） | 旁白自然度/稳定性；引擎可插拔 |
| Phase 2 | PC-001 | TXT→SegmentClassifier→VoiceResolver→Narrator/Character→TTS 完整闭环 | speaker>95%、profile 命中>95%、无串音 |
| Phase 3 | — | CharacterVoiceManager + 重要性评分（0.4 出场+0.3 对话+0.2 剧情+0.1 关注）→ Tier1 生成/Tier2 共享池/Tier3 旁白 | 一本小说不生成几百个声音 |
| Phase 4 | MOBILE-001/002/003 | 模型资产整理（常驻=Director int4+CosyVoice；非常驻=VoiceDesign 1.7B）；VoiceProfile 压缩（单角色<5MB，去 reference wav）；PC 模拟 8GB 内存 | 移动化准备 |
| Phase 5 | MOBILE-004 | PC 模拟手机（4 线程/6GB 限制）：热启动/100 句/角色切换 → RTF/memory peak/first latency | Android 前最后验证 |
| Phase 6 | — | Android Implementation Package：models/database/schemas + 协议冻结（RenderUnit/VoiceProfile/CharacterDB/缓存） | 进入 Android 改造 |

**执行顺序（冻结，2026-08-20 修订）**：① VF-003-B++ ✅ → ② NarratorProfile v1 + Edge TTS 旁白 → ③ PC-001 300 段闭环（**每类 100 段 = 70 旁白 + 30 对白**，真实比例）→ ④ PC-002 1000 段压力 → ⑤ Android。


---

## 9. 编译器架构转向（2026-08-21 用户定案，追加）

> 定位：小说阅读可预生成 → **本地有声书编译器**，非实时 TTS。CosyVoice = 高质量离线 Renderer；Audio8 = 试听/低端/fallback。协议见 VOICE_RUNTIME_ARCH §7。

| 任务 | 内容 | 状态 |
|---|---|---|
| MOBILE-000A | **ReaderVoice Runtime**（重定义）：固定 fixture（RenderUnit+VoiceProfile+wav cache）跑通 多角色/连续播放/缓存命中/断点恢复 | 待真机（无线 adb 10.40.128.204:36953 未连通，需设备端配对） |
| MOBILE-000B | ReaderDirector MNN 化：Step1 转换 ✅；Step2 真机 Android Gate **PASS**（2026-08-21：int4 图 + 自编译 16KB 对齐 MNN 3.6.1，VS8 prompt 输出 V=PERF; E=… 正确；fp16 图在 3.6.1 不可用→int4 为生产路线）；Step3 任务级等价性（HF vs MNN）进行中 | Step2 真机 PASS |
| **MOBILE-000C**（新增） | **Audio Cache Compiler**：句级生成 + opus 压缩 + SQLite 索引 + 增量更新（book.db/render_units/audio/voices/cache） | 未开始 |
| MOBILE-000D（新增） | **TTS Router**：Audio8（fast/preview/Tier2）+ CosyVoice（premium/Tier1）质量策略；Audio8-TTS 0.1B/0.6B ONNX INT4 已存在（调研 2026-08-21） | 未开始 |
| MOBILE-000E（新增） | 全书压力测试：百万字导入 → 自动分角色/建声 → 后台生成 → 离线播放 | 未开始 |
| MOBILE-VF | VoiceDesign 端侧化 | **降优先级**（声音设计非播放瓶颈） |
