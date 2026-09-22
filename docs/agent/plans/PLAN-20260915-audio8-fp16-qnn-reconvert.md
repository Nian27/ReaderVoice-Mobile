# PLAN-20260915-audio8-fp16-qnn-reconvert

## 1. 任务
用已验证的官方 FP16 ONNX 链路重导 Audio8 端侧 QNN 图，使手机 App 产出可懂语音。

## 2. 背景（本轮已确认的根因）

用户试听判定：

| 产物 | 含 INT4 计算 | 判定 |
|---|---|---|
| L_FP16_fullChain.wav | 否 | **可懂人话** |
| B_officialCodec_on_realReference75.wav | 否 | 可以 |
| A/E/F/G（同协议/prompt/codec，仅差 INT4） | 是 | 不行 |
| D（手机 QNN） | 是 | 不行 |

=> **本机 ONNX Runtime 的 INT4 执行是坏的**；一切 FP16 路径正常。
旁证：ORT_DISABLE_ALL 下 INT4 输出与输入无关（T=30/T=132 logits md5 同为 9dd12252860d）；
ORT 1.20/1.21/1.22 → 256 帧不收敛 46Hz 蜂鸣；1.19.2 → GatherBlockQuantized 未注册。

## 3. 目标 / 验收 Gate

| Gate | 判据 |
|---|---|
| G1 | 同一 prompt 下 QNN slow prefill logits vs 官方 FP16 slow logits **cos >= 0.99** |
| G2 | greedy 下 QNN fast 逐帧 codes 与官方 FP16 golden **逐帧完全一致** |
| G3 | QNN codec 输出 vs 官方 FP16 codec 输出 **cos >= 0.99** |
| G4 | 端侧整链 WAV 用户试听 = 可懂人话（最终判据） |

当前基线（本轮实测，参考可信）：

```
官方 FP16 : argmax=3571  max=32.257  rms=9.136
端侧 QNN  : argmax=1766  max=15.720  rms=5.751
G1 现况   : cos = 0.5068   (slow_hidden cos = 0.4071)   -> FAIL
```

## 4. 关键已验证事实（不要重复推翻）

1. 8 个模型文件与 HF LFS 官方 sha256 **8/8 MATCH**
2. prompt 与官方 `prompt.py::PromptBuilder.build` **逐 token 一致**
3. tokenizer vocab/merges/added_tokens 与官方 **完全相同**
4. 官方 KV cache = **valid_prefix（按绝对位置写 `cache[:,:,pos,:]=delta`）**，实测 cos=1.000000；左移窗口 cos=0.925844
5. 官方 runtime 用 `ORT_ENABLE_ALL`
6. 官方采样器 = temp0.7 / top_p0.9 / top_k50 + Gumbel，slow 每步采样两次（normal + high@1.0，重复则用 high）
7. 官方 `fast` 也用同一个采样器（**不是 greedy**）

## 5. 不变量

- 不改 raw source；不破坏用户数据
- 保留 FP16 参考链路（`models_fp16/` + `third_party/arktts_official/`），它是唯一的正确性基准
- 一次只改一个主要变量；每个 Gate 单独跑
- 不改 Gate 以通过测试

## 6. 步骤

### F1-A 固化 golden 参考集（先做，成本最低）

```powershell
$py = "C:/Users/Administrator/AppData/Local/Programs/Python/Python310/python.exe"
$env:PYTHONPATH=""
foreach ($k in 0,1,2) {
  $env:CELL_DIR="E:/AndroidStudioProjects/audio8tts-mnn/runs/appv1-realtext/cells/k$k"
  $env:MODEL_DIR="E:/AndroidStudioProjects/audio8tts-mnn/models_fp16"
  & $py E:/AndroidStudioProjects/audio8tts-mnn/scripts/run_official_runtime.py
}
```

前置：设备上跑 `TEXT_K=k` 导出 `prompt_*.txt`（k=0 已就绪于 `cells/RG/prompt.txt`；k=1,2 见 §9 已知问题）。

### F1-B 把 FP16 ONNX 转成 QNN（权重 W8 / act A16）

```powershell
& E:/AndroidStudioProjects/audio8tts-mnn/scripts/02-convert-qnn.ps1 \
    -Component slow_ar -InputModel E:/AndroidStudioProjects/audio8tts-mnn/models_fp16/slow_ar_fp16.onnx \
    -ActBitwidth 16 -WeightsBitwidth 8 -BiasBitwidth 32 \
    -CalibrationInputList <校准输入清单> -UsePerChannelQuantization
```

要点（与旧图的三处不同，必须同时满足）：

1. **不要左移 cache**：graph 只输出 `key_delta_i/value_delta_i`，写回由 host 按绝对位置做
2. **codes 支持多 token**：`[1, 11, sequence]`，prefill 一次前向（旧的逐 token × 132 是错的）
3. **序列长度 2048**（旧的 255 槽窗口是错的）

### F1-C 端侧 runtime 改动

- `audio8_engine.h::slowStep` 增加 `positions` 参数，cache 写回改 `cache[:,:,pos,:]=delta`
- prefill 改为单次多 token 调用；`input_pos = arange(T)`
- fast cache 同样位置索引（pos 0..9）
- 采样器已就绪：`MODE_OFFICIAL`（含双采样与重复判据）

### F1-D 逐 Gate 验证
`G1` → `G2` → `G3` → `G4`，任一 FAIL 即停并记录。

## 7. 风险 / 回滚

| 风险 | 应对 |
|---|---|
| 1.74 GB fp16 权重过大 | W8 量化后约 370 MB（与现有 slow.bin 366 MB 同量级） |
| 2048 槽 attention 在 HTP 上过慢/超内存 | 先按 512 槽验证正确性，再逐步放大 |
| 校准数据不足导致 W8 掉点 | 用 FP16 链路生成校准集；必要时 W8 + per-channel |
| 回滚 | 保留现有 `slow.bin` / `fast.bin` / `codec.bin` 不动，新产物写入独立目录 |

## 8. 产物

```
models_fp16/                                    FP16 官方等价模型（已验证可出声）
third_party/arktts_official/                    官方 runtime.py / prompt.py 原文
scripts/int4_to_fp16.py                         INT4→FP16 反量化（自适应 bits/zp 打包/zp 偏置）
scripts/run_official_runtime.py                 FP16 参考链路驱动
scripts/gate_c2_fp16.py                         G1 测量
runs/appv1-realtext/REPORT_20260915_rootcause_final.md
listen-kit-20260915/                            用户试听清单 A/B/D/E/F/G/H/I/J/L + GOLDEN
```

## 9. 已知问题 / 失败记录

1. **设备 init 变慢**：15:15 起 `TEXT_K=1/2` 导出跑不完 120 s；`qnn.log` 显示 context 反序列化成功、graph retrieve 成功，init.log 仍在增长 -> 疑似 DSP 侧资源/热状态问题。待办：重启手机或冷却后重跑。
2. `K_officialRuntime_ORT119.wav` 已删除（1.19.2 加载失败，文件是上一轮的残留副本）。
3. 我曾误判「INT4 执行不是根因」并撤回结论；撤回依据（reference 段 next-token 命中率）**方法本身不成立**（reference 段是上下文，未必是生成目标）。用户试听是唯一有效判据。

## 10. 进度

- [x] 确认根因：INT4 ONNX 在本机执行错误（用户试听）
- [x] 建立 FP16 参考链路并验证可出声（L）
- [x] 反量化实现（自适应 bits / zp 打包 / zp 偏置自动判定）
- [x] G1 基线测量（cos 0.5068 FAIL）
- [ ] F1-A golden 参考集（k=0 就绪，k=1,2 待设备恢复）
- [ ] F1-B FP16 ONNX → QNN 转换
- [ ] F1-C 端侧 runtime 改 valid_prefix + 多 token prefill
- [ ] G1/G2/G3/G4

## 11. 下一步（立即可做）

1. 重启手机 / 冷却后重跑 `scripts/dump_prompts_k12.ps1` 拿 k=1,2 的 prompt 矩阵
2. 跑 F1-A 固化三个文本的 golden codes + WAV
3. 准备 F1-B 的校准输入清单（用 FP16 链路的中间 tensor 即可）
