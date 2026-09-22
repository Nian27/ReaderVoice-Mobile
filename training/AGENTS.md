# training/ AGENTS.md

ReaderDirector 训练/蒸馏/评估。**训练栈全新搭建**（ms-swift + Qwen3.5，与 CosyVoice 蒸馏 lab 无关）。

## 冻结事实

```text
GPU      = RTX 4060 Ti 16GB（WSL2 Ubuntu 优先；本机 Windows + Python 3.12 实测）
Student  = Qwen3.5-0.8B-Base（TASK-090 LoRA 学生初始化；官方定位：纯预训练 + chat control tokens）
Probe    = Qwen3.5-0.8B-Base（TASK-080 仅 protocol probe：JSON valid/candidate obedience/EOS/长度，不进能力榜）
Zero-shot= Qwen3.5-0.8B / Qwen3.5-2B（post-trained，TASK-080 三线：A Rule-only / B 0.8B / C 2B upper；D Base probe）
Teacher  = Qwen3.5-2B（upper bound / teacher candidate；Qwen3.5-4B/9B 量化离线推理后续评估）
方法     = 一个 Multi-task LoRA（8 任务共享），禁止叠多 LoRA
路线     = 0.8B 先 BF16 LoRA（rank 8/16/32 消融）；2B 必要时 QLoRA
数据     = 按书 split 70/15/15，系列整体同 split
推理     = 全部 enable_thinking=false + temperature=0（BASELINE_MODE=NON_THINKING）；max_input_tokens=2048；max_new_tokens=32
下载     = huggingface.co 被墙，必须 HF_ENDPOINT=https://hf-mirror.com
```

## 数据纪律

```text
1. 训练数据必须带 provenance（v5 §65）：LEGACY_V907 / LEGACY_V907_AUDITED / LEGACY_V907_USER_CORRECTED /
   EXPLICIT_RULE / TEACHER_4B / TEACHER_9B / HUMAN_GOLD
2. LEGACY_V907 单独 ≠ Gold；只有 v90.7 + Human Audit 才是 Gold
3. 禁止把 v90.7 JS 源码当训练语料（授权不明 + 无必要）——学行为，不背书源码
4. 禁止用 Test Book Set 调参；Teacher 不是绝对真值（协议：Rule+Legacy+Teacher 三方）
5. Hard-only 会过拟合：初始混合 50% Gold/core : 30% Hard : 20% Silver
6. 去重：按 normalized template hash 控制 "XX说道" 类简单模式泛滥
7. 难例采样要平衡任务，Emotion 不得淹没 Speaker
- VS8 系评估：prompt 必须与训练数据字节一致（从冻结 sampler 提取校验）；手工重打 prompt 曾造成 SET R 32.1% 假失败（ADR-043）

```

## 训练规程

- 正式 Run 前 100-step smoke（记录 peak VRAM / tokens/s / loss / grad norm），4060Ti 不顶满 16GB。
- Run ID 命名：`RD08_S1_GOLD_R16_YYYYMMDD_seedYYYYMMDD`；产物落 `training/runs/<run-id>/`（command.txt / environment.txt / run.json / dataset_manifest.json / metrics.json / report.md / checkpoint）。
- Stage 延续：Stage1 Gold → checkpoint → Stage2 Distill → checkpoint → Stage3 Hard/Correction，**继续同一个 adapter**；消融从 Base 分叉新 adapter。
- 环境版本全部冻结：environment.yml / pip-freeze.txt / GPU_DRIVER.txt / MODEL_MANIFEST.json（模型 revision 固定，禁止 main/latest）。
- 训练判定：没超过 baseline（0.8B few-shot / 0.8B Post / 2B）不算成功，loss 变低不算数。

## 蒸馏

- V1 = Offline Behavior Distillation：Hard Case → Teacher → 结构化答案（label/confidence bucket/evidence span/type）→ 一致性/人工审查 → SFT Student。不做单卡在线 GKD。
- Teacher 不保存长 CoT；同卡禁止 Student/Teacher 同时常驻（先 Teacher 批量标注再 Student 训练）。
- 每轮与旧 checkpoint A/B；Gate 见 v5 §81（Auto-confirm Precision ≥ 98% 为第一优先）。

## Gate 判定

v5 §81：Speaker（Accuracy/Macro F1/Auto-confirm Precision≥98%/Coverage/UNKNOWN Recall/3+人）、Identity（False Merge Rate 第一优先）、VoiceState、Emotion、Dialect。0.8B vs 2B：核心指标 ≤1–2pp 且 False Merge 不恶化 → 用 0.8B。
