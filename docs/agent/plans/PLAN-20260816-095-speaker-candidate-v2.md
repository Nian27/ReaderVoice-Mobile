# PLAN-20260816-095 — Speaker Candidate Architecture v2（TASK-095）

## Goal
将 Speaker attribution 的第一上限从"选择能力"修正为"Candidate Recall"：Candidate Discovery 独立于 Speaker Cue；落地 Candidate Compiler（union + typed filtering + provenance），recall 61.4% → ≥85.4%（冻结 095A）→ ≥95%（095B 目标），并建立首次出场约束兜底与 2-8 候选 dev 能力。全部完成后才进入 TASK-100。

## Current Verified Facts
- A/B/C 200 条实验（real_pool_v1 真实上下文版，abc_human_label_200.jsonl，known=158/unknown=42）：
  - Gold Candidate Recall：A（regex 候选）=B（实体过滤）=61.4%（97/158）；负例 61 条
  - A 选中污染候选 19 次，B=0（实体过滤消除污染选择，但不提升 recall）
  - C 开放生成 acc@known 39.9%、UNKNOWN 47/200 → 开放生成不可入正式系统
  - Python 实验：recent_context 全文本 2-4 字 CJK 片段 ∩ 实体集 → 修复 38/61 负例 → recall 85.4%
  - 剩余 23 负例根因：① 实体集自举缺陷（"张居正"因 cue 提取失败从未进 rule_speaker → 不在实体集；"石头"被 gate "头" 误杀）② 首次出场角色（国谷裕子/伊万/蒋娜）③ 少量标注推断误差
- 实体集（books_private/real_pool_v1/entity_sets.json）构建于 rule_speaker 统计 + 形态 gate——存在循环依赖：cue 失败 → 实体缺失 → 候选缺失（用户指出的自举问题）
- RealPoolPipelineTest 候选窗口 = assign 后 recentSpeakers.take(6)（SCENE_ACTIVE），不含 recent_context 提及实体（RuleSpeakerBaseline.kt:69-70、RealPoolPipelineTest.kt:54-101）
- 旧 200 条实验的三路输入（abc_cases_200.jsonl）与标注（abc_human_label_200.jsonl）可复用为 095A 验证集（T/recent_context 不随候选逻辑变化）

## Non-goals
- 不做 GKD/Teacher 蒸馏（TASK-100 在 095 全部 Gate 通过后）
- 不引入独立 NER 大模型（手机资源限制；规则高召回 + ReaderDirector 只处理模糊项）
- 095A 不做 local opaque ID / 随机 permutation（095D 范畴）
- 095A 不做 NEW_MENTION fallback（095C 范畴）
- 不把 85.4% 写成"Speaker Candidate 已解决"（=entity-store-limited baseline）

## Invariants
- Raw source immutable；PhysicalLine != LogicalParagraph（AGENTS #1/#2）
- Mention != Identity != Embodiment != VoiceState（#3）；Character != VoicePack（#4）
- UNKNOWN 是合法结果（#5）；False Merge > False Split（#6）
- Performance causality（#7）：Mention Discovery 只建身份词典，不预灌未来剧情状态（关系/敌友/死亡/秘密身份/情绪/voice state）
- 禁止用未来证据标注过去样本；候选 provenance 必须保留（可审计"正确角色是谁召回的"）
- 文档优先级：实际代码/测试 > PROJECT_STATE > DECISIONS > Master Manual（AGENTS Change discipline）

## Scope
- `data-room/src/main/kotlin/com/readervoice/data/semantic/SpeakerCandidateCompiler.kt`（新增：CandidateSource 抽象 + CUE/CURRENT_MENTION/RECENT_MENTION/SCENE_ACTIVE source + union/dedup/eligible filter）
- `data-room/src/main/kotlin/com/readervoice/data/semantic/SemanticModel.kt`（SpeakerCandidate data class，或独立文件）
- `data-room/src/test/kotlin/com/readervoice/data/semantic/Task095GateTest.kt`（新增单测）
- `data-room/src/test/kotlin/com/readervoice/data/realpool/RealPoolPipelineTest.kt`（接入 compiler，segments 输出 candidates + sources）
- `training/readerdirector/inference/abc_build_entity_sets.py`（实体集独立化，095B 范畴——095A 复用现有宽松版）
- `training/readerdirector/inference/abc_run_three_ways.py` / `abc_sample_200.py`（候选字段消费新格式）
- 文档：docs/PROJECT_STATE.md、docs/DECISIONS.md（架构结论升级）

## Baseline
- Candidate Recall：61.4%（旧候选）/ 85.4%（Python 验证的 context-mention∩entity 上限）
- Polluted selected candidate：A=19 → 目标 0
- TRUE_UNKNOWN / IDENTITY hard negative / candidate protocol validity：不降（Task070GateTest G5/G8/G9/G10 全过为前置）
- C 开放生成：acc 39.9% / UNKNOWN 47/200（已否决，作对照记录）

## Milestones
- M1（095A）：SpeakerCandidateCompiler v1（CUE∪RECENT_MENTION∪SCENE_ACTIVE，typed sources + score）→ Kotlin 单测（Task095GateTest）→ RealPoolPipelineTest 接入 → 重导 17 书 → 重建 real_pool_v1_samples.jsonl（候选含 sources）→ 重跑 200 条 A/B 验证 → **Gate：recall ≥85.4%、polluted=0、Task070GateTest 全过**；记录 "85.4% = entity-store-limited baseline"
- M2（095B）：Mention Discovery v2 —— BookMentionIndex（全书文本独立扫描：已有角色/alias exact match + 中文人名/称谓/实体规则 + 自我介绍/称呼/呼语模式 + cue 证据），输出 MentionCandidate（surface span，不直接建 Character）→ Entity Resolver → CharacterStore；**Gate：Candidate Recall ≥95%（stretch 97-98%）、First-appearance Recall ≥90%、polluted ≈0、UNKNOWN safety 不降**
- M3（095C）：Constrained New-Mention Fallback —— 协议 `{type: NEW_MENTION, mention_id}` 与最后一级 copy-span（surface 必须逐字存在于 input）；**Gate：首次出场案例 recall ≥90%，幻觉角色=0**
- M4（095D）：Train/Dev 构造 2/3/4/5/6/8 候选分布（多人同场/上句说话者/被呼叫者/动作主体/句中非 speaker 人名/场外/UNKNOWN/new mention）；local opaque candidate ID（C0..Cn）+ 每样本随机 permutation；**Gate：dev 上 recall@K 报告（R@2/R@4/R@6/R@8），4+ 候选无退化**
- M5（095E）：冻结新 untouched Test v2（全新书）；旧 test 降为 TASK-090 historical（仅历史参考）
- M6（TASK-100 前置 Gate）：Candidate Recall ≥95%（最好 ≥97%）+ First-appearance 处理路径 + local opaque ID + TRUE_UNKNOWN/identity 不回退 + 4-6 候选 dev 集 → 才进入 Teacher distillation

## Progress
- M1 ✅ 2026-08-16：SpeakerCandidateCompiler v1（CUE∪RECENT_MENTION∪SCENE_ACTIVE，sources+score）落地；
  Task095GateTest G1-G6 全过；Task070GateTest 全过（不降级）；重导 17 书 → 重建 200 条验证集（text/recent_context 0 mismatch，标注复用有效）；
  **Gate：Candidate Recall 61.4% → 85.4%（135/158）、polluted 19→0、acc@known 36.1%→45.6%**；
  截断 bug 记录：v1 初版 SCENE_ACTIVE 全量（≤12）+ max=10 → RECENT_MENTION 被截断（recall 77.2%）；修复 = SCENE take(6) + RECENT 优先于 SCENE + max=12 → 85.4%。
  结论：**85.4% = entity-store-limited baseline**（负例 23 = 实体集缺失 ~19 + 首次出场 2 + 标注误差 2），待 095B/095C。
- M2 ⏳ 095B（规则层验证完成，2026-08-16）：Mention Discovery 三源独立于 rule_speaker：
  ① 呼语（对白开头"X，/X！"2-4 字块）② 自我介绍（我是/我叫/在下/本座…）③ cue 主语滑动（cue 动词前 10 字内 2-4 字片段，≥3 次）。
  结果（200 条验证集，标注复用）：**v2 实体集（呼语+介绍）→ 89.9%**；**v3（+cue 主语滑动）→ 93.0%**；
  别名映射（老三→刘杰/贤侄女→柳子安/小田切→小田切敏郎）+ 称谓后缀剥离（蒋娜小姐→蒋娜）+ 官职前缀（西城县令高传式）→ **93.7%（148/158）**。
  剩余 10 负例：T 内自我介绍 2（国谷裕子/伊万 → 095C）、官职/称谓正式映射 4（高传式/蒙江/席兴白/狄青麟 → identity resolution）、上下文无 2（柯南/张远，超窗口）、标注推断误差 2（谢令姜/萧思衡，不改数据）。
  **已知问题（落地前必须解决）**：v3 cue 滑动实体集膨胀 37 万（RECENT_MENTION 平均 30 候选/case，4B 不可用）；最长匹配提取 91.8%/mean 17.7 仍偏高——095B 落地版需精炼实体集（gate/频率/精确块）或候选截断策略。
  **Gate 状态：93.7% < 95% 未过**（M2 未完成；差距 = identity resolution 别名层 + 095C + 标注置信）。
- M2 ⏳→✅ 095B 变更为 AI-assisted Mention Discovery（用户架构升级 2026-08-16）：
  095B.2（AI Mention Discovery feasibility）**PASS**：Cross-domain Challenge Set 150 条/15 域/186 gold（人工构造）；
  三路对比 Rule 29.6%（跨域崩盘）vs 2B few-shot 88.2% exact/95.7% contain vs **4B few-shot 93.5% exact/98.4% contain/99.5% exact-span**；
  Gate 全过（AI Recall ≥97-98% ✅、Exact-span ≥99% ✅、明显优于规则 ✅）→ **AI = primary mention discoverer 定案（ADR-038）**；
  Rules = seed/validation/fallback；MENTION 成为 ReaderDirector 正式任务；Rule mention baseline（93.7% speaker recall / 29.6% mention recall）只作对照。
  资产：runs/task095/095B2_mention_feasibility.md、challenge_mentions_150.jsonl、mention_{2b,4b}_fs_results.jsonl。
- M2.5 ✅ 095B.3（2026-08-16）：0.8B MENTION task 训练完成（checkpoint-783，epoch=1.0，从 checkpoint-394 续训，lr 5e-5/1epoch/r8，MENTION 45%+replay 55%）；
  **能力评估（frozen challenge 150）：Exact Recall 88.2%（与 2B few-shot 持平）/ Contain 91.9% / Precision 90.0% / Exact-span validity 100%**；
  分域：transliterated/long_transliteration/english_name/chinese_name 100%，first_appearance 91%；弱域：nonhuman_agent 58%、compound_surname 77%、title_office 79%、apostrophe 80%、fantasy_title 80%；
  **audit_300（222 条人工审核）：Semantic Recall 85.4% / Precision 87.6% / True NONE 81.8% → A1 Gate FAIL**；
  归因：A1-1 Teacher MISS（呼语/代词/称谓，NONE 桶 6 条污染）→ prompt v2；A1-4 非实体当实体（FALSE 24 条：作品/物品/地点/病毒/律法/泛称）→ **ADR-039 Type Policy** + prompt v2 负例；A1-3 NONE 污染 → UNVERIFIED_NONE；
  处理中：Dev-QC 四轮（v2 规则文本/v3 示例化/v1+400/v1+动态）→ 定案 v1+400（回归最少、无碎化、True NONE 27/27）；环境不一致发现（历史 mention_train 与当前 4B 行为不可复现差异）→ 当前环境全量重标 mention_train_v2.jsonl（v1 prompt+400 tokens，4250 条）——**已完成（2026-08-18，4250/4250，末行 JSON 闭合，span 验证全过）**；
  **A2 预检（v1 checkpoint-783 vs r8 baseline）**：SPEAKER 0.667→0.733 ↑、IDENTITY 1.0 ✅、false_merge 0 ✅、hard_negative 0 ✅、TRUE_UNKNOWN 50/50 ✅、candidate_violation 0 ✅、**VOICE_STATE 0.714→0.143 ❌（灾难性遗忘）** → 修复：to_swift 混合 MENTION 45%→40% + VOICE_STATE replay ×3→×6（v2 训练生效）；
  **v2 训练完成（2026-08-18）**：to_swift 重建 sft_v2_mention_v2.jsonl（12,473 条，MENTION 40%/IDENTITY×3/VOICE_STATE×6，sha256=38582ad3…）；0.8B 续训 checkpoint-394 → **checkpoint-780（epoch=1.0，runs/task095/mention_r8_v2/v3-20260818-212221）**；训练注：swift 预处理需 --dataset_num_proc 0（沙箱禁 multiprocess），末批残批触发 padding-free 需 --dataloader_drop_last；
  **A2 复检（v2 checkpoint-780）**：SPEAKER 0.733 ✅（保持）、IDENTITY 1.0 ✅、false_merge 0 ✅、hard_negative 0 ✅、TRUE_UNKNOWN 50/50 ✅、strict_valid 1.0 ✅、candidate_violation 0 ✅、**VOICE_STATE 0.286（↑自 0.143，仍 < baseline 0.714，gold 仅 7 条）** → 待 A3/A4 正式回归；
  **A3 regression vs v1（2026-08-18，checkpoint-780）**：Exact Recall 89.2%（↑88.2%）/ Contain 93.5%（↑91.9%）/ Precision 89.7% / Exact-span validity 100%；弱域改善 compound_surname 85%/title_office 86%/apostrophe 90%；nonhuman_agent 58% 持平 → **定性 = REGRESSION PASS（vs v1），capability gate = FAIL（Recall 93.5<95、Precision 89.7<90）**；产物 runs/task095/mention_qwen3.5-0.8b-base_loracheckpoint780_fs_results.jsonl；
  **A4 Agent 初判完成（2026-08-18，221 条）**：Silver 数据质量 Semantic Recall 98.8%/Precision 85.8%/True NONE 90.9%（Gate 95/90/95 FAIL）→ **人工确认 PENDING**；MISS 4（呼语/称谓）、FALSE 54（ADR-039 非实体为主）、NONE 污染 3/33；报告 A2_AUDIT_REPORT.md；
  **状态 = CONDITIONAL FAIL / ACCEPTANCE PENDING（收敛阶段，暂不进入 095C）**；**Challenge 150 降级为 Cross-domain Dev Challenge v1**（曾被反复查看影响策略 → 非 Frozen Test；Locked Challenge v2 另建，开发期不看分类错误）；
  **A5 完成（2026-08-18）**：VOICE_STATE 独立 dev 扩充集 dev_voice_state_v1.jsonl（75 条 = 5 动作 × 15 均衡，全 EXPLICIT_RULE_GOLD，确定性状态机复刻 build_sft_v1，sha256=4644d3ae…；eval_checkpoint.py 新增 --data 参数）；三 checkpoint 评估：**baseline 394 = 0.387、v1-783 = 0.453、v2-780 = 0.453** → **结论：无灾难性遗忘（v2==v1，且 v2 > baseline）；原 0.714→0.143→0.286 是 7 条 NONE 偏斜 gold 的评估假象（394 押 NONE 得 5/7，v1/v2 押 START/REPLACE 得 1/7）**；
  **A5 根因定案（2026-08-18，用户确认）：VOICE_STATE 任务定义有缺陷，模型走捷径是正确策略**。证据：① 训练 200 条全为合成状态机（TARGET=第N句，prompt 无任何真实文本上下文）；② 相同 current_state 对应多个 gold action（phase=ADULT 对应 START 15+NONE 15，熵 1.0 bit；temp=START 对应 REPLACE 10/END 5/CONTINUE 3，熵 1.42 bit）→ 输入信息不足，任务不可判定；③ 按 current_state 最优猜测理论上限 49.3%，v2 实测 45.3% 已接近上限 → 模型不是没学会转移，而是给定输入下转移本就不可推断。**修复方向（v3）**：VOICE_STATE 必须携带真实文本上下文（TARGET 前后文/说话内容/对话线索），gold 由文本证据规则或 Teacher 标注驱动（如自称变化→REPLACE、情绪转变→START），禁止纯随机状态机；A5 集需重建为真实书上下文版；
  **下一步执行顺序（用户定案，2026-08-18 更新为 TASK-095-VS2）**：
  - ✅ **ADR-040**：legacy VOICE_STATE 五分类直接预测废除（identical inputs → random golds，证据：熵 1.0-1.56 bit、理论上限 49.3% vs v2 实测 45.3%）；旧 200 训练 + 75 A5 仅作 historical diagnostic，禁调 replay 倍率
  - ✅ **ADR-041**：VoiceEvent 新定义（NO_EVENT/TEMP_SET/TEMP_CLEAR + style/target/scope/evidence_span/confidence；type!=NO_EVENT 必须 exact evidence_span；emotion/prosody/scene-exit 禁止视为变声）
  - ✅ **VoiceStateReducer**（inference/voice_event.py）：确定性转移矩阵 19 checks 100% PASS（none+NO_EVENT→NONE、tempX+NO_EVENT→CONTINUE、none+SET→START、tempX+SET(X)→CONTINUE、tempX+SET(Y)→REPLACE、tempX+CLEAR→END、none+CLEAR→NONE、utterance+boundary→END）
  - ✅ **feasibility set v1**（410 条真实书上下文）：rules 高召回检索（TEMP_SET cue 2551 命中 / TEMP_CLEAR 148 / hard_neg 27807）+ 4B few-shot + host span 验证 → host validity 100%、hard_neg 拒绝 99%、none 100%
  - ⏳ **Gate VS-F = FAIL**：TEMP_SET Precision proxy 约 68-73%（41 条人工抽检，误报 4 类：固有音色/物理原因/情绪语气/行为伪装）→ 需 prompt v2（负例强化）+ cue 精化（限定声音域）+ 重标 → 报告 runs/task095/VS2_FEASIBILITY.md
  - ⏳ **A4-fast 完成**：MENTION 残差 = 真 FP 45（ADR-039 非实体，host 可滤，非协议缺陷）+ MISS 4（呼语/称谓）+ NONE 污染 3/33 + boundary 0 → **不重标 MENTION，host 非实体过滤层（095C）+ 呼语补漏**；报告 A4_FAST_REPORT.md
  - ✅ **VS2.2 冻结（2026-08-18，v33 全 Gate 达标）**：4 known FP corrected 4/4；7 known TP retained 7/7（#207 模仿+修饰词回归已修）；hard_neg→OVERRIDE 0/80；host validity 410/410=100%；OVERRIDE_SET 7/7 + 新正确检出 #217（模仿朱五太爷声音）/#83（假装福建口音）；CLEAR 3/3；PERFORMANCE 3→SET 全为正确检出非 leakage → **四类 ontology（VOICE_OVERRIDE_SET/CLEAR/PERFORMANCE/NONE）正式冻结，不再 v3.4/v4 prompt engineering**
  - **VS 长程路线（用户定案）**：VS3 VoiceEvent IR（event_id/book/segment/span/subject/confidence/revision）→ VS4 deterministic Reducer（NONE/PERFORMANCE/SET/CLEAR/REPLACE/ORPHAN_CLEAR，含 PERFORMANCE 不泄漏测试）→ VS5 VoiceStateTimeline（按 narrative position 可重建，跨章不自动清、不跨 book）→ VS6 Subject Binding（UNKNOWN_SUBJECT→PENDING_BINDING）→ VS7 regression corpus → VS8 0.8B SFT → VS9 Kotlin/Room 集成 → VS10 NarrationIR/PerformanceInstructionCompiler → VS11 CosyVoice E2E → final_voice_state_v1_report.mdreplay，删除旧随机 VOICE_STATE）→ v3 训练（checkpoint-780 续训）
  - **VS6 冻结（2026-08-19，CONDITIONAL PASS）**：VoiceEventBinding schema（14 checks）+ deterministic seeds（18 checks，observer trap 防污染）+ GroundingResolver v2（10 checks，host 用 owner_surface 重映射 ID，16 条修正）；160 fixtures × 15 类；4B 7 轮迭代 56.3→95.1%：Owner Semantic 95.1%（差 Gate 97% 1.9pp）/ Grounding 95.1% / **UNKNOWN Precision 100%** / Observer-Causer 97%（1 错）/ host validity 100%；**剩余 7 错 = pronoun 3（fixtures 单句不可判，归 ContextBuilder §11）+ borrowing 语义分歧 3（4B 倾向 owner=声音归属，VS7 训练重点）+ causer 1**；失败树走完（contrastive few-shot ✅ / VS6.4 两阶段 ❌ 75.7% 反效果 / 9B ❌ 19GB>16GB 显存不可行）→ **不再 prompt engineering**；产物 runs/voice_state/VS6_REPORT.md、runs/task095/vs6_binding_fixtures.jsonl + vs6_binding_results.jsonl（v7 版，修正后 gold 5 条）
  - **下一步**：VS7 regression corpus（TASK_VOICE_EVENT + TASK_VOICE_BIND 分离标签；borrowing/causer 成对样本为训练重点；按书 split）→ VS8 0.8B 训练（同 adapter 续训 + MENTION hard + VOICE_EVENT + VOICE_BIND + 旧任务 replay）
  评估/训练脚本：095b3_eval_lora.py（swift 加载链 + processor.tokenizer 修复）、095b3_train_watchdog.sh（resume 路径+反斜杠修复）、eval_checkpoint.py（adapter 键重映射，A2 工具就绪）

## Decisions
- D1：候选公式冻结为 union（Ccue ∪ Ccurrent ∪ Crecent ∪ Cscene ∪ Cnew ∪ Clocked）+ eligible filtering，不是 intersection（用户规格 §九）
- D2：SpeakerCandidate 携带 sources（CUE/CURRENT_MENTION/RECENT_MENTION/SCENE_ACTIVE/NEW_MENTION/USER_LOCKED）——可审计召回来源
- D3：开放生成否决（C 实验 39.9%/47 UNKNOWN）；首次出场走 constrained NEW_MENTION/copy-span（§七/§八）
- D4：实体发现 = 规则高召回 + ReaderDirector 只处理模糊项；不引 NER 模型（§五）
- D5：095A 的实体集沿用宽松 gate 版（rule_speaker 统计）；自举缺陷在 095B 修复（Mention Discovery 独立）
- D6：Candidate ID 全局 local opaque（C0..）+ 随机 permutation——在 095D 落地，095A 先保留 surface 以便对照验证集

## Experiments
- E-095A-1：候选 = SCENE_ACTIVE（旧）→ baseline recall 61.4%（对照，已测）
- E-095A-2：候选 = SCENE_ACTIVE ∪ RECENT_MENTION（recent_context∩entity）→ 预期 recall 85.4%（Python 已验，Kotlin 复现）
- E-095B-1：Mention Discovery v2 实体集 vs rule_speaker 实体集 → recall 差异 + First-appearance recall
- （结果以 Gate 报告为准，禁止把预测当结果）