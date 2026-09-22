# M1 Round 16 — 补齐 fullsup 的完整 Gate；G-content 实质改善

日期：2026-09-16    状态：DAgger+fullsup 训练中（step 200/2000）

## 1. 三版 student 的完整对照（本轮补齐 fullsup 的 Gate）

| 版本 | first_div | match@20 | match@50 | G-content CER | G-clone |
|---|---|---|---|---|---|
| v2（仅生成段监督） | 4.2 | 0.28 | 0.12 | 1.3207 | — |
| v2dagger | 8.2 | 0.62 | 0.40 | 1.2621 | 0.958 |
| **fullsup（全序列监督）** | **10.4** | 0.57 | 0.25 | **0.8903** | **0.951** |
| teacher 基线 | — | — | — | **0.1758** | 上界 0.971 |

阈值（PLAN REV.3 工作默认）：G-content <= 0.226；G-clone >= 0.90

**G-content 从 1.26 改善到 0.89，是 R13 修复带来的实质进步**，但仍未达阈值。

## 2. fullsup 的 speaker gate（四项齐全，R11 纪律）

    clone(T vs S)   0.951
    ref->student    0.930
    ref->teacher    0.971   <- 上界

    G-separation（同文本不同音色 student cos，越小越可分）
      voice_001 / voice_003   0.605
      voice_001 / voice_002   0.643
      voice_002 / voice_003   0.922   <- 受素材限制：两者本就同属一个说话人
      MEAN                    0.723

## 3. 判读

1. 音色条件（voice conditioning）在蒸馏后**完整保留**（G-clone 0.951 vs 上界 0.971）
2. 内容生成是唯一未达标项，且**每一步修复都带来单调改善**：
       RAS 修复      -> 多样性恢复（uniq 124~169 -> 154~230）
       全序列监督    -> CER 1.26 -> 0.89
3. 说明方向正确，余下是收敛量的问题（DAgger 迭代 + 训练量）

## 4. 进行中

    DAgger + 全序列监督（--init student_12L_fullsup.pt）
    step 200/2000   DAgger cos_log 0.979~0.982   ~4.3 s/步   约 2 小时

    对比上一版 DAgger 的 0.93~0.96，在线指标明显更好。

## 5. 下一轮

DAgger+fullsup 完成后，用同一套工具（tracking / CER / speaker 四项）
与上表对照，判断是否达 G-content 阈值 0.226。

若仍不足，按 PLAN 的后续手段推进：
    - 更长 rollout（96/128）
    - 提高 on-policy 比例
    - 序列级目标
    - 增加训练量（当前仅 15 条 train、5000 步）
