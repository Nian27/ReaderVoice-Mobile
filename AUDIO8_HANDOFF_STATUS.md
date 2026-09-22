# AUDIO8 蒸馏 — 状态交接（2026-09-16，R37）

## 一句话状态

**M1（12L Slow 蒸馏）尚未通过；M2/M3/M4 未开始。**
路线已在 R35 修正并写入 PLAN REV.4；当前正在跑修正后的关键实验。

---

## 1. 当前正在运行

    DAgger + 全序列监督（--init student_12L_big97.pt, 96 样本, 1200 步）
    step 500/1200   DAgger cos_log 0.970~0.988   ~4.2 s/步
    检查点: runs/audio8-m1/student_12L_big_dg.pt（完成后写入）

### 它跑完后立即要做的事

1. 用 val 集跑 free-running CER（**主判据**，不是 cos）：
       scripts/m1_gen_student.py --ckpt <dg.pt> --split val --limit 8 --out gen_valdg
       (改 render_flat_codes.py 的路径) -> render
       scripts/m1_pairs_val97.py -> scripts/m1_gate_content.py
   判据：G-content <= 0.226（teacher val 基线 0.2867）
2. speaker 四项: scripts/m1_gate_speaker_report.py

---

## 2. 有效资产

| 资产 | 路径 | 说明 |
|---|---|---|
| 数据（当前有效） | runs/audio8-m0big | n=132, digest 692de0c1… |
| split | runs/audio8-m0big/split_manifest.json | train(bookA) 96 / val(bookB) 10 / test(bookC) 26 |
| teacher WAV | runs/audio8-m0big/wav | 134 条，已渲染 |
| 当前最好 student | runs/audio8-m1/student_12L_big97.pt | val cos 0.807（但 CER 0.97，不可用） |
| Student 初始化 | runs/audio8-m1/student_{12,8,6,4}L.safetensors | 层映射已验证 |
| 采样唯一实现 | scripts/m1_sampling.py + _selftest + _crosscheck | 均已 PASS |
| 位置分解工具 | scripts/m1_pos_profile.py | 定位 R13 根因的工具 |
| 分布同质性检查 | scripts/m1_dist_shift_check.py | |
| 大批量生成 | scripts/m0v2_gpu_rollout.py + m0big_*.py | 约 4~5 条/分钟 |

---

## 3. 实验结论汇总（含失败路线）

### 成立

| 结论 | 证据 |
|---|---|
| 设备侧 pipeline 正确 | P0_CLOSURE：修 cache reset 后与 bug 前逐位一致 |
| 数据量抬升 val 平台 | 四点单调：14/40/60/96 条 -> 0.680/0.742/0.772/0.807 |
| 全序列监督修复 prompt 段 | prompt cos 0.53 -> 0.99997（val 0.984 泛化） |
| DAgger 改善 free-running | R8：first_div 4.2->8.2, match@50 0.12->0.40 |
| 音色条件蒸馏后保留 | clone 0.951~0.958 vs 上界 0.971 |

### 不成立（失败路线，同等重要）

| 假设 | 证伪方式 |
|---|---|
| teacher-forced cos 可作为进度判据 | R35：cos 0.81~0.9997 区间对应 CER 都不可用 |
| M3 one-shot 可行 | 六变体全部 train~1.0 / val<=0.14 |
| M3 两阶段可行 | val accA 0.048 |
| 扩大生成并行度可提速 | 4 进程撞显存上限（15.9/16.4GB）完全停摆 |

---

## 4. 八条纪律（全部来自实测）

1. 数据长度必须覆盖目标（R3：64 帧只覆盖 12.2% 文本）
2. 度量必须做等价归一（R4：繁简转换使 CER 0.61->0.36）
3. 采样必须单点实现 + 可执行自检（R7/R9：缺 RAS 使评测与训练双双失真）
4. speaker gate 必须报 clone/ref->stu/ref->tea/separation 四项（R11）
5. 全序列平均指标必须按位置分解（R13：0.99983 掩盖了 prompt 段 0.53）
6. 同一逻辑多处实现时修复必须逐处核对（R14）
7. 训练结果必须在 val/test 上报告（R22：8 轮把记忆当学习）
8. **代理指标的适用范围必须实测**（R35：14 轮把 cos 当目标，而它与质量无关）

---

## 5. 明确的下一步（按优先级）

1. **等 DAgger 跑完 -> 测 val free-running CER**（唯一未答的关键问题）
2. 若 CER <= 0.226：M1-B 通过 -> 进 M2（8L/6L/4L）与 M3（Fast 减层，不是 one-shot）
3. 若 CER 仍高：按 R8 经验加大 on-policy 比例（tf_prob 0.1 -> 0）与 rollout 长度
4. **并行恢复数据生成**：bookA 还有 ~150 条可用，把 train 推向 250
5. M3 已定框为 **Fast 减层蒸馏（保持 AR）**：目标 2L(0.67s) / 1L(0.33s)，
   脚本 scripts/m3_fast_ar_distill.py 已就绪（data 已具备，无需额外 teacher）

---

## 6. 性能预算（未变，来自 F4 修正后的实测）

    Prefill<=0.30  Slow<=0.80  Fast<=0.55  Codec<=0.90  Host<=0.15  Total<=2.70  RTF<=0.91
    Slow 必须 ~12.5 ms/frame；Fast 唯一杠杆是减层（unroll 不省算力）

## 7. 环境

    训练 venv: E:/AndroidStudioProjects/ReaderVoiceMobile/training/envs/qwen3tts/.venv
               torch 2.11.0+cu128  RTX 4060 Ti 16GB
    系统 python310: onnxruntime 1.23.2（跑 codec/render/CER）
    whisper: E:/AndroidStudioProjects/cosyvoice3-distill-lab/whisper/base.pt
    WavLM speaker: E:/AndroidStudioProjects/audio8tts-mnn/assets/local/wavlm-base-plus-sv
---

# ★ 重大更新（R39/R40）—— 瓶颈是容量，M1 前提被推翻

## A. 决定性结果

在**完全相同的数据（96 条）与流程**下，只改 student 层数：

| student | val gen_cos | val argmax | **val free-running CER** |
|---|---|---|---|
| 12L (+DAgger) | 0.807 | 0.153 | **0.9817** ❌ 不可懂 |
| 20L | 0.974 | 0.567 | **0.4116** ✅ 大幅改善 |
| teacher 24L | 1.000 | — | 0.2867 |

20L 训练曲线（2000 步，val 仍在上升）：
    step    0  gen_cos=0.75812  argmax=0.2072
    step  800  gen_cos=0.96506  argmax=0.4886
    step 1600  gen_cos=0.97432  argmax=0.5672

## B. 这推翻了什么

1. **PLAN REV.2 的 M1 前提**（24→12 后内容能保持）**不成立**。
2. **M2 的 8L/6L/4L 计划需重做** —— 12L 都不够，更小的不可行。
3. **R21—R38 共 18 轮把 12L 的失败归因于数据量 / 训练方法，实际是容量。**
   数据量和全序列监督确实各自有效（val cos 0.68->0.807），但都不足以跨过容量门槛。

## C. 阈值定义修正（我做错的一处）

PLAN REV.3 写的 `G-content <= teacher + 0.05` 用了 **train 集的 teacher CER（0.1758）**
-> 阈值 0.226，**低于 teacher 自己在 val 上的 0.2867**。

正确：`teacher_val + 0.05 = 0.2867 + 0.05 = 0.337`

按修正阈值：12L 0.9817 ❌ ；20L 0.4116 接近但未过。

## D. 性能预算需要重算（这是下一步的核心问题）

    Slow 预算 0.80 s / 62 帧 = 12.5 ms/frame
    按 F4 修正后的热态单价 ~0.65 ms/层：
        20L: 62 x 20 x 0.65 = 0.81 s   <- 压线，无余量
        16L: 62 x 16 x 0.65 = 0.64 s   <- 有余量
        12L: 62 x 12 x 0.65 = 0.48 s   <- 但容量不够

=> **必须找到「刚好够用」的最小层数**，这决定整个蒸馏路线能否成立。

## E. 正在运行（R40 启动）

    16L student：训练 2500 步 + val 生成
    checkpoint: runs/audio8-m1/student_stu16L.pt
    layer_map: [0,2,3,5,6,8,9,11,12,14,15,17,18,20,21,23]  481.86M 参数

### 16L 跑完后要做的

1. 渲染 + CER：
       render_flat_codes.py（改路径为 gen_val16L / gen_val16L_wav）
       m1_pairs_val97.py（改路径）-> m1_gate_content.py
   判据：CER <= 0.337（修正阈值）
2. 同时对 20L 做 DAgger（R8 证明 DAgger 改善 free-running），看能否把 0.41 推向阈值内
3. 测 16L / 20L 的 speaker 四项（m1_gate_speaker_report.py）

## F. 待办优先级（更新）

1. 🔥 16L 判定 -> 确定最小够用层数（决定路线可行性）
2. 🔥 20L + DAgger -> 尝试跨过 CER 阈值
3. 恢复数据生成（bookA 还有约 150 条可用）
4. 用通过的层数重跑 M1-C（多书多 voice 正式训练）
5. M3 = Fast 减层蒸馏（保持 AR；one-shot 已证不可行）
6. M4 = W4 + QNN；注意 20L 的 Slow 预算很紧，W4 是必需项

## G. 一条新的纪律（第 9 条）

**容量与数据量是两个独立的约束，必须分别实验确定，不能只调其中一个。**
我在 R21—R38 共 18 轮里只调数据量与训练方法，从未变过 student 层数，
因此把容量不足误判为数据不足。**变量扫描应先做粗扫（层数 4/8/12/16/20），再在选定点上细调。**
## H. 容量曲线完整（R40 补充）

| student | val gen_cos | val argmax | **val CER** | Slow 预算 (0.65ms/层) |
|---|---|---|---|---|
| 12L | 0.807 | 0.153 | **0.9817** | 0.48 s（容量不够） |
| **16L** | **0.967** | **0.530** | **0.4662** | **0.64 s ✅ 有余量** |
| 20L | 0.974 | 0.567 | **0.4116** | 0.81 s ❌ 压线，须 W4 |
| teacher 24L | 1.000 | — | 0.2867 | — |
| 修正阈值 | | | 0.337 | |

**12L -> 16L 是质变，16L -> 20L 是量变。16L 已捕获大部分收益。**

### 结论：**16L + W4 是同时满足容量与 Slow 预算的组合**

### 剩余差距与最直接的下一步

    16L CER 0.4662 -> 阈值 0.337，还差 0.129
    R8 已证明 DAgger 改善 free-running（train match@50 0.12 -> 0.40）
    **但 16L/20L 上的 DAgger 都还没试过** <- 最直接的下一步

    命令：m1b_dagger.py --init student_stu16L.pt --limit 96 --maxroll 64 \
                          --steps 1200 --lr 5e-5 --teacher-forced-prob 0.1 --tag 16L_dg
    然后用同一套 val 流程测 CER

### 若 DAgger 仍不足以跨过阈值

- 尝试 18L（16 与 20 之间）
- 提高 on-policy 比例（tf_prob 0.1 -> 0）
- 加大 rollout 长度（64 -> 96/128）
- 扩大数据（bookA 还有约 150 条）

### 已确认的结论

- M1 的 12L 前提**不成立**（容量不足），M2 的 8/6/4L 计划作废
- 正确的 student family 应在 **16L~20L** 区间
- Fast 的杠杆是**减层**（unroll 不省算力）；M3 已定框为 Fast 减层蒸馏
---

# R41（继续轮）—— 发现并修复 DAgger 的层数推断 bug

## A. 症状与假结论

用 --init student_stu16L.pt 跑 DAgger 后：

    student_stu16L.pt   val gen_cos=0.96889  argmax=0.5363   val CER=0.4662
    student_16L_dg.pt   val gen_cos=0.73638  argmax=0.0907   val CER=1.5954

表面上「DAgger 让 16L 显著变差」。**这是假的。**

## B. 真因（m1b_dagger.py 的 bug）

    旧实现：student = M.StudentSlow(a.nlayer)      # a.nlayer 默认 12
    跑 --init student_stu16L.pt 时未传 --nlayer
    => 构建 12L 模型，用 strict=False 加载 16L 的 state_dict
    => 后 4 层被静默丢弃，训练的是「16L 的前 12 层」

佐证：该 run 的 DAgger step 0 cos_log=0.35（12L 正常起点是 0.93）；
      评估脚本把它标为 (12L)，因为它读 ckpt 的 args.nlayer，而 DAgger 存的是 DAgger 的 args。

## C. 修复

    层数改为从 checkpoint 推断：
        ck['args']['nlayer'] 优先，否则从 state_dict 的 layers.N 最大索引推断
    并改为严格加载（missing/unexpected 都断言为空），杜绝静默丢层。

## D. 影响范围

- R38 的 12L DAgger（CER 0.9817）：--init student_12L_big97.pt 且默认 nlayer=12，**有效**
- R40 的「16L DAgger」（CER 1.5954）：**无效，已作废**
- 正在重跑：student_16L_dg2.pt

## E. 纪律补充（第 6 条的具体化）

**从 checkpoint 加载模型时，架构参数必须从 checkpoint 推断，不能用调用方的默认值。**
strict=False 会把「架构不匹配」变成「静默丢参数」—— 最危险的一类 bug：
不报错，只让结果变差，从而被误读成「训练方法无效」。

这次是靠「teacher-forced 指标在 DAgger 前后的对比」发现的（0.969 -> 0.736），
而不是靠训练日志 —— 训练日志看起来完全正常。
## I. R41 最终结果（修复层数 bug 后）

| 配置 | val gen_cos | val argmax | **val CER** | Slow 成本 |
|---|---|---|---|---|
| teacher 24L | 1.000 | — | 0.2867 | — |
| **阈值** | | | **0.337** | |
| 12L | 0.807 | 0.153 | 0.9817 | 0.48 s |
| 16L | 0.9689 | 0.5363 | 0.4662 | 0.64 s |
| **16L + DAgger** | — | — | **0.4021** | **0.64 s ✅ 预算内** |
| 20L | 0.9757 | 0.5754 | 0.4116 | 0.81 s ❌ 压线 |

**16L + DAgger（0.4021）优于 20L 不加 DAgger（0.4116），且 Slow 成本在预算内。**

    DAgger 的净收益：0.4662 -> 0.4021（-0.064）
    距阈值 0.337 还差 0.065

## J. 明确的下一步（按预期收益排序）

1. **16L + 更强 DAgger**：提高 on-policy 比例（tf_prob 0.1 -> 0）、加长 rollout（64 -> 96/128）
2. **16L + 扩数据**：bookA 还有约 150 条可用，R32/R34 已证数据量抬升平台
3. **18L**：16L 与 20L 之间，可能在预算内拿到更好的容量
4. 三者可组合；判据始终是 **val CER <= 0.337**

## K. 有效的模型检查点

    student_16L_dg2.pt   val CER 0.4021   <- 当前最佳，且符合 Slow 预算
    student_stu20L.pt    val CER 0.4116
    student_stu16L.pt    val CER 0.4662   （DAgger 的起点）
    student_12L_big_dg.pt val CER 0.9817  （12L 的证明：容量不足）

## L. 复现 val CER 的完整命令

    # 1) 生成（split=val, limit=8）
    m1_gen_student.py --ckpt <ckpt> --out <gen_dir> --split val --limit 8
    # 2) 渲染（把 render_flat_codes.py 里的 'gen_v16dg2'/'gen_v16dg2_wav' 改成 <gen_dir>）
    render_flat_codes.py
    # 3) 配对（改 m1_pairs_val97.py 的路径）
    m1_pairs_val97.py
    # 4) CER
    m1_gate_content.py --pairs <pairs.json> --out <out.json>

    注意：teacher 的 val CER 恒为 0.2867（8 条样本），阈值 = 0.2867 + 0.05 = 0.337
---

# R42（继续轮）—— DAgger 已收敛；转投数据量

## A. DAgger 收敛的证据

从最佳检查点续跑 DAgger（更低 tf_prob、更低 lr）：

    student_16L_dg2 起点        DAgger cos_log = 0.99319
    16L_dg3 训练 400 步后        DAgger cos_log = 0.989 ~ 0.992

**没有任何一步超过起点。** 说明 DAgger 目标已经饱和。

这也解释了 R41 的结果：DAgger 第一次的 -0.064（0.4662 -> 0.4021）
是「从无到有」的收益，而不是「越多越好」。已终止 16L_dg3。

## B. 三个旋钮的当前状态

| 旋钮 | 状态 | 证据 |
|---|---|---|
| 容量 | 12L 不够 / 16L 起效 / 20L 略优 | 0.9817 / 0.4662 / 0.4116 |
| on-policy (DAgger) | **已饱和** | 精调无增益（本节 A） |
| 数据量 | **唯一仍有已验证收益的旋钮** | R32/R34：14/40/60/96 条 -> 0.680/0.742/0.772/0.807 |

## C. 正在进行：数据补齐

    目标：bookA 63 + bookB 40 + bookC 40 = 143 条（bookA 池子已接近耗尽）
    2 workers，约 3 条/分钟，预计 48 分钟
    完成后总计约 285 条（当前 156）

    **注意：bookA 的可用池约 167 条，已用 104。达到 ~167 后 train 集无法再扩，**
    届时若仍需更多数据，必须引入新书（会改变 book-level split 的构成）。

## D. 下一步

1. 数据补齐完成后：重建 split -> 用全部 bookA 重训 16L（全序列监督）
2. 在 16L 上做一次 DAgger（第一次的收益是确定的）
3. 测 val CER，判据 0.337
4. 若仍不足：**bookA 池已耗尽 -> 必须引入新书**（这是路线上限的关键约束）

## E. 当前最佳与基线（供对比）

    student_16L_dg2.pt   val CER 0.4021   <- 最佳（Slow 成本 0.64s，预算内）
    student_stu20L.pt    val CER 0.4116
    student_stu16L.pt    val CER 0.4662
    teacher              val CER 0.2867   （阈值 = 0.2867 + 0.05 = 0.337）
---

# R43 —— 用户收紧路线后的执行（split 冻结 / 16L 冻结 / 只扩 train）

## A. 用户的三条修正（已执行）

1. **不要重建 split**。val/test 是基准坐标系，必须冻结。
   发现的问题：我的 m0big_make_split.py 每次重建会让 val 随生成增长
   （测 0.4021 时 val=10 条，补齐后会变 40+）-> 基准不可比。
   **已改为 split_frozen.json**：val/test 锁定为产生 0.2867/0.4021 的那组确切样本。
2. **不要自动再跑 DAgger**。改为：先测 TF / free-run / CER 三数，
   仅当「supervised 明显改善但 free-run 仍落后」时才做 1 次 DAgger。
3. **16L 冻结为 Slow 主架构**。不再扫 14L/18L/20L/22L。

## B. 冻结后的 split（不可变）

    split_frozen.json
    val  = 8 条（bookB 前 8，永久冻结）
    test = 8 条（bookC 前 8，永久冻结）
    train = bookA（当前 123，上限约 167）
    holdout = bookB/bookC 的其余样本（不参与任何评估）

    当前 digest = eebd8054542fcde72a8dfc67a591f8238e64348587081850a1774895f048a50e（n=159）
    **digest 会随 train 增长而变，但 val/test 成员恒定**

## C. ★ 阻塞点：语料库只有 3 卷成稿，无法新增 train books

    E:/小说/山河镇狱/
        第一卷  30 章  -> bookA (train)
        第二卷  90 章  -> bookB (val, 冻结)
        第三卷  90 章  -> bookC (test, 冻结)
        第四~第九卷  0 章（未写）
        _废弃_第三卷旧稿  17 章

    其他位置未找到中文小说语料（fanqie_auto_publish 是发布工具，docs/ 是项目文档）。

    **需要用户决策：**
      (a) 从公开领域中文文学拉文本（如《三国演义》《红楼梦》）作为 train books
      (b) 用户提供另一部小说
      (c) 接受 train 上限 167 条（bookA 单本）

    **注意：即使拿到新书，也绝不动 val/test —— 新书只进 train。**

## D. 用户定义的下一步 Gate（三数判读）

    285 条（实际为 train 167 上限）完成后重训 plain 16L，只看三个数：
        A. teacher-forced CER
        B. free-run CER
        C. rollout logits cos

    判读：
        A 降 B 也降      -> 数据仍有效，继续扩书
        A 降 B 不降      -> exposure/state mismatch 又成瓶颈 -> 做 1 次 DAgger
        A/B 都不明显降   -> 当前数据分布饱和 -> 必须增加【独立书籍多样性】

    **关键区分：数据规模 vs 数据多样性。**
    167 条来自 1 本书 与 167 条来自 6 本书，对小说 TTS 泛化价值可能完全不同。

## E. 当前最佳（基线，用于对比）

    teacher        val CER 0.2867   目标上限 0.3367
    student_16L_dg2 val CER 0.4021   差距 0.0654
---

# R44 —— 数据规模饱和已确认；下一步必须换「多样性」

## A. A/B/C 三数（用户定义的 Gate）

| 配置 | A: TF gen_cos / argmax | B: free-run CER |
|---|---|---|
| 15 条 (12L) | 0.807 / 0.153 | 0.9817 |
| 96 条 (16L) | 0.9674 / 0.5304 | 0.4662 |
| **155 条 (16L)** | **0.9760 / 0.5761** | **0.4481** |
| 96 条 (16L+DAgger) | — | 0.4021 |
| 96 条 (20L) | 0.9757 / 0.5754 | 0.4116 |
| teacher | 1.000 | 0.2867（阈值 0.337） |

**+61% 数据只买来 A +0.009、B -0.018 -> 落在「数据分布饱和」档。**

### 判读（用户预设）

    A 降 B 也降    -> 数据仍有效，继续扩书
    A 降 B 不降    -> exposure/state mismatch -> 做 1 次 DAgger
    A/B 都不明显降 -> 分布饱和 -> 必须增加【独立书籍多样性】  <-- 当前

## B. 冻结 split 已生效（R43）

    split_frozen.json   digest 7263d24b956fde98
    train 155 / val 8 / test 8 / holdout 20
    train frames = 45263
    val/test 成员自 0.2867/0.4021 基线以来【未变】

## C. ★ 当前瓶颈：语料库只有 3 卷成稿

    E:/小说/山河镇狱/
        第一卷 30 章  -> bookA (train)  已榨到 155 条
        第二卷 90 章  -> bookB (val, 冻结)
        第三卷 90 章  -> bookC (test, 冻结)
        第四~第九卷   0 章（未写）
        _废弃_第三卷旧稿 17 章（同作者同文风，多样性有限）

    用户已选 (a)：拉公开领域中文文学作为新的 train books。

## D. 下一步（唯一尚未使用的高价值杠杆）

1. **引入独立书籍多样性**（新书只进 train，绝不动 val/test）
2. 建议文本源：不同文风/句长/对话密度的中文作品
   例如 三国演义（文言白话混合）、红楼梦（对话密集）、
        骆驼祥子/呐喊（现代白话，短句）
3. 每本 40~80 条，而非单本榨到几百条
4. 重训 plain 16L -> 测 A/B -> 若 A 降 B 也降 => 多样性有效

## E. 保留的判断

- 16L 仍是 Pareto 点（Slow 0.64s，预算内），**不因数据换源而改变**
- 16L + DAgger (0.4021) 仍是当前最佳 CER
- DAgger 在当前分布已饱和；**若新书改变了 state distribution，需重新评估其价值**
    （不是机械执行，而是新分布下重新测量）
---

# R45 —— 独立书籍多样性已引入（公有领域中文文学）

## A. 新 train books（从中文维基文库拉取，公有领域）

    bookD  三國演義   60 条
    bookE  紅樓夢     53 条
    bookF  老殘遊記   60 条
    合计 173 条，kind 均衡（narration 60 / mixed 60 / dialogue 53）

    脚本：scripts/m0big_fetch_newbooks.py（MediaWiki action=raw）
          scripts/m0big_merge_newbooks.py（合并 spans + 生成 jobs）
    spans 总数 625（原 452 + 新 173）

### 为什么这三本

    三國演義  文言白话混合，句长差异极大
    紅樓夢    对话极密集，多角色
    老殘遊記  清末白话，游记体，与网文差异大
    **共同点：与 bookA（现代网文）在文风/句长/对话密度上差异显著。**

## B. 关键约束（不可违反）

    bookD/E/F 只进 train；bookB(bookA? 不) -> 见下
    bookB = val  永久冻结（8 条，成员不可变）
    bookC = test 永久冻结（8 条，成员不可变）
    **这样 train books 数量 -> val CER 的学习曲线才可信，**
    **且 12L/16L/20L、DAgger、data scaling 的历史 CER 仍可直接比较。**

## C. 正在运行

    173 条 teacher rollout，2 workers，约 2.7 条/分钟 -> 约 64 分钟
    GPU 9.45 GB / 96%

## D. 重训计划（数据到齐后）

    train = bookA(155) + bookD/E/F(173) = 约 328 条
    val/test 保持冻结
    重训 plain 16L -> 测 A/B

    判读：
        A 降 B 也降  -> 多样性有效，继续加书
        A 降 B 不降  -> exposure/state mismatch -> 做 1 次 DAgger（新分布下重测其价值）
        A/B 都不降   -> 多样性也不是瓶颈，需重新定位

## E. 历史基线（继续有效，因 val/test 冻结）

    teacher               val CER 0.2867   （阈值 0.337）
    student_16L_dg2       val CER 0.4021   （96 条 + DAgger，单书）
    student_16L_data155   val CER 0.4481   （155 条，单书，plain）
---

# ★★ R46 —— 数据多样性假设被决定性验证（新最佳）

## A. 关键对照

| 配置 | 书数 | 条数 | A: TF gen_cos / argmax | **B: free-run CER** |
|---|---|---|---|---|
| 15 条 (12L) | 1 | 15 | 0.807 / 0.153 | 0.9817 |
| 96 条 (16L) | 1 | 96 | 0.9674 / 0.5304 | 0.4662 |
| 155 条 (16L) | 1 | 155 | 0.9760 / 0.5761 | 0.4481 |
| **216 条 (16L)** | **4** | **216** | **0.9808 / 0.6173** | **0.3949** ← 新最佳 |
| 96 条 (16L+DAgger) | 1 | 96 | — | 0.4021 |
| teacher | — | — | 1.000 | 0.2867（阈值 0.337） |

### 核心结论

    同一本书内 +59 条（96->155）      -> B 只降 0.018
    换 3 本新书 +61 条（155->216）    -> B 降 0.053   （近 3 倍）

**「来自几本书」比「共有多少条」更重要。** 数据多样性 > 数据规模。

注意：216 条版是 **plain（无 DAgger）**，已优于 96 条 + DAgger（0.4021）。

## B. 按用户的 A/B 判读表

    A 降 B 也降  -> 多样性有效，继续加书   <-- 当前命中这一档

## C. 新书来源（可复现）

    scripts/m0big_fetch_newbooks.py  从 zh.wikisource.org 拉取（action=raw）
        bookD 三國演義 / bookE 紅樓夢 / bookF 老殘遊記
    脚本可扩展：改 BOOKS 列表即可加书（如 儒林外史/官場現形記/聊齋誌異）

## D. 当前 split（冻结，不可变）

    split_frozen.json  digest 782e437cf8d1e5bf
    train 216 (bookA 155 + bookD/E/F 61)  /  val 8  /  test 8  /  holdout 20
    train frames = 71342
    **val/test 成员自 0.2867/0.4021 基线以来从未改变**

## E. 下一步（按预期收益）

1. **继续加书**（多样性已验证有效）：从 3 本加到 6~8 本
    候选：儒林外史 / 官場現形記 / 聊齋誌異 / 鏡花緣 / 二十年目睹之怪現狀
    每本 40~60 条
2. 补齐 bookD/E/F 剩余的 rollout（已生成 61/173）
3. 差距：0.3949 -> 0.337，还差 0.058
4. **DAgger 在新分布下的价值需要重测**（旧分布已饱和，但分布已变）

## F. 有效检查点

    student_16L_books4.pt   B=0.3949  <- 当前最佳（plain，4 本书）
    student_16L_dg2.pt      B=0.4021  （96 条 + DAgger）
    student_16L_data155.pt  B=0.4481  （155 条，1 本书）
---

# ★★ R47 —— 蒸馏算法改进：逐层 hidden 对齐（距阈值 0.012）

## A. 结果

| 配置 | 书数 | A: gen_cos / argmax | **B: free-run CER** |
|---|---|---|---|
| 155 条 (16L) | 1 | 0.9760 / 0.5761 | 0.4481 |
| 216 条 (16L) | 4 | 0.9808 / 0.6173 | 0.3949 |
| **216 条 (16L) + 逐层对齐** | **4** | **0.9840 / 0.6448** | **0.3493** |
| teacher | — | 1.000 | 0.2867（阈值 0.337） |

**逐层对齐 -0.046，与加书 -0.053 幅度相当，且只需 8 分钟训练。**

## B. 改了什么

原 loss 只对齐 student 第 16 层（最后一层）的 hidden。
24->16 层映射 [0,2,3,5,6,8,9,11,12,14,15,17,18,20,21,23] 下，
**student 前 15 层完全没有直接监督**，只能靠最后一层的梯度间接学。

新增（m1b_distill_slow.py，--w-layer，默认 0）：

    StudentSlow.forward(..., return_hidden=True) -> (logits, n, hids[])，hids[i] 为第 i 层输出
    StudentSlow.layer_map = 与 m1a_build_students.py 一致的层映射
    loss += w_layer * mean_i MSE(student.hids[i], teacher.hids[layer_map[i]])

本次用 --w-layer 0.5。

## C. 三个旋钮的最新贡献（同一冻结 val/test）

    容量      12L -> 16L          B 0.9817 -> 0.4662   (-0.516)
    算法      仅末层 -> 逐层对齐   B 0.3949 -> 0.3493   (-0.046)  <- 本轮
    多样性    1 本书 -> 4 本书     B 0.4481 -> 0.3949   (-0.053)
    规模      96 -> 155 条(同书)   B 0.4662 -> 0.4481   (-0.018)
    on-policy DAgger(旧分布)      B 0.4662 -> 0.4021   (-0.064)

**算法与多样性同量级，规模最弱。**

## D. 距目标只差 0.012

    student_16L_layer.pt   B = 0.3493
    阈值                     B = 0.3370

## E. 下一步（算法侧仍有空间，按代价排序）

1. **w_layer 调参**（0.5 -> 1.0 / 2.0）—— 最便宜，单次 8 分钟
2. **逐层 loss 换 cosine 或归一化 MSE** —— 各层 hidden 尺度不同，纯 MSE 可能被大尺度层主导
3. **同时对齐 attention 输出 / 关系矩阵**（relation distillation）
4. 更长训练 / 学习率调度
5. **DAgger 在新分布下重测**（旧分布饱和，但现在是算法+数据双更新后的新分布）

## F. 有效检查点

    student_16L_layer.pt    B=0.3493  <- 当前最佳
    student_16L_books4.pt   B=0.3949
    student_16L_dg2.pt      B=0.4021
---

# ★★★ R48 —— 关键方法学发现：CER 的测量精度不足（追的是噪声）

## A. 测量

在同一批【冻结 8 条 val】上，用同一 `prompt.npy` 参考条件，只换 teacher / student：

| 系统 | mean CER | median | sd | min | max |
|---|---|---|---|---|---|
| BF16 torch teacher（本地 replica） | 0.2867 | 0.1905 | 0.2031 | 0.0676 | 0.6129 |
| 官方 FP16 ONNX teacher | 0.3824 | 0.2955 | 0.2727 | 0.1169 | 0.9357 |
| student 16L + 逐层对齐 | 0.3493 | 0.2893 | 0.1531 | 0.2000 | 0.6022 |

逐样本互有胜负：bf16 在 ch036/ch102 明显好，fp16 在 ch033/ch039 明显好。

## B. 统计读法（这是本轮真正的结论）

    sd ≈ 0.20 ~ 0.27，n = 8  ->  标准误 ≈ 0.07 ~ 0.09

    bf16 vs fp16 均值差 0.096  ->  不显著
    student vs bf16 差 0.063   ->  不显著（t ≈ -0.7）

**在 n=8 的冻结 val 上，CER 的标准差约 0.2，而目标差距是 0.012。**
**=> 我之前一直在追噪声。**

阈值 0.337 = BF16 teacher 的 8 次随机 realization 的均值 + 0.05，
而该均值本身标准误 ≈ 0.072。**阈值自身的不确定性远大于待判差距。**

## C. 推论

1. **不能断言 BF16 或 FP16 teacher 谁更好** —— 差异在噪声内
2. **不能断言 student 已达标或未达标** —— 同样在噪声内
3. **也不能据此否证「逐层对齐有效」**（0.046 的改善同样小于噪声，
   但它是**同 realization 下的配对比较**，配对设计能消掉大部分样本间方差）

## D. 必须做的修复（优先级高于任何调参）

1. **每样本多种子重复**（如 3 seeds × 8 样本 = 24 次测量）
   把 realization 噪声从 sd/sqrt(n) 降下来
2. **改用配对比较**：所有候选模型必须用**同一个 seed 序列**跑同一批样本
   这样样本间方差被消掉，只剩模型差 —— 这是唯一能分辨 0.012 级差距的方法
3. val 冻结不变（成员仍为那 8 条），只是**每条的测量次数**增加
4. 报告时给 **mean ± sem**，不只给 mean

## E. 已确立的事实（不受本轮影响）

- 12L 容量不足（0.98 vs 0.47，差距巨大，远超噪声）
- 数据多样性有效（1 本书 vs 4 本书，但同样需按 D 重测确认）
- token-ID 契约 / student 自己 prefill / 全序列监督 / 逐层映射 / 共享冻结 —— 均有实验支持

## F. 新增脚本

    m0big_val_fp16_jobs.py     生成冻结 val 的 jobs
    m1_pairs_teachercmp.py     同参考条件下多 teacher 对比配对
    m0_rollout.py              增加 M0IN / M0VOX 环境变量覆盖（原来硬编码）
    m1_gate_content.py         报告 bf16 / fp16 / student 三种 tag
---

# ★★★★ R49 —— 配对测量推翻多个结论：必须改为多种子 + mean±sem

## A. 决定性对照

    配对（同一 seed 777，n=6）:
        bf16    0.3297
        layer   0.3794
        books4  0.3778      <- 差 0.0016（噪声）

    非配对（默认 seed 20260914，n=8）:
        layer   0.3493
        books4  0.3949      <- 差 0.046（我据此宣称「逐层对齐有效」）

**换种子后结论翻转。**

## B. 为什么 seed 配对不成立

模型一旦在第 3 帧分叉，后续随机序列走上完全不同轨迹。
seed 只控制了第一帧的噪声，控制不了整条 rollout。
=> **必须靠【多次独立 rollout 求均值】降噪，而不是靠对齐随机数**

## C. 结论可靠性重估

| 结论 | 观测差异 | 判定 |
|---|---|---|
| 12L 容量不足 | 0.516 | ✅ 远超噪声，稳 |
| 逐层对齐有效 | 0.046 | ❌ 换 seed 后消失，**撤回** |
| 数据多样性有效 | 0.053 | ⚠️ 未在最严条件下验证 |
| DAgger 有效 | 0.064 | ⚠️ 同上 |
| 距阈值 0.012 | 0.012 | ❌ 远小于噪声 |
| BF16 vs FP16 teacher | 0.096 | ❌ 不显著（sd 0.2~0.27） |

**只有 12L 那一条是稳的。**

## D. 唯一严谨的测量方案（优先级最高）

    val 成员【冻结不变】（8 条，不破坏历史可比性）
    但每条样本跑 N 个独立 seed：
        8 样本 x 8 seeds = 64 次测量  ->  sem ≈ sd/sqrt(64) ≈ 0.025
    报告 **mean ± sem** + 配对 t 检验（同一 (sample, seed) 组合下比较模型）

    **配对必须按 (sample, seed) 单元配对，不是只按 sample。**
    这样每个单元内两个模型的随机序列起点相同，且单元内差值只含模型差。

## E. 新增/修复的脚本

    render_flat_dir.py   参数化渲染扁平目录的 <name>.codes.npy（自动处理 [T,11] -> 10 列）
    m0_rollout.py        增加 M0IN / M0VOX 环境变量覆盖
    m1_gen_student.py    读 split_frozen.json
    m1_pairs_paired.py   按交集构造配对比较
    m1_gate_content.py   报告 tag: teacher/bf16/fp16/student/layer/books4

    **教训：不要用 sed -replace 改脚本（我的正则误伤了 ONNX 输入名 'codes'，
    导致渲染静默失败）。参数化脚本或写新脚本。**

## F. 当前所有可比较的检查点（CER 均为 n=8 单次，仅供参考）

    student_16L_layer.pt   0.3493 ~ 0.3794（两次测量）
    student_16L_books4.pt  0.3778 ~ 0.3949
    student_16L_dg2.pt     0.4021
    student_16L_data155.pt 0.4481
    bf16 teacher           0.2867 ~ 0.3297
    fp16 teacher (官方)    0.3824
    **这些数字的差异大多在噪声内，不可据此排序。**

---

# ROUND 18 追加（2026-09-16）：失败模式定性 + EOS 监督

## A. 决定性结论：books4 的差距是【漏字】，不是【内容错】

48-val（n=48，seed 20260914，配对 BF16 teacher），编辑操作按类型/位置分解：

    tag       CER    cer_head  cer_tail    sub     ins     del
    tea     0.2042   0.1381    0.0661    0.1131  0.0003  0.0908
    books4  0.2766   0.1650    0.1116    0.1205  0.0013  0.1549
    layer   0.3128   0.2147    0.0981    0.1375  0.0372  0.1382
    d20L    0.3299   0.2435    0.0864    0.1502  0.0412  0.1386

    与 teacher 配对差值：
    books4  DCER=+0.0724  Dhead=+0.0270(37%)  Dtail=+0.0455(63%)  Dsub=+0.0074  Dins=+0.0010  Ddel=+0.0641
    layer   DCER=+0.1086  Dhead=+0.0766(71%)  Dtail=+0.0319(29%)  Dsub=+0.0243  Dins=+0.0369  Ddel=+0.0474
    d20L    DCER=+0.1257  Dhead=+0.1054(84%)  Dtail=+0.0203(16%)  Dsub=+0.0370  Dins=+0.0409  Ddel=+0.0478

  **books4 的 Dsub 只有 +0.0074（≈teacher 水平），Dins +0.0010。它的差距 89% 是删除（漏字）。**
  **layer/d20L 会【幻觉多字】（Dins +0.037/+0.041，teacher 仅 0.0003），71%/84% 集中在头段。**

  这是三种模型【定性质不同】的失败模式。此前把它们当作同一族做「多 seed 排序」是错的。

## B. 共同现象：学生「压缩」

    语义流密度（48-val 均值）：
    tag      frames   uniq   uniq/frame  frames/char
    tea       303.0  227.7     0.7960      4.149
    layer     293.8  217.5     0.7824      4.037
    books4    284.8  210.3     0.7806      3.968
    d20L      277.0  210.2     0.7844      3.963
    dg2       284.8  206.8     0.7623      3.964

  帧更少、语义词表更窄 -> 音频更短 -> 漏字。dg2（DAgger）重复度最高，与它 CER 更差一致。
  **越界语义 token 0/13670 —— token-ID 契约完好。**

## C. EOS 假设：否定

    所有帧位置上 EOS margin 约 -8.6，且被 top_p 屏蔽 81-85% -> EOS 不可能提前胜出。
    真正的 EOS 决策在位置 Tp+T-1：
      teacher margin +3.89 rank 63 cum 0.8856 masked 10%
      student margin +3.27 rank 63 cum 0.8997 masked 19%
    **student 在决策位置被 top_p 屏蔽的概率是 teacher 的约 2 倍。**

  缓存路径一致性（排除实现 bug）：cos 0.999988，argmax 0.9770，EOS margin RMSE 0.118。

## D. 必须记住的两个陷阱（本轮踩到）

  1. **off-by-one**：m0v2 存的 logs[k] 是【生成第 k 帧】的 logits。
     EOS 在第 T 步被抽中，**该步 logits 从未落盘**。真正的决策位置是 full 的位置 Tp+T-1。
     用 [Tp-1, Tp-1+T) 切片会得到 -16.5 的假 margin（真值 +3.89）。
  2. **测量截断**：m1_gen_student.py:31 `T=min(maxframes, len(semantic.npy))` 把学生
     生成长度硬性截断在 teacher 帧数。所以 dlen>0 构造上不可能，dlen==0 的 29 个样本
     **是被截断而非「长度正确」**。违反纪律 #1「数据长度必须覆盖目标」。

  教训：**相关性（corr(dlen,CER_diff)=-0.709）不能替代操作级分解**——
  按编辑类型拆开后，早停只解释 57%，且对 layer/d20L 分解方向相反。

## E. 新增修改

    m1b_distill_slow.py   新增 W_EOS / EOS_IN_LI / --w-eos（默认 0，不改变既有行为）
                          在位置 Tp+T-1 加 EOS 的交叉熵：
                            eos_log = logits[0, Tp+T-1][LI]
                            ce_eos = F.cross_entropy(eos_log[None], [EOS_IN_LI])
    新脚本（全部只读）：
      m1_eos_profile.py        teacher-forced EOS margin 剖面
      m1_cached_vs_full.py     自由生成路径 vs 全序列前向一致性
      m1_eos_freerun_probe.py  解除长度上限的自由生成 EOS 探针
      m1_stop_decomp.py        按提前停止分组的差距贡献分解
      m1_err_kind.py           编辑操作按类型/位置分解
      m1_sem_density.py        语义流密度/多样性/越界检查

## F. 产物

    runs/audio8-m1/eos_profile_valexp.json
    runs/audio8-m1/cached_vs_full.json
    runs/audio8-m1/eos_decision_valexp.json
    runs/audio8-m1/eos_freerun_valexp.json（探针运行中）
    runs/audio8-m1/g_content_valexp.json（含 dg2）

## G. 下一步

    1. 读 eos_freerun_valexp.json：dlen==0 的 29 个样本是否会自然越过 T
    2. 跑 --w-eos 1.0（tag=16L_books4eos），与 16L_books4 同数据同 seed 做 A/B
    3. 给 m1_gen_student.py 增加 natural 长度模式，修正被截断的评估口径
    4. 三项完成后统一用 seed 级 margin 检验（upper95 < 0.05）判定

  **未解决风险**：早停组反事实（0.0312 -> PASS）n=19、且依赖「补全长度即消除错误」的乐观假设；
  4 个灾难样本贡献 books4 全差距的 43%；teacher 自身 48-val CER 0.2042，
  ref 标签 CER≈0.95+ 说明存在参考音频与文本不匹配的样本，Gate 分母仍不确定。

---

# ROUND 18 追加之二（2026-09-18）：设备侧 F5 —— 慢图量化三路线全部关闭

## A. 设备地址（新增单一来源）

  无线调试地址每次重连都会变。6 个脚本原先各自硬编码了**已失效**的旧地址。
  现统一为：$(if ($env:A8_DEVICE) { $env:A8_DEVICE } else { '10.40.137.201:37869' })
    04-device-htp-smoke.ps1 / 06-mnnqnn-context-equiv-slow-ar-fixed.ps1 / 07-margin-scan-batch.ps1
    08-mnn-multi-equiv-slow-ar-fixed.ps1 / run_fast_ar_positions.ps1 / run_slow_ar_fixed_chain.ps1
  用法：先 $env:A8_DEVICE = '<ip:port>'，否则用默认值。

## B. F4 三条量化路线在设备上全部实测完毕（F5_RESULT.md）

  同一 harness（qnn-net-run --retrieve_context）/ 同一输入 / position-000 / 100 重复：

    臂                        cos        argmax   relL2    ctx_MB   计时(3 次)
    a16w8 基线（per-channel） 0.993341   1.0     0.1159    349.0   42.98/71.84/52.06
    a16w8 per-tensor          0.993228   1.0     0.1167    ~349    88.91 (n=1)
    a8w8                      0.024491   0.0     1.0000    365.6   82.73 (n=1)
    a16w4                     0.170816   0.0     1.6259    364.2   44.83/40.33/41.15

  **A8W8 与 A16W4 数值失效**（cos 0.024 / 0.171，argmax 全错，relL2>=1.0 = 比预测全零还差）。
  **A16W8 per-channel 是唯一健康臂。** per-tensor 质量与基线相同、无收益。
  net.json 里 A16W4 确有 4265 个张量标 4-bit，但 **context 反而更大**（364.2 vs 349.0 MB）。

## C. [新增纪律] 计时结论必须多次重复

  基线 A16W8 三次重复 = 42.98 / 71.84 / 52.06 ms，**自身 spread 1.67 倍**。
  在这种噪声下，A8W8/per-tensor 的单次 82.7 / 88.9 ms **不能**被声明为"慢 1.9~2.1 倍"。
  A16W4 与基线在计时上不可区分 —— W4 不是被计时否定的，是**被质量否定的**。

  与 R49 同类：**单次实现的波动大于所追逐的效应。报告倍数前必须先给出基线的重复分布。**

## D. 对 RTF 路线的影响

  量化已经走到头。慢图基线 1.8~3.0 ms/层，有效带宽仅 ~5-8 GB/s（可用 40-50 GB/s），
  带宽受限解释成立，但**降位宽这条杠杆已用尽**。

  **剩下的杠杆只有「减少层数」和「减少步数」。**
  24L -> 16L 直接把慢图成本乘 16/24 —— **M1 蒸馏不只是为质量，它同时是 RTF 的解药。**
  但蒸馏当前 G-content FAIL（meanDiff +0.0724），**质量仍是主线，提速不能替代质量。**

## E. 产物

  artifacts/diagnostics/slow-ar-matrix-20260918/{summary.json,compare.json,repeat.json,logits-*.raw}
  artifacts/diagnostics/slow-ar-a8w8-a16w4-20260918/
  scripts/f5_dev_slow_matrix.ps1 / f5_compare_slow_arms.py / f5_dev_repeat.ps1 / f5_dev_a8w8_vs_a16w4.ps1


