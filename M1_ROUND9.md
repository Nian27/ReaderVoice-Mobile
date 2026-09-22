# M1 Round 9 — 修掉 DAgger 训练 rollout 的 RAS 缺失（第三个同类 bug）

日期：2026-09-16    状态：加强版 DAgger 重跑中

## 1. 发现

`m1b_dagger.py` 里自实现的 `rollout()` 与 `rollout_slow` 有**同一个 bug**：

    sem_i = sampler(logits[0,-1][LOGIT_IDX].float())
    sem = SB_ID + sem_i          # 缺 RAS；EOS(4096) 时产生非法 token 155774

含义：**DAgger 的 on-policy rollout 用的采样分布与推理端不一致。**
训练时见到的 prefix 分布 != 部署时遇到的 prefix 分布 —— 这直接削弱了 on-policy 蒸馏的意义。

## 2. 修复

    sl  = logits[0,-1][LOGIT_IDX].float()
    nrm = int(LOGIT_IDX[sampler(sl, 0.7, 0.9, 50)])
    hgh = int(LOGIT_IDX[sampler(sl, 1.0, 0.9, 50)])
    sem = hgh if (SB_ID <= nrm <= SE_ID and nrm in prev) else nrm
    if sem == EOS: break
    prev.append(sem); prev = prev[-10:]

并把 sampler 改成接受温度参数（RAS 每帧抽两次，共用同一 RNG 流）。

修复后 smoke：step 0 的 DAgger cos_log 就是 0.945（坏版本起步约 0.85）。

## 3. 这是同一类 bug 的第三次出现

| 位置 | 症状 |
|---|---|
| R7 `rollout_slow`（评测） | 缺 RAS -> teacher 自己都 collapse |
| R9 `m1b_dagger.rollout`（训练） | 缺 RAS -> on-policy 分布与推理不一致 |

根因：**采样逻辑被复制到多个文件，官方 `_sample_semantic` 的 RAS 契约没有单点实现。**

## 4. 纪律建议（应写入代码结构，而非文档）

- 把「官方采样器 + RAS + EOS」做成**唯一实现**（如 `m1_sampling.py`），
  所有 rollout（评测 / 训练 / 数据生成）都调用它，禁止各自复制。
- 任何 rollout 在投入使用前，必须先通过「teacher 复现存储数据」对照。

## 5. 进行中

    steps 3000 / maxroll 64 / teacher_forced_prob 0.1 / lr 8e-5
    warm-start from v2dagger，RAS 修正后的 rollout
    预计 ~2.3 小时

## 6. 下一轮

1. 完成后重测 tracking + CER
2. **抽取公共采样模块**（第 4 节），消除复制粘贴型 bug
3. 若仍不足，考虑把 RAS 一致性写入 loss，或加噪 teacher forcing
