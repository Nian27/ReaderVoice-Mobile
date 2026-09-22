# PLAN-20260818-096 — Voice Factory：自动角色声音设计可行性（TASK-096）

## Goal
回答唯一问题：**能否根据角色描述自动生成稳定的专属声音（VoicePersona → VoiceDesign → anchor），并作为 reference 供小型 zero-shot TTS 长期复用？**
不是：现在就把 Qwen3-TTS 移植 Android。

## 与主线关系（用户 §21/§44）
- 与 VS6-VS8 主线正交：Voice Factory 决定**默认声音怎么来**；VoiceState 决定**当前位置用什么声音**；Performance 决定**这一句怎么说**
- GPU 调度：VS6 正式 eval / VS8 训练优先；TASK-096 只用空闲 GPU 时段；写脚本/persona 集/分析可并行
- VS10 前必须读取本任务决策（可能改 VoicePack 来源，不改 Character/VoiceState/Performance/NarrationIR）

## Current Verified Facts
- CosyVoice3-MNN 基线冻结（RTF 0.79-0.96 / enrollment 扩展可行 / 胡桃音色样例在库）——Route C 基础存在
- Qwen3-TTS 尚未本地化（模型/工具链待确认）——Route Q 是研究项
- VoicePersona 与 Performance 必须分离（§24）：persona 是长期属性，不能因第 80 章愤怒反向改写第 1 章

## Milestones
- **096A VoicePersona 定义**：14 维结构化字段（perceived_gender/voice_age/pitch_range/timbre/weight/brightness/roughness/breathiness/authority/energy/speech_rate/social_style/accent/special_traits）+ free_description；**禁止**把情绪/单句表演写进 persona
- **096B Persona Test Set**：24 personas（男女 × 少年/青年/中年/老年 × 高/中/低 pitch × 明亮/低沉 等），含 near-neighbor 对（中年低沉威严男 A/B）验证可分性
- **096C Qwen VoiceDesign anchors**：每 persona 生成 3 个 anchor 候选（统一 reference text：neutral + phonetic coverage + moderate prosody，禁强情绪文本）→ QC（内容/停顿/爆音/长静音/情绪/年龄/性别/Persona match）→ CanonicalVoiceAnchor
- **096D 双 Runtime A/B**：Route Q（anchor → Qwen3-TTS 0.6B zero-shot clone）vs Route C（anchor → CosyVoice3 enrollment → CosyVoice3）；每 persona ≥10 unseen 句
- **096E 决策树**：A) Qwen clone≈design 且 CosyVoice 掉身份 → 研究 Qwen Android runtime；B) 两者≈ → 走 CosyVoice3-MNN（当前最省工程量）；C) 都损失 → 修 anchor selection/reference 参数；D) design 本身不稳 → 修 PersonaCompiler/instruction，不怪 clone
- **096F 真实小说验证**（096A-E 过才做）：20-30 Character Personas 自动生成，验证角色自然区分/不撞声/男女年龄合理/长期稳定
- **096G 端侧决策**：PC A/B 明确成功后才考虑 Android；VoiceDesign 1.7B 低频模型仅新角色设计时加载（手机跑不动则 PC 版/未来 Tiny VoiceDesigner）

## 主指标（§30）
Design Adherence / Clone Retention（anchor vs zero-shot speaker similarity）/ Inter-character Separation（不同 persona embedding 距离 + 人工 ABX）/ Cross-sentence Consistency（同角色 10 句 variance）/ Content（CER/WER）/ Persona Retention（clone 后年龄音高厚薄仍一致）/ Performance Robustness（neutral/happy/sad/angry/low voice/fast 改表演不改音色）

## 第一轮不做（§33）
Android port / MNN/QNN/NPU 转换 / LoRA 训练 / VoiceDesign 蒸馏。第一轮只回答产品概念值不值得继续。

## 纪律
- 禁止为 TASK-096 修改 VS2-VS5 冻结层；问题分层：VOICE_PERSONA / VOICE_DESIGN / VOICE_CLONE / PERFORMANCE / TTS_RUNTIME
- 失败路线同等记录；每个实验落 runs/<run-id>/（run.json/metrics/report.md）

## Progress
- 2026-08-18：ExecPlan 建立（用户定案，并行线）。
- 2026-08-19：**096B 完成**——24 VoicePersona v1（runs/voice_state/voice_personas_v1.json；male 13/female 11；少年2/青年8/中年7/老年4/少女3；含 near-neighbor 对 P17-P18 中年低沉威严男 A/B、P19-P20 活泼甜美少女 A/B；字段 14 维 + free_description）；等待 VS6 Gate → 096C VoiceDesign anchors