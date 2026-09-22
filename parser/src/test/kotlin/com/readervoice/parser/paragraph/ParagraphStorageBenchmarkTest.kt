package com.readervoice.parser.paragraph

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LogicalParagraph 存储 benchmark（TASK-030 §71/§72）：
 * A = 全存 normalized_text；B = 只存 source spans + boundary transforms，需要时重建。
 * 额外评估：全文搜索/UI render/context fetch 的影响（§72）。
 */
class ParagraphStorageBenchmarkTest {

    @Test
    fun `benchmark option A vs B`() {
        // 构造 ~100 万字符的 fixed-width 文本（大量段落）
        val sb = StringBuilder()
        val para = "张三推开门，看见李雪坐在桌边，皱眉说道，你怎么还没回去。外面天已经全黑了，路上不太安全，我送你回去吧。"
        while (sb.length < 1_000_000) {
            for (i in 0 until para.length step 20) sb.append(para.substring(i, minOf(i + 20, para.length))).append("\n")
            sb.append("\n")
        }
        val text = sb.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = com.readervoice.parser.source.EncodingDetector.detect(bytes)
        val lines = com.readervoice.parser.source.PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines

        val t0 = System.nanoTime()
        val r = ParagraphRecoveryPipeline().run(lines, "b", emptySet(), emptySet(), emptySet(), emptyList())
        val buildMs = (System.nanoTime() - t0) / 1e6
        val paras = r.paragraphs
        println("=== BENCH paragraphs: ${paras.size}, build_ms=${"%.0f".format(buildMs)} ===")

        val root = createTempDirectory("rv-para-bench-")
        val dbA = root.resolve("a.db").toFile()
        val tA0 = System.nanoTime()
        DriverManager.getConnection("jdbc:sqlite:${dbA.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE para(pid INTEGER PRIMARY KEY, text TEXT)")
                conn.autoCommit = false
                val ps = conn.prepareStatement("INSERT INTO para VALUES(?,?)")
                for (p in paras) { ps.setInt(1, p.paragraph.paragraphId.toInt()); ps.setString(2, p.paragraph.normalizedText); ps.addBatch() }
                ps.executeBatch(); conn.commit()
            }
        }
        val tA1 = System.nanoTime()
        val sizeA = dbA.length()

        val dbB = root.resolve("b.db").toFile()
        val tB0 = System.nanoTime()
        DriverManager.getConnection("jdbc:sqlite:${dbB.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE para(pid INTEGER PRIMARY KEY, line_no INTEGER, char_start INTEGER, char_end INTEGER, transforms TEXT)")
                conn.autoCommit = false
                val ps = conn.prepareStatement("INSERT INTO para VALUES(?,?,?,?,?)")
                for (p in paras) {
                    val first = p.paragraph.sourceSpans.first()
                    ps.setInt(1, p.paragraph.paragraphId.toInt())
                    ps.setInt(2, first.lineNo)
                    ps.setInt(3, first.charStart)
                    ps.setInt(4, first.charEnd)
                    ps.setString(5, p.normalizedSpans.joinToString(";") { "${it.transformType}:${it.synthetic}" })
                    ps.addBatch()
                }
                ps.executeBatch(); conn.commit()
            }
        }
        val tB1 = System.nanoTime()
        val sizeB = dbB.length()

        // 查询对比：随机 1000 段
        val qA0 = System.nanoTime()
        var lenA = 0
        DriverManager.getConnection("jdbc:sqlite:${dbA.absolutePath}").use { conn ->
            val ps = conn.prepareStatement("SELECT text FROM para WHERE pid=?")
            repeat(1000) { ps.setInt(1, 1 + Random(9).nextInt(paras.size)); ps.executeQuery().use { rs -> if (rs.next()) lenA += rs.getString(1).length } }
        }
        val qA1 = System.nanoTime()

        val qB0 = System.nanoTime()
        var lenB = 0
        DriverManager.getConnection("jdbc:sqlite:${dbB.absolutePath}").use { conn ->
            val ps = conn.prepareStatement("SELECT line_no FROM para WHERE pid=?")
            repeat(1000) { ps.setInt(1, 1 + Random(9).nextInt(paras.size)); ps.executeQuery().use { rs -> if (rs.next()) lenB += rs.getInt(1) } }
        }
        val qB1 = System.nanoTime()

        println("=== A（全存 normalized）===")
        println("db_mb=${"%.2f".format(sizeA / 1048576.0)} import_ms=${"%.0f".format((tA1 - tA0) / 1e6)} query_1000_ms=${"%.1f".format((qA1 - qA0) / 1e6)}")
        println("=== B（spans+transforms）===")
        println("db_mb=${"%.2f".format(sizeB / 1048576.0)} import_ms=${"%.0f".format((tB1 - tB0) / 1e6)} query_1000_ms=${"%.1f".format((qB1 - qB0) / 1e6)}")

        assertTrue(sizeB < sizeA, "B must be smaller")
        assertTrue(paras.size > 10_000, "expected many paragraphs")
    }
}
