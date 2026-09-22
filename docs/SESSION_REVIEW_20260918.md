# 会话回顾与当前真实状态（2026-09-18）

> 给下一个接手的人/Agent：这份文档取代聊天记录，按它就能续上。
> 读取顺序仍是：`AGENTS.md` → `docs/PROJECT_STATE.md` → `docs/DECISIONS.md` → 本文件 → 相关 ExecPlan。

## 一、主线走到哪了

```
MOBILE-005（真实章节 ReaderDirector）        ✅ CLOSED（真机 3 次完整 + 1 次取消全绿）
CH-0（章节批次化运行时）                     🔶 in_progress（P0-A 完成 / P0-B 大体完成 / P1-P3 未做）
CH-1 角色身份   CH-2 音色   CH-4 调度        ⏸ 未开始（TTS 由用户明确暂停）
```

### MOBILE-005 关闭时的证据（可复核）
```
3 次完整整章：各 138 行 / coverage 98.6% / acc=137 down=1 rej=2 / shortcut=111 / calls=29
              elapsed 308/318/329s，统计逐项一致
1 次主动取消：50 行保留、watchdog stuck=0（协作式取消，非超时）
C18：4 轮 464 行持久 ScriptLine 中局部 ID 泄漏 = 0
```

## 二、CH-0 各里程碑的真实完成度

| 里程碑 | 内容 | 状态 | 证据强度 |
|---|---|---|---|
| P0-A M0.1 | `ConfirmedChapterView`（只留 CONFIRMED、ordinal 连续、按 anchor 排序） | ✅ | **强**（3 书 × 12 章 Gate） |
| P0-A M0.2 | 重算 `contentEndLine`（非末章 = 下一确认章 start-1） | ✅ | 强 |
| P0-A M0.3 | 服务/Reader 只消费 confirmed view | ✅ | 中（编译 + 真机 1 次） |
| P0-A M0.4 | 稳定唯一 `chapterId`（内容派生 = anchor 行 byteStart） | ✅ | 强（真机 = 2175/13434 非 0） |
| P0-A M0.5 | 段落层继承 `chapterId`（此前恒 null ⇒ 按章导演 0 段） | ✅ | **弱**（真机 1 次 70 行） |
| P0-B | `ScriptRepository`（JSONL + state.json + 稳定键） | ✅ 契约与 Gate | 中（JVM 3 用例 + 真机 state.json） |
| P0-B 真机续跑 | 杀进程 → 重进 → `resume from S_n` | ⏳ **未验证** | 无 |
| P1 | 批次化 12 段/batch + 前4~8/后1~2 上下文 | ❌ 未做（commit 粒度已按 12 段就位） | — |
| P2 | 规则治理（《…》14,174 条候选污染 + chapterIndex 语义） | ❌ 未做 | — |
| P3 | Director 质量（REJECT / candidate pollution 指标） | 🔶 刚开始测量 | 见下 |

**量化收益（P0-A）**：
```
从离婚开始的文娱：rawCandidates 16,060 → CONFIRMED 1,090（REJECTED 14,174 = 《书名号》污染）
穿越东京泡沫时代：rawCandidates  5,392 → CONFIRMED   855
4 条不变式违规：44,756 → 0
```

## 三、★ 质量真相（本轮最重要的发现）

建了测量工具后第一次出数，**立刻证实"效果很差"且有单一主因**：

```
真实章节 chapterId=13434，20 段：
  哈哈 × 6 句   ←「刘三刀…哈哈大笑起来」的 cue 碎片
  招呼 × 3 句   ←「刘三刀招呼一声」的 cue 碎片
  NARRATOR × 9 ✓   UNKNOWN × 2 ✓
  ⇒ 45% 的说话人根本不是人
```

**根因链**：`RuleSpeakerBaseline` 的抽取消歧没拦住动词/状语碎片 ⇒ 被当人名进候选 ⇒
模型在坏候选里做选择题 ⇒ **答得完全合规（ACCEPTED_ALL），只是人是错的**。

⇒ 这解释了长期落差：**我报的全是协议合规率（coverage/anchor/ACCEPTED_ALL），
从未测过说话人是否正确**。协议全绿 ≠ 剧本可用。

**工具已就位**：`tools/mobile005/speaker_gold.js`（extract / judge；
ADR-055：无 MANUAL_GOLD 则拒绝报准确率）。

## 四、我在本会话犯的错误（按类型，供监督）

```
A. 跨层"接口改了但数据没通"（4 次，全部由用户发现，不是我的测试发现）
   ① LogicalParagraph.chapterId 恒为 null（我假定它继承了章节 id）→ 按章导演 0 段
   ② 「查看剧本」漏传 bookId → 剧本页永远"还没有剧本"
   ③ 跨章运行写进 chapterId=0 → 混章剧本污染按章视图
   ④ M0.5 之前"按章导演"实际什么都没跑（同上①）
B. 工具/工程细节
   ⑤ PowerShell 单引号串里 `n 是字面量 ⇒ 把 `r`n 写进源码（3 次）
   ⑥ 多行字符串替换因行尾失配失败（2 次，后改 edit 工具）
   ⑦ 断言写错位（segmentId = 1000+i，我写 1019 应为 1020）
   ⑧ 留了一处无用函数（loadScriptLines 返回空列表）后删
   ⑨ Gate 首版 O(n²)（每次线性扫全行）导致超时
```

**共同教训（已写进本文件，需写进 AGENTS）**：
> 每加一条**跨层字段**，必须配一条**端到端断言**（例如"按章导演后该章 totalSegments>0"、
> "点【剧本】必须看到 N>0 行"）。**编译通过 + 单测绿 ≠ 数据通了。**

## 五、架构真相源（Normify）

```
位置：C:\Users\Administrator\normify-readervoice-mobile\
  94 模块 / 180 API / 83 依赖 / 15 policy 规则 / 变更 5 个（MOBILE-005=verified，CH-0=in_progress）
契约：C1–C18 冻结（C18 = 局部候选 ID 不得进入持久 ScriptLine）
政策：layers-main 依赖方向 + forbid-* 越层拦截 + 债务规则 debt-render-unit-legacy-coupling
```

**已冻结、不得擅动**：`DirectorDecisionV2` 四态、15 个失败码、`SpeakerRef`/C18、
`ScriptLineCodec` schemaVersion=2、ADR-055 评价口径。

## 六、下一步（按我建议的优先级）

```
① ★ 候选人名合法性闸门（直击 45%）——**优先于一切**
   在"接受 surface 为角色名"前依次否决：
     a. 叹词/拟声/语气词表（哈哈/呵呵/哼/嘿…）
     b. DeliveryCueLexicon 判为表演提示者（**现成能力，只是没在名字生成路径上调用**）
     c. 形态不像人名（单双字动词短语、含 着/了/道/说/问/声 等 cue 语素）
     d. 全书从未作为独立名词短语出现
   ⇒ 否决者**降级为 delivery cue**（不删信息、不进候选）
   验收：同章同批 20 段，非人说话人 9 → 0，且 NARRATOR/UNKNOWN 不误伤
   涉及文件：RuleSpeakerBaseline（抽取/消歧）、SpeakerCandidateCompiler（候选编译）
② 候选污染指标进 Gate：candidate_count / pollution_count / pollution_rate / invalid_surface_count
③ P0-B 真机续跑验证（导演某章 → force-stop → 重进 → resume from S_n）
④ 别名归一最小版（同章同名/称谓合并）→ 支撑 CH-1
⑤ 上下文窗口定标（前 4~8 + 后 1~2）—— 必须在 ① 之后才有意义
⑥ P1 批次化（12 段/batch；commit 粒度已就位）
```

## 七、欠债（明确记录，不隐藏）

```
· TTS 未接（用户明确暂停）：Audio8 旁白目录仍是空占位；ScriptLine 目前只被测试消费
· scheduler 模块（928 行）无人引用；RenderUnit 建立在旧 freeform 结果上（policy 记为债务）
· 8 个界面仍为纯 View（已迁 Compose：书架/运行台/剧本/原文/规则）
· 真机验证散落且多为"用户发现"；缺端到端断言
· PROVISIONAL 章节策略未定（当前默认排除）
· StructureOverrideStore 构造的 Chapter 仍 chapterId=0（覆盖路径未走）
```
