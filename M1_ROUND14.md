# M1 Round 14 — 把全序列监督补齐到 DAgger（两处同源修复）

日期：2026-09-16    状态：DAgger+全序列监督训练中

## 1. 又一个同源遗漏

R13 在 `m1b_distill_slow.py` 修了「只监督生成段」的问题，
但 **`m1b_dagger.py` 里是同一份逻辑的两个分支**（tf_mode / DAgger），
也都只监督 `slice(Tp-1, Tp-1+T2)`。

后果：即使以 fullsup 权重为起点，DAgger 也会因为 prompt 段没有梯度而把该行为逐渐破坏。

（这就是 R7/R9 的翻版：**同一份逻辑存在两处实现，修了一处漏了一处。**）

## 2. 修复

    tf_mode 分支  s_idx slice(Tp-1,Tp-1+T2) -> 全序列 slice(0, Tp+T2)
    DAgger 分支   同上
    CE 仍只在生成段 (ce_slice = slice(Tp-1, Tp-1+T2))，因 prompt 段含非 semantic token

## 3. smoke 验证

    修复前 DAgger 起步: step 0  cos_log = 0.841
    修复后 DAgger 起步: step 0  cos_log = 0.978      <- 起点显著更好

（起点更好是因为 fullsup 权重已经让 prompt 段正确，且该行为不再被 DAgger 破坏）

## 4. 进行中

    m1b_dagger --init student_12L_fullsup.pt
                --steps 2000 --maxroll 64 --tf_prob 0.1 --lr 6e-5
    预计 ~1.8 小时

## 5. 累计纪律（第 6 条）

| # | 纪律 | 来源 |
|---|---|---|
| 1 | 数据长度必须覆盖目标 | R3 截断 |
| 2 | 度量必须做等价归一（繁简） | R4 |
| 3 | 采样必须单点实现 + 可执行自检 | R7/R9 RAS |
| 4 | speaker gate 必须报四项 | R11 |
| 5 | 全序列平均指标必须按位置分解 | R13 |
| 6 | **同一份逻辑若存在多处实现，修复必须逐处核对** | R14 |

第 6 条与第 3 条互补：第 3 条要求把重复实现收敛成单点，
第 6 条是在收敛完成前，对已存在的重复实现做穷举核对。

## 6. 下一轮

1. DAgger+fullsup 完成后：tracking + G-content CER + speaker gate
2. 完成 SAMP 委托收敛（`m1b_dagger` 已完成；`m1b_freerun_metric` 仍保有实现）
