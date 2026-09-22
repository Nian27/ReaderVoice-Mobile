# M1-B Round 3 — 发现并修复数据设计缺陷（样本截断）

日期：2026-09-16    状态：数据重生成中

## 1. 决定性对照：rollout 确定性成立

    teacher vs teacher, 同 seed  -> identical: True
    teacher vs teacher, 不同 seed -> identical: False
    rollout 成本: 0.148 s/frame

=> free-running 漂移指标有效（脚本 scripts/m1b_control.py）

## 2. G-content Gate 建起来了（Whisper）

    venv 内 whisper OK，模型 cosyvoice3-distill-lab/whisper/base.pt (145MB)
    scripts/m1_gate_content.py : wav -> Whisper(zh) -> CER（编辑距离/参考字数）

### 首次运行结果 —— 却暴露了真正的问题

    MEAN CER[teacher] = 0.9654
    MEAN CER[student] = 0.9665

teacher 和 student 的 CER 都是 0.96，Whisper hypothesis 只有 4~6 个字。

## 3. 根因：M0 数据被严重截断（数据设计缺陷，不是模型问题）

    n_chars   min/mean/max =  60 / 121 / 236
    frames    min/mean/max =  37 /  63 /  64     <- 98% 恰好 64
    实际音频 mean 2.9s   按 5 字/秒需要 mean 24.2s   覆盖率 12.2%
    需要帧数 mean 521（当前硬上限 64）

**m0_rollout.py 对所有 span 固定 maxn=64，只生成了每段的前 2.97 秒。**
后果：
1. Gate 无法计算（用完整段落文本比 CER 没有意义）
2. **很可能是 free-running 漂移的主因** —— student 只见过 64 帧续写，
   却被要求生成远超训练视野的长度

## 4. 修复

    frames_for(text) = int(len(text)/5.0 * 21.53) + 48      # 中文 5 字/秒, 21.53 frame/s
    输出改到 runs/audio8-m0v2（保留旧集不覆盖）

### 3 条验证（长度恢复对比）

    bookA-ch001-p009__voice_001  chars= 60  frames=306/306  264s  uniq=217/306
    bookA-ch001-p009__voice_002  chars= 60  frames=230/306  198s  uniq=199/230
    bookB-ch031-p000__voice_002  chars=125  frames=200/586  178s  uniq=165/200

帧数 64 -> 200~306，uniq 比例健康。成本 178~264 s/sample。

## 5. 正在进行

    45 条全量重生成（后台，预计约 3 小时）-> runs/audio8-m0v2

## 6. 下一轮

1. 重生成完成后：重新生成 teacher WAV -> 用 G-content(Whisper CER) 验证 teacher 基线
   （teacher CER 必须显著低于 0.96 才说明数据可用）
2. 用新数据重训 12L（teacher-forced + DAgger）
3. 重建 split_manifest + dataset_digest（旧 digest 632dec58 作废）

## 7. 注意：旧冻结资产受影响

    runs/audio8-m0 的 dataset_digest 632dec58... 建立在截断数据上，
    重生成后必须重新冻结。计划文档 PLAN-...REV.2 的数据章节需同步更新。
