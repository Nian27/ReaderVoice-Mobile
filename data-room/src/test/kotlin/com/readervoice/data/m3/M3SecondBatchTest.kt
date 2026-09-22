package com.readervoice.data.m3

import com.readervoice.data.Db
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.DirectorOutputValidator
import com.readervoice.data.semantic.DirectorSelectionPrompt

import com.readervoice.data.semantic.ProtocolNormalizer
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.ScriptLineBuilder
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3：**第二批次 = G_APP5 原 5 段**（用户指定）。
 *
 * 与 M0 批次的差别：G_APP5 是书的前几段，**旁白与对白混合** ⇒ 能覆盖
 * `NARRATOR + NARRATION` 路径与 `AUDIO8_NARRATOR` 路由（M0 全是对白目标）。
 *
 * 本测试两步合一：
 *   ① 生成 fixture + prompt（用共享渲染器）
 *   ② 若设备端输出已就位，则过链评估四项指标
 */
class M3SecondBatchTest {

    private val out = File("../runs/mobile_005_director_real/gapp5_batch")
    private val book = File("../runs/mobile_004_director_engine/gapp5/book_source.txt")
    private val bookId = "book-4455b46ef2d2"
    private val maxTargets = 8
    private val windowSize = 8
    private val skipFrontMatter = 60

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    @Test
    fun `build gapp5 batch fixture and prompts`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(book.exists(), "G_APP5 book_source 不在")
        out.mkdirs()
        val fixture = File(out, "fixture.jsonl")
        if (fixture.exists()) return          // 已生成则跳过（幂等）

        val bytes = book.readBytes()
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, bookId).lines
        ChapterStructureCompiler(legacyRules()).compile(lines, bookId)
        val paras = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
            .paragraphs.map { it.paragraph }

        val segmenter = SemanticSegmenter()
        val db = Db(createTempDirectory("rv-g5-").resolve("t.db").toString())
        db.createSchema(); SchemaMigrationV2(db).migrate()
        val store = CharacterStore(db)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-g5','2026-09-17')")
        val bookPk = 1L

        // 两轮实体发现（同 M0：先无实体集，再带实体集消歧）
        fun discover(entities: Set<String>, scope: List<com.readervoice.parser.paragraph.LogicalParagraph>): LinkedHashMap<String, Int> {
            val baseline = RuleSpeakerBaseline(entities)
            val found = LinkedHashMap<String, Int>()
            val recent = ArrayDeque<String>(); var prevCue: String? = null
            for (p in scope) {
                val segs = segmenter.segment(p); if (segs.isEmpty()) continue
                val res = baseline.assign(p, segs, recent.toList(), prevCue)
                for (s in segs) {
                    val r = res[s.segmentIndex] ?: continue
                    val sp = r.speaker ?: continue
                    if (r.status == "UNKNOWN") continue
                    val variants = RuleSpeakerBaseline.candidateNameVariants(sp)
                    if (variants.size == 1) found.merge(sp, 1, Int::plus)
                    else variants.drop(1).forEach { v -> found.merge(v, 1, Int::plus) }
                    recent.remove(sp); recent.addFirst(sp)
                }
                while (recent.size > windowSize * 2) recent.removeLast()
                prevCue = baseline.extractTrailingCue(p.normalizedText)
            }
            return found
        }
        // 实体发现用更大的窗口（模拟角色层从全书构建实体）：开头几段只有旁白提及，没有显式 cue
        val discoveryWindow = paras   // 全书扫描：等同角色层从全书构建实体（本书开头无显式 cue）
        val r1 = discover(emptySet(), discoveryWindow)
        val entities = r1.keys
        entities.forEach { store.upsertEntity(bookPk, "e-$it", "PERSON", it, "CONFIRMED") }
        val r2 = discover(entities, discoveryWindow)
        println("[G5] entities: round1=${r1.size} round2=${r2.size}")

        // 逐段构建上下文，取开头的前 maxTargets 个**可朗读片段**（对白 + 旁白）
        val baseline = RuleSpeakerBaseline(entities)
        val ctxBuilder = ContextBuilder(store)
        val window = ArrayDeque<ContextBuilder.WindowParagraph>()
        val recent = ArrayDeque<String>()
        var prevCue: String? = null
        var idx = 0
        val records = mutableListOf<JSONObject>()
        for (p in paras.drop(skipFrontMatter).take(120)) {
            val segs = segmenter.segment(p); if (segs.isEmpty()) continue
            val res = baseline.assign(p, segs, recent.toList(), prevCue)
            for (seg in segs) {
                if (idx >= maxTargets) break
                val ctx = ctxBuilder.build(bookPk, p, segs, res, window.toList(), p.paragraphId, windowSize, emptyList())
                val cands = JSONArray()
                ctx.candidateSpeakers.forEach { c ->
                    cands.put(JSONObject().put("id", c.localId).put("name", c.name)
                        .put("recent_turn_distance", c.recentTurnDistance).put("sources", JSONArray(c.sources)))
                }
                val evs = JSONArray()
                ctx.evidenceItems.forEach { e ->
                    evs.put(JSONObject().put("id", e.id).put("kind", e.kind).put("text", e.text)
                        .put("emotion_hint", e.emotionHint ?: JSONObject.NULL))
                }
                records += JSONObject()
                    .put("target_segment_id", DirectorSelectionPrompt.segmentToken(ctx.target))
                    .put("paragraph_id", p.paragraphId).put("segment_index", seg.segmentIndex)
                    .put("segment_type", seg.type.name.lowercase())
                    .put("text", seg.text).put("source_start", seg.sourceStart).put("source_end", seg.sourceEnd)
                    .put("paragraph_len", p.normalizedText.length)
                    .put("candidates", cands).put("evidence", evs)
                    .put("rule_speaker", res[seg.segmentIndex]?.speaker ?: JSONObject.NULL)
                    .put("rule_status", res[seg.segmentIndex]?.status ?: "UNKNOWN")
                    .put("recent_context", JSONArray(ctx.recentSegments.map { it.text }.takeLast(6)))
                File(out, "prompt_%02d.txt".format(idx)).writeText(DirectorSelectionPrompt.render(ctx))
                idx++
            }
            for (s in segs) {
                val r = res[s.segmentIndex] ?: continue
                val sp = r.speaker
                if (sp != null && r.status != "UNKNOWN") { recent.remove(sp); recent.addFirst(sp) }
            }
            while (recent.size > windowSize * 2) recent.removeLast()
            prevCue = baseline.extractTrailingCue(p.normalizedText)
            window.addLast(ContextBuilder.WindowParagraph(p, res))
            while (window.size > windowSize) window.removeFirst()
            if (idx >= maxTargets) break
        }
        File(out, "fixture.jsonl").writeText(records.joinToString("\n") { it.toString() })
        println("[G5] targets=$idx prompts written to ${out.absolutePath}")
        assertTrue(idx > 0)
    }

    @Test
    fun `evaluate gapp5 batch if device outputs present`() {
        val fixture = File(out, "fixture.jsonl")
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture.exists(), "先跑 build 步骤")
        val recs = fixture.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        var runs = 0; var hard = 0; var cosmetic = 0; var verify = 0; var unknown = 0
        var accepted = 0; var downgrade = 0; var lineOk = 0; var shortcut = 0
        val fixes = LinkedHashMap<String, Int>(); val fails = LinkedHashMap<String, Int>()
        val lines = mutableListOf<String>()

        recs.forEachIndexed { i, rec ->
            val env = M3BatchEnv.of(rec)
            // ★ shortcut：旁白段不问模型（speaker 必然是 NARRATOR）；对白无候选 ⇒ UNKNOWN
            val skip = DirectorSelectionPrompt.skipDecision(env.ctx)
            val raws: List<String> = if (skip != null) {
                shortcut++
                listOf(
                    """{"segment_id":"${rec.getString("target_segment_id")}","speaker":"$skip",""" +
                        """"type":"${if (skip == "NARRATOR") "NARRATION" else "DIALOGUE"}",""" +
                        """"emotion":"NEUTRAL","emotion_intensity":0.5,""" +
                        """"delivery":{"pace":"NORMAL","volume":"NORMAL","tone":"NEUTRAL"},"voice_event":"NORMAL","evidence":[]}"""
                )
            } else {
                val f = File(out, "out_%02d.txt".format(i))
                if (!f.exists()) return@forEachIndexed
                f.readLines().map { it.trim() }.filter { it.startsWith("{") }.ifEmpty { return@forEachIndexed }
            }
            for (raw in raws) {
                runs++
                val n = ProtocolNormalizer.normalize(raw)
                val r = DirectorOutputValidator.validate(
                    n, rec.getString("target_segment_id"), env.candidateIds, env.evidenceIds,
                )
                if (n.fixes.isNotEmpty()) { cosmetic++; n.fixes.forEach { fixes.merge(it.name, 1, Int::plus) } }
                (r.hardFailures + r.softFailures).forEach { fails.merge(it.code.name, 1, Int::plus) }
                when (r.outcome) {
                    ValidationOutcome.REJECT -> hard++
                    ValidationOutcome.VERIFY -> verify++
                    ValidationOutcome.DOWNGRADE -> downgrade++
                    ValidationOutcome.ACCEPTED_ALL -> accepted++
                }
                if (r.decision?.speaker == "UNKNOWN") unknown++
                val line = ScriptLineBuilder.build(env.paragraphText, env.segment, env.ctx, r)
                if (line != null) {
                    check(line.text == rec.getString("text"))
                    lineOk++
                    if (lines.size < 8) {
                        lines += "  [${rec.getString("target_segment_id")}] ${line.type}/${line.speakerKind} " +
                            "route=${line.route} outcome=${line.outcome} text=${line.text.take(24)}"
                    }
                }
            }
        }
        val pct = { x: Int -> "%.1f%%".format(100.0 * x / runs.coerceAtLeast(1)) }
        val report = buildString {
            appendLine("=== M3 第二批次评估（G_APP5 原 5 段，含旁白）===")
            appendLine("runs=$runs（${recs.size} 目标 × 3 次）")
            appendLine("① 协议硬失败率        : ${pct(hard)} ($hard/$runs)")
            appendLine("② cosmetic fix rate   : ${pct(cosmetic)} ($cosmetic/$runs)  $fixes")
            appendLine("③ UNKNOWN / VERIFY    : ${pct(unknown)} / ${pct(verify)}")
            appendLine("④ ScriptLine 安全生成率: ${pct(lineOk)} ($lineOk/$runs)")
            appendLine("outcome 分布: ACCEPTED_ALL=$accepted DOWNGRADE=$downgrade VERIFY=$verify REJECT=$hard")
            appendLine("shortcut（旁白不问模型 / 空候选不猜名）: $shortcut")
            appendLine("失败码: $fails")
            appendLine("样例 ScriptLine：")
            lines.forEach { appendLine(it) }
        }
        File(out, "m3_batch_metrics.txt").writeText(report)
        println(report)
    }
}





