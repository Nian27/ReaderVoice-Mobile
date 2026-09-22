# F2-A1 / F2-A2 结果：单步基本正确，误差沿深度累积 ⇒ 走 calibration 分支

## F2-A1 canonical teacher-forced 位置隔离（C 用 B 的 state，不用自己的历史）

```
pos | QNN argmax | B argmax | logits cos | hidden cos | K(min/mean)   | V(min/mean)
0   | 3765       | 113      | +0.994158  | +0.993236  | 0.9865/0.9968 | 0.9846/0.9930
1   | 3933       | 3933     | +0.989550  | +0.993412  | 0.9893/0.9952 | 0.9324/0.9745
2   | 1076       | 876      | +0.998482  | +0.997523  | 0.9971/0.9992 | 0.9737/0.9945
4   | 1133       | 1133     | +0.999004  | +0.997607  | 0.9978/0.9993 | 0.9793/0.9941
8   | 314        | 2247     | +0.991185  | +0.992434  | 0.9777/0.9935 | 0.3147/0.8945
16  | 1637       | 1637     | +0.979002  | +0.985335  | 0.9717/0.9920 | 0.9540/0.9811
32  | 3962       | 689      | +0.939275  | +0.952796  | 0.9749/0.9929 | 0.8939/0.9651
64  | 271        | 3551     | +0.988125  | +0.968815  | 0.9868/0.9975 | 0.9827/0.9920
131 | 255        | 3571     | +0.965117  | +0.929393  | 0.9483/0.9902 | 0.8435/0.9561

对照：C 自递归 rollout 的 prefill logits cos = 0.790（F1 门）
```

## 判读（按预设的三分支）

```
canonical single-step 全部高 (0.939~0.999)   ✅
self-rollout 只有 0.790                        ❌
=> 命中第一分支：calibration / state distribution 问题
   即：不是某个算子坏，而是每步的小量化误差在 132 步状态递归中累积漂移
```

pos=0 就到 0.9941（不是 1.0）说明**基础 recipe 有约 0.6% 的本底误差**；
此后 cos 在 0.939~0.999 之间波动，**没有出现「某个位置单步就崩」**，所以不是 position-dependent 算子问题。

## ⚠ 一个必须写进 Gate 判据的发现

**cos 高不等于决策对**：

```
pos0  cos 0.9941  但 argmax 3765 vs 113      <- 完全不同
pos8  cos 0.9912  但 argmax 314  vs 2247
pos32 cos 0.9393  但 argmax 3962 vs 689
pos64 cos 0.9881  但 argmax 271  vs 3551
pos131 cos 0.9651 但 argmax 255  vs 3571    <- 第一帧 semantic 直接错
```

9 个位置里只有 pos1/4/16 的 argmax 与 B 一致。
⇒ **后续 Gate 必须同时要求 argmax 一致（或 top-1 命中率），不能只看 cosine。**
这也解释了为什么 P/Q 听起来是噪音：单步 cos 看着还行，但每一帧的 semantic 都在错，
再经 fast AR 展开成 10 个 codebook，误差被放大成完全不同的音。

## F2-A2 逐层 K/V sentinel（首个坏层定位）

`pos=131`（最差）：

```
layer |   K cos  |   V cos
  0   | 0.99995  | 0.99995
  1   | 0.99997  | 0.99928
  2   | 0.99992  | 0.99554
  3   | 0.99510  | 0.99395
  4   | 0.99903  | 0.98611  V
  5   | 0.99648  | 0.97674  V
  6   | 0.96807  | 0.84350  K V   <- 第一个明显坏层
  7   | 0.97670  | 0.90503  K V
  8   | 0.99900  | 0.93397  V
  9   | 0.94828  | 0.84650  K V
 10   | 0.97371  | 0.91616  K V
 11   | 0.97204  | 0.94516  K V
 12   | 0.98872  | 0.94605  K V
 13   | 0.99193  | 0.97470  V
 14   | 0.99738  | 0.97251  V
 15   | 0.99580  | 0.97785  V
 16   | 0.99422  | 0.95674  V
 17   | 0.99600  | 0.98760  V
 18   | 0.99828  | 0.99230
 19   | 0.99873  | 0.99660
 20   | 0.99905  | 0.99467
 21   | 0.99702  | 0.98970  V
 22   | 0.98899  | 0.92111  K V
 23   | 0.98937  | 0.89513  K V
```

**规律（pos 8 / 32 / 131 三处一致）：**

1. **layer 0~3 干净**（K/V 都 ≥0.99）
2. **从 layer ~6 开始出现系统性误差，并且沿深度不再恢复**（L22/L23 又掉到 0.89~0.99）
3. **V 比 K 差得多**（pos131: V 最低 0.8435 vs K 最低 0.9483）
4. pos=8 的 L2 V=0.3147 是个例异常（pos32/131 同层正常）⇒ 位置相关的极端值，非普遍算子缺陷

⇒ 典型的 **「激活量程随深度增长、固定 calibration 范围裁剪」** 形态。
不是某一层投影算子坏（那样只有一层掉），而是**误差逐层累积**。

## F2-B 建议（不要先全图提位宽）

```
主因：calibration 覆盖不到真实 rollout 的深层激活分布
1. 用真实 rollout 状态重做 calibration     <- 本轮已产出可直接用
   runs/appv1-realtext/canon/pos{0,1,2,4,8,16,32,64,131}/ 里有 132 步真实 x/cache/mask
   以及 B 的全部 K/V/logits/hidden 参考值
2. 局部提精度：优先 V 通路（deep layers 的 v_proj / attention 输出），而非全图 A16W16
3. Gate 换成双判据：logits cos >= 0.99  且  argmax 一致率 >= 0.95
```

## 复现命令

```powershell
$py = "C:/Users/Administrator/AppData/Local/Programs/Python/Python310/python.exe"
$env:PYTHONPATH=""
& $py E:/AndroidStudioProjects/audio8tts-mnn/scripts/f2a1_phase1.py     # 导出 canonical 状态 + B 参考
& $py E:/AndroidStudioProjects/audio8tts-mnn/scripts/f2a1_phase2.py     # 推给手机 QNN、拉回、对拍
& $py E:/AndroidStudioProjects/audio8tts-mnn/scripts/f2a2_layers.py     # 逐层 K/V 表
```

## 状态表

```
F0-A/B  语义桥 A==B(微图)                   ✅
F1      deployed Slow QNN 自递归            ❌ 0.790
F2-A1   canonical teacher-forced 位置隔离    ✅ 完成 -> 单步 0.939~0.999，属状态累积分支
F2-A2   首个坏层定位                         ✅ 完成 -> layer0~3 干净，~L6 起累积退化，V 比 K 差
F2-B    重做 calibration（真实 rollout）      ⏭ 下一步
F2-C    rollout Gate（cos>=0.99 且 argmax 一致率>=0.95）  ⏭
Fast F0-C                                    ⏸ Slow 修完后
```

## 端侧新增能力（已编译进 APK）

```
nativeCanonicalStep(dir)  <- 用外部给定 x/rope/mask/cache 跑一次 slow step
                             读 x.raw rope.raw mask.raw cache_{key,value}_<l>.raw
                             写 out_logits.raw out_hidden.raw out_kd_<l>.raw out_vd_<l>.raw
触发：files/CANON marker（不需要 UI 交互）
引擎：Audio8Engine::slowStepRaw / readDelta
```
