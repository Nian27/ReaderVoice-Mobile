package com.readervoice.data.snapshot

import com.readervoice.data.character.CharacterReadStore
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.DirectorContext
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegment
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.parser.paragraph.LogicalParagraph
import org.json.JSONArray
import org.json.JSONObject

/**
 * MOBILE-005 / M2：G2 对拍的**共享执行体**。
 *
 * 桌面（JDBC store + 记录代理）与设备（回放 store）必须调用【同一个函数】，
 * 否则"两端一致"可能只是"两份代码碰巧一致"。所有范围/窗口/选择策略写死在这里。
 *
 * 输入只依赖：LogicalParagraph 列表 + CharacterReadStore。输出：每个目标的语义结论记录（JSONL 行）。
 */
object M2ParityRunner {

    const val RANGE_START = 60
    const val RANGE_END = 1200
    const val MAX_TARGETS = 120
    const val WINDOW = 8

    /**
     * @param paragraphs 已段落化的段落（两端必须来自同一输入文件与同一 bookId）
     * @param bookPk     角色库主键（两端一致；设备端回放 store 也会用到）
     * @param store      只读角色访问（桌面=记录代理，设备=回放 store）
     * @param sink       每条记录一行（JSONL），由调用方决定落盘位置
     * @return 实际产出的目标数
     */
    fun run(paragraphs: List<LogicalParagraph>, bookPk: Long, store: CharacterReadStore, sink: (String) -> Unit): Int {
        val segmenter = SemanticSegmenter()
        // ★ M3：baseline 必须拿到实体集，否则 delivery-cue 判定与前后缀消歧都不生效（等于死代码）。
        //   该调用会被记录/回放机制覆盖，设备端同样能拿到。
        val baseline = RuleSpeakerBaseline(store.canonicalNamesOf(bookPk))
        val ctxBuilder = ContextBuilder(store)
        val slice = paragraphs.subList(RANGE_START, minOf(RANGE_END, paragraphs.size))

        // ── pass1：目标选择必须【与 store 无关】（设备端只能回放已记录的调用）──
        // ★ M3 修正：选择条件不再要求"规则层给出了 speaker"。
        //   Director 的职责恰恰是裁决规则层解不了的段（cue 是 delivery/无 cue/turn-tracking 不可靠），
        //   若只挑"规则能解"的段，就会把最难的部分排除在评估之外，也会让 UNKNOWN 永远不出现。
        //   排序仍以 isEasy 优先（信息量更大），但不作为入选门槛。
        val chosen = LinkedHashSet<Long>()
        run {
            val recent = ArrayDeque<String>(); var prevCue: String? = null
            val cand = mutableListOf<Triple<Long, Boolean, Long>>()
            for (p in slice) {
                val segs = segmenter.segment(p); if (segs.isEmpty()) continue
                val res = baseline.assign(p, segs, recent.toList(), prevCue)
                for (s in segs) {
                    if (s.type != SegmentType.SPEECH && s.type != SegmentType.INNER_MONOLOGUE) continue
                    val r = res[s.segmentIndex]
                    cand += Triple(s.segmentId, r?.isEasy == true, p.paragraphId)
                }
                advance(recent, res, segs)
                prevCue = baseline.extractTrailingCue(p.normalizedText)
            }
            cand.sortedWith(compareByDescending<Triple<Long, Boolean, Long>> { it.second }.thenBy { it.third })
                .take(MAX_TARGETS).forEach { chosen += it.first }
        }

        // ── pass2：走完整管线（含 store 查询），只为选中的目标出记录 ──
        var idx = 0
        val window = ArrayDeque<ContextBuilder.WindowParagraph>()
        val recent = ArrayDeque<String>()
        var prevCue: String? = null
        for (p in slice) {
            val segs = segmenter.segment(p); if (segs.isEmpty()) continue
            val res = baseline.assign(p, segs, recent.toList(), prevCue)
            for (seg in segs) {
                if (seg.segmentId !in chosen) continue
                val ctx = ctxBuilder.build(bookPk, p, segs, res, window.toList(), p.paragraphId, WINDOW, emptyList())
                sink(record(idx, p.paragraphId, seg, ctx, res[seg.segmentIndex]?.speaker,
                    res[seg.segmentIndex]?.status ?: "UNKNOWN").toString())
                idx++
            }
            advance(recent, res, segs)
            prevCue = baseline.extractTrailingCue(p.normalizedText)
            window.addLast(ContextBuilder.WindowParagraph(p, res))
            while (window.size > WINDOW) window.removeFirst()
        }
        return idx
    }

    private fun advance(recent: ArrayDeque<String>, res: Map<Int, RuleSpeakerBaseline.SpeakerResult>,
                        segs: List<SemanticSegment>) {
        for (s in segs) {
            val r = res[s.segmentIndex] ?: continue
            val sp = r.speaker
            if (sp != null && r.status != "UNKNOWN") {
                recent.remove(sp); recent.addFirst(sp)
            }
        }
        while (recent.size > WINDOW * 2) recent.removeLast()
    }

    /** 语义结论记录：两端逐字段一致（G2 的判据面）。 */
    private fun record(i: Int, paragraphId: Long, seg: SemanticSegment, ctx: DirectorContext,
                       gold: String?, goldStatus: String): JSONObject {
        val cands = JSONArray()
        ctx.candidateSpeakers.forEach { c ->
            cands.put(
                JSONObject().put("id", c.localId).put("identity_id", c.identityId).put("name", c.name)
                    .put("dist", c.recentTurnDistance).put("aliases", JSONArray(c.aliases))
                    .put("sources", JSONArray(c.sources))
            )
        }
        val evs = JSONArray()
        ctx.evidenceItems.forEach { e ->
            evs.put(
                JSONObject().put("id", e.id).put("kind", e.kind).put("text", e.text)
                    .put("emotion_hint", e.emotionHint ?: JSONObject.NULL)
            )
        }
        // M3：规则层结果只报"型别 + 一致率"，不再冒充 gold（见 GoldPolicy）
        val goldKind = goldStatus.let { st ->
            when {
                gold == null -> "NONE"
                st == "PROVISIONAL" -> "RULE_BASELINE"
                else -> "WEAK_GOLD"
            }
        }
        return JSONObject()
            .put("i", i)
            .put("pid", paragraphId)
            .put("si", seg.segmentIndex)
            .put("type", seg.type.name)
            .put("start", seg.sourceStart)
            .put("end", seg.sourceEnd)
            .put("text", seg.text)
            .put("target", ctx.target.text)
            .put("target_segment_id", ctx.target.segmentId)
            .put("recent", JSONArray(ctx.recentSegments.map { it.text }))
            .put("candidates", cands)
            .put("evidence", evs)
            .put("identity_constraints", JSONArray(ctx.identityConstraints))
            .put("embodiment", JSONArray(ctx.embodiment.map { it.surface + "->" + it.actingIdentity + ":" + it.stateType }))
            .put("rule_speaker", gold ?: JSONObject.NULL)
            .put("rule_status", goldStatus)
            .put("gold_kind", goldKind)
    }

    /** 渲染 G2 判据用的稳定文本（避免 JSON 键序差异造成伪不一致）。 */
    fun canonicalLine(rec: JSONObject): String = buildString {
        append("i=").append(rec.getInt("i"))
        append(" pid=").append(rec.getLong("pid"))
        append(" si=").append(rec.getInt("si"))
        append(" type=").append(rec.getString("type"))
        append(" span=").append(rec.getInt("start")).append("..").append(rec.getInt("end"))
        append(" text=").append(rec.getString("text"))
        // M3：规则层输出只作为 rule_speaker/一致率口径，不叫 gold
        append(" rule=").append(if (rec.isNull("rule_speaker")) "-" else rec.getString("rule_speaker"))
        append(" rule_status=").append(rec.getString("rule_status"))
        append(" gold_kind=").append(rec.getString("gold_kind"))
        val c = rec.getJSONArray("candidates")
        append(" cands=[")
        for (k in 0 until c.length()) {
            val o = c.getJSONObject(k)
            append(o.getString("id")).append('(').append(o.getString("identity_id")).append(')')
                .append(':').append(o.getString("name")).append('@').append(o.getInt("dist"))
            val al = o.getJSONArray("aliases")
            if (al.length() > 0) append('[').append((0 until al.length()).joinToString(",") { al.getString(it) }).append(']')
            val src = o.optJSONArray("sources")
            if (src != null && src.length() > 0) append('{').append((0 until src.length()).joinToString(",") { src.getString(it) }).append('}')
            if (k < c.length() - 1) append('|')
        }
        append("]")
        val ev = rec.optJSONArray("evidence") ?: JSONArray()
        append(" evidence=[")
        for (k in 0 until ev.length()) {
            val o = ev.getJSONObject(k)
            append(o.getString("id")).append(':').append(o.getString("kind")).append(':').append(o.getString("text"))
            if (!o.isNull("emotion_hint")) append('/').append(o.getString("emotion_hint"))
            if (k < ev.length() - 1) append('|')
        }
        append("]")
        val ic = rec.getJSONArray("identity_constraints")
        append(" constraints=[").append((0 until ic.length()).joinToString(",") { ic.getString(it) }).append("]")
        val em = rec.getJSONArray("embodiment")
        append(" embodiment=[").append((0 until em.length()).joinToString(",") { em.getString(it) }).append("]")
        val rc = rec.getJSONArray("recent")
        append(" recent=[").append((0 until rc.length()).joinToString("␟") { rc.getString(it) }).append("]")
    }
}
