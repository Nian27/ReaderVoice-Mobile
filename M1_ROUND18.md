# M1 ROUND 18 — 失败模式定性：books4 的差距是【漏字】，不是【内容错】

日期：2026-09-16
基准：48 样本扩展 val（全 bookB），seed 20260914，与 BF16 torch teacher 配对
Gate：H0 Student−Teacher ≥ 0.05 vs H1 < 0.05；判据 upper95 < 0.05

## 任务

判定 16L Student（books4）相对 teacher 的 CER 差距（+0.0724）属于哪一种失败模式 ——
内容表征错误、容量不足、数据不足、还是生成控制问题 —— 以决定下一步投入方向。

## 结论

1. **books4 的差距 89% 是「漏字（删除）」，不是内容错。**
   Δsub = +0.0074（≈ teacher 水平），Δins = +0.0010，Δdel = +0.0641。
   其中尾段删除 0.0455（63%）、头段删除 0.0270（37%）。

2. **layer / d20L 是定性质不同的失败：幻觉多字。**
   Δins = +0.0369 / +0.0409（teacher 仅 0.0003），且差距 71% / 84% 集中在头段。
   这解释了为什么它们 sd 大 —— 幻觉是随机事件，不是系统性偏移。

3. **三者共同现象是「压缩」**：帧数少于 teacher，语义词表更窄。
   Δframes = −9.2 / −18.2 / −26.0；Δuniq_rate = −0.0136 / −0.0154 / −0.0115（dg2 −0.0336）。
   帧更少 → 音频更短 → 漏字。这是 recall/覆盖失败，不是 fidelity 失败。

4. **EOS 假设被否定。** 所有帧位置上 EOS margin ≈ −8.6 且被 top_p 屏蔽 81–85%，
   EOS 不可能在帧位置上提前胜出。EOS 只在位置 `Tp+T-1` 成为强候选
   （teacher margin +3.89 rank 63；student +3.27 rank 63），且 student 被屏蔽率 10% → 19%。

5. **发现测量缺陷（纪律 #1「数据长度必须覆盖目标」）：**
   `m1_gen_student.py:31` 把学生生成长度硬性截断在 teacher 帧数 `T`，
   所以 `dlen > 0` 在构造上不可能，`dlen == 0` 的 29 个样本是**被截断**而非「长度正确」。

6. **正面确认**：所有学生输出的语义 token 无一越界（0 / 13670），token-ID 契约完好。

## 证据

### 编辑操作分解（48-val，opencc t2s + 去标点后按 Levenshtein 最优对齐分类）

```
tag       CER    cer_head  cer_tail    sub     ins     del
tea     0.2042   0.1381    0.0661    0.1131  0.0003  0.0908
books4  0.2766   0.1650    0.1116    0.1205  0.0013  0.1549
layer   0.3128   0.2147    0.0981    0.1375  0.0372  0.1382
d20L    0.3299   0.2435    0.0864    0.1502  0.0412  0.1386

与 teacher 配对差值:
books4  ΔCER=+0.0724  Δhead=+0.0270(37%)  Δtail=+0.0455(63%)  Δsub=+0.0074  Δins=+0.0010  Δdel=+0.0641
layer   ΔCER=+0.1086  Δhead=+0.0766(71%)  Δtail=+0.0319(29%)  Δsub=+0.0243  Δins=+0.0369  Δdel=+0.0474
d20L    ΔCER=+0.1257  Δhead=+0.1054(84%)  Δtail=+0.0203(16%)  Δsub=+0.0370  Δins=+0.0409  Δdel=+0.0478
```

### 按「是否提前停止」分组的贡献分解

```
books4  总差距 +0.0724
  dlen==0（被 T 上限截断） n=29  diff=+0.0517  contrib=+0.0312
  dlen<0 （提前 EOS 停止） n=19  diff=+0.1041  contrib=+0.0412
  反事实（早停组达 teacher 水平）总差距 = 0.0312 -> PASS
layer   总差距 +0.1086：dlen==0 n=32 diff=+0.1273 contrib=+0.0849（反事实仍 FAIL）
d20L    总差距 +0.1257：dlen==0 n=28 diff=+0.1513 contrib=+0.0882（反事实仍 FAIL）
```

### 语义流密度（48-val 均值）

```
tag      frames   uniq   uniq/frame  frames/char
tea       303.0  227.7     0.7960      4.149
layer     293.8  217.5     0.7824      4.037
books4    284.8  210.3     0.7806      3.968
d20L      277.0  210.2     0.7844      3.963
dg2       284.8  206.8     0.7623      3.964
```

### 缓存路径一致性（排除实现 bug）

```
自由生成路径(fwd_cached) vs 全序列一次前向（teacher 自身，n=48）
  logits cos        mean=0.999988  min=0.999846
  argmax 一致率      mean=0.9770
  max|Δlogit|       mean=0.229  max=0.750
  EOS margin RMSE   0.118
```

### EOS 决策位置探针（n=48）

```
位置 Tp+T-1（真正的 EOS 决策）   rank    margin    cum     masked
  teacher                        63.3    +3.89   0.8856     10%
  student                        63.0    +3.27   0.8997     19%

位置 Tp+T-2（最后一帧）          teacher margin -8.61  masked 81%
                                 student margin -9.10  masked 85%
```

## 修改

1. `scripts/m1b_distill_slow.py`：新增全局 `W_EOS` / `EOS_IN_LI`、命令行 `--w-eos`（默认 0，不改变既有行为）。
   在 loss 中加入位置 `Tp+T-1` 对 EOS 的交叉熵：
   ```python
   ce_eos = torch.zeros((), device=dev)
   if W_EOS > 0:
       eos_log = logits[0, Tp+T-1][LI].float()
       ce_eos = F.cross_entropy(eos_log[None], torch.tensor([EOS_IN_LI], device=dev))
       loss = loss + W_EOS * ce_eos
   ```
   理由：该位置此前既无 CE target（CE 切片 `[Tp-1, Tp-1+T)` 不含它），
   在 KL 里也只占 `1/(Tp+T) ≈ 0.1%` 的权重。

2. 新增诊断脚本（全部只读，不改数据）：
   - `scripts/m1_eos_profile.py` — teacher-forced EOS margin 剖面
   - `scripts/m1_cached_vs_full.py` — 自由生成路径 vs 全序列前向一致性
   - `scripts/m1_eos_freerun_probe.py` — 解除长度上限的自由生成 EOS 探针
   - `scripts/m1_stop_decomp.py` — 按提前停止分组的差距贡献分解
   - `scripts/m1_err_kind.py` — 编辑操作按类型/位置分解（内容 vs 截断）
   - `scripts/m1_sem_density.py` — 语义流密度/多样性/越界检查

## 命令

```powershell
$PY='E:\AndroidStudioProjects\ReaderVoiceMobile\training\envs\qwen3tts\.venv\Scripts\python.exe'
& $PY scripts\m1_err_kind.py
& $PY scripts\m1_stop_decomp.py
& $PY scripts\m1_sem_density.py
& $PY scripts\m1_eos_freerun_probe.py     # 解除长度上限探针
# 已实现的 EOS 监督训练（待跑）：
& $PY scripts\m1b_distill_slow.py --nlayer 16 --tag 16L_books4eos --steps 4000 --lr 1e-4 --limit 216 --w-eos 1.0
```

## Gate

| 项 | 结果 |
|---|---|
| books4 G-content (48-val) | **FAIL** — meanDiff +0.0724, upper95 +0.1091 |
| layer G-content | **FAIL** — +0.1086, upper95 +0.2163 |
| d20L G-content | **FAIL** — +0.1257, upper95 +0.2438 |
| token-ID 契约 | **PASS** — 0/13670 越界 |
| 缓存路径一致性 | **PASS** — cos 0.999988 |
| EOS 提前触发假设 | **否定** — 帧位置 margin ≈ −8.6 |

## 失败记录

- **「提前停止是根因」的初判被推翻。** 首轮只看 `corr(dlen, CER_diff) = −0.709`，
  据此判断「停止时机是根因」；按编辑类型拆开后发现早停只解释 57%，
  且对 layer/d20L 分解方向相反。**教训：相关性不能替代操作级分解。**
- **一度误判 EOS 剖面为「student 提前抬高 EOS」，实为 off-by-one：
  `logs[k]` 是生成第 k 帧的 logits，EOS 在第 T 步被抽中，该步 logits 从未落盘。**
  真正的决策位置是 `Tp+T-1`，补上后 teacher margin 由 −16.5 变为 +3.89。
- **`m1_sem_density.py` 初版 `show()` 的 `key` 默认值写死成 teacher**，
  导致逐 tag 行全部显示 teacher 数值；已修。

## 产物

- `runs/audio8-m1/eos_profile_valexp.json`
- `runs/audio8-m1/cached_vs_full.json`
- `runs/audio8-m1/eos_decision_valexp.json`
- `runs/audio8-m1/eos_freerun_valexp.json`（探针完成后）
- `runs/audio8-m1/g_content_valexp.json`（含 dg2，重算中）

## 风险

1. **早停组的反事实（0.0312 -> PASS）是乐观估计**，n=19，且假设「补全长度即可消除错误」。
   4 个灾难样本（ch111/ch036/ch061/ch056）贡献了 books4 全差距的 43%，样本量太小。
2. **长度上限 T 使所有长度统计被右删失**，学生真实自然长度未知，探针结果出来前
   不能断言「压缩」是训练问题还是测量artifact。
3. teacher 本身的 48-val mean CER 0.2042，样本难度极高（含 ref 音频与文本不匹配的样本，
   `ref` 标签 CER≈0.95+）。Gate 分母的不确定性仍未解决。

## 下一步

1. **读 `eos_freerun_valexp.json`（探针）**，判定 dlen==0 组的 29 个样本是否会自然越过 T
   —— 若会，则尾段删除（0.0455）主要由测量截断制造。
2. **跑 `--w-eos 1.0`**，与 `16L_books4` 同数据同 seed 做 A/B，在 48-val 上重测。
3. **修正评估口径**：给 `m1_gen_student.py` 增加 `--length-mode natural`（上限 = T + margin），
   与既有 capped 口径并列报告，避免用被截断的长度做长度结论。
4. 三项做完后统一用 seed 级 margin 检验（upper95 < 0.05）判定是否 PASS。

---

# ROUND 18 补充：dg2 完成后的完整统计（48-val, n=48, 34 chapters）

## 1. 四个 16L 变体 全部显著劣于 teacher；变体之间 无一显著

    20k chapter-cluster bootstrap（重采样 chapter，非样本）
    pair               mean_diff      sd      t   p(2s)  boot95lo  boot95hi
    tea-books4           -0.0724  0.1266  -3.96  0.0001   -0.1112   -0.0405 SIG
    tea-dg2              -0.0645  0.0961  -4.65  0.0000   -0.0952   -0.0367 SIG
    tea-layer           -0.1086  0.3713  -2.03  0.0428   -0.2361   -0.0379 SIG
    tea-d20L            -0.1257  0.4072  -2.14  0.0324   -0.2630   -0.0434 SIG
    books4-dg2           +0.0079  0.1033   0.53  0.5974   -0.0175   +0.0388 ns
    books4-layer         -0.0362  0.3894  -0.64  0.5199   -0.1693   +0.0419 ns
    books4-d20L          -0.0533  0.4191  -0.88  0.3784   -0.1952   +0.0324 ns
    dg2-layer            -0.0440  0.3867  -0.79  0.4301   -0.1781   +0.0331 ns
    dg2-d20L             -0.0612  0.4103  -1.03  0.3017   -0.2015   +0.0189 ns
    layer-d20L           -0.0171  0.1252  -0.95  0.3434   -0.0524   +0.0197 ns

  **按 R50 纪律：学生 vs teacher 显著（学生更差）；变体 vs 变体不显著。
  绝不能把「变体间不显著」读成「达到了 teacher 水平」—— 反方向才是真结论。**

## 2. dg2(DAgger) 的失败模式与 books4 互补 —— 本轮最重要的发现

    tag       CER   cer_head cer_tail    sub     ins     del
    tea     0.2042   0.1381   0.0661   0.1131  0.0003  0.0908
    books4  0.2766   0.1650   0.1116   0.1205  0.0013  0.1549
    dg2     0.2688   0.1918   0.0770   0.1464  0.0006  0.1217
    layer   0.3128   0.2147   0.0981   0.1375  0.0372  0.1382
    d20L    0.3299   0.2435   0.0864   0.1502  0.0412  0.1386

    Delta vs teacher:
    books4   +0.0724  head +0.0270(37%)  tail +0.0455(63%)  sub +0.0074  ins +0.0010  del +0.0641
    dg2      +0.0645  head +0.0537(83%)  tail +0.0109(17%)  sub +0.0333  ins +0.0003  del +0.0310
    layer    +0.1086  head +0.0766(71%)  tail +0.0319(29%)  sub +0.0243  ins +0.0369  del +0.0474
    d20L     +0.1257  head +0.1054(84%)  tail +0.0203(16%)  sub +0.0370  ins +0.0409  del +0.0478

  **books4：内容保真极好（Dsub +0.0074 约等于 teacher）但尾段漏字严重（Dtail +0.0455）。**
  **dg2：尾段漏字被压掉 76%（Dtail +0.0109），零幻觉（Dins +0.0003），但替换错误涨 4.5 倍（Dsub +0.0333）。**

  dg2 的配方：--teacher-forced-prob 0.25（25% TF + 75% 自 rollout）、--maxroll 24
  （rollout 只覆盖前 24 帧）。**DAgger 只在自轨迹的前 24 帧上训练，
  却在尾段漏字上取得最大改善 —— 说明「停止/长度控制」由早期状态决定。**

  **两者误差互补，总分因此几乎相同（差 +0.0079，不显著）。
  这意味着存在一个尚未尝试的组合：books4 的 Dsub + dg2 的 Dtail
  -> 预期总差距约 0.02~0.03，将进入 PASS 区间。**

## 3. 推荐的下一个实验（有明确预期）

    目标：同时拿到低替换 + 低尾段漏字
    配方：teacher-forced KD（保替换）
        + 显式 EOS 监督（--w-eos，本已实现）
        + on-policy rollout，且 --maxroll 加长到覆盖尾段（>= 128）
        + --teacher-forced-prob 高于 0.25（保护 Dsub）
    判据：48-val seed 级 margin 检验 upper95 < 0.05

## 4. 机器争抢（重要环境事实）

  本轮训练与 gate 极度缓慢，根因不是脚本：**同一台机器上还有其它项目在跑**
  （E:\Project\320M_3D 的 run_gpu_batch/unseen_eval、physics room 模拟占 13.5 GB 内存、
  eval_gate_coherence），把 16 GB 显存压到 15.9/16.4 GB。
  训练实测约 5.2 s/step，4000 步约 5.8 小时。**做性能/时间结论时必须计入这一外部负载。**

