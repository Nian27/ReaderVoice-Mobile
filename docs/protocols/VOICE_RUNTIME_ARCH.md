# VOICE_RUNTIME_ARCH.md — 声音运行时架构 v1 冻结（TASK-RV-044-B 附件）

**日期：2026-08-20** ｜ 状态：**FROZEN（待 ADR-045 正式化）** ｜ 前置：READERDIRECTOR_DUAL_MODEL_ARCHITECTURE.md、RENDERUNIT_V1_SCHEMA.md、VOICE_PROFILE_SCHEMA.md、CosyVoice3-MNN v1.1.0 真机数据（RTF 0.79-0.96 热态 / ~947MB / SM8850 q_proj NPU +5.94%）。

## 0. 定位

本文件冻结**声音生成侧运行时**：SpeechRoute、引擎边界、队列、内存策略、降级路径。LLM 侧见 044-B 主文档。

## 1. SpeechRoute（冻结，R3 落地）

```
角色 character_id
  → CharacterVoiceDB.active_profile_id
  → VoiceProfile（assets + hash）
  → 引擎路由：SpeechRoute = { engine: "cosyvoice3-mnn" | "http" | "system" | "none",
                               profile_id, profile_hash }
  → 引擎合成（RenderUnit 消费）
```

- **角色只绑定 VoiceProfile，不绑定引擎/后端**；引擎与硬件策略属于设备层（HardwareProfileKey = 厂商+SoC+GPU+驱动+模型哈希），不属于角色。
- 引擎可替换（CosyVoice3 → IndexTTS → 其他）：RenderUnit 不含引擎私有字段；替换 = 换 SpeechRoute 目标 + 版本升级缓存失效。
- 旁白与角色同机制（旁白 = 保留角色位，可独立 profile 或用户指定）。

## 1.5 VoiceSystem 分层与 NarratorProvider（FROZEN，2026-08-20）

```
VoiceSystem
├── NarratorProfile（旁白，一级 Voice Role）
└── CharacterVoiceProfiles（苏晚晴 / Aurelius / 药老 …）
```

- **段落路由**：SegmentClassifier → NARRATION → NarratorProfile；DIALOGUE → CharacterProfile（RENDERUNIT §3.5）。
- **NarratorProvider 抽象（不绑定 Edge）**：

```
NarratorProvider（全离线，FROZEN 2026-08-20）
└── Audio8（第一版旁白吞吐目标；仅 Android Gate PASS 后可用）
└── System（兜底，需显式用户许可）
（edge_tts 仅开发期对照，不进入产品路线）
```

- **全离线原则**：旁白与角色一样完全本地；手机端无 Edge/云端依赖。
- 参数接口统一：`style{gender, age, tone, speed}`（NarratorProfile 字段），上层不感知具体引擎。
- **第一版固定分流（ADR-045）**：旁白/未知说话者 = Audio8 吞吐层；具名角色 = CosyVoice 本地身份层。Audio8 尚未通过 Android Gate 时，旁白 route 必须 unavailable，禁止静默转 CosyVoice。
- 旁白本地缓存与角色同机制（cache.key 公式，RENDERUNIT §5），engine 变化只失效旁白片段。

## 2. 引擎边界（冻结）

| 引擎 | 形态 | 输入 | 输出 | 状态 |
|---|---|---|---|---|
| cosyvoice3-mnn | Android JNI 四库（llm/flow/hift/enrollment） | RenderUnit（render_instruction + voice_profile） | 24kHz wav | 已验证（v1.1.0） |
| cosyvoice-main（PC） | Python 官方仓库 | 同上 | 24kHz wav | Phase 3 验证用 |
| cosyvoice（角色层） | 本地 CosyVoice（所有具名角色的身份保持离线渲染） | RenderUnit 同角色 | 24kHz wav | 全离线；后台预生成 |
| audio8（旁白层，2026-08-21 调研） | Audio8-TTS 0.1B/0.6B（DualAR，zero-shot 克隆，ONNX INT4 已有，Apache 2.0，11 语言含中文） | text + 旁白 reference identity | 44.1kHz | 旁白/未知说话者大规模吞吐；Android Gate 未过前不可用 |
| http（legacy） | 远程 TTS | text + 简单参数 | wav | 降级/对照用，不进新链路 |

## 3. 队列与生产消费（冻结）

- **RenderUnit 队列 = SQLite voice_plan 表**：Producer（Director）写入，Consumer（Renderer）顺序消费；可中断/续跑（AGENTS 不变量 10 cooperative cancel）。
- **CosyVoice 单常驻运行时**：顺序合成、前两段启动缓冲、跨句流水；热态 RTF>1 时整章预生成（禁自动背压暂停，沿用 E 盘 lab 结论）。
- **播放 P0**（AGENTS 不变量 9）：当前播放窗口优先；合成队列按播放 cue 顺序消费统一音频缓存。
- 缓存：audio 文件 + SQLite 索引（cache.key = RENDERUNIT §5 公式）；AudioAsset 绑定 voice_revision_id（不变量 13）。

## 3.5 手机端三模式（冻结，2026-08-20 修订）

| 模式 | 常驻 | 说明 |
|---|---|---|
| Mode 1 阅读模式 | ReaderDirector + CosyVoice | 日常朗读（TXT → RenderUnit → TTS） |
| Mode 2 角色创建模式 | 临时加载 VoicePersona Generator | 生成候选 wav → 卸载 |
| Mode 3 注册模式 | 临时加载 enrollment | candidate → spks/cond/tokens → VoiceProfile → 卸载 |

- 创建/注册与阅读互斥（Scheduler 协作；Playback P0 优先；完成后恢复合成队列）。
- 内存峰值 = 单模型；**禁止 VoiceDesign 与 CosyVoice 同时常驻**（与 Director/Renderer 同一原则）。

## 3.6 Voice Allocation Policy（冻结）

| 层级 | 条件 | 声音来源 |
|---|---|---|
| 具名角色 | 有明确身份 | CosyVoice VoiceProfile（Tier 1 自动生成、Tier 2 共享 Profile） |
| 旁白/未知说话者 | 无具名身份 | Audio8 NarratorProfile（Android Gate PASS 后） |

- 判定依据是**文本证据**（出场/对话统计），不靠模型猜测；统计来自 ReaderDirector 角色分析输出。
- Tier 1 角色首次遇到（无档案）→ 触发 Mode 2 → Mode 3（VoiceFactoryManager 编排）。

## 4. 内存策略（8GB 级设备，冻结）

```
Director Mode：加载 LLM → 批量生成 RenderUnit → 卸载/休眠（int4 0.5-1GB 短暂占用）
Renderer Mode：加载 CosyVoice（~947MB 常驻）→ 消费队列 → 卸载（可选）
```

- **禁止 LLM + TTS 同时常驻**（两者常驻 ≈ 2-3GB + Android/SQLite/UI/JVM/GPU 驱动，8GB 不可行）。
- 切换由 Scheduler 控制（三 Frontier；Playback P0 优先）。
- 内存预算表（8GB 设备，冻结目标）：Android 系统 + 宿主 ~2GB ｜ CosyVoice 运行时 ~1GB ｜ 音频缓冲/队列 ~0.5GB ｜ LLM 瞬时 int4 ~0.5-1GB ｜ 余量 ≥2GB。

## 5. 降级路径（冻结）

| 条件 | 行为 |
|---|---|
| voice_state=UNKNOWN（角色无档案） | RenderUnit 标记降级，不合成；播放层用系统/旁白声线占位并提示 |
| profile 失效（hash 不匹配/档案删除） | 缓存音频失效；角色进"音色失效"状态；不静默换声 |
| 引擎加载失败/数值检查失败（finite/RMS/削波） | 整句丢弃并重跑 CPU 兜底路径（沿用 NPU 验证纪律） |
| 热态 RTF>1 持续 | 整章预生成模式；不做自动背压暂停 |
| 引擎缺失（模型包未装） | SpeechRoute 回落 http/system；RenderUnit 队列保留 |

## 6. 验收（044-B 附件 acceptance）

1. 队列压力测试：Producer 1000 段 → Consumer 顺序消费，取消/续跑恢复一致。
2. 引擎替换测试：同一 RenderUnit 队列分别消费 cosyvoice-main 与 cosyvoice3-mnn，输出 wav 均有效（PC 先行）。
3. 内存验证（Android 阶段）：Director/Renderer 切换峰值 < 预算表。
4. 降级矩阵测试：上表 5 条件逐一触发并验证行为。


## 7. 本地有声书编译器架构（FROZEN，2026-08-21 用户定案）

> **定位修正**：小说阅读 = 可预生成场景（TXT/角色/Profile 不变）→ CosyVoice 是**高质量离线 Renderer**，不是实时 TTS。核心矛盾从"冷启动"转为"本地音频编译缓存系统"。

```
TXT → ReaderDirector → RenderUnit Queue
  → Offline Render（CosyVoice3，后台预生成，优先级队列：当前章前 20 分钟优先）
  → 句子级 wav cache → 播放（秒开）
  → Audio8 Narrator Render：旁白/未知说话者批量生成（Android Gate PASS 后）
```

- **句子级粒度**（segment_000001.wav 对应 RenderUnit seg_001）：可局部修改重生成，不做整章 wav。
- **音频缓存三层（冻结）**：
  1. 热缓存：最近 1000 句 wav（24kHz）
  2. 冷缓存：RenderUnit + VoiceProfile + seed（重生成，不存 wav）
  3. 压缩：24kHz opus（20-40kbps；100 万句 ≈ 几十 GB → 可接受配额内）
- **Book Package（Android 文件结构，冻结）**：
```
book/
├── book.db          # SQLite：render_units 索引 + 缓存状态 + 增量更新
├── render_units/    # seg001.json …（RenderUnit 落盘）
├── audio/           # seg001.opus …
├── voices/          # vp001/（运行时资产）
└── cache/           # 冷缓存（seed/临时）
```
- 缓存键沿用 RenderUnit §5 公式；增量更新：改文本/档案只失效相关句，重生成后原子替换。
- **固定身份分流**：具名角色全部 CosyVoice（可共享 Profile）；旁白/未知说话者全部 Audio8。设备性能动态切换属于未来 ADR，不进入第一版。
## 8. 四模型 Runtime 架构 v2（2026-08-24 用户定案，FROZEN；supersede §3.5/§4 内存条目）

> **supersede 声明**：§3.5"创建/注册与阅读互斥、禁止 VoiceDesign 与 CosyVoice 同时常驻"与 §4"禁止 LLM+TTS 同时常驻"**被本 v2 取代**——v2 以 MnnTaoAvatar 官方架构为据：多模型可同时常驻（旗舰 SoC / ≥8GB RAM / ~5GB 模型空间），常驻策略见 §8.6。其余 v1 冻结条款（SpeechRoute、引擎边界、AudioAsset/voice_revision_id、音频缓存三层、降级路径）继续有效。

### 8.1 四模型分工（FROZEN，各司其职不重叠）

| 模型 | 身份 | 高频 | 干什么 | 不干什么 |
|---|---|---:|---|---|
| Qwen3.5-0.8B | 导演 | 高 | 文本理解：人物发现、speaker attribution、旁白/对话切分、情绪、方言、韵律、新角色 VoiceSpec | 不生成音频 |
| Qwen3-TTS VoiceDesign | 选角师 | 极低（新角色首次出现） | 自然语言造声 → 参考音色 wav（3 候选取稳） | 不负责全书台词 |
| CosyVoice | 角色演员 | 高 | 按固定音色（VoiceProfile）做各情绪表演 | 不决定角色是谁 |
| Audio8-TTS | 旁白演员 | 高 | 固定旁白音色持续朗读 | 不负责角色切换 |

### 8.2 核心数据结构（FROZEN）

```
Character      { characterId, aliases, gender, age, persona, voiceStages[] }
VoiceStage     { stageId, label(少年/青年/成熟…), ageRange, characterId, voiceProfileId }
VoiceSpec      { characterId, stageId, gender, age, pitch, brightness, weight, breathiness, roughness, accent, description }
VoiceProfile   { profileId, voiceStageId, characterId, qwenReferenceAudio, referenceText, cosySpeakerEmbedding, cosyPromptTokens, fingerprint, version }
SpeechSegment  { segmentId, type[NARRATION|DIALOGUE], characterId, voiceStageId, text, emotion, intensity, speed, dialect, voiceProfileId }
RenderedSegment{ segmentId, pcm, sampleRate, engine[audio8|cosyvoice], timing }
```

- **阶段关系（FROZEN）**：`Character → VoiceStage(01,02,…) → VoiceProfile(每 stage 一个)`。长篇小说合法声线阶段变化（萧炎 少年→青年→成熟）由 VoiceStage 表达；VoiceProfile 永不被覆盖，Character 不塞阶段状态。
- **资产目录按阶段**：`voices/<character_id>/<stage_id>/{voice.json, reference.wav, reference.txt, cosy_embedding.bin, cosy_prompt_tokens.bin, fingerprint.json}`。

### 8.3 Voice Bridge（选角师 → 演员，FROZEN）

```
角色文字描述 → Qwen3-TTS VoiceDesign → reference.wav + reference.txt → CosyVoice zero-shot enrollment → VoiceProfile
```

- **identity/performance 分离**：enrollment 只用**固定规范文本**（自然中性语气），禁止用带情绪台词（吼叫/快语速/重呼吸会污染身份）。
- **每个 VoiceStage 原则上只执行一次**（A/B/C 3 候选取最稳后冻结 VoiceProfile）；**正常台词渲染永不调用 Qwen-TTS**。仅以下情况重新启动：新角色、新 VoiceStage、用户主动重建音色、VoiceProfile 损坏/迁移。
- **音色=谁在说；情绪=怎么说**：同一 VoiceProfile 供 happy/angry/sad 各情绪 CosyVoice 表演，绝不为情绪重新造声。
- **永久资产目录**（每角色）：`voices/<character_id>/{voice.json, reference.wav, reference.txt, cosy_embedding.bin, cosy_prompt_tokens.bin, fingerprint.json}`。

### 8.4 五个软件模块（FROZEN）

```
ReaderVoice Runtime（总控）
├── DirectorManager      Qwen3.5 导演（常驻 warm）
├── VoiceRegistry        角色音色资产库 + VoiceSpec 缓存（MISS → 触发 VoiceCreationManager）
│    └── VoiceCreationJob Qwen3-TTS 选角 → 3 候选 → CosyVoice enrollment → 存入 Registry（按需加载，结束后释放）
└── SpeechScheduler（交通警察）
     ├── Audio8Worker     旁白串行队列（单常驻实例）
     ├── CosyVoiceWorker  角色串行队列（单常驻实例）
     ├── ReorderBuffer    segmentId 有序重排（先算完不等于先播）
     └── PersistentAudioTrack  WRITE_BLOCKING 追加 PCM；audio-marker 映射 playbackHeadPosition ↔ 当前句
```

- **引擎内串行、引擎间并行**：Audio8/CosyVoice 各自单实例单会话（AR/Cosy 状态不保证线程安全），两引擎互不阻塞。
- **不落 WAV 为正常路径**：PCM 直入队列；WAV 仅 cache/export/debug。
- **audio-marker**（参考 TaoAvatar AudioChunksPlayer）：维护 playbackHeadPosition → 当前段文本映射，用于高亮当前句/角色头像/点击跳转/进度条/断点续播。

### 8.5 流式分段与切段纪律（FROZEN）

- **不等待整章**：LLM 逐 segment 产出 → 立即入队列合成/播放（segment0 就绪即开播），与后续分析并行。
- **不用朴素标点切段**（TaoAvatar 的 `delimiters.toRegex()` 会把标点漏掉，小说绝不允许）：标点影响停顿/情绪/韵律/对话边界。切段必须由 parser/导演的**结构化 SpeechSegment** 驱动，标点完整保留。
- **会话/epoch 切换**（跳章/退出）：`generationEpoch++`，cancel 旧 epoch 全部 job；旧 Audio8/CosyVoice/LLM 结果一律丢弃；UI 回调校验 epoch，防"跳章后蹦出旧句"。

### 8.6 常驻策略（FROZEN；supersede §4）

| 模型 | 状态 | 说明 |
|---|---|---|
| Qwen3.5-0.8B | Warm 常驻 | 导演高频 |
| Audio8-TTS | Warm 常驻 | 旁白高频 |
| CosyVoice | Warm 常驻 | 角色高频 |
| Qwen3-TTS VoiceDesign | 默认 Unloaded | 仅新角色创建时按需加载（一部小说新角色 30~100 个，运行占比 ~0.5%），完成后释放 |

- **Voice Lookahead（提前造声）**：预先解析章节 → 发现未来 30~100 段内首次出现的新角色 → 后台立即启动 VoiceCreationJob；等播到该段时声音已就绪（不卡顿）。
- **架构允许** Qwen3.5 + Audio8 + CosyVoice 同时 Warm（TaoAvatar 证明"多模型并行常驻"是合理的官方工程模式）；**实际驻留由 RuntimeMemoryGovernor 按设备 RAM / PSS / thermal gate 动态决定**，不把 TaoAvatar 解读为"我们这三模型组合已过 8GB Gate"（体积/内存需真机 PSS 实测）。
- **驻留优先级**：Qwen3.5（导演当前必然使用）> 当前即将使用的 TTS > 另一 TTS；**Qwen3-TTS VoiceDesign 默认 COLD/UNLOADED**，仅 VoiceCreationJob 窗口内加载，完成后释放。

### 8.7 官方参考定位（2026-08-24）

> **MnnLlmChat = LLM/MNN/Hexagon 后端如何跑**；**MnnTaoAvatar = 多模型常驻/并行初始化/LLM→TTS 流水/会话 cancel/PCM 有序播放**（App 调度层第二 reference）；**MNN Hexagon backend = Road B 内核**（Execution/Command/LinearAttention）。
- ReaderVoice 架构 = LLM 层（MnnLlmChat 模式）→ ReaderVoice Core（导演+小说管线）→ SpeechScheduler（TaoAvatar 模式改造）→ Audio8/CosyVoice 双引擎 → ReorderBuffer → AudioTrack。
