# F5 — 慢图量化四臂设备实测：三条量化路线均无收益，其中两条数值不可用

日期：2026-09-18
设备：Honor BKQ-AN90 / SM8850 / HTP V81 / QAIRT 2.48.40.260702
设备地址：10.40.137.201:37869（无线调试；6 个脚本的默认值已改为 env:A8_DEVICE 单一来源）
状态：**CLOSED（质量结论确定；计时结论受噪声限制，已按实修正）**

## 一、背景

F4_PERF_SPLIT.md 第六节列出的下一步里，三条是慢图量化：

    #1 slow A8W8（act 16->8）   判据：若显著变快 => A16 引入了额外转换
    #2 slow per-tensor 量化     判据：per-channel 反量化若未向量化会压死带宽
    #3 slow W4（W8->W4）        判据：若时间近似减半 => 坐实带宽受限

三条的 context 都已在 2026-09-15 18:35~21:01 构建完成，**但从未在设备上测量过**。
本次把它们与 A16W8 基线在**同一 harness、同一输入、同一位置**下测完。

## 二、质量结果（确定性，可靠）

position-000，100 次重复，输出 logits[4097] 对 FP16 ONNX 参考（min -8.234 max 8.773）：

    臂                                          cos        argmax   relL2    ctx_MB
    a16w8 基线（per-channel）                  0.993341     1.0    0.1159    349.0   OK
    a16w8 per-tensor                           0.993228     1.0    0.1167    ~349    OK
    a8w8                                       0.024491     0.0    1.0000    365.6   崩溃
    a16w4                                      0.170816     0.0    1.6259    364.2   崩溃

  relL2 = ||v-ref|| / ||ref||；**relL2 >= 1.0 表示比直接预测全零还差**。

  **A8W8 与 A16W4 的输出与参考几乎不相关（cos 0.024 / 0.171，argmax 全错）。
  这不是"轻微量化损失"，是数值失效。**

  注意 net.json 里 A16W4 确有 4265 个张量标记为 4-bit，但**序列化 context 反而比基线更大**
  （364.2 MB vs 349.0 MB）—— 激活量化插入的额外算子撑大了图，W4 没有换来字节下降。

## 三、计时结果（噪声大，不足以支撑倍数结论）

    臂                      trial1   trial2   trial3    mean    spread
    a16w8 基线              42.98    71.84    52.06    55.6     1.67x
    a16w4                   44.83    40.33    41.15    42.1     1.11x
    a8w8                    82.73     -        -        -         -
    a16w8 per-tensor        88.91     -        -        -         -

  **基线自身在三次重复间波动 1.67 倍（43 -> 72 -> 52 ms）。**
  在这种噪声下，A8W8/per-tensor 的单次 82.7 / 88.9 ms **不能**被声明为"慢 1.9~2.1 倍"。

  可以确定的只有：
    - **没有任何一臂出现带宽假设所预测的约 2 倍加速。**
    - A16W4 与基线在计时上**不可区分**（42.1 vs 55.6，且 A16W4 自身更稳定）。
      因此 W4 路线**不是被计时否定的，而是被质量否定的（cos 0.171）**。
    - A16W8 per-channel 是唯一质量健康的臂。

  **[新增纪律] 计时结论必须多次重复。** 本次 n=1 的 82.7 vs 43.0 是在基线自身有 1.67 倍波动下得出的，
  与 R49 的教训同类：单次实现的波动大于所追逐的效应。报告倍数前必须先给出基线的重复分布。

## 四、与 F4 数值的差异必须显式归一化（纪律 #2）

    F4 报告（app 的 nativePerfSplit 路径）   repSlow mean 74.15 ms/step
    本次（qnn-net-run，同一 context 家族）   基线 42.98~71.84 ms/step
    -> 区间重叠

  两者不是同一 harness，绝对数值不可互相引用。本次四臂 A/B 因 harness/输入/位置全同，
  **相对比较有效**。

## 五、对 RTF 预算的影响

    基线每层：43~72 ms / 24 层 = 1.8~3.0 ms/层
    单层 14.9M 参数（INT8 = 14.9 MB）=> 有效带宽 5~8 GB/s
    SM8850 可用带宽约 40-50 GB/s -> 仍在 ~10-20%，带宽受限的解释依然成立

    62 步 x 43~72 ms = 2.7~4.5 s   （预算 Total <= 2.70 s）-> **慢图本身就是一堵墙**

  **量化这条路已经走到头。剩下的杠杆只有「减少层数」和「减少步数」：**
      慢图 24L -> 16L：x16/24 -> 62 步 = 1.8~3.0 s。**这正是 M1 蒸馏在做的事 ——
      蒸馏不只是为质量，它同时是 RTF 的解药。**
      步数 62 -> 更少：需要 codec/帧率侧配合。

## 六、修改

  1. 新增 scripts/f5_dev_slow_matrix.ps1   四臂矩阵设备实测驱动
  2. 新增 scripts/f5_compare_slow_arms.py  设备 logits vs FP16 参考的等价性比对
  3. 新增 scripts/f5_dev_repeat.ps1        计时重复性验证
  4. 新增 scripts/f5_dev_a8w8_vs_a16w4.ps1 首次两臂驱动
  5. **修复设备地址单一来源**：6 个脚本的 DeviceSerial 默认值由硬编码旧地址改为
     $(if ($env:A8_DEVICE) { $env:A8_DEVICE } else { '10.40.137.201:37869' })：
       04-device-htp-smoke.ps1 / 06-mnnqnn-context-equiv-slow-ar-fixed.ps1 /
       07-margin-scan-batch.ps1 / 08-mnn-multi-equiv-slow-ar-fixed.ps1 /
       run_fast_ar_positions.ps1 / run_slow_ar_fixed_chain.ps1
     全部通过 PowerShell Parser 校验（parse_errors=0）。

## 七、命令

    $env:A8_DEVICE = '10.40.137.201:37869'
    & scripts\f5_dev_slow_matrix.ps1
    & scripts\f5_dev_repeat.ps1
    & $PY scripts\f5_compare_slow_arms.py artifacts\diagnostics\slow-ar-matrix-20260918

## 八、Gate

    A16W8 per-channel 作为慢图部署配置   PASS（cos 0.9933，argmax 正确）
    A8W8                                 FAIL（cos 0.0245，argmax 全错）
    A16W4                                FAIL（cos 0.1708，argmax 全错）
    per-tensor 量化                      FAIL（质量与基线相同，无收益）
    "量化能让慢图变快"这一总假设          FAIL（无任何一臂达到带宽假设预测的约 2 倍加速）
    慢图 RTF 预算                        FAIL（62 步 x 43~72 ms = 2.7~4.5 s > 2.70 s）

## 九、产物

    artifacts/diagnostics/slow-ar-matrix-20260918/{summary.json,compare.json,repeat.json,logits-*.raw}
    artifacts/diagnostics/slow-ar-a8w8-a16w4-20260918/（首次两臂）

## 十、风险 / 局限

  1. **只测了 position-000 一个位置**。失效幅度（cos 0.024 / 0.171）极大，不需要更多位置即可拒绝；
     健康臂 cos 0.9933 在其余 9 个位置是否成立尚未验证（scripts/06 已有 position 循环可复用）。
  2. **A8W8 与 per-tensor 只测了 1 次**，其计时不可引用。若要比较耗时必须补到 n>=3。
  3. A8W8/A16W4 失效的**根因未定位**（校准集不匹配？激活 scale 失配？转换器行为？）。
     本轮结论只是"这两条路在设备上不可用"，不等于"理论上不可能"。
  4. 计时受设备热/后台负载影响大（同臂 spread 1.67 倍），
     后续任何设备计时都必须报告重复分布，不能报单点。

## 十一、下一步

  1. **停止在慢图上做量化**（A8W8 / per-tensor / W4 三条全部关闭）。
  2. **把慢图层数作为唯一剩下的主杠杆**，与 M1 蒸馏合并：
     16L 在此 harness 下预期 62 步 = 1.8~3.0 s。**但蒸馏当前 G-content FAIL（meanDiff +0.0724），
     质量不达标则提速无意义 —— 蒸馏质量仍是主线。**
  3. 若要继续压 RTF：先补 A16W8 在 10 个位置的 cos 与耗时（建立稳定的健康基线分布），
     再考虑帧数/Codec 侧。


---

# 冻结（2026-09-18，用户确认）

## A. 量化矩阵 —— 正式 CLOSED

    F5 Quantization matrix
    A16W8 per-channel    OK   ONLY HEALTHY ARM   （但仅 pos0 deployment PASS，非 FROZEN）
    A16W8 per-tensor     FAIL NO BENEFIT
    A8W8                 FAIL NUMERIC FAIL
    A16W4                FAIL NUMERIC FAIL
    quantization speed lever  CLOSED

  **不再调查 W4 为什么坏。** cos=0.17 已足以拒绝；除非以后 QNN SDK/转换器升级顺手重测，
  不为一个不能解决总架构问题的支线再花轮次。

## B. 纠正：24L -> 16L 提速是真的，但 **16L 不是 RTF<1 的充分解**

    健康 A16W8 实测：24L 慢图 43~72 ms/frame
    按层数线性缩到 16L：28.7~48 ms/frame
    62 frames：1.78~2.98 s
    而整段音频只有约 2.97 s

  **即 16L Slow 自己就会吃掉接近全部实时预算**，还没算 Fast、Codec、prefill。
  F5 第五节"24L -> 16L 是 RTF 的解药"的说法**必须修正为**：16L 只是第一站。

## C. 路线拆成两条

    质量线：
      24L Teacher -> 16L Student -> 先把 G-content 过掉
    性能线：
      16L 只是第一站 -> 还要继续向 12L / 8L 探
      Fast 必须 10-step -> 1/2-step

    整体判据：**Slow 减层 + Fast 去 AR，缺一个都不可能进入实时区。**

    Slow 主线现状：
      M1-16L quality   ACTIVE
      G-content        current FAIL
      M2-12L / 8L      等 16L 算法收敛经验

## D. 保留的两个细节

  1. **A16W8 per-channel 现在只能叫 pos0 deployment PASS。**
     等 Student 真正准备部署时，至少再扫 pos = 0, 8, 32, 64, 131，
     确认没有随 position 加深恶化的问题。**现在不阻塞训练。**
  2. 不重查 W4（见 A）。

## E. 下一步路线（用户确认）

    1. 等 16L_books4eos 完成
    2. 48-val G-content：meanDiff = Student CER - Teacher CER；PASS iff one-sided upper95 < +0.05
       （不回 8 条 val，不看单 seed 的漂亮数字）
    3. 若 PASS -> 立刻做 G-clone / G-separation / G-open-set，
       并把 16L 当作质量锚点 Student，复制 distillation recipe 去压 12L / 8L
    4. 同时启动 Fast one-shot 数据/模型
    5. 复用 16L 蒸馏经验继续压 Slow 12L / 8L
    6. 最后再部署 A16W8 per-channel

  **若 48-val 仍然 meanDiff ~ +0.07：不再优先加书，改 Student 的训练目标**
  （normalized layer loss / relation distillation / 更合理的 intermediate supervision）——
  真正要解决的是"少 8 层之后丢掉的表示能力"，而不是同类数据不够。

