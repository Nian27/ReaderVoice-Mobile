# M1 Round 7 — 发现并修复评测工具链的第二个致命 bug（RAS 缺失）

日期：2026-09-16    状态：工具链已正确；12L free-running 确认失败；DAgger 进行中

## 1. 元凶：`rollout_slow` 没有实现官方 RAS

官方 `_sample_semantic` 是**每帧抽两次**：

    normal = sample(T=0.7, top_p=0.9, top_k=50)
    high   = sample(T=1.0, top_p=0.9, top_k=50)
    若 normal 落在最近 ras_window_size(10) 帧出现过 -> 改用 high

`config.json` 里有 `ras_temperature: 1.0 / ras_top_p: 0.9 / ras_window_size: 10`。
`m0v2_gpu_rollout.py`（生成 teacher 数据）实现了 RAS，**但 `rollout_slow` 没有**。

### 验证（决定性）

用 `rollout_slow` 跑 **teacher 自己**，与 M0v2 存储的 teacher 对比：

    修复前: first64 sem match = 1~5 / 64      uniq = 67 / 224 / 180 / 215
    修复后: first64 sem match = 64 / 64         uniq = 232 / 227 / 191 / 233
            （4/4 样本全部逐帧一致，frames 也完全对上）

=> **修复后 `rollout_slow` 精确复现 teacher 数据。工具链第一次真正正确。**

### 影响

**此前所有自由生成评测（含 student CER 0.9634）都是在无 RAS 的坏采样器下做的，全部无效。**
这是继 Round 3（数据截断）、Round 4（繁简转换）之后**第三个**评测 bug。

## 2. 顺带修掉的 EOS bug

旧代码 `sem = SB_ID + sampler(...)`：当 sampler 返回 4096（EOS）时会得到 **155774 这个非法 token**，
既不停止也不合法。已改为 `sem = int(LOGIT_IDX[si])` 并在 EOS 处 break。
（实测本轮样本未触发，属潜伏 bug。）

## 3. 修复后的有效测量

### 逐帧跟踪（student vs teacher，同 seed + 同 RAS）

    MEAN first_div = 4.2 帧
    MEAN match@20 = 0.28     match@50 = 0.12     match_all = 0.03

### 任务级 Gate（Whisper CER + t2s）

    MEAN CER[teacher] = 0.1758    （teacher 通顺）
    MEAN CER[student] = 1.3207    （student 乱码；CER>1 表示插入远多于参考）

    实例：
      TEACH: 周青山看着他眼底多了几分复杂…学校今年有一个贫困声特训推荐名额…  CER=0.105
      STU  : 其中一位同人接触了大征情,便得对接线,让刺激丹起冰从落…            CER=0.982

### student uniq semantic 的变化

    无 RAS: 124 / 162 / 140 / 166 / 127 / 129 / 140 / 169
    有 RAS: 181 / 216 / 159 / 188 / 180 / 154 / 230 / 209
    teacher: 232 / 227 / 191 / 233

RAS 显著改善多样性，但**没有救回内容**。

## 4. 结论

**12L student 在自由生成下确实失败**，且这是在正确的数据 + 正确的采样器下测得的。
teacher-forced `cos_logits=0.99983` 对自由生成质量毫无预测力。

## 5. 顺带完成

- 45 条全长度数据重生成（GPU 路径，7~10x 提速）
  `dataset_digest = e974931223aabdf08342ccb8d4d49423d3d20b6fd1ba2ba478254b23f171f135`
  frames 41/458/995，语音速率 0.182 s/字（正常）。旧 632dec58 作废。
- 12L 在全长度数据上重训：cos_logits 0.99983 / cos_hidden 0.99730 / argmax 0.765

## 6. 正在进行

    DAgger（修正 cache mask + RAS 数据，maxroll=48，900 步）  step 525/900

## 7. 下一轮

1. DAgger 完成后 → 用已修正的工具链重测 tracking + CER
2. 若 DAgger 不足：把 RAS 的一致性也纳入训练目标；或提高 rollout 比例；或序列级 beam/对比目标
3. 三个评测 bug 的教训应写成纪律（见下）

## 8. 新增纪律

**任何评测工具在用之前，必须先证明它能复现已知良好的基线。**
本轮正是靠「rollout_slow(teacher) 必须复现 M0v2 teacher 数据」这条对照才发现 RAS 缺失。
前三轮（数据截断 / 繁简 / RAS）全部是同类问题：工具错了，却被当成模型结论。
