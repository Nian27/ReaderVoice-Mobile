# TASK080_08B_ERROR_ANALYSIS — Qwen3.5-0.8B（post-trained）zero-shot 错误分桶

**测试集**：fixture-C（4 人场景），SPEAKER gold=9 / IDENTITY gold=1 / VOICE_STATE gold=2。

## 1. Speaker 逐样本（gold 9，正确 1）

| # | 文本 | gold | pred | 类型 | 错误归因 |
|---|---|---|---|---|---|
| 1 | “谁在那里！” | 赵铁柱 | 赵铁柱 ✓ | 显式 cue（喝道） | — |
| 2 | “是我。” | 钱二 | 赵铁柱 | 后置 cue（钱二答道） | 后置 cue 盲区 + 近因偏差 |
| 3 | “二位别吵了。” | 孙三娘 | 钱二 | 显式 cue（笑道） | **近因偏差**：选 distance=1 候选，忽略 RECENT 中 "孙三娘笑道：" |
| 4 | “钱二，你站住。” | (no gold) | 孙三娘 | 无 cue（哼了一声） | 强猜（gold 外） |
| 5 | “我偏不。” | 钱二 | **OUT_OF_CANDIDATE** | 后置 cue | 输出裸名 "钱二" 而非 id —— 唯一协议违规 |
| 6 | “这位客官，可是要住店？” | 周老板 | 钱二 | 显式 cue（问） | 近因偏差 |
| 7 | “住店？先赔我的酒钱！” | 赵铁柱 | 周老板 | 显式 cue（说） | 近因偏差 |
| 8 | “小心！” | 孙三娘 | 赵铁柱 | 显式 cue（尖声叫道） | 近因偏差（distance=3 候选） |
| 9 | “游戏，开始了。” | 宿主 | 赵铁柱 | 显式 cue（冷冷道） | 近因偏差 |
| 10 | “遵命，主人。” | 控制者 | 宿主 | 显式 cue（低声道） | 近因偏差 |

## 2. 分桶统计（§36）

| Bucket | 样本 | 正确 | 结论 |
| --- | ---: | ---: | --- |
| explicit cue（候选榜首=真值） | 1 | 1 | ✓ 能跟随榜首 cue |
| explicit cue（真值非榜首） | 6 | 0 | ✗ **候选顺序 > 文本证据** |
| postposed cue | 2 | 0 | ✗ 盲区 |
| 协议违规 | 1 | — | 裸名回显（1 次） |

## 3. 其他任务

- IDENTITY：gold=宿主≠控制者（hard negative）。0.8B 答 UNKNOWN → 正确率 0，但**未违反硬负例**（没敢说 SAME）——保守倾向可接受。
- VOICE_STATE：2 gold（孙三娘 START / 赵铁柱 NONE）→ 1 对（START ✓）。START_f1=0.67。
- 强猜倾向：`unknown_on_ungolded=0.0`——对无证据样本从不输出 UNKNOWN。

## 4. 结论（对 TASK-090 的输入）

1. 协议可训练（91% strict）——LoRA 目标之一是 100% 候选服从 + 消除裸名回显。
2. 近因偏差是主错误类：RECENT 中 cue 与候选顺序冲突时必输。LoRA 需大量"cue≠榜首"负采样。
3. 后置 cue 完全盲区——训练数据已含 POSTPOSED_SPEECH_CUE gold（fixture + 真实书），LoRA 可学。
4. 无 UNKNOWN 能力——需在 SFT 中加入 UNKNOWN 样本（TURN_TRACKING/无 cue 段）。
