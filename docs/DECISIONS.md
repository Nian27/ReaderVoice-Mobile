# DECISIONS.md — 架构决策记录（ADR）

记录不可轻易反复的架构决定。每条：Decision / Reason / Alternatives / Evidence / Consequences / Revisit trigger。
新增决策先于 Master Manual 更新（文档优先级见 PROJECT_STATE）。

## ADR-001 — Character != VoicePack

- **Decision**：角色是小说语义对象，VoicePack 是声学对象，只通过 VoiceBinding（lock_mode: AUTO/USER_SELECTED/USER_LOCKED）关联。
- **Reason**：换声音不能改角色身份；角色 Merge 与声音绑定独立失效传播。
- **Alternatives**：角色内嵌 voice 字段（v90.7 模式，characterRecords 内嵌 voice）——合并/换声耦合，弃用。
- **Evidence**：v5 §2.3；v90.7 固定音色与角色卡耦合导致的 9 字段锁族。
- **Consequences**：Room 三层表（entity / voice_pack / binding）；AudioAsset 绑 voice_revision_id。

## ADR-002 — PhysicalLine != LogicalParagraph

- **Decision**：原文按 PhysicalLine 建索引，LogicalParagraph 是独立派生对象，带 LineBoundary（SOFT_WRAP/HARD_PARAGRAPH/…）与 revision。
- **Reason**：硬换行/一行多段/用户 JOIN-SPLIT 都必须可回溯、可回滚。
- **Evidence**：v5 §8/§15/§26；网文 20/40 字硬换行真实分布。
- **Consequences**：Parser 输出两套实体；source map 全程保留。

## ADR-003 — Student 先单 Multi-task LoRA

- **Decision**：ReaderDirector = 一个 Base + 一个 Multi-task LoRA（8 任务共享），Stage1-3 继续同一 adapter；消融才从 Base 分叉。
- **Reason**：多 adapter 有顺序依赖/干扰/MNN 导出复杂/移动端版本管理难。
- **Evidence**：v5 §60-61；0.8B 小模型多任务共享表示。
- **Consequences**：训练阶段 = 同一 adapter 的延续训练。

## ADR-004 — TTS V1 不重训主模型

- **Decision**：CosyVoice3-MNN 主权重（LLM/Flow/HiFT）V1 冻结，只做工程化（Engine/TtsRequest/调度/取消/缓存/QC/VoiceRevision）。
- **Reason**：现有链路已可听、RTF 接近实时；最大缺口是长篇工程化而非声学。
- **Evidence**：v5 §88；Magic8 Pro 真机数据。
- **Consequences**：TTS 训练任务只在明确 Gate 失败时建立（INSTRUCT 破坏音色/方言不足/情绪不跟）。

## ADR-005 — False Merge > False Split

- **Decision**：Alias/Identity Merge 优先防错；merge 需语义 gate + damage gate；UNKNOWN 合法。
- **Reason**：误 merge 污染全部后续段落与已生成音频，误 split 代价局部。
- **Evidence**：v5 §41/§81；v90.7 负证据 hard block 4.0 语义。
- **Consequences**：Auto-confirm Precision ≥98% 为第一 Gate；合并阈值随影响面增大。

## ADR-006 — 训练数据按书 split + provenance 必填

- **Decision**：Train/Val/Test 按书 70/15/15，系列整体同 split；每条样本带 provenance（LEGACY_V907 / *_AUDITED / *_USER_CORRECTED / EXPLICIT_RULE / TEACHER_* / HUMAN_GOLD）。
- **Reason**：同书切句会过拟合书内风格；provenance 防止把旧系统输出当 Gold。
- **Evidence**：v5 §64/§65/Appendix P。
- **Consequences**：TASK-070 dataset 管线强制字段校验。

## ADR-007 — Performance Memory 因果性

- **Decision**：Identity Memory 可用全书证据；Performance Memory（情绪/关系/秘密/态度）只能用当前时间点之前证据。
- **Reason**：未来剧情污染早期表演（v5 §55）。
- **Consequences**：ContextBuilder 双通道取数。

## ADR-008 — User Locked 最高优先级

- **Decision**：用户修正（角色/别名/Voice/段落 JOIN-SPLIT/发音）编译为 OverrideRule（USER_LOCKED > USER_OVERRIDE > CONFIRMED_RULE > MODEL），即时生效。
- **Evidence**：v90.7 固定音色硬锁行为（深挖 C：manual fixed 全链最高、唯一解除是显式解锁）；FUN-legado SOURCE_MANUAL。
- **Consequences**：CorrectionEvent → OverrideCompiler → BookOverrideRule → RuleEngine 全链为 v5 §123。

## ADR-009 — 基线资产只读冻结

- **Decision**：research/third_party/ 第三方资产不可变（SHA-256 manifest），更新版必须新建 asset_id；公开材料只含自写分析。
- **Reason**：防止"v90.7 改版"冒充同一基线；授权状态未知时保护作者权益。
- **Evidence**：V907_MANIFEST.json / V907_PROVENANCE.md。
- **Revisit**：获得作者书面许可时更新 license_status。

## ADR-010 — 智能设备/后端不写死

- **Decision**：NPU/GPU/CPU 后端由 Device Benchmark 决定，不写死全链 NPU；每一级导出（HF→MNN PC→W8→W4→Android）都跑任务级指标，不以 tensor cosine 代替。
- **Reason**：MNN 3.6.1 基准显示 Hexagon prefill 强但 CPU decode 可能更快；量化错一个 token 业务结论即变。
- **Evidence**：v5 §84-86；CosyVoice 整 LLM NPU Token 坍缩失败记录。
- **Consequences**：HardwarePolicy 决策表 + 分级 Gate。

## ADR-011 — v90.7 is a behavior baseline, not product source

- **Decision**：Use v90.7 as behavior/requirements/data-mining baseline. Do not directly port the full script.
- **Reason**：Large legacy rule system, environment coupling, maintainability, third-party copyright uncertainty.
- **Alternatives**：整段移植 1.4 万行 JS——环境耦合（legado ttsrv、智谱在线 API、原神标签池）且授权不明。
- **Evidence**：V907_CAPABILITY_MATRIX.md（12 域逆向 → 13 决策 = 7 KEEP + 6 REDESIGN + 0 DROP）。
- **Consequences**：TASK-050 只导出行为语义与难例；LEGACY_V907 单独 ≠ Gold。
- **Revisit**：Only if explicit license/permission and a specific function is demonstrably better to reuse directly.

## ADR-012 — Legacy remote graph/upload features are not ported

- **Decision**：ReaderVoice V1 has no automatic remote upload of books, character graph, voice data, or analysis state.
- **Reason**：Offline product goal and privacy.
- **Evidence**：V907_SECURITY_REVIEW.md（S1-S3 远程机制存在但默认关；硬编码智谱 key 教训）。
- **Consequences**：所有状态默认本地；"NOT PORTED"清单挂入 tts-cosyvoice 与 app-android AGENTS。
- **Revisit**：Only through an explicit opt-in cloud feature in a future version.

## ADR-013 — Source offset 口径：byte + codepoint 双轨存储

- **Decision**：所有派生数据统一用 `byte_offset`（原始文件绝对字节）与 `unicode_codepoint_offset`（解码字符流序号）双轨；`android_utf16_offset` 不存储，按需派生计算。
- **Reason**：Python/训练工具天然 code point，Kotlin UI 天然 UTF-16；emoji/扩展字符下三者数值不同，含糊的 `offset` 字段必然出错。
- **Alternatives**：只存 UTF-16 index（Kotlin 便利但训练侧错位）；只存 byte（无字符级定位）。
- **Evidence**：docs/protocols/SOURCE_OFFSET_CONVENTION.md；emoji fixture 13 cp = 16 UTF-16 units = 42 bytes 实测。
- **Consequences**：PhysicalLine 存 byteStart/End + charStart/End；精确 char→byte 表由 Scanner 扫描时产出（collectCharOffsets）。

## ADR-014 — raw_text 不落库（Option B：offsets + lazy read）

- **Decision**：DB 只存行 offsets + metadata，行文本从不可变源 lazy read。
- **Reason**：3.3M 字实测：DB 2.7MB vs 12.3MB（-78%）、导入 99ms vs 229ms、查询持平（+2%）；百书规模差距 ~1GB。
- **Evidence**：docs/experiments/TASK010_LARGE_FILE_REPORT.md §2。
- **Consequences**：TASK-040 Room 落地沿用；可加短行内存缓存；实现抽象保留切换能力。

## ADR-015 — decode revision 与编码声称规则

- **Decision**：(a) 用户编码 override 产生新 decode revision，raw 文件永不修改；(b) 纯 ASCII 声称 UTF-8 confidence 1.0（编码交集）；(c) 含 GB18030 4 字节序列才声称 GB18030，否则 GBK。
- **Reason**：编码错误不该破坏不可变源；"decode 不报错"不是可靠判据（ASCII 是交集、二进制可被勉强解码）。
- **Evidence**：docs/experiments/TASK010_ENCODING_REPORT.md §3/§4。
- **Consequences**：ImportPipeline 顺序 = 解码 → non-text 拒收（>1/16 control → CORRUPT_TEXT）→ 置信度判定（<0.55 → AMBIGUOUS_ENCODING）→ 提交。

## ADR-016 — Book identity 独立于 SourceRevision identity

- **Decision**：`same hash → EXACT_DUPLICATE`；`same filename + different hash → DISTINCT_SOURCE（新 Book，无 parent 链）`；只有显式 reimport/update（或可靠 same persisted URI/显式 parent）才建立 `SOURCE_REVISION`（parent_source_file_id）。长期结构：Book └ SourceRevision r1/r2…，不是 Book→parent→Book 链。
- **Reason**：两个完全不同的小说可以都叫 book.txt；文件名不是版本证据。
- **Evidence**：TASK-010 曾把"同名不同 hash"自动视为 revised edition——错误语义，已修正。
- **Consequences**：ImportPipeline 返回 DistinctSource/SourceRevision 两种 outcome；TASK-040 Room schema：books（逻辑作品）+ source_files（版本），parent_source_file_id 仅在显式更新时设置。
- **Revisit**：未来如引入可信 URI/书源身份系统，可放宽自动 revision 判定。

## ADR-017 — Regex 安全采用 Hybrid 策略（兼容优先）

- **Decision**：Legacy 26 条规则经静态审计+回归+行长度上限后进入 TRUSTED_LEGACY；用户自定义规则必须过 compile validation → feature audit → danger pattern check → line length policy；不因 ReDoS 安全直接换 RE2/J（会破坏 lookbehind/backreference 规则）；未来 RE2-compatible 规则走 safe engine、Legacy incompatible 规则走 audited Java Pattern。
- **Reason**：Legacy 兼容性与安全必须同时满足（TASK-020 §5）。
- **Evidence**：txtTocRule.json 26 条规则含 `(?<=...)` lookbehind 与负向断言（RE2/J 不兼容）。
- **Consequences**：RegexSafetyAnalyzer + RegexCompatibilityAnalyzer + 行长度上限策略（TASK-020 落地）。

## ADR-018 — Boundary confidence 模型（bootstrap）

- **Decision**：边界置信度 = 确定性加权评分的 |join−break| 映射（≥3→0.99/≥2→0.92/≥1→0.82/else→0.6）；AUTO≥0.95 / PROVISIONAL 0.75-0.95 / UNCERTAIN<0.75 的口径以 evidence 为准。不先上神经网络（可审计、错误可解释、数据未就绪）。
- **Evidence**：TASK030_LAYOUT_BASELINE.md（6 类边界错误修复）。
- **Consequences**：权重为初始工程值，TASK-030 Gold（20-30 真实书）后校准。

## ADR-019 — LogicalParagraph 存储 = Option B（spans + transforms）

- **Decision**：DB 只存 source spans + boundary transforms，normalized_text 需要时重建。
- **Reason**：1M 字/18,182 段实测：DB 1.51MB vs 2.87MB（-47%）、导入 83ms vs 189ms（-57%）、查询 64.8ms vs 75.9ms（-15%）；符合"offset + lazy read"架构（TASK-010 ADR-014 同精神）。
- **Evidence**：TASK030_LARGE_FILE_REPORT.md §存储 benchmark。
- **Consequences**：TASK-040 Room 落地沿用；FTS 索引独立评估（§72 考虑全文搜索/UI render/context fetch）。

## ADR-020 — Synthetic whitespace SourceMap 约定

- **Decision**：JoinPolicy 插入的连接空格在 NormalizedSpan 中 transformType=INSERTED_SPACE 且 synthetic=true，source 定位为 null；禁止伪造 byte offset。构建时同步生成映射，禁止事后 substring 反推。
- **Reason**：TASK-010 已证明 rawText 无法可靠反推 byte offset（§46）；synthetic 空格无源可指（§48）。
- **Evidence**：NORMALIZED_SOURCE_MAP.md；G7/G8 测试。
- **Consequences**：任何派生层（语义/音频对齐）遵循同一映射契约。

## ADR-021 — 空行/分隔符/广告行表示

- **Decision**：连续空行**不生成可朗读 LogicalParagraph**（仅 Boundary 证据 + SpacingBlock 记录区间）；SEPARATOR 与 BOILERPLATE 行同样不产段落（§29/§35：不朗读）。
- **Reason**：避免空行产生幽灵段落污染语义层；"不朗读"与"保留原文"并行（源永不删）。
- **Evidence**：TASK-030 separator fixture（3 正文段 + 0 分隔符段）。
- **Consequences**：段落流 = 可朗读内容；未来 UI 显示原文时从 source 还原。

## ADR-022 — Hybrid integer PK + stable UID

- **Decision**：低数量/跨系统对象（Book/SourceRevision/VoicePack/ModelRevision）用 stable UID；高数量热表（PhysicalLine/Boundary/Span/ParagraphRevision）用 INTEGER PRIMARY KEY（无 AUTOINCREMENT）。
- **Reason**：300 万字书 166k 行/61k 段，每个 FK 存 36 字符 UUID 会膨胀（§45-§48）。
- **Evidence**：TASK040_PERSISTENCE_REPORT.md；schema v1 复合 UNIQUE（source_revision_pk, line_no）等。
- **Consequences**：DB 体积可控；跨系统引用走 UID。

## ADR-023 — Stage-level ArtifactRevision DAG

- **Decision**：只有 stage revision（SOURCE/STRUCTURE/PARAGRAPH_RECOVERY 及未来 SEMANTIC/NARRATION/RENDER/AUDIO）用通用 ArtifactRevision+ArtifactDependency DAG；业务实体（Chapter/Paragraph/Span）是真实表 + 显式 FK。
- **Reason**：Generic DAG 管到 item 级会爆炸（§10/§155 坑 1）；item 级依赖用 domain 表 FK（§11）。
- **Evidence**：schema v1；cycle 拒绝测试（G6）。
- **Consequences**：RevisionSnapshot（source+structure+paragraph）成为可复现性核心（§102-§105）。

## ADR-024 — Immutable CorrectionEvent + compiled Override

- **Decision**：CorrectionEvent 是不可变日志（禁止 UPDATE，§35）；OverrideRule 是当前生效约束（§36）；Correction → Override Compiler（§37）；Undo = 新增事件 + head 切换（§40）；优先级 USER_LOCKED>USER_OVERRIDE>SYSTEM_CONFIRMED>AUTO，**USER_LOCKED 不被低优先级覆盖**（实现期修正：低优先级同 match → DISABLED）。
- **Reason**：删除不等于忘记历史；用户锁定永不被自动重解析覆盖。
- **Evidence**：TASK040_PERSISTENCE_REPORT.md；CorrectionTest。
- **Consequences**：Character/Voice 修正复用同一接口。

## ADR-025 — No INSERT OR REPLACE

- **Decision**：核心持久化表禁止 REPLACE 语义（可能 DELETE+INSERT → rowid 变 + FK cascade 误删）；用 @Upsert / INSERT ... ON CONFLICT DO UPDATE。
- **Reason**：REPLACE 是 Room/SQLite 常见坑，会级联删关联数据（§44/§155 坑 2）。
- **Evidence**：schema v1（artifact_head 用 ON CONFLICT DO UPDATE）。
- **Consequences**：AGENTS.md 红线 + G11 静态检查。

## ADR-026 — Derived FTS is rebuildable cache

- **Decision**：全文搜索用派生 FTS 表（可重建），Paragraph 数据仍是 source of truth（§68/§69/§110）。
- **Reason**：Option B 存储（ADR-019）下 normalized_text 不权威；FTS 只是加速缓存。
- **Evidence**：TASK040_STORAGE_REPORT.md（gold 书 +4.4% 体积、build 4ms、query 2.8ms）。
- **Consequences**：FTS 不强制上线；未来按需启用。

## ADR-027 — RevisionSnapshot consistency

- **Decision**：一次任务（如 ReaderDirector 请求）必须绑定确定版本的 (source, structure, paragraph, 未来 character/semantic/voice_binding) 快照；读取 heads 后不中途 getLatest。
- **Reason**：后台升级角色图时 LLM 可能读到"前半旧+后半新"的混合状态——极难复现（§104/§155 坑 3）。
- **Evidence**：REVISION_PROTOCOL.md；snapshot() 实现 + 测试。
- **Consequences**：所有下游分析入口以 snapshot 为参数。

## ADR-028 — Identity Model：NarrativeEntity + Identity + Embodiment 分离（TASK-060 消融）

- **Decision**：冻结 Model B——Mention → NarrativeEntity → Identity（cluster）→ Embodiment（interval）→ VoiceState。Model A（Entity + typed relationships 无独立 Embodiment）在 7 case 消融中无法无补丁表达 surface/body ≠ actual acting identity（附身/冒充），弃用。
- **Reason**：v90.7 的固定音色/临时换声/音龄证据行为 + 附身/控制 Hard Case 需要 identity/body 分离；TASK-050 已证明旧系统"临时不写卡"是正确方向。
- **Evidence**：V907_BEHAVIOR_CONTRACT（契约 1/2/4）；7 case 消融（普通别名/群体/关系/控制/附身/冒充/UNKNOWN）。
- **Consequences**：character 表族按此建模；CONTROL/EMBODIMENT/IMITATION/POSSESSION 永远 ≠ SAME_PERSON（§25 回归写死）。

## ADR-029 — Non-destructive merge（IdentityCluster + Lineage）

- **Decision**：Merge 不做 destructive（禁 DELETE entity_B + UPDATE mentions）；用 IdentityClusterMembership + IdentityLineage（MERGED_INTO/SPLIT_FROM/SUPERSEDES）表达；旧 entity/evidence/mention 永不删除。
- **Reason**：v90.7 非原子 merge + 启发式备份恢复是已知硬伤（TASK-050 L2）；非破坏式使 split 无需"从备份恢复一切"（§14），User Correction/Audio Cache 可重定位（§16）。
- **Evidence**：TASK-050 Domain 08（非原子、备份启发式、fixed 不迁移问题）；V907_BEHAVIOR_CONTRACT 契约 5。
- **Consequences**：Split = 新 revision + cluster membership 变更；Mention/Evidence 不丢。

## ADR-030 — Identity whole-book / Performance causal-only

- **Decision**：双查询 API——`resolveIdentity(entity, revision)` 允许全书证据（后文"张老师=张明"可反推早期）；`queryEffectiveVoiceState(identity, position, revision)` 只允许 position 之前证据。禁止共用 getLatestCharacterState。
- **Reason**：Identity Memory 与 Performance Memory 必须分离（v5 §55；TASK-060 §21/§22/§63）；未来剧情污染早期表演是不可复现错误。
- **Evidence**：TASK-040 RevisionSnapshot；v90.7 无此概念（状态即时覆盖）。
- **Consequences**：数据库层接口即强制因果边界（voice_state/embodiment 查询带 position 谓词）。

## ADR-031 — Narrative VoiceState 与 User Voice Constraint 分离

- **Decision**：Narrative VoiceState（小说内角色声音状态：phase/临时伪装）与 VoiceBindingConstraint（用户坚持的 VoicePack）是两层；临时状态不再因用户锁被整体拒绝（v90.7 L3 行为仅作 LEGACY_BEHAVIOR 记录，TASK-060 为 INTENTIONAL REDESIGN）。
- **Reason**："张三故意压低声音"（故事状态）与"用户固定 Voice A"（声学选择）可共存：TTS Planner = Voice A + temporary performance control。
- **Evidence**：TASK-050 Harness L3（fixed 拒临时——LEGACY）；v5 §44 lock_mode。
- **Consequences**：TASK-060 只定义 VoiceBindingConstraint 概念（不建 VoicePack）。

## ADR-032 — CharacterCompiler streaming observations

- **Decision**：CharacterCompiler 输入为 `Sequence<CharacterObservation>`（chapter/window → batch persist → release），禁止 wholeBookText 全驻留 API；working set 随处理量有界（PERF060-P2/P3 契约）。
- **Reason**：PRE-TASK060 PERF REVIEW：编译器集合 ~109MB 可释放、persistence 基线 6MB——compiler lifetime 是可控项，接口层强制。
- **Evidence**：PRE_TASK060_PERF_REVIEW.md（S0-S6）。
- **Consequences**：Character build 与结构编译器解耦（上游已持久化段落 → 流式读取）。

## ADR-033 — MODEL ENTRY FREEZE（TASK-070 → TASK-080 之间禁止插入前置任务）

- **Decision**：TASK-070（Gold Set）与 TASK-080（Baseline 三线）之间不插入任何新前置任务，除非 TASK-070 Gate 无法满足。TASK-080 首测顺序锁定：同一 locked test 上 Rule-only baseline → 0.8B zero-shot → 2B upper bound，再决定任何 LoRA。
- **Reason**：Gold/上下文格式（NARRATION_IR_SCHEMA / CONTEXT_BUILDER_SCHEMA）未冻结前训练即废；三线输入形态一致（DirectorContext）是可比性的前提（手册 §179 顺序）。
- **Evidence**：TASK070_GOLD_SET_REPORT.md（G1-G12 全绿）。
- **Consequences**：任何"先做 X 再训练"的提案必须先在 TASK-080 三线上给出数据再谈；emotion/dialect 模型同理（NarrationIR v1 恒 null）。

## ADR-034 — Zero-shot 用 post-trained 模型；Base 只做 protocol probe（TASK-080 修正）

- **Decision**：模型实验链冻结为 A=Rule-only baseline；B=`Qwen3.5-0.8B`（post-trained）zero-shot；C=`Qwen3.5-2B`（post-trained）zero-shot upper bound；D=`Qwen3.5-0.8B-Base` 仅 100–500 samples protocol probe（JSON valid/candidate obedience/EOS/输出长度），**不进能力排行榜**。TASK-090 LoRA 学生 = `Qwen3.5-0.8B-Base`（官方定位：纯预训练 + 预训练 chat control tokens，适合 LoRA；post-trained 模型定位：prototyping/task-specific）。
- **Reason**：官方明确 Base 不适合 direct interaction；零样本能力测的是 post-trained 模型。
- **Evidence**：HF Qwen3.5-0.8B/-Base/2B 模型卡（2026-08 核）。
- **Consequences**：probe 与 zero-shot 的角色不混；manifest 锁 revision；training/AGENTS.md 冻结事实同步更新。

## ADR-035 — Speaker attribution 第一上限 = Candidate Recall；Candidate Discovery 独立于 Speaker Cue（TASK-095 架构结论）

- **Decision**：ReaderDirector（0.8B/4B）的选择能力不是当前瓶颈；瓶颈是候选构造器对真实说话人的系统性漏检。候选 = `Ccue ∪ Ccurrent ∪ Crecent ∪ Cscene ∪ Cnew ∪ Clocked`（union + typed eligibility filtering + provenance），**不是** intersection；Speaker Candidate 携带 sources（CUE/CURRENT_MENTION/RECENT_MENTION/SCENE_ACTIVE/NEW_MENTION/USER_LOCKED）。实体发现（Mention Discovery）必须独立于 rule_speaker——CharacterStore 不得由 speaker cue 自举产生。首次出场角色走 constrained NEW_MENTION/copy-span（surface 必须逐字存在于输入），禁止开放式生成角色名。TASK-100 Teacher 蒸馏前置硬 Gate：Candidate Recall ≥95%（stretch 97-98%）+ First-appearance 处理路径 + local opaque candidate ID（每样本随机 permutation）+ TRUE_UNKNOWN/identity 不回退。
- **Reason**：A/B/C 200 条实验（真实小说，人工标注 158 known）：Gold Candidate Recall 61.4%（regex 候选 = 实体过滤候选，A=B）；候选污染不是 recall 主因（A 选中污染候选 19 次被实体过滤清零但不改变 recall）；recall 主因 = 候选窗口不含上下文提及实体（cue 失败/叙述提及/首次出场）。recent_context 提及 ∩ 实体集 → 85.4%。开放生成（C）acc 39.9%/UNKNOWN 47/200 → 否决。负例 23 个根因 = 实体集自举缺陷（"张居正"因 cue 提取失败从未进 rule_speaker → 不在实体集）+ 首次出场 + 少量标注误差。
- **Alternatives**：① 继续扩展 cue 词表（"只好/好奇/恭敬地"无底洞，否决）；② 全 AI 开放生成（幻觉+UNKNOWN 高，否决）；③ 独立 NER 模型（手机资源不值，否决——规则高召回 + ReaderDirector 只处理模糊项）。
- **Evidence**：abc_human_label_200.jsonl（200 条人工标注，T/recent_context 与 segments 0 mismatch）；PLAN-20260816-095（095A Gate：recall 61.4%→85.4%、polluted 19→0、acc@known 36.1%→45.6%、Task070GateTest 全过）；85.4% = entity-store-limited baseline（非"已解决"）。
- **Consequences**：TASK-095 插入 TASK-100 之前；095A 落地 SpeakerCandidateCompiler（SpeakerCandidateCompiler.kt）；095B 独立 Mention Discovery（目标 recall ≥95%）；095C constrained fallback；095D 2-8 候选 dev + opaque ID；095E 新 frozen Test v2。85.4% 之前不得进入 Teacher 蒸馏（否则 Silver 含 15% 无正确答案样本）。

## ADR-036 — 候选输出用局部随机 opaque ID（095D 落地）

- **Decision**：ReaderDirector 永远只看局部候选 ID（C0/C1/C2…，每样本随机 permutation），不直接暴露持久 entity ID；`{candidates:[{id:"C0",name:"张居正",evidence:["RECENT_MENTION"]}]}`，答案 `S=C0`。协议扩展 `S=C3`（已知实体）/`S=M1`（新 mention）/`S=UNKNOWN`（信息不足）三态。
- **Reason**：TASK-090 已证 id==name → 模型学会复制名字（id-echo）；local opaque ID 从协议层打掉名字回显/slot anchoring/persistent-ID memorization，比堆 id-echo 数据更干净；NEW_MENTION 从 UNKNOWN 拆出（"信息不足"≠"首次出现新人物"）。
- **Alternatives**：继续全局持久 ID + id-echo 数据增强（已证明不彻底）。
- **Evidence**：TASK-090/090.1 名字回显限制；TASK-095 候选协议设计（用户规格 §十/§十五）。
- **Consequences**：095D 训练/评估数据全部 local opaque ID + permutation；评估侧映射回实体做 recall/accuracy 统计。

## ADR-037 — Candidate budget 永远在 evidence fusion + ranking 之后（TASK-095A 教训）

- **Decision**：Candidate Compiler 两阶段化：Discovery（各 source 只贡献证据，不占位）→ EligibilityFilter → CandidateRanker（evidence-aware scoring）→ Budgeter（top-K 截断）。**禁止 source 按执行顺序消耗位置配额**（截断必须发生在 provenance merge + eligibility + ranking 之后）。候选是"证据排序"结果，不是"字符串长度/加入顺序"结果。附带纪律：禁止把 dev 错误反推的具体名字/别名写进正式规则（name-specific hardcoded alias 禁止，G-B6）——它们只能作为 regression fixtures 验证通用 Identity Resolver。
- **Reason**：095A 初版 SCENE_ACTIVE（≤12 全量）先执行占满 max=10，把真正有用的 RECENT_MENTION 截断 → recall 77.2% vs 修复后 85.4%。这是"容量控制发生在 ranking 之前"的架构缺陷。另：诊断期手工别名（老三→刘杰等）若写成正式规则 = 看 dev 错误填答案，不是 Identity Resolution。
- **Alternatives**：① 提高 max 配额（治标，膨胀又回来）；② 手工别名表（污染泛化，禁止）；③ 无截断全量候选（候选膨胀，4B 不可用）。
- **Evidence**：095A 截断 bug（77.2%→85.4%）；候选膨胀 30/case（v3 实体集）；"狄青麟冷 vs 狄青麟" longest-match 错误；"张远 vs 张远玩" 清洗误删。
- **Consequences**：SpeakerCandidateCompiler 演化为 Discovery → Filter → Ranker → Budgeter 四段；MentionLexicon（带证据统计）替代裸 HashSet；Recall@K 曲线决定 budget（K 不预设）；Dev-B（不人工看答案）验证泛化。

## ADR-038 — AI-assisted Universal Mention Discovery（TASK-095B.2 定案）

- **Decision**：叙事实体 Mention Discovery 以 AI 为主判定器（4B/0.8B 输出原文 exact span，host 验证 span 逐字存在），规则降级为 seed/validation/fallback（cue 主语/呼语/自我介绍/称谓官职仅作 Level 0 确定性发现）。MENTION 成为 ReaderDirector 的正式任务（Multi-task：MENTION/SPEAKER/IDENTITY/RELATION/VOICE_STATE），不引入独立 NER 模型。Mention Discovery ≠ Identity Resolution（"老三"→"刘杰"必须由 IDENTITY 任务判断，禁止 name-specific hardcoded alias）。
- **Reason**：Cross-domain Mention Challenge Set（150 条/15 域/186 gold）三路对比：Rule 29.6%（中文 cue 特化，英文/音译/长名/群体全 0%）；2B few-shot 88.2% exact/95.7% contain；4B few-shot 93.5% exact/**98.4% contain**/**99.5% exact-span**（零造词）。"什么是人物称谓"是语义问题（张居正/纳威·隆巴顿/Jean-Luc Picard/O'Brien/红衣主教/AI管家），纯规则是无底洞；AI copy + span 验证在"发现权 vs 造词权"之间成立。
- **Alternatives**：① 频率+中文规则（v3 实体集 37 万膨胀、跨域 0%——否决为主线）；② 传统 NER（PER/ORG/LOC 标签不覆盖 ROLE_MENTION/GROUP/AGENT，否决）；③ 独立 NER 模型（手机资源不值，MENTION 并入 ReaderDirector 多任务）。
- **Evidence**：runs/task095/095B2_mention_feasibility.md；challenge_mentions_150.jsonl；mention_qwen3.5-4b_fs_results.jsonl；095b2_eval_mention.py（可复跑）。
- **Consequences**：095B.3 训练 0.8B MENTION task（4B few-shot 蒸馏 + span 验证）；BookMentionIndex（导入期批处理 MENTION，阅读期查索引）；095C 简化为 M7 = MENTION 已发现 + IdentityStore 无匹配 → NEW_MENTION/provisional entity；Candidate 公式 Ccue∪Ccurrent∪Crecent∪Cscene∪Cnew∪Clocked 中 Cnew 由 MENTION 支撑。

## ADR-039 — NarrativeEntity Type Policy（TASK-095 audit_300 定案）

- **Decision**：MENTION 提取的实体类型冻结为五类：PERSON（具体人物）、GROUP（场景内群体）、NON_PERSON_AGENT（AI/系统/精灵/器灵/机械管家/可发声机构）、ROLE_MENTION（职位/称谓/关系称谓，指代场景内具体人）、UNKNOWN_AGENT（未具名但有明确身份指代的叙述主体）。以下**一律不是 mention**：作品/影视/游戏/杂志名称、《书名》、物品/产品/武器/装备名（A 片/Pokeball 2/武装机器狗/碧罗三清秘卷）、地点/建筑（指挥使府/惊雁宫/战神殿/冶铁场）、时间/朝代/年号（嘉靖年间）、律法/典籍/功法名（大明会典/大明律）、疾病/病毒（MERS）、会议/活动名（国际反恐会议）、泛指职业/阶层类别（练气士/采药人/演员/领导/财阀）、比喻/典故引用（"像小龙女一样"）、纯头衔名词（冥界圣人/军师祭酒作名词）。
- **Reason**：audit_300（222 条人工审核）FALSE_MENTION 24 条（7.5%）全部集中在上述类别——Teacher 对"实体/非实体"边界没有政策，把书名、产品名、地点、病毒、头衔当实体；同时 NONE 桶 6 条 MISS（呼语"维迪/远"、叙述主语代词"她/我"、称谓"尊上"）证明呼语/代词覆盖不足。类型政策不清时禁止先修模型（先 ADR 再改标签，failure A1-4）。
- **Evidence**：audit_300.jsonl（222 条审核，FALSE_MENTION 24/MISS 9/True NONE 81.8%/Semantic Recall 85.4%/Precision 87.6%——A1 Gate FAIL）；FALSE 类别分布见 audit 审核 note。
- **Consequences**：MENTION teacher prompt v2 必须显式携带非实体清单 + 呼语/代词规则；mention_train.jsonl 需按 v2 重新标注；challenge_mentions_150 冻结不动（历史对照），新标注用 mention_fs_v2 prompt_version。
- **Revisit**：当出现政策未覆盖的新类别时，先扩展本 ADR 再改 prompt，禁止 prompt 内联新增类别。

## ADR-040 — Legacy VOICE_STATE action prediction invalid（TASK-095 A5 定案）

- **Decision**：**废除 START/CONTINUE/REPLACE/END/NONE 作为 ReaderDirector 的直接预测目标**。旧 VOICE_STATE 五分类任务（build_sft_v1.build_voice_state，纯随机状态机）停止继续优化、停止扩展样本；其 200 条训练样本与 A5 的 75 条 dev（dev_voice_state_v1.jsonl）仅保留为 **historical diagnostic**，不作为模型能力 Gate。**替代定义（ADR-041）**：ReaderDirector 只预测文本中的 VOICE_EVENT（NO_EVENT / TEMP_SET / TEMP_CLEAR），动作由程序确定性推导。
- **Reason**：相同 observable input 映射到多个随机分配的 gold action。证据：① 训练 200 条全为合成状态机（TARGET=第N句，prompt 无任何真实文本/对话上下文）；② 相同 current_state 对应多 action（phase=ADULT 对应 START 15+NONE 15，熵 1.0 bit；temp=START 对应 REPLACE 10/END 5/CONTINUE 3，熵 1.42 bit；temp=CONTINUE 三向，熵 1.56 bit）→ P(Y|X) 天然多解、任务不可判定；③ 按 current_state 最优猜测理论上限 = 49.3%，v2-780 实测 45.3% 已逼近上限 → 模型不是学不会转移，而是输入不足以确定标签；模型按 temp 有无押 START/REPLACE 是给定信息下的最优策略（捷径正确）。MENTION 训练对 VOICE_STATE 无灾难性遗忘（A5 均衡 75 条：394=0.387 < v1=0.453 == v2=0.453）；原 dev 的 0.714→0.143→0.286 是 7 条 NONE 偏斜 gold 的评估假象。
- **Evidence**：dev_voice_state_v1.jsonl（75 条 5 动作×15，sha256=4644d3ae…）；runs/task090/eval/vs_a5_{394,v1_783,v2_780}__dev.json；current_state 歧义统计（熵 1.0-1.56 bit）。
- **Consequences**：v3 及以后训练数据**删除**旧随机 VOICE_STATE action 样本（或仅作 reducer 单测参照，不再作为 LLM SFT task）；replay 混合中 VOICE_STATE 槽位由 VOICE_EVENT 数据替代；任何 Agent 不得再调旧任务的 replay 倍率（×12/×20）来修复遗忘。
- **Revisit**：若未来引入带真实文本上下文的五分类直接预测（非随机 gold），需先重写本 ADR 并建立新 Gate。

## ADR-041 — VoiceEvent + deterministic VoiceStateReducer（TASK-095-VS2 定案）

- **Decision**：**ReaderDirector 的 voice 任务重新定义为 VOICE_EVENT 检测**（文本发生了什么），状态动作由纯程序推导：
  - VoiceEvent = { type: NO_EVENT | TEMP_SET | TEMP_CLEAR, style?, target?, scope?, evidence_span, confidence }；**type != NO_EVENT 时必须携带 exact evidence_span（逐字来自原文）**；scope 属于 { UTTERANCE, UNTIL_CLEAR, UNKNOWN }（第一版必带，防 voice leakage）。
  - VoiceStateReducer(previousState, event) 纯确定性：none+NO_EVENT→NONE；tempX+NO_EVENT→CONTINUE；none+TEMP_SET(X)→START；tempX+TEMP_SET(X)→CONTINUE；tempX+TEMP_SET(Y)→REPLACE；tempX+TEMP_CLEAR→END；none+TEMP_CLEAR→NONE/invalid；utteranceX+boundary→END。完整转移矩阵必须 100% unit test 通过，不依赖 AI。
  - 明确禁止：emotion/prosody/scene-exit 不视为 temp voice change——愤怒地喊（EMOTION=ANGRY 而非 TEMP_SET）、压低声音（仅当确属角色长期伪装/临时变声才 TEMP_SET，prosody 单句不算）、退场/换场（scene exit != voice clear，角色可能保持伪装回来）。
- **Reason**：旧任务把语义事件和状态机逻辑搅在一起——同一 current_state 多解（ADR-040）证明 AI 学状态转移不可行；而文本里发生了什么（模仿/恢复原声/压低声音/无变化）是 LLM 擅长且可验证（evidence_span host 校验）的语义判断。五动作一个不丢，全部转为程序确定性推导，消除 AI 的投机空间。
- **Evidence**：ADR-040 歧义证据链（熵 1.0-1.56 bit、上限 49.3%）；MENTION 管线已验证 AI 判语义 + host 验证据模式可行（Exact-span validity 100%）。
- **Consequences**：VS2 feasibility 集 = 300-500 条真实书上下文（rules 高召回检索 + 4B few-shot 标注 + host evidence_span 验证），hard negatives 含 emotion/prosody/scene-exit；Gate VS-F：4B TEMP_SET Precision 不低于 98%、evidence validity 不低于 99%、Macro-F1 达标；通过后才设计 v3 数据混合（MENTION hard + VOICE_EVENT + 旧任务 replay），禁提前训练 0.8B。Reducer 落地位置：data-room（Kotlin，最终运行时）+ inference（Python，feasibility 期）。
- **Revisit**：scope 枚举或 style 取值若在真实书样本中暴露新形态，先扩本 ADR 再改 prompt；TEMP_SET Precision <98% 时禁止进入 v3 训练。

## ADR-042 — VoiceControlEvent ontology：VOICE_OVERRIDE / PERFORMANCE / NONE（TASK-095-VS2.2 定案）

- **Decision**：**废弃三分类（TEMP_SET/TEMP_CLEAR/NO_EVENT），升级为四类 VoiceControlEvent**：
  - **VOICE_OVERRIDE_SET**：有效声线身份/音色被临时替换（模仿他人声音、换成老人嗓音、捏嗓子装作女人、变声器）→ 才允许进入 VoiceStateReducer。
  - **VOICE_OVERRIDE_CLEAR**：明确解除此前 override（恢复成自己本来的声音、不再捏着嗓子、那种苍老嗓音消失了）。
  - **PERFORMANCE**：仍是这个人的声音（VoiceProfile 不变），只是当前怎么说——压低声音/低声/大喊/轻声/颤抖/冰冷/诱惑/含糊 → 走 PerformanceInstruction（volume/prosody/style/emotion → 当前 RenderUnit），**不进入 VoiceStateReducer**。
  - **NONE**：与声音控制无关（装样子/模仿动作或术法/恢复秩序/恢复本来面貌/固有音色描述）。
  - 结构化输出：{ kind: VOICE_OVERRIDE|PERFORMANCE|NONE, operation: SET|CLEAR?, style?, target?, scope?, evidence }；evidence 必须逐字来自原文；scope 属于 {UTTERANCE, UNTIL_CLEAR, UNKNOWN}（第一版 scope 错不阻塞 feasibility）。
  - 推荐层次式判断（Step A voice-related? → Step B override SET/CLEAR → Step C style/target/scope），最终一次输出。
- **Reason**：VS2.1 A/B（同一 410 条，只改 prompt）证明三分类是定义冲突：v1 宽松 → 误报多；v2 严格 → 真正事件被压成 NO_EVENT。最典型 = 压低声音——它确实是声音控制信息（volume/style），但又不是声线身份替换，不能简单扔成 NO_EVENT，否则 ReaderDirector 会学会压低声音完全不用管（对有声书是错的）。v2 还暴露方向混淆（切换成了自己本来的声音 → 模型知道与声音有关但三分类逼它在 SET/CLEAR 选错；基德恢复本来面貌 → evidence 作用于面貌而非声音）。**Performer 和 VoiceOverride 是两个不同概念，拆开后 Character VoiceState / CosyVoice instruction / RenderUnit 全部更顺**。
- **Evidence**：VS2.1 A/B（vs2_annotated_v1.jsonl 410 条 vs vs2_annotated_v2.jsonl 410 条）：v2 消除四类系统性误报（固有音色/物理原因/情绪语气/行为伪装），但 5/9 保留的 TEMP_SET 是故意/突然压低声音（无身份伪装，按新定义应为 PERFORMANCE）；TEMP_SET selection rate 6/120=5%。
- **Consequences**：
  1. **统计口径修正**：TEMP_SET candidate recall proxy 5% 改名为 **heuristic positive yield（候选启发式正例产出率）**——候选集是规则高召回检索不是人工 Gold，任何 Agent 不得把它当 Recall，也不得为提升 yield 把误报加回来。
  2. VoiceStateReducer **只处理 VOICE_OVERRIDE**（SET→START/CONTINUE/REPLACE、CLEAR→END）；PERFORMANCE 不进入 Reducer，直接编译为 PerformanceInstruction（volume/style/emotion 作用于当前 RenderUnit）。
  3. VS2.2 = 同一 410 条 + 同一 4B + 同一 temperature + 同一 retrieval，只改 prompt v3（四类 ontology + 成对 hard examples）→ 重跑 → 人工审核所有 OVERRIDE + 分层抽检 PERFORMANCE/NONE。
  4. **新 Gate（ontology feasibility）**：evidence validity 100%；VOICE_OVERRIDE_SET Precision ≥95%；VOICE_OVERRIDE_CLEAR Precision ≥95%；PERFORMANCE→OVERRIDE confusion ≤5%；NONE hard-negative ≥98%；四类 Macro-F1 等人工 Gold 后正式计算。产品阶段再要求 Override SET Precision ≥98%。
  5. **不启动 0.8B LoRA**（标签空间未定稿前训练最浪费）；PASS 后才构建 VoiceEvent Gold（真实小说上下文，覆盖 emotion/prosody/固有音色 hard negative）→ 0.8B LoRA。
- **Revisit**：PERFORMANCE 的 style/scope 若需细分（如 prosody 粒度）或 scope 枚举暴露新形态，先扩本 ADR 再改 prompt。

## ADR-043（2026-08-20）：VS8 冻结、评估 prompt 同源纪律、数据补丁纪律

**状态**：Accepted（TASK-095-VS8）。**背景**：VS8-FORMAL 在 checkpoint-780 续训链上三阶段（338→353→361）收敛；DEV+回归双 PASS 后 Test-v1 一次性运行。

**决策**：
1. **冻结**：checkpoint-361（vs8_2/v0-20260820-153040）＋ vs8_train_v1p2.jsonl（sha fbba8fd2…）。VS7_DEV_FRESH 仅用于 DEV 门；VoiceState-Test-v1（73）只允许一次性评估，失败进 VOICE_STATE_V2_BACKLOG，永不调参。
2. **评估 prompt 同源纪律**：VS8 系评估 prompt 必须与训练数据字节一致，从冻结 sampler（095b3_vs8_train_v1.py）提取并做字节级校验；禁止手工重打。证据：简版 prompt 使 SET R 32.1%→同版 100%（假失败），且 train_v1 与 to_swift 的 BIND prompt 存在一行差异。
3. **数据补丁纪律（VS8.1/8.2）**：只加全新模板（禁止 dev/Test 句子）、冻结 roster、确定 seed、exact+归一化双层 dedup；CLEAR/UNKNOWN 覆盖缺口按失败树定位后补（根因例：load_vs7_hard 对 NONE event 跳过 BIND 视图 → BIND O=UNKNOWN 仅 5/662）。
4. **残余归账**：注意到…声线不对 与 陌生的声音 两族 → VOICE_STATE_V2_BACKLOG（V2 数据补丁，不触碰 Test-v1）。

## ADR-044 — Android 自动章节索引的来源和显示语义（MOBILE-001）

- **Decision**：Book Package 的章节导航持久化 `Chapter.titleRaw`（原文章节头），不持久化 `titleDisplay` 作为唯一标题；`titleDisplay` 仅是解析器内部的派生后缀。`manifest.json` 记录 `structure_format_version` 与 `structure_origin`：仅 `AUTO_LEGACY_RULEPACK` 的旧格式允许后台重编译，`USER_EDITED` 永不自动覆盖。
- **Reason**：v1 将派生标题持久化，用户看到残余逗号和截断标题；为修显示而改变 `cleanTitle` 又让同一私有书的 confirmed 数从 620 变为 571，说明清洗函数参与全局 Resolver，不能作为 UI 修补点。原文标题和自动结构来源把显示、解析语义和用户修订边界分开。
- **Evidence**：`runs/mobile_001_structure_device_gate/report.md`；USB v3 结果为 620 anchors、完整标题、source SHA 不变；Host parser/core/app test PASS。
- **Consequences**：任何标题显示改动先落在 presentation/persistence 层；若需改变 `cleanTitle`，必须视为 Chapter Compiler 语义变更并重跑 TASK-020 Gate。未来结构编辑器提交时必须把 manifest origin 写为 `USER_EDITED`。

## ADR-045 — 第一版 SpeechRoute 按叙事身份固定分流（MOBILE-002）

- **Decision**：第一版不做按角色重要性、设备性能或缓存状态的动态 TTS Router。`NARRATOR` 与 `UNKNOWN_SPEAKER` 固定目标为 Audio8；`CHARACTER` 固定目标为 CosyVoice3-MNN。引擎未通过真机 Gate 时 route 必须 fail-closed 并报告 unavailable，不静默改走另一条引擎路径。
- **Reason**：旁白是大规模吞吐层，角色是身份保持层；让次要角色在两引擎间动态切换会破坏“角色演员库”的核心语义，也混淆 Audio8 的旁白吞吐实验与 CosyVoice 的角色音色验证。
- **Evidence**：`RENDERUNIT_V1_SCHEMA.md` R3、`VOICE_PROFILE_SCHEMA.md` engine assets 隔离规则；Audio8 Android Gate 尚未开始，CosyVoice Android runtime 尚未抽取进本仓库。
- **Consequences**：SpeechRoute 只选择已验证 target；MOBILE-002B 为 Audio8 旁白链，MOBILE-002C 为 CosyVoice 角色链，MOBILE-002D 才统一调度。未来低端设备/NPC 动态路由必须另立 ADR 和 Gate。

## ADR-046 — 核心端侧 Runtime Gate 先于 Scheduler 与阅读 UI（MOBILE）

- **Decision**：MOBILE 的实现顺序调整为：ReaderDirector Android MNN（CPU adapter → GPU capability Gate）→ Audio8 Android ORT Gate → CosyVoice ABI bridge/固定 PCM Gate → Scheduler/Cache → 阅读高亮 UI。Book Package 已完成，保留但不再扩展空播放器功能。
- **Reason**：没有端侧 Director 和两个真实 TTS 引擎时，Scheduler 与播放器只能连接 fixture，无法验证用户实际得到的离线生成体验。另一方面，Audio8 当前只有 PC Gate FAIL 与 Android 未测，不能被预先标为 MNN/GPU/NPU renderer；CosyVoice 已有真机基线，当前只需最小封装，不能挤占 Director 与 Audio8 的可行性验证。
- **Evidence**：`PLAN-20260822-054-mobile-003-readerdirector-android-runtime.md`；`PLAN-20260821-050-mobile-000d-audio8-feasibility.md`；`PLAN-20260821-053-mobile-002-speech-runtime-contract.md`。
- **Consequences**：QNN HTP 继续是 Director 的后续优化支线；MNN GPU 必须以实际 backend 命中、正确性与同条件性能决定；Audio8 先走官方 ORT Android 以降低首个真机 Gate 风险，只有失败后才评估 MNN 转换。

## ADR-047 — ReaderVoice 的多引擎 MNN 必须先通过共同运行库 Gate

- **Decision**：同一 ReaderVoice APK 内只能有一套每 ABI 唯一的 `libMNN.so`、`libMNN_Express.so`、`libllm.so`。在接入 Director 与 CosyVoice 前，先用 CosyVoice 当前携带的 runtime 加载冻结 Director 模型，CPU load/生成通过后才测 OpenCL；未过 Gate 时停止合并，改走重编一个引擎到共同 MNN build 的路径。
- **Reason**：CosyVoice 与独立 Director probe 的同名运行库 SHA-256 和文件大小均不同。Android 打包不会保留两份同名 `.so`，所以各自单 App 的 PASS 不能推导为同 App 共存。
- **Evidence**：`runs/mobile_002_cosyvoice_extraction/source_snapshot.md`；Cosy `libMNN.so=48,769,280B`、Director probe `libMNN.so=55,382,208B`，其余同名核心库也不同。
- **Consequences**：共同 runtime CPU Gate 成为 MOBILE-003 M1；OpenCL Gate 在相同 runtime 上执行；构建成功只说明链接通过，不能代替真机 model load/PCM Gate。

## ADR-048 — Android 16KB ELF 对齐是所有端侧 Native Gate 的前置条件

- **Decision**：目标 Android APK 的每个 `arm64-v8a/*.so` 必须先通过 16KB ELF Gate，才允许模型 load、PCM 或性能 Gate。检查标准是所有 `PT_LOAD` segment alignment 均为 `0x4000`，同时以 `zipalign -v -c -P 16 4` 验证包对齐。预编译 4KB `.so` 不得因 ZIP 对齐通过而被保留。
- **Reason**：Magic8 Pro 对当前 debug APK 报告 Cosy JNI、MNN core、LLM 等 ELF LOAD segment 未对齐；ZIP 对齐只能决定 APK 内 entry 位置，不能修复二进制自身的程序段布局。把这两层混淆会造成“构建/安装看似成功、设备仍拒绝兼容”的假通过。
- **Evidence**：`runs/mobile_003_director_runtime/report.md`；Android 官方 16KB page-size 指南。
- **Consequences**：NDK r27 统一采用 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` 重建 native 产物；`libcosy_conditioner_exec.so` 以 4KB 供应产物 BLOCKED，不进入目标 APK。16KB 只验证可兼容性，绝不等价于模型/PCM/性能通过。
- **修正（2026-08-22）**："conditioner_exec 无源码"结论错误——该库是改名为 `.so` 的 ET_EXEC 可执行文件，内嵌 Usage（`conditioner.mnn voice_dir target_tokens.csv output_dir prompt_tokens prompt_frames`）与输出 JSON 和 `mnn-jni/CosyVoiceFlowConditionerBenchmark.cpp`（目标 `CosyVoiceFlowConditionerBenchmark.out`）逐字一致；源码在库。BLOCKED 依据仅剩 4KB ELF，重编该目标即可解除。证据：`runs/mobile_003_director_runtime/` 隔离包 llvm-nm/strings 实测。

## ADR-049 — Audio8 高质量 codec teacher 采用 MNN offline QNN/HTP，停止以 codec student 作为速度主线

- **Decision**：Audio8 的 codec token→PCM 生产候选冻结为高质量、非蒸馏 T16 teacher 的 MNN 3.6.1 offline QNN plugin + SM8850/V81 context。既有 FT2 codec student 因固定 test/unseen 质量失败只保留 diagnostic，不继续作为速度主线；下一阶段转向 Slow/Fast AR 持久 runtime 与完整 text→wav 串接。
- **Reason**：同一高质量 teacher 在 direct `qnn-net-run` 下约1.3–1.57 RTF，但在持久 MNN plugin 下50/200次稳定为0.320854/0.321114；与 direct-QNN逐float相同，4个真实case对ONNX最差cosine=0.999098、SI-SDR=27.43dB。性能瓶颈主要是runner/context/I/O集成口径，而不是teacher必须蒸馏。继续codec蒸馏会用已证实的质量损失解决一个已经消失的速度问题。
- **Evidence**：`E:/AndroidStudioProjects/audio8tts-mnn/docs/reports/AUDIO8_MNN_QNN_HTP_20260824.md`；同目录 artifacts 的V81 skel/FastRPC/CDSP日志、多样本PCM与200次监控。
- **Consequences**：ReaderVoice仍不得把独立codec probe标为Audio8 TTS已接入。必须先完成真实文本的Slow/Fast AR多帧loop，再接codec context；共同MNN ABI、16KB APK、系统级内存/功耗和长时温升另设Gate。200次期间Thermal Status=0但HAL nsp瞬时最高约85.3°C，后台预生成必须支持节流。
- **Supersedes**：ADR-046 中“Audio8先走官方ORT Android、失败后才评估MNN”的执行顺序；该路线已被真实MNN-QNN/HTP证据取代，但ADR-046的“核心runtime先于UI/Scheduler”原则继续有效。

## ADR-050 — ReaderDirector MNN prefill 默认后端改为 Hexagon 0:23（DSP RoPE 根因修复后）

- **Decision**：ReaderDirector（Qwen3.5-0.8B，MNN 3.6.1 fork）在 SM8850 上的 prefill 默认配置由 `config_rd_cpu_greedy.json` 改为 `config_rd_hex_greedy.json` + `MNN_HEX_LAYER_HTP=0:23`（全 24 层上 Hexagon）。CPU 后端保留为无损兜底与对拍基线（logits cos = 1.0）。
- **Reason**：DSP RoPE kernel 的配对/写回偏移用错（`half_head_dim` 而非 `rope_half_head_dim`，透传区写成 `[half_head_dim, head_dim)`），导致全部 6 个 full_attention 层数值错误、层 7 在下游被放大成断崖。修复后同会话 A/B：0:23 logits cos 0.956355 → 0.994986（argmax 95826 → 124483），12:23 0.990833 → 0.993881，7:7 0.981213 → 0.990193，24-token 生成文本与 CPU 逐字一致；透传区逐位不变、纯 linear-attention 的 0:0 逐字节不变（对照成立）；skel A/B 18 样本计时无回归。**即"最准"与"最快"不再互斥**，P15-6 的"默认回 CPU"结论（当时以 0:23 cos 0.956 未过 Gate 为前提）失效。
- **Evidence**：`runs/mobile_000b2_hexagon_la/m2-3-nodealign/report.md`（P16-FIX 段）、`p15/P16_FIX_SECTION.md`、`p15/P16_3E_ROOTCAUSE.md`、`PLAN-20260916-057-dsp-rope-partner-offset-fix.md`；代码 `MNN-3.6.1-hexdebug-20260914/source/backend/hexagon/htp-ops-lib/src/dsp/rope_ops.cc`（skel md5 4e38fd00…）。
- **Consequences**：App 侧（MOBILE-004 QwenEngine）的 `Backend=cpu` 是 M2 冻结预期，**不因本 ADR 自动切换**——切换需在 App 内单独过 Gate（HTP Correctness 需从 NOT PASSED 重跑）。绝对速度仍依赖设备状态：0:23 vs cpu 的 margin 本次会话为 2~12%（P15 会话 20%），绝对数字需安静设备复测。非 FA 算子的 ~0.99 精度赤字仍在（P16-2 未启动）。
- **Supersedes**：`report.md` P15-6 §6「最终后端路由结论」中"默认后端切回 CPU"的结论；P15-4 对 0:23 的排序结论恢复有效，但其 1.391 s 绝对值来自不同设备状态的会话，不与本次数字直接比较。

## ADR-051 — 正确性 Gate 的参照系：必须同时报 cos(vs CPU fp16) 与 cos(vs CPU fp32)

- **Decision**：本项目所有 LLM 端侧后端的正确性 Gate，参照系一律**双报**：`cos(logits vs CPU precision=low)` 与 `cos(logits vs CPU precision=normal)`；判断"某后端是否有精度缺陷"时，**必须以后者为地板基准**，禁止用单一 fp16 CPU 参照断定某后端"有赤字"。
- **Reason**：P15-6 依据 `cos(vs CPU fp16) = 0.956 → 0.995` 判定"Hexagon 路径存在既有的 0.99x 精度赤字"，并设定 0.997187 为目标。P16-2 实测该前提不成立：**fp16 模式自身相对 fp32 就差 0.0048**（`cos(CPU fp16, CPU fp32) = 0.995184`，两次运行逐字节可复现），而 Hexagon 与 fp16 CPU 的差是 0.0050（`cos(0:23, CPU fp16) = 0.994986`）——同量级。换参照系后 **`cos(HTP 0:23, CPU fp32) = 0.996345` > `cos(CPU fp16, CPU fp32) = 0.995184`**，且 argmax 与 fp32 一致。逐算子归因（`added = cos_in - cos_out`）也显示注意力/RoPE/归一化新增误差 ≤1e-4，全部差异来自 W4 dense 的 fp16 舍入。⇒ 所谓赤字是参照系带来的，不是 Hexagon 缺陷，0.997187 在 fp16 口径下任何实现都到不了。
- **Evidence**：`runs/mobile_000b2_hexagon_la/m2-3-nodealign/p15/P16_2_SECTION.md`、`report.md` P16-2 段、`p162_incr.js`、`p162i/**`（含 in/out 配对 dump）、`p162/var_cpu_fp32*.logits.txt`。
- **Consequences**：① 停止"补 Hexagon 精度赤字"方向（P16-2 记为负结果）；唯一推荐下一步回到 P16-3（Dense asymmetric W4 + HMX，速度向）。② `cos(vs fp32)` 只能当**数值噪声地板**度量，**不得**当作任务质量结论——同一 prompt 的 24-token 生成中 CPU fp32 反而给出史实错误（610 年），fp16 CPU 与 HTP 0:23 都给对的 618；任务正确性仍按任务级 Gate 判定。③ 新增 lesson：㉓"路径 A 相对 B 有赤字"必须先测 B 自身对更高精度参照的偏差；㉔ 逐节点 cos 表会把上游误差算进下游节点，必须 cos_in/cos_out 配对算 added。

## ADR-052 — 性能归因必须绑定将发布的配置；对称路径互为参照物

- **Decision**：① 任何"下一个优化目标"的结论，必须在**即将发布的那个配置**上重新测量后才允许排期；跨配置继承的性能归因一律视为待验证假设。② 同一模块内的一对对称路径（如 host↔device 的 H2D/D2H 搬运）在优化前必须交叉比对实现与吞吐，禁止只优化其中一条。③ 性能画像统一用**边跑边流式抓取**（或文件式落盘开关），禁止事后 `logcat -d` 读（会被其它进程冲掉）。
- **Reason**：P15-2 的"Convolution(Dense) 占 54%、是最大单项"是在 `MNN_HEX_LAYER_HTP=12:23` 上测的 —— 那个配置有 12 层跑在 **CPU** 上，54% 是那 12 层的 CPU 卷积。在 P16-FIX 之后真正要发布的 `0:23`（全 24 层上 HTP）上重测，Dense 在 host 侧只剩 **22 ms**，DSP 侧 219 ms 且与 host 异步重叠；真实分布是 `LinearAttention 47% + host↔DSP 搬运 49%`。若按继承结论直接做 asymmetric W4 + HMX，整轮优化会投到一个占 15%、且被重叠掩盖的项上。搬运内部再拆解发现：`convert`(fp16↔fp32) 是 NEON 的、仅 0.3 ms，而 **D2H 的 NC4HW4 重排 620 ms**（174 MB ⇒ 280 MB/s），同文件的 H2D 对称路径却是 5 GB/s —— 差距 18 倍纯粹来自实现：D2H 是「plane 外层 + 逐元素两次整除 + 每次调用堆分配 + 跨 8 KB 散写」，H2D 是「channel 外层 + NEON」。按 H2D 同构重写后 repack 640→61 ms、0:23 prefill A/B 每轮均优（12 样本 min 1.591→1.412 s），且四配置 logits 与 24-token 文本**逐字节/逐字不变**。
- **Evidence**：`runs/mobile_000b2_hexagon_la/m2-3-nodealign/p15/P16_3_SECTION.md`、`report.md` P16-3 段、`p163_attrib.ps1`/`p163_repack_sim.js`/`p163_repack_ab.ps1`、`p163/**`；libMNN.so md5 `edf6acde9764a002a1f49d62091de36b`。
- **Consequences**：① 新增 lesson ㉕（对称路径互为参照物）、㉖（归因绑定发布配置）、㉗（画像边跑边抓）。② D2H 重排重写属**纯提速、零数值变化**，正确性 Gate 用"logits 逐字节不变"即可判定，无需重跑 cos 门槛。③ 修复后最大单项变成 `cpu|LinearAttention`（按设计留 CPU：DSP 的 GDR prefill 实测 1.48 s > CPU 0.86 s）⇒ 继续压 prefill 需要结构性改动，不再是 kernel 微优化。④ 该收益要等 App 侧 HTP Gate 重跑通过（ADR-050）才进用户路径。
## ADR-053 — VoiceDesign 音色创建：外置权重的 Tokenizer Decoder 为当前唯一稳定配置

- **Decision**：① Qwen3-TTS VoiceDesign 在端侧做「文字设计音色」时，**Talker 与 Tokenizer Decoder 都采用外置权重**（`.mnn` + `.mnn.weight`），接受 Decoder 约 459 s 的一次性代价以换取峰值 HWM 0.90 GB。② **不使用** FP16 Decoder、**不使用** 同 RuntimeManager 内的分阶段 Module 释放、**不改动** CosyVoice enrollment 接口。③ VoiceDesign 完成后生成参考音频并自动 enrollment 为统一 `VoiceProfile`；VoiceProfile 可缓存，后续朗读无需重跑 VoiceDesign。④ 后续优化 Decoder 必须单独开 **Decoder-HTP / 独立 Runtime** 分支，不得扰动该冻结基线。
- **Reason**：本设备（SM8850/BKQ-AN90，Honor HyperHold 生效）长期可用内存紧张，实测 **HWM 超过约 1.2 GB 后 HyperHold 换出与系统内存压力显著增加**，表现为应用被判定 idle、页被换出、推理慢到不可用（实测卡在 frame 97 后 10 分钟零输出）。五配置真机对比：

  | 配置 | anon | 峰值 HWM | Decoder | 结论 |
  |---|---:|---:|---:|---|
  | 全内嵌基线 | 1.11 GB | 2.17 GB | 约 20 s | FAIL，内存过高 |
  | **全外置（正式）** | **0.61 GB** | **0.90 GB** | **约 459 s** | **PASS，已完整跑通 2 次** |
  | Decoder 内嵌 | 0.76 GB | 1.86 GB | 约 20 s | FAIL，HyperHold |
  | Decoder FP16 | — | ~1.13 GB | 约 20 s | FAIL，PCM 最大误差 2693 LSB |
  | 分阶段释放 + 内嵌 Decoder | 0.97 GB | 1.93 GB | 约 20 s | FAIL，释放无效且峰值过高 |

  分阶段释放实测：释放 GraphB/codec head/frame embedding/code predictor 六个 Module 后，anon 仅从 575,448 kB 降到 518,196 kB（**仅降 56 MB**，远低于预设 ≥400 MB Gate）。**在当前共享 `RuntimeManager/Executor` 生命周期下，释放 `Module` 后相关 anon 未被有效归还，实测仅下降 56 MB，因此分阶段释放无法满足本设备内存 Gate。**（该结论仅针对当前实现与真机环境，不泛化为 MNN 所有 Runtime/Session 用法。）
- **Evidence**：`CosyVoice3-MNN-Plus/docs/VOICEDESIGN_DEVICE_MEMORY_2026-09-16.md`、`docs/experiments/VD-DEVICE-MEMORY-20260916.md`；代码 `voicedesign-app/src/main/cpp/voicedesign_jni.cpp`（`logRss` 逐模块打点 + `writeProgress` 逐帧进度）。
- **Consequences**：① 新增 lesson ㉘：**「能跑通」优先于速度** —— 端侧内存 Gate 必须按设备实际可用内存定，不能按理论最低值定。② 新增 lesson ㉙：**释放 `Module` ≠ 归还 anon** —— 任何"分阶段加载省内存"方案都必须先做释放有效性实测，不能按对象生命周期推断。③ 新增 lesson ㉚：**FP16 不可用于 vocoder 类最后的波形合成级** —— 逐样本 PCM 差异达 2693 LSB（cos 0.99992 完全不能作为判据）。④ UI 必须给分钟级任务的逐帧进度：native 每帧写 `progress.txt`，UI 每秒轮询；ETA 用最近 20 帧滑动窗口（前几帧含 OpenCL kernel 编译，用全程平均会高估十几倍）。⑤ VoiceDesign 与「参考音频克隆」**两条**音色创建路径均已端到端验收（`voice-1789540901382` / `voice-1789551292376`），共用同一个 `installEnrolledVoiceProfile()`，产出同一种 `VoiceProfile`，下游合成侧零改动。⑥ **新增 lesson ㉛：dither 是 enrollment 的前置条件，不是设计路径的补丁** —— `CosyVoiceEnrollmentNative.enroll()` 内 CAMPPlus 的前置 fbank 取 `log(能量)`，而**合成音频的静音是精确 0 样本**（实测 7.76 s 样本中 19.9% 为 0，fbank 774 帧中 12 帧全 0）⇒ `log(0) = -inf` ⇒ NaN 传播 ⇒ campplus 输出非有限 ⇒ 返回 **37「说话人特征无效」**。真实录音有本底噪声不会踩到，但用户完全可能把合成音频（含 VoiceDesign 产物）当参考导入克隆。**因此在两条路径的 enroll 之前都无条件叠加 ±1 LSB 确定性 dither（LCG 可复现，约 -90 dB 听不见）**；实测克隆路径由 exitCode=37 变为 exitCode=0、4.88 s 注册成功。


### ADR-053 增补（2026-09-17）— GraphB 权重存储格式、运行时驻留与部署边界

**核心结论**

```text
Storage compression != Runtime-memory compression.

GraphB FP16 external weight:        2.82 GB on disk
GraphB weight-only INT4:            0.884 GB on disk

但 CPU/MNN runtime：
  RssAnon ≈ 3.52 GB（fp16 3.53 GB / int4 3.52 GB，基本不变）

=> 在当前 MNN 3.6.1、当前 GraphB 转换方式和 CPU backend 路径下，
   会将量化权重展开为执行侧表示，
   因此 weight-only quantization 不能用于本项目的 runtime RAM 降低。
```

同一实测下另有 **2.82 GB → 884 MB 的磁盘收益**；其对 OpenCL / HTP **原生量化执行**的潜在收益**尚未验证**，
不得据此把该量化路线封死。

**真机实测数据（BUILD-GATE 验证过的干净进程）**

| 打点 | anon |
|---|---:|
| `RSS[nativeRun-entry]` | 560,540 kB |
| `RSS[after-prompt-build]` | 737,428 kB |
| **`RSS[after-prefill]`** | **3,688,028 kB** ← **+2.81 GB ≈ `graphb28_v6_fp16.mnn.weight` 的 2.82 GB** |
| `RSS[frame-9-done]` | 4,016,452 kB |

- **与 backend 无关**：cpu / opencl 的 `after-prefill` anon 只差 **76 kB**（3,700,956 vs 3,700,880 kB）。
- **内存形态**：`smaps` 分类显示 **3,863.8 MB（96%）落在 `[anon:scudo]`**（native malloc），
  `[anon:dalvik*]` 只有 28.5 MB → **与 Kotlin 层无关**，是 native 分配。
- **不是泄漏**：41/55/69/83 帧时 anon 均为 4,015,568 / 4,016,212 / 4,016,224 / 4,016,224 kB，**一次性到位后稳定**。
- **后果**：decode 从干净进程的 **0.5 秒/帧** 降到 **2.07 秒/帧**，占设计总耗时的 **71%**。

**六条修法的实测结果**

| # | 方案 | 实测结果 |
|---|---|---|
| ① | `setExternalPath(d, 2)` + `USE_CACHED_MMAP=2` + `MMAP_FILE_SIZE=4096` | **anon 确实降到 0.27 GB** ✓ 但 MNN 预分配 **正好 4 GB** 的 `0_0_0_1_0.static` 后 **prefill 死等**（20 秒 CPU 增量 0 jiffies）✗ |
| ② | ONNX 内嵌 | GraphB 权重 2.82 GB **> 2 GB protobuf 上限**，物理不可能 ✗（ONNX 里 309 个 initializer 中 253 个走 external_data）|
| ③ | `--weightQuantBits 8` | 转换"成功"但 `.weight` **字节数完全没变**（2,821,857,280），未生效 ✗ |
| ④ | `--weightQuantBits 4 --weightQuantBlock 64` | `.weight` **2,821,857,280 → 884,093,704 B（3.19×）** ✓ 分片传输后在设备重组，**SHA256 与本地逐位一致** ✓ 但**运行时 anon 仍 3.52 GB** ✗ |
| ⑤ | 蒸馏更小的 GraphB | **未试**（属模型研发，应独立立项，见下）|
| ⑥ | 拆成多个 <2 GB 的内嵌 `.mnn` | **未试**；且需先证伪（见下）|

**⑥ 为什么不能直接做**：即使把 2.82 GB 拆成 4×705 MB，**若运行时同时加载四个模块，总权重与执行表示不变，RAM 不会凭空下降**。
且 GraphB **每生成一帧都要重过全部 28 层**，若每帧 load→forward→destroy，加载开销很可能大于推理本身。
所以 ⑥ 真正可能成立的只有两个价值：**(A) 避开 external `_RebuildExternalOp` 的执行开销**；**(B) 真正做到分块生命周期**（但受上述每帧重入限制）。
**→ `GB-SPLIT-P0` 已执行（2026-09-17），结论：⑥ 终止。**

**实验设计**：同一 4 层、同一 FP16 权重、同一 frozen input（全零），
A = external（`graphb4l.mnn` 74,720 B + `.weight` 403,136,512 B），
B = embedded（`graphb4l.mnn` 403,209,928 B）。
两份均为 `--fp16` 从同一 ONNX 转换，**总权重与总大小相同**，唯一差异是外置/内嵌。
传输到设备后**分片重组，SHA256 与本地逐位一致**（A=`1799ae78…`，B=`8a8fb9c2…`）。
每组 2 次 warmup + 12 次计时，权重加载完成后读 `/proc/PID/status`。

**实测结果**

| 指标 | A external | B embedded | 判定 |
|---|---:|---:|---|
| `loadMs` | **3 ms** | **1,048 ms** | 内嵌把权重搬运提前到 load |
| `firstMs`（首次 forward） | **1,134.3 ms** | **961.1 ms** | 内嵌略快，但只是把代价从 load 挪过来 |
| `loadMs + firstMs` | **1,137 ms** | **2,009 ms** | ★ **内嵌总开销更大** |
| **`steadyMs`（稳态）** | **107.66 ms** | **106.07 ms** | ★★ **差 1.5%，统计噪声级** |
| **`RssAnon`（稳态）** | **334,064 kB** | **334,020 kB** | ★★★ **差 44 kB，完全相同** |
| `VmHWM`（峰值） | **1,192,420 kB** | **1,551,488 kB** | ★★ **内嵌高 351 MB ≈ 权重大小** |
| `hiddenSum` | 0.000000 | 0.000000 | 一致（全零输入→全零输出）|

**三条结论**

1. **内嵌不改变稳态执行速度**（107.66 vs 106.07 ms）→ **价值 A（避开 `_RebuildExternalOp`）不成立**：
   `_RebuildExternalOp` 的代价只是**从 first-forward 挪到了 load**，稳态毫无变化。
2. **内嵌不改变运行时权重驻留**（334,064 vs 334,020 kB）→ **⑥ 不是内存方案**。
   无论 external 还是 embedded，403 MB 权重最终都进 anon；拆成 4×705 MB 也同理
   （四块同时加载时总权重与执行表示不变）。
3. **内嵌的峰值反而更高**（1,551,488 vs 1,192,420 kB，+351 MB）→ external 的 lazy rebuild 在峰值上更优。

**→ ⑥（拆分内嵌）三条价值全部证伪，终止。不做 28L 分块重构。**

**该实验同时验证了你的预判**：「⑥ 很可能救不了 RAM」成立；
且额外发现「价值 A」也不成立（代价只是位移，不是消除）。

**⑤ 的定位**：若目标是把 2.82 GB 缩到 1B/0.8B/0.5B，它会同时降低磁盘、运行时权重、内存带宽、计算与加载压力，
是**结构性方案**；但它是**模型研发**（hidden/logits 蒸馏、codec trajectory 保真、描述遵循、speaker identity、EOS 行为、
最终 CosyVoice enrollment、音色相似性/多样性），**必须独立立项为 "Small VoiceDesign"，不作为部署优化的尾巴**。
**触发条件**：只有当 2.82 GB GraphB 真的成为产品不可接受的长期约束时才立项。

**⑤ 的定位**：若目标是把 2.82 GB 缩到 1B/0.8B/0.5B，它会同时降低磁盘、运行时权重、内存带宽、计算与加载压力，
是**结构性方案**；但它是**模型研发**（hidden/logits 蒸馏、codec trajectory 保真、描述遵循、speaker identity、EOS 行为、
最终 CosyVoice enrollment、音色相似性/多样性），**必须独立立项为 "Small VoiceDesign"，不作为部署优化的尾巴**。

**BUILD-GATE（必须前置，本轮实证抓到隐藏多轮的部署失败）**

```text
Layer A  APK：本地 APK SHA == 设备 pm path 拉回的 base.apk SHA
Layer C  .so：本地【strip 后】SHA == 设备 nativeLibraryDir 下 .so SHA
               （★ 必须比 strip 后的：Gradle 的 stripDebugDebugSymbols 会剥符号，
                 与 jniLibs 原始 .so 的 SHA 必然不同。本轮第一次比对用了原始 SHA，
                 误判为 LAYER C FAIL。）
Layer D  运行时 build tag：Kotlin APP_BUILD_ID + native VD_BUILD_ID（JNI_OnLoad 里打）
```

- **实证价值**：Layer C 抓到了 `pm install -r -d` 返回 `Success` 但设备 **.so 仍是旧版**
  （因为安装前紧跟的 `am force-stop` 打断了替换）。此前多轮"UI 点击没提速"的测量全部建立在旧 `.so` 上。
  修法：安装后**逐层验证**，不匹配就重试，匹配上才允许进入测量。

**新增 lesson**

- **㉟ 「压缩存储」≠「压缩运行时内存」** —— weight-only int4 把 `.weight` 从 2.82 GB 降到 884 MB，
  但 CPU 后端执行时会展开为执行侧表示，anon 不降。**任何内存优化方案的判据必须是【运行时 RssAnon】，不是【文件大小】。**
- **㊱ 启动阶段的日志不能靠事后 `logcat -d` 反推代码有没有执行** —— logcat 环形缓冲区会把启动早期（几秒内）的日志冲掉。
  本轮因此误判过一次"新代码没编进去"。**native 侧必须写文件**（`rss.log` 就是这么做的，它是可靠的）；
  Kotlin 侧若要在启动早期留证据，也应落文件。
- **㊲ BUILD-GATE 必须校验「安装后的 stripped `.so`」** —— 见上。
- **㊳ external weight 的真实代价是双重的** —— 既慢（`_RebuildExternalOp`）**又**把权重从 file-page 变成 anon。
- **㊴ 「配置开关」与「实验残留」必须区分，且清理前必须先验证** —— 曾经把 `vd_prec.txt=low` 当成实验残留删掉，
  结果 anon 立刻从 **3.53 GB 涨到 5.95 GB** 并卡死。根因：`Precision_Low` 让 GraphB 的 fp16 权重**保持低精度**
  （anon 增量 2.81 GB ≈ `.weight` 的 2.82 GB），而 `Precision_High` 会把它**展开成 fp32**（增量 4.95 GB ≈ 2.82×2）。
  **→ 任何「清理」动作前必须先确认该配置对基线是否必要；且这类配置应显式写进代码，不藏在文件开关里。**
  此前 ADR-053 只记了"慢"，本轮补上"占 anon"。

**已确认可用的正式配置（冻结）**

```text
VD_BASELINE_20260917
- GraphB:        graphb28_v6_fp16.mnn（外置 2.82 GB）
- Precision:     Low  ★ 必要条件，非偏好
                 Precision_High 会把 fp16 展开成 fp32：anon 3.53 GB → 5.95 GB，decode 卡死
                 已把 low 写成代码默认（不再依赖 vd_prec.txt 文件开关）
- Fast Decoder:  独立 RuntimeManager + 内嵌 tokenizer_decoder_static_t96.mnn
                 （decoder 633,087 ms → 6,570 ms，96×）
- 设计进程:      android:process=":design"（不加载朗读链 .so）
- EnrollmentCore: 唯一实现（Gate E1 三产物 SHA256 bit-exact）
- 采样参数:      ensureLlmSamplerConfig() 运行时合并
- int4:          仅留档（PC 侧产物保留），已退出自动路径；不得因"文件存在"改变产品行为
- BUILD-GATE:    Layer A（APK SHA）+ Layer C（安装后 stripped .so SHA）+ Layer D（运行时 build tag）
```

**Baseline 验证记录（2026-09-17 19:46，USB 连接，真机 PASS）**

运行时配置指纹（从 logcat 实时抓取，非事后推断）：
```text
I VDS : VDS_PREC=low
I VDS : VDS_GRAPHB file=graphb28_v6_fp16.mnn variant=fp16
```

`design-loop.log`：
```text
[进度] 设计完成（223s）：OK steps=90 stop=EOS prefill=12888ms decode=194099ms decoder=5798ms
      wav=172800 samples (7.20s) finite=yes min=-0.4920 max=0.6064
      ENROLL_OK tokens=125 frames=250 ms=6217 [decoder=INTERNAL+ISOLATED-RTM]
[进度] 音色创建完成：VD_BASELINE（125 Token）
音色已注册: voice-1789645798109 / VD_BASELINE / tokens=125 / frames=250
目录内容: prompt-speech-tokens.csv, prompt-cond.bin, spks.bin, source.wav, rand-noise.bin, profile.json, design.json
=== 闭环成功，总耗时 223844 ms ===
```

逐阶段耗时（本次）：
| 阶段 | 耗时 | 对比 |
|---|---:|---|
| prefill | 12,888 ms | |
| decode | 194,099 ms | **占 87%，唯一瓶颈** |
| **decoder** | **5,798 ms** | vs 外置 633,087 ms → **109×** |
| **enroll** | **6,217 ms** | vs 18,158 ms → 2.9× |
| **总耗时** | **223,844 ms（3.73 分钟）** | vs 最初 759 s → **3.4×** |

`rss.log` 完整内存序列：
| 打点 | anon |
|---|---:|
| `before` | 77,036 kB = 0.08 GB |
| **`after-prefill`** | **3,701,352 kB = 3.53 GB** ← Precision_Low 正确值（High 时 5.95 GB）|
| `frame-0-done` | 4,016,700 kB = 3.83 GB |
| `frame-9-done` | 3,478,516 kB = 3.40 GB（部分权重被换出）|
| `rtm-reset` | 3,473,200 kB = 3.39 GB |
| `decoder-internal-loaded` | 838,204 kB = 0.82 GB ← **释放生效，降 2.6 GB** |
| `decoder-post-forward` | 1,622,792 kB = 1.58 GB |
| `decoder-released` | 647,572 kB = 0.63 GB ✓ |
| `VmHWM` | 4,777,104 kB = 4.56 GB |

**→ `VD_BASELINE_20260917` 全链 PASS，配置与实测数据均已冻结。**

**唯一未解决瓶颈**：`decode` 占 87%（194 s / 224 s），根因是 GraphB 外置权重在 prefill 时被
`_RebuildExternalOp` 读进 anon（+2.81 GB），造成内存压力。六条修法全部实测失败（见上），
仅剩 ⑤（Small VoiceDesign 蒸馏）作为结构性方向，触发条件见下。

**部署纪律（本轮实证）**：长任务（设计 3-4 分钟）在**无线 adb 下会 100% 掉线**（5 次尝试全部发生在
decode 阶段，短操作从不掉线）——推测是内存峰值触发系统级回收，连带挤掉 adbd/无线调试 socket。
**做端侧长任务验证必须用 USB。**

**GraphB 变体选择已改为显式**（`vd_graphb_variant.txt`，默认 `fp16`）：
曾经写成「`graphb28_v6_w4.mnn` 存在就用 int4」，结果一次实验遗留的权重文件悄悄改变了产品行为 ——
**实验模型绝不能因为「碰巧在目录里」就进入正式路径。**

**设备侧已清理**（2026-09-17）：`graphb28_v6_w4.mnn*`、`graphb4l_*`、`tokenizer_decoder_static_t96.mnn.weight`（残留）、
`tokenizer_decoder_static_t300.mnn`（已由 t96 取代）、`/data/local/tmp` 分片。
模型目录 **6.9 GB → 4.4 GB**。PC 侧 `E:/AndroidStudioProjects/vd-graphb-int4/` 与 `gb4l-ab/` 保留留档。

**设计总耗时实测**：759 s（12.7 分钟） → **206 s（3.4 分钟）**，其中 decoder 96× 提速，剩余 71% 卡在 decode。
**注意**：该耗时随设备整体负载波动（实测 decode 在 0.32 / 0.5 / 1.86 / 2.07 秒/帧之间大幅变化），
**单次测量不足以下结论，须多次采样**。

### ADR-053 增补（2026-09-18）— NPU 使用策略：从「整句布尔判定」到「分阶段判定」

> **本节在写入当天被自己的埋点推翻并重写过。原始推论（存在一个 256 阈值、长句因此失去 NPU）
> 已被证伪，证伪过程保留在下方「原始调查过程」中，供后续避免重犯。**

**决定性证据（NPU-R1 埋点，2026-09-18）**

原来的 fallback 会 `deleteRecursively()` 掉第一次（hexagon 配置）的报告，导致「失败那一次究竟
跑了什么」永久丢失。补上保留埋点后立刻拿到了 attempt-1 的真实数据：

```json
attempt-1（hexagon 配置）:
  inputTokens        157
  generatedTokens    276
  continuousHexagon  true          ← 长句【是】拿到了 NPU 连续解码，不是 false
  npuPrefillMs       2344.8
  decodeMs           8568.4
  tokensPerSecond    32.2

attempt1.fallbackReason     = Token 连续重复：longestRun=19
attempt1.llmExitCode        = 0
attempt1.sessionResetCalled = true
```

**真实因果链**

```text
长句 → continuousHexagon = true（NPU 确实在跑）
     → NPU 生成的 token 出现 longestRun = 19 的连续重复
     → CosyVoiceLlmOutputQuality.collapseReason() 判定「Token 连续重复」
     → 质量门拒绝 → 整句回退 CPU（reset + 删目录 + 用 cpu 配置重跑）
```

**由此被证伪的三条**

1. **「存在 256 阈值」是伪相关**。原 6 组对照表里只有 #1、#2 有效（它们没触发回退，读到的就是
   attempt-1）；#3–#6 读的全部是 attempt-2 的 **CPU** 报告，那份 `config-cpu-*` 路径不含
   "hexagon"，`continuousHexagon: false` 是必然结果。**失败组的数字来自另一条执行路径**，与句子
   长度无关 —— 所谓阈值是我把两条路径的数据混在一张表里得出的。
2. **「长句失去 NPU」不成立**。恰恰相反，长句第一次尝试拿到了 NPU 连续解码。
3. **门拒绝的原因不是「没进 NPU」**，而是 **NPU 输出质量坍缩**（`longestRun=19`）。

**真正的问题**：为什么 NPU 在长序列上会产生连续重复？

这正是项目文档记录过的「整层/连续 Hexagon 解码导致 Token 坍缩」，而现在 stage-filter 只把
layer0 的 `q_proj` 放 NPU 也仍会出现。

**NPU-R1b 观测：坍缩段的位置分布（2026-09-18 第二次长句，500 token）**

```text
attempt1.fallbackReason = Token 连续重复：longestRun=14, 全程500
  len12@[86-97]    止于 19%
  len8 @[102-109]  止于 21%
  len9 @[252-260]  止于 52%
  len8 @[330-337]  止于 67%
  len14@[428-441]  止于 88%

attempt-1: inputTokens 255, generatedTokens 500（= max_new_tokens 上限，无 EOS）,
           continuousHexagon true, decodeMs 14847, tokensPerSecond 33.68
```

**由位置分布得出的三条推论**

1. **坍缩段散布全程（19%→88%），不是集中爆发** → **「前段 NPU / 后段 CPU」方案被否定**：
   坏段从 19% 就开始，按位置切分没有依据。
2. **短句通过、长句失败是概率差异，不是阈值** —— 生成 43~119 token 时撞不上，生成 276~500
   时撞得上。这解释了为什么它此前看起来像一个长度阈值。
3. 指向 **`layer0/q_proj` 放 NPU 这个配置本身在长序列上不可靠**，而非某个长度约束。
   与项目文档「逐算子出现过 Token 崩溃/非法 Token，未通过正确性验证的算子不得启用」一致。

**同时观察到一个未解释的相关性**：本次 `generatedTokens` 正好等于 `max_new_tokens=300` 配置下
的实际上限（500），即**没有正常 EOS 收尾**，而坍缩同时出现。**因果方向未定**（是坍缩导致无法
产生 EOS，还是长尾生成本身更容易累积误差），读 `libcosy_llm_jni.cpp` 时需一并确认。

**对改造方向的影响**：per-step 检测/回退仍是合理方向（坍缩是散布的，逐步检测能在第一个坏段
就介入），但**按位置切分 backend 的方案应放弃**。per-step 方案要求 `generate` 支持逐步回调，
这正是 `libcosy_llm_jni.cpp` 的领域 —— 进一步提高了拿到那份源码的优先级。

**NPU-R1c 观测：坍缩是间歇性的，不是确定性的**

第三次跑同一句长文本（同内置音色，`continuousHexagon` 同为 true），**完全干净**：

```text
inputTokens 157  generatedTokens 286  decodeMs 7179  tokensPerSecond 39.84
speech tokens 285 个，unique 204
runs>=8 : 0        ← 零个连续段
maxRun  : 0
raw 末 token = 158486 = EOS   ← 正常收尾
```

三次同文本长句对照：

| 次 | inputTokens | generated | runs>=8 | maxRun | EOS | decodeMs | tok/s |
|---|---:|---:|---:|---:|---|---:|---:|
| R1b | 255 | 500 | 5 | 14 | 无（撞上限） | 14,847 | 33.7 |
| 更早 attempt-1 | 157 | 276 | — | 19 | — | 8,568 | 32.2 |
| R1c | 157 | 286 | **0** | **0** | **有** | 7,179 | 39.8 |

**→ 坍缩是间歇性的（随机/状态相关），不是确定性的。**

这推翻了「长度超过某值必然坍缩」的框架，应改写为：**NPU 路径有一定概率产生数值偏差；序列越长、
独立尝试次数越多，撞上的概率越高**。这也解释了短句为何看起来「总是通过」—— 不是因为它在阈值
内，而是因为它只掷了几十次骰子。

**两个附带结论**：

- `npuFirstTokenValid` 在**完全正常**的这次也是 `false`，再次确认它**不是有效判据**，任何策略
  都不应依赖它。
- NPU 路径三次 tok/s = 33.7 / 32.2 / 39.8，CPU fallback = 21.2，差距约 **1.6–1.9×**。
  **此前记录的 2.8–4.3× 应作废**（那是把不同 `generatedTokens` 的数据直接相除得到的）。

**这也给 R1b 的「无 EOS 收尾」提供了另一种解释**：可能是坍缩导致采样无法收敛到 EOS，
也可能只是这次恰好生成长；由于坍缩本身是间歇的，需要多次采样才能分离。

**NPU-R4 受控对照：三条路径同句长文本（2026-09-18）**

为分离「长文本在 CPU 上本来就慢」与「fallback 路径本身低效」，新增受控开关
`<模型目录>/force_cpu_llm.txt`：以 cpu 配置**直接起步**，不经过 hexagon 那次失败尝试、
不经过 reset/rebuild。同一句长文本结果：

| 路径 | generated | decodeMs | tok/s |
|---|---:|---:|---:|
| NPU（连续解码，干净那次） | 267 | 6,721 | **39.7** |
| CPU 直接起步（强制，无 fallback） | 500 | 65,151 | **7.67** |
| CPU fallback（hexagon 失败 + reset + rebuild） | 315 / 331 | 75.5k / 79.6k | **4.17** |
| 文档基线（**短句**，纯 CPU Low/4t） | 118–125 | — | 84.99 |

**三条互相独立的结论**

1. **NPU 路径本身真实有效**：比 CPU 直接起步快 **5.2×**（39.7 vs 7.67）。前排除了
   「NPU 反而不如 CPU」的可能，这条路值得保留。
2. **fallback 路径确有额外开销**：比 CPU 直接起步慢 **1.8×**（4.17 vs 7.67），
   即 teardown/rebuild 成本真实存在，但**量级只有 1.8×，不是主因**。
3. **主要代价来自坍缩率本身**：NPU 约 **2/3 概率坍缩**（3 次采样中 2 次），一旦坍缩就落到
   三条路里最慢的一条。**不是某条路特别差，而是大部分时间没走成那条快的路。**

**因此优化优先级**：① 降低 NPU 坍缩率（收益最大）→ ② 消除 fallback 重建开销（1.8×）。

**另注意到**：`generatedTokens` 撞满 500（= `max_new_tokens` 上限）的情况在 CPU 路径出现两次，
而 NPU 那次是正常 EOS 收尾（286 token）。提示 CPU 路径可能有 EOS 收敛问题，
**但这是与坍缩独立的问题**，需分开验证。

**结论**

> **当前 NPU 使用方式的问题不是「阈值太小」，而是判定粒度太粗：`continuousHexagon` 是整句
> 一次性的布尔，一旦门在末尾拒绝，前面几百个 token 的 NPU 计算全部作废并整句重跑。同时它
> 并不能防止坍缩 —— 它允许了长句进 NPU，然后才在末尾发现输出不可用。**

**改造方向（下一阶段）**：把 NPU 可用性从 per-utterance 布尔 改为 per-stage / per-step 属性。

```text
Prefill ：可用 NPU → NPU；不适合 → CPU
Decode  ：每个 step 尝试 q_proj on NPU → 输出校验 → PASS 用 NPU / FAIL 当前 step 回退 CPU
```

理由是 stage-filter 只放行 `layer0 / Convolution / q_proj`，而 decode 每步的 q_proj 输入 shape
并不会随历史变长而变成超长 sequence tensor；真正的大 `seq_len` 只出现在 prefill。
**「CPU prefill + NPU decode」是最值得优先验证的组合**，它有机会让长 prompt 不再导致后续几百个
token 全部失去 NPU。

本轮已完成 **NPU-R1**（保留 attempt-1 报告 + 记录 requestedBackend / fallbackReason /
sessionResetCalled），这是后续 R2–R4 的数据基础。

**性能数字：未经受控解释，不得作为后端优劣依据**

attempt-1（NPU）decodeMs 8,568 / 32.2 tok/s，attempt-2（CPU）decodeMs 14,899 / 21.2 tok/s，
但两者 **generatedTokens 不同**（276 vs 316），只能粗看。

另有悬而未决的落差：`STAGE3_FEASIBILITY.md` 在同样配置（87 prompt、约 120 生成、Low/4t）下记
84.99 tok/s，而本轮 fallback 路径仅 4.3–13.5 tok/s，差 6–20 倍。**频率只能解释约 1.6×**（负载
实测 cpu6 可冲到 2.88 GHz，说明空闲读到的 `scaling_max_freq` 是内核动态值而非硬上限；温度
50–67 °C，非热节流；`time_in_state` 的 98.8% 低频驻留是从开机累计、设备大部分时间空闲，不构成
钳制证据）。**因此「降频钳制 / 需要重启」这个中间结论已撤回**，但落差本身仍未解释。

**OpenCL 路线：已有结论，不必重测**。`STAGE3_FEASIBILITY.md` 在 MNN 3.6.0 上测得 LLM OpenCL
热缓存 68.38 tok/s < CPU 84.99 tok/s，冷启动 prefill 约 10.09 秒，当时决策即 **LLM 不上 GPU**
（OpenCL 主线留给 Flow）。真正没被合理利用的是现有 NPU，不是 GPU。

---

### 原始调查过程（部分结论已被上方推翻，保留供避坑）

**当时的目标**：找 256 阈值的来源。**当时的错误前提**：把 attempt-2 的 `continuousHexagon=false`
当成了失败那一次的值。

**当时列出的两个「自洽」假设**（现已知均为伪相关）：

- `inputTokens` 阈值 ∈ (135, 157]
- 总长（input + generated）阈值 ∈ (254, 279]

**当时排除的归因**（这部分结论**仍然有效**，因为它们独立于上述前提）：

1. **`HexagonKVCacheManager::mPageSize = 256`** —— 该类只在 `HexagonAttention.cpp:95` 被 `new`，
   而当前 stage-filter（`MNN_HEXAGON_LAYERS=0` / `OPS=conv` / `NAME=q_proj`）只放行 layer0 的
   Convolution，**`HexagonAttention` 从未被创建**，此路径实际未进入。曾据此改过 `onAlloc` 预分配
   并重编 `libMNN.so`，实测对结果无任何影响。
2. **`HexagonAttention.cpp:68 kHmxKvBlock = 256`** —— 同上，未进入。
3. **`KVCACHE_SIZE_LIMIT`** —— `Backend.hpp:52` 定义、`Session.cpp:100` 赋值，但**只有 QNN 后端
   读**（`QNNAttention.cpp:15`），Hexagon 不读。
4. **config 的 `chunk` / `chunk_limits`** —— 注入 `chunk=1024` 后合成**直接卡死、进程被杀**；
   `chunk` 只能在模型已编译的 shape 里选。另：直接改源 config 会撞上 `MODEL_FILE_SPECS` 的字节数
   校验，`modelStatus().ready` 变 false（报「朗读模型未就绪」）—— 与 lesson ㉞ 同类错误。
5. **decode 图的静态 shape** —— 导出时 `seq_len` 本就是动态轴（`llmexport.py:75-78`）。

**下一步（排序）**

- **A. 拿到 `libcosy_llm_jni.cpp`（第一优先级）** —— 现在能带着明确问题去读：它为什么在长序列上
  产生 `longestRun=19`？是 q_proj 的 NPU 数值误差累积，还是 decode 侧的某种近似？
- **B. NPU-R2/R3/R4** —— 拆 `prefillHexagon` / `decodeHexagon`，验证「CPU prefill + NPU decode」。
- **C. 应用层切句**（workaround，**暂不做**）。

**副产物（可复用，本轮实测）**

- **本机可重编 hexagon 版 `libMNN.so`**：NDK 27 + cmake 3.22.1 + ninja，关键选项
  `-DMNN_HEXAGON=ON -DMNN_OPENCL=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON`（**缺最后一项**会在
  `HexagonKVCacheManager.hpp` 报 `KVCacheManager` 未定义），且**不需要 Hexagon SDK** —— hexagon
  后端的 `fastrpc_*` 全部经 `dlopen` 动态加载，htp-ops-lib 的 stub(arm64)/skel(v81) 二进制本机已有。
- **`mnn-patches/mnn-3.6.1-hexagon-stage-filter.patch` 用 `git apply` 会报 corrupt**，需按 diff 内容
  手工插入 `HexagonExecutionFactory.cpp` 的 `create()` 开头。

**已回退**：`libMNN.so` 已恢复原版（48,769,280 B，SHA256 前缀 `40c630a10994fa55`，与 `git HEAD`
逐字节一致）；MNN 源码里 `HexagonKVCacheManager.cpp` 的预分配改动与诊断日志均已撤回，只保留
`HexagonExecutionFactory.cpp` 的 stage-filter 复现（线上配置的一部分）。

---

**NPU-R5/R6/R7：长句可行的配置组合（2026-09-18，已验证）**

**过程中推翻的两个前提**

1. **repetition collapse 不是 GPU/NPU 特有**。同句长文本下 CPU 也出现**连续 266 个相同 token、
   无 EOS、撞满 `max_new_tokens`** 的退化输出（`len266@[234-499]`）。此前把 CPU 当作「干净的
   兜底」是错的 —— 它只是**从未被检查过**（质量门只在 `llmBackend == "hexagon"` 时跑）。
2. **文档基线 84.99 与本轮实测 4.16 的落差不是后端性能差异**，而是**正常生成 vs 退化卡死**的
   区别。退化状态下测出的 tok/s 与正常状态不可比。

**同句长文本、各配置实测**（用 `raw-output-ids-0.csv` 离线统计 `runs>=8`；
正常输出约 248 token 且正常 EOS，退化输出撞满 500 token 且无 EOS —— **时长本身即判据**）

| 后端 | 采样器 | 采样次数 | 退化次数 | 正常时长 | 退化时长 |
|---|---|---:|---:|---|---|
| CPU | cosyvoice_ras | 3 | 2 | 9.9 s | 20.0 s |
| OpenCL | cosyvoice_ras | 3 | 1 | 9.9 s | 20.0 s |
| OpenCL | **mixed** | 3 | 1 | 12.0 s | 20.0 s |
| NPU | cosyvoice_ras | 4 | 3 | — | — |

> ⚠️ 2026-09-18 NPU-R8 更正：本表「CPU」一行的采样器无法从留存产物确认，而那条 `len266@[234-499]`
> **不可能**出自 `cosyvoice_ras`（守卫会把最近 10 个里出现过的 token 置 `-inf`）。故「CPU 退化率最高」
> 这一归因不成立，需在 `cosyvoice_ras` 下重测。详见 NPU-R8。

**`precision` 不是解药**：OpenCL 下 `precision: high` 两次采样仍有 1 次退化（maxRun 13），
与 `low` 无本质差别。

**最终配置（已验证：三次全部拿到正常输出，零退化交付）**

**已落地为代码中的自动分流策略**（⚠️ 2026-09-18 NPU-R10 已**取消**该分流：长句走 NPU 实测
4/4 首试通过、prefill 快 17~35 倍。见 NPU-R10）：

```text
明文 <= 24 字  → hexagon (NPU)   prefill 仅 0.9~2.4 s，短句摊得掉，且通常不退化
明文 >  24 字  → opencl          decode 快，prefill 固定约 11~13 s，长句摊得掉
质量门         → 所有后端统一过重复检测 + 自动重试（**含 CPU 兜底那一次**，见 R7）
最终兜底       → CPU（2 次；仍不过门则直接报错，不交付）
```

采样器仍用 config 里的 `cosyvoice_ras`（`mixed` 实测略优但样本不足，未改默认）；
如需切换可用 `<模型目录>/force_llm_sampler.txt`。

**端到端验证（2026-09-18，标记文件已清除、走自动分流）**

```text
短句 7 字  : continuousHexagon=true  → NPU
             prefill 940 ms / decode 1,174 ms / 38.3 tok/s / 整链 wall 2.2 s
长句 40 字 : CLEAN_FIRST_TRY（首次即干净，未触发重试）→ OpenCL
             prefill 13,218 ms / decode 6,068 ms / 46.8 tok/s / 整链 wall 20.6 s
```

**对照**：长句此前是「退化 → 回退 CPU 65~80 s，**且交付的仍可能是退化输出**」；
现在是「约 20 s，且有质量门保证不交付退化输出」。

```text
run 1: 触发重试后成功，未回退 CPU   → 11.2 s 正常输出
run 2: 首次尝试即干净              → 10.7 s 正常输出
run 3: 触发重试后成功，未回退 CPU   → 10.7 s 正常输出
```

**为什么长句反而该用 OpenCL**：历史「OpenCL 不如 CPU」（68.38 vs 84.99）是在**短句**上测得；
长句上 CPU 会退化，而 OpenCL 的 decode 达 55 tok/s。代价是 **prefill 固定约 11 秒**
（CPU 0.7~1.1 s、NPU 1.5~2.4 s），长句摊得掉、**短句会亏 —— 短句仍应走 NPU**。

**新增受控开关（仅实验用，默认不启用）**

```text
<模型目录>/force_llm_backend.txt    内容 cpu | opencl | hexagon
<模型目录>/force_llm_precision.txt  内容 high | low
<模型目录>/force_llm_sampler.txt    内容 mixed | cosyvoice_ras
```

**遗留**

- config 里的 `max_new_tokens: 300` 未生效 —— Kotlin 侧 `runLlm` 写死 `maxTokens = 500`，把它覆盖了。
  退化时因此会一路重复到 500 才停。是否改为读取 config 值待定。
- **坍缩起点尚未解释**。目前只有 **1 次** CPU 长句退化被完整留存下来，它的重复段从第 234 个生成
  token 起（`len266@[234-499]`）。**单次观测不足以断言"起点是位置性的"** —— 要立住这个结论，
  需要多次退化样本 + 每次的 `inputTokens`（`prompt_len`）：只有当 `inputTokens + 234` 反复是同一个
  整齐常数时，才能认定是 decode 侧的序列长度边界（静态 shape / KV cache），也才能按
  「重新导出 decode 图、加大静态 shape」根治（用户已选定该路线）。
- **`libllm.so` 与 `libcosy_llm_jni.so` 在本仓库里没有对应源码。**
  `jniLibs/arm64-v8a/libllm.so`（18,358,600 B，2026-08-25）带 `cosyvoice_ras_window` /
  `cosyvoice_ras_tau` / `npu_model_dir` / `key_value_shape` 等符号；`libcosy_llm_jni.so`
  （473,424 B，2026-08-25）带 `npuFirstTokenValid` / `npuPrefillMs` / `hybridNpuPrefill` / `cpuContext`
  等符号。而 `mnn-jni/CosyVoiceLlmPersistentBenchmark.cpp`（327 行）是**过期副本**：它把
  `hybridNpuPrefill` 写死为 `false`，并且把 `continuousHexagon` 算成
  `hexagonRuntime && !stagedHexagonLayers.empty()`（纯配置回显 —— 而 `hexagon-stage-*.txt`
  恰好由 App 自己在发起这次调用之前写入）。**因此：这两个 .so 目前无法从仓库重建；仓库里的 native
  源码也不能当作线上行为的判据**（线上 `continuousHexagon` 是否同样是配置回显，从仓库无法确认，
  需在设备上用「同一配置、只改 stage 文件」的对照实验判定）。

**NPU-R7：把「兜底」也纳入质量门（2026-09-18；设备复测见 NPU-R9）**

R5 的尝试结构留了两个洞，本质是同一件事 —— **最后一次尝试不过门**。

1. **CPU 兜底那一次跑完直接交付，从不过门。** 而 CPU 恰是长句退化率最高的后端（上表 2/3），
   于是「兜底」成了唯一能把退化音频悄悄送出去的路径。

   留存向量（同句 40 字，两段都取自设备上的 `raw-output-ids-0.csv`）：

   | 文件 | 原始 token 数 | 末位 | unique | 长度 ≥ 8 的连续段 |
   |---|---:|---|---:|---|
   | `voice-ab/dev/opencl-tokens.csv`（干净） | 249 | EOS `158486` | 181 | 无 |
   | `voice-ab/dev/cpu-tokens.csv`（退化） | 500 | `151926`（非 EOS） | 158 | `len266@[234-499]` |

   即：CPU 那一次**没有任何 EOS**，且 `234 + 266 = 500` —— 重复段一直顶到 `maxTokens` 上限才停。
2. **入口后端本来就是 `cpu` 时**，兜底块会拿同一份 CPU 配置再跑一遍（约 70 s），
   既没换后端，也没提高质量。

**改法**：把「重试 + 兜底」重写成一张显式尝试计划表，**每一次尝试（含 CPU 兜底）都过同一道门**；
门全不过就 `error(...)` 抛出，不再交付。

```text
路由后端为 opencl / hexagon：该后端 5 次 → CPU 2 次 → 仍不过门则抛错
路由后端为 cpu            ：CPU 2 次           → 仍不过门则抛错
```

**5 次而不是 3 次的依据**：坍缩是每次采样独立的间歇事件（同句三次 token 轨迹各异：315 / 331 / 267），
重试即重新抽样，成功概率按几何分布累积。OpenCL 单次退化率约 1/3，5 次后「全部失败」约
`0.33^5 ≈ 0.4%`；而**期望尝试次数只从 1.44 升到 1.49**，平均墙钟几乎不变（约 29 s → 30 s）。
CPU 每次约 70 s，所以只在最末端留 2 次。

**每次被拒的产物都留档**：`llm-persistent-attemptN.jsonl` 与 `raw-output-ids-attemptN.csv`
（N = 尝试序号）落在 run dir，`llm-attempts.txt` 记录每次的 backend / 退出码 / 拒因 / 是否被接受。
**NPU-R8：拿回 Native 源码后的三处更正（2026-09-18，源码已固化）**

**来源**：构建机在 WSL Ubuntu `/home/vicentrent/work/cosyvoice-npu/`，Windows 侧可经
`\\wsl$\Ubuntu\home\vicentrent\...` 直读（不必启动 WSL）。已把 MNN 引擎与 Hexagon/QNN 后端的
diff 固化进 `CosyVoice3-MNN-Plus/mnn-patches/mnn-cosyvoice-npu-fork/`，并把
`mnn-jni/CosyVoiceLlmPersistentBenchmark.cpp` 换成真源码（327 行 → 465 行）。

**更正 1：`continuousHexagon` 与 `npuFirstTokenValid` 是配置回显，不是运行期测量。**

```cpp
const bool continuousHexagon = hexagonRuntime && !stagedHexagonLayers.empty();
const bool hybridNpuPrefill  = hexagonRuntime && !continuousHexagon;
// 报告里直接写这两个布尔：
//   "continuousHexagon"  : continuousHexagon
//   "npuFirstTokenValid" : hybridNpuPrefill
```

`hexagonRuntime` = 配置文件名含 `hexagon`；`hexagon-stage-*.txt` 又恰好由 App 自己在发起调用前写入
（`CosyVoiceRuntime` 对 hexagon 后端写 `0` / `conv` / `q_proj`）。所以只要走 hexagon 后端，
`continuousHexagon` 必然为 `true` —— 它**永远不等于**「NPU 真的在算」。

**影响**：R1/R1b/R1c 中「NPU 确实在跑」「NPU 输出退化」这类表述只能改读成「在（配置意义上的）
连续 Hexagon 分支下输出退化」。R8 之前一切以 `continuousHexagon` / `npuFirstTokenValid` 为
证据的推断都需要重新界定。已被直接推翻的一处：`CosyVoiceStore.llmRuntimeConfig` 注释里的
「实测长句的 continuousHexagon 为 true（NPU 确实在跑）」已改写（连同 `CosyVoiceRuntime` 里同源的
一段注释）。

**更正 2：那条 56.5 秒的路径是 `hybridNpuPrefill`，而它在产品路径上走不到。**

hybrid = 先用 hexagon 配置只生成 **1** 个 token，再用 `config-cpu-*` **另起一个 LLM 实例**，
把 `input_ids + 第一个 token` 整句重新 prefill 后继续解码 —— decode 全在 CPU。
记录里的 `npuPrefillMs=1078 / cpuPrefillMs=0 / continuousHexagon=false / decodeMs=56493 /
tokensPerSecond=4.09` 正是它。因为 App 对 hexagon 总会写 stage 文件，这条分支不会被触发。

**更正 3：`cosyvoice_ras` 不是「另一种采样参数」，它带两个别的采样器没有的机制。**

| 机制 | `cosyvoice_ras` | `mixed`（MNN 默认） |
|---|---|---|
| 候选集 | 只含 6561 个语音 token（`151924..158484`） | 整个词表 |
| EOS | **仅当已生成 token 数 ≥ `cosyVoiceMinTokens` 时才放进候选集** | 任意时刻可出 |
| 防重复 | 最近 10 个输出里出现过该 token 就置 `-inf` 重抽一次 | **无** |
| 重复惩罚 | —— | `repetition_penalty` 默认 1.0，且默认 `mixed_samplers` 不含 `penalty` 步 |

`config-cpu-cosyvoice-ras.json`（模型文件，504 B，SHA256 `448256ef…`）用的是 `cosyvoice_ras`，
而**每个后端的 runtime config 都由它派生**（只覆盖 `backend_type` / `thread_num` / `power`），
所以三个后端默认都带上面那两个机制。另：`llm_config.json` 从来不被 C++ 运行时读取（只被 Python
导出工具使用），因此 `CosyVoiceStore.ensureLlmSamplerConfig()` 写入的 `repetition_penalty: 1.1`
**从未生效过**；该处已加注释标明，避免继续被当成已生效的配置。

**由更正 3 直接得到的一条反证 —— R5 表里「CPU 2/3 退化」这一行不成立。**

`cosyvoice_ras` 的守卫是「最近 10 个输出里出现过 → 置 `-inf` 重抽」，阈值 `10 × 0.1 = 1`，
也就是**连续两个相同 token 在它下面就已经不可能**。而留存的退化向量
`voice-ab/dev/cpu-tokens.csv` 里是一个 `len266@[234-499]` 的连续段 —— 它**不可能**出自
`cosyvoice_ras`。设备上那份 `config-cpu-cosyvoice-ras-runtime.json` 实测是 `sampler_type: "mixed"`
（采样器 A/B 实验留下的残留；标记清除后下次调用会自动改回 `cosyvoice_ras`），与之吻合。

因此：「CPU 是退化率最高的后端」这一归因**不成立**，它更可能是**采样器不同**造成的。
必须在 `cosyvoice_ras` 下重测三个后端，才能重新谈后端差异。作为旁证，同样留存的
`voice-ab/dev/opencl-tokens.csv`（249 token、末位 EOS、无长度 ≥ 8 的连续段）来自 `cosyvoice_ras`，
表现正常。

**仍未解释**：`len266@[234-499]` 的重复起点为什么是第 234 个 token（单次观测，且该次采样器
尚不能确定，因此这条线索目前不足以支撑任何结论）。
**NPU-R9：质量门判据重新标定 —— 旧判据把「正常终止」也拒了（2026-09-18，真机实测）**

**R7 上线后第一次真机跑暴露了一个比 R7 更基础的问题。** 同句 63 字（音色前缀 26 字 + 文本 37 字，
`inputTokens = 196`）在 OpenCL 上连跑 5 次 + CPU 兜底 2 次，**7 次全部被质量门拒绝**，整条链直接报错 ——
也就是说 R7 把「悄悄交付退化音频」换成了「什么都交付不了」。

实测 7 次（`precision: low`；三个后端的 runtime config 都是 `sampler_type: cosyvoice_ras`）：

| # | 后端 | token 数 | EOS | unique | 最长连续段 | prefill | decode | tok/s | wall |
|---:|---|---:|---|---:|---|---:|---:|---:|---:|
| 1 | opencl | 370 | ✓ | 230 | 10@[308-317] | 10.8 s | 6.3 s | 59.1 | 18.7 s |
| 2 | opencl | 387 | ✓ | 246 | 20@[67-86] | 11.0 s | 6.4 s | 60.3 | 18.9 s |
| 3 | opencl | 500 | ✗ | 233 | 105@[395-499] | 11.0 s | 8.0 s | 62.4 | 21.0 s |
| 4 | opencl | 500 | ✗ | 243 | 107@[393-499] | 11.3 s | 8.1 s | 61.6 | 21.5 s |
| 5 | opencl | 500 | ✗ | 231 | 117@[383-499] | 12.2 s | 8.1 s | 61.6 | 22.4 s |
| 6 | cpu | 357 | ✓ | 234 | 8@[78-85] | 1.1 s | 84.5 s | 4.22 | 87.1 s |
| 7 | cpu | 369 | ✓ | — | 9@[86-94] | — | — | — | — |

旧判据 `longestRun >= 8 即拒` 会拒掉**全部 7 次**，其中 1/2/6/7 是**正常终止**的输出
（370~387 token、有 EOS、unique 230~246），最长连续段仅 8~20 个 token（约 0.3~0.8 秒）——
在语音 token 里对应长元音 / 静音 / 拖音，属正常范围。真正跑飞的那 3 次都**没有 EOS**，
且最长段 105~117（约 4.2~4.7 秒同一个 token）。

**新判据**（`CosyVoiceLlmOutputQuality.collapseReason`）只保留三条能证明退化的条件：

```text
1. 没有 EOS               —— 生成只可能在 EOS 或 maxTokens 处停下，没 EOS 就是撞满上限（本次 3/7）
2. unique <= 4 且长度 >= 20 —— 灾难性坍缩
3. 单段连续重复 >= 64       —— 本次「正常」最大 20、「跑飞」最小 105，64 落在两者之间
```

`longestRun` 与各重复段位置继续写进拒因与 `llm-attempts.txt`，只作诊断，不再单独作为拒绝依据。

**验证（2026-09-18 18:50，同一句 63 字）**：新判据下首次尝试即被接受

```text
llm-attempts.txt        : attempt1.backend=opencl / llmExitCode=0 / result=accepted
llm-persistent.jsonl    : inputTokens=196 generatedTokens=380 invalidOutputs=0
raw-output-ids-0.csv    : 380 token、末位 EOS、unique=236、longestRun=17@[231-247]
整链产物                : flow-output/student_target_mel_android.bin
                          + hift-output/hift-android.wav (727,724 B ≈ 15.2 s)
```

**同一轮里被顺带验证的两件事**

1. **更正 1 在设备上直接可见**：全部报告的 `continuousHexagon=false`、`hybridNpuPrefill=false`，
   而 `stagedHexagonLayers="0"` —— OpenCL 配置名不含 `hexagon`，故 `hexagonRuntime=false`，
   两个字段必然为 `false`，确认它们只是配置回显。另外 `npuPrefillMs` 在 **cpu** 配置下也被写成
   1065 ms —— 该字段与 NPU 无关，它只是「主实例的 prefill 时间」。
2. **更正 3（采样器才是关键变量）得到两次直接支持**：CPU 在 `cosyvoice_ras` 下 2/2 次都**正常终止**
   （357 / 369 token，有 EOS，最长段 8 / 9），与保留下来的那次 266 连击完全不同。
   「CPU 后端会坍缩」这一说法不成立；那次 266 连击来自不带 RAS 守卫的 `mixed` 采样器。

**仍未解释（新增）**：18:50 那次整链的 LLM 段 wall = **274 s**（`decodeMs = 249,780`、
`tokensPerSecond = 1.52`、`prefillMs = 22.9 s`），而同配置同文本在 18:43 的 5 次尝试是
18.7~22.4 s（约 60 tok/s、prefill 10.8~12.2 s）—— **OpenCL 的 decode 慢约 40 倍**，但输出本身正常。
当时电池 41.0 °C、thermal_zone0/1 = 51.2 / 51.5 °C，且此前刚跑过 7 次尝试（含一次 87 s 的 CPU 段）。
是热降频、GPU 抢占，还是 OpenCL 在多次 `reset()` / 重建之后退化，尚未区分 —— 需冷却后同文本复跑判定。
**NPU-R10：长文本「不能用 NPU」是判据造成的 —— 分流取消，长句恢复走 NPU（2026-09-18，真机实测）**

**结论先写**：同一句 63 字（音色前缀 26 字 + 文本 37 字，`inputTokens = 196`）在 hexagon 上
**4/4 次首次尝试即通过**，而且比 OpenCL 更快更稳。R5 的「长句改走 OpenCL」因此取消。

**hexagon vs opencl（同句、同 `cosyvoice_ras`、R9 新判据）**

| 后端 | 尝试 | 首试通过 | 生成 token | EOS | longestRun | prefill | decode | tok/s | wall |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|
| hexagon | 4 | 4/4 | 422 / 443 / 353 / 409 | ✓ 全有 | 23 / 18 / 9 / 15 | 0.62~0.73 s | 9.1~11.7 s | 37.6~38.8 | 10.3~13.1 s |
| opencl | 6 | 3/6 | 370 / 387 / 500 / 500 / 500 / 380 | 3 次无 | 10 / 20 / 105 / 107 / 117 / 17 | 10.8~22.9 s | 6.3~249.8 s | 1.5~62.4 | 18.7~273.8 s |

要点：

- hexagon 的 **prefill 只有 0.62~0.73 s**（OpenCL 10.8~22.9 s，差 17~35 倍）—— 这正是当初
  「短句走 NPU」的理由，而它同样适用于长句；
- hexagon 4 次**全部正常终止**（都有 EOS）；OpenCL 6 次里 3 次撞满 `maxTokens = 500` 且无 EOS；
- hexagon 那 4 次的 `longestRun` 是 23 / 18 / 9 / 15 —— **旧判据 `>= 8` 会把它们全部拒掉**。
  这就是「长文本 NPU 用不了」的完整成因：不是 NPU 算不动，而是 ① 判据把正常输出误判成坍缩，
  ② 被判据拒掉之后又按长度分流去了 OpenCL。这两条腿在 R8/R9 里都已拆掉。

**改动**：`CosyVoiceRuntime` 删除「明文 <= 24 字 → hexagon，否则 opencl」的分流，
`autoBackend` 恒为 `null`，即一律采用硬件策略给出的后端（SM8850 ⇒ hexagon）。
重试计划（R7）与质量门（R9）保留作安全网；`force_llm_backend.txt` 仍可做受控对照。

**端到端验证（2026-09-18 19:16，无任何标记文件，走自动路由）**

```text
llm-attempts.txt     : attempt1.backend=hexagon / llmExitCode=0 / result=accepted
llm-persistent.jsonl : inputTokens=196 generatedTokens=409 invalidOutputs=0
                       continuousHexagon=true prefillMs=625 decodeMs=10743 tok/s=38.07 wallMs=12080
raw-output-ids-0.csv : 409 token、末位 EOS、unique=250、longestRun=15@[76-90]
整链产物             : cache/cosyvoice-preview/autorun-112901344.wav (783,404 B ≈ 16.32 s)
```

**留档的试听文件**（`E:\AndroidStudioProjects\voice-ab\listen\`）

```text
长句63字-NPU自动路由-成功-12.1s.wav   ← 本次修复后的产物（hexagon）
长句63字-opencl-成功-15.2s.wav        ← 同一句走 OpenCL 的成功产物（R9）
长句-opencl-旧.wav / 长句-cpu-旧-退化20s.wav  ← 早前的对照
```

**未解释**：hexagon 那 4 次里 `longestRun` 到了 18~23（约 0.7~0.9 秒同一个 token）。它们都有 EOS，
听感需人工确认；R9 的 64 阈值暂时把它们放行。若试听确认这些位置听感异常，需要把「长重复段」
与「出现位置」关联起来再定阈值。
**NPU-R11/R12/R13：长文本"只读了一句"的三个真因（2026-09-18，真机实测）**

用户在 App 里填了 3 遍「你好，欢迎使用阅读，这是手机 MNN 本地合成测试。」（92 字），
听到的却只有「远处那盏灯忽明忽暗。你好，欢迎使用阅读，这是手机 MNN 本地合成测试。」。
拆下来是三个独立问题，均已修。

**R11：质量门的拒因是"没有 EOS"，而尾部空转那一截本来就是多余的。**

模型说完之后 EOS 出不来，它就把最后一个 token 一路重复到 `maxTokens`。这类输出不需要重抽 ——
重复段之前那一截就是模型真正想说的内容。新增 `CosyVoiceLlmTailRepair`：当且仅当
「没有 EOS + 重复段延伸到序列末尾 + 重复段 ≥ 32 + 保留段 ≥ 32」时，把重复段整体切掉，
并重写 `speech-tokens-0.csv`；同时写 `llm-tail-repair.txt` 留证。

**R12：`maxTokens` 写死 500 ≈ 只有 20 秒语音，长文必然撞满。**

这条链的 LM 生成的是「音色前缀文本 + 正文」整段（前缀里 25 个字的参考文本同样占 token）。
实测约 6 token/字：39 字 → 184 token；63 字 → 353~443；92 字 → 撞满 500。
改为按字长估算：`(前缀字数 + 正文字数) * 8 + 128`，钳在 `[500, 1024]`；实际值写进 `llm-attempts.txt`。

**R13（决定性）：`cosyvoice_ras` 采样器没有任何重复惩罚，这才是重复的根源。**

读构建机上的 MNN fork 源码可以确认：`cosyvoice_ras` 的管线是写死的
`stepCosyVoiceRange → stepTopK → stepTopP → stepSelect`，**不含 `penalty` 步**；
而 `repetition_penalty` 只在 `penalty` 步里生效，MNN 默认的 `mixed_samplers` 也不含它。
也就是说这整套 LLM 从来没有过重复惩罚。

实测对照（同一句 92 字、同一台设备、hexagon）：

| 采样器 | 尝试 | token | EOS | unique | 最长连续段 | 结果 |
|---|---:|---:|---|---:|---|---|
| `cosyvoice_ras`（旧默认） | 6 | 500 / 952 | ✗ 全部 | 57~219 | 33 / 314 / 345 / 610 / 858 | 全部被拒 → 只能靠 R11 截断 |
| `mixed` + `penalty` + `repetition_penalty=1.15` | 1 | **590** | **✓** | — | — | **首次尝试即接受，成品 23.56 s** |

修法：`CosyVoiceStore.llmRuntimeConfig()` 派生出的 runtime config 默认改为

```json
{ "sampler_type": "mixed",
  "mixed_samplers": ["penalty", "topK", "topP", "temperature"],
  "repetition_penalty": 1.15,
  "temperature": 1.0, "top_k": 25, "top_p": 0.8 }
```

代价是失去 `cosyvoice_ras` 的「只从 6561 个语音 token 里采」限制；实测 `invalidOutputs=0`，
万一出现非法 token 质量门会拒掉并重试。`force_llm_sampler.txt` 仍可覆盖回 `cosyvoice_ras` 做对照。

**顺带回答用户的两个疑问**

- **"前面怎么会有『远处那盏灯忽明忽暗』"**：那是音色档案的 `promptPrefix`，由注册时填的参考文本构成，
  是 CosyVoice zero-shot 格式的一部分，不是数据损坏。但**它确实被念了出来** —— 因为 LM 生成的是
  「参考文本 + 正文」整段，而 App 把整段都当成目标 token 交给 flow。
  **这一条尚未修**：需要在 flow 之前丢掉前 `promptTokenCount` 个 token 才能只读出正文，
  而这要先确认 flow 的 `sequenceLength = 2*(promptTokenCount + targetTokens)` 契约，
  不能凭猜测改（见"未解决"）。
- **"注册时音频超过 5 s，数据溢出了"**：方向对。当前音色 `voice-1789716179003` 的设计产物是
  **7.68 s**，而档案里存的是 `promptTokenCount=125 / promptFrameCount=250` —— 正好是
  `MAX_REALTIME_PROMPT_TOKENS = 125` / `rt_limit.txt` 的 5.0 秒上限。也就是说注册时音频被**截到 5 s**，
  后 2.68 s 没有进音色档案。这是有意的实时版上限，不是越界写坏；若希望保留更长参考，
  需要抬高这个上限并同步验证 flow 的 `promptFrameCount` 桶。

**未解决**

- **参考文本被一并念出**（上面第一条）：需要先确认 flow/conditioner 对「token 序列是否含提示区」的约定。
- **~~试听整链要 32 分钟~~ → 已撤回。** 那是我拿**单次离群值**（20:51 的 autorun：`wallMs=1,951,358`、
  `LLM 1,459.49 s`、`decodeMs` 1.52→0.73 tok/s）当成了现状，**又一次犯了"单次观测下结论"的错**
  （与 R1 的 256 阈值、R5 的 CPU 退化率同类）。20:55 / 21:00 三次后续试听实测完全正常：

  | run | prompt / 输出 token | tok/s | LLM wall | 尝试 |
  |---|---:|---:|---:|---|
  | `run-1789736452938` | 150 / 155 | **67.2** | **3.05 s** | 首次即接受 |
  | `run-1789736434743` | 150 / 151 | **58.7** | **3.32 s** | 首次即接受 |
  | `run-1789736136060` | 231 / 452 | **47.6** | **11.94 s** | 首次即接受 |

  所以**试听现在是健康的**。那次 32 分钟仍是真实发生过的离群事件（且是 `autorun` 路径、
  紧接着我一连串压测之后），但样本量为 1，不足以说明任何趋势，已不再作为"当前问题"记录。
- **`decodeMs` 与 `wallMs` 出现过互相矛盾的读数**（809 s vs 639 s；1,459 s vs 1,951 s 则同向），
  说明该字段的差分在某些路径上不可靠，诊断时不要单独采信 —— 用 `wallMs` 或产物文件时间。
**NPU-R14：注册用参考文本缩短到 15 字（2026-09-18）**

**问题**：设计流程的 `DEFAULT_REFERENCE_TEXT` 是 26 字，实测设计出 **7.68 秒**音频；
而注册时会被 `MAX_REALTIME_PROMPT_TOKENS = 125`（= 5.0 秒 / 250 帧）**截到 5 秒**，
`promptPrefix` 里却仍然存完整文本 —— 前 5 秒大约只覆盖前 17 个字，却告诉模型这句话有 26 个字。
文本提示与音频提示不对齐，属于实打实的输入缺陷。

**对照**：内置基准音色 `builtin-mnn-reference-v1` 是 **15 字 / 87 token（3.48 秒）**，文本与音频对齐。

**改动**：

```text
DEFAULT_REFERENCE_TEXT: "风从北边吹过来，带着一点凉意，远处那盏灯忽明忽暗。"   (26 字, 设计出 7.68 s)
                    →   "风从北边吹过来，带着一点凉意。"                        (15 字)
```

并在 `design.json` 里补记 `promptTokenCount` / `promptFrameCount` /
`promptTruncatedByRealtimeLimit`，让"这次有没有被实时版上限截断"可见，不再静默。

**未验证**：15 字的设计时长是**按旧默认速率推算的约 4.4 秒**（26 字 → 7.68 s ≈ 0.30 s/字），
不是实测；旧默认的 7.68 秒含句末长停顿，真实值需跑一次 design 才能确认。
若确认低于 `minSeconds = 3.0`，把这句加长几个字即可。

**顺带澄清一个流程问题（用户提问）**：设计流程里**没有**多余的「生成参考音频」步骤。
VoiceDesign 模型（GraphB，条件为 `description`）的前向输出就是 PCM——它必须念点什么才谈得上"一个音色"，
`referenceText` 只决定念哪些字、不影响音色。而且自 ADR-054 起，decoder 出 PCM 后**直接**调用
`enrollFromPcm`（`CosyVoiceVoiceDesigner.createFromDesign` 第 70~71、104~107 行注释），
不再经 WAV 往返；`reference.wav` 只是留档资产。"从 decoder 直接到注册"**已经是现状**，
唯一能省的就是文本长度。
## ADR-054 — VoiceIdentity Source 解耦：enrollment 算法唯一化 + 采样参数运行时化

- **Decision**：① 把 enrollment 算法从 JNI 里抽出为 **`CosyVoiceEnrollmentCore`**（`enrollFromPcm` / `enrollFromWavFile` / `quantizeInt16InPlace` / `ditherInt16InPlace`），`CosyVoiceEnrollmentJni.cpp` 退化为薄包装（410 行 → 54 行）。「参考音频克隆」与「文字设计音色」是两种 **VoiceIdentity Source**，但从 enrollment 往下共用同一条链。② VoiceDesign 的 decoder 出 PCM 后**直接**调用 `enrollFromPcm`，**不再经过 WAV 往返**；`reference.wav` 仍然写，但定位从「传输介质」改为「长期资产」。③ PCM 入口第一版**保持 int16-WAV 数值语义**（内存内做 `float → clamp[-1,1] → (int16)lrintf(v*32767.0f) → /32768.0f` round-trip），不追求比 WAV 更高的精度。④ `llm_config.json` **移出 `MODEL_FILE_SPECS`**，采样参数改由 `CosyVoiceStore.ensureLlmSamplerConfig()` 在运行时合并（幂等）。
- **Reason**：① 算法此前只存在于 JNI 内部且只接受 WAV 路径，导致 VoiceDesign 必须先把 PCM 落成 WAV 再让 enrollment 读回来 —— 两条路各有实现，修一处不生效两处。② `reference.wav` 是**极其昂贵的上游成果**的廉价快照：一次 VoiceDesign ≈ 数分钟，而 WAV ≈ 370 KB。将来换 enrollment 算法 / speech tokenizer / CAMPPlus / 迁移 Audio8 / 用户导出试听 / 重建 VoiceProfile / 音色一致性回归，都不必重跑设计。③ 已有 enrollment golden 绑定的是「WAV int16 往返」那条路径；先保 golden，等整链稳定后再单独评估直接用 float PCM 是否更好（**教训 ㉜**：不要为了"更高精度"无意中换 golden —— 此前 `frame_emb fp32` 就因为"更精确"而改变了 EOS 行为）。④ 采样参数是**应用的选择**而不是**模型的一部分**：把它当模型文件校验大小/hash，会导致"修好采样反而判模型缺失"（实测：改成 508 字节后 `modelStatus().ready=false`，报「朗读模型未就绪: llm_config.json」）。
- **Evidence**：
  - **Gate E1（SHA256 bit-exact，PASS）**：同一 WAV 分别跑 LEGACY `.so` 与新建 EnrollmentCore，三个产物逐位相同 ——

    | 产物 | LEGACY | 新 Core |
    |---|---|---|
    | `prompt-speech-tokens.csv` | `f2d38ac8…0180` | `f2d38ac8…0180` |
    | `prompt-cond.bin` | `c3a82e03…06b5` | `c3a82e03…06b5` |
    | `spks.bin` | `95e280f9…47e9` | `95e280f9…47e9` |

  - **全链 E2E（PASS）**：`design-loop.log` 出现
    `OK steps=98 stop=EOS prefill=34312ms decode=68222ms decoder=633087ms wav=184320 samples (7.68s) finite=yes min=-0.5500 max=0.4132 ENROLL_OK tokens=125 frames=250 ms=18158`
    —— `ENROLL_OK` **出现在 design 的返回串里**，即 native 在 decoder 之后直接产出了注册产物；随后 `音色创建完成：E2E_FINAL（125 Token）`，音色目录含 `prompt-speech-tokens.csv, prompt-cond.bin, spks.bin, source.wav, rand-noise.bin, profile.json, design.json`。用该音色合成一句话：`6.40 秒 · peak 0.898 · rms 0.156 · finite=True`（PC 侧逐项复核一致）。
  - **采样参数修复（PASS）**：同一句话「你好，我是克隆出来的音色。」，

    | | 生成长度 |
    |---|---|
    | 修复前 | 3.04 / 10.48 / 20.00 秒（3.4 倍波动）|
    | 修复后 | **2.20 / 2.20 秒（一致）** |

- **Consequences**：① 新增 lesson ㉜：**不要为了"更高精度"无意中换掉 golden** —— PCM 直连第一版必须显式模拟 int16 往返。② 新增 lesson ㉝：**MNN-LLM 的采样参数默认值是"全关"**（`top_k=-1` / `top_p=-1` / `repetition_penalty=-1.0`，见 `source/transformers/llm/engine/src/llmconfig.hpp`），不显式写入等于从全词表裸采样；官方 CosyVoice 部署用 `top_k=25 / top_p=0.8 / repetition_penalty=1.1`。③ 新增 lesson ㉞：**"应用配置"与"模型文件"必须分开校验** —— 把可调参数塞进被校验的模型清单，会让"修正参数"表现为"模型损坏"。④ `CosyVoiceEnrollmentCore` 现在是 enrollment 的唯一实现，两处调用点（WAV / PCM）共用。⑤ 后续 Decoder 提速仍按 ADR-053 单独开分支，不扰动本基线。



## ADR-055 — 评价口径冻结：规则输出永不充当 gold；cue 是证据不是 mention

- **Date**：2026-09-17
- **Status**：ACCEPTED（MOBILE-005 / M3 开工前置）
- **Context**：M2 的 G2 PASS 只证明"手机与桌面在做同一件事"，不证明那件事对。真实书实测暴露：
  `傅远坐下扫了一眼，饶有兴致道：` 被规则层判为说话人 `饶有兴致`（2-4 字 CJK、不在停用词、无代词、无动作后缀），
  于是**候选与所谓 gold 同时被污染** ⇒ 完全可能出现"模型答 饶有兴致 / gold 也是 饶有兴致 / 准确率 100% / 产品荒谬"。
  同类污染还有动词短语残留：`闫妮追问道` → `闫妮追`、`曹健开玩笑道` → `曹健开玩`。
  根因不是模型，而是**把规则层自己的输出当成了 gold**，以及**把表演提示当成了人物 mention**。
- **Decision**：
  1. **gold 分型（强制）**：`MANUAL_GOLD`（人工确认，**唯一可进硬准确率 Gate**）/ `WEAK_GOLD`（规则/legacy/teacher 自动产生）/
     `RULE_BASELINE`（RuleSpeaker 预测）/ `NONE`。`GoldPolicy.requireHardGate()` 对非 MANUAL_GOLD 直接抛错。
     报告必须分开写：`Candidate Recall @ MANUAL_GOLD` / `Director Accuracy @ MANUAL_GOLD` /
     `Agreement with Rule Baseline` / `UNKNOWN rate`——**禁止把规则一致率写成 accuracy**。
     历史冻结数据集（TASK-080）不改，只在其上叠加型别解释：其 `EXPLICIT_RULE_GOLD` 按本 ADR 是 **WEAK_GOLD**。
  2. **cue 是证据不是 mention**：`饶有兴致 / 冷冷地 / 不耐烦地 / 缓缓 / 沉声` 等 delivery cue 不得进入候选、
     不得充当 speaker，而是转为 `EvidenceItem(kind=DELIVERY_CUE, emotion_hint=…)` 供导演参考。
     判定用高精度形态（显式四字状语表 / 地·然 后缀 / 方式-情绪词根 / cue 动词自带），
     并设**实体集安全阀**：`knownEntities` 命中者永不判为 cue（形态规则不得吃掉真人名）。
  3. **UNKNOWN 语义**：`NARRATOR`=确定旁白；`C0..`=证据足以判定为某候选；`UNKNOWN`=确定是角色对白但证据不足。
     UNKNOWN 是正常答案（fail-safe），不是失败答案；候选为空 + 对话 ⇒ 直接 UNKNOWN，不调用模型猜名字。
  4. **文本与决策分离**：`ScriptLine.text` **永远**取自 canonical `sourceSpan`（`SemanticSegment.sourceStart/End`），
     模型只输出 `segment_id + speaker + type + emotion + delivery + voice_event + evidence IDs`。
     模型若输出文本，只能作为 debug 字段，**不得**成为生产文本来源。
  5. **目标选择不得以"规则层给了 speaker"为门槛**：Director 的职责正是裁决规则层解不了的段；
     只挑"规则能解"的段会把最难部分排除在评估之外，并让 UNKNOWN 永不出现。
- **Alternatives rejected**：① 继续用规则输出当 gold（会产生"100% 准确率的荒谬结果"）；
  ② 让模型生成 text_span 再由 Validator 校验（漂移只是从 70% 修到 99%，而非架构上不可能发生）；
  ③ 用长度启发式硬切动词残留（无实体集时不得启用——已作为"无实体集保持既有行为"写入测试）。
- **Consequences**：① 新增 lesson ㉟：**parity 不是正确性** —— 两端可以 100% 一致地错；
  进入语义层后，语义质量优先于 parity。② 新增 lesson ㊱：**评价尺子必须先冻结再测量**。
  ③ 规则层 recall 会下降（`傅远坐下扫了一眼，饶有兴致道：` 从"假名"变为 UNKNOWN）——这是**正确方向**：
  按不变量 5「UNKNOWN 合法，禁止强造角色名」，把无法归约的句子交给 Director 而非编造。
  ④ `ContextBuilder` 此前完全未使用 TASK-095 的 Candidate Compiler，仅靠"窗口里已有 speaker"；
  现已改为 `CUE ∪ RECENT_MENTION ∪ SCENE_ACTIVE`（新增只读方法 `canonicalNamesOf(bookPk)`），
  候选召回不再依赖规则层先给出 speaker。
- **Evidence**：`runs/mobile_005_director_real/m3_step1_candidate_gold/report.md`、
  `runs/mobile_005_director_real/m2_semantic_parity/{trace.json,desktop_canonical.txt,device_canonical.txt}`
  （G2 在新语义下重跑：desktop/device canonical md5 相同）、
  `data-room/src/test/.../m3/DeliveryCueAndGoldPolicyTest.kt`（8 测试）。

---

## ADR-056（2026-09-18）：产品模型切换 —— Reader-first（阅读器优先，Director 是剧本缓存层）

- **Status**：Accepted（用户定案）
- **Context**：解包参考 APK `io.legado.app` 3.30.6 后确认产品形态差异。此前我们把
  ReaderDirector 当"分析工具"用：导入书 → 点"分析整章" → 等 5 分钟 → 看 JSONL。
  用户的真实目标是**一款正常好用的本地阅读器**，只是在其阅读链路里悄悄接上
  ReaderDirector、角色库与本地 TTS。用户反馈的"慢 / 会退出 / 剧本不保存 / 章节有问题"
  全部源于"用实验程序思路做阅读器"。
- **Decision**：
  1. **产品目标重定义**：ReaderVoiceMobile 首先是一款完整的本地小说阅读器；
     ReaderDirector 是它的「智能剧本缓存层」；用户正常翻书，系统只在**阅读位置附近**
     按批次理解、持久化、预取；角色与音色随后复用这些持久数据。
  2. **章节规则默认保守**（对齐 Legado 内置 `txtTocRule.json`：26 条中 14 条 `enable=false`）：
     激进规则默认关闭，用户在【章节规则】页显式启用。
  3. **`ConfirmedChapterView` 是唯一生产口径**：`RawChapterCandidate[]` 只作内部诊断，
     生产 UI（目录/阅读/导演/进度）不得知晓 `PROVISIONAL / REJECTED`；
     生产代码只允许 `book.chapters[i]`（canonical），不再 `revision.chapters[i]`。
  4. **剧本 = 这本书的数据**（Book 的一部分），不是 run 产物：`ScriptRepository` 需提供
     范围查询（`preparedRange`）、缓存命中判断（sourceSpanHash + protocolVersion +
     characterRevision）与失效语义。
  5. **Director 由阅读位置驱动**：正常阅读一次只准备 8–16 段（前 4–8 / 后 1–2 上下文）；
     "整章分析"降级为可选的**预缓存**高级操作（不变量 9：Playback/阅读窗口 P0）。
- **Consequences**：
  - 立即修复：`ChapterCandidateScanner` 旧实现只对 `regexRisk >= HIGH` 的禁用规则跳过，
    导致禁用中的低/中风险宽泛规则（`《…》成对`、`====` 特定符号、`顶格标题`、`通用规则`）
    仍参与扫描并进入解析序列（真机实测 4.33MB 的书产生 16,060 个"章节"）。
    现改为 `enable=false` 一律不扫描。
  - 新增 ExecPlan `PLAN-20260918-062-reader-first-product-model.md`（R1–R5）。
  - 后续里程碑优先级从此按"阅读体验"排，而不是按"Director 能力"排。
  - 风险：关掉宽泛规则后，部分书（纯数字标题 / 无"第N章"）会没有章节 ⇒ 必须提供
    "未识别到章节 → 去【章节规则】启用"的空状态引导（R1 待办）。
- **Evidence**：`docs/agent/plans/PLAN-20260918-062-reader-first-product-model.md`；
  `parser/src/main/kotlin/com/readervoice/parser/chapters/ChapterCandidateScanner.kt`；
  `runs/mobile_005_director_real/ch0_chapter_gate/report.md`（confirmed 违规 = 0）；
  `runs/mobile_005_director_real/candidate_gate/report.md`。
