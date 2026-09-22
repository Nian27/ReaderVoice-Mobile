# TASK-090.1 — Candidate-Cardinality Robustness Patch

**状态：PASS（G1-G12，见文末）** | 2026-08-13
模型：`Qwen3.5-0.8B-Base` LoRA r8/a16（唯一训练配置，G11）
数据：SFT v1.1（sha256=29630abb…，6,300 样本 = v1 5,592 + cardinality 708）

## 0. 关键过程事件（诚实记录）

1. **TASK080_TEST_V1 降级**：`EXPOSED_DIAGNOSTIC`（不再作为无偏 test；仍保留报告）。
2. **post_cue 可见化**：原管线后置 cue（"钱二答道。"）在 prompt 中不可见（gold 有、证据无）→ 新增 `post_cue` 字段 + `speaker_v2.txt`（无 post_cue 时与 v1 逐字节一致，已断言验证）。
3. **生成器 gold 对齐 bug（重要）**：`speaker_sample` 在 pattern 分支前用随机 index 构造 target → 前置/后置/别名/附身模式全部标签错位（cue 指张三、gold 是随机候选）。由 per-pattern 异常暴露（preposed_pos1 22% < mid 44%，pattern 一致反常）。**修复：target 在 pattern 分支后统一构造**；alignment 检查 0/225 错位。
   - 后果：旧 test_v2（5ae7f76e）、旧 SFT v1.1（7f578e5e）、旧 dev_card 全部作废重新生成；**重训一次**（旧 checkpoint-394 进程在 360 步异常死亡，日志被 tail 缓冲丢失 → 重训改日志直写 + `--save_steps 100` 防全丢）。
4. **32-token 截断发现**：长 opaque id（25 字符 cluster id）+ JSON 样板 > 32 token → 生成被截断算协议失败。dev @48 strict 100%（截断全解）。test_v2 用短 id 不受影响。评估器加 `--max_new_tokens` 参数。

## 1. 新增评估集

| 集 | 内容 | 规模 | hash |
| --- | --- | ---: | --- |
| **TASK090_TEST_V2**（新 locked test，训练前锁定） | book D/E 全新 synthetic：2-6 候选全档 × 前置/后置 cue/最近≠speaker/TRUE_UNKNOWN/alias/embodiment + identity（SAME/DIFFERENT hard/UNKNOWN trap）+ voice 状态机 | 108 | **24b0711c…** |
| DEV-CARD A/B/C | 2-6 候选 × 6 pattern × 3 books × 3 reps | 270 | 23aa2db2… |
| SFT cardinality | cardinality × gold-position 正交化（K×pos×pattern×10 + alias/embodiment + TRUE_UNKNOWN 60） | 708 | 41b321ff… |

## 2. 结果

### DEV-CARD（SpeakerAcc@K / Perm@K，max_new_tokens=32）

| K | acc@K | perm@K | cand_viol | TRUE_UNKNOWN | n |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 | **1.000** | **1.000** | 0 | 1.0 | 54 |
| 3 | **1.000** | **1.000** | 0 | 1.0 | 54 |
| 4 | **1.000** | **1.000** | 0 | 1.0 | 54 |
| 5 | **1.000** | **1.000** | 0 | 1.0 | 54 |
| 6 | **1.000** | **1.000** | 0 | 1.0 | 54 |
| Δ4/Δ5/Δ6 | **0 / 0 / 0** | | | | |

per-pattern 全 45/45：preposed_pos1/mid/last、postposed、recent_neq、true_unknown。

### 各集汇总

| | dev_card | TEST_V2 | dev (fixture, @48) | TEST_V1 (@48, 诊断) |
| --- | ---: | ---: | ---: | ---: |
| SPEAKER acc | 1.000 | **1.000 (80/80)** | 0.667 | 0.667 |
| strict JSON | 100% | 100% | 100% | 98.8% |
| cand violation | 0 | 0 | 0 | 1.2%（1 例名字回显） |
| IDENTITY | — | 1.0 (6/6) | 1.0 | 1.0 |
| false_merge / hard_neg_viol | — | 0 / 0 | 0 / 0 | 0 / 0 |
| VOICE | — | 0.455 (22 gold) | **0.714**（↑ 自 0.429） | 0.5 |
| TRUE_UNKNOWN probe (50) | — | — | — | **1.0** |

## 3. 诚实标注（必须）

- **DEV-CARD 与 TEST_V2 与训练数据同模板生成**（同一 SCENES/LINES/CUE_VERBS 池，仅 seed 不同）→ **1.000 是分布内结果**，证明"候选数量 + 位置 + 后置 cue 的鲁棒性在协议/分布层面完全解决"，**不能外推真实小说**。
- 真实文本信号：fixture dev SPEAKER 0.667、TEST_V1 0.667（TASK-090 为 0.556，**有提升**）；dev voice 0.714（显著提升）。真实文本泛化仍 OPEN（TASK-100 Hard Mining 的目标）。
- dev SPEAKER 0.667 vs TASK-090 的 v2-r8 0.867：cardinality 数据（简单模板）稀释了 fixture 复杂上下文的表现——分布偏移代价，如实记录。

## 4. Gate（G1-G12）

| Gate | 判定 |
| --- | --- |
| G1 SFT v1.1 hash frozen | PASS（29630abb…，含 cardinality 708 修复版） |
| G2 test_v2 训练前生成并锁定 | PASS（24b0711c…；旧 5ae7f76e 因生成器 bug 作废，重生成后锁定，记录在案） |
| G3 test_v2 不参与调参 | PASS（重训过程未查看 test_v2 标签级错误） |
| G4 SpeakerAcc@2..6 全报告 | PASS（1.000×5，ΔK=0） |
| G5 permutation consistency 报告 | PASS（1.000×5） |
| G6 candidate violation = 0 | PASS（dev_card/test_v2/dev 均 0；test_v1 诊断 1.2%） |
| G7 TRUE_UNKNOWN 不倒退 | PASS（1.0，与 TASK-090 持平） |
| G8 Identity false merge = 0 | PASS |
| G9 VoiceState 不倒退 | PASS（dev 0.714，↑） |
| G10 strict JSON ≥99% | PASS（dev_card 100 / test_v2 100 / dev 100；test_v1 98.8% 诊断） |
| G11 只训 r8 | PASS（rank 8 / alpha 16 唯一配置） |
| G12 regression/security PASS | PASS（:data-room:test 全绿；secret_scan 314 文件无命中；baselines PASS） |

## 5. 产物

```text
training/readerdirector/
├ sft/gen_cardinality.py（test_v2/dev_card/sft 三合一生成器，确定性 seed）
├ prompts/{speaker,identity,voice_state}_v2.txt（post_cue 槽；v1 保留冻结）
├ inference/{eval_cardinality.py, eval_checkpoint.py(--max_new_tokens), true_unknown_probe.py}
└ dataset/（gitignored）test_v2.jsonl + TASK090_TEST_V2_MANIFEST.json + dev_card.jsonl + sft_v1.1.jsonl
```

## 6. 下一步（TASK-100）

按冻结路线进入 Teacher Distillation + Hard Mining。Teacher 定位修正：**2B = Teacher Candidate**（非 Gold Teacher——TASK-080 证明其自身有近因偏差/后置 cue 盲区/不输出 UNKNOWN）；9B 参与难例；Silver 需 Rule gold + Human audit + Teacher agreement 三方。本任务不再分支。
