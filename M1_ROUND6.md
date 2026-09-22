# M1 Round 6 — 全长度数据重生成 + 任务级 Gate 给出决定性 FAIL

日期：2026-09-16    状态：12L teacher-forced 优秀，free-running 失败；DAgger 重跑中

## 1. 数据重生成改用 GPU 路径（7~10x 提速）

ONNX CPU 路径 0.87 s/frame -> torch GPU（已验证复刻，argmax 与官方 ONNX 一致）0.09 s/frame。
同 4 条对比：229/129/122/484 s  ->  36/31/29/49 s。

    45 条全部完成。
    dataset_digest = e974931223aabdf08342ccb8d4d49423d3d20b6fd1ba2ba478254b23f171f135
    frames min/mean/max = 41 / 458 / 995      (旧: 37 / 63 / 64)
    语音速率 0.182 s/字 ≈ 5.5 字/秒            (旧数据只有 12.2% 覆盖)
    split train 15 / val 15 / test 15 ; kind narration/dialogue/mixed 各 15

**旧 dataset_digest 632dec58… 正式作废。**

## 2. 12L 在全长度数据上重训（teacher-forced）

    step 2999  loss=0.363  kl=0.079  hid=0.060  ce=0.448
               cos_logits=0.99983   cos_hidden=0.99730   argmax_agree=0.765
    3000 步 / 422 s

比旧数据上的 0.99953 / argmax 0.625 更好。

## 3. 漂移指标被校准，并发现它是退化的

    MEAN  teacher vs teacher (不同 seed)  first_div = 1.2
    MEAN  teacher vs student              first_div = 0.5     <- 比 seed noise 还稳
    MEAN  full-frame identical:  T/T = 0.0   T/S = 0.2

**学生与 teacher 的分叉，和 teacher 换个随机种子分叉同量级。**
该指标无法区分「学生质量」与「换了个随机数」。
**前几轮我把 seed noise 误当成学生退化，现在纠正。**

## 4. 任务级 Gate（决定性）

    MEAN CER[teacher] = 0.1758     <- teacher 音频质量好
    MEAN CER[student] = 0.9634     <- 学生音频完全不可懂

学生 uniq semantic = 124~169 / 240~400 帧；teacher = 216~344。
**学生掉进重复吸引子（AR token collapse）。**

## 5. 本轮最重要的方法论结论

    teacher-forced cos_logits = 0.99983  ┐
    cos_hidden               = 0.99730  ├─ 全部无法预测自由生成质量
    漂移指标优于 seed noise              ┘   （学生 CER 0.9634 = 乱码）

**只有任务级 Gate（ASR CER / speaker similarity）能判定蒸馏是否成功。**
这与用户从一开始就强调的「tensor cos 不能代替任务级指标」完全一致。

## 6. 正在进行

    DAgger（修正后的 cache mask，maxroll=48，900 步，warm-start from 12L_v2）
    进度：step 175/900，DAgger cos_log 0.85~0.91，约 3 s/步 -> 约 45 分钟

## 7. 下一轮

1. DAgger 完成后重跑任务级 Gate（CER + speaker），与 0.9634 / 0.1758 对比
2. 若 DAgger 仍不足：考虑 rollout 温度、更长 rollout、序列级（on-policy）迭代
3. 建 G-clone（WavLM x-vector，已可用）与 G-separation / G-open-set

## 8. 产物

    runs/audio8-m0v2/            45 条全长度数据 + split_manifest + wav/
    runs/audio8-m1/student_12L_v2.pt
    runs/audio8-m1/gen_v2/ gen_v2_wav/
    runs/audio8-m1/g_content_v2.json   任务级 CER 结果（关键证据）
    scripts/m0v2_gpu_rollout.py, m0v2_make_split.py, m1_gen_student.py,
            render_flat_codes.py, m1_drift_calib.py, m1_gate_speaker.py
