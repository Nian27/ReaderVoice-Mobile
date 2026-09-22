package com.readervoice.data.m2

import com.readervoice.data.Db
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.snapshot.M2ParityRunner
import com.readervoice.data.snapshot.RecordingCharacterStore
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M2 Gate 桌面侧：
 *   ① 用**记录代理**包住真实 JDBC store，跑共享执行体 M2ParityRunner ⇒ desktop_semantic.jsonl + trace.json
 *   ② 同时落一份 canonical.txt（稳定文本，避免 JSON 键序造成伪不一致）
 * 设备端 `SemanticParityActivity` 用同一 runner + 回放 store ⇒ device_semantic.jsonl ⇒ G2 判分。
 */
class M2SemanticParityTest {

    private val dir = File("../runs/mobile_005_director_real/m2_semantic_parity")
    private val bookId = "book-4455b46ef2d2"

    private fun newDb(): Db {
        val db = Db(createTempDirectory("rv-m2-").resolve("t.db").toString())
        db.createSchema()
        SchemaMigrationV2(db).migrate()
        return db
    }

    @Test
    fun `dump semantic parity trace and desktop outputs`() {
        val input = File(dir, "input.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(input.exists(), "M2 input not present: $input")
        dir.mkdirs()

        val bytes = input.readBytes()
        val encoding = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
        val rules = javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
            .use(LegacyChapterRuleAdapter::fromJson)
        ChapterStructureCompiler(rules).compile(lines, bookId)
        val para = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
        val paragraphs = para.paragraphs.map { it.paragraph }

        // 与 M0 同法：用规则层发现角色 surface 建 store（设备端不需要 DB，它回放 trace）
        val db = newDb()
        val store = CharacterStore(db)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-m2','2026-09-16')")
        val bookPk = 1L
        val segmenter = SemanticSegmenter()
        val baseline = RuleSpeakerBaseline()
        val discovered = LinkedHashMap<String, Int>()
        run {
            val recent = ArrayDeque<String>(); var prevCue: String? = null
            for (p in paragraphs) {
                val segs = segmenter.segment(p); if (segs.isEmpty()) continue
                val res = baseline.assign(p, segs, recent.toList(), prevCue)
                for (s in segs) {
                    val r = res[s.segmentIndex] ?: continue
                    val sp = r.speaker
                    if (sp != null && r.status != "UNKNOWN") {
                        discovered.merge(sp, 1, Int::plus); recent.remove(sp); recent.addFirst(sp)
                    }
                }
                while (recent.size > 16) recent.removeLast()
                prevCue = baseline.extractTrailingCue(p.normalizedText)
            }
        }
        discovered.entries.sortedByDescending { it.value }.take(80)
            .forEach { (name, _) -> store.upsertEntity(bookPk, "e-$name", "PERSON", name, "CONFIRMED") }

        val recorder = RecordingCharacterStore(store)
        val jsonl = File(dir, "desktop_semantic.jsonl")
        val canon = File(dir, "desktop_canonical.txt")
        val linesOut = mutableListOf<String>()
        val n = M2ParityRunner.run(paragraphs, bookPk, recorder) { line ->
            linesOut += M2ParityRunner.canonicalLine(JSONObject(line))
            jsonl.appendText(line + "\n")
        }
        canon.writeText(linesOut.joinToString("\n") + "\n")
        File(dir, "trace.json").writeText(recorder.traceJson())

        println("[M2] paragraphs=${paragraphs.size} targets=$n storeCalls=${recorder.calls} " +
            "traceBytes=${File(dir, "trace.json").length()} canonicalLines=${linesOut.size}")
        assertTrue(n > 0, "no semantic target produced")
    }
}
