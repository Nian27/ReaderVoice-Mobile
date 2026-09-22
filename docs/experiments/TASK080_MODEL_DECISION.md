# TASK080_MODEL_DECISION — 训练策略判定（§38/§44）

**日期：2026-08-12** | 依据：TASK080_MODEL_BASELINE.md（同一 locked test，NON_THINKING/temp=0）

## 1. 实测

| System | Speaker Acc（gold=9） | 协议（Strict/Cand） | Identity hard-neg |
| --- | ---: | ---: | --- |
| Rule-only | 1.00（coverage 0.818） | 100% | 0 违规 |
| 0.8B post zero-shot | 0.111 | 90.9% / 90.9% | UNKNOWN（保守） |
| 2B post zero-shot | 0.556 | 100% / 100% | DIFFERENT ✓ |
| 0.8B Base probe | — | 0% / 0% | —（不产生 JSON） |

## 2. 判定：情况 B SIGNAL（2B 明显 > 0.8B，+44.4pp）

**口径修正（TASK-090 §0）：这是 CAPACITY_SIGNAL，不是已证明的容量问题。**
Fisher exact 双侧 p=0.131（1/9 vs 5/9）；Wilson 95% 区间 [0.02,0.435] vs [0.267,0.811] 明显重叠——gold 仅 9 条，统计上不能排除偶然。
结论不因此改变，仅科学表述降级。主线不变：

```text
STUDENT              = Qwen3.5-0.8B-Base（TASK-090 LoRA；官方定位：纯预训练 + chat control tokens）
UPPER BOUND / TEACHER = Qwen3.5-2B（蒸馏标签源）
TRAINING             = TASK-090 0.8B-Base LoRA（BF16，rank 8/16/32 消融）
```

反证未出现（Base probe 的 0% 协议正确率正是"必须 LoRA"的证据，而非"换模型"的证据）。

## 3. LoRA 前的已知修正点（全部留给 TASK-090，不做 TASK-080.1）

1. **近因偏差**：0.8B 6/6、2B 2/4 在"显式 cue ≠ 候选榜首"时输给候选顺序 → SFT 需大量 cue≠榜首 负采样 + 候选顺序随机化。
2. **后置 cue 盲区**：训练集已含 POSTPOSED_SPEECH_CUE gold → 直接可学。
3. **UNKNOWN 缺失**：两模型 `unknown_on_ungolded=0.0` → SFT 混入 UNKNOWN 样本（TURN_TRACKING/无 cue 段，provenance=UNKNOWN 的样本就是素材）。
4. **裸名回显**（0.8B 1 例）→ 协议约束在 prompt 中强化。
5. VOICE_STATE gold 稀缺（fixture 仅 7+2）→ TASK-090 数据工程：真实书临时换声观察流（TASK-060 遗留）需补，否则 VoiceState 任务无训练信号。
6. Dev set 用于 prompt v2 实验（§16：test 禁止调参；prompt 改版 = 新 hash 版本）。

## 4. 冻结延续

- MODEL ENTRY FREEZE 维持：TASK-080 → TASK-090 之间不再插入前置任务（PERF-030/040、Room、20-30 真实书仍 OPEN 不阻塞）。
- 数据/协议冻结：test_sha256=1bdb5d8f…；prompt *_v1 hash 见 manifests/prompts.json；模型 revision 见 manifests/models.json。
