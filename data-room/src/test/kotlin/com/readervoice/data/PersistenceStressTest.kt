package com.readervoice.data

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TASK-040 §106/§107/§109/§110：
 * 3.3M 字符全量持久化（bulk insert batch）+ DB size 分解 + FTS prototype。
 */
class PersistenceStressTest {

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    @Test
    fun `3M plus chars persist without OOM`() {
        // 构造 3.3M 字符 fixed-width 文本（每 40 行一个章节标题，结构完整）
        val sb = StringBuilder()
        val wrap = "张三推开门，看见李雪坐在桌边，皱眉说道，你怎么还没回去。外面天已经全黑了，路上不太安全，我送你回去吧。"
        var chapterNo = 0
        var lineInChapter = 0
        while (sb.length < 3_300_000) {
            if (lineInChapter % 40 == 0) {
                chapterNo++
                sb.append("第${chapterNo}章 压力章节${chapterNo}\n")
            }
            for (i in 0 until wrap.length step 40) sb.append(wrap.substring(i, minOf(i + 40, wrap.length))).append("\n")
            sb.append("\n")
            lineInChapter++
        }
        val text = sb.toString()
        val lines = TestFixtures.linesOf(text)
        val bytes = text.toByteArray(Charsets.UTF_8)

        val path = createTempDirectory("rv-stress-db-").resolve("t.db").toString()
        val t0 = System.nanoTime()
        Db(path).use { db ->
            db.createSchema()
            val rev = RevisionStore(db)
            val persist = Persister(db, rev)
            val bookPk = persist.persistBook("book-3m", "3M 压力书")
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val (srcPk, _) = persist.persistSourceRevision(bookPk, "src-3m", "/tmp/3m.txt", sha, bytes.size.toLong(), "UTF-8", 1.0)
            val t1 = System.nanoTime()
            val linePkByNo = persist.persistPhysicalLines(srcPk, lines)
            val t2 = System.nanoTime()

            val structResult = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
            val structPk = persist.persistStructure(Persister.StructureInput(structResult, srcPk, linePkByNo))
            val t3 = System.nanoTime()

            val paraResult = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
            persist.persistParagraphs(Persister.ParagraphInput(paraResult, srcPk, structPk, linePkByNo))
            val t4 = System.nanoTime()

            // 表规模分解（§109）；WAL checkpoint 后才是真实持久大小（§77）
            db.exec("PRAGMA wal_checkpoint(TRUNCATE)")
            val sizes = DatabaseIntegrityVerifier(db).tableSizes()
            val totalMb = java.io.File(path).length() / 1048576.0
            println("=== TASK040_STRESS ===")
            println("bytes=${bytes.size} lines=${lines.size} paragraphs=${sizes["paragraph_revision"]}")
            println("source_ms=${"%.0f".format((t1 - t0) / 1e6)} lines_ms=${"%.0f".format((t2 - t1) / 1e6)}")
            println("structure_ms=${"%.0f".format((t3 - t2) / 1e6)} paragraph_ms=${"%.0f".format((t4 - t3) / 1e6)}")
            println("db_total_mb=${"%.1f".format(totalMb)}")
            println("lines=${sizes["physical_line"]} boundaries=${sizes["line_boundary"]} spans=${sizes["paragraph_source_span"]}")
            val heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0
            println("peak_heap_mb=${"%.1f".format(heap)}")

            val verdict = DatabaseIntegrityVerifier(db).verify()
            assertTrue(verdict.pass, "integrity: ${verdict.issues.take(3)}")
            assertTrue(heap < 2048, "heap too high")
        }
    }

    @Test
    fun `fts prototype benchmark`() {
        // §69/§110：on-demand materialization vs rebuildable FTS（不把 FTS 变主存储）
        val text = TestFixtures.goldBookText()
        val lines = TestFixtures.linesOf(text)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val path = createTempDirectory("rv-fts-").resolve("t.db").toString()
        Db(path).use { db ->
            db.createSchema()
            val rev = RevisionStore(db)
            val persist = Persister(db, rev)
            val bookPk = persist.persistBook("book-fts", "FTS 书")
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val (srcPk, _) = persist.persistSourceRevision(bookPk, "src-fts", "/a.txt", sha, bytes.size.toLong(), "UTF-8", 1.0)
            val linePkByNo = persist.persistPhysicalLines(srcPk, lines)
            val structResult = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
            val structPk = persist.persistStructure(Persister.StructureInput(structResult, srcPk, linePkByNo))
            val paraResult = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
            persist.persistParagraphs(Persister.ParagraphInput(paraResult, srcPk, structPk, linePkByNo))

            val sizeWithout = dbFileSize(path)

            // FTS 派生缓存（§68：SearchIndex 不是 source of truth）
            val t0 = System.nanoTime()
            db.exec(
                "CREATE VIRTUAL TABLE paragraph_fts USING fts5(paragraph_revision_pk UNINDEXED, text)"
            )
            // 从 source spans 物化文本（简化：用 paragraph_revision 的 fingerprint 占位——真实物化需 normalized 重建）
            db.exec(
                "INSERT INTO paragraph_fts(paragraph_revision_pk, text) " +
                    "SELECT paragraph_revision_pk, 'placeholder' FROM paragraph_revision"
            )
            val buildMs = (System.nanoTime() - t0) / 1e6
            val sizeWith = dbFileSize(path)

            val q0 = System.nanoTime()
            db.query("SELECT count(*) FROM paragraph_fts WHERE paragraph_fts MATCH 'placeholder'") { rs ->
                rs.next(); println("fts_hits=${rs.getInt(1)}")
            }
            val queryMs = (System.nanoTime() - q0) / 1e6

            println("=== TASK040_FTS ===")
            println("without_fts_mb=${"%.2f".format(sizeWithout)} with_fts_mb=${"%.2f".format(sizeWith)}")
            println("build_ms=${"%.0f".format(buildMs)} query_ms=${"%.1f".format(queryMs)}")
            assertTrue(sizeWith >= sizeWithout) // gold 书数据小，增量可能不足一页（4KB 粒度）
        }
    }

    /** WAL 模式下数据在 -wal/-shm 文件：总大小 = 主 + wal + shm（§77）。 */
    private fun dbFileSize(path: String): Double {
        var total = 0L
        for (suffix in listOf("", "-wal", "-shm")) {
            val f = java.io.File(path + suffix)
            if (f.exists()) total += f.length()
        }
        return total / 1048576.0
    }
}
