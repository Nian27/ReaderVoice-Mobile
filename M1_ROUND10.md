# M1 Round 10 — 采样逻辑单一化（结构性消除复制粘贴型 bug）

日期：2026-09-16    状态：唯一实现 + 自检已建立；DAgger3 进行中

## 1. 新建 scripts/m1_sampling.py —— 采样与 rollout 的唯一实现

包含（全部对齐官方契约）：

    sample_official(logits, rng, T, top_p, top_k)     官方 _sample verbatim
    sample_semantic_ras(sl_4097, rng, prev)           RAS 双抽样 + EOS
    new_cache(n_layer, dev)                           KV cache
    fwd_cached(model, ids_step, pos, cache)           单步 forward（正确因果 mask）
    rollout(model, prompt, T, tfast, rng, dev)        规范 rollout

文件头写明了它存在的理由（R7 评测端缺 RAS / R9 训练端缺 RAS，根因都是复制粘贴）。

## 2. 自检：scripts/m1_sampling_selftest.py

**任何 rollout 投入使用前必须通过**：rollout(teacher) 必须逐帧复现 m0v2 存储数据。

    bookA-ch001-p004__voice_001   317/317 语义逐帧一致   PASS
    bookA-ch001-p004__voice_003   288/288              PASS
    bookA-ch001-p005__voice_002   232/232              PASS
    bookA-ch001-p005__voice_003   285/285              PASS
    M1_SAMPLING SELF-TEST: PASS

这是一个可执行的回归护栏，之后不再依赖人肉记忆。

## 3. 过程中修掉一个细节

`sample_official` 直接 `np.asarray(torch_cuda_tensor)` 会报错，
必须 `.detach().float().cpu().numpy()`。TeacherFast.gen 回调传的就是 CUDA tensor。

## 4. 待办（下轮）

把 `m1b_freerun_metric.rollout_slow` / `m1b_dagger.rollout` / `m0v2_gpu_rollout`
全部改为委托 SAMP（当前三者各自实现但已修正，属维护性风险而非正确性 bug）。

## 5. DAgger3 进行中

    steps 3000 / maxroll 64 / tf_prob 0.1 / lr 8e-5，RAS 修正后的 rollout

## 6. 三个评测 bug 的完整清单（教训固化）

| 轮次 | bug | 造成的错误结论 |
|---|---|---|
| R3 | 样本截断（64 帧覆盖 12.2%） | 「漂移来自 exposure bias」（一半是数据问题） |
| R4 | CER 未做繁简转换 | 「teacher 音频质量差」（实际 0.13~0.28） |
| R7 | 评测 rollout 缺 RAS | 「student CER 0.9634」（无效数字） |
| R9 | 训练 rollout 缺 RAS | on-policy 分布与推理不一致 |

共同点：**工具/数据错了，却被当成模型结论。**
对策已落地：唯一采样实现 + 可执行自检。
