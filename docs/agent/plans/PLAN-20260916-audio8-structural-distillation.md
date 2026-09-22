# PLAN-20260916-audio8-structural-distillation  (REV.2 — 由性能预算倒推 Student 尺寸)

状态：ACTIVE      M0-2 ✅ / M0-3 ✅     下一步 M1-A
前置：F4_CORRECTION.md、P0_CLOSURE.md、M0_2_RESULT.md

---

## 1. 目标（硬预算，不是只有 RTF<1）

目标音频 2.972 s。每一块给硬预算，留手机抖动余量：

    Prefill      <= 0.30 s
    Slow         <= 0.80 s
    Fast         <= 0.55 s
    Codec        <= 0.90 s
    Host/other   <= 0.15 s
    --------------------------
    Total        <= 2.70 s        RTF <= 0.91

## 2. 单层成本（从 in-app clean 基线反推，不是估计）

    clean 基线: audio 2.972s  prefill 12.65  slow 6.18  fast 9.40  codec 7.68  total 35.91  RTF 12.08

    Slow : 6.176 s / 64 帧 / 24 层 = 4.02 ms / layer / frame
    Fast : 9.404 s / 64 帧 / 10 步 / 4 层 = 3.67 ms / layer-pass

    => 产品环境按 **约 4 ms / layer-pass** 作为安全基线。

## 3. 从预算倒推出的硬约束

    Slow 预算 0.80 s / 64 帧 = **12.5 ms / frame**

    满足 12.5 ms/frame 的可行解：
        4L @ 3.0 ms/layer   = 12 ms   OK
        6L @ 2.0 ms/layer   = 12 ms   OK
        8L @ 1.5 ms/layer   = 12 ms   OK

    => **最终 Slow 不是固定 12L，而是必须做到约 12 ms/frame。**
       具体是 (4L,W8) / (6L,高效kernel) / (8L,W4+更小hidden) 里哪个，由音质决定。

### 3.1 为什么 12L 不是产品形态

    只做 24->12，其它不变：Slow 6.18 -> 3.09 s
    **Slow 自己就超过整条 RTF=1 的 2.972 s 预算。**

    加上 Fast one-shot 4L (0.94 s)：
        Slow 12L -> Slow+Fast = 4.03 s   ❌
        Slow  8L -> 3.00 s               ❌ 光 AR 就 3 秒
        Slow  6L -> 2.48 s               ⚠️ 只剩 0.49 s 给 prefill+codec
        Slow  4L -> 1.97 s               ⚠️ 只剩 1 s，仍非常紧

    结论：**每层还是现在这个价格的话，砍到 4 层都难过 RTF=1。**

### 3.2 因此必须同时发生五件事

    ① Slow 大幅减层
    ② Fast 10-step -> one-shot
    ③ 单层本身更便宜（W4 / 缩 hidden / 更小 Student）
    ④ Codec < 1 s
    ⑤ prefill multi-token

## 4. Student family（不是单个 12L）

    Teacher 24L
      ├── 12L   蒸馏 pipeline 验证版（**不作为产品**）
      ├──  8L   第一产品候选
      ├──  6L   性能候选
      └──  4L   激进移动端候选

    12L 的使命是回答「减掉一半层以后，content / voice clone / open-set 还能不能保持」。
    12L 学不住 -> 先别谈 8L/4L。
    12L 很轻松 -> 马上下探 8L -> 6L -> 4L，不停在 12L。

    Fast 不锁死 4L：同时测 FastStudent-4L 与 FastStudent-2L one-shot。
    Fast 任务比 Slow 简单（teacher 已给强 semantic hidden），可能出现 Slow6L+Fast2L 这种更划算的组合。

## 5. hidden width（分阶段）

    阶段 1：hidden 896 固定（最易蒸馏）
    阶段 2：若 4~8L 仍不够，再缩 width（需 projection adapter，难度更高）
        768/896 -> (0.857)^2 ≈ 0.735 每层约便宜 26%
        640/896 -> (0.714)^2 ≈ 0.51 接近一半

## 6. 接口契约（冻结）

    Student 保持与 teacher 完全相同的外部契约：
        slow:  codes/token IDs [1, 11, T]，embedding 在模型内部
        fast:  slow_hidden + token_id + use_slow_hidden + input_pos

    数据资产 x.npy = [T,11] int64 是长期契约，**不转成 [T,896] embedding**。
    第一版 M1 freeze embedding + output head，只训中间层。

    DISTILLATION INVARIANT:
        Student must preserve the teacher's complete voice-conditioning protocol.
        No distillation stage may collapse, remove, average, or bypass
        reference-voice conditioning.

## 7. M1 核心训练路径（关键：Student 自己 prefill）

    prompt.npy [11,T]
       -> Student 完整 prefill        <-- 不能从 teacher hidden/cache 起步
       -> teacher-forced decode
       -> 每帧比较 student logits/hidden <-> teacher

    危险假 PASS：给 Student 正确的 teacher state -> decode 好；
                 真实自己 prefill -> voice clone 崩。
    因此不保存也不使用 24 层 teacher KV cache（M0 数据已足够）。

## 8. 四个 Gate（M1 起固定）

    G-content     Teacher <-> Student  logits cos / token agreement / CER
    G-clone       同 reference: Teacher WAV <-> Student WAV speaker similarity
    G-separation  同 text + 不同 reference: voice_001 vs voice_002 必须可分
    G-open-set    voice_006/007/008（从未训练）仍能 clone   <-- VoiceDesign 的硬门

    禁止只报 tensor cos；每个 milestone 必须同时报任务级指标。

## 9. 数据与 split（已冻结）

    runs/audio8-m0/  n=45  dataset_digest=632dec58e94f20bdf71fba722a9d0d280b84f2da54d20fdc1d015c5fa5013a99
    book_split   train=bookA / val=bookB / test=bookC     同一本书绝不跨 split
    voice_split  train=001,002,003 / val=004,005 / open-set holdout=006,007,008
    span_kind    narration 15 / dialogue 15 / mixed 15

    M0-2 是 smoke 集：M1-B 的 overfit 允许用全量 45 条（split 不适用于 overfit 检验）。
    正式训练（M1-C 起）严格遵守 split。

## 10. 路线（REV.2）

    M1-A   24->12L 初始化                 目的：验证蒸馏/voice-clone pipeline，不作为产品
    M1-B   12L smoke overfit (16~32 条, 3 train voice)
           Gate: G-content + G-clone + G-separation + G-open-set
    M1-C   12L 多书多 voice 正式训练
    M2-A   24->8L    第一产品候选
    M2-B   24->6L    性能候选
    M2-C   24->4L    激进候选
    M3     Fast one-shot（同时测 4L / 2L）
    M4     W4 + QNN 转换，按实测 ms/layer 选最终组合

    目前押注的组合：SlowStudent 6~8L + FastStudent 2~4L one-shot + W4 + multi-token prefill
    而不是 Slow 12L + Fast 4L。

## 11. 已知风险

1. ~~设备侧 correctness 未收口~~ **已解除**（见 P0_CLOSURE.md）
2. **性能基线未冻结**：in-app RTF 在 7.6~12.2 间波动（HTP 快/慢态，成因未定）。
   本计划所有 ms/layer 都取自 clean 基线（RTF 12.08），属偏保守取值。
3. Slow 减层与 Fast one-shot 的收益是**加法**（作用于不同阶段），不是乘法。
   Codec 是独立的第三堵墙。
4. 缩 hidden width 需要 projection adapter，蒸馏难度显著高于单纯减层，放阶段 2。

## 12. 并行轨道

    P0  设备侧 correctness                 ✅ CLOSED / FROZEN
    P1  HTP 快/慢态根因（idle-gap sweep / forced perf mode）  ⏸ 可能白拿 5x
    P2  Codec acceleration（独立墙）        ⏸
    M*  本计划                              🔥 ACTIVE
---

# REV.3 (2026-09-16) — M1 执行中记录：默认值、纪律、进度

## A. 数据（已冻结，取代 REV.2 的数据章节）

    runs/audio8-m0v2   n=45
    dataset_digest = e974931223aabdf08342ccb8d4d49423d3d20b6fd1ba2ba478254b23f171f135
    frames min/mean/max = 41 / 458 / 995      语音速率 0.182 s/字
    split: book train/val/test 各 15；kind narration/dialogue/mixed 各 15

    **旧的 runs/audio8-m0 (632dec58…) 作废**：它只生成了每段前 2.97 s（覆盖 12.2%）。

## B. 音色池：采用工作默认值，不再阻塞

事实（R5 实测，WavLM x-vector 聚类）：
    官方 demo 素材（voices01b 15 个中文 + voicesml 11 个多语言）
    **实际只覆盖约 4~6 个不同说话人**（由少数配音员录制）。

工作默认（在用户给出其他指示前执行）：
    1. train 用现有 voices（voice_001/002/003），G-separation 的结果必须标注
       「受素材限制，voice_002 与 voice_003 本就同属一个说话人」
    2. **open-set 预留 voice_006/007/008，绝不进训练**（即使它们与某些 train voice 同簇）
    3. 长期方案（待补）：用本机 Qwen3-TTS-1.7B-VoiceDesign 生成全新音色做 G-open-set ——
       这恰好对应产品真实场景「VoiceDesign 设计出没见过的新音色」

## C. M1-B 通过阈值：采用工作默认值

    G-content:  student CER <= teacher CER + 0.05      (teacher 实测 0.1758 -> 阈值 0.226)
    G-clone:    student-vs-teacher speaker cos >= 0.90  (teacher 上界 ref->teacher 0.971)
    G-separation: 同文本不同 reference 的 student cos 必须显著低于同 reference 的 cos
    理由：绝对 CER 会被 ASR 与近音字影响（如 异兽->艺术 是近音错误，不应等同乱码），
          故用相对判据。**若用户给出不同阈值，以用户为准。**

## D. 累计纪律（6 条，全部来自实测教训）

| # | 纪律 | 来源 |
|---|---|---|
| 1 | 数据长度必须覆盖目标 | R3：64 帧只覆盖 12.2% 文本 |
| 2 | 度量必须做等价归一（繁简） | R4：CER 0.61 -> 0.36 |
| 3 | 采样必须单点实现 + 可执行自检 | R7/R9：缺 RAS 使评测与训练双双失真 |
| 4 | speaker gate 必须报 clone/ref->stu/ref->tea/separation 四项 | R11：只报 clone 会在内容全崩时得出 PASS |
| 5 | 全序列平均指标必须按位置分解 | R13：0.99983 掩盖了 prompt 段 0.53 |
| 6 | 同一逻辑若有多处实现，修复必须逐处核对 | R14：m1b_dagger 漏修 |

## E. 已建立的护栏（可执行）

    scripts/m1_sampling.py            采样/rollout 唯一实现（RAS + EOS + 因果 mask）
    scripts/m1_sampling_selftest.py   rollout(teacher) 必须逐帧复现 m0v2   [PASS]
    scripts/m1_sampling_crosscheck.py 两个实现必须给出相同序列            [PASS]
    scripts/m1_pos_profile.py         cos 按序列位置分解（定位 R13 根因的工具）

## F. M1 进度快照

    teacher-forced（全序列监督后）
        gen 段 cos 0.99958~0.99986     prompt 段 cos 0.99994~0.99998
        修复前 prompt 段仅 0.53（根因）

    free-running tracking（first_div / match@20 / match@50）
        v2 (仅 TF)      4.2 / 0.28 / 0.12
        v2dagger        8.2 / 0.62 / 0.40
        fullsup        10.4 / 0.57 / 0.25
        fullsup+DAgger  进行中

    Gate
        G-content  FAIL (teacher 0.176 / student 1.26)
        G-clone    PASS (0.958)
        G-separation 受素材限制
        G-open-set   阻塞（待 VoiceDesign 生成音色）
---

# REV.4 (2026-09-16) — 路线修正：teacher-forced cos 不是有效判据

## A. 决定性测量（R35）

    train:  teacher-forced cos 0.9997  ->  free-running CER 0.89
    val:    teacher-forced cos 0.8070  ->  free-running CER 0.97
    teacher val 基线 CER 0.2867（阈值 G-content <= 0.226）

**cos 在 0.81 ~ 0.9997 的范围内，对应的 CER 都不可用。**
=> **teacher-forced cos 对 free-running 质量几乎没有预测力。**

## B. 因此修正主线

    ❌ 错误主线：继续堆 teacher-forced 数据 -> 只抬 cos，不救 free-running
    ✅ 正确主线：在【足够的数据量】上做 DAgger（on-policy）

两者的作用不同，且都需要：
    数据量     -> 保证 val 泛化（R32/R34 四点验证：14/40/60/96 条 -> 0.680/0.742/0.772/0.807）
    DAgger     -> 保证 free-running 稳定（R8：first_div 4.2->8.2, match@50 0.12->0.40）
**先后关系：先有数据，再 DAgger。**

## C. 判据变更（重要）

    ❌ 不再用 teacher-forced cos 作为 M1 的通过判据
    ✅ 用 val 集的 free-running CER（Whisper + t2s）作为主判据
    ✅ 辅以 speaker 四项（R11）

## D. 数据现状

    runs/audio8-m0big   n=132   dataset_digest=692de0c1b224113ef320654c7c2f22d41be586d58480ed9084d9f54cf8f6fbb4
    split: train(bookA) 96 / val(bookB) 10 / test(bookC) 26
    语音速率 0.200 s/字；退化样本已过滤（frames<40 或 <0.3*target）
    bookA 可用池约 250 条（当前已用 97）

## E. 缩放曲线的四点证据（R32/R34）

    train 条数   val gen_cos   val argmax
       14          0.680         0.060
       40          0.742         0.096
       60          0.772         0.116
       96          0.807         0.153
    单调上升，未饱和；斜率 ~+0.035 / 36 条
    **但这条曲线只对 teacher-forced 成立，对 free-running CER 无效（见 A）。**

## F. 累计纪律（8 条）

| # | 纪律 | 来源 |
|---|---|---|
| 1 | 数据长度必须覆盖目标 | R3 |
| 2 | 度量必须做等价归一（繁简） | R4 |
| 3 | 采样必须单点实现 + 可执行自检 | R7/R9 |
| 4 | speaker gate 必须报四项 | R11 |
| 5 | 全序列平均指标必须按位置分解 | R13 |
| 6 | 同一逻辑多处实现时修复必须逐处核对 | R14 |
| 7 | 训练结果必须在 val/test 上报告 | R22 |
| 8 | **代理指标的适用范围必须实测，不能假设** | R35 |

第 8 条的教训：我在 R21—R34 把 teacher-forced cos 当成进度指标推了 14 轮，
而它在该区间内与最终质量无关。**代理指标必须先验证与目标指标的单调关系。**

## G. 当前进行中

    DAgger + 全序列监督（--init student_12L_big97.pt, 96 样本, 1200 步）
    完成后用 val 集 free-running CER 判定
