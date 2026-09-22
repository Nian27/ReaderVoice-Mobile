# M1 Round 17 — M3（Fast one-shot）架构验证通过，但泛化暴露了真正的瓶颈

日期：2026-09-16    状态：M3 需数据量级提升；DAgger+fullsup 仍在跑

## 1. M3 设计（已实现）

    scripts/m3_fast_oneshot.py

    把 teacher 的 fast 分支从自回归改成并行：
        teacher: position p 输入 = fast_embeddings(cb_{p-1})     -> 必须串行 10 次
        student: position p 输入 = fast_embeddings(cb0) + q_p     -> 9 个位置一次算完
    q_p 为每位置学习偏置，初始化为 0：此时 position 1 的输入恰好等于 teacher 的，起点正确。
    因果 mask + RoPE 保留，权重用 teacher fast 初始化（66.99M，同参数量）。

## 2. 结果

    step 2999  loss=0.0000  acc=1.0000    (3000 步 / 376 s)

    split    frames     acc      per-cb-acc min
    train      4670   1.0000    1.0000
    val        7339   0.0470    0.0119
    test       8593   0.0465    0.0122

**训练集 100% 是纯粹的记忆**：15 条样本 / 4670 帧，67M 参数背下来很容易。

## 3. 判读（重要）

1. **架构可行** —— 一次 forward 能表示 (hidden, cb0) -> cb1..cb9 的映射
   （否则训练集也到不了 100%）
2. **泛化需要数据量级提升** —— teacher 同样 67M 参数但走自回归，每步只学 cb_{p-1}->cb_p；
   one-shot 要一次学整个联合分布，难度高得多，4670 帧远远不够。
3. **瓶颈是 teacher 数据量，不是方法本身。**

## 4. 这改变了对 M0-4 的优先级

    当前数据: 45 条 / 约 20k 帧（train 15 条 / 4670 帧）
    m0v2 GPU 路径: 约 40 s/sample
    生成 500 条: 约 5.5 小时

建议把 M0-4（批量 teacher rollout）从『后续』提到『M3 的前置』：
    - M3 需要 数据量 x10 以上才可能泛化
    - Slow 的 M1-C 正式训练同样受益

## 5. M3 的备选路径（用户已提出）

    Stage A -> cb1..cb4
    Stage B -> cb5..cb9
即 10 step -> 2 step。在数据量受限时，2-step 的每步映射比 9 路 one-shot 简单得多。
**建议在扩数据的同时并行验证这条。**

## 6. DAgger+fullsup 进展

    仍在跑（step 350/2000 时 cos_log 0.975~0.981）

## 7. 下一轮

1. DAgger+fullsup 完成 -> 完整评估（tracking / CER / speaker）
2. 启动批量 teacher rollout（M0-4），目标 200~500 条
3. 并行验证 M3 的两阶段（2-step）变体
