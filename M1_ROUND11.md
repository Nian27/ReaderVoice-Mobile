# M1 Round 11 — 四个 Gate 全部可用；G-clone PASS 但不足以判定成功

日期：2026-09-16    状态：Gate 齐备；DAgger3 进行中

## 1. Gate 现状总览

| Gate | 工具 | 结果 |
|---|---|---|
| G-content | Whisper(zh)+t2s -> CER | **FAIL**：teacher 0.176 / student 1.26 |
| G-clone | WavLM x-vector cos | **PASS**：teacher-vs-student MEAN 0.958（上界 ref->teacher 0.971） |
| G-separation | 同文本不同音色 student cos | **受限**：受参考素材说话人数量限制（见 R5） |
| G-open-set | 需要从未进训练的音色 | **阻塞**：素材不足（待用户决策） |

## 2. G-clone 详细结果

    name                              clone  ref->stu  ref->tea
    bookA-ch001-p004__voice_001       0.965    0.920     0.978
    bookA-ch001-p004__voice_003       0.981    0.964     0.970
    bookA-ch001-p005__voice_002       0.949    0.950     0.974
    bookA-ch001-p005__voice_003       0.953    0.958     0.975
    bookA-ch001-p009__voice_001       0.957    0.938     0.981
    bookA-ch001-p009__voice_002       0.930    0.931     0.946
    bookA-ch002-p002__voice_001       0.959    0.923     0.971
    bookA-ch002-p004__voice_003       0.973    0.958     0.974
    MEAN                              0.958    0.943     0.971

## 3. G-separation 详细结果

    voice_001 / voice_002   0.534
    voice_001 / voice_003   0.555
    voice_002 / voice_003   0.909    <- 不可分
    MEAN                    0.666

**判读：voice_002 与 voice_003 在 R5 的 WavLM 聚类里本来就同属一个说话人**
（两者都来自 classical_polyphone_zh_*）。
=> 0.909 反映的是参考素材本身，**不是 student 的缺陷**；
   student 正确保留了参考音色的结构（这与蒸馏保真是一致的）。

同时也再次说明 **R5 的音色池问题必须解决**，否则 G-separation 无判别力。

## 4. 一个重要的方法论观察

G-clone 的 0.958 是在**内容已经是乱码**的输出上测得的。
=> speaker embedding 主要由 prompt 中的 reference 决定，
   **G-clone 可以在内容崩溃时依然通过**。

**所以 G-clone 通过 ≠ 蒸馏成功**，必须四门齐过 —— 与用户的判断一致。

## 5. 新增脚本

    scripts/m1_gate_speaker_report.py   一次性算 clone / ref->stu / ref->tea / separation
    注意：voices 目录在 runs/audio8-m0/voices，而数据在 runs/audio8-m0v2
          （路径不同，早期版本因此漏算了 ref->stu）

## 6. 新增纪律（写进代码结构）

**R10 建立**：scripts/m1_sampling.py 是采样/rollout 的唯一实现；
              scripts/m1_sampling_selftest.py 是必须通过的可执行回归护栏。
**R11 建立**：speaker gate 必须报四项——clone / ref->stu / ref->tea / separation。
              只报 clone 会得出错误结论（本例中内容全崩但 clone 仍 0.958）。

## 7. DAgger3 进行中

    steps 3000 / maxroll 64 / tf_prob 0.1 / lr 8e-5（RAS 修正后的 rollout）

## 8. 下一轮

1. DAgger3 完成后重测 tracking + G-content CER
2. 完成 SAMP 委托重构（维护性）
3. 待用户决策：音色池 / M1-B 通过阈值
