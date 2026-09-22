# PLAN-20260916-057 — DSP RoPE 配对偏移修复（Native Backend 变更）

## Goal

修掉 MNN Hexagon DSP RoPE kernel 的配对/写回偏移根因（P16-1b-3e 定位），重建 DSP skel 并在
SM8850 真机验证：层 7 断崖消失、0:23 全 Hexagon 配置通过精度 Gate，且速度无回归。

## Current Verified Facts

```
1) CPU 参考 = MNNRoPEComputeBasic
   source/backend/cpu/compute/CommonOptFunction.cpp:4638
   旋转区 [0, rope_dim)，配对 (k, k+P) P=rope_dim/2，透传区 [rope_dim, head_dim)
2) trig 布局：CPURoPE.cpp:195-199 与 execute_command.cc case DSP_OP_ROPE 一致
   c_e=cos+0, c_o=cos+P, 每 token 步进 rope_dim
3) 本模型：head_dim=256 / rope_cut_head_dim=64 / num_head=8 / kv_num_head=2
   （HexagonRoPE.cpp:179-183 由 attr 读入）
4) 实际执行路径 = fuse 路径（HexagonRoPE.cpp:72 mFuseLayerNorm=(qNorm||kNorm)!=nullptr；
   P9-0 DSP profile 有 ROPE_FUSE_LAYERNORM(15)）→
   htp_ops_rope_fuse_layernorm → process_one_seq → compute_rope_head_fp16 → compute_rope_head64blocks_fp16
5) 旧代码在 [0,64) 与 [128,192) 两个区间与 CPU 不一致（P16-3E lane 级推演 + 实测 0.77/0.76）
6) host libMNN.so 不包含 rope_ops.cc（build_hexagon_m1/build.ninja 无该文件规则）
7) CPU baseline 与 P15 基线逐字节相同（MD5 5C242819…），旧 skel 逐字节复现历史 logits
```

## Non-goals

- 不动 Attention / Convolution / GDR / LayerNorm 等其它 DSP kernel。
- 不做 asymmetric W4 + HMX（P16-3，独立任务）。
- 不解决 Hexagon 路径既有的 ~0.99 精度赤字（P16-2）。
- 不改 host 侧 RoPE 参数绑定（已核对正确）。
- 不为 C4 未融合路径新增真机验证环境（该路径本模型不执行）。

## Invariants

- 根 AGENTS：禁止为过测试降低 Gate；未知结果不得写成确定事实；失败路线同等记录。
- 数字只认 CPU 参考实现，不认"看起来合理"。
- 第三方/用户数据不可破坏；skel 基线必须留备份（`outputs/_frozen_baseline_p16/`）。

## Scope

```
source/backend/hexagon/htp-ops-lib/src/dsp/rope_ops.cc        （唯一代码改动）
source/backend/hexagon/htp-ops-lib/outputs/libMNN_htpops_skel.so（构建产物）
/data/local/tmp/MNN/libMNN_htpops_skel.so                      （设备部署 + 旧版备份）
```

## Baseline

```
修复前（P15-6 / P16 记录，同口径可复现）:
  0:23 logits cos = 0.956355  argmax 95826        ← Gate FAIL
  12:23 cos = 0.990833 ; 7:7 = 0.981213 ; 0:7 = 0.971053
  L3 FusedRoPE 四分块 vs CPU: 0.8714 / 0.9993 / 0.7678 / 0.9993（整头 0.8886）
  500 tok prefill（P15 会话）: 0:23 1.391 s ; cpu 1.663 s ; 12:23 2.290 s
```

## Milestones

```
M1 ✅ 语义钉死（CPU 参考 + trig 布局 + 实际执行路径）
M2 ✅ 索引级仿真 Gate（26 组几何，新算法 == CPU 参考，0 越界）
M3 ✅ 代码修复（4 处函数 + 1 处 trig 暂存）
M4 ✅ 构建 DSP skel（符号检查通过）+ 设备部署 + 旧版备份
M5 ✅ G6 内核级 Gate（L3/L7 四分块对拍，透传区逐位不变）
M6 ✅ 端到端 0:23/12:23 同会话 A/B + 单层扫描 + 确定性
M7 ✅ 生成文本对拍 + 速度 A/B（无回归）
M8 ✅ 文档落盘（本节 + report.md + STATE + ADR-050）
```

## Progress

M1-M8 ✅ 2026-09-16（全部完成；Gate 见 Validation）

## Decisions

```
D1 先写索引级仿真再构建 —— 一次 DSP 构建 + 真机往返成本高，仿真把"索引写错"这一类
   风险前移；实测该仿真自检能复现旧算法的 0.42 偏差，说明它真的在建模。
D2 尾块用 lane_count 限长的 vstu_variable 部分写，而不是"整向量写 + 依赖后续覆盖"：
   后者的正确性依赖写序与内存余量，属于隐性不变量，不可接受。
D3 C4 路径跨 pack64 块时按 lane 逐个 gather：非 64 对齐的整向量读会读到隔壁 pack 块
   （P16-3d 的 pack64 索引公式），必须显式处理。
D4 head_dim<128 的通用块/尾块 HVX 代码不改造成"局部补丁"，而是保留结构 + 修正偏移与
   透传区（避免大范围重写引入新风险）。
D5 同会话 A/B 必须用"旧 skel 逐字节复现历史文件"来证明可比性，而不是假设设备状态稳定。
```

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | 索引级仿真（26 几何） | CPU 参考 | maxAbsDiff 全 0，0 越界；旧算法 0.42 | SIM PASS |
| E2 | DSP skel 重建 | 旧 skel | 只重编 rope_ops.cc，符号检查过 | 构建 PASS |
| E3 | L3 RoPE 四分块（3:3 vs 24:24） | 旧 skel | 0.8714/0.9993/0.7678/0.9993 → 0.99984/0.99928/0.99944/0.99935 | G6 PASS |
| E4 | 0:23 logits cos | 旧 skel 0.956355 | 新 skel 0.994986，argmax 124483 | G7/G8 PASS |
| E5 | 12:23 logits cos | 0.990833 | 0.993881 | 同向改善 |
| E6 | 7:7 / 0:7 | 0.981213 / 0.971053 | 0.990193 / 0.993291 | 断崖消失 |
| E7 | 0:0（对照，无 RoPE） | 0.993186 | 0.993186（逐字节同） | 对照成立 |
| E8 | 确定性 | — | 同配置两次逐字节相同 | PASS |
| E9 | 生成文本 24 tok | cpu | 0:23 与 CPU 逐字一致 | PASS |
| E10 | skel A/B 计时（18 样本） | 旧 2.4947 s | 新 2.2753 s，中位数持平 | 无回归 |

## Validation

```
G6  L3/L7 RoPE 四分块 vs CPU 全部 >= 0.99 且透传区逐位不变 ....... PASS
G7  0:23 logits cos >= 0.99 ................................... PASS（0.994986）
G8  argmax 回到健康档（124483）................................ PASS
G9  确定性 ................................................... PASS
G10 生成文本与 CPU 一致 ...................................... PASS
G11 速度无回归 ............................................... PASS
命令: runs/.../p16fix_scan.ps1 / p16fix_speed.ps1 / p16fix_speed_ab.ps1 / p16fix_rope_node.js
      node p16_rope_fix_sim.js ; node cmp_logits.js <cpu> <others>
```

## Rollback

```
设备侧旧 skel 备份: /data/local/tmp/MNN/libMNN_htpops_skel.so.before_ropefix
                    （md5 5a36d52e45bf1fe7c70cfe47debafff8）
仓库侧备份: htp-ops-lib/outputs/_frozen_baseline_p16/libMNN_htpops_skel.so.prefix_before_rope_fix
回退 = 把备份 cp 回 libMNN_htpops_skel.so，无需重新构建；代码回退 = 恢复 rope_ops.cc 旧实现
```

## Artifacts

```
代码   source/backend/hexagon/htp-ops-lib/src/dsp/rope_ops.cc
构建   outputs/libMNN_htpops_skel.so (4,597,776 B, md5 4e38fd004909eab517d06419fe584d99)
脚本   runs/mobile_000b2_hexagon_la/m2-3-nodealign/{p16_rope_fix_sim.js,p16fix_*.ps1,p16fix_rope_node.js}
数据   runs/.../m2-3-nodealign/p16fix/**、p15/corr/fix_*.logits.txt、p16fix_speed*.txt
文档   runs/.../p15/P16_FIX_SECTION.md、report.md（P16-FIX 段）、本 PLAN、ADR-050
```

## Open Issues

```
1) 绝对速度依赖设备状态：本次 0:23 vs cpu 只有 2~12%（P15 会话 20%）⇒ 需安静设备复测钉死 margin
2) C4 未融合路径与其它形状只有仿真证据，无真机证据
3) 单层扫描 3 处 <=0.0015 下降（0:6 / 3:3 / 15:15）无因果解释
4) ~~Hexagon 既有 ~0.99 精度赤字（P16-2 未启动）~~ ⇒ 已由 P16-2 证伪为参照系假象，见下
```

## 追加：P16-2 精度赤字归因（2026-09-16，同一会话内完成）

```
结论：所谓"Hexagon 0.99x 精度赤字"是【以 fp16 CPU 为参照】的产物，不是 Hexagon 缺陷。

证据链:
  ① 逐算子新增误差（cos_in/cos_out 配对，脚本 p162_incr.js）
       注意力/RoPE/归一化 <= 1e-4；误差 100% 来自 W4 dense 投影；
       L3 与 L0 两种层类型的 sum(positive added) 都约 4.1e-3；
       同形状 mlp/gate 干净 vs mlp/up 脏 10~20x ⇒ 逐张量 W4 属性，非形状/分支
  ② 决定性对照（新增 config_rd_cpu_fp32.json，两边逐字节可复现）
       cos(CPU fp16, CPU fp32) = 0.995184
       cos(HTP 0:23, CPU fp32) = 0.996345   ← 比 fp16 CPU 更接近 fp32，argmax 也与 fp32 一致
       cos(HTP 0:23, CPU fp16) = 0.994986   ← 与 fp16 模式自身对 fp32 的偏差同量级
    ⇒ 两者分歧 = fp16 噪声地板；P10 门限 0.997187 在 fp16 口径下不可达

反面证据（必须同时引用）: 24-token 生成中 CPU fp32 反而给出史实错误（610 年），
                         fp16 CPU 与 HTP 0:23 都给 618 ⇒ cos 不能当任务质量结论。

判定: P16-2 = 负结果（无缺陷可修）；停止该方向。
文档: p15/P16_2_SECTION.md、report.md P16-2 段、ADR-051（Gate 参照系双报纪律）
新增 lesson: ㉓ 先测参照系自身偏差；㉔ 逐节点 cos 要 cos_in/cos_out 配对算 added
开放项: 把逐算子 added 归因也跑一遍 vs CPU fp32，量化"每个算子有多少是 HTP 专属"
```

## 追加：P16-3 系统级速度（2026-09-16，同一会话内完成）

```
目标修正: P15-2 的"Convolution(Dense) 54%"是 12:23 口径（12 层跑在 CPU 上）。
          在将发布的 0:23 上重测: LA 47% / host↔DSP 搬运 49% / DSP 内部 290 ms（重叠）
根因:     host↔DSP 搬运内部 repack 640 ms，其中 D2H 620 ms（174 MB ⇒ 280 MB/s）；
          convert(fp16<->fp32) 仅 0.3 ms（NEON，无辜）。
          同文件的 H2D 对称路径是「channel 外层 + NEON」= 5 GB/s，快 18 倍。
修复:     按 H2D 同构重写 nc4hw4DeviceToHost / deviceToNchwHost（NEON、去堆分配、除法外提）
前置 Gate: p163_repack_sim.js 14 组形状逐元素等价仿真（含 c%4!=0 补零）——
          当场抓到第一版 deviceToNchwHost 的 channel 步长写错（应 h*w），未进构建
结果:     repack 640→61 ms（D2H 15.9x）；copy 1016→449 ms；
          prefill A/B 12 样本 min 1.591→1.412 s，每轮配对均优（1.11~1.69x，随负载）
正确性:   0:23/12:23/7:7/0:0 logits MD5 与优化前【完全相同】（A/B 8 文件），24-token 文本一致
          ⇒ 纯提速、零数值变化
新增工具: MNN_HEX_COPY_PROFILE_LOG（flush/host/repack/convert 四段拆分，文件式落盘）
文档:     p15/P16_3_SECTION.md、report.md P16-3 段、ADR-052
lesson:   ㉕ 对称路径互为参照物 ㉖ 归因绑定将发布的配置 ㉗ 画像边跑边流式抓
下一步:   prefill 最大单项已变成 cpu|LinearAttention（按设计留 CPU）⇒ 需要结构性改动
          （更快的 DSP GDR prefill 或减少跨边界次数），不再是小 kernel 优化；
          且这些收益要等 App 侧 HTP Gate 重跑通过才进用户路径（ADR-050）
```

## Handoff

- 结论已改：默认配置从 `config_rd_cpu_greedy.json` 变为 `MNN_HEX_LAYER_HTP=0:23`。
- 下一步优先级：P16-2（补齐非 FA 算子精度赤字，目标是让 0:23 从 0.995 走向 0.997187 冻结门限）
  → 之后才是 P16-3（Dense 投影 54%，asymmetric W4 + HMX）。
- 任何后续 DSP kernel 改动：先看 `p16_rope_fix_sim.js` 这种索引级仿真能不能前置验证。
