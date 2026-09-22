# v90.9 增量分析 — 别名检验与发音人轮询（自写分析，2026-09-17）

> **资产边界**（遵循根 AGENTS「第三方资产」条）：原始 JSON 与展开 JS 存于
> `research/private/legado-v909/`（RESEARCH_ONLY、LICENSE_UNKNOWN，不进公开 Git / APK / 模型包 / 公开数据集）。
> 本文件**只含自写分析**，不含第三方代码片段。引用行号指向私有展开件，仅作内部溯源。

## 0. 这是什么

| 项 | 值 |
|---|---|
| 名称 | 多角色朗读2.85**发音人轮询**+**增强别名检验**v90.9 |
| version / author | 909 / 命無言、萌新、M |
| 形态 | SillyTavern 规则对象：`code`（JS 引擎，783 KB / 13,253 行）+ `tags`（4,078 发音人标签）+ `tagsData`（4,075 份标签配置） |
| 与 v90.7 的关系 | v90.7 是同一工程的上一版（`docs/baseline/v907/` 已有 12 域逆向）；v90.9 是**功能增量版** |

**关键区分**：标题里的"发音人轮询"指 **TTS 发音人标签的轮询分配**（voice tag rotation），
**不是**"谁在说话"的归属。v90.7/v90.9 的"谁在说话"仍走 **API/LLM 裁决**（`processCharacter` → 投票 → 别名校验）。
⇒ 这两件事在本项目里分属两层：**Director（谁+怎么说）** 与 **Voice 分配（用哪个音色）**。参考时不要混淆。

## 1. 可直接迁移到 ReaderDirector 的四件事

### ① 群体称呼 ≠ 单人（单人/群体禁止互并）

v90.9 用一组高精度形态判据识别"群体称呼"：显式表（众人/诸人/二人/一行人…）、
`数量词 + 身份`（几名弟子/三个修士/一群女子）、`成对描述`（一男一女/一高一矮）、以及群体量词收尾。
一旦一方是群体、另一方是单人 ⇒ **禁止合并**。

**本项目落地**：`NameEvidence.isGroupName()` / `mergeBlockReason()`（自写实现），
并接入两处：`RuleSpeakerBaseline`（群体称呼不得当说话人）与 `SpeakerCandidateCompiler`（不得进候选）。
**这补上了我们一个真实缺口**：`SegmentType.GROUP_SPEECH` 早已声明却从未被赋值，
且 `众人` 类词只出现在规则层的 STOPLIST 里、候选编译器完全不设防。

### ② 身份证据要用【有界】文本模式，而不是"关键词命中"

v90.9 的两类模式都**不跨句、有 gap 上限**：
- 正向（同一人/别名）：`A ⟨≤50 字且无句末标点⟩ 别名提示词 ⟨同⟩ B`，外加括注形式 `A（B）`；
  提示词是显式词表（本名/真名/又名/又称/又叫/也叫/名叫/就是/正是/同一人/化名/被称为/介绍为…）。
- 反向（非同人）：`A 不是 B`、`A 与 B 不是同一人`、`A 与 B 是两个人`、
  `A 只是 B 的⟨亲属词⟩`（**关系 ≠ 同一人**）、`A 对/向 B 说话`（**对话 ⇒ 不同人**）。

**本项目落地**：`NameEvidence.aliasEvidence()` / `contradictionEvidence()`，
并在 `ContextBuilder` 里作为 `ALIAS_EVIDENCE` / `NEGATIVE_EVIDENCE` / `MERGE_BLOCK` 证据项进入 E# 编号。
**意义**：我们原来的 `identityConstraints` 只有断言式 `"A != B"`；现在它可以**带出处**（哪句话说的），
这正好补上 Director 需要的"可审计证据"，也让 G4 的"证据使用率"可评。

### ③ 决策结局要【类型化】，而不是二值

v90.9 的别名决策状态机给出的是带类型的结局，例如：
`alias_api_true_final_false`（模型说"是"、但本地 gate 挡下）、
`is_alias_reuse_existing`、`is_alias_main_missing`、`is_alias_api_main_missing`（**引用了不存在的主名**）、
`alias_blocked_to_not_alias`、`not_alias`。

**本项目落地（M3 步骤 4 的 Validator 规格）**：失败不写 `FAIL` 一个词，而写**类型化原因**，至少要区分：
`SCHEMA_UNPARSABLE` / `SEGMENT_ID_MISMATCH` / `SPEAKER_OUT_OF_CANDIDATES` / `EVIDENCE_OUT_OF_SET` /
`DELIVERY_ENUM_INVALID` / `INTENSITY_OUT_OF_RANGE` / **`SPEAKER_ID_MISSING`（悬空引用）**。
"悬空引用"这一类是我们原 V1–V6 里没有的，而真实模型很容易产生（M0 已见相似形态）。

### ④ 每类证据都要有【预算】

v90.9 给每一类上下文/证据都配了显式上限（最近 N 章的角色数、正图谱边数、反图谱边数、共现 pair 数、
每条证据的字符上限……）。这是长期迭代换来的"prompt 不膨胀、噪声不淹没"纪律。

**本项目落地**：`ContextBuilder` 证据装配改为**按类预算**：
`DELIVERY_CUE ≤2`、身份证据 ≤3、`RULE ≤1`、`SPEAKER_CUE ≤1`，总上限 7。

## 2. 明确【不采纳】的部分（以及原因）

| v90.9 做法 | 我们的处置 | 原因 |
|---|---|---|
| "谁在说话"走 API 投票（v90.7 同） | **不采纳** | 本项目使命是**完全端侧**；且其投票默认路径是死代码、平票取"最晚返回"、无结果时 `Math.random()` 兜底（见 `docs/baseline/v907/03_SPEAKER.md`）——不确定性与联网都不可接受 |
| 发言人的 `reason` 自由文本参与判定 | **部分采纳** | 采纳其"理由与结论矛盾则推翻"的**高精度**检查思路；但按其自身注释的教训，**不用泛词扫描**（附身/冒充/假冒等），只做字段-理由的显式矛盾 |
| 正/反图谱 + 共现统计的完整子系统 | **暂不采纳** | 我们已有 `hasHardNegative` / `identity_evidence` 表与硬负例不变量；先落地文本层判据，图谱规模留给后续里程碑 |
| 发音人标签轮询（全局计数、最少优先） | **记入 Voice 层待办** | 属于"用哪个音色"的问题，与本轮 Director 无关；其"全局使用计数 + 最少优先 + 硬锁不被覆盖"的策略值得在 Voice 分配里程碑照搬 |

## 3. 本轮实际改动（代码）

```
新增 data-room/.../semantic/NameEvidence.kt     群体判据 + 别名/反向证据（自写；有界模式）
改   RuleSpeakerBaseline.cueOf                  群体称呼不得当说话人
改   SpeakerCandidateCompiler.add               群体称呼不得进候选
改   ContextBuilder 证据装配                     身份证据（ALIAS/NEGATIVE/MERGE_BLOCK）+ 按类预算
新增 data-room/src/test/.../m3/NameEvidenceTest.kt  5 测试（含"不得跨句 / gap 有上限"的反向保护）
```

## 4. 待办（下一轮可继续挖）

```
· v90.9 的"最近 N 章"上下文策略（章节级记忆）与我们的窗口模型对比 —— 可能改善长程说话人一致性
· v90.9 的临时换声/自然年龄审计提示词（candidateSet + acceptedAll/allAcceptedVerification/
  downgrade/reject/verify 的结局空间）—— 比我们当前 accept/fail 二值更细，值得对照设计 DirectorDecisionV2
· 发音人标签轮询策略（Voice 分配里程碑）
```

## 5. 产物

```
research/private/legado-v909/{v90.9.original.json, v90.9.code.extracted.raw.js, v90.9.meta.json}
docs/baseline/v909/01_ALIAS_AND_SPEAKER_DELTA.md   ← 本文件（自写分析）
data-room/.../semantic/NameEvidence.kt, m3/NameEvidenceTest.kt
```
