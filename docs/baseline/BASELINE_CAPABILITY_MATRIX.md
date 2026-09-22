# BASELINE_CAPABILITY_MATRIX.md — 三条基线能力对比与使用方式

**更新：2026-08-12（TASK-000）**

| Baseline | 资产 | 核心能力 | ReaderVoice 用法 | 注意 |
|---|---|---|---|---|
| Chapter Regex | `txtTocRule.json` 26 条规则（目录/数字/大写数字/数字混合/Chapter/特殊符号/顶格/双标题/书名序号/字数分割/通用/默认 等 family） | 章节/目录候选生成；`Pattern.MULTILINE` 执行 | **Structural Compiler 的 LegacyChapterRulePack 初始规则**（TASK-020）；regex 只产候选，最终归 Global Resolver | 纯数字规则风险最高，不能单靠 Regex |
| v90.7 多角色 | `mingwuyan_v907` 规则 JSON（14,058 行 JS） | 12 能力域：对话检测/发音人轮询投票/别名正负证据图/共现/合并拆分/性别年龄/临时换声/固定音色/失败保护；阈值 1.5/1.0/4.0 | **Requirements + Hard Cases + Behavior labels**（TASK-050/070）；12 域 → 13 决策（7 KEEP + 6 REDESIGN + 0 DROP） | 行为基线非产品源码（ADR-011）；远程上传不迁移（ADR-012）；含硬编码智谱 key（不复用） |
| CosyVoice3-MNN | `CosyVoice3-MNN-formal` v1.1.0 | LLM→Conditioner→2-step Flow→HiFT 全链；SM8850 受限 NPU；热态 RTF 0.79-0.96；VoiceProfile + enrollment | **ReaderVoice TTS Engine 抽取源**（TASK-120，CV-001~013） | JNI 符号漂移/无原生取消/Conditioner 子进程三隐患；V1 不重训 |

## 三条线的边界（禁止混为一谈）

```text
Chapter Regex   → parser/（结构）
v90.7           → core-character/ + core-director/（语义）+ training/（数据）
CosyVoice3-MNN  → tts-cosyvoice/（声学）
```

- 角色语义与声学产物分离：NarrationIR（语义） vs AudioAsset（声学），只有 VoiceBinding 关联。
- v90.7 的行为验证了本项目设计（临时换声两层分离、固定锁优先、负证据 hard block），但**存储与执行层全部重设计**。
