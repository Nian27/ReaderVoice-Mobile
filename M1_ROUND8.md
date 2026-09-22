# M1 Round 8 — DAgger 生效（质变：前缀正确），但仍不足以长程稳定

日期：2026-09-16    状态：工具链正确；DAgger 迭代中

## 1. DAgger 训练完成（900 步 / 41 分钟，修正 cache + RAS 数据）

    step 899  [DAgger] loss=1.764  kl=0.441  cos_log=0.97239  cos_hid=0.96148
    DAgger cos_log 从 0.85 -> 0.97

## 2. 逐帧跟踪（同 seed + 同 RAS，与 teacher 对比）

                      first_div  match@20  match@50  match_all
    v2 (仅 teacher-forced)   4.2     0.28      0.12      0.03
    v2dagger (DAgger)        8.2     0.62      0.40      0.08
                            (2.0x)  (2.2x)    (3.3x)    (2.7x)

**DAgger 明确有效。**

## 3. 任务级 Gate（Whisper CER + t2s）

    MEAN CER[teacher] = 0.1758
    MEAN CER[v2]      = 1.3207
    MEAN CER[dagger]  = 1.2621

数字上只小幅改善，但**有质变**：

    TEACH : 周青山看着他眼底多了几分复杂…学校今年有一个贫困声特训推荐名额…   CER=0.105
    STU(v2): 其中一位同人接触了大征情,便得对接线,让刺激丹起冰从落…            CER=0.982  开头就错
    STU(dg): 周青山看着他眼里多了…                                        CER=0.842  开头对了
    STU(dg): 周清山皺了皺眉,你想清楚。但…                                  CER=0.818  开头对了

**student 现在前 5~10 个字是正确的，之后退化成重复/乱码。**

## 4. 判读

这**不是模型能力问题**（teacher-forced cos_logits=0.99983，说明它能拟合 teacher 分布），
而是推理时自身误差把状态推出分布，导致 long-horizon 崩溃。

这正是 on-policy 蒸馏要解决的问题，方向正确，只是迭代量不够。

## 5. 进行中（加强版 DAgger）

    steps 900 -> 3000
    maxroll 48 -> 64        （覆盖更长的失败区间）
    teacher_forced_prob 0.25 -> 0.1   （更多 on-policy）
    lr 1e-4 -> 8e-5   warm-start from v2dagger

## 6. 若仍不足的后续手段

1. 把 RAS 一致性写入训练目标（当前 loss 未直接约束 RAS 双抽样的行为）
2. 序列级目标（beam/对比）而非逐帧 KL
3. 训练时对输入 embedding 加噪（noisy teacher forcing）提升鲁棒性
4. 更长 rollout（96/128）—— 代价是每步成本翻倍

## 7. 重要提醒

本轮所有结论都建立在 **RAS 修复后**的工具链上：
    rollout_slow(teacher) 必须复现 M0v2 存储数据（已验证 64/64 逐帧一致）
这是评测可信的前提。
