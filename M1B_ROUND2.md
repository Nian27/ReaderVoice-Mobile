# M1-B 进展（Round 2）— 诊断 + 一个真实实现 bug + 有效指标建立

日期：2026-09-16    状态：进行中（修正版 DAgger 训练中）

## 1. Oracle 诊断：证明推理路径与训练等价

喂 teacher 的真实 token（而非采样）跑 student 自回归：

    MEAN cos_logits = 0.99935   （8/8 样本，min 0.99823）
    对比 free-run（自己采样）: 0.841

=> 推理路径没有实现 bug，gap 100% 来自 prefix 漂移（exposure bias）。

## 2. 发现并修复一个真实实现 bug（KV cache attention mask）

m1b_dagger.py 的 fwd_cached 里 mask 写成：

    attn = (key_ok | fully[...])      # fully 全 True

这使 mask **恒为全 True**，query 会注意到 cache 中未填充的 2048-pos 零区。
第一轮 DAgger 的 rollout 走的就是这条坏路径。

修正为纯因果：  attn = (kp[:,None,:] <= qp[:,:,None])[:,None]

### 验证 cached vs 全序列 forward（同序列逐步对比）

    per-step logits cos: [1.0, 0.999997, 0.999997, 0.999995, 0.999999, 0.999999, 0.999999, 0.999996]
    per-step hidden cos: [1.0, 0.999998, 0.999998, 0.999997, 0.999998, 0.999998, 0.999998, 0.999997]
    MIN logits cos = 0.999995   MIN hidden cos = 0.999997

=> cache 实现已验证。脚本：scripts/m1b_validate_cache.py

## 3. 一个方法论纠正：之前的 free-run cos_logits 指标无效

m1b_generate.py 里 free-run 的 cos_logits 是拿 student logits 对比
**teacher 在 teacher 自己 prefix 上存的 logits**。prefix 一旦分叉，对比失效。
所以 0.841 / 0.878 都不是有效数字，应作废。

## 4. 建立有效的 free-running 指标

teacher(torch 24L) 与 student 用**同一 RNG、同一采样器**各自 rollout，量：

    first_div_semantic       首个 semantic 不同的帧
    full_frame_identical     11 路 token 全同的帧数

基线（student_12L_overfit.pt, T=32, 8 samples）：

    mean_first_div = 1.1 / 32
    mean_identical_frames = 0.1 / 32

**注意这个指标非常严格**：两个分布只要有一点差异，随机采样就会在几帧内分叉。
它不是「学生好坏」的直接判据，而是「漂移速度」的度量。
最终判据仍必须是任务级（ASR/CER + speaker similarity），与用户既定 Gate 一致。

## 5. 正在进行

    修正版 DAgger（12L, warm-start from overfit, 1200 步, fixed cache）
    进度：step 50/1200, cos_log 0.879 -> 0.943, ~4 s/step => 约 80 分钟
    日志：runs/audio8-m1/dagger12L_v2.log

## 6. 下一轮

1. DAgger2 完成后重测有效 free-running 指标（first_div / identical_frames），
   与基线 1.1 / 0.1 对比
2. 建立任务级 Gate（G-content = ASR/CER；G-clone/separation/open-set = speaker embedding）
3. 若 DAgger 仍不足以支撑长序列，考虑：降低 rollout 采样温度 / 加长 maxroll / 增大数据量
