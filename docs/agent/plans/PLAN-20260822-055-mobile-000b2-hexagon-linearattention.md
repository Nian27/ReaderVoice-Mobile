# PLAN-20260822-055 — MOBILE-000B.2：HexagonLinearAttention（Qwen3.5 hybrid 上 HTP）

## Goal
在 MNN 3.6.1 Hexagon NPU 直接编程后端实现 LinearAttention 算子（gated_delta_rule），使 ReaderDirector（Qwen3.5-0.8B hybrid：18 层 linear_attention + 6 层 full_attention）在 SM8850（Hexagon v81）上以 backend_type=hexagon 全图运行，产出真实 DSPOpType 性能数据。正确性优先，性能随后。

## Current Verified Facts
- MNN 3.6.1 本地源码 == 官方 3.6.1 tag（llm.cpp/llmconfig.hpp/qnn CMakeLists SHA 全同）。
- LLM 引擎已内置 backend_type="npu"/"hexagon" 路径（llm.cpp:340/1520）；MnnLlmChat 带 QnnModule.kt。
- Hexagon 后端：HexagonExecutionFactory 对未知 op 返回 nullptr → MNN 自动 fallback CPU（机制确认）；execution 已有 HexagonAttention（449 行，host 编排 HexagonCommand + workspace + FD 内存）。
- DSP 算子全集（dsp_op_name.h）：MATMUL_Q4A16_FP16 / MATMUL_Q4A16_BLOCK_FP16 / BATCH_MATMUL / UNARY / BINARY_ELEMENTWISE / SOFTMAX / LAYER_NORM / LAYER_NORM_PACKED / ROPE / CAST / SELECT / REDUCTION / SCALE / ZERO / COMMAND_GROUP 等——gated_delta_rule 分解原材料基本齐备。
- LinearAttention 已有 CPU（gated_delta_rule_ref/mnn/decode + short_conv）/OpenCL（kernel 分解：short_conv_nosilu/state_update/output、conv_silu、gated_delta_rule、l2_norm、chunk_* 系列）/Metal/Vulkan/CUDA 实现；StateCache 状态缓存模式现成（CPU/OpenCL）。
- 环境：Hexagon SDK（WinNT/Linux）、QAIRT 2.48（V81）、SM8850 真机 BKQ-AN90、无线 ADB 10.40.128.183:39191（2026-08-24 更新；旧端点已失效）、merged int4 已在 probe 上 CPU load OK。
- 官方构建链：build_64.sh -DMNN_HEXAGON=ON -DMNN_GPU_TIME_PROFILE=ON；DSP 侧 sync_remote_build.sh v79（输出 libMNN_htpops.so + libMNN_htpops_skel.so）；DSPOpType profile 可精确测每 op HTP 耗时。

## Non-goals
- 不做 QNN 后端（compilefornpu 离线 context）路线（M2 FAIL，缺 FusedRoPE 等，且 LinearAttention 同样未实现）。
- 不做 chunked/Neumann 优化（M3 可选）；不做模型训练改动。
- 不替换 ReaderVoice common runtime（probe 独立验证链）。

## Invariants
- AGENTS：一次实验一个变量；禁止为过测试降 Gate；失败路线同等记录。
- Hexagon skill 硬约束（官方）：**不以 CPU fallback 作为优化手段**；不做算子融合式优化（除非用户解除）。
- 正确性对照：CPU ref（gated_delta_rule_ref）逐 token 比对；用 test/op/LinearAttentionTest.cpp 基建。

## Scope
- MNN-3.6.1（fork 构建）：source/backend/hexagon/execution/HexagonLinearAttention.{cpp,hpp} + HexagonExecutionFactory 注册 +（如缺）htp-ops-lib DSP 算子。
- 构建：project/android 式 arm64 构建（本机用 NDK r27 + ninja 已有先例）；DSP 侧优先 WSL/Linux Hexagon SDK。
- 验证：mnn-llm-probe（换 hexagon 库 + backend_type=hexagon）真机 merged int4；DSPOpType profile。

## Baseline
- merged int4 CPU（自编译 3.6.1 精简库）load OK + VS8 正确输出（5.6s/16tok）。
- NPU：无任何 LinearAttention 支持（QNN/Hexagon 皆缺）。

## Milestones
- M1：host 侧 HexagonLinearAttention execution 骨架：onResize 分配 StateCache（仿 OpenCL mStateCache/CPU snapshotPrefixState）+ onExecute 编排：decode 路径（gated_delta_rule_decode 语义）先上 HTP；正确性对 CPU ref PASS（LinearAttentionTest）。
- M2：prefill 路径（short_conv + conv_silu + l2_norm + gated_delta_rule 全序列）上 HTP；decode+prefill 混合（LLM 引擎真实调用链）正确 PASS；真机 merged int4 backend_type=hexagon load+generate 通过（含 16KB 对齐库）。
- M3：DSPOpType 性能剖析（full 层 vs linear 层 HTP 占比、tok/s）；性能优化（chunked/状态缓存复用）视数据决定。
- M4（视 M3）：ReaderVoice common runtime 集成（若 common runtime load FAIL 定位完成）。

## Progress
- M0 ✅ 2026-08-22：可行性评估（DSP 算子集/OpenCL 分解/HexagonAttention 模式/引擎 hexagon 路径）——本计划立项。
- M1 ✅ 2026-08-23/24：HexagonLinearAttention decode（L=1）命令序列（fork commit 5d0faf1）；2026-08-24 代码复查修正为 **create 期 shape 判定 fallback**（commit 待提交）。
- M2-0 进行中（2026-08-24）：runtime/架构 sanity（v81 custom-op smoke / fallback 语义 / StateCache 设计）——详见下方"M2 门序修订"。

## Decisions
- D1：走 Hexagon 直接编程后端（非 QNN 离线转换）——3.6.1 官方 LLM 支持 hexagon（llm.cpp:1520），且免离线 context。
- D2：正确性优先：先 decode 后 prefill；先 naive 后优化。
- D3：状态缓存复用 CPU/OpenCL 的 StateCache 语义，Hexagon 侧新实现（仿 HexagonKVCacheManager 的内存管理）。
- D4：DSP 算子优先复用（MATMUL_Q4A16/BINARY/UNARY/SOFTMAX 等），缺的关键算子（exp/log/cumsum 等按 OpenCL kernel 清单对照）才在 htp-ops-lib 新增。

## Experiments
| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | decode 单步 vs CPU ref | ref | M1 | — |
| E2 | prefill 全序列 vs CPU ref | ref | M2 | — |
| E3 | 真机 merged int4 hexagon vs CPU | CPU 5.6s | M2 | — |
| E4 | DSPOpType 分布 | — | M3 | — |

## Validation
- M1/M2：LinearAttentionTest（CPU vs hexagon 逐 token/tensor 比对，误差阈值仿现有测试）；真机 load→generate 输出 == CPU 输出。
- M3：DSPOpType 显示 LinearAttention 各子算子 HTP 耗时；RTF/tok/s 记录。
- Gate：全部 PASS 才更新 PROJECT_STATE；不把 partial op 当 NPU 证据。

## Rollback
- 仅 MNN fork 构建目录与 probe jniLibs 可变；MNN-3.6.1 源码改动用 git 管理（如未初始化则先 git init 快照）；回退 = 切回 CPU 库。

## Artifacts
- source/backend/hexagon/execution/HexagonLinearAttention.*；htp-ops-lib DSP 新增（如有）；runs/mobile_000b2_hexagon_la/（构建日志/DSPOpType profile/report.md）；probe 库切换记录。

## Open Issues
- v79 skel 在 v81 设备兼容性（先试 v79，FAIL 再 v81）；HTP 访问权限（普通 app dsprpc 是否可调——qnn-work 链已验证过 HTP 访问）；exp/log unary 是否在 DSP UNARY 覆盖内（对照 htp-ops-lib unary 实现）。
- common runtime（全功能库）merged load=FAIL 的独立问题（M4）。

## M1 实现设计（2026-08-22，从 HexagonAttention/OpenCL/CPU 三方提取）

### 命令构造模式（照抄 HexagonBinary/HexagonAttention）
每个 DSP 算子一个 HexagonCommand：定义 packed param 结构体（参照 HexagonBinary 的 MergedBinaryParam）、
HexagonBackend::getDevicePtr 取输入/输出 fd 对、dst.emplace_back() +
dst.back().build(hexBackend, DSP_OP_XXX, &params, sizeof(params), inputFds, outputFds, commandInputs, commandOutputs)。
张量必须 fp16（getBytes==2）；workspace 用 backend()->onAcquireBuffer(DYNAMIC) + getDevicePtr 追加 outputFds。

### decode 路径命令序列（M1 目标，L==1）
1. RASTER_BLIT：拼 convState+input → conv 输入
2. CONV_DEPTHWISE2D_FP16：short conv
3. UNARY(SILU)：conv_out
4. RASTER_BLIT×3：提取 q/k/v 通道分片
5. REDUCTION+UNARY(RSQRT)+SCALE：L2Norm(q/k)
6. UNARY(EXP)：decay=exp(gate)
7. BINARY(mul,S,bcast k)+REDUCTION(sum)：vPred=S^T@k
8. BINARY(mul,S,bcast q)+REDUCTION(sum)：o_q=S^T@q
9. REDUCTION(k*q)：kq
10. BINARY：delta=beta*(v-decay*vPred)；out=decay*o_q+kq*delta
11. SCALE+BINARY(add)：S=decay*S+k(x)delta
12. RASTER_BLIT：conv state 移位

状态缓存：mConvState[B,D,K_conv-1] + mRecurrentState[B,H,d_k,d_v]，
onResize 用 backend()->onAcquireBuffer(STATIC) 分配（跨 token 持久）。

### 关键风险（M1 前置确认）
1. DSP_OP_UNARY 是否覆盖 SILU/EXP/RSQRT（对照 htp-ops-lib unary op 表）。
2. DSP_OP_REDUCTION 的 reduce 轴支持（沿最后一维 d_k）与输出形状。
3. BINARY_ELEMENTWISE 广播语义（MergedBinaryParam.broadcastDims 已支持——HexagonBinary 有 _buildBroadcastShape）。
4. S 的持久性：decode 每 token 更新 S——需要 ION/dmabuf 可访问的持久 buffer（HexagonKVCacheManager 已有先例）。
5. prefill(L>1) 的 chunked 分解（OpenCL chunk_* 系列）留 M2。

### 性能预期管理
- M1/M2 正确性优先，性能数据用 DSPOpType profile 采集后再评估。
- 宣传数字（Qwen3-0.6B dense v79 pp512 2667tok/s）不适用于 hybrid decode 场景；
  ReaderDirector 是短输出 decode 为主，NPU 收益主要体现在 prefill 与 full 层。

## M0.5 替代路线（2026-08-22 定案）：ONNX 展开 + QNN SDK 直转

发现：audio8tts-mnn 并行线（E:\AndroidStudioProjects\audio8tts-mnn\artifacts\runs）已用 **QNN SDK 直转 ONNX**（qnn-onnx-converter → WSL HTP context → qnn-net-run）在 SM8850/V81 上跑通 Audio8 Slow/Fast AR 数值 Gate（cosine≥0.98），**完全绕过 MNN QNN backend**。

Qwen3.5 llm.onnx 现状：FusedLinearAttention×18 / FusedRoPE×6 / FusedAttention×6（自定义 op）→ QNN converter 不认识。

**新路线**：写 ONNX 图重写器展开 3 个 fused op 为标准算子 → 复用 audio8tts-mnn 的 02/03/04 脚本链 → HTP 数值 Gate。
- FusedAttention 展开：标准 MHA（MatMul+Softmax+mask）
- FusedRoPE 展开：q/k RMSNorm + rotary（rope_cut_head_dim=64）
- FusedLinearAttention 展开：gated_delta_rule 单步（S 状态作图输入/输出，decode 外部循环；prefill 展开 L 步或逐位置）
- 收益：1 周内拿到 "Qwen3.5 hybrid 在 SM8850 HTP 数值可跑与否" 的决定性证据；工具链/脚本/数值 Gate 方法论全部复用
- 风险：S 状态跨调用传输开销（d_k*d_v*heads*2B*18 层）、QNN API 状态保持（qnn-net-run 单次执行不适合产品化，但可行性验证足够）

**与原 Hexagon 路线的分工**：M0.5 快速验证先行；若 PASS → 产品化决策（QNN API 状态保持 vs Hexagon 自研 LinearAttention DSP 算子）。

## Handoff
- M3 数据 → 决策 NPU 支线去留；M4 → ReaderVoice 集成；同时 CPU 主线（common runtime FAIL 定位 + 等价性）持续。


## M2 门序修订（2026-08-24，基于 M1 代码复查与用户审计）

### 复查结论（修正此前文档的两处错误）
1. **QNN 后端标识**：`MNN_FORWARD_NN=5` 是 NPU runtime backend 槽（QNN runtime 用 5）；`MNN_CONVERT_QNN=32` 是 **offline convert 类型**（MNNForwardType.h:58 "For Offline Convert"），不是 runtime backend。此前文档写"QNN backend=32"错误，已改。
2. **Hexagon backend 不是"只有 libMNN"**：还必需 `libMNN_htpops.so`（CPU stub）+ `libMNN_htpops_skelV<arch>.so`（DSP skeleton，htp-ops-lib/build.sh `DSP_ARCH=$1` 产出，无 whitelist，v81 语法允许），运行需 `LD_LIBRARY_PATH`+`ADSP_LIBRARY_PATH`（docs/transformers/llm.md:136-147 官方流程）。真正的 v81 Gate 是 **Hexagon SDK 工具链是否认识 v81**（sim/真机验证），不是 MnnLlmChat 的 `sQnnLibMap`。
3. **M1 fallback 语义错误（已修代码）**：MNN Pipeline 的 CPU 回落只在 **Execution create 返回 nullptr** 时发生（backup backend）；onResize/onBuildCmd 返回 NOT_SUPPORT 是运行失败（Pipeline.cpp:526 `NO_ERROR != code → return -1`，无二次切换）。M1 原实现在 onBuildCmd 里判 shape 返回 NOT_SUPPORT——已改为 create 期判定（HexagonSoftmax 同款 4 参签名），onBuildCmd NOT_SUPPORT 仅作防御性复核。
4. **prefill→decode 状态交接（Road B 最大架构问题，原计划未覆盖）**：CPU prefill 的持久状态在 `mStateCache`（CPULinearAttention.cpp onClone:1204 显式共享，CPU↔CPU）；HTP decode 的 `mConvState/mRecurrentState` 是 HTP 侧持久 buffer（hexagon memory），**不能 shared_ptr 直通**。需要一次 prefill→decode 边界 handoff（CPU→HTP 上传），不做逐 token 同步。

### 架构调整（相对原 M1/M2/M3/M4 里程碑）
- **prefill (L>1) 留 CPU**（CPULinearAttention，create 期被 Hexagon create 拒绝 → 自动回落 backup CPU）；**decode (L=1) 上 HTP**（HexagonLinearAttention）。
- 状态统一为逻辑 `LinearAttentionState`（canonical metadata + conv/recurrent 版本 + dirty/owner），CPU 镜像与 HTP 镜像仅在 backend 切换时同步一次（prefill→first decode）。性能可接受（每回合一次上传，非每 token）。

### 新门序（替代原 M1-M4 编号推进）
```text
M2-0  Runtime / architecture sanity
├─ M2-0.1 Hexagon v81 custom-op runtime smoke（build.sh v81 → skelV81 真机加载）
├─ M2-0.2 fallback 语义确认（已修代码：create 期 shape 判定 → CPU；构建验证）
└─ M2-0.3 CPU/Hexagon StateCache 设计确认（LinearAttentionState 桥）
M2-1  LinearAttention isolated correctness（绕开 prefill/engine）
├─ D0 零初始 state L=1 / D1 随机非零 state L=1
├─ D8/D32/D128/D256 连续 token 递推
└─ 每步记录 out/conv_state/S 的 cosine + rel-L2 + max-abs-err + finite
M2-2  CPU Prefill → HTP Decode handoff
├─ pp8+tg1 / pp32+tg1 / pp128+tg1 / pp512+tg1
└─ Gate：第一枚 decode token 正确 = convState/S/layout/precision/copy 全接上
M2-3  llm_bench 全模型
├─ -a cpu vs -a hexagon
└─ -pg 32,8 / 128,16 / 512,32（prefill+generate 单次运行，decode 延续 prefill cache）
M2-4  full generation parity（token IDs / logits / first divergence / 128-token drift）
M2-5  Performance（prefill/decode tok/s、per-token latency、HTP command 数、FastRPC、memory、thermal）
```
- M1 的"零新增 kernel（primitive 组合）"= 第一阶段成功条件，**非最终架构约束**；若 M2-5 显示 command dispatch/traffic 过高 → M4 阶段考虑 fused gated_delta_rule HTP op。
- 冻结基线：MNN-3.6.1 fork（afb1c24 + 5d0faf1 + 本轮 create-期 fallback）。**暂不 rebase master**（upstream Hexagon 仍在演进，先 PASS M2 正确性再 cherry-pick）。

### 遗留
- v81 工具链验证（M2-0.1 首项）；HTP 访问权限沿用 qnn-work 链已验证结论。
## M2-1 正式规格（2026-08-24 用户定案，m2_1_la_iso 工具）

- **载体**：m2_1_la_iso = 单 LinearAttention minimal net（flatbuffer）+ Hexagon Session + 独立 naive FP32 参考（不复用 MNN CPU LA 内核，防同 bug 假 parity）。
- **state 不藏在 Execution 里测**：HexagonLinearAttention 加 debug-only import/export hooks（MNN_HEX_LA_IMPORT / MNN_HEX_LA_EXPORT_DIR 环境门），显式构造 conv_state_before / S_before，逐次读回 after。
- **D 序列（D = decode recurrent depth/sequential steps，非 feature dim）**：Case A(零状态 1 步) / Case B(随机非零 seeded 1 步) / T8 / T32 / T128 / T256。
- **每步比较 3 对象**：output / conv_state / recurrent S；指标：finite + nonzero + cosine + rel-L2 + max-abs + mean-abs + p99-abs。
- **真实模型 tensor**：至少 LA-early / LA-middle / LA-late 三层（CPU 模型抓真实 io）。
- **HTP 真执行独立 Gate**：onBuildCmd hit + DSP profile 出现 REDUCTION/BINARY/UNARY/RASTER 链；report 两列 Math Gate / HTP Gate，不合一。
- **阈值第一轮只观测**：优先误差增长曲线（out drift / S drift）；D1 错先查 layout/broadcast/decay 位置；D32 掉先怀疑 fp16 reduction 累加/L2norm/decay 放大。
- **FORCE 纪律**：MNN_HEX_LA_FORCE 仅 debug/isolated；生产接入需解决 create 期几何探针在真实 shape 下合法选 hexagon，禁止永久"空 io 无条件接受"。

## M2-1 门（全 PASS 才进 M2-2）

- M2-1A 零状态单步 / M2-1B seeded 单步 / M2-1C T8 / M2-1D T32 / M2-1E T128 / M2-1F T256 / M2-1G LA-early 真实层 / M2-1H LA-middle / M2-1I LA-late / M2-1J DSP/HTP 执行证据。
- M2-2 = CPU prefill state → CPU→HTP bridge → pp8/32/128/512 + tg1（state handoff 先于性能）。

## M2-1 门 Progress（2026-08-25 真机 PASS）

- **结果**：op/linear_attention_m2_1 @ hexagon backend 10（BKQ-AN90 10.40.128.150:42719）→ TEST PASS（failed=0）。
- T001=0.999995 / T008=0.999998 / T032=0.999997 / T128=0.999990 / T256=0.999996（minCos）；state convCos=0.999994 SCos=0.999988；FINAL worstOutCos=0.999990。
- **两根因修复**：① emitBlit offset/stride 字节单位 vs DSP 元素×unitBytes 的 2× 错位（emitBlit 内部 /kb 转元素单位）；② S/convState export 直读 stale → export 前 markHostOutput 登记（不 flush；flush 会改变 command submission 形态，实测 T001 0.999995→0.7623）。
- **M2-1A..F 数学 Gate PASS**；状态（convState/S）PASS；M2-1G..I（真实层）的等价形态（真实维度 H=16/dk=dv=64/D=3072/Kc=4）PASS；M2-1J DSP 执行证据 = DSPOpType RASTER_BLIT/REDUCTION/BINARY/UNARY profile 链 + m2-0-smoke 564 groups。
- **方法学**（防"探针改变被测系统"）：tail-overwrite 只增不删、禁截断；回读前 markHostOutput；每 (case,stage) fresh module。此前所有截断探针结论作废。
- 证据：runs/mobile_000b2_hexagon_la/m2-1-20260824-fix037/m2-1-gate-pass-20260825.md
- 下一步：M2-2（CPU prefill → HTP decode handoff）。

## M2-2 门 Progress（2026-08-25 真机 PASS）

- **第一阶段（handoff）**：pp8/32/128/512 → tg1 全 PASS。M2_2_FIRST_TOKEN_GATE=PASS。pre/out/post 三层对齐（pre=1.0, out=0.999999, post_conv/S=1.0）。
- **关键定位**：export 读取时机——onExecute 内命令入队后立即 export 读到「执行前」旧态（vs-pre=1.0 判别）；修复=export 移 onExecute 开头延迟导出（forward→readMap→再 forward 触发），读出上一步 readMap 同步后的状态。量化对照（naive fp16 量化）排除纯噪声。
- **第二阶段（recurrence）**：pp128 → tg2/tg4/tg8/tg32 全 PASS。M2_2_RECURRENCE_GATE=PASS, FIRST_DRIFT_TOKEN=-1（无漂移）, WORST_OUT_COS=0.999981, WORST_STATE_COS=0.999961。HTP persistent recurrence 自洽（S 写回/复用正确）。
- **结论**：M2-2 门全 PASS → 可进 M2-3 llm_bench 全模型（cpu vs hexagon, -pg 32,8 / 128,16 / 512,32）。
- 证据：runs/mobile_000b2_hexagon_la/m2-1-20260824-fix037/m2-2-handoff-pass-20260825.md + m2-2-recurrence-pass-20260825.md

## M2-3 门 Progress（2026-08-26 真机：引擎集成首通 + 生成正确性定案）

- **首通**：llm_demo hexagon FORCE=1 EXIT=0（prefill 24 LA code=0, decode 1 token）；修复 4D 布局解析 / FAIL:002/005 / prefill 每 token flush。
- **部署层根因**：设备缺 embeddings_bf16.bin → embedding 全零 → 全零 logits（"!!!!!!!!"）。补文件后（508MB, vocab248320×1024×2）恢复。
- **模型切换**：qwen3.5-base-mnn-v2 为坏导出（独立 embedding 仍乱码）；切 readerdirector-mnn-v2（tie_embeddings 自包含）→ CPU 输出正确（prefill 274 tok/s, decode 64.77）。
- **hexagon 乱码隔离**：LA 非根因（MNN_HEX_LA_OFF 后 cos 仍 0.22）；readback 非根因（markHostOutput 逐字节无变化；共享 coherent DMA）；fp16 模型结构坏（Status 4）不可作判别。
- **Conv 4+1 臂定案**：HTP fp16 Conv 正确（D_vs_C1 cos=1.000000）；**hexagon asymmetric-W4→fp16 fallback 反量化语义与任何合理约定不符（B_vs_C1=-0.025）→ 根因 = HexagonConvolution.cpp:748 fallback 路径**。官方支持路径为 symmetric W4 block64。
- 证据：runs/mobile_000b2_hexagon_la/m2-1-20260824-fix037/m2-3-correctness-20260826.md（含追加 1/2）
- 下一步：修 fallback asym 反量化（P6 per-OC weight 对比验证）或导出 symmetric W4（官方路径）或 hexagon 显式 dequant fp16 conv 规避。

## M2-3 / P16-FIX 门 Progress（2026-09-16 真机：DSP RoPE 根因修复，0:23 转正）

> 独立 ExecPlan：`PLAN-20260916-057-dsp-rope-partner-offset-fix.md`（含 15 字段全文）。
> 主导出物：`runs/mobile_000b2_hexagon_la/m2-3-nodealign/report.md` 的 P16-FIX 段与 `p15/P16_FIX_SECTION.md`。

- **根因**：`htp-ops-lib/src/dsp/rope_ops.cc` 的旋转配对/写回偏移用 `half_head_dim`(=128)，
  正确值 `rope_half_head_dim`(=32)；透传区写成 `[half_head_dim, head_dim)`，
  正确为 `[rope_dim, head_dim)`（以 CPU 参考 `MNNRoPEComputeBasic` 为准）。
  另修 fuse 路径 trig 暂存跨 token 越界读（按 `half_head_dim` 分段，实际只有 `rope_dim`）。
- **前置仿真**：`p16_rope_fix_sim.js` 26 组几何与 CPU 参考逐元素一致、0 越界；
  旧算法在 (256,64) 偏差 0.42（自检通过）。
- **G6 内核门 PASS**：L3 FusedRoPE 四分块 0.8714/0.9993/0.7678/0.9993
  → 0.99984/0.99928/0.99944/0.99935，透传区两段逐位不变（differ=0）。
- **端到端（同会话 A/B，CPU baseline 与旧 skel 均逐字节复现历史文件）**：
  0:23 0.956355→**0.994986**（argmax 95826→124483）、12:23 0.990833→0.993881、
  7:7 0.981213→0.990193、0:7 0.971053→0.993291、0:0 逐字节不变（对照）。
- **文本**：24-token「请用三句话介绍唐朝。」0:23 与 CPU **逐字一致**（此前为"…到公元960年…"事实错）。
- **速度**：skel A/B 18 样本中位数持平（无回归）；同会话排序 0:23 < cpu < 12:23。
- **结论变更**：prefill 默认配置 `config_rd_cpu_greedy.json` → `MNN_HEX_LAYER_HTP=0:23`（ADR-050）。
- **未决**：绝对速度依赖设备状态（本次 0:23 vs cpu 仅 2~12%，P15 会话 20%）；
  C4 未融合路径与其它 head_dim/rope_dim 形状只有仿真证据。

## M2-3 / P16-2 Progress（2026-09-16 同会话：「Hexagon 精度赤字」证伪）

- **逐算子新增误差归因**（`MNN_HEX_CHECKPOINT_IN=1` + `p162_incr.js`，`added = cos_in - cos_out`）：
  注意力 `FusedAttention -3.3e-4` / `FusedLinearAttention -7.6e-6` / `FusedRoPE +5.8e-5` 全部干净；
  **误差 100% 来自 W4 dense 投影**（L3/L0 两种层类型 sum(positive added) 都约 4.1e-3）；
  同形状 `mlp/gate` 干净而 `mlp/up` 脏 10~20x ⇒ 逐张量 W4 属性，非形状/分支；层输入 `cos_in = 1.000000`。
- **决定性对照**：新增 `config_rd_cpu_fp32.json`（同模型同权重，precision low→normal，两边逐字节可复现）：
  `cos(CPU fp16, CPU fp32) = 0.995184` vs `cos(HTP 0:23, CPU fp32) = 0.996345`
  ⇒ **Hexagon 0:23 比 fp16 CPU 基线更接近 fp32**，argmax 也与 fp32 一致。
- **判定**：Hexagon 与 fp16 CPU 的分歧 = fp16 噪声地板，**无 Hexagon 专属缺陷可修**；
  P10 门限 0.997187 在 fp16 口径下任何实现都到不了。**P16-2 记为负结果，停止该方向。**
- **反面证据**：24-token 生成中 CPU fp32 给出史实错误（610 年），fp16 CPU 与 HTP 0:23 都给 618
  ⇒ `cos(vs fp32)` 只能当噪声地板，不作任务质量结论（ADR-051）。
