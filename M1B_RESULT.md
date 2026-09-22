# M1-B RESULT — 12L SlowStudent 首轮蒸馏（teacher-forced 成功，free-running 未达标）

日期：2026-09-16    状态：**部分达成**（pipeline 验证通过；生成质量待改进）

## 实现（自建模块，复刻官方 modeling_arktts.py 的 slow 路径）

    scripts/m1b_distill_slow.py    StudentSlow（embedding -> 12 blocks -> norm -> tied head）
    scripts/m1b_generate.py        自回归生成 + TeacherFast（4 层 fast 分支，10 位置 KV cache）
    scripts/m1b_render.py          codes -> WAV（官方 codec_decoder_fp16.onnx）

    训练环境：training/envs/qwen3tts/.venv  torch 2.11.0+cu128  RTX 4060 Ti 16GB

## 训练设置

    nlayer=12   steps=2000   lr=2e-4 OneCycle   limit=15 (train split)
    freeze: embeddings + codebook_embeddings（第一版）
    trainable = 178.95M params
    loss = 1.0*KL(t||s) + 1.0*MSE(hidden) + 0.5*CE(semantic)
    训练路径：prompt.npy -> Student **自己完整 forward prefill** -> 逐位置对齐 teacher
    速度 ~8 步/s，2000 步 249 s

## 结果

### teacher-forced（M1-B 主指标）

    step 1999  loss=0.379  kl=0.095  hid=0.037  ce=0.495
               cos_logits=0.99953   cos_hidden=0.99819   argmax_agree=0.625   |grad|=0.18

### free-running（自回归，Student slow + teacher fast + 官方采样器）

    MEAN  semantic_agree=0.516   cos_logits=0.841   （per-sample 0.47 ~ 0.99）

    name                              cosL      semAgree
    bookA-ch002-p005__voice_002      0.98963    1.000
    bookA-ch004-p000__voice_001      0.95902    0.625
    bookA-ch002-p004__voice_003      0.94159    0.750
    bookA-ch001-p009__voice_001      0.90544    0.562
    bookA-ch003-p010__voice_001      0.90450    0.500
    bookA-ch001-p005__voice_002      0.78290    0.250
    bookA-ch003-p000__voice_003      0.77589    0.375
    bookA-ch004-p014__voice_002      0.47233    0.062

## 判读

1. **蒸馏管线本身成立**：teacher-forced cos_logits 0.99953 / cos_hidden 0.99819，12L 确实学得住。
2. **free-running 明显退化**（0.9995 -> 0.841）—— 正是计划里预警的 exposure bias / 状态漂移。
   这是下一步要解决的核心问题，不是实现 bug。
3. **argmax_agree=0.625 与 cos_logits=0.99953 并存**，符合用户预判的「top1/top2 差距极小」，
   不应据此判 FAIL。

## 两个指标方法论修正（重要）

- `semantic_agree` / `codebook_agree` 比的是**两次独立随机采样**的结果，不是公平指标；
  只有 `cos_logits` 在 teacher-forced 下有意义。free-running 下 prefix 一旦分叉，逐帧对比失效。
- **WAV 波形 cos 不是随机 TTS 的有效 Gate**（本次 student/teacher wav_cos 仅 0.045~0.449）。
  同文本不同采样会产生感知相似但波形不相关的音频。
  => G-content / G-clone 必须用 **ASR/CER + speaker embedding**，不能用波形 cos。

## 下一步（M1-B 收敛 + Gate）

1. 提升 free-running：
   - 更多步数（2000 -> 10000+）与全部 15/45 条
   - scheduled sampling（逐步用 student 自己的 prefix 训练）
   - 加长 teacher-forced 上下文比例
2. 实现四个 Gate：
   - G-content: ASR -> CER（需要 ASR 模型；或用 semantic token 分布 KL）
   - G-clone / G-separation / G-open-set: speaker embedding cosine
     （需确认可用的 speaker encoder；本项目有 Qwen3-TTS-VoiceDesign 与 speech_tokenizer 可查）
3. 通过后再进 M2-A（8L）。

## 产物

    runs/audio8-m1/student_12L_overfit.pt              训练 checkpoint
    runs/audio8-m1/m1b_12L_overfit.json                训练历史
    runs/audio8-m1/gen_12L_overfit/{g_content.json, *.codes.npy, *.semantic.npy, *.student.wav, *.teacher.wav}
    listen-kit-20260915/W{1,2,3}_12Lstudent_*.wav + W{1,2,3}_TEACHER_*.wav
