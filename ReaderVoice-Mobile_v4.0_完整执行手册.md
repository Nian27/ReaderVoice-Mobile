# ReaderVoice-Mobile v4.0 完整执行手册
## 端侧多角色长篇有声书编译系统：TXT 结构恢复 + ReaderDirector 蒸馏 + CosyVoice3-MNN + Android + Agent 工程规范

**版本：v4.0**  
**日期：2026-08-12**  
**开发机基线：NVIDIA RTX 4060 Ti 16 GB**  
**目标端：Android ARM64，优先 Snapdragon 8 Gen 3 / 8 Elite，最低完整体验目标 8 GB RAM**  
**核心基础：现有目录 Regex、现有多角色 v90.7、Nian27/CosyVoice3-MNN、Qwen3.5 小模型、MNN、Room/SQLite、Media3**

---

# 0. 项目最终要做成什么

ReaderVoice-Mobile 不是“给小说套一个 TTS”，也不是“把整本小说扔给 LLM”。

它是一个：

> **完全离线、可增量编译、可局部修正、可版本迁移、面向长篇小说的多角色 AI 有声书编译器。**

用户最终流程：

```text
导入 TXT
  ↓
自动识别编码
  ↓
识别目录 / 卷 / 章
  ↓
恢复真实段落
  ↓
识别旁白 / 对白 / 心理活动 / 引用 / 群体发声
  ↓
建立角色与身份图
  ↓
自动绑定 VoicePack
  ↓
ReaderDirector 只处理规则难以解决的语义问题
  ↓
生成 NarrationIR
  ↓
SemanticSegment → RenderUnit
  ↓
CosyVoice3-MNN 生成音频
  ↓
Quality Gate
  ↓
Timeline Renderer
  ↓
段落级播放
```

最终必须支持：

- 书库几十至上百本书；
- 未激活书不运行昂贵 LLM/TTS；
- 当前书按需语义分析；
- 即将播放窗口优先 TTS；
- 多角色声音长期稳定；
- 用户自建 VoicePack；
- 角色 Alias / Merge / Split；
- UNKNOWN 角色后续回填；
- 年龄声线变化；
- 临时伪装声 / 模仿 / 压嗓；
- 内心独白；
- 群体发声；
- 方言/口音；
- 情绪与语速；
- 用户修正立即变成硬规则；
- APP 被杀或重启后恢复；
- 模型升级后旧书仍然声音一致；
- 飞行模式可正常听书。

---

# 1. 已有资产不是废代码，而是三条成熟基线

## 1.1 目录 Regex 系统

现有规则已经覆盖：

```text
章节1：拉斯维加斯
#1「图文」被女友
03：祸国殃民的表弟
12
一百七十
1、人参公鸡
二百二十章 boy next door
```

策略：

- 不重写一个超级 Regex；
- 现有规则整体迁入 `LegacyChapterRulePack`；
- 在其外增加全局结构解析。

最终：

```text
Legacy Regex
↓
Candidate Scan
↓
Sequence / Style / Density Scoring
↓
TOC Detector
↓
Volume/Chapter Resolver
```

---

## 1.2 多角色 v90.7

现有系统已经不是简单“角色名 → 发音人”，而是一个角色语义状态机。

应提取并保留的能力：

```text
是否真实发声
Speaker Attribution
角色复用
Alias
正 Identity Evidence
负 Identity Evidence
共现
关系证据
合并/拆分
发声音龄
自然年龄阶段
临时变声
固定音色硬锁
跨章状态
模型审计
失败保护
```

新项目原则：

> **ReaderVoice 的多角色能力不得比 v90.7 倒退。**

但不搬 14,058 行级 JS（2026-08-12 node 实测修正；旧"70 万级"表述作废）。

迁移：

```text
状态语义
证据类型
真实难例
用户修正
测试用例
```

重新用 Kotlin / Python / Room 实现。

---

## 1.3 CosyVoice3-MNN

当前仓库已经有可用 Android TTS 主链：

```text
Text
↓
LLM
↓
Speech Tokens
↓
Conditioner
↓
2-step Flow
↓
Mel
↓
HiFT
↓
24 kHz WAV
```

截至 2026-08 当前仓库主线：

- MNN 3.6.1；
- SM8850 已验证 `第0层 q_proj NPU + 其余 LLM CPU`；
- 整 LLM NPU 因 Token 坍缩不作为正式路径；
- Flow 支持 CPU/OpenCL；
- HiFT 正式仍以 CPU 为主；
- Flow 已完成从 10 步到 2 步蒸馏；
- README 真机数据：Magic8 Pro 热态 RTF 约 0.79–0.96；
- v1.1.0 三模块常驻 PSS 约 2.25 GB；
- 冷启动 OpenCL kernel 编译可能出现 10–15 秒级等待；
- HiFT 长句存在非线性退化；
- HiFT QNN/HTP 最终 PCM 尚未过质量门。

结论：

> **TTS 模型本身先不重训，优先工程化改造。**

---

# 2. 项目分层

```text
ReaderVoice-Mobile/
│
├── parser/
│   ├── source/
│   ├── chapters/
│   ├── layout/
│   └── paragraph/
│
├── core-character/
│   ├── mention/
│   ├── entity/
│   ├── identity/
│   ├── scene/
│   └── voice-state/
│
├── core-director/
│   ├── rules/
│   ├── context/
│   ├── protocol/
│   └── runtime/
│
├── training/
│   ├── exporters/
│   ├── datasets/
│   ├── teacher/
│   ├── sft/
│   ├── eval/
│   └── runs/
│
├── tts-cosyvoice/
│   ├── engine/
│   ├── voicepack/
│   ├── scheduler/
│   ├── quality/
│   └── native/
│
├── audio-renderer/
│
├── scheduler/
│
├── data-room/
│
├── app-android/
│
├── docs/
│   ├── architecture/
│   ├── protocols/
│   ├── experiments/
│   └── agent/
│
├── AGENTS.md
└── README.md
```

每个复杂子系统使用局部 `AGENTS.md`。

---

# 3. 开发顺序必须先结构、后语义、再 TTS

严格顺序：

```text
TASK-000  Agent/Repo 工程规范
TASK-010  TXT Source/Encoding
TASK-020  Chapter/TOC/Volume
TASK-030  Paragraph Recovery
TASK-040  Database Schema
TASK-050  v90.7 数据迁移与能力矩阵
TASK-060  Character / Identity / VoiceState
TASK-070  ReaderDirector Gold Set
TASK-080  ReaderDirector Baseline
TASK-090  LoRA / Distillation
TASK-100  MNN Director
TASK-110  CosyVoice Engine 重构
TASK-120  VoicePack Revision
TASK-130  RenderPlanner / Timeline
TASK-140  Scheduler / Playback Bootstrap
TASK-150  Multi-book / Storage
TASK-160  8GB 真机验收
TASK-170  Beta APP
```

禁止从 `TASK-090` 直接开始训练模型。

---

# 4. TASK-000：先建立 Agent 工作体系

第一位 Agent 在写业务代码前必须完成：

```text
AGENTS.md
docs/agent/PLANS.md
docs/agent/TASK_TEMPLATE.md
docs/PROJECT_STATE.md
docs/DECISIONS.md
```

并创建局部：

```text
parser/AGENTS.md
core-character/AGENTS.md
training/AGENTS.md
tts-cosyvoice/AGENTS.md
app-android/AGENTS.md
```

## 4.1 为什么不用一个超级 AGENTS.md

当前主流 `AGENTS.md` 约定和 Codex 官方实践都强调：

- 根文件放项目级规则；
- 子目录放局部规则；
- 离目标代码越近的规则优先；
- 不要重复 lint/README 内容；
- 长任务应使用单独 ExecPlan；
- 规则应基于真实重复错误逐步增加。

本项目根 `AGENTS.md` 只放：

```text
项目目标
不可破坏不变量
工作流程
最小验证
实验纪律
安全规则
Definition of Done
```

局部实现细节下沉到模块文件。

## 4.2 AGENTS.md 维护规则

Agent 必须遵守：

1. 如果重复出现同一种 Agent 错误 ≥2 次，评估是否应更新最近一级 `AGENTS.md`。
2. 一次性任务细节不得塞入根文件，写进 ExecPlan。
3. CI 已能自动检查的格式细节不重复写进 AGENTS。
4. 新规则必须可执行、可验证，不能写“写高质量代码”这种空话。
5. 根文件尽量控制在约 8 KB 以内。
6. 局部文件尽量控制在约 4–6 KB。
7. 修改核心架构时必须同步检查相关 AGENTS 是否过期。
8. 所有 Agent 在开始模块工作前显式读取该模块最近的 AGENTS.md；不能依赖工具一定自动发现嵌套文件。
9. `AGENTS.override.md` 只用于临时实验性覆盖，不作为长期规范。
10. 不同 Agent 工具不原生支持 AGENTS.md 时，用它们自己的入口文件指向 AGENTS.md，不维护两套冲突规则。

---

# 5. ExecPlan：复杂任务必须先写可执行计划

以下任务必须写 ExecPlan：

- DB schema 迁移；
- Parser 重构；
- ReaderDirector 数据/训练协议变更；
- CosyVoice native backend 变更；
- TTS 数值精度/量化变更；
- Scheduler / cancellation；
- 跨 2 个以上核心模块的功能；
- 任何可能影响已冻结 Gate 的改动。

ExecPlan 文件：

```text
docs/agent/plans/PLAN-YYYYMMDD-xxx.md
```

必须包含：

```text
Goal
Current facts
Non-goals
Invariants
Files/modules
Milestones
Progress
Decision log
Experiment table
Validation
Rollback
Artifacts
Open issues
Handoff
```

ExecPlan 是 living document。

Agent 每完成一个 milestone 更新 Progress，而不是最后才补。

---

# 6. 项目状态与决策文档

## PROJECT_STATE.md

只放：

```text
当前冻结事实
当前模型版本
当前手机 baseline
已通过 Gate
已失败路线
下一正式任务
```

禁止写成长篇日记。

## DECISIONS.md

记录不可轻易反复的架构决定：

```text
ADR-001 Character != VoicePack
ADR-002 PhysicalLine != LogicalParagraph
ADR-003 Student 先单 Multi-task LoRA
ADR-004 TTS V1 不重训主模型
ADR-005 False Merge > False Split
...
```

每条：

```text
Decision
Reason
Alternatives
Evidence
Consequences
Revisit trigger
```

---

# 7. TXT Source 层

TXT 是主输入。

处理顺序：

```text
bytes
↓
encoding
↓
PhysicalLine
↓
Raw structural anchors
↓
Layout recovery
↓
LogicalParagraph
↓
Text normalization
↓
SemanticSegment
```

不可颠倒。

## 7.1 编码

支持：

```text
UTF-8
UTF-8 BOM
GBK
GB18030
UTF-16LE
UTF-16BE
```

保存：

```text
detected_charset
confidence
user_override
```

## 7.2 原文不可变

永远保留：

```text
raw source
source offset
```

任何：
- trim；
- 广告跳过；
- 段落恢复；
- 发音归一；

都不能破坏映射。

---

# 8. PhysicalLine

数据库至少：

```text
line_id
book_id
line_no
source_start
source_end
raw_text

leading_ws_kind
leading_ws_count
trailing_ws_count

char_count
punctuation_features
quote_features

is_blank
is_separator
chapter_candidate
```

必须先提取 layout feature，后 trim。

---

# 9. Chapter Parser

现有 Regex 只负责：

```text
Candidate Generation
```

不负责最终判断。

`ChapterCandidate`：

```text
line_id
rule_id
family
raw_title
parsed_serial
base_score
sequence_score
style_score
density_score
toc_score
final_score
```

## 9.1 Rule Family

```text
STANDARD_ZH
ZH_CHAPTER_PREFIX
HASH_NUMBER
ARABIC_PREFIX
PURE_NUMBER
SPECIAL_TITLE
VOLUME
ENGLISH
CUSTOM
```

## 9.2 中文数字独立解析

例如：

```text
第二百二十章 → 220
壹佰零三 → 103
〇一二 → 12
0012 → 12
```

## 9.3 TOC

开头连续高密度章标题且没有正文：

```text
TOC_ENTRY
```

正文中的同名章：

```text
CHAPTER
```

TOC 反过来辅助正文定位。

---

# 10. Paragraph Recovery

核心原则：

> `\n` 只是一个证据，不等于 Paragraph。

支持：

```text
多行 → 一段
一行 → 多段
```

`LineBoundary`：

```text
SOFT_WRAP
HARD_PARAGRAPH
AUTHOR_LINE_BREAK
STRUCTURAL_BREAK
UNCERTAIN
USER_JOIN
USER_BREAK
```

保存 confidence + evidence。

## 10.1 固定宽度硬换行

检测：

```text
行长高度集中
+
大量长行没有句末标点
+
下一行无段首缩进
```

## 10.2 一行一段

检测：

```text
行长变化大
+
句意完整
+
频繁全角段首缩进
```

## 10.3 CrossParagraphLink

例如：

```text
张三说道：
“你来了。”
```

可以保持两个逻辑段，但建立：

```text
SPEECH_CUE
```

不让 Speaker Resolver 丢线索。

---

# 11. Special Block

识别：

```text
PROSE
POETRY
LYRICS
SCRIPT
MESSAGE_LOG
LIST
SEPARATOR
BOILERPLATE
```

`POETRY/LYRICS` 不自动 prose reflow。

网文广告标：

```text
SKIP_READ
```

源文本不删。

---

# 12. Parser Gold Set

至少 20–30 本真实 TXT。

按“书”划分训练/测试，不按句。

覆盖：

```text
20字硬换行
40字硬换行
无空行
空行加倍
全角缩进
引号跨行
聊天
诗歌
广告
混合格式
超长单行
```

指标：

```text
Chapter Boundary F1
TOC false positive
JOIN Precision/Recall
BREAK Precision/Recall
LogicalParagraph Exact Match
SpeakerCueBreakError
Paragraph-Induced Speaker Error Rate
```

最后两个是本项目关键指标。

---

# 13. Character 数据模型

不再：

```text
name -> character
```

而是：

```text
Mention
↓
NarrativeEntity
↓
Identity
↓
Embodiment
↓
VoiceState
```

## 13.1 EntityType

```text
PERSON
GROUP
NARRATOR
SYSTEM
NON_PERSON_AGENT
UNKNOWN
```

## 13.2 Mention

正文出现形式：

```text
张明
张教授
老张
师父
妈妈
```

Mention 不等于 Alias。

## 13.3 IdentityEvidenceGraph

边：

```text
SAME_PERSON
DIFFERENT_PERSON
ALIAS
TITLE
RELATIONSHIP
CONTROL
POSSESSION
EMBODIMENT
IMITATION
CO_PRESENCE
UNKNOWN
```

每条证据：

```text
positive/negative
confidence
segment_id
source
model_version
revision
```

---

# 14. Complex identity：附身 / 分魂 / 傀儡

必须区分：

```text
身份是谁
身体是谁
文本表面叫谁
谁实际发声
```

Segment 保存：

```text
speaker_identity_id
surface_mention
embodiment_id
```

例如：

```text
林雪身体
被魔尊控制
当前发声主体 = 魔尊
```

不能因为表面主语是林雪就用林雪声音。

---

# 15. UNKNOWN 是合法结果

无法确认：

```text
UNKNOWN_SPEAKER_003
```

不要强造：

```text
神秘男子
黑衣人
青年男人
```

后文确认后：

```text
UNKNOWN_003 -> role_张明
```

这能显著减少错误 Alias。

---

# 16. Character 生命周期

```text
CANDIDATE
PROVISIONAL
CONFIRMED
MERGED
SPLIT
RETIRED
```

任何 Merge 必须可回滚。

`Mention` 仍保留原证据，不物理删除。

---

# 17. VoiceState 三层

```text
BaseVoice
VoicePhase
TemporaryVoiceOverride
```

优先级：

```text
USER_LOCKED
>
TemporaryOverride
>
VoicePhase
>
BaseVoice
```

## 17.1 VoicePhase

```text
少年
青年
中年
老年
```

长期时间轴。

## 17.2 TemporaryVoiceOverride

```text
伪装
模仿
变声
压低声音
```

时间区间：

```text
start segment
end segment
```

不依赖用户按顺序读过上一章。

---

# 18. Voice Age

分开：

```text
chronological_age
appearance_age
voice_age
```

VoiceMatcher 主要看 `voice_age`。

---

# 19. ReaderDirector 不是通用聊天模型

定义：

> **规则处理不了的小说语义残差任务模型。**

任务：

```text
UTTERANCE
SPEAKER
IDENTITY
RELATION
VOICE_STATE
SCENE
EMOTION
DIALECT
```

一个共享模型，一个 Multi-task LoRA。

---

# 20. Rule First

绝大多数确定问题不进 LLM：

```text
显式“张三说道”
确定 Alias
用户 Override
两人简单交替
当前 Scene 排除
```

只有低置信：

```text
ReaderDirector
```

这样同时改善：

- 精度；
- 延迟；
- 电量；
- 训练难度。

---

# 21. ReaderDirector Context

不传整个章节。

只传：

```text
current segment
recent 5–20 segments
scene participants
relevant identity evidence
confirmed aliases
user overrides
rule candidates
```

典型 1k–4k tokens。

复杂难例再扩大。

---

# 22. Identity Memory 与 Performance Memory 分开

## Identity Memory

可以使用未来章节证据：

```text
张老师其实就是张明
```

## Causal Performance Memory

只能使用当前文本时间点以前：

```text
当前情绪
当前关系
当前秘密
当前态度
```

禁止未来剧情污染早期表演。

---

# 23. ReaderDirector 模型路线

主 Student：

```text
Qwen3.5-0.8B-Base
```

上界：

```text
Qwen3.5-2B-Base
```

Teacher：

```text
Qwen3.5-9B（量化离线推理）
```

如果 9B 本机效率不满意：

```text
4B Teacher 初筛
↓
真正 Hard Case
↓
9B
```

---

# 24. 4060 Ti 16GB 的训练原则

这张卡适合：

```text
0.8B BF16 LoRA
2B LoRA / QLoRA
9B 4bit Teacher inference
```

不优先：

```text
9B full finetune
在线 Teacher+Student GKD
超长 context
多 adapter 堆叠
```

---

# 25. 为什么 V1 不叠多 LoRA

禁止主路线：

```text
Base
+ Speaker LoRA
+ Alias LoRA
+ Emotion LoRA
+ Dialect LoRA
```

原因：

- 顺序依赖；
- adapter 干扰；
- 导出复杂；
- 版本复杂；
- Android 不需要动态组合。

采用：

```text
Base
+
ReaderDirector Multi-task LoRA
```

Stage 之间继续同一个 adapter。

不同研究方案才从 Base 分叉新 adapter 做 A/B。

---

# 26. 训练阶段

## D0 Baseline

冻结 Test Book Set。

测试：

```text
0.8B post-trained zero-shot
0.8B Base few-shot
2B post-trained
```

不训练。

## D1 Gold SFT

来源：

```text
人工
用户修正
显式规则真值
v90.7 高置信审计
```

## D2 Behavior Distillation

Teacher 对难例输出结构化 label。

## D3 Hard Mining

Student 跑整本验证小说，抓：

```text
错误
低置信
Teacher/legacy分歧
```

人工/Teacher 修正后回灌。

## D4 2B Upper Bound

判断 0.8B 是否足够。

---

# 27. 训练数据来源优先级

```text
USER_LOCKED
>
HUMAN_GOLD
>
EXPLICIT_RULE_GOLD
>
LEGACY+TEACHER AGREEMENT
>
TEACHER ONLY
>
LEGACY ONLY
```

Teacher 不是绝对真值。

---

# 28. v90.7 Exporter

新建：

```text
LegacyV907Exporter
```

导出：

```text
上下文
对话
最终speaker
角色主名
aliases
正反证据
发声音龄
临时voice state
merge/split
审计结果
用户修正
```

每条保存 provenance。

---

# 29. Dataset

按“书”划分：

```text
Train Books 70%
Val Books 15%
Test Books 15%
```

系列整体在同一 split。

不能同书切 train/test。

---

# 30. 样本协议

例如 Speaker：

```json
{
  "task": "SPEAKER",
  "context": "...",
  "scene_roles": ["r01", "r07"],
  "rule_candidates": [
    {"id": "r01", "score": 0.72},
    {"id": "r07", "score": 0.41}
  ],
  "overrides": [],
  "target": {
    "speaker": "r01",
    "confidence": "HIGH"
  }
}
```

V1 不添加特殊 tokenizer token。

用短文本协议或严格短 JSON。

---

# 31. 训练输出要短

手机 decode 成本很重要。

最终可从：

```json
{"speaker":"r01","confidence":"H"}
```

进一步压成：

```text
S=r01;C=H
```

但训练早期先保留可读 JSON，等精度稳定再做协议压缩。

---

# 32. LoRA Recipe：0.8B

以 ms-swift 当前 Qwen3.5 Dense LoRA 官方范式为参考。

起始实验：

```text
model = Qwen3.5-0.8B-Base
tuner = lora
dtype = bfloat16
rank = 16
alpha = 32
target_modules = all-linear
max_length = 2048
batch = 2
grad_acc = 8
lr = 1e-4
epoch = 2
warmup_ratio = 0.05
```

这是实验起点，不是科学常数。

正式做：

```text
rank 8 / 16 / 32
```

最小消融。

---

# 33. 4060 Ti OOM 回退顺序

0.8B：

```text
batch 2 -> 1
↓
max_length 2048 -> 1536
↓
gradient checkpoint / liger（验证后）
↓
最后才考虑 QLoRA
```

2B：

```text
batch=1
grad_acc↑
max_length=2048
```

若仍 OOM或需要 4k：

```text
QLoRA
```

训练时 4bit 基座不直接作为 Android 部署权重；最终合并到原始 Base 后再做 MNN 量化。

---

# 34. 训练环境

优先 WSL2 Ubuntu 或稳定 Linux。

Qwen3.5 当前 ms-swift 官方 Best Practice 要求较新的 transformers，并给出 FLA/causal-conv/flash-attn 可选安装路线。

第一阶段可避免把所有性能插件都装上：

```text
先跑通最小 LoRA
↓
记录 baseline
↓
再逐项启用加速
```

不要一次装十个插件导致环境难定位。

所有版本冻结：

```text
environment.yml
pip-freeze.txt
GPU_DRIVER.txt
MODEL_MANIFEST.json
```

---

# 35. 训练 smoke test

正式训练前：

```text
100 steps
```

记录：

```text
peak VRAM
allocated
reserved
tokens/s
step time
loss
grad norm
```

4060 Ti 16GB 建议保留显存余量，不把 reserved 长期顶满 16GB。

---

# 36. Teacher 蒸馏

V1 采用：

# Offline Behavior Distillation

```text
Hard Case
↓
Teacher inference
↓
结构化答案
↓
一致性/人工审查
↓
SFT Student
```

不做单卡在线 GKD。

原因：

```text
9B Teacher + Student backward
```

对 16GB 单卡不划算。

---

# 37. Teacher 输出

不保存长 Chain-of-Thought。

保存：

```text
label
confidence bucket
evidence_span
evidence_type
```

例如：

```json
{
  "speaker": "r07",
  "confidence": "HIGH",
  "evidence_span": "张明低声道",
  "evidence_type": "SPEECH_CUE"
}
```

---

# 38. Hard Example Mining

每轮 Student：

```text
run locked validation books
↓
collect errors
↓
classify
↓
teacher
↓
human review critical disagreements
↓
retrain same adapter
```

错误分类：

```text
QUOTE_NOT_SPEECH
WRONG_SPEAKER
TURN_TRACKING
PRONOUN
FALSE_ALIAS_MERGE
FALSE_ALIAS_SPLIT
GROUP_PERSON_CONFUSION
POSSESSION_CONFUSION
TEMP_VOICE_LEAK
VOICE_PHASE_WRONG
OVER_EMOTION
FALSE_DIALECT
```

---

# 39. 继续同一个 LoRA，而非叠第二个

Stage1：

```text
checkpoint-gold
```

Stage2：

```text
resume checkpoint
+ hard/silver
```

Stage3：

```text
resume
+ correction set
```

每阶段保存冻结 checkpoint。

避免多个 adapter 串联。

---

# 40. 训练数据混合

Hard-only 会过拟合。

初始可试：

```text
50% Gold/core
30% Hard
20% Silver
```

再由错误统计调整。

任务采样要平衡，不能 Emotion 样本淹没 Speaker。

---

# 41. ReaderDirector Gate

Speaker：

```text
Accuracy
Macro F1
Auto-confirm Precision
Coverage
UNKNOWN Recall
```

优先：

```text
Auto-confirm Precision >= 98%
```

Identity：

```text
False Merge Rate
False Split Rate
Pairwise F1 / B3
```

重点：

> False Merge 比 False Split 更危险。

VoiceState：

```text
Persistent Change Accuracy
Temporary Start/End F1
State Leakage Rate
```

Emotion：

```text
Label
Intensity bucket
Overacting Rate
Human preference
```

Dialect：

```text
Macro F1
False Activation Rate
Negative Evidence Override Accuracy
```

系统：

```text
Protocol Validity
Average output tokens
Latency
VRAM
```

---

# 42. 0.8B vs 2B 决策

如果 0.8B：

```text
核心指标距离2B ≤1–2个百分点
False Merge不恶化
VoiceState不恶化
```

优先 0.8B。

若 0.8B 明显输在复杂多人/Identity：

先：

```text
规则增强
ContextBuilder
Hard data
```

仍不足才迁 2B。

---

# 43. LoRA Merge 与 MNN

最佳 checkpoint：

```text
Base + LoRA
↓
merge
↓
HF merged
↓
重新跑整个 locked test
↓
MNN host
↓
MNN W8
↓
MNN W4 block64
↓
Android
```

每一级均跑任务级指标。

不以 tensor cosine 代替业务正确率。

---

# 44. CosyVoice3-MNN 改造原则

不把当前 App 当最终 ReaderVoice App。

抽：

```text
CosyVoiceEngine
CosyVoiceModelStore
VoicePackStore
PerformanceInstructionCompiler
TtsPriorityScheduler
TtsQualityGate
native runtime
```

保留原 CosyVoice3-MNN App：

```text
Reference / Benchmark App
```

用于数值和听感回归。

---

# 45. 当前 CosyVoice 主链冻结

V1 不改变：

```text
LLM
→ Conditioner
→ 2-step Flow
→ HiFT
```

不重新蒸馏 Flow。

不让未过 PCM Gate 的 HiFT NPU 进入正式版。

---

# 46. VoicePack → VoiceProfileRevision

逻辑：

```text
VoicePack
├ original_reference_audio
├ prompt_text
└ VoiceProfileRevision[]
```

Revision 保存：

```text
revision_id
cosyvoice_model_version
speaker_encoder_version
speech_tokenizer_version
frontend_version
prompt_hash

prompt_speech_tokens
prompt_condition
speaker_embedding
```

AudioAsset 必须绑定 `voice_revision_id`。

---

# 47. 用户参考音频必须保留

用户 VoicePack：

```text
source audio + transcript
```

不能只保存 embedding。

未来 frontend/encoder 升级：

```text
re-enroll
```

---

# 48. Voice Enrollment

```text
MP3/WAV/record
↓
decode
↓
VAD
↓
quality gate
↓
ASR or user transcript
↓
prompt selector
↓
CosyVoice enrollment
↓
VoiceProfileRevision
```

质量检测：

```text
speech duration
silence
clipping
noise
music
multi-speaker
reverb proxy
emotion extremeness
```

---

# 49. Realtime Prompt Selector

当前固定截前 N token 的策略不适合长期产品。

改成：

```text
reference audio
↓
VAD segments
↓
quality scoring
↓
选稳定3–5秒候选
↓
token/frame约束
↓
Realtime Revision
```

保留原始长参考用于未来重新选择。

---

# 50. CosyVoice ZERO_SHOT / INSTRUCT2

ReaderDirector 不直接自由写 instruction。

输出结构化：

```text
language
accent
emotion
intensity
speed
volume_style
narration_style
```

由：

```text
PerformanceInstructionCompiler
```

变成有限模板。

---

# 51. Voice × Instruct Compatibility Matrix

必须专项实验：

```text
20 VoicePacks
×
Neutral/Happy/Sad/Angry/Tense/Soft/Fast/Slow
×
若干普通话/方言
```

核心：

```text
CER
Speaker Similarity
duration
F0
loudness
bad generation
human MOS
```

特别看：

# Speaker Identity Retention

如果情绪/方言控制明显破坏克隆身份：

```text
减弱instruction
固定角色模式
Control Adapter
最后才TTS微调
```

---

# 52. ZERO_SHOT 与 INSTRUCT2 模式 Gate

对比：

A：

```text
neutral=ZERO_SHOT
controlled=INSTRUCT2
```

B：

```text
整章统一INSTRUCT2，neutral也有模板
```

C：

```text
RenderSequence内部锁模式
```

以：

```text
音色一致 + MOS
```

选。

不要自行发明模型没训练过的 hybrid 输入。

---

# 53. SemanticSegment != RenderUnit

SemanticSegment 是语义单位。

RenderUnit 是 TTS 单位。

连续：

```text
同speaker
同voice revision
同accent
emotion compatible
长度安全
```

可合并。

这能避免每句话都重新起韵律。

---

# 54. RenderUnit Length Profiling

CosyVoice3-MNN 当前存在长句 HiFT 非线性退化。

必须真机扫描：

```text
20
40
60
80
100
150
...中文字
```

记录：

```text
speech tokens
Flow seq
audio duration
LLM time
Flow time
HiFT time
RTF
CER
MOS
```

最终得到：

```text
preferred range
safe range
hard max
```

不拍脑袋“50字固定切”。

---

# 55. TTS API

ReaderVoice 对 TTS 只调用：

```text
TtsRequest
```

字段：

```text
job_id
generation_epoch
render_unit_id
text
voice_revision_id

performance:
  language
  accent
  emotion
  intensity
  speed
  volume

priority
deadline
output_mode
```

返回：

```text
TtsResult
audio_asset / PCM
duration
RTF
tokens
backend
QC
model_revision
```

---

# 56. Priority Scheduler

CosyVoice 内部允许单 execution slot。

外部：

```text
P0 当前/即将播放
P1 后2–3分钟
P2 后10分钟
P3 speculative
```

不能为了吞吐同时跑多个 Flow 把 GPU/RAM打爆。

---

# 57. Cooperative Cancellation

禁止杀 native 线程。

三层：

```text
QUEUED_CANCEL
COOPERATIVE_CANCEL
IN_FLIGHT_STALE
```

用户跳章：

```text
generation_epoch++
↓
旧queued取消
↓
旧in-flight标stale
↓
安全点停止
↓
旧结果即使完成也discard
```

Native 需要逐步加入：

```text
requestCancel(jobId)
```

LLM 每 token/小批检查；
Flow 两步之间检查；
HiFT 若不可安全中止，结束后丢弃。

---

# 58. Max Non-preemptible Quantum

真机测：

```text
p50/p95/p99
```

如果一个 speculative 调用不可取消 8 秒：

- chunk 太大；
- 或后端不适合作 P1/P2。

---

# 59. Playback Bootstrap

新书第一次点击播放：

```text
Fast Paragraph Recovery
↓
Rule-only Semantic
↓
已知Voice? 多角色 : provisional/narrator
↓
Bootstrap TTS
↓
PLAY
```

后台再跑完整 Director。

增加 KPI：

```text
TTFA p50
TTFA p95
```

---

# 60. Playback Commit Frontier

已经开始播放的 Paragraph revision：

```text
COMMITTED
```

不能被后台热替换。

新分析：

```text
只作用未来未播Paragraph
```

已听部分下次 replay 用新 revision。

---

# 61. 三 Frontier

```text
Playback Frontier
Acoustic Frontier
Semantic Frontier
```

保持：

```text
Playback <= Acoustic <= Semantic
```

Scheduler 的核心不是“后台多算”，而是维持安全距离。

---

# 62. Semantic/Acoustic Cache 分离

SemanticHash：

```text
text
emotion
accent
speed
pronunciation
```

SynthesisHash：

```text
SemanticHash
+ voice_revision
+ TTS version
+ frontend version
```

角色 merge 后：

- 如果 voice revision相同，不重 TTS；
- 如果不同，旧波形不能改 metadata 冒充新声音；
- 标 `ACOUSTIC_STALE`；
- 靠近播放窗口时 lazy rebuild。

---

# 63. AudioAsset 状态

```text
VALID
SEMANTIC_STALE
ACOUSTIC_STALE
RENDER_STALE
FAILED_QC
```

---

# 64. TTS Quality Gate

Cheap：

```text
NaN
duration ratio
silence ratio
RMS
clipping
repeat tail
abnormal tail
```

可疑：

```text
ASR CER
```

方言/人名场景 ASR 不能作为绝对真值。

---

# 65. 多书调度

全局：

```text
Current Book
Prewarm Book(optional)
All Others idle
```

优先：

```text
当前播放
当前书下一窗口
当前书下一章
用户手动预生成
其他书=0
```

切书立即让旧 speculative jobs stale。

---

# 66. 存储

Hot：

```text
PCM / decoded current window
```

Warm：

```text
compressed paragraph/render audio
```

Cold：

```text
NarrationIR only
```

全书不长期存 PCM。

Cache quota：

```text
current book
> recent book
> archived book
```

VoicePack raw 不由自动 cache GC 删除。

---

# 67. Job 幂等

```text
job_id
type
target_id
input_hash
generation_epoch
state
attempt
lease_until
backend
last_error
```

状态：

```text
PENDING
RUNNING
DONE
FAILED
CANCELLED
STALE
```

Audio 写入：

```text
.tmp
↓
complete
↓
checksum
↓
atomic rename
↓
DB DONE
```

---

# 68. Model Residency

```text
UNLOADED
MMAPPED
SESSION_CREATED
WARMING
READY
HOT
```

Warm-up 用代表性最小输入，不 touch 全部权重页。

UI 不冻结。

已有音频时播放器继续工作。

---

# 69. 8 GB 模式

默认：

```text
Director Batch
↓
释放/降驻留
↓
TTS Batch
```

ASR 仅 Enrollment/QC 按需。

不假设：

```text
Director + CosyVoice + ASR
```

全常驻。

---

# 70. Android 后台策略

核心承诺：

> 当前播放窗口一定优先。

不承诺：

> 锁屏后无限生成完整书库。

所有远期工作：

```text
best effort
checkpointable
```

---

# 71. Android Model Manager

模型包：

```text
Director
CosyVoice runtime
Enrollment optional
ASR optional
```

每个：

```text
model_id
version
size
sha256
min_app_version
device_profile
```

下载：

```text
.partial
↓
sha256
↓
staging
↓
smoke test
↓
active switch
```

旧模型在新模型验证前不删。

---

# 72. Voice Revision / Synthesis Epoch

模型升级后：

```text
voice_revision_v2 available
```

不立即让当前小说跳版本。

书/章节绑定：

```text
SynthesisEpoch
```

同一章尽量同一 voice revision。

---

# 73. Android UI

必须：

```text
Library
Book Reader
Character Studio
Voice Studio
Problem Inbox
Model Manager
Storage Manager
Settings
```

Problem Inbox：

```text
UNKNOWN
Alias conflict
TTS QC fail
Pronunciation
Dialect uncertainty
VoiceState conflict
```

---

# 74. Correction Compiler

用户修正：

```text
CorrectionEvent
↓
OverrideCompiler
↓
BookOverrideRule
↓
Rule Engine
```

支持：

```text
ENTITY_EQ
ENTITY_NEQ
SPEAKER_ASSIGN
SPEAKER_BLOCK
PARAGRAPH_JOIN
PARAGRAPH_SPLIT
PRONUNCIATION
ACCENT_FORCE
ACCENT_BLOCK
VOICE_BIND
ROLE_LOCK
SKIP_TEXT
```

`USER_LOCKED` > AI。

---

# 75. Agent 实验纪律

任何性能/模型实验：

1. 先冻结 baseline；
2. 一次只改主要变量；
3. 固定数据 split；
4. 固定 seed；
5. 不用 Test Set 调参；
6. 产物落到 `runs/<run-id>/`；
7. 保存 manifest；
8. 保存 metrics；
9. 保存失败；
10. 只有 Gate 通过才能修改 PROJECT_STATE 的正式结论。

---

# 76. 每个 run 至少

```text
run.json
metrics.json
command.txt
environment.txt
report.md
```

模型实验再加：

```text
checkpoint metadata
dataset digest
git commit
```

TTS 实验再加：

```text
reference.wav
candidate.wav
PCM metrics
RTF
backend trace
```

---

# 77. “能跑”不等于“通过”

尤其 TTS/NPU：

不能因为：

```text
build成功
so加载
tensor corr高
速度快
```

就宣布成功。

必须到：

```text
最终 PCM finite
非静音
内容正确
speaker identity
听感
RTF
```

全部 Gate。

这是现有 CosyVoice3-MNN 已证明的重要经验。

---

# 78. Agent Definition of Done

一次任务完成必须同时满足：

```text
代码完成
测试完成
相关 Gate 运行
失败明确记录
没有破坏用户数据
没有无关大改
文档/状态更新
```

最终 Agent 回复必须汇报：

```text
改了什么
为什么
跑了哪些命令
哪些测试通过
哪些没跑及原因
产物路径
剩余风险
```

不能只说“已完成”。

---

# 79. Agent 禁止行为

```text
禁止删除用户未提交改动
禁止 git reset --hard 清理问题
禁止随意删除 research/runs/models
禁止把 secrets 写进仓库
禁止为了过测试降低 Gate
禁止用 GT/Test Set 做训练调参
禁止把未知结果写成确定事实
禁止在音频质量未过时只报速度成功
禁止修改整书源文本来“修复”解析
禁止在没有证据时强制 Alias Merge
```

---

# 80. AGENTS.md 本身的完成 Gate

运行 Agent 之前人工/自动检查：

```text
根 AGENTS.md 存在
每个关键模块局部 AGENTS.md 存在
不存在相互冲突的根/局部规则
命令真实存在
路径真实存在
没有 secrets
没有巨量重复 README
```

Agent 每次发现命令/路径已经过期：

```text
先验证
→ 更新最近AGENTS
```

不能继续传播错误命令。

---

# 81. 开工 Milestone 0

交付：

```text
AGENTS.md
5个局部AGENTS.md
PLANS.md
TASK_TEMPLATE.md
PROJECT_STATE.md
DECISIONS.md
```

Gate：

```text
新Agent只读这些文件
能正确描述：
项目目标
核心不变量
当前Baseline
如何测试
什么不能做
```

---

# 82. Milestone 1：TXT Structural Compiler

完成：

```text
Decoder
PhysicalLine
Chapter Candidate
TOC
Volume
Layout Profile
Paragraph Recovery
Source Map
```

Gate：

```text
30本TXT
Parser指标达标
无source offset破坏
300万字内存稳定
```

---

# 83. Milestone 2：Character System

完成：

```text
Mention
Entity
Identity Evidence
Merge/Split
Scene
Embodiment
VoiceState
Override
```

Gate：

- 模拟误 merge 可完整 split；
- UNKNOWN 后续可绑定；
- 用户锁定不被模型覆盖；
- Identity 与 Voice 分离。

---

# 84. Milestone 3：ReaderDirector 数据

完成：

```text
v90.7 exporter
Gold
Silver
Hard
book-level split
eval harness
```

先不训练。

Gate：

```text
每条 provenance可追踪
无 test book 泄漏
```

---

# 85. Milestone 4：0.8B SFT

4060 Ti：

```text
BF16 LoRA
2048
rank16
```

先 smoke，后正式。

Gate：

```text
高置信speaker precision
false merge
protocol validity
```

不够：

```text
先数据/上下文
后模型尺寸
```

---

# 86. Milestone 5：蒸馏

```text
4B/9B teacher
↓
only hard cases
↓
agreement/human audit
↓
same LoRA continual SFT
```

每轮必须和旧 checkpoint A/B。

---

# 87. Milestone 6：2B Upper Bound

只回答：

> 0.8B 是否值得换成 2B？

若 2B只小幅提升：

```text
不进手机
```

---

# 88. Milestone 7：MNN Director

逐级：

```text
HF
MNN PC
Android CPU
Android GPU/NPU
```

任务级一致性 Gate。

---

# 89. Milestone 8：CosyVoice Engine

从现有仓库抽核心。

第一版保留可靠 pipeline。

新增：

```text
TtsRequest
Priority
Epoch
Cancel
VoiceRevision
QualityGate
```

不急着 memory-only pipeline。

---

# 90. Milestone 9：Voice / Instruct Matrix

这是 ReaderVoice 多角色质量生死点。

必须出正式报告：

```text
voice identity
emotion
accent
content
```

不通过才进入 TTS 微调研究。

---

# 91. Milestone 10：Render / Playback

完成：

```text
RenderPlanner
PausePlanner
Loudness
Timeline
Media3
Semantic bookmark
Playback Commit Frontier
```

书签保存：

```text
segment_id + intra_segment_offset
```

而不是仅毫秒。

---

# 92. Milestone 11：Scheduler

模拟：

```text
正常连续播放
跳20章
切书
锁屏
杀进程
重启
模型切换
低空间
高温
```

无状态丢失。

---

# 93. Milestone 12：Final Android Gate

8GB 目标设备：

```text
飞行模式
300万字TXT
连续60min
当前窗口生成
跳章
换角色Voice
Merge
Pronunciation override
kill/restart
```

必须：

```text
无OOM
无LMK导致功能中断
无buffer underrun
无native crash
角色声音不乱跳
```

---

# 94. 性能指标

系统：

```text
TTFA p50/p95
TTS RTF p50/p95
Director latency
Peak PSS
thermal state
battery
buffer underrun
cache hit
```

AI：

```text
LLM utilization = LLM处理字符 / 全书字符
```

目标：

> 规则覆盖越高，在精度不掉的前提下 LLM utilization 越低越好。

---

# 95. Correction Cost

重要新指标：

```text
RebuildAmplification =
重生成音频秒数 / 被修正文本对应音频秒数
```

避免一次 Alias 修正导致全书雪崩。

---

# 96. 失败回退树

ReaderDirector：

```text
0.8B差
→ context/rules
→ hard data
→ distillation
→ 2B
```

TTS：

```text
NPU质量差
→ CPU/GPU
```

Voice instruct：

```text
speaker drift
→ weaker control
→ consistent mode
→ Control Adapter
→ TTS finetune
```

8GB：

```text
并发不足
→ Director/TTS分时
→ ASR按需
→ shorter context
→ smaller prefetch
```

---

# 97. 什么时候才训练 CosyVoice

只有出现明确 Gate 失败：

```text
INSTRUCT2造成稳定speaker drift
情绪控制不足
口音控制不足
ReaderDirector控制无法映射
```

才建立 TTS 训练任务。

第一阶段不动主权重。

可研究顺序：

```text
instruction template
↓
control adapter
↓
small LoRA
↓
更深微调
```

Flow/HiFT 不因为“想创新”就重训。

---

# 98. 未来研究线：ReaderDirector → CosyVoice hidden control

V1：

```text
NarrationIR discrete controls
```

V2：

```text
ReaderDirector contextual hidden
↓
small adapter
↓
CosyVoice condition
```

冻结两个大模型，只训练 adapter。

比较：

```text
labels only
vs
labels + hidden
```

指标：

```text
speaker similarity
CER
emotion appropriateness
MOS
```

有明确收益再继续。

---

# 99. 正式开工第一条 Agent Prompt

建议首次启动 Coding Agent：

```text
Read the repository root AGENTS.md and docs/agent/PLANS.md first.
Do not implement product features yet.
Complete TASK-000 only:
1. verify the proposed repository layout,
2. create or correct the root and module AGENTS.md files,
3. create PROJECT_STATE.md and DECISIONS.md,
4. create an ExecPlan for TASK-010 TXT Source/Encoding,
5. run only checks relevant to documentation/schema bootstrap.
Do not modify CosyVoice model code in this task.
```

随后才让 Agent进入 Parser。

---

# 100. 最终完成定义

ReaderVoice-Mobile v1.0 真正成功，不是“LLM 和 TTS 都能运行”。

而是：

> 用户导入一个真实、格式混乱、数百万字的 TXT，数秒级进入可播放准备状态；系统能够恢复章节与段落，长期跟踪角色身份和声音；规则解决确定性问题，专项 ReaderDirector 只处理困难语义；CosyVoice3-MNN 根据 VoicePack 和结构化表演参数按需生成音频；当前播放永远优先，用户任何修正都能局部传播；多本书不会在后台全部计算；手机在飞行模式下长时间工作且不崩。开发过程由 AGENTS.md + ExecPlan + 冻结 Gate 约束，任何 Agent 都能够从仓库本身恢复上下文并继续开发。

---

# 101. 当前应冻结的技术选择

| 模块 | V1 |
|---|---|
| 主输入 | TXT |
| 次输入 | EPUB |
| Parser | Rules + statistics |
| DB | Room/SQLite |
| Director | Qwen3.5-0.8B-Base + Multi-task LoRA |
| Upper bound | Qwen3.5-2B |
| Teacher | Qwen3.5-4B/9B offline |
| 训练卡 | RTX 4060 Ti 16GB |
| 训练方式 | BF16 LoRA 优先；2B必要时QLoRA |
| 蒸馏 | Offline behavior distillation |
| Android LLM | MNN |
| TTS | Nian27/CosyVoice3-MNN core |
| TTS训练 | V1 不做 |
| Voice | VoicePack + VoiceProfileRevision |
| LLM/TTS融合 | NarrationIR |
| Audio | RenderUnit → Timeline |
| 播放 | Media3 |
| Agent规范 | Root + nested AGENTS.md + ExecPlan |
| 长任务 | docs/agent/PLANS.md |

---

# 102. 参考资料（2026-08-12 核验）

- OpenAI Codex：Custom instructions with AGENTS.md
  https://developers.openai.com/codex/agent-configuration/agents-md
- OpenAI：Using PLANS.md for multi-hour problem solving
  https://developers.openai.com/cookbook/articles/codex_exec_plans
- AGENTS.md open format
  https://agents.md/
- openai/codex AGENTS.md
  https://github.com/openai/codex/blob/main/AGENTS.md
- vercel/next.js AGENTS.md
  https://github.com/vercel/next.js/blob/canary/AGENTS.md
- microsoft/vscode AGENTS.md
  https://github.com/microsoft/vscode/blob/main/AGENTS.md
- Nian27/CosyVoice3-MNN
  https://github.com/Nian27/CosyVoice3-MNN
- QwenAudio/CosyVoice
  https://github.com/QwenAudio/CosyVoice
- ms-swift Qwen3.5 Best Practices
  https://github.com/modelscope/ms-swift/blob/main/docs/source_en/BestPractices/Qwen3_5-Best-Practice.md
- Qwen3.5-0.8B-Base / 2B-Base
  https://huggingface.co/Qwen/Qwen3.5-0.8B-Base
  https://huggingface.co/Qwen/Qwen3.5-2B-Base
