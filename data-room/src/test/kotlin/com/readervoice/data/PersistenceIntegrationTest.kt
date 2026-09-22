package com.readervoice.data

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端持久化集成测试：import → structure → paragraphs → DB + integrity。
 * 使用 TASK-020 gold book（含卷/章/TOC）+ TASK-030 段落。
 */
class PersistenceIntegrationTest {

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    private fun buildPipeline(db: Db): Triple<Persister, Long, Map<Int, Long>> {
        val rev = RevisionStore(db)
        val persist = Persister(db, rev)

        // 1. book + source（gold 文本）
        val text = TestFixtures.goldBookText()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val lines = TestFixtures.linesOf(text)

        val bookPk = persist.persistBook("book-gold", "Gold 测试书")
        val (srcPk, _) = persist.persistSourceRevision(
            bookPk, "src-gold", "/tmp/gold.txt",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size.toLong(), "UTF-8", 1.0,
        )
        val linePkByNo = persist.persistPhysicalLines(srcPk, lines)

        // 2. structure
        val structResult = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
        val structPk = persist.persistStructure(Persister.StructureInput(structResult, srcPk, linePkByNo))

        // 3. paragraphs
        val paraResult = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
        val prPk = persist.persistParagraphs(
            Persister.ParagraphInput(paraResult, srcPk, structPk, linePkByNo)
        )
        return Triple(persist, prPk, linePkByNo)
    }

    private fun bookPk(db: Db): Long {
        var pk = 0L
        db.query("SELECT book_pk FROM book WHERE book_uid='book-gold'") { rs ->
            rs.next(); pk = rs.getLong(1)
        }
        return pk
    }

    @Test
    fun `full pipeline persists and verifies`() {
        val db = Db(createTempDirectory("rv-integ-").resolve("t.db").toString())
        db.createSchema()
        db.use {
            val rev = RevisionStore(db)
            buildPipeline(db)

            // heads 齐
            val srcHead = rev.head("book:${bookPk(db)}", "SOURCE")
            val stHead = rev.head("book:${bookPk(db)}", "STRUCTURE")
            val pHead = rev.head("book:${bookPk(db)}", "PARAGRAPH_RECOVERY")
            assertTrue(srcHead != null && stHead != null && pHead != null)

            // 表规模（§109）
            val sizes = DatabaseIntegrityVerifier(db).tableSizes()
            assertTrue(sizes["physical_line"]!! > 0)
            assertTrue(sizes["chapter"]!! >= 10, "expected chapters: ${sizes["chapter"]}")
            assertTrue(sizes["paragraph_revision"]!! > 0)
            println("DB tables non-empty=${sizes.filterValues { it > 0 }.size}, paragraphs=${sizes["paragraph_revision"]}")

            // integrity 100%（G14）
            val verdict = DatabaseIntegrityVerifier(db).verify()
            assertTrue(verdict.pass, "integrity issues: ${verdict.issues}")

            // snapshot（§102）
            val snap = rev.snapshot("book:${bookPk(db)}")
            assertEquals("src-gold", snap.sourceRevisionId)
            assertEquals(stHead.activeRevisionId, snap.structureRevisionId)
            assertEquals(pHead.activeRevisionId, snap.paragraphRecoveryRevisionId)
        }
    }

    @Test
    fun `reopen keeps data consistent`() {
        // G2：close → reopen → 数据一致
        val path = createTempDirectory("rv-reopen-").resolve("t.db").toString()
        Db(path).use { db ->
            db.createSchema()
            buildPipeline(db)
        }
        Db(path).use { db ->
            val sizes = DatabaseIntegrityVerifier(db).tableSizes()
            assertTrue(sizes["paragraph_revision"]!! > 0, "paragraphs survive reopen")
            val verdict = DatabaseIntegrityVerifier(db).verify()
            assertTrue(verdict.pass, "integrity after reopen: ${verdict.issues}")
        }
    }
}
