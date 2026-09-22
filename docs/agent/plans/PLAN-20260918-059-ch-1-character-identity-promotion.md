# PLAN-20260918-059 — CH-1 character-identity-promotion（剧本角色 → 稳定身份）

> 状态：**规划态（本轮不实现生产代码）**。经 2026-09-18 MOBILE-005 架构评审修订：五处修订（C18 / 双路径身份解析 / Character-Voice 所有权 / 证据化 importance / 主线-偿债线分离）已写入 Normify 模型。
> 架构真相源：`C:\Users\Administrator\normify-readervoice-mobile\normify.html`（94 模块 / 180 API / 15 policy 规则 / 5 change）。

## Goal

把剧本里的说话人从"一次性选择结果"变成**可累积、可解释、可锁定**的长期身份：`Mention → 身份解析 → CharacterProposal(PROVISIONAL) → 证据累积 → PromotionPolicy → PERSISTENT`；并落地 C18，使持久 `ScriptLine` 只携带 `SpeakerRef(Narrator|Unknown|CharacterId)`，绝不携带 request-scoped 的 `C#`。

## Current Verified Facts

| 事实 | 证据 |
|---|---|
| 局部候选 ID（C0/C1）已在协议层用对：模型只见局部 ID，`identityId` 不进 prompt | `data-room/.../semantic/ContextBuilder.kt`、`DirectorSelectionPrompt.kt`；M3 测试断言 |
| `C# → identityId` 的映射**目前隐含**在 `ScriptLineBuilder`/`ChapterDirectorRunner` 内，没有具名单元 | Normify 模块 `rvm.directing.local-candidate-resolver`（source 指向这两个文件） |
| 持久 `ScriptLine.speaker` 现在是字符串形态（`NARRATOR` / `C#` / `UNKNOWN`），**C# 有泄漏进持久行的可能** | `ScriptLineBuilder.kt`；真机 138 行产物 `runs/mobile_005_director_real/app_chapter_run/script_lines.jsonl` |
| 真机路线当前只有自举只读 store（身份=名字、无聚类） | `data-room/.../snapshot/BootstrapCharacterStore.kt` |
| 角色层已有：实体/聚类/别名/提及/证据/合并/拆分 + 旧版迁移 | `data-room/.../character/{CharacterModel,CharacterStore,CharacterCompiler,LegacyBehaviorAdapter,SchemaMigrationV2}.kt` |
| 角色层**缺**：Mention 一等层、Resolver（推理前）、AliasResolver、Proposal、Importance、Lifecycle | 目录 `core-character/` 仅 `AGENTS.md`；Normify `planned_count=12` |
| 群众/网友类污染已在候选层有闸门（群体名识别） | `data-room/.../semantic/NameEvidence.kt`（`isGroupName`）+ `rvm.understanding.candidate` |
| 根 AGENTS 不变量 3/4/5/7/8 直接约束本任务 | `/AGENTS.md` Core invariants |
| policy 已能机器拦截越层依赖（含 1 条真实债务） | `normify-readervoice-mobile/policy.yml`（15 规则）；债务 `rvm.rendering.render-unit → rvm.directing.freeform-legacy` |

## Non-goals

- ❌ 不做 TTS / VoiceProfile 绑定（属 CH-2）。
- ❌ 不做调度接线与 COMMITTED 替换策略（属 CH-4）。
- ❌ 不拆 Gradle 模块、不搬家包（属 CH-3 偿债线）。
- ❌ 不改 Director 协议字段（`DirectorDecisionV2` 保持冻结；只改**持久化形态**）。
- ❌ 不重训模型、不改 prompt 协议版本（本轮纯结构/身份层）。
- ❌ 不让 Normalizer 参与身份修复（C10）。

## Invariants

根 `AGENTS.md`：**3**（Mention ≠ Identity ≠ Embodiment ≠ VoiceState）、**4**（Character ≠ VoicePack）、**5**（UNKNOWN 合法，禁止强造角色名）、**6**（False Merge > False Split）、**7**（Performance causality）、**8**（User Locked 最高）、**15**（无自动远程上传）。
架构契约：**C3**、**C4**、**C8**、**C9**、**C10**、**C11**、**C18**（新增，本轮评审冻结）。
policy 规则：`layers-main`、`forbid-store-direct-use`、`forbid-character-to-rendering`、`forbid-freeform-in-production`。

## Scope

**新增（planned → 实现）**
```
rvm.understanding.mention          MentionDiscovery / MentionCandidate（一等层）
rvm.character.alias-resolver       AliasResolver（本名/昵称/称谓/称号 → 同一身份）
rvm.character.resolver             CharacterResolver（推理前身份解析）
rvm.character.proposal             CharacterProposal + PROVISIONAL
rvm.character.importance           CharacterEvidenceVector + PromotionPolicy
rvm.character.lifecycle            CharacterLifecycle（状态机 + 可审计转换）
rvm.directing.local-candidate-resolver  CandidateMap + SpeakerRef 解析（C18 强制点）
```
**修改**
```
rvm.understanding.candidate        compile 时同时产出 CandidateMap
rvm.directing.script-line          speaker 由 String → SpeakerRef（持久形态）
rvm.directing.runner               链路插入 local resolve 步骤
rvm.character.model / read-port / store / bootstrap-store   接入证据向量与 PROVISIONAL
rvm.contracts                      C3/C18 状态由 planned 转已落地证据
```
**不动（明确保留）**
```
rvm.ingestion.**                   已过 M1 Gate，禁止重写
rvm.understanding.segmenter/rule-speaker/delivery-cue/name-evidence   已过 M2 Gate
rvm.directing.{protocol,prompt,normalizer,validator}                  协议与四态已冻结
rvm.persistence.revision/correction/invalidation                      版本与修正底座
```

## Baseline

| 指标 | 当前值 | 证据 |
|---|---|---|
| 真机整章 segments | 140 | `runs/mobile_005_director_real/app_chapter_run/stats.json` |
| lines / coverage | 138 / 98.57% | 同上 |
| canonical anchor | 100%（138/138 `text.length == span`） | 同产物校验 |
| speaker 形态 | `NARRATOR`111 / `C#`27 / 无 UNKNOWN | `script_lines.jsonl` |
| 持久身份 | 无（自举 store，身份=名字） | `BootstrapCharacterStore` |
| 角色层晋升链 | 0 个模块（6 个缺失） | Normify `planned_count=12` |

**目标基线（CH-1 完成后）**：`script_lines.jsonl` 的 `speaker` 字段 **0 次出现 `C#`**；同一角色跨章回填到同一 `characterId`；群众/网友 hard-negative 集合 **0 次晋升**。

## Milestones

**M1 — Mention 一等层（前置）**
- 交付：`MentionDiscovery.discover(paragraph) → List<MentionCandidate>`；提及与身份在类型上分离（C3）。
- 验收：单元测试覆盖"同一段内多个提及 / 代词提及 / 群体提及"；不产生 identity。
- 依赖：无（可在 MOBILE-005 close 前并行做设计）。

**M2 — C18 落地：LocalCandidateResolver + SpeakerRef（最高优先，独立于身份链）**
- 交付：`CandidateMap { C0..Cn → CharacterId }`；`LocalCandidateResolver.toSpeakerRef(decision)`；`ScriptLine.speaker: SpeakerRef`。
- 验收：**真机整章产物中 `speaker` 字段 0 次 `C#`**；悬空 `C#` 仍 REJECT（C9 不回退）；旧 JSONL 有迁移/兼容策略。
- 为何优先：它是**纯结构改动**，不依赖身份链，且直接消除"下一段 permutation 后 C1 变成别人"的隐患。

**M3 — 推理前身份解析**
- 交付：`CharacterResolver.resolve(mention)`（已知→Identity；未知→Proposal）+ `AliasResolver`（本名/昵称/称谓/称号归一）。
- 验收：`understanding → character` 边界处完成解析，`CandidateMap` 由身份而非字符串生成；`understanding` 只依赖 `character.read-port`（policy `forbid-store-direct-use` 0 违规）。

**M4 — 证据向量与晋升策略**
- 交付：`CharacterEvidenceVector`（mentionCount / dialogueCount / distinctChapterCount / recentChapterCount / namedMentionCount / aliasCount / recurrence / firstSeen / lastSeen / userPinned / userLocked）+ `PromotionPolicy.decide/explain`。
- 验收：`importanceScore` 仅是派生值；每次晋升可用一句话解释（"出现 7 章、42 句对白、有明确姓名"）；群众/网友/路人 hard-negative 0 晋升。

**M5 — 生命周期与用户锁定**
- 交付：`CharacterLifecycle.transition/history/applyUserLock`。
- 验收：PROVISIONAL→PERSISTENT 转换携带 PromotionDecision；`userLocked` 不被任何自动流程覆盖（C11）；切书隔离。

**M6 — 真机整章回归**
- 交付：同章整章运行，speaker 全部为 SpeakerRef，身份跨段稳定。
- 验收：沿用 MOBILE-005 Close Gate 的 Anchor/Hang/Crash 三条 + 新增"0 个 `C#` 落盘"。

## Progress

```
M1 ⏳ 设计就绪（Mention 契约已在 Normify 计划态声明）
M2 ✅ 2026-09-18 **C18 落地（提前执行）**：SpeakerRef/CharacterId(稳定 opaque) + CandidateMap +
   LocalCandidateResolver + ScriptLine.speaker 改 SpeakerRef + codec v2(speakerRef)/v1 只读 legacy +
   runner fail-closed 计数；快速 Gate（固定 24 段=34 片段）lines=34 acc=34 rej=0 **localIdLeaks=0**
   anchor=100%；data-room 全量 25 suites/122 tests 0 failures；semantic-core/parser-core/app-android 编译通过。
   ★ 按 2026-09-18 评审决定，M2 由"CH-1 的一步"**改列为 MOBILE-005 的最后一块结构安全门**（见 Decisions D7）。
   待补：真机产物复验（设备当前不可达）。
M3 ⏳ 未开始
M4 ⏳ 未开始
M5 ⏳ 未开始
M6 ⏳ 未开始
前置：MOBILE-005 仍 open —— 仅剩真机 3 次完整运行 + 主动 epoch cancel（设备不可达，阻塞中）
```

## Decisions

| # | 决策 | 理由 |
|---|---|---|
| D1 | `C#` 是 request-scoped 地址，**不得**进入持久 ScriptLine/NarrationIR/CharacterStore | 每段候选置换不同，跨段复用 C# 会指向别人；新增契约 C18 |
| D2 | 身份解析分两段：**推理前** `Mention→Resolver→Identity/Proposal`；**推理后**只做 `C#→CharacterId` 查表 | 避免把"这是同一个人吗"的复杂度外包给 0.8B 模型；避免图里出现"Director 发现角色"的错觉 |
| D3 | `Character` 拥有音色**业务状态**（binding/profile/variant/locked），`Rendering` 只负责合成 | 换 TTS 引擎不推倒角色库 |
| D4 | importance 持久化**原始证据向量**，score 只是派生值 | 用户问"为什么这角色有专属音色"必须能回答 |
| D5 | CH-1→CH-2→CH-4 为主线；CH-3 为独立偿债线且不得阻塞主线 | 避免架构整理拖慢产品能力 |
| D6 | `RenderUnitBuilder → freeform-legacy` 记为 warning 级债务（非 error） | 违规真实存在但修它属 CH-1/CH-3 生产改动，本轮只暴露不掩盖 |
| D7 | **C18 是 MOBILE-005 的最后一块 Close blocker，必须先于 3 次长跑落地**（2026-09-18 评审改序） | MOBILE-005 的最终产物就是持久 `ScriptLine[]`；若其中仍是 request-scoped 地址，生产契约未真正成立。先落 C18 再跑长章，可少跑一轮且关闭时证据链完整 |
| D8 | `CharacterId` = **稳定 opaque id**（现有 `entityUid`），不用 SQLite rowid/自增 | DB 迁移、merge、导入导出、按书迁移、PROVISIONAL→PERSISTENT 都要保持稳定 |
| D9 | 旧 v1 JSONL **不猜、不原地迁移**：只读 legacy，`speakerResolvable=false` | 离开当时 CandidateMap 后 `C0` 已无法安全解释；猜它会制造假身份 |
| D10 | `NarrationIR` 的旧记录改名 `NarrationSpeakerRef`，`SpeakerRef` 一名一义 | 原同名类型造成歧义（`SpeakerRef` 必须只指 C18 持久引用） |

## Experiments

| id | 变量 | 基线 | 预期结果 | 结论 |
|---|---|---|---|---|
| E1 | speaker 持久形态 | String（含 `C#`27） | SpeakerRef（`C#` 0） | 待做 |
| E2 | 身份解析时机（推理前 vs 推理后） | 无解析 | 回填率↑、悬空 C#→REJECT 不变 | 待做 |
| E3 | PromotionPolicy 阈值 | 无 | 群众/网友 hard-negative 0 晋升 | 待做 |
| E4 | 证据向量 vs opaque score | 无 | 晋升可解释率 100% | 待做 |

## Validation

```text
V1 单元：Mention/Resolver/AliasResolver/Proposal/Importance/Lifecycle 各自测试
V2 契约：C18 —— 断言持久 ScriptLine 的 speaker 类型不含局部 ID；JSONL 全量扫描 0 次 C#
V3 契约：C9/C10 回归 —— 悬空 C# 仍 REJECT；Normalizer 仍只修表示
V4 policy：normify_validate = 0 error（含 forbid-store-direct-use / layers-main）
V5 真机：同章整章运行，沿用 MOBILE-005 Close Gate 的 Anchor 100% / hang 0 / crash 0
V6 回归：M1（段落 parity）、M2（语义 parity）仍 PASS —— 证明只动身份层未动已冻结层
```

## Rollback

- `ScriptLine.speaker` 形态变更：保留 `ScriptLineCodec` 的双读能力（旧 JSONL 可解析），必要时回退读取层而不回退数据库。
- 身份链新增模块均为**纯新增**，可用 policy/feature 开关停用而不影响 MOBILE-005 路径。
- 不触碰 `persistence` 迁移（CH-1 **不含** DB schema 变更；证据向量先落 JSON 侧，DB 迁移留到有真实数据后单独开 change）。

## Artifacts

```
规划（本轮）：C:\Users\Administrator\normify-readervoice-mobile\{normify.html,tree.json,policy.yml,changes\**}
本文件：docs/agent/plans/PLAN-20260918-059-ch-1-character-identity-promotion.md
预期实现产物：data-room/.../character/{resolver,proposal,importance,lifecycle,alias-resolver}.kt
              data-room/.../semantic/{MentionDiscovery,LocalCandidateResolver}.kt
              runs/mobile_005_director_real/ch1_*/{script_lines.jsonl, report.md}
```

## Open Issues

1. ~~`SpeakerRef` 落盘形态~~ → **已冻结并落地**：`CharacterId(value: String)` 稳定 opaque（现有 `entityUid` 形态如 `uid-1`），SQLite rowid 仅 persistence 内部使用（D8）。
2. ~~旧 JSONL 处置~~ → **已冻结**：不猜、不原地迁移；v1 只读且 `speakerResolvable=false`（D9）。
3. PROVISIONAL 角色的剧本行如何路由 TTS（CH-2 之前）→ **已冻结**：允许确定性临时 voice pool（同书内尽量稳定），**不得**产生 APPROVED VoiceProfile / 永久 VoiceBinding；PERSISTENT 才触发 VoiceBindingRequest。
4. CharacterCompiler 与 Mention 的边界 → **已冻结**：`MentionExtractor` 属 Understanding；`CharacterCompiler`/`CharacterResolver` 只**消费** Mention，不得自己再做 mention discovery。
5. EvidenceVector 持久化时机 → **已冻结**：在 canonical Mention / identity observation 成立后追加事实；`importanceScore` 随时派生，**不把模型 Director 输出当唯一依据**。
6. 新增：`NarrationSpeakerRef`（原 `SpeakerRef`）与 C18 `SpeakerRef` 的统一时机 —— 建议 CH-1 M3 完成后（届时 `status` 由角色生命周期接管）。
7. 新增：真机 3 次长跑 + epoch cancel 仍待设备可用（当前无线调试端点不可达）。

## Handoff

- 下一动作：**先走完 MOBILE-005 Close Gate**（同章 3 次完整运行 + 指标全记录 + epoch 取消真机 PASS），再启动 CH-1 的 M2（C18 落地）。
- CH-1 开工前必须复读：本文件 Decisions D1–D6、`docs/DECISIONS.md` ADR-055、Normify `rvm.contracts`（C18）。
- 禁止事项：不得用 Normalizer/Validator 的"修复"路径实现身份回填（会破坏 C9/C10）。
