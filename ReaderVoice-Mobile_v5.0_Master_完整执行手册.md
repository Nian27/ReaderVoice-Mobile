# ReaderVoice-Mobile v5.0 Master Manual
# 端侧多角色长篇有声书系统：从真实 TXT 到可用 Android APP 的完整执行手册
## 含：TXT/目录/段落恢复、多角色 v90.7 迁移、ReaderDirector LoRA/蒸馏、RTX 4060 Ti 16GB 训练、CosyVoice3-MNN 改造、VoicePack、缓存/调度/播放、AGENTS.md/ExecPlan

**文档定位：项目总源文档（Master Source of Truth）**  
**版本：v5.0**  
**日期：2026-08-12**  
**PC 开发机：NVIDIA RTX 4060 Ti 16GB**  
**移动端目标：Android ARM64；优先 Snapdragon 8 Gen 3 / Snapdragon 8 Elite；8GB RAM 为最低完整体验工程目标**  
**已有工程资产：目录 Regex、多角色朗读 v90.7、Nian27/CosyVoice3-MNN**

---

# 0. 为什么重新做 v5

v4 把很多细节拆到了 `AGENTS.md / PLANS.md / DECISIONS.md` 中，这对 Coding Agent 的上下文管理是正确的，但导致“总执行手册”本身比 v2/v3 更短。

v5 改变这一策略：

- `AGENTS.md`：继续保持短、精确、只放 Agent 必须遵守的不变量；
- `PLANS.md`：继续负责复杂任务的动态执行计划；
- **本 Master Manual：必须单文件闭环，任何一个新 Agent/开发者即使没有看过此前聊天，也应能理解为什么这么做、怎么做、数据怎么存、实验怎么跑、失败怎么办、最终怎么验收。**

因此本手册内容只允许“去重后更完整”，不允许因为模块化而缩水。

---

# 1. 项目最终要做什么

项目名：

# ReaderVoice-Mobile

它不是：

```text
小说 → 普通 TTS → WAV
```

也不是：

```text
整本书 → 大模型 Agent → CosyVoice
```

而是：

> **一个完全端侧、有状态、可增量编译、可局部修正、可版本迁移的多角色有声书编译系统。**

用户最终看到的体验：

```text
导入 TXT
  ↓
数秒级识别书籍/章节
  ↓
自动恢复被硬换行破坏的真实段落
  ↓
很快开始播放
  ↓
旁白与主要角色自动使用不同声音
  ↓
后台继续分析角色、别名、情绪和后续章节
  ↓
用户可给任意角色换自定义 VoicePack
  ↓
只局部重新生成受影响音频
```

项目不是广播剧制作器，第一代目标是：

- 小说文本忠实；
- 多角色稳定；
- 人物不串；
- 语气适度；
- 手机可持续；
- 用户可修正；
- 出错可回滚。

---

# 2. 最终系统的五条总原则

## 2.1 Rule First，LLM 只处理残差

确定性强的问题：

```text
章节标题
段落硬换行
“张三说道”
已锁定 Alias
用户修正
数字/标点归一化
```

都由程序解决。

LLM 处理：

```text
多人省略主语对白
复杂指代
人物是否同一身份
附身/控制
情绪
方言证据
临时换声
```

目标不是让 LLM 越忙越好，而是：

```text
在精度不降低的情况下
LLM Utilization 越低越好
```

---

## 2.2 数据库是记忆，LLM 不是记忆

LLM 不“记住整本书”。

真正状态在：

```text
Room / SQLite
```

模型只接受当前任务所需子集：

```text
当前段
最近上下文
当前场景人物
相关 Alias/Identity
用户 Override
必要历史状态
```

---

## 2.3 Character != Voice

角色是小说语义对象：

```text
谁
```

VoicePack 是声学对象：

```text
用什么声音
```

二者只能通过绑定关系关联。

---

## 2.4 语义状态和声学产物分开

```text
NarrationIR
```

描述“应该怎么读”。

```text
AudioAsset
```

描述“某模型、某音色、某版本实际合成了什么”。

这样才能支持：

- 换声音；
- 升模型；
- 修 Alias；
- lazy rebuild。

---

## 2.5 当前播放永远优先于未来生成

运行时优先级：

```text
当前音频连续播放
>
即将播放内容生成
>
当前书后续分析
>
未来章节预生成
>
其他书
```

---

# 3. 现有三个资产如何使用

# 3.1 现有目录 Regex

用户已有多种规则，例如：

```text
章节1：拉斯维加斯
#1「图文」被女友
03：祸国殃民的表弟
12
一百七十
1、人参公鸡
二百二十章 boy next door
```

这些规则不丢弃。

迁移为：

```text
LegacyChapterRulePack
```

但 Regex 只负责：

```text
候选产生
```

最终章节由全局 Resolver 决定。

---

# 3.2 现有多角色 v90.7

v90.7 已经解决了大量真实网文问题：

- 对话块是否实际发声；
- Speaker；
- 别名正/反证据；
- 共现；
- 模型关系证据；
- Alias 审计；
- Merge/Split；
- 发声音龄；
- 临时伪装/换声；
- 固定音色；
- 跨章状态恢复；
- 多 API 投票；
- 失败保护。

v5 的原则：

> **能力语义迁移，历史补丁代码不照搬。**

需要从 v90.7 提取：

```text
数据
错误案例
用户修正
状态机定义
证据类型
Hard Case
```

---

# 3.3 CosyVoice3-MNN

截至当前仓库主线，它已经是一个较完整的 Android 本地 CosyVoice3 工程：

```text
LLM
→ Conditioner
→ 2-step Flow
→ HiFT
```

当前已验证事实必须作为基线冻结：

- MNN 3.6.1；
- SM8850 上仅第 0 层 q_proj 的受限 NPU 路径可保留；
- 整 LLM NPU 会出现 Token 坍缩，不作为正式默认；
- Flow 支持 CPU/OpenCL；
- HiFT 正式仍以 CPU 路径为主；
- Flow 已做 10-step → 2-step 蒸馏；
- 真机热态 RTF 已能接近实时，但仍有冷启动与长句问题；
- HiFT QNN/HTP 虽速度好，但最终 PCM 质量尚未过正式 Gate。

ReaderVoice V1：

> **重构 CosyVoice3-MNN 为 Engine，不重新训练 TTS 主权重。**

---

# 4. 最终模块架构

```text
ReaderVoice-Mobile
│
├─ StructuralIndexCompiler
│  ├─ EncodingDetector
│  ├─ PhysicalLineScanner
│  ├─ ChapterRuleEngine
│  ├─ TOCDetector
│  ├─ VolumeResolver
│  ├─ LayoutProfiler
│  └─ ParagraphRecovery
│
├─ SemanticCompiler
│  ├─ RuleEngine
│  ├─ UtteranceClassifier
│  ├─ SpeakerCandidateGenerator
│  ├─ ReaderDirector
│  └─ NarrationCompiler
│
├─ CharacterSystem
│  ├─ MentionStore
│  ├─ NarrativeEntityStore
│  ├─ IdentityEvidenceGraph
│  ├─ SceneState
│  ├─ EmbodimentState
│  └─ VoiceState
│
├─ VoiceSystem
│  ├─ VoicePackStore
│  ├─ VoiceProfileRevision
│  ├─ VoiceMatcher
│  └─ Enrollment
│
├─ TtsCosyVoice
│  ├─ CosyVoiceEngine
│  ├─ PerformanceInstructionCompiler
│  ├─ TtsPriorityScheduler
│  ├─ QualityGate
│  └─ Native Runtimes
│
├─ AudioRenderer
│  ├─ RenderPlanner
│  ├─ PausePlanner
│  ├─ Loudness
│  └─ ParagraphTimeline
│
├─ RuntimeScheduler
│  ├─ PlaybackFrontier
│  ├─ AcousticFrontier
│  ├─ SemanticFrontier
│  ├─ ModelResidency
│  └─ ThermalController
│
├─ Data
│  ├─ Room
│  ├─ Files
│  └─ Cache
│
└─ Android App
```

---

# 5. 多本书生命周期

每本书有状态：

```text
DISCOVERED
IMPORTED
LIGHT_INDEXED
ACTIVE
SEMANTIC_READY_WINDOW
AUDIO_READY_WINDOW
ARCHIVED
```

## 5.1 DISCOVERED

只有：

```text
URI
文件名
文件大小
时间
```

## 5.2 IMPORTED

复制/持久化到 App 管理存储后：

```text
文件 hash
编码信息
Book ID
```

## 5.3 LIGHT_INDEXED

允许所有书执行：

```text
目录
章节
物理行索引
轻人物候选
全文倒排
```

不跑：

```text
ReaderDirector
CosyVoice
```

## 5.4 ACTIVE

只允许：

```text
Current Book = 1
Prewarm Book = 0~1
```

## 5.5 ARCHIVED

保留：

```text
角色库
用户修正
NarrationIR
Voice绑定
```

可删：

```text
大体积 Audio Cache
```

---

# 6. 文件导入

V1 优先：

```text
TXT
```

V1.5：

```text
EPUB
```

不建议 V1 做 PDF。

Android 使用 SAF：

```text
ACTION_OPEN_DOCUMENT
```

导入后复制到 App-managed storage。

保存：

```text
original_uri
local_copy_path
sha256
source_size
import_time
```

不要完全依赖外部 URI。

---

# 7. TXT Encoding

支持：

```text
UTF-8
UTF-8 BOM
GBK
GB18030
UTF-16LE
UTF-16BE
```

检测顺序：

```text
BOM
↓
严格 UTF-8 decode
↓
GB18030/UTF16 候选评分
↓
用户 Override
```

候选评分因素：

```text
replacement char 数量
汉字比例
常见中文标点
NUL分布
非法字符
```

保存：

```text
detected_charset
charset_confidence
charset_user_locked
```

---

# 8. PhysicalLine 与原文 SourceMap

绝不能：

```text
readLine().trim()
```

然后才分析。

必须：

```text
raw bytes
↓
decode
↓
raw PhysicalLine
↓
提取空格/缩进/布局
↓
normalized view
```

`PhysicalLine`：

```text
line_id
book_id
line_no

source_start_byte
source_end_byte
source_start_char
source_end_char

raw_text

leading_ascii_spaces
leading_fullwidth_spaces
leading_tabs
trailing_spaces

char_count
han_count

punctuation_mask
quote_state

is_blank
is_separator
is_chapter_candidate
```

---

# 9. ChapterRule 数据结构

用户现有 JSON 规则不废弃。

兼容层：

```text
LegacyChapterRule
```

新结构：

```text
ChapterRule
{
  rule_id
  name
  regex
  family
  target_type
  priority
  scope
  enabled

  confidence_base

  serial_parser
  title_parser

  positive_examples[]
  negative_examples[]

  user_locked
}
```

`target_type`：

```text
CHAPTER
VOLUME
PREFACE
AFTERWORD
EXTRA
TOC_ENTRY
```

`scope`：

```text
GLOBAL
BOOK
SERIES
```

---

# 10. 章节规则族

不要超级 Regex。

规则族：

```text
STANDARD_ZH
ZH_CHAPTER_WORD
ARABIC_PREFIX
HASH_NUMBER
PURE_NUMBER
SPECIAL_TITLE
VOLUME
ENGLISH_CHAPTER
CUSTOM
```

例如：

## STANDARD_ZH

```text
第一章
第一章 标题
第 12 章：标题
第二百二十回
```

## HASH

```text
#1
# 1
#12「标题」
```

## ARABIC

```text
03：标题
003. 标题
12、标题
```

## PURE

```text
12
一百七十
```

纯数字风险最高，不能单靠 Regex。

---

# 11. ChineseNumeralParser

独立模块，统一：

```text
一 -> 1
十二 -> 12
一百零八 -> 108
两千三百 -> 2300
壹佰贰拾 -> 120
〇一二 -> 12
0012 -> 12
```

解析失败：

```text
serial_value = null
```

不强猜。

---

# 12. Chapter Candidate 两遍处理

## Pass A：Raw Candidate Scan

在 paragraph reflow 前，对 PhysicalLine 扫：

```text
所有 ChapterRule
```

产生：

```text
ChapterCandidate
```

## Pass B：Global Structure Resolution

利用全书：

```text
序号连续性
候选间正文长度
规则族稳定性
卷重置
标题长度
候选密度
TOC区域
邻接空行
```

计算最终分。

---

# 13. TOC Detector

典型：

```text
目录
第一章 A
第二章 B
第三章 C
...
```

如果候选：

```text
非常密集
候选间正文接近0
连续几十条
```

标为：

```text
TOC_BLOCK
```

TOC Entry 可以与正文 Chapter 做：

```text
title similarity
serial match
```

帮助发现正文漏掉的标题。

---

# 14. 卷/章层级

必须：

```text
Book
└ Volume
  └ Chapter
```

因为：

```text
第一卷
第一章
第二章

第二卷
第一章
```

章号重置是正常的。

不能让 sequence checker 误判。

---

# 15. Paragraph Recovery 的核心定义

原始：

```text
PhysicalLine
```

与：

```text
LogicalParagraph
```

彻底分开。

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

每个 Boundary 保存：

```text
confidence
evidence[]
rule_version
```

---

# 16. 典型硬换行

原 TXT：

```text
张三推开门，看见李雪还坐
在桌边，皱了皱眉说道：“你
怎么还没回去？”
```

应：

```text
3 PhysicalLine
→ 1 LogicalParagraph
```

而：

```text
张三说道：
“你来了。”
```

不一定物理合并。

可保留两段并建立：

```text
CrossParagraphLink = SPEECH_CUE
```

---

# 17. LayoutProfile

分类：

```text
PARAGRAPH_PER_LINE
FIXED_WIDTH_HARD_WRAP
BLANK_LINE_PARAGRAPH
INDENTED_PARAGRAPH
MIXED
SCRIPT_DIALOGUE
UNKNOWN
```

统计：

```text
median_line_length
line_length_modes
line_length_std
blank_ratio
indent_ratio
terminal_punctuation_ratio
quote_cross_line_ratio
```

Profile 分层：

```text
BookProfile
→ ChapterProfile
→ LocalWindowProfile
```

因为同一本书不同来源段落可能格式不同。

---

# 18. Hard Wrap 判断特征

两个相邻行：

```text
prev_length
next_length
distance_to_wrap_mode
prev_terminal_punctuation
next_indent
blank_gap
quote_stack
bracket_stack
chapter_anchor
separator
next_starts_speaker
next_starts_quote
```

输出：

```text
P(SOFT_WRAP)
P(HARD_BREAK)
P(AUTHOR_BREAK)
```

开发初始可设置：

```text
>=0.95 auto confirm
0.75~0.95 provisional
<0.75 uncertain
```

最终必须由 Gold Set 校准。

---

# 19. 一行拆多段

例如：

```text
　　张三回到了家。　　李雪还没睡。
```

允许：

```text
1 PhysicalLine
→ 2 LogicalParagraph
```

分割点必须保存原始 offset。

---

# 20. 中文 JoinPolicy

硬换行拼接不能默认加空格。

```text
HAN + HAN -> ""
ASCII_WORD + ASCII_WORD -> " "
NUMBER + NUMBER -> ""
HAN + ASCII -> context rule
```

例如：

```text
张三看着
李雪。
```

→

```text
张三看着李雪。
```

不是：

```text
张三看着 李雪。
```

---

# 21. Quote Stack

不要只数：

```text
“ ”
```

维护：

```text
quote_type
depth
open_state
parent
```

支持：

```text
“”
「」
『』
""
''
```

以及嵌套。

---

# 22. SpecialBlock

识别：

```text
PROSE
POETRY
LYRICS
SCRIPT
MESSAGE_LOG
LIST
SEPARATOR
LETTER
SCREEN_TEXT
BOILERPLATE
```

例如诗歌：

```text
床前明月光
疑是地上霜
```

必须保留行。

---

# 23. Boilerplate

网文：

```text
最新网址
请收藏
本章未完
手机用户请访问
```

不删除原文。

标：

```text
SKIP_READ
```

检测：

```text
URL/domain
章节首尾重复
模板长句
用户规则
```

避免高频正常短句误删。

---

# 24. Parser Gold Set

至少 20–30 本真实 TXT。

应故意覆盖：

- 正常一行一段；
- 20 字硬换行；
- 40 字硬换行；
- 每行后空行；
- 无空行；
- 全角缩进；
- 引号跨行；
- 第一人称；
- 聊天；
- 诗歌；
- 广告；
- 超长单行；
- 目录重复；
- 混合格式。

人工标签：

```text
JOIN
BREAK
AUTHOR_BREAK
SPECIAL_BLOCK
CHAPTER
TOC
```

---

# 25. Parser 指标

```text
Chapter Boundary Precision/Recall/F1
TOC False Positive Rate

JOIN Precision
JOIN Recall
BREAK Precision
BREAK Recall

LogicalParagraph Exact Match

SpeakerCueBreakError
Paragraph-Induced Speaker Error Rate
```

最后两个非常重要。

---

# 26. LogicalParagraph Revision

段落恢复不是永久真理。

结构：

```text
logical_paragraph_id
revision_id
source_spans[]
normalized_text

boundary_confidence
reason
parent_revision
```

reason：

```text
AUTO_REFLOW
USER_JOIN
USER_SPLIT
RULE_UPDATE
SOURCE_UPDATE
```

---

# 27. 用户段落修正

用户：

```text
合并这两段
```

产生：

```text
ParagraphBoundaryOverride
priority=USER_LOCKED
```

后续 Parser 升级也不能覆盖。

只 invalidate：

```text
该 Paragraph
相关 Segment
相关 RenderUnit
相关 ParagraphTimeline
```

不整书重算。

---

# 28. SemanticSegment 类型

最终：

```text
NARRATION
DIALOGUE
INNER_MONOLOGUE
GROUP_SPEECH
QUOTE
LETTER
MESSAGE
SCREEN_TEXT
SYSTEM_TEXT
UNKNOWN
```

---

# 29. 一段中可有多个 Segment

例如：

```text
张明推开门，说：“你来了。”李雪抬头：“嗯。”
```

拆：

```text
S1 NARRATION
张明推开门，说：

S2 DIALOGUE 张明
你来了。

S3 NARRATION
李雪抬头：

S4 DIALOGUE 李雪
嗯。
```

---

# 30. Character 数据结构：不要只有 Character

正式：

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

---

# 31. EntityType

```text
PERSON
GROUP
NARRATOR
SYSTEM
NON_PERSON_AGENT
UNKNOWN
```

“众人”“二老”“护卫队”不能并成某个普通人。

---

# 32. Mention

每一次文本出现：

```text
mention_id
surface_text
segment_id
source_span

candidate_entity_ids[]
confidence
```

这样 Alias Merge/Split 才能回滚。

---

# 33. Identity Evidence Graph

替代简单 Alias Map。

边类型：

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
ACTION_RELATION
UNKNOWN
```

每条证据：

```text
source_segment_id
positive_or_negative
confidence
reason_code
model_version
rule_version
revision
```

---

# 34. Alias 正负证据

例如：

```text
张教授 == 张明
```

正证据：

```text
前文明确“张明张教授”
```

负证据：

```text
张教授和张明同场对话
```

不能只累计正证据。

---

# 35. Group 与 Person 禁止自动互并

这是 v90.7 的真实经验。

例如：

```text
众女
二老
师徒二人
```

禁止直接：

```text
= 某一个角色
```

除非有明确文本证据。

---

# 36. Relationship != Alias

```text
师父
妈妈
老师
老婆
```

默认是关系 Mention。

必须依赖：

```text
speaker context
scene
owner/relative
```

不能成为全局 Alias。

---

# 37. Embodiment

复杂网文：

```text
附身
夺舍
分魂
傀儡
控制身体
```

必须区分：

```text
Identity
Body
Surface Mention
Actual Speaker
```

例如：

```text
林雪身体
被魔尊控制
```

实际：

```text
speaker_identity = 魔尊
embodiment = 林雪身体
```

Voice 应跟真正发声主体/文本明确状态走，而不是表面身体名。

---

# 38. UNKNOWN

不知道：

```text
UNKNOWN_SPEAKER_003
```

而不是强行：

```text
黑衣男子
青年男人
神秘人
```

后文：

```text
UNKNOWN_003 -> 张明
```

即可。

UNKNOWN 应有：

```text
provisional_voice_slot
```

但不立即成为永久 Character。

---

# 39. Character 生命周期

```text
CANDIDATE
PROVISIONAL
CONFIRMED
MERGED
SPLIT
RETIRED
```

所有合并必须：

```text
可回滚
保留 Mention
保留 Evidence
```

---

# 40. Character Importance

计算：

```text
mention_count
dialogue_count
chapter_span
scene_centrality
graph_centrality
```

得到：

```text
MAIN
SUPPORTING
EPISODIC
EXTRA
```

LLM 可以补充，但不能独裁。

---

# 41. VoiceState 三层

```text
BaseVoice
VoicePhase
TemporaryVoiceOverride
```

## BaseVoice

长期基础音色。

## VoicePhase

```text
少年
青年
中年
老年
```

## Temporary

```text
伪装
模仿
压嗓
变声
```

---

# 42. Character Age != Voice Age

保存：

```text
chronological_age
appearance_age
voice_age
```

例如：

```text
活3000年
外观青年
声音青年
```

VoiceMatcher 看 `voice_age`。

---

# 43. Temporary Voice State

旧系统按“用户顺序读过上一章”恢复，不适用于新系统。

改为：

```text
TemporalStateInterval
```

例如：

```text
role=张三
start=Ch10:S42
end=Ch12:S18
override=old_man
```

用户直接跳到 Ch11 也能正确查询。

---

# 44. 用户固定声音

简化成：

```text
VoiceBinding
{
  role_id
  voice_revision_id
  lock_mode
}
```

lock：

```text
AUTO
USER_SELECTED
USER_LOCKED
```

---

# 45. VoicePack

逻辑对象：

```text
voice_id
display_name

raw_reference_audio
prompt_text

source_type
user_tags

created_at
```

其下是：

```text
VoiceProfileRevision[]
```

---

# 46. VoiceProfileRevision

必须绑定：

```text
revision_id
voice_id

cosyvoice_model_version
speaker_encoder_version
speech_tokenizer_version
frontend_version

prompt_hash
prompt_speech_tokens
prompt_condition
speaker_embedding

quality_score
created_at
```

AudioAsset 绑定：

```text
voice_revision_id
```

不是只绑 voice_id。

---

# 47. Voice 原始音频必须保留

对用户创建音色：

```text
source.wav/mp3
+
prompt_text
```

必须保留。

模型升级时：

```text
re-enroll
```

而不是旧 embedding 永久锁死。

---

# 48. Voice Enrollment

```text
音频
↓
decode/resample
↓
VAD
↓
质量分析
↓
选参考片段
↓
ASR / 用户输入 transcript
↓
用户确认
↓
CosyVoice frontend
↓
VoiceProfileRevision
```

---

# 49. Prompt Selector

不能只截“前125 tokens”。

更合理：

```text
整段参考
↓
VAD
↓
候选片段
↓
评分
```

评分因素：

```text
语音占比
SNR
clipping
reverb
情绪极端度
说话速度
多人概率
```

选最稳定的 3–5 秒作为 realtime prompt 候选。

---

# 50. VoiceMatcher

不是简单：

```text
男青年 -> 随机找男青年音色
```

VoiceProfile：

```text
gender_style
voice_age
pitch
brightness
dialect_capability
expressiveness
quality
embedding
```

角色：

```text
gender_style
voice_age
personality
importance
dialect_need
```

优化：

```text
角色适配
+
主要角色间区分度
+
复用惩罚
```

---

# 51. ReaderDirector 定位

它不是聊天模型。

定义：

> **规则残差上的小说语义 Director。**

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

一个共享模型。

---

# 52. 不让 ReaderDirector 做什么

禁止把：

```text
目录识别
硬换行恢复
明确“张三说道”
已锁 Alias
数字转中文
音频拼接
```

交给 LLM。

---

# 53. ReaderDirector Input

典型：

```text
TASK=SPEAKER

Current:
“你怎么知道？”

Recent:
...

Scene:
r01 张三
r07 李雪
r09 王教授

Rules:
r01 score=0.72
r07 score=0.22

Overrides:
none

Identity:
relevant only
```

输出：

```json
{
  "speaker":"r01",
  "confidence":"HIGH"
}
```

---

# 54. Context Builder

通常：

```text
1k–4k tokens
```

由：

```text
current segment
recent 5–20 segments
scene participants
relevant role summaries
relevant Identity evidence
override
rule candidate
```

组成。

不直接整个 chapter。

---

# 55. Identity Memory 和 Performance Memory

必须分开。

## Identity Memory

允许全书证据：

```text
“张老师其实叫张明”
```

## Performance Memory

只能当前之前：

```text
情绪
态度
秘密是否知道
关系状态
```

禁止未来剧情污染第 2 章演绎。

---

# 56. SceneState

```text
scene_id
location
time_hint
participants
mood
start_segment
end_segment
```

Speaker Candidate 先限制在 active participants。

这对 0.8B 小模型非常关键。

---

# 57. ReaderDirector 模型选择

主 Student：

```text
Qwen3.5-0.8B-Base
```

Upper Bound：

```text
Qwen3.5-2B-Base
```

Teacher：

```text
Qwen3.5-4B / 9B
```

Teacher 仅离线推理。

---

# 58. 为什么优先 Base 模型做 LoRA

Base 模型适合 task-specific fine-tuning。

但必须 A/B：

```text
0.8B-Base + LoRA
vs
0.8B post-trained + LoRA
```

不能纸面假设 Base 一定赢。

---

# 59. 4060 Ti 16GB 的总策略

你的卡适合：

```text
0.8B BF16 LoRA
2B LoRA / QLoRA
4B/9B 量化 Teacher inference
```

不优先：

```text
9B full fine-tune
Teacher+Student 同卡在线 GKD
32K context
多层 LoRA 堆叠
```

---

# 60. 一个 LoRA，不叠多个

主路线：

```text
Base
+
ReaderDirector Multi-task LoRA
```

训练阶段：

```text
Stage1 Gold
↓
checkpoint
↓
Stage2 Distill
↓
checkpoint
↓
Stage3 Hard/Correction
```

继续同一个 Adapter。

如果做消融：

```text
从 Base 分叉不同 Adapter
```

而不是层层叠。

---

# 61. 为什么不叠 LoRA

因为：

- Merge 顺序会影响；
- 多任务交互难诊断；
- MNN 导出复杂；
- 移动端版本管理麻烦；
- 很多任务共享相同小说表示。

---

# 62. 数据来源

## Gold

```text
USER_LOCKED
Human Audit
Explicit Rule Ground Truth
```

## Silver

```text
v90.7 + Teacher agreement
Teacher high confidence
```

## Hard

```text
Student错误
Student低置信
Teacher-v90.7分歧
复杂网文案例
```

---

# 63. v90.7 Exporter

必须写：

```text
LegacyV907Exporter
```

抽：

```text
current context
dialogue text
legacy speaker
role main name
aliases
positive graph
negative graph
co-occurrence
relation evidence
voice age
temporary voice state
merge/split
audit result
user correction
```

每条加 provenance。

---

# 64. Dataset Split

必须按书：

```text
Train Books 70%
Val Books 15%
Test Books 15%
```

系列小说整体放同一 split。

禁止同一本书切句混 train/test。

---

# 65. 数据格式

```json
{
  "sample_id":"...",
  "task":"SPEAKER",
  "book_hash":"...",
  "input":{
    "text":"...",
    "recent_context":"...",
    "scene_roles":["r01","r07"],
    "rule_candidates":[
      {"role":"r01","score":0.72}
    ],
    "overrides":[]
  },
  "legacy_prediction":{},
  "teacher_prediction":{},
  "target":{
    "speaker":"r01",
    "confidence":"HIGH"
  },
  "provenance":"HUMAN_GOLD"
}
```

---

# 66. Hard Negative

Identity 必须重点加入：

```text
师父 != 徒弟
妈妈 != 固定全局人物
众女 != 林雪
二老 != 王老
宿主 != 控制者
关系称谓 != Alias
```

方言：

```text
四川人 + 标准普通话
东北人 + 没有口音
```

Emotion：

大量：

```text
NEUTRAL
LOW
```

避免过演。

---

# 67. Curriculum

训练不一开始全混。

阶段：

```text
C1 Utterance基础
C2 双人 Speaker
C3 多人/指代
C4 Alias/Identity
C5 附身/控制
C6 VoiceState
C7 Emotion
C8 Dialect
```

最终再 Multi-task 混合。

---

# 68. 0.8B LoRA 起始 Recipe

开发起点：

```text
Model:
Qwen3.5-0.8B-Base

dtype:
BF16

LoRA:
rank=16
alpha=32
target=all-linear

max_length:
2048

batch:
2

gradient_accumulation:
8

lr:
1e-4

epoch:
2

warmup:
0.05
```

不是最终定值。

---

# 69. LoRA Rank 消融

仅：

```text
8
16
32
```

如果：

```text
rank8 ≈ rank16
```

优先 8。

如果 rank32 无显著收益，不用。

---

# 70. 4060Ti OOM 回退

0.8B：

```text
batch 2 → 1
↓
max_length 2048 → 1536
↓
gradient checkpoint
↓
最后才 QLoRA
```

2B：

```text
batch=1
grad_acc↑
```

仍 OOM/要长 context：

```text
QLoRA
```

---

# 71. 为什么 0.8B 先 LoRA 不 QLoRA

0.8B 相对小。

优点：

```text
训练稳定
基础 BF16
merge简单
量化噪声少
```

QLoRA 更多用于 2B 显存紧张或更长 context。

---

# 72. 训练前 Smoke

正式 Run 前 100 step。

记录：

```text
peak VRAM
allocated
reserved
step time
tokens/s
loss
grad norm
```

建议留显存余量，不把 16GB 顶满。

---

# 73. 实验产物

每 Run：

```text
training/runs/<run-id>/
├ command.txt
├ environment.txt
├ run.json
├ dataset_manifest.json
├ metrics.json
├ report.md
└ checkpoint/
```

---

# 74. Teacher Behavior Distillation

V1 不做在线 GKD。

流程：

```text
Hard Case
↓
4B/9B Teacher
↓
结构化答案
↓
Legacy/Rules一致性检查
↓
人工审查分歧
↓
Gold/Silver
↓
Student SFT
```

---

# 75. Teacher 不保存长 CoT

只保存：

```text
label
confidence bucket
evidence span
evidence type
```

例如：

```json
{
  "speaker":"r07",
  "confidence":"HIGH",
  "evidence_span":"张明低声说道",
  "evidence_type":"SPEECH_CUE"
}
```

---

# 76. Teacher 双层

如果 9B 本地慢：

```text
4B Teacher
↓
高置信直接
↓
低置信/冲突
↓
9B Teacher
```

---

# 77. Hard Example Mining

每一版 Student：

```text
跑 held-out validation books
↓
收错误
↓
错误分类
↓
Teacher
↓
人工检查关键分歧
↓
下一轮继续同 Adapter
```

---

# 78. 错误分类

```text
QUOTE_NOT_SPEECH
WRONG_SPEAKER
TURN_TRACKING
PRONOUN
FALSE_ALIAS_MERGE
FALSE_ALIAS_SPLIT
GROUP_PERSON
POSSESSION
TEMP_VOICE_LEAK
VOICE_PHASE
OVER_EMOTION
FALSE_DIALECT
```

---

# 79. Stage 混合

不要只训练 Hard。

初始：

```text
50% Gold/Core
30% Hard
20% Silver
```

按指标调整。

---

# 80. DPO / GRPO / Logit KD

V1：

```text
SFT + behavior distillation
```

足够。

DPO：

只在有真实听感偏好：

```text
A更自然
B更过演
```

时做 Emotion/Performance。

GRPO：

V1 不做。

Logit KD：

如果 Behavior Distillation 仍不够，再研究 Offline Top-K logits。

---

# 81. ReaderDirector 指标

## Speaker

```text
Accuracy
Macro F1
Auto-confirm Precision
Coverage
UNKNOWN Recall
3+ Person Accuracy
```

主 Gate：

```text
Auto-confirm Precision >= 98%
```

## Identity

```text
False Merge Rate
False Split Rate
Pairwise F1
B3
```

False Merge 第一优先。

## VoiceState

```text
Persistent Change Accuracy
Temporary Start/End F1
State Leakage Rate
```

## Emotion

```text
Macro F1
Intensity MAE / bucket accuracy
Overacting Rate
Human Preference
```

## Dialect

```text
Macro F1
False Activation Rate
Negative Evidence Accuracy
```

---

# 82. 0.8B vs 2B

2B 是上界，不默认手机。

如果：

```text
0.8B 核心指标距离2B ≤1–2个百分点
且 False Merge不恶化
```

用 0.8B。

如果差：

先：

```text
ContextBuilder
Rules
Hard Data
Distill
```

仍差再 2B。

---

# 83. LoRA Merge

最终：

```text
Base + Adapter
↓
merge
↓
HF Merged
```

必须重新跑 locked test。

比较：

```text
Adapter mode
vs
Merged mode
```

不能因为 merge 成功就假设行为一致。

---

# 84. MNN 导出层级

```text
HF Reference
↓
MNN PC reference
↓
MNN W8
↓
MNN W4 block64
↓
Android CPU
↓
Android OpenCL
↓
Android Hexagon/QNN
```

每一级都跑业务指标。

---

# 85. 为什么不能只 tensor cosine

模型输出是：

```text
speaker
identity
protocol
```

只要量化导致一个 token 错：

业务结论可能完全变化。

所以要看：

```text
任务指标
```

---

# 86. MNN 运行策略

MNN 3.6.1 当前官方基准证明：

```text
Hexagon prefill 很强
CPU decode 可能更快
```

因此：

```text
Device Benchmark
```

决定后端。

不写死 NPU 全链。

---

# 87. CosyVoice3-MNN：当前基线

当前架构：

```text
CosyVoiceStore
CosyVoiceRuntime
LlmNative
FlowNative
HiFTNative
EnrollmentNative
```

合成：

```text
LLM
→ Conditioner
→ Flow
→ HiFT
```

V1 以当前可听、稳定路径为 Reference。

---

# 88. TTS 不要重新训练的原因

已经有：

- 可用语音；
- Flow 蒸馏；
- Android 运行；
- Voice Profile；
- ZERO_SHOT；
- INSTRUCT2；
- 部分 NPU 探索。

最大缺口是：

```text
长篇工程化
多角色绑定
版本化
调度
取消
缓存
质量门
```

不是声学模型完全不可用。

---

# 89. CosyVoice Engine 抽离

最终：

```text
tts-cosyvoice/
├ engine/
├ model/
├ voicepack/
├ instruction/
├ scheduler/
├ quality/
└ native/
```

原 CosyVoice App 留为：

```text
Reference/Benchmark App
```

不要把其 Compose UI 直接合进 ReaderVoice。

---

# 90. TtsRequest

```text
job_id
generation_epoch

render_unit_id
text

voice_revision_id

language
accent
emotion
intensity
speed
volume_style

priority
deadline
output_mode
```

---

# 91. TtsResult

```text
audio_asset_id / PCM
duration
speech_token_count
RTF

backend
model_revision

quality_gate
warnings[]
```

---

# 92. PerformanceInstructionCompiler

ReaderDirector 只输出结构：

```text
emotion=angry
intensity=2
accent=sichuan
speed=normal
```

只有 Compiler 可以生成 CosyVoice INSTRUCT2 文本。

禁止业务代码自由：

```text
"请用非常..."
```

这样保证一致。

---

# 93. ZERO_SHOT 与 INSTRUCT2

必须做三方案实验：

## A

```text
neutral → ZERO_SHOT
controlled → INSTRUCT2
```

## B

```text
整章统一 INSTRUCT2
neutral也用中性 instruction
```

## C

```text
同一 RenderSequence 锁一种模式
```

选：

```text
speaker consistency + MOS
```

---

# 94. Voice × Instruct Matrix

至少：

```text
20 VoicePack
×
Neutral/Happy/Sad/Angry/Tense/Soft/Fast/Slow
×
若干口音
```

抽样执行。

指标：

```text
CER
Speaker Similarity
F0
Duration
Loudness
Bad Generation
MOS
```

最重要：

```text
Speaker Identity Retention
```

---

# 95. 如果 Instruct 导致音色漂

回退：

```text
减弱 instruction
↓
同角色固定模式
↓
控制模板优化
↓
Control Adapter
↓
最后才 TTS LoRA
```

不直接重训 Flow/HiFT。

---

# 96. RenderUnit

SemanticSegment 不是 TTS 单位。

相邻：

```text
同 speaker
同 voice revision
同 accent
emotion compatible
长度允许
```

可合并。

---

# 97. 为什么不能逐句 TTS

每句话独立：

```text
语气重置
呼吸断裂
音色微漂
开头韵律重复
```

所以：

```text
Segment → RenderPlanner → RenderUnit
```

---

# 98. RenderUnit Length Profile

必须真机实验：

```text
20
40
60
80
100
150
200中文字
```

记录：

```text
speech token count
Flow seq length
audio seconds
LLM time
Conditioner time
Flow time
HiFT time
RTF
CER
MOS
```

得到：

```text
Preferred
Safe
HardMax
```

不固定“每50字切”。

---

# 99. ParagraphTimeline

最终：

```text
Audio A
Pause
Audio B
Pause
Audio C
```

默认：

```text
trim abnormal silence
loudness normalize
insert pause
sample concat
```

不默认 crossfade 人声。

---

# 100. PausePlanner

因素：

```text
标点
speaker change
叙述→对白
对白→叙述
情绪
scene break
```

提供：

```text
紧凑
正常
舒缓
```

全局倍数。

---

# 101. Loudness

不简单 peak normalize。

用感知响度窗口。

允许角色有小差异，但避免：

```text
角色A突然大6dB
```

---

# 102. TTS Quality Gate

Cheap Gate：

```text
NaN
空音频
duration ratio
silence ratio
RMS
clipping
repetition
tail
```

可疑才：

```text
ASR
```

---

# 103. ASR Gate

ASR CER 高只说明：

```text
suspicious
```

方言、人名、外语会让 ASR 自己错。

综合：

```text
CER
duration
acoustic anomaly
dialect
proper noun
```

---

# 104. TTS Retry

最大：

```text
2–3
```

每次可以：

```text
safer instruction
shorter RenderUnit
backend fallback
seed/decoding adjustment
```

仍失败：

```text
Problem Inbox
```

---

# 105. TTS 文件 I/O

当前 CosyVoice3-MNN 的 Debug pipeline 会产生大量中间文件。

ReaderVoice 第一版：

```text
允许保留 Reference File Pipeline
```

但成功提交 AudioAsset 后：

```text
清理 run temp
```

---

# 106. v1.5 Memory Pipeline

后续：

```text
LLM → IntArray
Conditioner → FloatBuffer
Flow → FloatBuffer
HiFT → PCM Buffer
```

逐步取消磁盘来回写。

不能因为这个性能优化阻塞第一版。

---

# 107. Conditioner 子进程

如果当前 Conditioner 仍使用独立进程：

V1 可保留 Reference。

V1.5：

```text
ConditionerNative JNI
```

减少每 RenderUnit 的进程启动开销。

---

# 108. Playback Cold Start

这是 P0 UX。

用户点击：

```text
没有 NarrationIR
没有 Audio
```

不能等待整条高质量链。

新增：

# Bootstrap Render Path

```text
目标章节
↓
Fast Paragraph Recovery
↓
Rule-only Semantic
↓
已知speaker/voice则多角色
未知则 narrator/provisional
↓
短 RenderUnit
↓
TTS
↓
开始播放
```

后台再完整分析。

---

# 109. TTFA

一级 KPI：

```text
Time To First Audio
p50
p95
```

必须真机测。

---

# 110. Playback Commit Frontier

当前已经开始播放的 Paragraph revision：

```text
COMMITTED_FOR_CURRENT_PLAY
```

后台即使产生更准确版本：

```text
不热替换正在播放的段
```

未来未播段可以升级。

用户下次 replay 用新版本。

---

# 111. 三 Frontier

```text
Playback Frontier
Acoustic Frontier
Semantic Frontier
```

稳定状态：

```text
Playback <= Acoustic <= Semantic
```

调度器的目标：

> 保持三者安全距离，而不是盲目跑更多后台工作。

---

# 112. Audio Buffer 水位

开发初值：

```text
Critical < 60s
Low < 180s
Healthy > 600s
```

Critical：

```text
暂停非紧急 Director
停止远期工作
全部资源给TTS
```

Healthy：

```text
可继续Director
降低TTS频率避免发热
```

---

# 113. Cooperative Preemption

不 kill native thread。

状态：

```text
QUEUED_CANCEL
COOPERATIVE_CANCEL
IN_FLIGHT_STALE
```

跳章：

```text
generation_epoch++
↓
queued旧任务取消
↓
running旧任务标stale
↓
安全点退出
↓
即使算完也discard
```

---

# 114. Native Cancel

建议 API：

```text
run(jobId)
requestCancel(jobId)
```

LLM：

```text
每 token/小批检查
```

Flow：

```text
两step之间检查
```

HiFT：

如果不可安全中断：

```text
算完
→ epoch过期
→ 丢弃
```

---

# 115. Max Non-preemptible Latency

必须测：

```text
p50
p95
p99
```

如果 speculative backend 单块不可抢占 8s：

```text
切小
或换后端
```

---

# 116. Emergency Backend

NPU/GPU 正在忙旧任务：

```text
当前播放P0
```

不能等。

可：

```text
CPU/GPU备用快速路径
```

先生成 bootstrap 音频。

---

# 117. Job Schema

```text
job_id
job_type
target_id

input_hash
generation_epoch

priority
state

attempt
lease_until

backend
created_at
updated_at

last_error
```

state：

```text
PENDING
RUNNING
DONE
FAILED
CANCELLED
STALE
```

---

# 118. Job 幂等

同一：

```text
operation + target + input hash
```

重复运行不能产生两个逻辑产物。

APP 被杀后：

```text
RUNNING lease超时
→ PENDING
```

恢复。

---

# 119. Audio 原子提交

```text
asset.tmp
↓
write
↓
close/checksum
↓
atomic rename
↓
DB transaction DONE
```

避免半文件。

---

# 120. SemanticHash 与 SynthesisHash

```text
SemanticHash =
spoken_text
emotion
accent
speed
pronunciation
```

```text
SynthesisHash =
SemanticHash
+ VoiceProfileRevision
+ TTSModelVersion
+ FrontendVersion
```

---

# 121. Merge 后缓存

如果两个角色实际绑定同一 VoiceRevision：

```text
只修语义
音频可复用
```

如果不同：

不能改 metadata 假装旧 WAV 是新声音。

应：

```text
ACOUSTIC_STALE
```

并：

```text
lazy rebuild
```

---

# 122. Audio 状态

```text
VALID
SEMANTIC_STALE
ACOUSTIC_STALE
RENDER_STALE
FAILED_QC
```

---

# 123. Correction Compiler

用户修正必须即时生效：

```text
CorrectionEvent
↓
OverrideCompiler
↓
BookOverrideRule
↓
RuleEngine
```

不是只留着以后训练。

---

# 124. Override 类型

```text
ENTITY_EQ
ENTITY_NEQ
SPEAKER_ASSIGN
SPEAKER_BLOCK
PARAGRAPH_JOIN
PARAGRAPH_SPLIT
QUOTE_TYPE
PRONUNCIATION
ACCENT_FORCE
ACCENT_BLOCK
VOICE_BIND
ROLE_LOCK
SKIP_TEXT
RESTORE_TEXT
```

优先：

```text
USER_LOCKED
>
USER_OVERRIDE
>
CONFIRMED_RULE
>
MODEL
```

---

# 125. 方言 Evidence

Evidence 必须有正负。

```text
+ 明确四川口音
+ 方言词

- 标准普通话
- 没有口音
```

且 Evidence 有：

```text
scope
valid_from
valid_to
scene
```

不能“出生四川”永久四川话。

---

# 126. Emotion

分：

```text
长期人物性格
Scene Mood
Utterance Emotion
```

ReaderDirector 不应该一句一句独立跳。

需要：

```text
emotion inertia
```

场景切换可衰减/reset。

---

# 127. Emotion Saturation

普通听书模式：

```text
旁白控制较弱
普通对白中等
明确喊叫才高强度
```

提供：

```text
自然
沉浸
广播剧
```

三个 gain preset。

---

# 128. OriginalText / SpokenText

永远保存：

```text
original_text
spoken_text
pronunciation_override
```

比如：

```text
1998年
→
一九九八年
```

不修改展示原文。

---

# 129. Pronunciation Dictionary

优先级：

```text
User Override
>
Book Dictionary
>
Character Name Dictionary
>
Rule Normalizer
>
CosyVoice Built-in
```

scope：

```text
MENTION
ROLE
BOOK
GLOBAL
```

---

# 130. 多语言 Code-switch

标 span：

```text
ZH
EN
NUMBER
NAME
OTHER
```

不要整句一个 language label。

---

# 131. Multi-book Cache

永久小数据：

```text
Book metadata
Chapter index
Character DB
Overrides
NarrationIR
```

大音频受 quota 控制。

清理：

```text
Archived
→ old future
→ old current-book history
```

不自动删：

```text
Voice raw
User corrections
Book project state
```

---

# 132. Hot/Warm/Cold

```text
Hot:
PCM current window

Warm:
compressed render/paragraph audio

Cold:
NarrationIR only
```

---

# 133. Semantic Bookmark

书签不能只存：

```text
position_ms
```

因为重生成可能改变时长。

存：

```text
segment_id
offset_inside_segment
```

播放时映射到 Timeline。

---

# 134. Model Residency

```text
UNLOADED
MMAPPED
SESSION_CREATED
WARMING
READY
HOT
```

Warm-up：

```text
代表性小输入
```

不是触碰整个模型所有页。

---

# 135. 8GB RAM 模式

默认：

```text
Director batch
↓
降驻留
↓
TTS batch
```

ASR 不常驻。

Enrollment 只有用户创建音色时加载。

---

# 136. ThermalController

输入：

```text
thermal status
buffer seconds
TTS RTF
battery
```

策略：

```text
温度高 + buffer足
→ 停后台AI

温度高 + buffer低
→ 只保P0 TTS

暂停播放
→ 停止重推理
```

---

# 137. Android 后台现实

不设计：

```text
锁屏无限跑完整书库
```

生产目标：

```text
用户当前听书
→ 当前窗口可靠
```

远期预生成 best-effort。

任务必须：

```text
checkpoint
resume
idempotent
```

---

# 138. Android ModelManager

模型：

```text
ReaderDirector
CosyVoice Runtime
Enrollment Optional
ASR Optional
```

ModelManifest：

```text
model_id
version
source_revision
converted_revision
quantization
sha256
size
min_app_version
device_profile
```

---

# 139. 模型升级

不能：

```text
删旧
↓
下新
```

正确：

```text
old active
new staging
↓
verify hash
↓
smoke
↓
switch active
↓
保留rollback窗口
```

---

# 140. SynthesisEpoch

Voice/Model 升级后，不让半章突然换声。

```text
Book/Chapter SynthesisEpoch
```

同一章尽量固定：

```text
TTS model revision
VoiceProfileRevision
Frontend version
```

---

# 141. Room Schema

核心表建议：

```text
books
source_files
physical_lines
line_boundaries

volumes
chapters
logical_paragraph_revisions
cross_paragraph_links

semantic_segments
semantic_spans

mentions
narrative_entities
identity_evidence
scenes
relationships
embodiment_states
voice_states

voice_packs
voice_profile_revisions
book_voice_bindings

narration_revisions
render_units
paragraph_timelines

audio_assets
cache_dependencies

correction_events
override_rules

jobs
model_manifests
device_profiles
```

---

# 142. 数据库版本

分别管理：

```text
db_schema_version
narration_ir_version
voicepack_schema_version
director_model_version
tts_model_version
frontend_version
```

不能只一个 `version=2`。

---

# 143. Room Migration

正式用户版本禁止：

```text
fallbackToDestructiveMigration
```

角色、VoicePack、用户修正不能因为升级丢。

---

# 144. AGENTS.md：为什么仍然要短

总手册要长。

Agent Instructions 要短。

根 AGENTS 只放：

```text
Mission
Invariants
Read order
Do/Don't
Validation
Definition of Done
```

局部：

```text
parser/AGENTS.md
training/AGENTS.md
tts-cosyvoice/AGENTS.md
app-android/AGENTS.md
core-character/AGENTS.md
```

---

# 145. Agent Read Order

每个 Coding Agent：

```text
1 /AGENTS.md
2 docs/PROJECT_STATE.md
3 最近模块 AGENTS.md
4 相关 ExecPlan
5 实际代码/测试
```

不要假设工具自动加载了所有嵌套文件。

---

# 146. ExecPlan 必用场景

必须写 Plan：

```text
DB迁移
Parser重构
训练/蒸馏协议改变
Native backend
Scheduler/cancel
跨多个核心模块
冻结Gate变化
```

---

# 147. ExecPlan 格式

```text
Goal
Current Verified Facts
Non-goals
Invariants
Scope
Baseline
Milestones
Progress
Decisions
Experiments
Validation
Rollback
Artifacts
Open Issues
Handoff
```

Progress 边做边更新。

---

# 148. PROJECT_STATE

只存：

```text
当前冻结架构
当前版本
通过Gate
失败路线
当前正式任务
```

不能长篇流水账。

---

# 149. DECISIONS

每条 ADR：

```text
Decision
Reason
Alternatives
Evidence
Consequences
Revisit Trigger
```

例如：

```text
ADR: False Merge > False Split
ADR: V1 单 Multi-task LoRA
ADR: V1 不重训 CosyVoice 主模型
```

---

# 150. Agent 禁止事项

```text
禁止 git reset --hard 清用户改动
禁止删 research runs
禁止删模型证据
禁止把 secret 写仓库
禁止降低 Gate 过实验
禁止用 Test 调参数
禁止把 UNKNOWN 强写成确定
禁止只报速度不报音质
禁止改源书正文来“修Parser”
禁止没证据 Merge
```

---

# 151. Run Artifact

任何正式实验：

```text
runs/<run-id>/
├ run.json
├ command.txt
├ environment.txt
├ metrics.json
├ report.md
└ artifacts/
```

训练再：

```text
dataset digest
checkpoint
model revision
```

TTS：

```text
reference audio
candidate audio
PCM metrics
backend trace
```

---

# 152. Definition of Done

Agent 不能只说“完成”。

必须：

```text
功能完成
相关测试运行
Gate结果
失败记录
迁移/失效正确
无无关破坏
文档更新
风险说明
```

---

# 153. 正式 Milestone 0：工程治理

产出：

```text
AGENTS.md
module AGENTS
PLANS.md
TASK_TEMPLATE
PROJECT_STATE
DECISIONS
```

Gate：

新 Agent 只读这些 + repo，就能说明：

```text
项目是什么
现在做到哪
什么不能动
怎么测试
下一步是什么
```

---

# 154. Milestone 1：TXT Source

完成：

```text
SAF Import
Encoding
SourceMap
PhysicalLine
```

Gate：

```text
乱码
大文件
offset一致
```

---

# 155. Milestone 2：Chapter/Paragraph

完成：

```text
Legacy Regex Migration
Chapter Candidate
TOC
Volume
Layout Profile
Paragraph Recovery
```

Gate：

真实 TXT Gold Set。

---

# 156. Milestone 3：DB/Revision

实现所有：

```text
Revision
Override
Invalidate
```

先不用 AI，用 fake data 完整跑通。

---

# 157. Milestone 4：Character System

完成：

```text
Mention
Identity
Merge/Split
Scene
Embodiment
VoiceState
UNKNOWN
```

Gate：

手工复杂案例。

---

# 158. Milestone 5：v90.7 Migration

产：

```text
V907_CAPABILITY_MATRIX.md
LegacyV907Exporter
Error Taxonomy
Hard Case Dataset
```

不是搬代码。

---

# 159. Milestone 6：ReaderDirector Baseline

先不训练：

```text
0.8B Base/few-shot
0.8B post-trained
2B
```

同 Test。

冻结 baseline。

---

# 160. Milestone 7：0.8B LoRA

4060 Ti：

```text
100 step smoke
↓
formal SFT
```

保存所有 Run。

---

# 161. Milestone 8：Teacher Distill

只对难例。

```text
4B
↓
9B
↓
human
```

继续同 Adapter。

---

# 162. Milestone 9：Hard Mining

直到：

```text
错误分布稳定
核心Gate趋于饱和
```

---

# 163. Milestone 10：2B Upper Bound

只有一个问题：

> 0.8B 是否值得被替换？

没有明显收益就不进手机。

---

# 164. Milestone 11：MNN Director

逐层部署 Gate。

最终：

```text
ReaderDirector-Mobile
```

---

# 165. Milestone 12：CosyVoice Engine

从现有仓库抽 Runtime。

先保留 reference 行为。

增加：

```text
TtsRequest
VoiceRevision
Priority
Epoch
QC
```

---

# 166. Milestone 13：Voice Enrollment

完成：

```text
自定义声音
VoicePack
Revision
Prompt Selector
```

---

# 167. Milestone 14：Control Matrix

完成 Voice × Emotion × Accent 正式验证。

只有失败明确才进入 TTS 微调研究。

---

# 168. Milestone 15：RenderPlanner

解决：

```text
段落内多角色
同角色多句连贯
长度 profile
```

---

# 169. Milestone 16：Playback Bootstrap

目标：

```text
新书点击后很快听到第一段
```

正式量 TTFA。

---

# 170. Milestone 17：Scheduler

验证：

```text
连续播放
跳章
切书
取消
高温
低内存
process kill
```

---

# 171. Milestone 18：Multi-book

导入：

```text
50–100 本
```

验证：

```text
未打开的书无LLM/TTS
Library流畅
DB增长合理
```

---

# 172. Milestone 19：Final 8GB Gate

飞行模式：

```text
导入300万字
目录
段落恢复
播放
多角色
创建Voice
跳章
换Voice
Merge
Pronunciation
kill/restart
```

连续：

```text
60min
```

Gate：

```text
无OOM
无音频underrun
无native crash
角色声音不乱
```

---

# 173. Final Metrics Dashboard

Parser：

```text
Chapter F1
Paragraph metrics
```

Character：

```text
Speaker Precision
False Merge
UNKNOWN
```

Director：

```text
Latency
tokens
accuracy
```

TTS：

```text
CER
SpeakerSim
RTF
BadRate
```

Runtime：

```text
TTFA
buffer underrun
PSS
thermal
battery
```

System：

```text
Correction Cost
LLM Utilization
Cache Hit
```

---

# 174. Correction Cost

定义：

```text
RebuildAmplification =
重生成音频秒数
/
被修正文本原音频秒数
```

目标：

```text
尽量接近局部
```

一次 Alias 修正不能引起整本书重建。

---

# 175. LLM Utilization

```text
LLM处理字符
/
书总字符
```

在精度相同的情况下：

```text
越低越好
```

代表 Rule First 真正有效。

---

# 176. 研究增强路线

V1 完成后：

```text
ReaderDirector Hidden State
↓
Control Adapter
↓
CosyVoice Condition
```

冻结两边主体，只训练小 Adapter。

对比：

```text
Discrete labels
vs
Labels + hidden
```

指标：

```text
speaker sim
CER
emotion appropriateness
MOS
```

---

# 177. 什么时候做 CosyVoice LoRA

只有：

```text
INSTRUCT稳定破坏speaker identity
方言严重不足
情绪严重不跟
Control Adapter无效
```

才做。

第一版不为了“模型创新”主动改 TTS。

---

# 178. 什么时候考虑 Unified ReaderVoice

只有：

```text
APP稳定
ReaderDirector稳定
CosyVoice稳定
Adapter证明连续表示有收益
```

才研究：

```text
Shared Transformer
```

否则风险过高。

---

# 179. 第一轮 Agent 执行顺序

Agent 不能直接训练。

依次：

```text
TASK-000 Repo/Agent Bootstrap
TASK-010 TXT Source
TASK-020 Chapter
TASK-030 Paragraph Recovery
TASK-040 DB
TASK-050 v90.7 Exporter
TASK-060 Character
TASK-070 Gold Set
TASK-080 Baseline
TASK-090 LoRA
TASK-100 Distill
TASK-110 MNN
TASK-120 CosyVoice Engine
...
```

---

# 180. 第一条给 Coding Agent 的 Prompt

```text
Read the repository root AGENTS.md, docs/PROJECT_STATE.md, and docs/agent/PLANS.md first.
Then inspect the actual repository tree and the closest module AGENTS.md files.

Do not implement ReaderDirector or CosyVoice changes yet.

Complete TASK-000 only:
1. verify repository layout;
2. verify/update root and nested AGENTS.md;
3. verify PROJECT_STATE.md and DECISIONS.md;
4. create an ExecPlan for TASK-010 TXT Source/Encoding;
5. record current baselines and unresolved facts;
6. run only repository/bootstrap checks relevant to this task.

Do not delete existing experiments, model artifacts, user data, or uncommitted changes.
Do not modify CosyVoice model weights or backend in TASK-000.
```

---

# Appendix A：推荐仓库结构

```text
ReaderVoice-Mobile/
├ AGENTS.md
├ README.md
├ docs/
│  ├ PROJECT_STATE.md
│  ├ DECISIONS.md
│  ├ agent/
│  │  ├ PLANS.md
│  │  ├ TASK_TEMPLATE.md
│  │  └ plans/
│  ├ architecture/
│  ├ experiments/
│  └ protocols/
│
├ parser/
│  ├ AGENTS.md
│  ├ source/
│  ├ chapters/
│  ├ layout/
│  └ paragraph/
│
├ core-character/
│  ├ AGENTS.md
│  ├ mention/
│  ├ identity/
│  ├ scene/
│  ├ embodiment/
│  └ voice-state/
│
├ core-director/
│
├ training/
│  ├ AGENTS.md
│  ├ data_raw/
│  ├ data_gold/
│  ├ data_silver/
│  ├ data_hard/
│  ├ exporters/
│  ├ teacher/
│  ├ sft/
│  ├ eval/
│  └ runs/
│
├ tts-cosyvoice/
│  ├ AGENTS.md
│  ├ engine/
│  ├ voicepack/
│  ├ scheduler/
│  ├ quality/
│  └ native/
│
├ audio-renderer/
├ scheduler/
├ data-room/
│
└ app-android/
   └ AGENTS.md
```

---

# Appendix B：根 AGENTS.md 应包含的核心内容

根文件不重复本手册全部细节，只包括：

```text
Mission
Read order
Core invariants
Planning rules
Change discipline
Evaluation discipline
Validation
Documentation
Secrets/user data
Definition of Done
```

核心不变量：

```text
Raw Source immutable
PhysicalLine != LogicalParagraph
Mention != Identity != Embodiment != VoiceState
Character != VoicePack
UNKNOWN合法
False Merge优先防止
Performance causality
User Locked最高
Playback P0
Native cooperative cancel
最终PCM Gate
多书按需
版本化Voice
```

---

# Appendix C：Parser AGENTS.md 重点

```text
不可直接 newline=paragraph
trim前提取layout
chapter regex只候选
raw anchor先于reflow
UNCERTAIN合法
poetry特殊处理
LLM不做大规模reflow
```

---

# Appendix D：Training AGENTS.md 重点

```text
GPU=4060Ti16GB
Student=0.8B
Upper=2B
Teacher=4B/9B
一个Multi-task LoRA
0.8B先BF16 LoRA
按书split
Teacher-only是Silver
formal run留manifest
test不调参
```

---

# Appendix E：TTS AGENTS.md 重点

```text
CosyVoice3-MNN为Reference
V1不重蒸Flow
NPU不能只速度
最终PCM/听感Gate
Voice raw必保留
VoiceRevision
结构化instruction
长度需profile
取消用cooperative
```

---

# Appendix F：Android AGENTS.md 重点

```text
Playback continuity第一
Job持久化
Room非破坏迁移
semantic bookmark
generation epoch
warmup不冻UI
low-memory分时
60min验收
```

---

# Appendix G：ReaderDirector 第一版 ms-swift 命令模板

以下只作为起始模板；Agent 必须先核验当前 ms-swift 参数与本地环境，再正式运行：

```bash
CUDA_VISIBLE_DEVICES=0 \
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True \
swift sft \
  --model Qwen/Qwen3.5-0.8B-Base \
  --tuner_type lora \
  --dataset data/train.jsonl \
  --val_dataset data/val.jsonl \
  --torch_dtype bfloat16 \
  --num_train_epochs 2 \
  --per_device_train_batch_size 2 \
  --per_device_eval_batch_size 2 \
  --gradient_accumulation_steps 8 \
  --learning_rate 1e-4 \
  --lora_rank 16 \
  --lora_alpha 32 \
  --target_modules all-linear \
  --max_length 2048 \
  --warmup_ratio 0.05 \
  --eval_steps 100 \
  --save_steps 100 \
  --save_total_limit 3 \
  --logging_steps 10 \
  --output_dir training/runs/readerdirector-0.8b-sft
```

正式运行前：

```text
先 100 step smoke
```

---

# Appendix H：训练失败排查

## OOM

```text
batch↓
max_length↓
grad accumulation↑
checkpoint
QLoRA
```

## Train loss 很好，Val 不升

```text
book diversity不足
过拟合角色名
epoch过多
lr过高
hard negatives不足
```

## 输出喜欢强猜角色

```text
UNKNOWN训练不足
hard negative不足
confidence策略过激
```

## False Merge

```text
加负例
提高merge gate
引入different-person证据
更多同场互动反例
```

## Emotion 太夸张

```text
Neutral比例↑
Intensity粗粒度
emotion task weight↓
```

---

# Appendix I：CosyVoice 改造任务拆分

```text
CV-001 抽 Engine
CV-002 VoiceProfileRevision
CV-003 TtsRequest/TtsResult
CV-004 PerformanceInstructionCompiler
CV-005 Priority Scheduler
CV-006 Generation Epoch
CV-007 Native cooperative cancel
CV-008 Audio Quality Gate
CV-009 RenderUnit length profiler
CV-010 Voice×Instruct matrix
CV-011 temp file GC
CV-012 memory pipeline（后续）
CV-013 Conditioner JNI（后续）
```

---

# Appendix J：Android Final Scenario

最终自动化/人工测试脚本：

```text
1 安装全新APP
2 飞行模式
3 导入300万字GBK/UTF8 TXT
4 自动识别目录
5 检查首章段落恢复
6 点击播放
7 记录TTFA
8 连续播放10min
9 跳到第20章
10 记录重新TTFA/取消行为
11 创建自定义Voice
12 绑定主角
13 修改主角Voice
14 检查局部重生成
15 合并角色
16 检查lazy rebuild
17 修改发音
18 检查局部失效
19 切另一本书
20 切回
21 kill进程
22 重启
23 继续播放
24 连续运行60min
25 导出metrics/report
```

---

# Appendix K：最终发布 Gate

必须同时：

```text
功能
正确性
性能
稳定性
数据安全
升级
```

任何一个未过：

```text
不叫正式可用
```

---

# 181. 一句话总结

ReaderVoice-Mobile 的最佳路线不是“找一个更大的 LLM 然后让它接管一切”，而是：

```text
真实 TXT 结构恢复
+
规则/数据库降低问题复杂度
+
专项蒸馏 ReaderDirector
+
现有 CosyVoice3-MNN 工程化
+
可增量/可抢占/可修正调度
+
严格 Agent/Gate 纪律
```

你的 RTX 4060 Ti 16GB 足够支撑这一条路线的核心研发：0.8B LoRA、多轮行为蒸馏、2B 上界以及量化 Teacher 离线标注；移动端则依靠小模型专项化、按需窗口和现有 CosyVoice3-MNN 基线，把系统真正落到手机上。

本 v5 文档作为 Master Source of Truth；后续局部 AGENTS、ExecPlan、PROJECT_STATE 只能补充和约束执行，不应再用“模块化”理由删除 Master Manual 中的关键实现细节。


---

# Appendix L：数据库字段级设计（V1 建议 Schema）

本节不是要求逐字照抄 Room Entity，而是把“必须保存的信息”冻结，防止后续 Agent 为了方便删字段。

## L.1 BookEntity

```text
book_id: UUID/String
source_file_id
title
author?
series_id?
source_format
source_hash
source_size
charset
charset_confidence
charset_user_locked
imported_at
last_opened_at

lifecycle_state
active_revision
parser_profile_id
```

`source_hash` 用于完全重复书籍检测。

不要用：

```text
文件名
```

当唯一 ID。

---

## L.2 SourceFileEntity

```text
source_file_id
book_id
original_uri
local_path
sha256
byte_size
last_modified
mime_type
```

如果未来允许书籍修订版：

```text
parent_source_file_id
```

保留来源链。

---

## L.3 PhysicalLineEntity

```text
line_id
book_id
line_no

byte_start
byte_end
char_start
char_end

raw_text

leading_ascii_space
leading_fullwidth_space
leading_tab
trailing_space

char_count
han_count
ascii_count

ends_sentence_terminal
ends_colon
ends_comma
starts_quote
ends_quote

quote_depth_before
quote_depth_after

blank
separator_candidate
chapter_candidate
```

`raw_text` 可以根据大文件策略改成 source offset + lazy read，而不是所有行全文都放数据库；但逻辑字段必须可取得。

---

## L.4 LineBoundaryEntity

```text
boundary_id
book_id

left_line_id
right_line_id

boundary_type
confidence

evidence_mask
evidence_json

rule_version
created_revision

user_locked
```

`boundary_type`：

```text
SOFT_WRAP
HARD_PARAGRAPH
AUTHOR_LINE_BREAK
STRUCTURAL_BREAK
UNCERTAIN
USER_JOIN
USER_BREAK
```

---

## L.5 ChapterCandidateEntity

```text
candidate_id
line_id
rule_id
family

raw_title
clean_title
serial_raw
serial_value

base_score
sequence_score
style_score
density_score
toc_score
length_score

final_score

candidate_type
resolved_state
```

---

## L.6 ChapterEntity

```text
chapter_id
book_id
volume_id?

chapter_index
serial_value?
title_raw
title_display

source_start
source_end

chapter_type
parser_revision

confidence
user_locked
```

`chapter_index` 是书内真实排序。

`serial_value` 可以重复，例如每卷都从 1 开始。

---

## L.7 LogicalParagraphRevision

```text
paragraph_id
revision_id
book_id
chapter_id

source_spans_json
normalized_text

block_type

boundary_confidence
revision_reason
parent_revision

active
created_at
```

不要直接 update 覆盖旧结构。

---

## L.8 CrossParagraphLink

```text
link_id
left_paragraph_id
right_paragraph_id

type
confidence

evidence
revision
```

type：

```text
SPEECH_CUE
QUOTE_CONTINUATION
CONTINUATION
SCENE_CONTINUATION
NONE
```

---

## L.9 SemanticSegmentEntity

```text
segment_id
book_id
chapter_id
paragraph_id

segment_index

type

source_spans
original_text
spoken_text

speaker_assignment_revision?
scene_id?

quote_depth
quote_type

pronunciation_revision
semantic_revision
```

---

## L.10 MentionEntity

```text
mention_id
segment_id

surface_text
source_start
source_end

mention_type

resolved_entity_id?
confidence
resolution_state
```

---

## L.11 NarrativeEntity

```text
entity_id
book_id

entity_type
canonical_name
display_name

status

first_seen_segment
last_seen_segment

importance_score
importance_level

user_locked
```

---

## L.12 IdentityEvidenceEntity

```text
evidence_id
book_id

entity_a
entity_b

relation_type
sign

confidence
weight

source_segment
source_type
reason_code
reason_text?

rule_version
model_version

valid_from?
valid_to?

revision
```

`sign`：

```text
POSITIVE
NEGATIVE
NEUTRAL
```

---

## L.13 EmbodimentStateEntity

```text
embodiment_state_id

identity_entity_id
body_entity_id?

state_type

start_segment
end_segment?

confidence
evidence_id
```

---

## L.14 SceneEntity

```text
scene_id
book_id
chapter_id

start_segment
end_segment?

location_hint
time_hint

mood

revision
```

场景参与人建议单独关联表：

```text
SceneParticipant
scene_id
entity_id
role
confidence
```

---

## L.15 VoiceStateEntity

```text
voice_state_id
entity_id

state_type
voice_age
style_override

start_segment
end_segment?

persistent

confidence
source
```

`state_type`：

```text
BASE
PHASE
TEMPORARY_OVERRIDE
ONE_SHOT
```

---

## L.16 VoicePackEntity

```text
voice_id
display_name

source_type
raw_audio_path
prompt_text

created_at
deleted_at?

user_tags_json
```

---

## L.17 VoiceProfileRevisionEntity

```text
voice_revision_id
voice_id

revision_no

cosyvoice_model_version
frontend_version
speaker_encoder_version
speech_tokenizer_version

prompt_hash

prompt_tokens_path
prompt_condition_path
speaker_embedding_path

quality_score

created_at
active
```

---

## L.18 BookVoiceBinding

```text
binding_id
book_id
entity_id

voice_revision_id

lock_mode

valid_from_segment?
valid_to_segment?

synthesis_epoch
```

---

## L.19 NarrationRevision

```text
narration_revision_id
segment_id

speaker_identity_id
embodiment_id?

voice_revision_id?

emotion
emotion_intensity

accent
speed
volume_style

pause_before
pause_after

confidence

analysis_model_version
rule_version
character_db_revision

parent_revision
reason
active
```

---

## L.20 RenderUnitEntity

```text
render_unit_id
book_id
chapter_id

segment_ids_json

tts_text

voice_revision_id
performance_hash
semantic_hash
synthesis_hash

render_plan_revision
status
```

---

## L.21 AudioAssetEntity

```text
audio_asset_id
render_unit_id

path
codec
sample_rate
channels
duration_ms
sha256

voice_revision_id
tts_model_version
frontend_version
backend

qc_state
qc_metrics_json

state

created_at
last_accessed
size_bytes
```

---

## L.22 CorrectionEvent

```text
correction_id
book_id

type
target_id

old_value
new_value

user_locked

source_ui
created_at

compiled_rule_id?
```

---

## L.23 OverrideRule

```text
rule_id
book_id?

scope
type

match_payload
action_payload

priority
user_locked

valid_from?
valid_to?

enabled
```

---

## L.24 JobEntity

```text
job_id
job_type

book_id
target_id

input_hash
generation_epoch

priority
state

attempt
max_attempt

lease_until
created_at
started_at
finished_at

backend
model_version

last_error_code
last_error_message
```

---

# Appendix M：TXT 段落恢复算法的具体实现方法

## M.1 为什么先做 Profile 再做 Boundary

单独看：

```text
上一行没有句号
```

并不能决定合并。

例如：

```text
第一行诗歌
第二行诗歌
```

同样没有句号，但必须保留换行。

所以顺序：

```text
Block/Layout Profile
↓
局部 Boundary
```

---

## M.2 BookProfile 计算

对前若干千个非空 PhysicalLine 统计：

```text
长度直方图
空行规律
全角缩进
句末标点
```

判断 fixed width 的一种简单思路：

```text
mode_len = 最频繁长度

near_mode_ratio =
count(abs(length-mode_len)<=2)
/
nonblank_count
```

若：

```text
near_mode_ratio 高
且
mode_len较大
且
大量 mode 行没有终止标点
```

提高：

```text
FIXED_WIDTH_HARD_WRAP
```

---

## M.3 BoundaryScore 示例

不要一开始训练模型。

可先线性打分：

```text
join_score = 0

if fixed_width_profile:
    join_score += 2.0

if prev_len near wrap_width:
    join_score += 1.2

if prev no sentence terminal:
    join_score += 1.4

if prev ends comma:
    join_score += 1.0

if next has paragraph indent:
    join_score -= 2.5

if blank gap:
    join_score -= profile_dependent

if chapter anchor:
    join_score -= 10

if separator:
    join_score -= 10

if block=POETRY:
    join_score -= 10
```

再映射 confidence。

这些数值只是初始工程参数，必须在 Gold Set 上校准。

---

## M.4 Quote 跨行

如果：

```text
quote_depth_after(prev) > 0
```

且 next 没有新段落强证据：

```text
SOFT_WRAP ↑
```

但不能绝对，因为出版排版可能存在跨段长引语。

---

## M.5 Speech Cue

模式：

```text
张三说道：
<换行>
“...”
```

这里：

```text
HARD_PARAGRAPH
+
CrossParagraphLink=SPEECH_CUE
```

往往比直接 JOIN 更安全。

---

## M.6 段首缩进

中文：

```text
U+3000 U+3000
```

是强段首。

但某些文件每行都缩进。

因此判断：

```text
global indent ratio
```

如果 95% 行都有相同缩进：

缩进区分力下降。

这也是为什么 Profile 必须先做。

---

## M.7 一行拆多段

检测：

```text
全角双空格
多个 tab
HTML-to-TXT残留
```

但应限制误切。

例如正文中：

```text
张三说：　　“不行。”
```

全角空格不一定是新段。

必须结合：

```text
句末
前后长度
引号
```

---

## M.8 Parser 不确定怎么办

保留：

```text
UNCERTAIN
```

UI 可不暴露所有不确定项。

Semantic ContextBuilder 在边界两侧多拿少量上下文，避免语义断裂。

---

# Appendix N：角色识别决策树

## N.1 每个 Dialogue 的决策顺序

```text
1 用户 Override?
  yes → 使用

2 显式 Rule?
  "张三说道"
  yes → 高置信候选

3 CrossParagraph Speech Cue?
  yes → 候选

4 当前 Scene participants
  缩候选集

5 已确认最近 speaker/turn pattern
  计算 turn score

6 Identity/alias exact hit

7 仍有多个候选?
  → ReaderDirector

8 ReaderDirector低置信?
  → UNKNOWN
```

---

## N.2 Speaker Score 不要全靠 LLM

可以保存：

```text
explicit_score
turn_score
scene_score
grammar_score
recent_score
director_score
```

后续用 Gold Set 训练一个小 calibration：

```text
Logistic Regression
```

比一直手调权重稳定。

---

## N.3 Identity Merge 决策

顺序：

```text
USER_EQ / USER_NEQ
↓
强 explicit evidence
↓
强 negative graph
↓
同场独立互动证据
↓
alias/title pattern
↓
Teacher/Director verify
↓
UNKNOWN
```

只有达到高阈值才 auto merge。

---

## N.4 Merge 需要双 Gate

推荐：

```text
semantic_gate
+
damage_gate
```

即：

不仅问：

```text
“是不是同一人？”
```

还考虑：

```text
一旦错，影响多少已生成内容？
```

若一个候选 merge 会影响 12 小时已生成音频，阈值应更保守。

---

## N.5 Same Name 不等于 Same Person

书中可能多个：

```text
李明
王老师
老张
```

必须保留场景、章节、关系证据。

---

# Appendix O：ReaderDirector 各任务协议

## O.1 UTTERANCE

输入：

```text
当前文本
前后一句
quote metadata
```

输出：

```json
{
  "type":"DIALOGUE|NARRATION|INNER_MONOLOGUE|QUOTE|MESSAGE|OTHER",
  "confidence":"H|M|L"
}
```

---

## O.2 SPEAKER

输入：

```text
current utterance
recent turns
scene roles
rule candidates
overrides
```

输出：

```json
{
  "speaker":"r07|UNKNOWN",
  "confidence":"H|M|L"
}
```

不输出角色详细简介。

---

## O.3 IDENTITY

输入：

```text
new mention
candidate role(s)
relevant evidence
```

输出：

```json
{
  "decision":"SAME|DIFFERENT|UNKNOWN",
  "target":"r07|null",
  "confidence":"H|M|L"
}
```

---

## O.4 RELATION

输出有限枚举：

```text
ALIAS
TITLE
KINSHIP
SOCIAL
POSSESSION
CONTROL
EMBODIMENT
IMITATION
CO_PRESENCE
OTHER
```

---

## O.5 VOICE_STATE

输出：

```json
{
  "action":"NONE|START|CONTINUE|REPLACE|END",
  "state":"BASE|PHASE|TEMPORARY",
  "voice_age":"young|adult|middle|old|unknown",
  "style":"...",
  "confidence":"H|M|L"
}
```

---

## O.6 SCENE

输出：

```text
new_scene?
participants_delta
location_hint
```

不要让 0.8B 输出长摘要。

---

## O.7 EMOTION

第一版枚举：

```text
neutral
happy
sad
angry
fear
surprise
tender
tense
disgust
```

intensity：

```text
0
1
2
3
```

不要一开始 0.437812 这种伪精确连续值。

---

## O.8 DIALECT

输出：

```json
{
  "accent":"mandarin|sichuan|dongbei|cantonese|...",
  "confidence":"H|M|L",
  "evidence_sign":"POS|NEG|NONE"
}
```

---

# Appendix P：ReaderDirector 数据生产全流程

## P.1 原始池

来自：

```text
真实小说
v90.7日志
用户修正
Parser Gold
```

---

## P.2 标准化

必须移除：

```text
API key
路径隐私
无关日志
```

保留：

```text
book anonymized id
chapter
segment
context
prediction
evidence
```

---

## P.3 Teacher Label

Teacher 不知道真实角色历史时，要提供：

```text
candidate role table
relevant aliases
scene roles
```

不要让 Teacher 凭角色名字世界知识瞎猜。

---

## P.4 多来源 Agreement

例如：

```text
Rule: r7
Legacy: r7
Teacher: r7
```

→ Silver High。

```text
Rule: none
Legacy: r7
Teacher: r9
```

→ Human Review。

---

## P.5 去重

同一句简单模式不能出现十万遍。

按：

```text
normalized template hash
```

控制过度重复。

否则 Student 会过度学习：

```text
“XX说道”
```

而忽略真正难例。

---

## P.6 难度分桶

```text
EASY
MEDIUM
HARD
EXTREME
```

训练前期可多 Easy/Medium 保基本能力。

后期增加 Hard。

EXTREME：

```text
复杂附身
多人同名
极长省略对话
```

可允许最终 UNKNOWN。

---

# Appendix Q：4060 Ti 16GB 训练运行手册

## Q.1 环境建议

Windows 主系统可保留开发工具。

训练优先：

```text
WSL2 Ubuntu
```

独立 Conda：

```bash
conda create -n readerdirector python=3.11 -y
conda activate readerdirector
```

然后按当前官方兼容矩阵安装：

```text
PyTorch
Transformers
ms-swift
PEFT
bitsandbytes（只在需要QLoRA时）
```

所有具体版本写：

```text
training/env/lock-YYYYMMDD.txt
```

---

## Q.2 下载模型

至少：

```text
0.8B Base
0.8B Post
2B Base
4B/9B Teacher按需要
```

模型 revision 固定。

禁止永远：

```text
main/latest
```

不记录 commit。

---

## Q.3 数据准备

```text
train.jsonl
val.jsonl
test.jsonl
```

同时：

```text
split_manifest.json
```

明确：

```text
哪些书在哪个split
```

---

## Q.4 Baseline

在训练前保存：

```text
0.8B Base few-shot
0.8B Post
2B Post/Base
```

指标。

如果训练后没超过 baseline：

不要因为“loss变低”宣布成功。

---

## Q.5 0.8B Smoke

先：

```text
100 step
```

确认：

```text
没有OOM
loss正常
速度稳定
val pipeline可运行
```

---

## Q.6 正式 Run ID

命名：

```text
RD08_S1_GOLD_R16_20260812_seed20260812
```

包含：

```text
模型
阶段
数据
rank
日期
seed
```

---

## Q.7 Resume

Stage2 不叠 LoRA。

从最佳 Stage1：

```text
resume
```

增加 Hard/Silver。

---

## Q.8 Teacher 推理

单卡最稳策略：

```text
停止 Student训练
↓
Teacher加载
↓
批量产生labels
↓
卸载Teacher
↓
Student继续训练
```

不要同卡同时常驻。

---

## Q.9 9B Teacher 如果 OOM

优先：

```text
4bit inference
batch=1
短context
```

仍不理想：

```text
4B做第一Teacher
```

---

## Q.10 训练记录

每一轮：

```text
GPU温度
显存峰值
step time
tokens/s
```

如果 Windows/WSL 出现明显抖动：

记录，而不是只看平均。

---

# Appendix R：CosyVoice Engine 代码级接口

## R.1 Interface

伪代码：

```kotlin
interface TtsEngine {
    suspend fun synthesize(
        request: TtsRequest,
        cancelToken: CancelToken
    ): TtsResult
}
```

---

## R.2 TtsRequest

```kotlin
data class TtsRequest(
    val jobId: String,
    val generationEpoch: Long,
    val renderUnitId: String,

    val text: String,

    val voiceRevisionId: String,

    val performance: PerformanceControl,

    val priority: Int,
    val deadlineMs: Long?,

    val outputMode: OutputMode
)
```

---

## R.3 PerformanceControl

```text
language
accent
emotion
intensity
speed
volumeStyle
narrationStyle
```

---

## R.4 Result

```text
status
pcm/audio path
duration
token count

llm time
conditioner time
flow time
hift time
total time

backend info
QC
warning
```

---

## R.5 CancelToken

```text
cancelRequested
generationEpoch
```

即使 native 无法立即终止：

```text
epoch mismatch
```

也必须阻止结果 commit。

---

# Appendix S：Scheduler 伪代码

```text
onPlaybackPositionChanged(position):
    update PlaybackFrontier

    if playableBuffer < Critical:
        cancel/deprioritize all speculative jobs
        enqueue P0 TTS
        pause Director except required bootstrap

    elif playableBuffer < Low:
        ensure P0/P1 TTS
        allow limited Director

    else:
        allow SemanticFrontier advance
        allow next chapter analysis

onSeek(newTarget):
    generationEpoch += 1

    cancel queued old jobs
    mark running old jobs stale

    set Playback target

    if target audio absent:
        run Fast Paragraph Recovery
        run Rule Bootstrap
        enqueue urgent TTS

onBookSwitch(book):
    generationEpoch += 1
    pause old speculative book
    activate new book
```

---

# Appendix T：缓存依赖传播

## T.1 改发音

```text
Pronunciation Rule
↓
affected SemanticSegments
↓
RenderUnit semantic hash changes
↓
Audio stale
↓
ParagraphTimeline stale
```

不影响：

```text
Character identity
```

---

## T.2 换角色 Voice

```text
BookVoiceBinding changes
↓
affected RenderUnits synthesis hash changes
↓
Audio ACOUSTIC_STALE
```

不重新：

```text
Speaker attribution
Emotion
Text normalization
```

---

## T.3 Merge Character

语义：

```text
Mention assignments
Speaker identity
```

变化。

如果 Voice 同：

```text
Audio may reuse
```

如果 Voice 不同：

```text
Acoustic stale
```

---

## T.4 Parser 段落 Join/Split

可能影响：

```text
SemanticSegment boundaries
Speaker context
RenderPlan
```

所以必须从 affected Paragraph 重新 Semantic Compile。

但不整书。

---

# Appendix U：详细真机 Test Matrix

## U.1 冷启动

```text
新装
无模型缓存
有模型文件
首次OpenCL
```

记录：

```text
App ready
Model ready
TTFA
```

---

## U.2 热启动

```text
模型已warm
当前书已索引
当前段无音频
```

记录：

```text
TTFA
```

---

## U.3 连续播放

```text
30min
60min
```

记录：

```text
underrun
RTF
PSS
thermal
battery
```

---

## U.4 跳章

场景：

```text
Ch5正在生成Ch6
用户跳Ch20
```

记录：

```text
旧任务取消时间
MaxNonPreemptible
新TTFA
旧结果是否错误commit
```

---

## U.5 切书

A → B → A。

检查：

```text
状态
书签
角色
Voice
缓存
```

---

## U.6 Voice Upgrade

旧 Revision → 新 Revision。

验证：

```text
旧章节不突然变声
新epoch正确
```

---

## U.7 Low Storage

人为把剩余存储降低。

检查：

```text
下载模型拒绝
cache GC
不删Voice raw
```

---

## U.8 Process Death

运行 Job 中 kill process。

重启：

```text
stale lease恢复
partial file清理
播放状态恢复
```

---

## U.9 Parser 修正

用户：

```text
JOIN
SPLIT
```

检查：

```text
source mapping
局部invalidations
书签
```

---

# Appendix V：Agent 每轮最终汇报模板

任何复杂任务完成后，Agent 最终回复/报告必须：

```text
# 任务
TASK-xxx

## 本轮结论
一句话说明是否过Gate。

## 修改
- file
- file

## 原因
为什么这样改。

## 运行命令
完整命令。

## 测试结果
指标。

## Gate
PASS / FAIL。

## 失败/异常
明确列出，不隐藏。

## 产物
路径。

## 文档更新
AGENTS / PLAN / STATE / DECISIONS。

## 剩余风险
尚未解决的。

## 下一步
唯一推荐下一正式动作。
```

---

# Appendix W：v5 的 Source-of-Truth 规则

后续文档优先级：

```text
实际代码/测试/冻结实验结果
>
PROJECT_STATE
>
DECISIONS
>
本 Master Manual
>
局部 ExecPlan
>
旧 v2/v3/v4 文档
>
聊天中的过期方案
```

若 Master Manual 与已经通过的新实验冲突：

```text
先更新 DECISIONS/PROJECT_STATE
再修 Master Manual
```

不能让手册永久落后于代码。

---

# Appendix X：v5 与 v4 的关键区别

v4：

```text
依赖模块化文档补细节
```

v5：

```text
Master 本身完整
+
AGENTS 负责约束
+
ExecPlan 负责具体任务
```

即：

> **“总手册详细”与“Agent 指令简洁”同时成立，不再互相牺牲。**
