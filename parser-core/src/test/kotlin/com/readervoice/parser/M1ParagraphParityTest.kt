package com.readervoice.parser

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * MOBILE-005 / M1 Gate 桌面侧：对**同一输入文件**跑段落化并落盘 desktop.jsonl。
 * 记录格式与设备端 `ParagraphParityActivity` 逐字段一致：
 *   {"i","pid","rid","idx","ch","block","policy","conf","len","sha"}
 * 参数（bookId / scan 参数 / pipeline 参数）也必须一致，否则 G1 无意义。
 */
class M1ParagraphParityTest {

    private val dir = File("../runs/mobile_005_director_real/m1_paragraph_parity")
    private val bookId = "book-4455b46ef2d2"

    @Test
    fun `dump desktop paragraphs for M1 parity`() {
        val input = File(dir, "input.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(input.exists(), "M1 input not present: $input")

        val bytes = input.readBytes()
        val encoding = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
        val rules = javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
            .use(LegacyChapterRuleAdapter::fromJson)
        val chapters = ChapterStructureCompiler(rules).compile(lines, bookId).revision.chapters

        val t0 = System.nanoTime()
        val para = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
        val ms = (System.nanoTime() - t0) / 1e6

        dir.mkdirs()
        val out = File(dir, "desktop.jsonl")
        val md = MessageDigest.getInstance("SHA-256")
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            para.paragraphs.forEachIndexed { i, bp ->
                val p = bp.paragraph
                val sha = md.digest(p.normalizedText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                md.reset()
                w.write(
                    JSONObject()
                        .put("i", i).put("pid", p.paragraphId).put("rid", p.revisionId)
                        .put("idx", p.paragraphIndex).put("ch", p.chapterId ?: JSONObject.NULL)
                        .put("block", p.blockType.name).put("policy", p.readPolicy.name)
                        .put("conf", p.boundaryConfidence).put("len", p.normalizedText.length)
                        .put("sha", sha)
                        .toString()
                )
                w.newLine()
            }
        }
        println("[M1] charset=${encoding.charset} lines=${lines.size} chapters=${chapters.size} " +
            "paragraphs=${para.paragraphs.size} pipeline=${"%.0f".format(ms)}ms -> ${out.absolutePath}")
        assertTrue(para.paragraphs.isNotEmpty())
    }
}
