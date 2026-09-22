# APP-A/B buildBank 修复后的真机结果 + F0-B'' 进展

## 1. 修了什么（单变量，context/tokenizer/prompt/sampler/codec 全不变）

`p1-runner/audio8_engine.h` SlowCache/FastCache 的 `buildBank`/`buildBankVal` 共 4 处：

```cpp
- int dstOff = ((A8_SLOW_CACHE - n + p) * A8_N_HEADS + h) * A8_HEAD_DIM;    // [slot, head, dim] 错
+ int dstOff = (h * A8_SLOW_CACHE + (A8_SLOW_CACHE - n + p)) * A8_HEAD_DIM; // [head, slot, dim] 对
```

依据：纯 FP16 A/B 对拍（同一官方状态，只换布局）

```
pos | headslot (对)      | slothehad (host 旧)  | 差
  2 | logits 0.855       | logits 0.661         | +0.19
  4 | logits 0.834       | logits 0.552         | +0.28
  8 | logits 0.910       | logits 0.558         | +0.35
 16 | logits 0.960       | logits 0.818         | +0.14
```

## 2. 真机结果：**行为确实变了，但仍不可懂**

同一文本 / 同 seed / 同 preset voice / TEXT_K=0 / GREEDY。

```
修复前 step=0: top1=1766(15.720) top2=2076(15.682) margin=0.0381  eos=11.345
修复后 step=0: top1=2946(14.241) top2=501(14.214)  margin=0.0270  eos=11.215
修复后 step=1: top1=2304(20.746) top2=2946(20.336) margin=0.4101  eos=11.356
修复后 step=2: top1=944(18.402)  top2=501(17.958)  margin=0.4440  eos=11.728
修复后 step=3: top1=501(21.494)  top2=645(21.494)  margin=0.0000  eos=10.548   <- top1==top2 完全并列
```

帧数 27；cb0 唯一值 23/27。但 TRACE 显示 **从 step≈20 起收敛成固定点循环**：

```
step=20..29  rel=87  cb=87 66 27 19 43 556 238 {410|112} 571 917   <- 反复
```

官方 FP16 golden 第一帧 codes = [3571, 843, 990, 923, 539, 856, 476, 344, 924, 1019]；
手机第一帧 = [2946, 599, 957, 18, 858, 749, 121, 968, 506, 621] ⇒ **从第 0 帧就分叉**。

**结论**：buildBank 轴序 bug 是真的（已修，行为可见改变），但它**不是唯一根因**。
剩余原因仍指向 cache 语义残差（F0-B''）。

## 3. F0-B'' 结果

### 假设 2（rope 的 bf16_round）—— **排除**

```
pos | bf16=False        | bf16=True
  1 | 0.954744          | 0.954704
  4 | 0.833577          | 0.834083
 16 | 0.959730          | 0.959725
 64 | 0.851458          | 0.850360
131 | 0.695282          | 0.689214
```

### 关键规律：**cos 随历史长度衰减**

```
pos=1   logits 0.955   hidden 0.970
pos=16  logits 0.960   hidden 0.967
pos=64  logits 0.851   hidden 0.812
pos=131 logits 0.695   hidden 0.757
```

误差随 cache 条目数累积 ⇒ **符合“微图对 cached K 又做了一次旋转”的假设**（双重旋转）。

### 假设 1（cached K 是 pre-RoPE）—— 脚本有 bug，**尚未得到结论**

测试脚本 `scripts/f0_bb_prerope.py`：由 `k_pre = k_delta @ R(p)^T` 反解 pre-RoPE K 再喂 cache。
崩在 `kd_hist` 收集：`off_run(pos)` 只跑了 2,4,8,16,64,131 这些跳点，
每层只记录了**最后一次**的 key_delta，而索引需要 0..pos-1 全部。

修法：`off_run` 必须**逐位置 0..131 连续调用**（每步 append 一次 kd_hist），再在目标 pos 上做对拍。

### 旁路证据（要注意）

同一次运行里 `pos=2 postrope` 得到 **0.9778/0.9821**，而上一支脚本同配置得到 **0.8547/0.9334**。
两者理论等价，说明**其中一支的桥还有未察觉的差异**，在做结论前必须先让这两支自洽。
（最可能：`f0_ab_posn.py` 与 `f0_bb_prerope.py` 的 mask 起点或 bank 填充在 cnt<pos 时不一致。）

## 4. 状态表（更新）

```
F0-A    官方 FP16 full chain                ✅ GOLDEN（用户确认可出声）
F0-B0   position=0 微图结构一致性           ✅ STRUCTURAL PASS  logits 0.983 / hidden 0.990
F0-B1  cache layout                         ❌ BUG FOUND  ✅ FIXED  ✅ 真机行为已改变
APP-A/B buildBank fix 真机试听               ⏳ 需要用户听 M_/N_ 两个 wav
F0-B2  residual cache semantics             🔥 进行中
        - bf16 rope                          ✅ 已排除
        - pre-RoPE cache                     ⏳ 脚本待修
        - mask 语义                          ⏳ 未测
        前置：先让两支对拍脚本自洽            🔥 最高优先
F0-C   Fast 微图同法对拍                     
F1     B(FP16 微图) ↔ C(手机 A16W8 QNN)       仅当 F0-B2/F0-C PASS
F2     重转 QNN / calibration                仅当 F1 FAIL
```

## 5. 试听清单新增（工作区根目录 listen-kit-20260915/）

| 文件 | 内容 |
|---|---|
| M_fixedAPK_phoneCodes_officialCodec.wav | ⚠ 注意：这是**旧(14:04)的 codes** 用官方 codec 解的，因为拉取时机太早 |
| N_fixedAPK_deviceQNNcodec.wav | ⚠ 同上，旧文件 |

**这两个文件作废**（设备侧 ar_codes/out_app 的 mtime 仍是 14:04，我拉早了）。
修复后运行的真实 codes 需要重新拉取（设备 15:33 只写了 prompt 与 prefill_logits，
ar_codes/out_app 在 15:36 拉取时尚未落盘，运行仍在进行且较慢）。
