# READERDIRECTOR_DUAL_MODEL_ARCHITECTURE.md — 双模型架构冻结（TASK-RV-044-B）

**日期：2026-08-20** ｜ 状态：**FROZEN（待 ADR-045 正式化）** ｜ 前置：RENDERUNIT_V1_SCHEMA.md（044-A）、VS2-VS8 冻结、CosyVoice3-MNN v1.1.0 引擎事实。

## 0. 定案

**ReaderDirector（LLM）与 CosyVoice（TTS）不融合参数，保持双模型架构，中间以 RenderUnit v1（044-A）为契约。** LLM 不再管理声音状态（VS8 已证明：LLM 报事件 → 确定性 Reducer 管状态）。

## 1. 总图（冻结）

```
小说 TXT
    │
    ▼
┌──────────────────────────────┐
│      Director Model          │
│  Qwen3.5-0.8B LoRA (vs8-361) │
│  文本理解 / 语义事件 / 缓存规划 │
└───────────┬──────────────────┘
    ┌───────┴────────┐
    ▼                ▼
 Character      Performance
 Resolver       Resolver
 (角色归属→ID)    (VoiceEvent 生成)
    │                │
 voice_profile_id   voice_event[] + semantic_intent
    └───────┬────────┘
            ▼
┌──────────────────────────────┐
│     确定性中间层（无模型）        │
│  VoiceStateReducer (VS4)      │
│  RenderInstructionCompiler    │
│  (RENDERUNIT_V1_SCHEMA §4)    │
└───────────┬──────────────────┘
            ▼
        RenderUnit v1（纯数据队列）
            │
            ▼
┌──────────────────────────────┐
│      Renderer Model          │
│  CosyVoice3-MNN (v1.1.0)     │
│  speaker cond → LLM → Flow → │
│  HiFT → 24kHz WAV            │
└──────────────────────────────┘
```

## 2. Director Model 职责边界（冻结）

**负责**：
1. 小说解析 / 角色发现 / 对话归属（MENTION/SPEAKER/IDENTITY 能力线，checkpoint-361 冻结）
2. VoiceEvent 生成（VS8 四类：SET/CLEAR/PERFORMANCE/NONE + evidence_span 逐字）
3. semantic_intent（封闭枚举，证据门槛，v1 仅 secrecy/urgency 生效）
4. 语言/方言/情绪**证据检测**（只报"有证据"，不做 TTS 标签）
5. 缓存规划：segment 边界、章节预生成决策、profile 引用

**不负责**：
- ❌ 声音生成 / 音色保持（CosyVoice 专属）
- ❌ 直接输出情绪/表演标签（只输出结构化事件+证据；表演标签由编译层确定性产生）
- ❌ 维护声音状态（VoiceStateReducer 专属）
- ❌ 角色 ID 接地（VS6 host GroundingResolver 专属）

**推理设置（冻结）**：enable_thinking=false、temperature=0、max_input 2048、单进程批处理（VS8 管线同款）。

## 3. Renderer Model 职责边界（冻结）

**负责**：speaker embedding / prompt speech tokens / instruction 条件 / Flow / HiFT / waveform（CosyVoice3-MNN v1.1.0 引擎，JNI 四库）。

**不负责**：
- ❌ 判断谁在说话 / 情绪 / 剧情（不接收原文，只接收 RenderUnit 的 render.* + tts_profile.*）
- ❌ 状态维护（无跨句记忆；每 RenderUnit 独立合成，音色身份来自档案）

**模式（冻结，已验证）**：INSTRUCT2（指令文本进 LLM，Flow 仍读同档案 → 身份/表演分离）为默认；ZERO_SHOT（原样复刻）兜底。切换逻辑在 RenderInstructionCompiler（044-A §4）。

## 4. 确定性中间层（冻结）

| 组件 | 输入 | 输出 | 确定性保证 |
|---|---|---|---|
| VoiceStateReducer（VS4） | voice_event 流（按段） | voice_state{state, source} | 25 项确定性测试 |
| GroundingResolver（VS6） | surface 名字 + 候选 | character_id | 10 项确定性测试 |
| RenderInstructionCompiler（044-A §4） | voice_event + intent + profile | render.* + cache.key | 映射表 golden 测试 |

**核心纪律**：所有"状态"与"表演标签"只经确定性程序，不经过第二个模型（R1/R2/R3 不变量）。

## 5. Android 进程模型（冻结：Director Mode / Renderer Mode）

8GB 级设备内存预算（CosyVoice 实测 ~947MB；LLM int4 预估 0.5-1GB；两者常驻 ≈ 2-3GB + Android/SQLite/UI/JVM/GPU 驱动 → 不可行）。

```
Director Mode：  加载 Qwen → 生成 RenderUnit 队列（批）→ 卸载/休眠
Renderer Mode：  加载 CosyVoice → 消费队列连续合成 → 卸载/休眠
```

- **AI 导演 → RenderUnit 队列 → TTS 演员**：队列落 SQLite（voice_plan 表），可中断/续跑（AGENTS 不变量 10：cooperative cancel）。
- 运行时互斥：单进程单引擎加载；切换由 Scheduler 控制（三 Frontier：Playback P0 优先，AGENTS 不变量 9）。
- CosyVoice 侧沿用 lab 结论：单常驻运行时、顺序消费、前两段启动缓冲、跨句流水；热态 RTF>1 时启用整章预生成（非自动背压）。
- 计算后端由设备档案决定（HardwareProfileKey = 厂商+SoC+GPU+驱动+模型哈希），角色不绑定后端。

## 6. 时序（PC 与 Android 同构）

```
文本段落批 → [LLM] → voice_event[] → [Reducer] → voice_state
           → [Compiler] → RenderUnit.jsonl（落库）
           → [CosyVoice] 逐条合成 → wav + cache
           → [Playback] 按 cue 顺序消费
```

## 7. 与既有任务线的关系（PLANS.md 修正）

- TASK-110（MNN Director）：本冻结的 Director 侧推理后端化（PC→Android 逐级 Gate）——待 TASK-RV-045 PC 链路通过后启动。
- TASK-120（CosyVoice Engine 抽取）：引擎已独立存在（CosyVoice3-MNN-formal v1.1.0）；ReaderVoiceMobile 侧只做 RenderUnit 消费者 + 档案库管理（CharacterVoiceDB）。
- TASK-130（RenderPlanner）：**本冻结将 RenderPlanner 具体化为 RenderInstructionCompiler（确定性）+ RenderUnit v1 队列**；Timeline/PausePlanner/Loudness 仍属 TASK-130 后续。
- 新增：CharacterVoiceDB（角色↔音色档案映射，profile 目录 + profile_hash + 状态机 BASE/OVERRIDE 引用）→ 归入 TASK-120/130 交接。

## 7.5 四层声音链与协议文档（冻结，2026-08-20 v2）

```
TXT → ReaderDirector → Semantic Character / Voice Event
  → Voice Persona Generator（TASK-RV-045-A/B，手机端 Mode 2）
  → Voice Profile Factory（TASK-RV-045-C/D，手机端 Mode 3，VOICE_PROFILE_SCHEMA）
  → RenderUnit（RENDERUNIT_V1_SCHEMA）
  → SpeechRoute（VOICE_RUNTIME_ARCH）
  → CosyVoice3-MNN → Audio
```

**闭环位置（冻结）**：声音创造/注册/使用全部在手机完成（无网络）；PC 只做模型转换/编译/训练/测试。

协议三件套（FROZEN）：`docs/protocols/RENDERUNIT_V1_SCHEMA.md`（044-A）、`docs/protocols/VOICE_PROFILE_SCHEMA.md`（044-A 附件）、`docs/protocols/VOICE_RUNTIME_ARCH.md`（044-B 附件）。核心原则：R1 身份/表演分离；R2 LLM 不直接控制 TTS；R3 角色不绑定模型（VoiceProfile → SpeechRoute → 引擎可替换）。

**优先级（按风险，冻结）**：① RenderUnit v1 冻结（完成）→ ② Qwen3-TTS VoiceDesign → CosyVoice enrollment 链路验证（045-B/C）→ ③ PC TXT 闭环（046）→ ④ 100 角色压力测试 → ⑤ Android 接入（047）。不先优化 NPU，不先做手机 UI。成败判据：**AI 生成的角色声音经 CosyVoice 后是否保持角色身份**（CAMPPlus 门槛）。

## 8. Phase 3 验收（TASK-RV-045，PC 100 句）

链路：TXT → ReaderDirector-PC → RenderUnit.jsonl → CosyVoice-main（Python，Phase 3 用官方仓库）→ wav。

测试集 100 句覆盖：男/女角色、老年/儿童、西幻名字、网络小说、第一人称、对话切换。

| 指标 | 目标 |
|---|---|
| 角色正确率（C023 归属） | >95% |
| 音色一致性（CAMPPlus 余弦 vs 注册档案） | 稳定（相对 10 步参考 ≥0.95） |
| CER（Whisper，繁转简） | <5% |
| MOS 盲听 | 明显优于 baseline（Edge/HTTP 引擎） |
| emotion override 错误率 | <5% |

先 PC 后 Android（CosyVoice3-MNN 移植已存在，PC 通过后再切引擎），不提前上手机。
