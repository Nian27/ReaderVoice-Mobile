package com.readervoice.data.semantic

import com.readervoice.data.character.CharacterReadStore
import com.readervoice.parser.paragraph.LogicalParagraph

/**
 * ContextBuilder（TASK-070 §4）：ReaderDirector 的结构化输入。
 * 纯规则/DB 查询，无 LLM：
 * - target / recent_segments：最近窗口内的段落 + 段
 * - candidate_speakers：**仅窗口内活跃说话人**（G6：candidate restriction，不给全书角色表）
 *   recentTurnDistance = 距当前的距离（1=最近）
 * - identity_constraints：同/异 identity 关系（"A != B" / "A == B"，防模型坍缩）
 * - embodiment：whoIsActingThrough（附身提示）
 * - user_locks：用户固定声音等（调用方传入 + voice state userOverride 属性）
 */
class ContextBuilder(private val store: CharacterReadStore) {

    /** 窗口内一个段落 + 其 baseline 结果。 */
    data class WindowParagraph(
        val paragraph: LogicalParagraph,
        val speakerResults: Map<Int, RuleSpeakerBaseline.SpeakerResult>,
    )

    fun build(
        bookPk: Long,
        paragraph: LogicalParagraph,
        segments: List<SemanticSegment>,
        speakerResults: Map<Int, RuleSpeakerBaseline.SpeakerResult>,
        recentWindow: List<WindowParagraph>,
        position: Long,
        windowSize: Int = 8,
        userLocks: List<String> = emptyList(),
    ): DirectorContext {
        // target：首个 speech 段（无则末段）
        val target = segments.firstOrNull { it.type == SegmentType.SPEECH || it.type == SegmentType.INNER_MONOLOGUE }
            ?: segments.lastOrNull()
            ?: SemanticSegment(paragraph.paragraphId * 1000, paragraph.paragraphId, 0, SegmentType.UNKNOWN, "", 0, 0, 0)

        // recent_segments：窗口内（不含当前段本身）+ 当前段中 target 之前的
        val segmenter = SemanticSegmenter()
        val recentSegs = mutableListOf<ContextSegment>()
        for (wp in recentWindow.takeLast(windowSize)) {
            for (seg in segmenter.segment(wp.paragraph)) {
                recentSegs += ContextSegment(
                    seg.text, seg.type.name.lowercase(), wp.paragraph.paragraphId,
                    segmentId = seg.segmentId, segmentIndex = seg.segmentIndex,
                )
            }
        }
        for (seg in segments) {
            if (seg.segmentIndex < target.segmentIndex) {
                recentSegs += ContextSegment(
                    seg.text, seg.type.name.lowercase(), paragraph.paragraphId,
                    segmentId = seg.segmentId, segmentIndex = seg.segmentIndex,
                )
            }
        }
        val boundedRecent = recentSegs.takeLast(windowSize * 3)

        // candidate_speakers：走 TASK-095 的 Candidate Compiler（CUE ∪ RECENT_MENTION ∪ SCENE_ACTIVE）
        // ★ M3 修正：此前只用"窗口里已有 speaker"，一旦规则层给不出 speaker（delivery cue/无 cue），
        //   候选会直接为空 —— 而本段文本里明明写着"傅远"。RECENT_MENTION 源正是为此存在。
        val speakersInOrder = mutableListOf<Pair<String, Int>>() // (surface, turnDistance)
        for (wp in recentWindow.takeLast(windowSize).reversed()) {
            for (res in wp.speakerResults.values) {
                res.speaker?.let { sp ->
                    if (speakersInOrder.none { it.first == sp }) speakersInOrder += sp to (speakersInOrder.size + 1)
                }
            }
        }
        for (res in speakerResults.values) {
            res.speaker?.let { sp ->
                if (speakersInOrder.none { it.first == sp }) speakersInOrder += sp to (speakersInOrder.size + 1)
            }
        }

        val entitySet = store.canonicalNamesOf(bookPk)
        val cueSpeakers = speakerResults.values.filter { it.isEasy }.mapNotNull { it.speaker }
        // recentContext 必须含【本段】文本（"傅远坐下扫了一眼，饶有兴致道：" ⇒ 傅远 可被召回）
        val recentContext = boundedRecent.map { it.text } + paragraph.normalizedText
        val compiled = SpeakerCandidateCompiler(entitySet).compile(
            cueSpeakers = cueSpeakers,
            recentSpeakers = speakersInOrder.map { it.first },
            recentContext = recentContext,
        )

        // ★ M3：局部 ID 分配（C0/C1…）+ 每样本确定性 permutation
        //   目的：① 模型只看局部 ID（不泄漏持久身份）；② 消除"正确候选恒在 C0"的位置偏差；
        //        ③ 两端（桌面/设备）必须得到相同编号 ⇒ 种子只依赖 (paragraphId, segmentIndex)。
        var seed = (paragraph.paragraphId * 2654435761L + target.segmentIndex * 40503L) and 0x7fffffff
        val ordered = compiled.toMutableList()
        for (i in ordered.size - 1 downTo 1) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val j = (seed % (i + 1)).toInt()
            val t = ordered[i]; ordered[i] = ordered[j]; ordered[j] = t
        }

        val candidates = ordered.mapIndexed { idx, sc ->
            val surface = sc.surface
            val entity = store.entityByCanonicalName(bookPk, surface)
            val identityId = entity?.let {
                val res = store.resolveIdentity(bookPk, it.entityPk)
                res.clusterId ?: it.entityUid
            } ?: surface
            val aliases = if (entity != null) {
                store.resolveIdentity(bookPk, entity.entityPk).clusterId
                    ?.let { store.aliasesOf(bookPk, it) }?.filter { it != surface } ?: emptyList()
            } else emptyList()
            val dist = speakersInOrder.firstOrNull { it.first == surface }?.second
                ?: (speakersInOrder.size + 1)
            CandidateSpeaker(
                localId = "C$idx",
                identityId = identityId,
                name = surface,
                aliases = aliases,
                recentTurnDistance = dist,
                sources = sc.sources.map { it.name }.sorted(),
            )
        }

        // identity_constraints：候选间同/异关系（★ 用局部 ID，模型看不到内部身份）
        val constraints = mutableListOf<String>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val a = candidates[i]; val b = candidates[j]
                when {
                    a.identityId == b.identityId -> constraints += "${a.localId} == ${b.localId}"
                    NameEvidence.mergeBlockReason(a.name, b.name).isNotEmpty() ->
                        constraints += "${a.localId} != ${b.localId}"
                    else -> constraints += "${a.localId} != ${b.localId}"
                }
            }
        }

        // embodiment：候选实体被附身？
        val embodiment = candidates.mapNotNull { c ->
            val entity = store.entityByCanonicalName(bookPk, c.name) ?: return@mapNotNull null
            store.actingThroughWithState(bookPk, entity.entityPk, position)?.let { (actingPk, stateType) ->
                val actingUid = store.entityByPk(actingPk)?.entityUid ?: "unknown"
                EmbodimentHint(surface = c.name, actingIdentity = actingUid, stateType = stateType)
            }
        }

        // user_locks：显式传入 + 候选 voice state 中 userOverride 属性
        val autoLocks = candidates.flatMap { c ->
            val entity = store.entityByCanonicalName(bookPk, c.name) ?: return@flatMap emptyList<String>()
            val vs = store.queryEffectiveVoiceState(bookPk, entity.entityPk, position)
            vs.baseAttributes.values.filter { it.userOverride }.map { "${it.attribute}=${it.value}" }
        }
        val locks = (userLocks + autoLocks).distinct()

        // ── M3：结构化证据（E0/E1…，确定性顺序；模型只能引用编号）──
        // 按【每类证据设预算】的纪律（参考 v90.9 的 ALIAS_RECENT_* 限额做法），避免 prompt 膨胀与噪声淹没：
        //   ① DELIVERY_CUE（表演提示）≤2   ② 身份证据（别名/反向/群体禁并）≤3
        //   ③ RULE（规则层 provenance）≤1  ④ SPEAKER_CUE（文本中被点名者）≤1
        val evidence = mutableListOf<EvidenceItem>()
        val targetResult = speakerResults[target.segmentIndex]

        // ② 身份证据：有界文本模式，把"A != B"从断言变成【有出处的证据】
        val evText = (boundedRecent.map { it.text } + paragraph.normalizedText).joinToString("。")
        var identityCount = 0
        pairLoop@ for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                if (identityCount >= 3) break@pairLoop
                val a = candidates[i].name
                val b = candidates[j].name
                NameEvidence.mergeBlockReason(a, b).takeIf { it.isNotEmpty() }?.let {
                    evidence += EvidenceItem("E${evidence.size}", "MERGE_BLOCK", "$a × $b：$it")
                    identityCount++
                    return@let
                }
                NameEvidence.contradictionEvidence(a, b, evText)?.let {
                    evidence += EvidenceItem("E${evidence.size}", "NEGATIVE_EVIDENCE", "$a ≠ $b（$it）")
                    identityCount++
                }
                if (identityCount >= 3) break@pairLoop
                NameEvidence.aliasEvidence(a, b, evText)?.let {
                    evidence += EvidenceItem("E${evidence.size}", "ALIAS_EVIDENCE", "$a = $b（$it）")
                    identityCount++
                }
            }
        }

        // ① 表演提示：由 cue 污染转来的"导演素材"（emotion_hint 供表演参考）
        targetResult?.deliveryCues?.take(2)?.forEach { cue ->
            evidence += EvidenceItem("E${evidence.size}", "DELIVERY_CUE", cue.text, cue.emotionHint)
        }
        // ③ 规则层 provenance：让模型知道当前判定的来源（含 UNKNOWN）
        targetResult?.provenance?.take(1)?.forEach { p ->
            evidence += EvidenceItem("E${evidence.size}", "RULE", p)
        }
        // ④ 显式 cue 候选：CONFIRMED 候选即"文本里被点名的人"
        candidates.filter { c -> speakerResults.values.any { it.speaker == c.name && it.isEasy } }
            .take(1)
            .forEach { c -> evidence += EvidenceItem("E${evidence.size}", "SPEAKER_CUE", c.name) }
        val boundedEvidence = evidence.take(7)

        return DirectorContext(
            target = ContextSegment(
                target.text, target.type.name.lowercase(), paragraph.paragraphId,
                segmentId = target.segmentId, segmentIndex = target.segmentIndex,
            ),
            recentSegments = boundedRecent,
            candidateSpeakers = candidates,
            identityConstraints = constraints.distinct(),
            embodiment = embodiment,
            userLocks = locks,
            evidenceItems = boundedEvidence,
        )
    }
}

