# TASK-080 MODEL BASELINE — 三线零样本对比（Rule / 0.8B / 2B + Base probe）

**状态：PASS（G1-G18，见文末）** | 2026-08-12
环境：`training/envs/task080/`（python 3.12 / torch 2.11.0+cu128 / transformers 5.12.1 / ms-swift 4.4.2 / qwen-vl-utils 0.0.14 / peft 0.19.1）
GPU：RTX 4060 Ti 16GB（DESKTOP_MODEL_BASELINE；手机性能≠此表）
模型（revision 锁定，`manifests/models.json`）：
`Qwen/Qwen3.5-0.8B` @ 2fc06364715b967f1860aea9cf38778875588b17
`Qwen/Qwen3.5-2B` @ 15852e8c16360a2fea060d615a32b45270f8a8fc
`Qwen/Qwen3.5-0.8B-Base` @ dc7cdfe2ee4154fa7e30f5b51ca41bfa40174e68
（huggingface.co 被墙 → HF_ENDPOINT=https://hf-mirror.com；snapshot_download 元数据抖动 → resolve-cache URL 逐文件补齐）

## 0. 模型链（ADR-034 修正后）

```text
A  Rule-only baseline          ← 同一 locked test
B  Qwen3.5-0.8B (post) zero-shot
C  Qwen3.5-2B (post) zero-shot upper bound
D  Qwen3.5-0.8B-Base protocol probe（100 samples，不进能力榜）
```

## 1. Locked Test（G1）

`training/readerdirector/dataset/test.jsonl`（81 samples）**test_sha256 冻结**：
`1bdb5d8fe570c7ca841f557826b01cf30f06f111e42d0eec3177245fef251ba6`
fixture-C（4 人巷战场景：显式 cue / 后置 cue / 交替 / 代词 / 无 cue 惨叫 / CONTROL 硬负例 / 临时换声 START）。
train=38,209（真实书全量）/ dev=161（fixture A+B）。全部按书划分（§64）。
provenance：EXPLICIT_RULE_GOLD 1,195 / UNKNOWN 37,256。真实书→train；dev/test=合成 fixture（版权文本不入库）。

**工程两层声明（§17）**：Engineering Model Baseline = VALID；Real-novel Generalization = OPEN。
gold 子集极小（SPEAKER 9 / IDENTITY 1 / VOICE_STATE 2）——本表是协议/管线信号，不是能力结论。

## 2. 比较表（§37 主表，全部同一 test set / 同一 prompt v1 / NON_THINKING / temp=0 / max_new_tokens=32）

| System | Speaker Acc | Speaker Macro-F1 | Candidate Violation | Identity Acc | False Merge | VoiceState Acc | Strict JSON | UNKNOWN recall* | Latency (med) | VRAM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Rule-only | **1.00** | 1.00 | 0 | 1.00 | 0 | 1.00 | 100% | — | — | — |
| 0.8B post | 0.111 | 0.056 | 9.1% | 0.0 (0/1) | 0 | 0.500 | 90.9% | 0.0 | 992ms | 1.49GB |
| 2B post | **0.556** | 0.500 | **0%** | **1.0 (1/1)** | 0 | 0.500 | **100%** | 0.0 | 1341ms | 3.65GB |
| 0.8B Base probe | — | — | — | — | — | — | **0%**（INVALID_TEMPLATE_PROBE）| — | 2980ms | 1.46GB |

\* UNKNOWN recall：gold=UNKNOWN 样本数为 0（rule gold 只在显式 cue 上），改报 `unknown_on_ungolded`（无 gold 样本上模型说 UNKNOWN 的比例）：两模型均 0.0——模型从不示弱，全部强猜。

**判定（§38）**：2B 明显 > 0.8B（Speaker +44.4pp）→ **情况 B SIGNAL**（TASK-090 口径修正：Fisher 双侧 p=0.131、Wilson 区间重叠——CAPACITY_SIGNAL 而非已证明；gold 仅 9 条）；
主线不变——TASK-090 先 LoRA `0.8B-Base`，`2B = Teacher/Upper Bound` 供蒸馏。
Rule=100% 属预期（§38 情况 C：easy explicit 规则本来就该赢）；模型价值应在 LoRA 后的 Hard 子集体现。

## 3. 协议系统指标（§29，test 全量）

| 指标 | 0.8B | 2B |
| --- | ---: | ---: |
| Strict JSON Valid | 90.9% | 100% |
| Enum Valid | 100% | 100% |
| Candidate Valid | 90.9% | 100% |
| Extra Text Rate | 9.1% | 0% |
| Loop Rate | 0 | 0 |
| EOS Success | 100% | 100% |
| 确定性（A/B raw hash 一致） | ✓ f0064683… | ✓ 1be9116b… |

注：早期一次 A/B 输出 hash 不一致是**数据集在两次 run 之间重新生成**（prompt 候选渲染修复 + 惨叫误判修复）所致；同数据集下两模型均确定（§32 记录在案）。

## 4. 性能（§30/§31，DESKTOP_MODEL_BASELINE）

| | load | prompt tok/s | gen tok/s | med latency | p95 | peak VRAM | avg in/out tokens |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0.8B | 2.2s | 112 | 9.1 | 992ms | 3297ms | 1.49GB | 165 / 13 |
| 2B | 3.5s | 105 | 7.7 | 1341ms | 2814ms | 3.65GB | 165 / 12 |
| Base | 2.2s | 1.0 | 10.7 | 2980ms | 3271ms | 1.46GB | 3 / 32 |

Base 的 chat template 不生效（input_tokens=3，未吃到正常 ReaderDirector prompt）→ 输出为重复 token。**此 0% 记为 INVALID_TEMPLATE_PROBE（有价值的 pipeline failure，保留不删除）；TASK-090 Step 0 必须用官方模板正确编码（input_tokens 应 100~300）后重跑 Valid pre-LoRA Base baseline。**

## 5. Future Leakage Sentinel（G12）

VOICE_STATE 输入 `current_state` = position **之前**（position-1）的因果状态（G8 已在 TASK-070 数据层验证；样本级抽查：张三 START 样本的 current_state 不含 temp，预测对 = START）。数据集级：`grep temp=` 校验 current_state 不含目标位事件 → **leakage=0**（见 Gate 表）。Identity 侧 whole-book 允许（G9，fixture 别名对 SAME gold 成立）。

## 6. 错误分桶（详见两份 ERROR_ANALYSIS）

- **0.8B**：近因偏差主导（几乎总选 distance=1 候选）；1 例名字回显违规；对显式 cue 的服从率低（9 gold 中 1 对）。
- **2B**：协议 100% 干净；5/9 对；4 错中 3 个近因偏差（孙三娘笑道→钱二、赵铁柱说→周老板、控制者→宿主）、1 个后置 cue 忽略（钱二答道）。2B 对 "cue 与候选榜首冲突" 时选候选榜首而非 cue。
- **共同**：UNKNOWN 从不输出（强猜倾向）；后置 cue 是两者盲区；候选顺序（distance）对结果影响大于文本证据。

## 7. Gate（G1-G18）

| Gate | 判定 |
| --- | --- |
| G1 locked test hash frozen | PASS（1bdb5d8f…，manifests/dataset.json） |
| G2 prompt hashes frozen | PASS（prompts.json：speaker/identity/voice_state _v1，内容 sha256） |
| G3 rule baseline rerun reproducible | PASS（metrics.json：agreement 1.0 / coverage 0.818 / strict 1.0） |
| G4 0.8B post inference | PASS（Run A/B，A/B hash 一致） |
| G5 2B post inference | PASS（Run A/B，A/B hash 一致） |
| G6 Base protocol probe | PASS（dev 100 samples，JSON valid 0%，EOS 0%，avg 32 tok） |
| G7 thinking disabled | PASS（enable_thinking=False 全部 run，template_ok=true） |
| G8 same context/prompt/test protocol | PASS（同一 runner/prompt v1/test.jsonl，仅 model 不同） |
| G9 strict vs repaired 分开 | PASS（metrics 分别报告；repairable=strict 在本数据上无差别） |
| G10 三任务指标完整 | PASS |
| G11 false-merge/hard-negative | PASS（identity hard_neg_violation：0.8B=0（答 UNKNOWN）、2B=0（答 DIFFERENT）） |
| G12 future leakage = 0 | PASS（VOICE_STATE current_state=position-1 因果，sentinel 样本核对无泄漏） |
| G13 latency/VRAM/tokens | PASS（perf.json ×3） |
| G14 两次确定性 run 对比 | PASS（同数据集 A/B raw hash 一致；差异原因已记录） |
| G15 错误分桶 | PASS（两份 ERROR_ANALYSIS） |
| G16 无 test 调参 | PASS（prompt v1 只修了候选渲染/惨叫守卫——在**冻结前**完成并重生成 dataset；冻结后 test 未再触碰；候选呈现修复属数据集生成修复而非测试集调参） |
| G17 无 LoRA | PASS（全程 inference only） |
| G18 secret/baseline/DB 回归 | PASS（secret_scan 无命中；:data-room:test 全套绿含 G1-G12） |

## 8. 产物

```text
training/readerdirector/
├ prompts/{speaker,identity,voice_state}_v1.txt
├ inference/{runner,parser,metrics,prompt_builder,perf_probe}.py
├ manifests/{models,dataset,prompts}.json
├ dataset/{train,dev,test}.jsonl + TASK080_DATASET_MANIFEST.json   (gitignored)
└ runs/task080/  rule_baseline/ qwen3.5-0.8b/{run_A,run_B,perf} qwen3.5-2b/... qwen3.5-0.8b-base/run_A + comparison.json   (gitignored)
training/envs/task080/{requirements-lock.txt, environment.txt}
```
