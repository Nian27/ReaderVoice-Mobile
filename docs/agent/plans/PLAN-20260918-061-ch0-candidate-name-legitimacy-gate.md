# PLAN-20260918-061 — CH-0 质量修复 ①：候选人名合法性闸门

## Goal

让 ReaderDirector 的**候选说话人里不再出现非人表面**：把角色发现输出的 cue 子句切片
（`哈哈`/`招呼`/`李寄舟询`/`张三丰知`/`倘若`…）在进入实体集之前拦下，
使"模型答得合规、人却是错的"这一类错误在候选层面不可能发生。

## Current Verified Facts

- 真机 `book-d22a7b3878ee`（《朕真的不务正业》4,327,603 B）按章导演，`scripts/13434/current.jsonl`
  前 20 行：`CHARACTER:boot-哈哈` ×6、`CHARACTER:boot-招呼` ×3、NARRATOR ×9、UNKNOWN ×2
  （`runs/mobile_005_director_real/gold/gold_review.jsonl`）。
- 同 20 行 outcome 全为 `ACCEPTED_ALL`/`VERIFY`，anchor 100% —— 协议指标全绿（ADR-055 反例）。
- 真机 discovery 缓存 `files/chapter_director/characters_book-d22a7b3878ee.json` = **371 个"名字"**，
  其中约 80% 为 cue 子句切片（`runs/mobile_005_director_real/candidate_gate/names_before.txt`）。
- 污染入口可复现：`SpeakerCandidateCompiler(污染实体集).recentMentions(本章窗口文本)`
  会召回 `哈哈`、`招呼`（`data-room/.../m3/NameLegitimacyGateTest.kt` 层 1 断言）。
- 污染自我固化：`DeliveryCueLexicon.classify` 的 `knownEntities` 安全阀（`DeliveryCue.kt`）
  在污染入集后会把污染**永久保护**成"人名"。
- 语料证据分离度（371 候选 / 74 手工标注真名）：`rightKinds>=3` ⇒ 真名 74/74 保留、
  污染 170/298 拦下；`total` 完全重叠（`这一` 1080 次 / 真名最小 3 次）不可用。

## Non-goals

- **不做**实体类型判定（PERSON vs OBJECT）：`神女`（雕像）/`天刀`（称号）这类"非人但非 cue 切片"
  的表面本项仍保留为候选 —— 归 CH-1（身份层）。
- **不做**别名归一 / 身份聚类 / 音色绑定（CH-1/CH-2）。
- **不改**协议：`DirectorDecisionV2` 四态、15 个失败码、`SpeakerRef`(C18)、`ScriptLineCodec` schemaVersion=2 全部不动。
- **不引入**新依赖、不改 Gradle 结构、不新增 UI。

## Invariants

- 不变量 1（原文不可变）：闸门只读正文，不写回 source map。
- 不变量 3/5：`Mention != Identity`、UNKNOWN 是合法结果，**禁止强造角色名** ⇒ 宁缺毋滥。
- 「一次实验只改一个主要变量」：本项只改"名字合法性"这一条链路。
- 「每个跨层字段必须有端到端断言」：见 Validation 的产品路径 Gate。

## Scope

| 文件 | 类型 |
|---|---|
| `data-room/src/main/kotlin/com/readervoice/data/semantic/NameLegitimacy.kt` | 新增 |
| `data-room/src/main/kotlin/com/readervoice/data/semantic/CharacterDiscovery.kt` | 新增 |
| `data-room/src/main/kotlin/com/readervoice/data/semantic/DeliveryCue.kt` | 修改 |
| `data-room/src/main/kotlin/com/readervoice/data/semantic/SpeakerCandidateCompiler.kt` | 修改 |
| `app-android/src/main/java/com/readervoice/app/ChapterDirectorService.kt` | 修改 |
| `data-room/src/test/kotlin/com/readervoice/data/m3/NameLegitimacyGateTest.kt` | 新增 |
| `tools/mobile005/{candidate_audit,name_evidence,name_gate_probe}.js`、`speaker_gold.js` | 新增/修改 |

## Baseline

| 指标 | 基线（2026-09-18 真机） |
|---|---|
| 实体集规模 | 371 |
| 真机 20 段中非人说话人 | 9（45%） |
| 真机同章编译候选 | `[话语, 李寄舟, 张三丰]`（含污染） |

## Milestones

- **M1 离线规则评估**：`name_gate_probe.js` + 手工标注（74 真名 / 7 称谓）→ 要求"真名零误伤"。
- **M2 闸门实现**：`NameLegitimacy`（语料证据 + 封闭类 + 残留归并）与 `CharacterDiscovery`（三段式）。
- **M3 产品路径接线**：Service 委托 `CharacterDiscovery`；缓存加 `gateVersion`；落盘审计。
- **M4 Gate**：真实书发现 + 产品路径对照（污染召回可见 / 干净集不再召回 / 剧本行 0 污染）。
- **M5 真机复跑**：设备跑同一章 → `speaker_gold.js nonperson` 报非人说话人数量。

## Progress

- M1 ✅ 2026-09-18：371 候选逐个复核 → `labels_candidate.json`（74 真名 + 7 称谓）；
  `rightKinds>=3` 分离度确认（真名最小 3 / 污染最大 267；`total` 不可用）。
- M2 ✅ 2026-09-18：`NameLegitimacy.kt`（滚动哈希一次全文扫描的语料证据）、`CharacterDiscovery.kt`
  （harvest → 闸门 → 干净集再 harvest）、`DeliveryCue.kt` 叹词绝对判据、`SpeakerCandidateCompiler` 封闭类否决。
- M3 ✅ 2026-09-18：Service 改为委托（发现逻辑从 app 层搬到 data-room，**产品与 Gate 同源**）；
  缓存 `gateVersion=1`；`name_gate_<bookId>.txt` 审计落盘。
- M4 ✅ 2026-09-18：`NameLegitimacyGateTest` 5 测试全绿；`:data-room:test` + `:parser:test` 全套件 PASS（无回归）。
  真实书结果：371 → 81（74/74 真名保留，290/297 污染拦下，残留 7 个均为称谓/指代）。
- M4b ✅ 2026-09-18：**跨书泛化验证**（`CharacterDiscoveryCrossBookGateTest`，3 本书）——
  **发现真实局限**：长篇《北宋穿越指南》原始候选 3,883 → 接受 1,587，抽样仍有大量常用副词/动词短语
  （`亲自`/`不由`/`不禁`/`只得`/`后方`/`高兴`/`作揖`…）存活。结论：语料统计**无法区分高频副词与真名**，
  需要 cue 子句**结构判据** + 常用副词封闭类词表（登记为 O-3，本轮不修）。
  顺带回滚 3 处跨书误杀（`渡法` 被"法"、`莫将` 被"将"、`弘心首座` 被"心"）。
- M5 ⏳ 阻塞：设备从 USB 掉线（`adb devices` 空），非代码问题。

## Decisions

- **D1**：闸门必须建立在**全文语料证据**（右邻字符多样性 + 独立短语出现），而不是继续扩词表。
  依据：`total` 与形态词表都无法分离（`这一` 出现 1080 次；`话语` 形态干净）。
- **D2**：阈值 `rightKinds >= 3 && independentCount >= 1`。宁缺毋滥（不变量 5）：
  只出现 1–2 次且每次都在 cue 里的名字降级为 UNKNOWN。
- **D3**：叹词/拟声是**绝对判据**，排在 `knownEntities` 安全阀**之前** ——
  否则污染一旦入集，安全阀会把它永久锁死（这是本次事故的自我固化机制）。
- **D4**：发现逻辑搬进 data-room（`CharacterDiscovery`），因为"Android 私有方法"= Gate 测不到
  = "编译绿 ≠ 数据通了"的温床（本项目已 4 次踩坑）。
- **D5**：`NAME_PLUS_RESIDUE`（真名 + 1~2 字残留）判据早于语料判据：拒因更可审计（记录归并到谁）。
- **D6**：称谓后缀（`大师/禅师/师太/首座/娘娘/圣姑`…）豁免"结尾虚词字"检查，
  否则 `风陵师太`、`弘心首座` 会被误杀（实测各误杀 1 次）。

## Experiments

| id | 变量 | 基线 | 结果 | 结论 |
|---|---|---|---|---|
| E1 | `total` 阈值 | — | 真名 3 / 污染 1080 完全重叠 | 不可用 |
| E2 | `rightKinds >= 3` | 371 名 | 真名 74/74，污染拦 170/297 | 采用（核心判据） |
| E3 | + 封闭类词表 | E2 | 污染拦 290/297 | 采用 |
| E4 | + 残留归并提前 | E3 | 拦截数不变，拒因可审计性提升（NAME_PLUS_RESIDUE 48） | 采用 |
| E5 | BAD_FINAL 含 `心`/`力` | — | `弘心首座` 误杀 | 移除该字 |
| E6 | 虚词前缀规则 | E4 | `虽然不知`（=虽然+不知）被拦 | 采用 |

## Validation

**离线段**

```bash
node tools/mobile005/name_gate_probe.js \
  runs/mobile_005_director_real/candidate_gate/source.txt \
  runs/mobile_005_director_real/candidate_gate/characters_cache.json \
  runs/mobile_005_director_real/candidate_gate/labels_candidate.json
# 期望：name_recall_kept=74/74，pollution_killed=290/297
```

**产品路径段**

```bash
./gradlew.bat --offline :data-room:test --tests "com.readervoice.data.m3.NameLegitimacyGateTest"
```

4 层断言（缺一不可）：

1. **向量定位**：污染实体集 `recentMentions(本章窗口文本)` 必须召回 `哈哈`/`招呼`（证明测试对事故敏感）；
2. **编译期防御纵深**：即使实体集被污染，`compile()` 输出也不得含封闭类表面；
3. **发现期闸门**：干净实体集下候选仅 `[李寄舟, 张三丰]`；
4. **剧本行**：确定性 decider 跑完整 runner，说话人不得落在任何被拦表面上。

**真机段（M5，待执行）**

```bash
node tools/mobile005/speaker_gold.js nonperson <拉回的 current.jsonl> <name_gate_book-*.txt>
# 期望：非人说话人 = 0（基线 9/20）
```

## Rollback

- 代码：本项为纯新增/等价替换；`git revert` 对应提交即可，无 DB 迁移、无数据格式变化。
- 缓存：`gateVersion` 提升会让旧缓存自动失效；回退代码后旧缓存（gateVersion=0）恢复可用。
- 无用户数据破坏面（只读正文 + 重算名字集）。

## Artifacts

- `runs/mobile_005_director_real/candidate_gate/report.md`（本项完整报告）
- `runs/.../candidate_gate/{GATE_RESULT.md, PRODUCT_PATH_RESULT.md, gate_probe_result.txt}`
- `runs/.../candidate_gate/{characters_cache.json, names_before.txt, audit_before.json, labels_candidate.json}`
- `runs/.../candidate_gate/sample_script_lines.jsonl`
- 设备侧：`files/chapter_director/name_gate_<bookId>.txt`、`characters_<bookId>.json`（gateVersion=1）

## Open Issues

- **O-1** 非人实体（雕像/称号/指代）仍可成为候选 ⇒ 需要 `NarrativeEntity.entityType` 判据（CH-1）。
  量化：本书 81 名中 7 个（`娘娘/天刀/圣姑/神女/小二/中年男子/红衣少女`）。
- **O-2** 极低频真名（1–2 次、全在 cue）会被降级为 UNKNOWN（刻意取舍）。
- **O-3 ★ 长篇欠筛（已验证）**：`rightKinds>=3 && independentCount>=1` 是**必要不充分**条件。
  《北宋穿越指南》（113k 段落）接受 1,587 个名字，抽样仍有 `亲自/不由/不禁/只得/后方/高兴/作揖/下意识/下定决心`
  等短语。根因：语料统计无法区分高频副词与真名（`亲自` 出现 984 次、右邻多样、也有独立出现）。
  修法方向（建议单独立 milestone）：① cue 子句**结构判据**（名字块后不得还挂动词短语）；
  ② 常用副词**封闭类词表**；③ 候选**预算/排序**（按 cue 主语证据取 top-K）。
  回归下限已写入 Gate：跨书污染率 ≥ 40%。
- **O-4** 真机复跑未做 ⇒ 尚无 `speaker_gold.js nonperson` 数字。**当前阻塞：设备 USB 掉线**。

## Handoff

- 下一步：M5 真机复跑 → 非人说话人 9 → 0；随后 O-3（长篇欠筛）与 P0-B 真机续跑验证。
- 闸门语义若变更，**必须**提升 `NameLegitimacy.GATE_VERSION`（设备缓存键）。
- CH-1 接手 O-1：`BootstrapCharacterStore` 目前把所有名字硬编成 `entityType="PERSON"`。
- 跨书复现命令：`./gradlew.bat --offline :data-room:test --tests "com.readervoice.data.m3.CharacterDiscovery*"`
