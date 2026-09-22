# VD-DEVICE-MEMORY-20260916 — VoiceDesign 真机内存与 Decoder 配置

对应 ADR-053。完整版见 `CosyVoice3-MNN-Plus/docs/VOICEDESIGN_DEVICE_MEMORY_2026-09-16.md`。

## 任务

让「文字设计音色 → enroll → VoiceProfile → 用该音色朗读」在真机可**稳定重复运行**，同时尽量压低设计耗时。

## 结论

**当前唯一经过完整真机验证的稳定配置：Talker 与 Tokenizer Decoder 均外置权重。**
anon 0.61 GB、峰值 HWM 0.90 GB、Decoder 约 459 s；已在真机完整跑通 2 次闭环（`voice-1789538978527`、`voice-1789540901382`，后者含 `design.json` 资产）。

## 五配置实测

| 配置 | anon | 峰值 HWM | Decoder | 结论 |
|---|---:|---:|---:|---|
| 全内嵌基线 | 1.11 GB | 2.17 GB | 约 20 s | FAIL |
| **全外置（正式）** | **0.61 GB** | **0.90 GB** | **约 459 s** | **PASS** |
| Decoder 内嵌 | 0.76 GB | 1.86 GB | 约 20 s | FAIL，HyperHold |
| Decoder FP16 | — | ~1.13 GB | 约 20 s | FAIL，2693 LSB |
| 分阶段释放 + 内嵌 | 0.97 GB | 1.93 GB | 约 20 s | FAIL |

## 已失败路线（数据留档，禁止重走）

1. **同 RuntimeManager 内分阶段 Module 释放**：释放 6 个 Module 后 anon 仅降 56 MB（575,448 → 518,196 kB）。
2. **FP16 Decoder**：`--fp16` 让文件 457,023,384 → 228,797,140 B（减半），但 int16 逐样本 176400/188160 不等、最大差 **2693 LSB**。
3. **Decoder T 300 → 96**：`t300.mnn` 457,023,384 B vs `t96.mnn` 456,941,592 B（差 0.018%），anon 无变化；数值 Gate 虽过（cos=1.000000000、int16 max 1 LSB），但**内存与磁盘均无收益**。
4. **`RuntimeManager::setCache`（OpenCL kernel 缓存）**：设了之后卡在 decoder 之后不再推进（frame 97 后 10 分钟零输出，RSS 停 1.59 GB，无 `.cache` 生成）。
5. **`USE_CACHED_MMAP` + `EXTERNAL_WEIGHT_DIR`（权重 mmap 缓存）**：MNN 把权重复制进 `<prefix>.static`（默认 `mmapFileSize=1024MB`），实测写到 1,073,741,824 B 即停止推进；且本质是「用磁盘换速度」，与省内存方向相反。

## MNN 源码依据（解释外置 Decoder 慢 23 倍）

```cpp
// source/core/OpCommonUtils.cpp
bool usemmap = (backend->getRuntime()->hint().useCachedMmap > 1);
if ((!usemmap)) { _RebuildExternalOp(externalFile, op, builder); }   // 权重读进内存
```

## 同时交付：UI 逐帧进度

native 每帧写 `<模型目录>/progress.txt`，UI 每秒轮询：
```
正在准备 prompt
prefill 完成（54 token，32 秒），开始逐帧解码
解码中 23 帧（约 1.84 秒音频）· 已用 43 秒 · 约 0.57 秒/帧 · 预计还需 0 分 49 秒
解码结束：共 98 帧（7.84 秒音频），EOS 自然停止。正在生成波形…
```
ETA 用**最近 20 帧滑动窗口**（前几帧含 OpenCL kernel 编译，全程平均会把 ETA 高估十几倍：实测第 5 帧算出"还需 13 分 11 秒"，实际 45 秒）。

## 失败/未完成

- Decoder 459 s 未解决；HTP 部署是独立分支，未开工。
- 跨模型 VoiceIdentity bridge（VoiceDesign representation → CosyVoice speaker embedding）属研究课题，未开工。

## 8. 关键通用修复：enrollment 前的 dither（两条路径都要）

初次验收「参考音频克隆」路径时直接失败：

```
enroll exitCode=37 (说话人特征无效)
```

**根因**：VoiceDesign 生成的音频里，静音是**精确 0 样本**（实测某条 7.76 s 样本中 19.9% 为 0，fbank 774 帧里 12 帧全 0）。enrollment 内 CAMPPlus 的前置 fbank 会取 `log(能量)`，`log(0) = -inf` → NaN 传播 → campplus 输出非有限 → `embedding.size()==192 && finiteVector()` 判定失败 → 返回 37。真实录音有本底噪声，永不精确为 0，所以不会踩到。

**修法**：在 **enrollment 之前无条件**给 WAV 叠加 ±1 LSB 的确定性 dither（LCG，可复现，约 -90 dB 听不见）。两处都要：

| 路径 | 位置 | 说明 |
|---|---|---|
| 文字设计音色 | `CosyVoiceVoiceDesigner.createFromDesign` | 设计产物 → enroll 之前 |
| 参考音频克隆 | `MainActivity.createVoiceProfile` | 解码截取后 → enroll 之前 |

**这不是设计路径的补丁，而是 enrollment 的前置条件**：用户完全可能把合成音频（含 VoiceDesign 产物）当参考音频导入克隆，不加就必然失败。真实录音加 dither 也无害。

**验证结果**：
```
已补 dither -> clone-dither.wav
enroll exitCode=0 (成功)
音色已注册: voice-1789551292376 / 克隆音色B / tokens=125 / frames=250
=== 克隆成功，耗时 4882 ms ===
```
随后用该克隆音色合成一句话通过：`20.00 秒音频 · peak 0.979 · rms 0.120 · finite=True`（PC 侧逐项复核一致）。

**教训**：dither 属"合成音频作为 enrollment 输入"的通用前置处理，不得只加在产生合成音频的那条链上。

## 9. 两条音色创建路径的端到端验收（2026-09-16）

| 路径 | VoiceProfile | 结果 |
|---|---|---|
| 文字设计音色 | `voice-1789540901382`（HOT2） | ✅ 设计(98帧 EOS) → enroll(125 Token) → 注册 → 合成 5.44 s |
| 参考音频克隆 | `voice-1789551292376`（克隆音色B） | ✅ enroll(125 Token, 4.88 s) → 注册 → 合成 20.00 s |

两者共用同一个 `installEnrolledVoiceProfile()`，产出同一种 `VoiceProfile` —— 下游合成侧零改动。

