package com.readervoice.data.semantic

import com.readervoice.data.character.CharacterStore
import com.readervoice.parser.paragraph.LogicalParagraph
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * DatasetExporter（TASK-070 §5）：train/dev/test.jsonl，**按 book 划分**（§64/G11）。
 *
 * 任务：SPEAKER / IDENTITY / VOICE_STATE。
 * provenance（G10）：EXPLICIT_RULE_GOLD / HUMAN_GOLD / LEGACY_V907 / LEGACY_V907_HARNESS / TEACHER / UNKNOWN。
 * - 显式 cue → EXPLICIT_RULE_GOLD（Easy Gold，legacy_label=false）
 * - TURN_TRACKING/无判断 → UNKNOWN（Hard 样本保留，target=规则猜测，不进 gold）
 * - legacy 记录存在 → target 取 legacy 输出，provenance=LEGACY_V907[_HARNESS]，legacy_label=true（**绝不等于 Gold**）
 * - HUMAN_GOLD/TEACHER：留给人工标注/教师蒸馏的占位来源（v1 不产生）
 * sample_id 确定性哈希；输出 JSON 结构与手册 §65 对齐。
 */
class DatasetExporter(private val store: CharacterStore) {

    data class BookSamples(val bookHash: String, val samples: List<DirectorSample>)

    data class ExportStats(val train: Int, val dev: Int, val test: Int, val byProvenance: Map<String, Int>, val byTask: Map<String, Int>)

    /** 单书采样：段落流 → 段 → baseline → IR → context → 样本。 */
    fun samplesForBook(
        bookPk: Long,
        bookHash: String,
        paragraphs: List<LogicalParagraph>,
        legacyRecords: Map<String, Map<String, Any?>> = emptyMap(), // textRef → v90.7 输出
        windowSize: Int = 8,
    ): BookSamples {
        val segmenter = SemanticSegmenter()
        val baseline = RuleSpeakerBaseline()
        val irBuilder = NarrationIRBuilder(store)
        val ctxBuilder = ContextBuilder(store)

        val samples = mutableListOf<DirectorSample>()
        val recentWindow = ArrayDeque<ContextBuilder.WindowParagraph>()
        val recentSpeakers = ArrayDeque<String>() // 近→远
        var prevCue: String? = null

        for (p in paragraphs) {
            val segments = segmenter.segment(p)
            if (segments.isEmpty()) continue
            val results = baseline.assign(p, segments, recentSpeakers.toList(), prevCue)
            // 更新说话人队列：显式 cue 优先入队
            for (seg in segments) {
                val r = results[seg.segmentIndex]
                if (r?.speaker != null && r.status == "CONFIRMED") {
                    recentSpeakers.removeAll { it == r.speaker }
                    recentSpeakers.addFirst(r.speaker)
                }
            }
            while (recentSpeakers.size > windowSize * 2) recentSpeakers.removeLast()
            prevCue = baseline.extractTrailingCue(p.normalizedText)

            val position = p.paragraphId
            val irs = irBuilder.build(p, segments, results, bookPk, position)
            val irBySeg = irs.associateBy { it.segmentId }

            // 每个 speech 段一个样本组（SPEAKER + IDENTITY + VOICE_STATE）
            for (seg in segments) {
                if (seg.type != SegmentType.SPEECH && seg.type != SegmentType.INNER_MONOLOGUE) continue
                val ir = irBySeg[seg.segmentId] ?: continue
                val textRef = "paragraph/${p.paragraphId}/segment/${seg.segmentIndex}"
                val ctx = ctxBuilder.build(
                    bookPk, p, segments, results, recentWindow.toList(), position, windowSize,
                )
                val legacy = legacyRecords[textRef]

                val prov = when {
                    legacy != null -> if (legacy["harness"] == true) "LEGACY_V907_HARNESS" else "LEGACY_V907"
                    ir.provenance.any { it in setOf("EXPLICIT_SPEECH_CUE", "CROSS_PARAGRAPH_CUE", "POSTPOSED_SPEECH_CUE") } -> "EXPLICIT_RULE_GOLD"
                    else -> "UNKNOWN"
                }
                val legacyLabel = legacy != null
                val surfaceName = results[seg.segmentIndex]?.speaker

                // 公共字段（全部任务统一，G12）。注意：org.json 的 JSONObject(JSONObject) 拷贝构造不复制内容，必须显式 put。
                fun baseInput() = JSONObject()
                    .put("text", seg.text)
                    .put("segment_type", seg.type.name.lowercase())
                    .put("text_ref", textRef)

                val input = baseInput().apply {
                    put("recent_context", JSONArray(ctx.recentSegments.map { it.text }))
                    put("scene_roles", JSONArray(ctx.candidateSpeakers.map { it.identityId }))
                    put("rule_candidates", JSONArray(
                        ctx.candidateSpeakers.map {
                            JSONObject().put("role", it.identityId).put("name", it.name).put("distance", it.recentTurnDistance)
                        }
                    ))
                    put("identity_constraints", JSONArray(ctx.identityConstraints))
                    put("embodiment", JSONArray(
                        ctx.embodiment.map { JSONObject().put("surface", it.surface).put("acting", it.actingIdentity).put("state", it.stateType) }
                    ))
                    put("overrides", JSONArray(ctx.userLocks))
                }
                val posKey = textRef

                // SPEAKER
                samples += DirectorSample(
                    sampleId = sid(bookHash, posKey, "SPEAKER"),
                    task = "SPEAKER", bookHash = bookHash,
                    input = jsonToMap(input),
                    target = jsonToMap(
                        if (legacyLabel) JSONObject().put("legacy_output", legacy)
                        else JSONObject()
                            .put("speaker", ir.speaker?.identityId ?: JSONObject.NULL)
                            .put("surface", surfaceName ?: JSONObject.NULL)
                            .put("status", ir.speaker?.status ?: "UNKNOWN")
                            .put("confidence", ir.speaker?.confidence ?: 0.0),
                    ),
                    provenance = prov, legacyLabel = legacyLabel,
                )
                // IDENTITY（TASK-080 §8 成对协议）：候选对 entity_a/entity_b + 证据 → SAME/DIFFERENT/UNKNOWN
                // gold：同 cluster（==）→ SAME；hard negative（!= + hard_block）→ DIFFERENT；其余 UNKNOWN（不伪装 gold）
                for ((ci, constraint) in ctx.identityConstraints.distinct().withIndex()) {
                    val pair = parseConstraint(constraint) ?: continue
                    val a = ctx.candidateSpeakers.firstOrNull { it.localId == pair.first } ?: continue
                    // 同 cluster 的两表面（别名）id 相同：b 必须是另一名候选（不同 surface）
                    val b = ctx.candidateSpeakers.firstOrNull { it.localId == pair.second && it.name != a.name } ?: continue
                    // cluster 级聚合：别名成员间的硬负例/证据一并统计（执法队-张明 的硬负例对 执法队-老张 同样生效）
                    val membersOf = { c: CandidateSpeaker ->
                        val cid = store.entityByCanonicalName(bookPk, c.name)
                            ?.let { store.resolveIdentity(bookPk, it.entityPk).clusterId }
                        val names = if (cid != null) listOf(c.name) + store.aliasesOf(bookPk, cid) else listOf(c.name)
                        names.mapNotNull { store.entityByCanonicalName(bookPk, it) }.map { it.entityPk }
                    }
                    val aPks = membersOf(a); val bPks = membersOf(b)
                    var hardBlock = false
                    val evidence = mutableMapOf<String, Int>()
                    for (ap in aPks) for (bp in bPks) {
                        if (store.hasHardNegative(bookPk, ap, bp)) hardBlock = true
                        store.evidenceSummary(bookPk, ap, bp).forEach { (k, v) -> evidence.merge(k, v, Int::plus) }
                    }
                    val sameCluster = pair.third == "=="
                    val relation = when {
                        sameCluster -> "SAME"
                        hardBlock -> "DIFFERENT"
                        else -> "UNKNOWN"
                    }
                    val prov = when {
                        sameCluster || hardBlock -> "EXPLICIT_RULE_GOLD"
                        else -> "UNKNOWN"
                    }
                    val aliasesOf = { c: CandidateSpeaker ->
                        store.resolveIdentity(bookPk, store.entityByCanonicalName(bookPk, c.name)?.entityPk ?: -1)
                            .clusterId?.let { store.aliasesOf(bookPk, it) } ?: emptyList()
                    }
                    val userLocksFor = { c: CandidateSpeaker ->
                        val e = store.entityByCanonicalName(bookPk, c.name)
                        if (e == null) emptyList<String>()
                        else store.queryEffectiveVoiceState(bookPk, e.entityPk, position)
                            .baseAttributes.values.filter { it.userOverride }.map { "${it.attribute}=${it.value}" }
                    }
                    samples += DirectorSample(
                        sampleId = sid(bookHash, posKey, "IDENTITY", ci),
                        task = "IDENTITY", bookHash = bookHash,
                        input = jsonToMap(
                            baseInput()
                                .put("book_hash", bookHash)
                                .put("entity_a", a.name)
                                .put("entity_a_id", a.identityId)
                                .put("entity_b", b.name)
                                .put("entity_b_id", b.identityId)
                                .put("aliases_a", JSONArray(aliasesOf(a).filter { it != a.name }))
                                .put("aliases_b", JSONArray(aliasesOf(b).filter { it != b.name }))
                                .put("positive_evidence", evidence["SAME_PERSON:POSITIVE"] ?: evidence["ALIAS:POSITIVE"] ?: 0)
                                .put("negative_evidence", evidence["DIFFERENT_PERSON:NEGATIVE"] ?: 0)
                                .put("relationship_evidence", JSONObject(
                                    evidence.filterKeys { it !in setOf("SAME_PERSON:POSITIVE", "ALIAS:POSITIVE", "DIFFERENT_PERSON:NEGATIVE", "CO_PRESENCE:NEUTRAL") }
                                ))
                                .put("co_presence", evidence["CO_PRESENCE:NEUTRAL"] ?: 0)
                                .put("hard_block", hardBlock)
                                .put("user_constraints", JSONArray(userLocksFor(a) + userLocksFor(b))),
                        ),
                        target = jsonToMap(
                            JSONObject()
                                .put("relation", relation)
                                .put("entity_a_id", a.identityId)
                                .put("entity_b_id", b.identityId),
                        ),
                        provenance = prov, legacyLabel = false,
                    )
                }
                // VOICE_STATE（TASK-080 §10）：输入只含 position 之前的状态（causal），预测动作
                val surf = results[seg.segmentIndex]?.speaker
                val vpk = irBuilder.voiceEntityPk(bookPk, surf, position)
                val vsPrev = vpk?.let { store.queryEffectiveVoiceState(bookPk, it, position - 1) }
                val vsNow = vpk?.let { store.queryEffectiveVoiceState(bookPk, it, position) }
                samples += DirectorSample(
                    sampleId = sid(bookHash, posKey, "VOICE_STATE"),
                    task = "VOICE_STATE", bookHash = bookHash,
                    input = jsonToMap(
                        baseInput()
                            .put("identity_id", ir.speaker?.identityId ?: JSONObject.NULL)
                            .put("current_state", vsPrev?.let { irBuilder.refOf(it) } ?: JSONObject.NULL),
                    ),
                    target = jsonToMap(
                        JSONObject()
                            .put("action", vsNow?.temporaryAction ?: "NONE")
                            .put("voice_state_ref", vsNow?.let { irBuilder.refOf(it) } ?: JSONObject.NULL),
                    ),
                    provenance = if (vsNow != null && irBuilder.refOf(vsNow) != null) "EXPLICIT_RULE_GOLD" else "UNKNOWN",
                    legacyLabel = false,
                )
            }
            recentWindow.addLast(ContextBuilder.WindowParagraph(p, results))
            while (recentWindow.size > windowSize) recentWindow.removeFirst()
        }
        return BookSamples(bookHash, samples)
    }

    /**
     * 按 book 划分（§64/G11）：全书样本整体进入同一 split。
     * @param splitOverride 指定 bookHash → split（真实书强制入 train 等）；未指定者按比例填剩余槽位。
     */
    fun export(
        dir: File,
        books: List<BookSamples>,
        trainRatio: Double = 0.7,
        devRatio: Double = 0.15,
        splitOverride: Map<String, String> = emptyMap(),
    ): ExportStats {
        dir.mkdirs()
        val ordered = books.sortedBy { it.bookHash }
        val total = ordered.size
        // 小语料下限：train/dev 各至少 1（总书数 ≥2 时），test 取剩余
        val trainN = (total * trainRatio).toInt().coerceAtLeast(1)
        val devN = if (total - trainN >= 2) (total * devRatio).toInt().coerceAtLeast(1) else 0
        val split = mutableMapOf<String, String>()
        // 1) override 优先占位
        for (b in ordered) splitOverride[b.bookHash]?.let { split[b.bookHash] = it }
        // 2) 剩余按比例填槽（计数已占槽位）
        var trainUsed = split.values.count { it == "train" }
        var devUsed = split.values.count { it == "dev" }
        for (b in ordered) {
            if (split.containsKey(b.bookHash)) continue
            split[b.bookHash] = when {
                trainUsed < trainN -> { trainUsed++; "train" }
                devUsed < devN -> { devUsed++; "dev" }
                else -> "test"
            }
        }
        val byProvenance = mutableMapOf<String, Int>()
        val byTask = mutableMapOf<String, Int>()
        val files = mapOf(
            "train" to File(dir, "train.jsonl"),
            "dev" to File(dir, "dev.jsonl"),
            "test" to File(dir, "test.jsonl"),
        )
        files.values.forEach { it.delete() }
        for (b in ordered) {
            val out = files.getValue(split.getValue(b.bookHash))
            out.appendText(b.samples.joinToString("\n") { sampleJson(it).toString() } + "\n")
            for (s in b.samples) {
                byProvenance.merge(s.provenance, 1, Int::plus)
                byTask.merge(s.task, 1, Int::plus)
            }
        }
        return ExportStats(
            train = if (files.getValue("train").exists()) files.getValue("train").readLines().size else 0,
            dev = if (files.getValue("dev").exists()) files.getValue("dev").readLines().size else 0,
            test = if (files.getValue("test").exists()) files.getValue("test").readLines().size else 0,
            byProvenance = byProvenance, byTask = byTask,
        )
    }

    private fun sampleJson(s: DirectorSample): JSONObject = JSONObject()
        .put("sample_id", s.sampleId)
        .put("task", s.task)
        .put("book_hash", s.bookHash)
        .put("input", s.input)
        .put("target", s.target)
        .put("provenance", s.provenance)
        .put("legacy_label", s.legacyLabel)

    /** "a == b" / "a != b" → Triple(a, b, op)。 */
    private fun parseConstraint(c: String): Triple<String, String, String>? {
        for (op in listOf(" == ", " != ")) {
            val idx = c.indexOf(op)
            if (idx > 0) return Triple(c.substring(0, idx), c.substring(idx + op.length), op.trim())
        }
        return null
    }

    private fun sid(bookHash: String, ref: String, task: String, extra: Any = ""): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$bookHash|$ref|$task|$extra".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 递归展开 JSONObject/JSONArray → Kotlin Map/List（JSONObject.NULL → null）。 */
    private fun jsonToMap(o: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        for (k in o.keys()) out[k] = unwrap(o.opt(k))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> jsonToMap(v)
        is JSONArray -> (0 until v.length()).map { unwrap(v.opt(it)) }
        else -> v
    }
}



