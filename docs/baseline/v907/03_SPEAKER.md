# 03_SPEAKER.md — v90.7 说话人归属域（TASK-050 Domain 03）

**日期：2026-08-12** | 证据：L2（L441-577/L883-1116/L6229-6371/L6558-6844 控制流）

## Purpose

对白 → 说话人（本地管道 + LLM 裁决 + 多结果投票）。

## 调用链（真实）

```text
processCharacter(包装 L13970) ← Legado 引擎逐对白
→ 上下文构造（prevContextChars ~500 字句号对齐 L1468 + 下文 xiawen=1300/首次 800 L10315 + next100Chars + NEXT_CHAPTER_COUNT=3 后 3 章 L10236）
→ concurrentApiRequest(L441)：每 key 一个 Java Thread + CountDownLatch；target=min(WAIT_API_RESULT_COUNT=5, 并发 bingfa=1)=1 → 单结果模式（L462）
→ validateNameAnalyzeBatchResult(L6229)：序号集合严格比对，缺失/额外/字段不全 → 重试
→ voteNameAnalyzeResult(L883)：逐 seq 三阶段投票（主名计数→平票取最晚 L1007-1019 → 性别 L1042 → 年龄 L1080）
→ __relations/__voiceAgeEvidence/__temporaryVoiceStateReview 独立投票（L1125-1134）
→ 别名校验 checkAliasByApi(L6608)：同性别前 100 候选 + 图谱/最近章提示
→ graphV907SelectBestCombinedResult(L12975)：≥2 模块不完整整包重试（L6816）
→ 最终 speaker + 音色标签
```

## 上下文（§22 确认）

- 前文：当前段前 ~500 字（句号边界对齐）；后文：xiawen 1300 字；跨章：后 3 章（NEXT_CHAPTER_COUNT=3）
- recent role hit：NAME_ANALYSIS_RECENT_ROLE_RANGE=5 章

## API Vote（§23）

- 请求数=API key 数（DualKeyManager 多 key 轮换）；默认 bingfa=1 → 并发 1 → **投票是死代码路径**（WAIT≥3 且 bingfa≥3 才触发多结果）
- timeout 18000ms（姓名分析 120000）；超时按已收集结果继续
- tie：平票选**最晚返回**（确定性差，依赖响应顺序）
- **兜底 bug：无有效结果时 Math.random() 随机性别/年龄（L973-974）**

## Failure（§24）

| 场景 | 行为 |
|---|---|
| API 全失败 | sleep(250) 重试 ≤8 轮（L6336-6371）→ analyzeCharacterFallback(L8647)：{name:"未知",__safeDialogueFallback:true} → duihua 标签 + **封死缓存/建卡/图谱**（L7301-7304） |
| 返回格式错误 | 序号校验失败 → 重试 |
| 角色不存在/名字歧义 | 别名校验裁决；本地 block 可转 not_alias |
| 多人平票 | 取最晚返回（随机性） |

## ReaderVoice mapping

**KEEP** 调用链结构（管道+裁决分离）；**REDESIGN**：投票改确定性（多数+置信阈值，删 Math.random 兜底）；上下文窗口按 ReaderVoice 段模型重建。

## Open questions

- `routeForSegment`/`localSpeakerCandidates` 不存在（本地槽位判定已删）——路由由 Legado 引擎承担，ReaderVoice 需自建。
