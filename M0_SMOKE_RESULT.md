# M0-SMOKE — FP16 teacher rollout 单句验证

日期：2026-09-16    状态：**PASS**
脚本：scripts/m0_teacher_smoke.py
产物：runs/audio8-m0/{meta.json,prompt.npy,positions.npy,x.npy,hidden.npy,logits.npy,semantic.npy,codes.npy,m0_teacher_smoke.wav}

## 配置

    teacher  = models_fp16/slow_ar_fp16.onnx (50 inputs) + fast_ar_fp16.onnx + codec_decoder_fp16.onnx
    prompt   = 设备 dump 的 prompt_GREEDY_noref0_idx0.txt -> [11,132]，与端侧逐位一致
    protocol = 官方 runtime.py verbatim（单次 multi-token prefill + 逐帧 decode）
    seed     = 20260914, temperature 0.7 / top_p 0.9 / top_k 50
    max_new  = 64  ->  实际生成 55 帧（遇到 stop）

## Gate 结果

| # | Gate | 结果 | 证据 |
|---|---|---|---|
| 1 | FP16 full-chain WAV 可听懂 | **待用户听** | pcm n=112640 peak=0.3380 rms=0.0457 dur=2.55s |
| 2 | dump 开/关 semantic+codes 完全一致 | **PASS** | semantic identical=True, codes identical=True |
| 3 | replay 复现 teacher logits/hidden cos>=0.9999 | **PASS** | logits cos min=1.00000000；hidden cos min=1.00000000 |
| 4 | 无 INT4/设备/QNN 数据混入 | **PASS** | 仅 models_fp16/*.onnx + device prompt 矩阵 |

Gate 3 是 **严格 1.00000000**（不是 0.9999 量级）—— 两次独立 rollout 逐位相同，
说明 dump 挂钩是纯读操作，且 rollout 完全确定。

## 数据结构（已冻结）

    prompt.npy      [11,132] int64     slow 输入矩阵（1 semantic + 10 codebook）
    positions.npy   [55]     int64     产生该帧 logits 的绝对位置
    x.npy           [55,11]  int64     slow 步的输入 token 列
    hidden.npy      [55,896] float32   teacher slow_hidden
    logits.npy      [55,4097] float32  teacher slow logits（semantic_then_eos 布局，未压缩）
    semantic.npy    [55]     int64     teacher 采样的 semantic token
    codes.npy       [55,10]  int64     完整 10 路 codebook（Fast 蒸馏直接可用）

**注**：FP16 ONNX 的 slow 输入是 token id（embedding 在模型内部），
与设备微图的 x[896] 契约不同。x.npy 存的是 token 列；
若 M1 需要 embedding 输入，需从 ONNX 里取出 embedding 表。

**未压缩 teacher 信息**：logits 全量 4097 维保存，按计划 M1 验证完 KL 需求后再决定是否改 top-K。

## 健康度

    uniq semantic = 54/55 (98%)   <- 无塌缩
    rollout 耗时 ~15-16s / 55 帧（PC CPU）

## 下一步（M0-2）

扩到 20~50 句小集，然后 book-level split 验证 + dataset digest。
句表来源需确认：TEST3 只有 3 条；需要一份真实文本清单（且按书 split）。
