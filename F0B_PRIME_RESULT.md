# F0-B' 结果：cache 布局 bug 定位（纯 FP16 语义桥）

## 1. position=0（空 cache）—— **PASS**

```
官方 FP16 pos0: logits argmax=2104 max=5.7459 rms=2.5117 | hidden rms=1.7432
微图    logits cos = 0.983047   max=5.6367 rms=2.4683
        slow_hidden cos = 0.989583
        key_delta  mean = 0.9944  (L0=1.0000 L1=1.0000 L23=0.9982)
        value_delta mean = 0.9695 (L0=0.9991 L1=0.9770 L23=0.9764)
```

=> **微图的 x / rope_position / 主干网络是对的。** (残差 ~1-2% 来自权重来源不同：
官方面是 INT4 反量化来的 FP16，微图是 BF16 safetensors。)

## 2. 逐位置 + 两种 cache 布局 —— **找到 host bug**

```
pos | layout     | logits cos | hidden cos | kdelta L0 | vdelta L0
  1 | headslot   | 0.954744   | 0.969959   | 0.999874  | 0.997776
  1 | slothehad  | 0.954744   | 0.969959   | 0.999874  | 0.997776
  2 | headslot   | 0.854682   | 0.933373   | 0.999515  | 0.992829
  2 | slothehad  | 0.660516   | 0.819625   | 0.999515  | 0.992829
  4 | headslot   | 0.833577   | 0.940557   | 0.998046  | 0.993702
  4 | slothehad  | 0.552175   | 0.894512   | 0.998046  | 0.993702
  8 | headslot   | 0.910257   | 0.953068   | 0.992431  | 0.999398
  8 | slothehad  | 0.557684   | 0.802031   | 0.992431  | 0.999398
 16 | headslot   | 0.959730   | 0.966985   | 0.972379  | 0.992334
 16 | slothehad  | 0.818065   | 0.884771   | 0.972379  | 0.992334
```

- `headslot` = 微图构造者 `fixed_inputs` 的约定：`bank[:, :, window-1-position:, :]`，即 `[HEADS, slots, HEAD_DIM]`
- `slothehad` = host `audio8_engine.h::buildBank` 的约定：`((SLOTS-n+p)*HEADS + h)*HEAD_DIM`，即 `[slots, HEADS, dim]`

**`headslot` 压倒性更好（pos=4: 0.834 vs 0.552；pos=8: 0.910 vs 0.558）**
⇒ **host 的 `buildBank`/`buildBankVal` 把 cache 的 slot 轴与 head 轴写反了**，端侧一直在喂被打乱的 cache。

旁证：`buildBank` 上方自己的注释写的就是 `[A8_N_HEADS, A8_SLOW_CACHE, A8_HEAD_DIM]` —— **注释是对的，代码是错的**。

另外 `key_delta L0` 在所有位置都是 0.99+（它不依赖 cache）⇒ 当前 token 的 K/V 通路本身没问题，
问题精确定位在 **cache 喂入**。

## 3. 已做的修复（host 侧，单变量）

`p1-runner/audio8_engine.h`：SlowCache / FastCache 的 `buildBank` 与 `buildBankVal` 共 4 处

```cpp
- int dstOff = ((A8_SLOW_CACHE - n + p) * A8_N_HEADS + h) * A8_HEAD_DIM;   // [slot, head, dim] 错
+ int dstOff = (h * A8_SLOW_CACHE + (A8_SLOW_CACHE - n + p)) * A8_HEAD_DIM; // [head, slot, dim] 对
```

FastCache 同改（待 F0-C 确认）。证据写进代码注释。

## 4. 仍存在的残差（下一步）

用正确的 `headslot` 布局后，cos 仍只有 0.83–0.96，且**非单调**（pos2 0.855 / pos4 0.834 / pos8 0.910 / pos16 0.960）。
`key_delta`/`value_delta` 当前 token 都是 0.99+ ⇒ cache 内容本身对，剩余差异在 **cached K 的旋转约定或 mask**。
候选（按可能性）：

1. 微图对 **cached K 也做了一次 RoPE**（即期望 cache 里是 pre-RoPE K），我们喂了 post-RoPE K
2. `rope_position` 的 bf16_round（构造脚本 `rope_matrix` 对 cos/sin 做 bf16 舍入，我没做）
3. mask 语义（0/1 之外的第三种约定）

## 5. 路线状态

```
F0-A  官方 FP16 full chain                     ✅ 用户确认可出声（GOLDEN）
F0-B  A ↔ B 纯 FP16 语义桥（position=0）        ✅ PASS 0.983/0.990
F0-B' 逐位置隔离 cache 通路                    ✅ 定位 host buildBank 轴序 bug，已修
F0-B'' 消除 cache 残差（0.83-0.96 -> >0.999）   🔥 下一步
F0-C  Fast 微图同法对拍                        
F1    B(FP16 微图) ↔ C(手机 A16W8 QNN)         仅当 F0-B''/F0-C PASS
F2    重转 QNN / calibration                   仅当 F1 FAIL
V2    新 Slow 设计（2048+multi-token+valid_prefix）冻结，不在本轮路径
```

## 6. 本轮新增脚本

```
scripts/micrograph_contract.py   微图输入/输出契约导出
scripts/f0_ab_slow.py            A↔B 单帧对拍（含 BANK_LAYOUT 开关）
scripts/f0_ab_pos0.py            position=0 隔离
scripts/f0_ab_posn.py            逐位置 × 两种布局矩阵扫描
```
