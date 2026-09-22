package com.readervoice.data

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/** 真实网文泛化测试（G13 首个真实样本）：娱乐：从1990年开始（咖啡香草）。 */
class RealBookTest {
    @Test
    fun `real book full pipeline`() {
        val f = java.io.File("../娱乐：从1990年开始 作者：咖啡香草.txt")
        // 真实书 gitignored（版权）：文件缺失时跳过，不影响其他环境/CI（§126/§127）
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists(), "real book fixture not present (gitignored)")
        val bytes = f.readBytes()
        val det = EncodingDetector.detect(bytes)
        println("charset=${det.charset} conf=${"%.2f".format(det.confidence)}")
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        println("lines=${lines.size}")

        val t0 = System.nanoTime()
        val structResult = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
        val t1 = System.nanoTime()
        val confirmed = structResult.revision.chapters.count { it.state == com.readervoice.parser.chapters.ResolveState.CONFIRMED }
        val provisional = structResult.revision.chapters.count { it.state == com.readervoice.parser.chapters.ResolveState.PROVISIONAL }
        println("structure: confirmed=$confirmed provisional=$provisional volumes=${structResult.volumes.size} tocBlocks=${structResult.toc.blocks.size} in ${"%.0f".format((t1 - t0) / 1e6)}ms")
        println(com.readervoice.parser.chapters.StructurePreview.render(structResult.revision, structResult.profile, structResult.groups))

        val paraResult = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
        val t2 = System.nanoTime()
        println("paragraphs=${paraResult.paragraphs.size} profile=${paraResult.bookProfile.profileType} in ${"%.0f".format((t2 - t1) / 1e6)}ms")
        val sample = paraResult.paragraphs.take(3)
        for (s in sample) println("  P[${s.paragraph.blockType}] ${s.paragraph.normalizedText.take(50)}")

        // persist
        val path = createTempDirectory("rv-real-").resolve("t.db").toString()
        Db(path).use { db ->
            db.createSchema()
            val rev = RevisionStore(db); val persist = Persister(db, rev)
            val bookPk = persist.persistBook("book-real-1990", "娱乐：从1990年开始")
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val (srcPk, _) = persist.persistSourceRevision(bookPk, "src-real", f.name, sha, bytes.size.toLong(), det.charset.label, det.confidence)
            val linePkByNo = persist.persistPhysicalLines(srcPk, lines)
            val structPk = persist.persistStructure(Persister.StructureInput(structResult, srcPk, linePkByNo))
            persist.persistParagraphs(Persister.ParagraphInput(paraResult, srcPk, structPk, linePkByNo))
            val verdict = DatabaseIntegrityVerifier(db).verify()
            assertTrue(verdict.pass, "integrity: ${verdict.issues.take(3)}")
            println("persist OK, db_mb=${"%.1f".format(java.io.File(path).length() / 1048576.0)}")
        }
    }

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )
}
