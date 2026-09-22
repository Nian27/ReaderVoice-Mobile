# TASK080_2B_ERROR_ANALYSIS — Qwen3.5-2B（post-trained）zero-shot 错误分桶

**测试集**：fixture-C，与 0.8B 完全同一 locked test / prompt / 生成参数（§20：只换模型）。

## 1. Speaker 逐样本（gold 9，正确 5）

| # | 文本 | gold | pred | 类型 | 错误归因 |
|---|---|---|---|---|---|
| 1 | “谁在那里！” | 赵铁柱 | 赵铁柱 ✓ | 显式 cue（喝道） | — |
| 2 | “是我。” | 钱二 | 赵铁柱 | 后置 cue（钱二答道） | **后置 cue 忽略**：RECENT 里 "钱二答道。" 就在眼前仍选近因 |
| 3 | “二位别吵了。” | 孙三娘 | 钱二 | 显式 cue（笑道） | **近因偏差**：distance=1 的钱二压过 "孙三娘笑道：" |
| 4 | “钱二，你站住。” | (no gold) | 钱二 | 无 cue（哼了一声） | gold 外；巧合命中真值方向 |
| 5 | “我偏不。” | 钱二 | 钱二 ✓ | 后置 cue | 本轮对上（与 #2 矛盾 → 不稳定） |
| 6 | “这位客官，可是要住店？” | 周老板 | 周老板 ✓ | 显式 cue（问） | — |
| 7 | “住店？先赔我的酒钱！” | 赵铁柱 | 周老板 | 显式 cue（说） | 近因偏差（周老板 distance=1） |
| 8 | “小心！” | 孙三娘 | 孙三娘 ✓ | 显式 cue（尖声叫道） | — |
| 9 | “游戏，开始了。” | 宿主 | 宿主 ✓ | 显式 cue（冷冷道） | — |
| 10 | “遵命，主人。” | 控制者 | 宿主 | 显式 cue（低声道） | 近因偏差（宿主 distance=1） |

## 2. 分桶统计（§36）

| Bucket | 样本 | 正确 | 结论 |
| --- | ---: | ---: | --- |
| explicit cue（榜首） | 2 | 2 | ✓ |
| explicit cue（真值非榜首） | 4 | 2 | ✗ 仍受候选顺序干扰（2/4 失败） |
| postposed cue | 2 | 1 | ✗ 不稳定（1/2） |
| 协议违规 | 0 | — | ✓ 100% 候选服从 |

## 3. 其他任务

- IDENTITY：gold=宿主≠控制者（hard negative）→ **答 DIFFERENT ✓**（0.8B 答 UNKNOWN）。硬负例服从正确。
- VOICE_STATE：与 0.8B 同为 1/2（START ✓ / NONE ✗——孙三娘 p3 的 phase-only 被猜成 START）。2B 未带来 VoiceState 增益（gold 仅 2 样本，不可下结论）。
- `unknown_on_ungolded=0.0`——与 0.8B 相同的强猜倾向。

## 4. 0.8B vs 2B 关键差异

| 维度 | 0.8B | 2B |
| --- | --- | --- |
| Speaker Acc | 11.1% | 55.6%（+44.4pp） |
| Strict JSON / 候选服从 | 90.9% / 90.9% | 100% / 100% |
| 显式 cue（非榜首） | 0/6 | 2/4 |
| Identity hard negative | UNKNOWN（保守） | DIFFERENT（正确） |
| 后置 cue | 0/2 | 1/2 |

**容量增益真实存在**（协议 + 基本 cue 服从 + 硬负例推理），但 2B 仍受近因偏差主导。
→ 与 TASK080_MODEL_BASELINE §38 判定一致：**情况 B**。2B 适合做 Teacher/Upper Bound；Student 仍走 0.8B-Base LoRA（TASK-090）。
