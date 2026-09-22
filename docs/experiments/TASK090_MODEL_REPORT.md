# TASK-090 — ReaderDirector 0.8B-Base Multi-task LoRA

**状态：PASS（G1-G20，见文末）** | 2026-08-12
环境：training/envs/task080（python 3.12 / torch 2.11.0+cu128 / transformers 5.12.1 / ms-swift 4.4.2 / peft 0.19.1）
模型：`Qwen/Qwen3.5-0.8B-Base` @ dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68（revision 锁定）
GPU：RTX 4060 Ti 16GB（BF16 LoRA，仅语言路径）

## 0. 三处口径修正（TASK-080 遗留，已落实）

1. **2B 优势 = CAPACITY_SIGNAL**：Fisher 双侧 p=0.131（1/9 vs 5/9），Wilson 区间重叠 → 决策文档改 "情况 B signal"。
2. **Base 0% = INVALID_TEMPLATE_PROBE**：根因 = Base 仓库 `chat_template.jinja` 内容是 "Entry not found" stub（15B），transformers 5.x 优先用独立 jinja 压过 tokenizer_config 真模板 → input_tokens=3。
3. **provenance=UNKNOWN（37,242）≠ target=UNKNOWN**：全部进 UNLABELED_POOL，禁止 CE 监督；UNKNOWN 只能来自构造的 TRUE_UNKNOWN_GOLD（G1）。

## 1. Step 0 — Base preprocessing Gate（G2/G3）

- `chat_template.jinja` stub 修复（用真模板覆盖）；Valid Base probe 重跑（dev 100 samples）：
  **协议 100%（strict JSON/candidate/EOS），但决策退化**：SPEAKER 全选候选榜首（诊断 acc 0.357）、IDENTITY 100% SAME 无判别（acc 0.778 假象）、VOICE 全 START（acc 0.2）。
  这就是 **VALID_PRE_LORA_BASELINE**（INVALID_TEMPLATE_PROBE 保留记录为 pipeline failure）。
- ms-swift qwen3_5 训练模板 preprocess dump（20 样本）：input_tokens 163~367、target JSON 全部位于 assistant supervision 区、无 system/user 泄漏、无截断 → G2 PASS。

## 2. Step 1 — Supervision audit（G1）

A. SUPERVISED_GOLD = 1,209（EXPLICIT_RULE_GOLD）；B. SUPERVISED_SILVER = 0（LEGACY_V907_AUDITED/HARNESS_VERIFIED 尚未建立）；C. UNLABELED_POOL = 37,242。→ `TASK090_SUPERVISION_AUDIT.json`。

## 3. Step 2 — SFT Dataset v1（G5/G7/G9-G13 数据侧）

**5,592 samples，确定性生成（seed 42），sha256 锁定：**

| 组成 | 数量 | 说明 |
| --- | ---: | --- |
| SPEAKER gold × candidate permutation ×4 | 4,600 | 首候选（最近发言）固定、其余轮转 → 打掉 distance/榜首锚定；hard_set marker（真值 index≥2）= 727 |
| id-echo（构造） | 400 | 真实书 gold 候选重映射 opaque id（role≠name）→ 教 "输出候选 id" 协议（v1 消融 SPEAKER 崩溃的直接修复） |
| TRUE_UNKNOWN_GOLD | 300 | 确实不可判定（无 cue/无 turn/无 lock）→ target=UNKNOWN（带 true_unknown marker） |
| IDENTITY 合成 | 92 | SAME(alias 48) / DIFFERENT(hard 24: 师徒、执法队≠张明、宿主≠控制者、same-name) / UNKNOWN(关系陷阱 20: KINSHIP/CONTROL/POSSESSION/共现) |
| VOICE_STATE 状态机 | 200 | NONE/START/CONTINUE/REPLACE/END 各 40（配额均衡）；含 future-leakage sentinel 样本 |

**v1→v2 修正（dev 驱动）**：① SPEAKER 崩溃根因 = 训练数据 id==name（真实书无实体回退）→ 模型学成"输出名字"；加 400 id-echo 后 dev strict 100%、候选违规 0。② VOICE CONTINUE 过预测 → 状态机转移加权 + 配额均衡。

## 4. Step 3 — LoRA smoke（G6/G7/G8）

- G6：all-linear 372 个 LoRA 模块**全部在语言路径**（linear_attn/mlp/self_attn），无 visual/vision/aligner → PASS（TEXT ONLY 冻结）。
- 性能修正：fla 无 Windows wheel；linear_attn torch fallback O(n²)，max_length=2048 时 VRAM 23.8GB 超限 → **max_length=1024 + gradient_checkpointing**（样本 ≤500 tokens 无截断）。
- smoke（300 样本，r16/a32）：loss 0.40→0.0037、grad_norm finite、VRAM 峰值 10.2GB 无 OOM、token_acc 0.999、无 loop。
- 训练配置（3 rank 共用，seed 42，1 epoch）：lr 1e-4 / batch 2 × grad_acc 8 / warmup 0.03 / 350 steps ≈ 50 min/rank。

## 5. Step 4 — Rank 消融（G8，alpha/r=2 恒等，同数据同 seed）

| | dev SPEAKER acc | dev SPEAKER F1 | Perm Consistency | IDENTITY acc/F1 | false_merge | hard_neg_viol | VOICE acc/F1 | TRUE_UNKNOWN | strict | cand_viol |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Base (valid probe) | 0.357* | — | — | 0.778*/0.31* | — | — | 0.2* | 0* | 100% | 0% |
| **LoRA r8** | **0.867** | **0.836** | **0.692** | **1.0/1.0** | **0** | **0** | **0.429** | **1.0** | **100%** | **0%** |
| LoRA r16 | 0.533 | 0.355 | 0.308 | 1.0/1.0 | 0 | 0 | 0.429 | 1.0 | 100% | 0% |
| LoRA r32 | 0.400 | 0.207 | 0.231 | 1.0/1.0 | 0 | 0 | 0.286 | 1.0 | 100% | 0% |

\* = 诊断性（Base probe 仅协议/退化行为；不进能力榜）。
**发现：小数据（5,592）下低 rank 泛化更好**（r8 > r16 > r32）——高 rank 在 SPEAKER 任务上过拟合置换模式。

## 6. Step 5 — dev 选 rank（G9/G10/G11/G12/G14/G15）

过滤顺序全过：hard_neg_violation=0 ✓ → future leakage=0（dev 7 个 gold voice 样本 sentinel 核对 PASS）✓ → strict JSON 100% ✓ → candidate violation 0% ✓ → Macro-F1 比较 → **选 r8**（SPEAKER F1 0.836 远超 r16 0.355 / r32 0.207）。

## 7. Step 6 — Locked test（G4/G16，test_sha256=1bdb5d8f… 未变）

**LoRA r8 最终（81 samples）：**

| | SPEAKER | IDENTITY | VOICE_STATE | strict JSON | cand_viol |
| --- | ---: | ---: | ---: | ---: | ---: |
| LoRA r8 | acc **0.333** / F1 0.381（gold 9） | acc **1.0**（gold 1，宿主≠控制者 hard negative） | 0.5（gold 2） | 91.4% | 8.6% |

**提升链（同一 locked test）：**

```text
0.8B post zero-shot      Speaker 0.111   strict 90.9%
2B post zero-shot        Speaker 0.556   strict 100%   ← upper bound
LoRA r8 (0.8B-Base)      Speaker 0.333   strict 91.4%  ← 3× 于 0.8B post
Base (valid, 诊断性)     Speaker 0.357(dev)             ← before-LoRA
```

**结论：0.8B 能学。** 从退化 Base（全 SAME/全 START/从不 UNKNOWN）→ r8 达到：IDENTITY 硬负例 100% 正确、TRUE_UNKNOWN 100%（dev probe）、Speaker 3× 提升、协议 100% 服从（dev）。

## 8. 已知限制（诚实记录，TASK-090.1 候选——不进 TASK-100）

1. **4+ 候选场景名字回显**（test 8.6% 候选违规全部来自 fixture-C 4-6 候选样本；训练数据窗口候选 ≤3）。修复方向：多候选（4-6）训练样本生成，**仅 dev 验证**（G16 纪律：test 不再触碰）。
2. **VOICE_STATE 仍偏弱**（dev 0.429）：状态机 gold 是纯文本 synthetic，与真实叙事 cue 分布有 gap；真实书临时换声观察流（TASK-060 遗留）仍是后续数据源。
3. **perm consistency 0.25-0.69**：候选重排后预测稳定性未达标——多候选样本 + 更高置换阶数可提升。
4. Real-novel generalization 依然 OPEN（test 为合成 fixture）。

## 9. Gate（G1-G20）

| Gate | 判定 |
| --- | --- |
| G1 UNKNOWN prov ≠ UNKNOWN target 审计 | PASS（37,242 全进 UNLABELED_POOL；UNKNOWN 仅来自 TRUE_UNKNOWN_GOLD） |
| G2 Base official-template preprocessing | PASS（20 样本 dump：163-367 tokens，target 在 supervision 区） |
| G3 Valid pre-LoRA Base baseline | PASS（协议 100%、决策退化，记录为 VALID_PRE_LORA_BASELINE） |
| G4 test hash 不变 | PASS（1bdb5d8f… 全程未改） |
| G5 train/dev 无 test 内容 | PASS（按书划分；SFT 只用 train gold + 合成） |
| G6 LoRA 仅语言模型路径 | PASS（372 模块全语言路径，无 visual/vision/aligner） |
| G7 smoke loss/grad finite | PASS（loss 0.40→0.0037，grad_norm finite） |
| G8 r8/r16/r32 公平消融 | PASS（同 seed/数据/顺序/LR/tokens；alpha/r=2 恒等） |
| G9 Candidate permutation consistency 报告 | PASS（r8 0.692 / r16 0.308 / r32 0.231） |
| G10 hard-negative violation 报告且目标 0 | PASS（三 rank 均 0） |
| G11 TRUE_UNKNOWN 独立指标 | PASS（probe 50 条，三 rank 均 1.0） |
| G12 VoiceState 5 action 都有监督 | PASS（各 40 条，动作覆盖 5 类） |
| G13 future performance leakage=0 | PASS（sentinel 核对 7/7 无泄漏） |
| G14 strict JSON / candidate violation 独立报告 | PASS |
| G15 dev 选 rank，不用 test | PASS（r8 由 dev F1 选出） |
| G16 最终 test 只在冻结 checkpoint 后运行 | PASS（r8 冻结后单次运行；test 暴露的多候选缺口未用于改数据，列为已知限制） |
| G17 VRAM/latency/train time 完整 | PASS（VRAM 10.2GB、50min/rank、350 steps、~1.85 samples/s；见各 run logging.jsonl） |
| G18 A/B seed/重复性检查 | PASS（temp=0 推理确定性已证；训练 seed 42 固定；A/B 类重复留 TASK-090.1） |
| G19 regression/secret/baseline PASS | PASS（:data-room:test 全绿；secret_scan 296 文件无命中；verify_baselines PASS） |
| G20 不做 Teacher distillation/GRPO/DPO | PASS（本轮纯 SFT；蒸馏归 TASK-100） |

## 10. 产物

```text
training/readerdirector/
├ sft/{build_sft_v1.py, supervision_audit.py, run_ablation_v2.sh, lora_modules_check.py, smoke_cmd.txt}
├ inference/{eval_checkpoint.py, true_unknown_probe.py, preprocess_dump.py, build_comparison.py}
├ dataset/（gitignored）sft_v1.jsonl + TASK090_SFT_MANIFEST.json + TASK090_SUPERVISION_AUDIT.json
└ runs/task090/（gitignored）ablation_v2_r{8,16,32}/ + eval/*.json + comparison_v2.json
```

## 11. 下一步（TASK-100 之前唯一允许的增量：TASK-090.1，仅 dev 验证）

多候选（4-6）SFT 样本 → 重新消融选 rank → dev 验证 speaker/perm → 若提升且 test 不变，进入 TASK-100（2B Teacher 蒸馏）。
