# PLAN-20260821-051-mobile-000b-qnn-sm8850-spike — ReaderDirector QNN-SM8850 feasibility spike

## Goal

以冻结 ReaderDirector Qwen3.5 int4 为输入，核验其能否生成 SM8850（`87/v81`）MNN/QNN
离线 context，并且只在转换通过后才进入真机加载、等价性与性能验证。

## Owner / Date / Status

- Owner：Codex + VicenTrent
- Date：2026-08-21
- Status：**closed — M1 PASS / M2 FAIL / M3-M4 paused**

## Problem and success condition

问题不是“设备是否有 NPU”，而是该精确 Qwen3.5 hybrid 图能否被 MNN 3.6.1 QNN backend
完整编码。成功必须同时存在可审计的 `87/v81` context/config、真机 QNN/HTP loader 证据、
VS8 正确性与同条件性能数据；任一缺失均不可宣称 NPU 部署成功。

## Current Verified Facts

- 真机为 SM8850 / Android 17，目标固定为 `soc_id=87`、`dsp_arch=v81`。
- 官方 MNN 2.37 QNN dependency 不含 V81；本机 QAIRT 2.48 含 V81 runtime 与 Linux host
  converter，故 SDK/工具链层 M1 通过。
- `training/models/readerdirector-mnn-v2` 是 CPU-target baseline。正式 NPU 尝试使用隔离
  的 text-only Qwen3.5 int4/group64 导出，参数为
  `generate_for_npu,seperate_embed,sym,act_bit=16`；不重写 CPU baseline。
- VS8 CPU MNN fidelity 基线为 V exact 21/30（70.0%）、E exact 18/30（60.0%）；它不是
  QNN 成功证据，也不是端到端任务准确率。

## Non-goals

- 不把 CPU/OpenCL 性能调参伪装成 NPU 验证。
- 不修改主工程、CPU MNN baseline、VS8 gold 或设备上的既有 probe。
- M2 失败后不构建/安装 QNN APK，也不对 partial graph 做 HTP 性能测试。

## Scope

- In：隔离 MNN/QNN host build、WSL/QAIRT conversion、产物与日志审计。
- Out：CPU probe 性能调参、CosyVoice/HiFT 结论外推、主工程接入、未通过 M2 时的 Android
  QNN APK 构建或安装。

## Baseline

- ReaderDirector CPU MNN int4 已能在真机推理，是本轮唯一有效端侧 fallback。
- M2 前的正确性参考仅为 VS8 CPU MNN fidelity：V 21/30、E 18/30；还未有 QNN 输出。

## Invariants

1. 固定 `87/v81`，绝不以 V79/SM8750 冒充 SM8850。
2. 不修改 CPU 模型、VS8 gold 或既有 Android CPU probe。
3. `compilefornpu` 的中间 C++/cache 不是 context binary；partial graph 一律不可部署。
4. 共享 MNN source 的诊断 patch 必须保留副本并在结束前还原、重建。
5. M2 未过，M3 Loader 和 M4 性能/正确性不得启动。

## Milestones

- M1 — SDK/host/model contract：**PASS**。QAIRT 2.48、WSL Linux host、`87/v81` 和正式 NPU
  export contract 均已核验。
- M2 — offline graph/context：**FAIL**。无完整 context binary / config，后端不支持该 hybrid
  图，详见失败记录。
- M3 — device QNN HTP loader：**PAUSED**，严格依赖 M2。
- M4 — VS8 equivalence and performance：**PAUSED**，严格依赖 M3。

## Progress

- M1 ✅ 2026-08-21：QAIRT 2.48 / WSL Linux host / SM8850 `87/v81` / NPU export contract 已核验。
- M2 ❌ 2026-08-21：最终 QNN-010 退出码为 1，未生成完整 context 或 config。
- M3 ⏸️：依赖 M2，未启动。
- M4 ⏸️：依赖 M3，未启动。

## Decisions

- QNN/HTP 仍是 SM8850 的目标优化路径，但冻结 MNN 3.6.1 source 的这一个模型组合不进入
  Android 集成。
- 将静态 recorder/thread-local 结论降级为诊断：只有更新后端 creator 或官方匹配 artifact 才
  允许重新打开 M2。
- 本计划的 M2 FAIL 只关闭这一次 offline-context 尝试，不否定 Qwen3.5 的 MNN runtime
  支持，也不阻塞 ReaderDirector CPU MNN 接入、RenderUnit cache 或 Android 阅读闭环。

## Experiments

| id | Variable | Result | Decision |
|---|---|---|---|
| QNN-000A | official QNN 2.37 audit | 仅到 V79 | FAIL；不可用于 SM8850 |
| QNN-000B | local QAIRT audit | QAIRT 2.48 含 V81 host/runtime | PASS；进入 conversion |
| QNN-001 | Windows online QNN build | `Can't Find type=32 backend` | FAIL；非 LLM offline converter 路径 |
| QNN-002 | Windows converter build | `compilefornpu.exe` `0xC0000005` | FAIL；切换 Linux host |
| QNN-003 | CPU-target int4 Linux conversion | `generateIO` PASS，Step2 null source SIGSEGV | FAIL；CPU-target 不是 NPU artifact |
| QNN-004 | NPU-target fused export | Windows visual `onnxslim` native crash | FAIL；转 text-only 隔离导出 |
| QNN-005 | text-only export with onnxslim | language ONNX 后仍 native crash | FAIL；移除已证实崩溃点 |
| QNN-006 | text-only no-onnxslim export | Step2 仍 SIGSEGV，且缺正式 NPU 参数 | FAIL；仅诊断资产 |
| QNN-007 | official-contract text-only export | artifact audit PASS，Step2 仍 SIGSEGV | FAIL；排除导出契约缺失 |
| QNN-008 | block64 fallback diagnostic patch | 可越过 null-weight SIGSEGV 并进入 Step3 | Diagnostic pass；不是完整 graph |
| QNN-009 | recorder `thread_local` diagnostic | 仍无完整 graph/context | FAIL；不把 truncated C++ 归为独立根因 |
| QNN-010 | fixed 008 model, 87/v81 final conversion | `FusedRoPE` type 306、Binary/Mul、hybrid Linear/lm_head 编码失败 | **M2 FAIL** |

## Failure analysis

QNN-010 的 `wsl_qnn_87_v81_010_threadlocalfix.stdout.log` 直接记录：

- `Not registered type 306, .../FusedRoPE`；
- `Not supported Binary type` 与 `Don't support type 7, .../Mul_output_0`；
- `in_proj_z`、`q_gate_proj` 和 `lm_head` 的 convolution shape errors。

因此，QAIRT 在 Step3 拒绝截断 C++ 是后果，不是可以通过输出缓存并发修补独立解决的根因。
“MNN 宣称支持 Qwen3.5”不等于此冻结 hybrid 架构在这份 MNN 3.6.1 QNN backend 中已有所有
converter creators。

## Validation

- `runs/mnn_llm/qnn_sm8850/wsl_qnn_87_v81_010_threadlocalfix.stdout.log`
- `runs/mnn_llm/qnn_sm8850/wsl_qnn_87_v81_010_threadlocalfix.stderr.log`
- `runs/mnn_llm/qnn_sm8850/wsl_qnn_87_v81_010_threadlocalfix.exitcode.txt`
- `runs/mnn_llm/qnn_sm8850/model_npu_int4_g64_sym_sepembed_textonly_008/`
- `runs/mnn_llm/qnn_sm8850/qnn_compilefornpu_float_fallback_009.patch`
- `runs/mnn_llm/qnn_sm8850/report.md`

## Artifacts

- `runs/mnn_llm/qnn_sm8850/model_npu_int4_g64_sym_sepembed_textonly_008/`
- `runs/mnn_llm/qnn_sm8850/wsl_qnn_87_v81_009_fallbackfix/`（partial graph，禁止部署）
- `runs/mnn_llm/qnn_sm8850/wsl_qnn_87_v81_010_threadlocalfix/`（partial graph，禁止部署）
- `runs/mnn_llm/qnn_sm8850/qnn_compilefornpu_float_fallback_009.patch`
- `runs/mnn_llm/qnn_sm8850/report.md`

## Rollback

所有失败 cache/log/patch 保留。两个共享 MNN 诊断 patch 已还原，Linux `compilefornpu` 已对
还原源码重新编译并可输出 usage；未构建/安装 QNN APK，未修改设备或 CPU probe。

## Risks

- 上游“Qwen3.5 support”可能覆盖不同 attention/export variant，不能替代针对 frozen model 的
  conversion gate。
- 更新 MNN revision 或采用官方 QNN artifact 时可能改变 token 行为，必须从 M1 开始重新跑，且
  不能拿 CPU VS8 fidelity 当作 QNN correctness pass。

## Open Issues

- 哪个上游 MNN revision 首次为该 Qwen3.5 hybrid variant 提供完整 QNN converter creators，尚未
  在本轮验证。
- 是否存在与 ReaderDirector 精确权重/attention 架构和 QAIRT 2.48 匹配的官方 QNN artifact，尚未
  获得。

## Handoff

QNN/HTP 保持 SM8850 的正式优化目标，但此 source revision 的 M2 已失败。它是性能优化支线，
不阻塞 Android 阅读闭环。QNN 支线的下一项工作是取得带 Qwen3.5 hybrid QNN creators 的更新
MNN revision，或与该确切模型结构匹配的官方 QNN artifact；之后重启 M1 → M2 → M3 → M4。
当前 CPU MNN int4 是唯一已验证端侧路径。
