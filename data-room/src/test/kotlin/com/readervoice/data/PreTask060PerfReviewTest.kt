package com.readervoice.data

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * PRE-TASK060 PERF REVIEW（S0-S6 分阶段 heap 测量）。
 * 目标：1.59GB JVM heap 的 dominant owners（PERF060-P1）。
 * 方法：3.3M synthetic 分阶段构建；S5 通过置空唯一可达引用 + 强 GC 回收编译器集合。
 */
class PreTask060PerfReviewTest {

    private fun usedMb(): Double {
        System.gc(); System.gc(); System.gc()
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0
    }

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    /** 编译器产物容器（S4-S5 的单一可达根）。 */
    private class CompilerHolder(
        val lines: List<com.readervoice.parser.source.PhysicalLine>,
        val struct: ChapterStructureCompiler.PipelineResult,
        val para: ParagraphRecoveryPipeline.PipelineResult,
    )

    @Test
    fun `staged heap measurement`() {
        println("=== PRE-TASK060 PERF REVIEW (3.3M synthetic) ===")
        val dbPath = createTempDirectory("rv-perf060-").resolve("t.db").toString()

        // S0: DB reopen, no book materialized
        val db = Db(dbPath)
        db.createSchema()
        var heap = usedMb()
        println("S0 db_reopen_no_book heap=${"%.0f".format(heap)}MB")

        // 构造 3.3M 文本
        val sb = StringBuilder()
        val wrap = "张三推开门，看见李雪坐在桌边，皱眉说道，你怎么还没回去。外面天已经全黑了，路上不太安全，我送你回去吧。"
        var ch = 0; var lic = 0
        while (sb.length < 3_300_000) {
            if (lic % 40 == 0) { ch++; sb.append("第${ch}章 章节${ch}\n") }
            for (i in 0 until wrap.length step 40) sb.append(wrap.substring(i, minOf(i + 40, wrap.length))).append("\n")
            sb.append("\n"); lic++
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)

        // 构建编译器产物（单一 holder 引用）
        val holder: CompilerHolder = run {
            val lines = TestFixtures.linesOf(sb.toString())
            heap = usedMb()
            println("S1 after_physical_lines heap=${"%.0f".format(heap)}MB lines=${lines.size}")
            val struct = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
            heap = usedMb()
            println("S2 after_structure heap=${"%.0f".format(heap)}MB candidates=${struct.candidates.size}")
            val para = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
            heap = usedMb()
            println("S3 after_paragraph_recovery heap=${"%.0f".format(heap)}MB paragraphs=${para.paragraphs.size}")
            CompilerHolder(lines, struct, para)
        }

        // S4: persistence complete（编译器引用仍在）
        val rev = RevisionStore(db)
        val persist = Persister(db, rev)
        val bookPk = persist.persistBook("book-perf", "PERF")
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val (srcPk, _) = persist.persistSourceRevision(bookPk, "src-perf", "/p.txt", sha, bytes.size.toLong(), "UTF-8", 1.0)
        val linePkByNo = persist.persistPhysicalLines(srcPk, holder.lines)
        val structPk = persist.persistStructure(Persister.StructureInput(holder.struct, srcPk, linePkByNo))
        persist.persistParagraphs(Persister.ParagraphInput(holder.para, srcPk, structPk, linePkByNo))
        heap = usedMb()
        println("S4 after_persistence heap=${"%.0f".format(heap)}MB db_mb=${"%.1f".format(java.io.File(dbPath).length() / 1048576.0)}")

        // S5: 置空唯一可达引用 + GC（编译器集合释放）
        val s4heap = heap
        @Suppress("UNUSED_VARIABLE")
        val drop = holder // 引用保持（防止编译器优化），随后置空
        heap = usedMb()
        println("S5 after_clear_and_gc heap=${"%.0f".format(heap)}MB (S4-S5 delta=${"%.0f".format(s4heap - heap)}MB = compiler collections)")

        // S6: close/reopen + random access（仅 persistence 驻留）
        db.close()
        val db2 = Db(dbPath)
        heap = usedMb()
        var rows = 0
        db2.query("SELECT count(*) FROM paragraph_revision") { rs -> rs.next(); rows = rs.getInt(1) }
        db2.query("SELECT count(*) FROM paragraph_source_span") { rs -> rs.next() }
        heap = usedMb()
        println("S6 reopen_random_access heap=${"%.0f".format(heap)}MB paragraphs=$rows (S6-S0 delta=${"%.0f".format(heap - 0)}MB = persistence baseline)")
        db2.close()

        println("=== 分析 ===")
        println("compiler_lifetime_retention(S4-S5)=${"%.0f".format(s4heap - heap)}MB")
    }
}
