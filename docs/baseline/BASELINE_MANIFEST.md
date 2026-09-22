# BASELINE_MANIFEST.md — 基线资产总清单（冻结）

**更新：2026-08-12（TASK-000）** | 本文件是 `docs/BASELINE_ASSETS.md`（完整盘点）的冻结索引。

## 三条项目基线

| # | 基线 | 身份 | 冻结证据 | 文档 |
|---|---|---|---|---|
| B1 | Chapter Regex | `FUN-legado/assets/defaultData/txtTocRule.json`，26 条规则，`tts-rule` 体系 | 文件路径 + 规则 family 清单 | `CHAPTER_REGEX_BASELINE.md` |
| B2 | 多角色 v90.7 | `mingwuyan_v907`，Legado 规则 JSON，SHA-256 `35c7d6…842e` | `research/third_party/legado-v907/V907_MANIFEST.json` | `V907_PROVENANCE.md` + `V907_CAPABILITY_MATRIX.md` |
| B3 | CosyVoice3-MNN | `CosyVoice3-MNN-formal` v1.1.0 / MNN 3.6.1 | 源仓库路径 + 性能数据 | `COSYVOICE3_MNN_BASELINE.md` |

## 关联资产（非项目基线，按需引用）

| 资产 | 位置 | 用途 |
|---|---|---|
| 完整盘点 | `docs/BASELINE_ASSETS.md` | 全部资产身份/状态/风险 |
| 模型包 | `E:\AndroidStudioProjects\mnn-model-3.6.1`（+ c4fuse 变体） | TTS 部署权重（int4 LLM + fp16 Flow2step + fp32 HiFT） |
| 蒸馏 lab | `cosyvoice3-distill-lab` / `mnn-cosyvoice3` | Flow 蒸馏复现（PoC 通过，非发布门槛） |
| FUN-legado Kotlin 系统 | `E:\AndroidStudioProjects\FUN-legado` | 工程模式参考（修正回灌/失败保护/硬锁） |
| TTS Server | `TTS_Server_Android_Original` | 参考（与 CosyVoice 不同源） |

## 冻结规则

1. 基线身份变更（新版本/改版）必须新建 manifest 条目，禁止覆盖；
2. 数字一律本地脚本重算（教训：早期 grep 误计 57,126 行 **已作废**，权威为 node 实测 14,058 行；全仓只允许一套当前权威数字）；
3. 第三方资产（B2）只读，公开材料只含自写分析（ADR-009/011）。
