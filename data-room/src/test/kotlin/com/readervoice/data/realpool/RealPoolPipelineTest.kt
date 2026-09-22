package com.readervoice.data.realpool

import com.readervoice.data.Db
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.semantic.SpeakerCandidateCompiler
import com.readervoice.data.semantic.CandidateSourceType
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test

/**
 * TASK-100 B 线：REAL_POOL_V1 全自动管线。
 * 17 本真实小说 → TXT 全链 → SemanticSegment → Rule Speaker（含跨段 cue/轮换/后置 cue）
 * → 信号标注（hardness signals）→ books_private/real_pool_v1/ 输出。
 * 规则层结果用于：0.8B student 对照 + 4B/9B Teacher 标注 + Human Audit 筛选。
 */
class RealPoolPipelineTest {

    private val BOOKS_DIR = File("../books_private")
    private val OUT_DIR = File("../books_private/real_pool_v1")

    @Test
    fun `real pool v1 full pipeline over all books`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(BOOKS_DIR.exists(), "books_private missing")
        val books = BOOKS_DIR.listFiles { f -> f.name.endsWith(".txt") }?.sortedBy { it.name } ?: emptyList()
        assumeTrue(books.isNotEmpty())
        OUT_DIR.mkdirs()
        val seg = SemanticSegmenter()
        val baseline = RuleSpeakerBaseline()
        val aggregate = JSONObject()

        for (b in books) {
            val bookId = "RB-" + sha256(b.readBytes()).take(8)
            val t0 = System.nanoTime()
            val bytes = b.readBytes()
            val det = EncodingDetector.detect(bytes)
            val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, bookId, false).lines
            val struct = ChapterStructureCompiler(legacyRules()).compile(lines, bookId)
            val paraResult = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())

            val recentSpeakers = ArrayDeque<String>()
            val recentTexts = ArrayDeque<String>() // 最近段落 normalizedText（真实上下文，2026-08-14 修复占位符缺陷）
            var prevCue: String? = null
            // TASK-095A：实体集（宽松 gate 版，books_private/real_pool_v1/entity_sets.json）
            val entitySets = JSONObject(File("../books_private/real_pool_v1/entity_sets.json").readText())
            val entitySet: Set<String> = try {
                val jo = entitySets.getJSONObject(bookId)
                (0 until jo.length()).map { jo.names().getString(it) }.toSet()
            } catch (e: Exception) { emptySet() }
            val candidateCompiler = SpeakerCandidateCompiler(entitySet)
            val out = StringBuilder()
            var nSpeech = 0; var nConfirmed = 0; var nUnknown = 0; var nPost = 0; var nCross = 0
            var nCand2 = 0; var nCand3 = 0; var nCand4 = 0; var nPronoun = 0; var nAlias = 0

            for (bp in paraResult.paragraphs) {
                val p = bp.paragraph
                val segments = seg.segment(p)
                if (segments.isEmpty()) continue
                val results = baseline.assign(p, segments, recentSpeakers.toList(), prevCue)
                // 本段 cue 确认的说话人（TASK-095A：进候选，不依赖 entitySet——缓解自举缺陷）
                val cueSpeakers = results.values.mapNotNull { it.speaker }.distinct()
                // 更新说话人队列 + prevCue
                for (s in segments) {
                    val r = results[s.segmentIndex]
                    if (r?.speaker != null && r.status == "CONFIRMED") {
                        recentSpeakers.removeAll { it == r.speaker }
                        recentSpeakers.addFirst(r.speaker)
                    }
                }
                while (recentSpeakers.size > 12) recentSpeakers.removeLast()
                prevCue = baseline.extractTrailingCue(p.normalizedText)

                // TASK-095A：候选 = CUE ∪ SCENE_ACTIVE ∪ RECENT_MENTION（带 provenance）
                val compiled = candidateCompiler.compile(cueSpeakers, recentSpeakers.toList(), recentTexts.toList())
                val window = compiled.map { it.surface }
                val windowSources = compiled.associate { it.surface to it.sources.joinToString(",") { s -> s.name } }
                for (s in segments) {
                    if (s.type != com.readervoice.data.semantic.SegmentType.SPEECH && s.type != com.readervoice.data.semantic.SegmentType.INNER_MONOLOGUE) continue
                    val r = results[s.segmentIndex] ?: continue
                    nSpeech++
                    when (r.provenance.firstOrNull()) {
                        "EXPLICIT_SPEECH_CUE" -> nConfirmed++
                        "POSTPOSED_SPEECH_CUE" -> { nConfirmed++; nPost++ }
                        "CROSS_PARAGRAPH_CUE" -> { nConfirmed++; nCross++ }
                        else -> nUnknown++
                    }
                    val text = s.text
                    if (text.startsWith("“我") || text.startsWith("“你") || text.startsWith("“他") || text.startsWith("“她")) nPronoun++
                    val k = window.size
                    if (k == 2) nCand2++ else if (k == 3) nCand3++ else if (k >= 4) nCand4++
                    val isExplicit = r.provenance.any { it in setOf("EXPLICIT_SPEECH_CUE", "CROSS_PARAGRAPH_CUE", "POSTPOSED_SPEECH_CUE") }
                    val signals = JSONObject()
                        .put("rule_unknown", !isExplicit)
                        .put("postposed", r.provenance.contains("POSTPOSED_SPEECH_CUE"))
                        .put("cross_cue", r.provenance.contains("CROSS_PARAGRAPH_CUE"))
                        .put("k2_alt", k == 2).put("k3plus", k >= 3)
                        .put("pronoun", text.startsWith("“我") || text.startsWith("“你") || text.startsWith("“他") || text.startsWith("“她"))
                        .put("turn_tracking", r.provenance.contains("TURN_TRACKING"))
                    val jo = JSONObject()
                        .put("book", bookId).put("para_id", p.paragraphId).put("seg_idx", s.segmentIndex)
                        .put("type", s.type.name.lowercase()).put("text", text.take(120))
                        .put("recent_context", JSONArray(recentTexts.toList())) // 真实前文（最多 3 段）
                        .put("rule_speaker", r.speaker ?: JSONObject.NULL)
                        .put("status", r.status).put("provenance", JSONArray(r.provenance))
                        .put("n_candidates", k).put("candidates", JSONArray(window))
                        .put("candidate_sources", JSONObject(windowSources))
                        .put("signals", signals)
                    out.append(jo.toString()).append('\n')
                }
                // 本段结束后入队（后置 cue 同段已处理完；recent_context 不含当前段）
                recentTexts.addLast(p.normalizedText.take(200))
                while (recentTexts.size > 3) recentTexts.removeFirst()
            }
            val stats = JSONObject()
                .put("book", bookId).put("file", b.name).put("bytes", bytes.size)
                .put("charset", det.charset.label).put("lines", lines.size)
                .put("chapters", struct.revision.chapters.size)
                .put("paragraphs", paraResult.paragraphs.size)
                .put("speech_segments", nSpeech).put("rule_confirmed", nConfirmed)
                .put("rule_unknown", nUnknown).put("postposed", nPost).put("cross_cue", nCross)
                .put("cand2", nCand2).put("cand3", nCand3).put("cand4plus", nCand4)
                .put("pronoun", nPronoun)
                .put("ms", (System.nanoTime() - t0) / 1_000_000)
            File(OUT_DIR, "$bookId.segments.jsonl").writeText(out.toString())
            File(OUT_DIR, "$bookId.stats.json").writeText(stats.toString(1))
            aggregate.put(bookId, stats)
            println(stats.toString(1))
        }
        File(OUT_DIR, "REAL_POOL_V1_AGGREGATE.json").writeText(aggregate.toString(1))
        // 汇总
        var sp = 0L; var conf = 0L; var unk = 0L; var post = 0L; var cross = 0L
        var k2 = 0L; var k3 = 0L; var k4 = 0L; var pron = 0L
        for (k in aggregate.keys()) {
            val s = aggregate.getJSONObject(k)
            sp += s.getLong("speech_segments"); conf += s.getLong("rule_confirmed"); unk += s.getLong("rule_unknown")
            post += s.getLong("postposed"); cross += s.getLong("cross_cue")
            k2 += s.getLong("cand2"); k3 += s.getLong("cand3"); k4 += s.getLong("cand4plus"); pron += s.getLong("pronoun")
        }
        println("=== REAL_POOL_V1 AGGREGATE: books=${aggregate.length()} speech=$sp confirmed=$conf unknown=$unk post=$post cross=$cross cand2=$k2 cand3=$k3 cand4+=$k4 pronoun=$pron")
    }

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
