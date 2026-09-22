package com.readervoice.parser.source

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Raw-text 存储策略 benchmark（TASK-010 §17，M5）：
 * Option A = 每行 raw_text 全存 DB；Option B = 只存 offsets，文本 lazy read（RandomAccessFile + 按需解码）。
 * 用 3M+ 字符真实规模测量：DB size / import time / query speed / memory。
 * 结论写入 docs/experiments/TASK010_LARGE_FILE_REPORT.md + DECISIONS ADR。
 */
class RawTextStorageBenchmark {

    @Test
    fun `benchmark option A vs option B`() {
        val paras = listOf(
            "　　晨雾未散，林间小径上传来脚步声，他停下脚步低声说道。",
            "　　阿雪抬起头，目光落向远处的山脊，这一走便是三年。",
            "　　夜里烛火摇曳，他把那封信又读了一遍，风从窗口灌进来。",
        )
        val sb = StringBuilder()
        val target = 3_300_000
        while (sb.length < target) for (p in paras) sb.append(p).append("\n")
        val text = sb.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)

        val root = createTempDirectory("rv-bench-")
        val srcFile = root.resolve("book.txt")
        Files.write(srcFile, bytes)

        val det = EncodingDetector.detect(bytes)
        val r = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "bench", collectCharOffsets = false)
        val lines = r.lines
        println("=== BENCH input: ${lines.size} lines, ${bytes.size} bytes ===")

        // ---------- Option A ----------
        val dbA = root.resolve("opt_a.db").toFile()
        val tA0 = System.nanoTime()
        DriverManager.getConnection("jdbc:sqlite:${dbA.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE lines(line_no INTEGER PRIMARY KEY, byte_start INTEGER, byte_end INTEGER, char_start INTEGER, char_end INTEGER, raw_text TEXT)")
                conn.autoCommit = false

                val ps = conn.prepareStatement("INSERT INTO lines VALUES(?,?,?,?,?,?)")
                for (l in lines) {
                    ps.setInt(1, l.lineNo); ps.setInt(2, l.byteStart); ps.setInt(3, l.byteEnd)
                    ps.setInt(4, l.charStart); ps.setInt(5, l.charEnd); ps.setString(6, l.rawText)
                    ps.addBatch()
                }
                ps.executeBatch()
                conn.commit()
            }
        }
        val tA1 = System.nanoTime()
        val sizeA = dbA.length()

        // Option A 随机查询（1000 行）
        val qA0 = System.nanoTime()
        var sumLenA = 0
        DriverManager.getConnection("jdbc:sqlite:${dbA.absolutePath}").use { conn ->
            val ps = conn.prepareStatement("SELECT raw_text FROM lines WHERE line_no=?")
            repeat(1000) {
                ps.setInt(1, 1 + Random(7).nextInt(lines.size))
                ps.executeQuery().use { rs -> if (rs.next()) sumLenA += rs.getString(1).length }
            }
        }
        val qA1 = System.nanoTime()

        // ---------- Option B ----------
        val dbB = root.resolve("opt_b.db").toFile()
        val tB0 = System.nanoTime()
        DriverManager.getConnection("jdbc:sqlite:${dbB.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE lines(line_no INTEGER PRIMARY KEY, byte_start INTEGER, byte_end INTEGER, char_start INTEGER, char_end INTEGER)")
                conn.autoCommit = false

                val ps = conn.prepareStatement("INSERT INTO lines VALUES(?,?,?,?,?)")
                for (l in lines) {
                    ps.setInt(1, l.lineNo); ps.setInt(2, l.byteStart); ps.setInt(3, l.byteEnd)
                    ps.setInt(4, l.charStart); ps.setInt(5, l.charEnd)
                    ps.addBatch()
                }
                ps.executeBatch()
                conn.commit()
            }
        }
        val tB1 = System.nanoTime()
        val sizeB = dbB.length()

        // Option B 随机查询：取 offsets 后从源文件 lazy read + 解码
        val qB0 = System.nanoTime()
        var sumLenB = 0
        val dec = Utf8Decoder
        DriverManager.getConnection("jdbc:sqlite:${dbB.absolutePath}").use { conn ->
            val ps = conn.prepareStatement("SELECT byte_start, byte_end FROM lines WHERE line_no=?")
            val raf = java.io.RandomAccessFile(srcFile.toFile(), "r")
            repeat(1000) {
                ps.setInt(1, 1 + Random(7).nextInt(lines.size))
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        val bs = rs.getInt(1); val be = rs.getInt(2)
                        val buf = ByteArray(be - bs)
                        raf.seek(bs.toLong()); raf.readFully(buf)
                        var pos = 0
                        val sbl = StringBuilder()
                        while (pos < buf.size) {
                            val (cp, next) = dec.decodeNext(buf, pos, buf.size)
                            sbl.appendCodePoint(cp); pos = next
                        }
                        sumLenB += sbl.length
                    }
                }
            }
            raf.close()
        }
        val qB1 = System.nanoTime()

        val ms = { ns: Long -> "%.1f".format(ns / 1e6) }
        println("=== BENCH Option A (raw_text 全存) ===")
        println("db_size_mb=${"%.2f".format(sizeA / 1048576.0)}")
        println("import_ms=${ms(tA1 - tA0)}")
        println("query_1000_lines_ms=${ms(qA1 - qA0)}")
        println("=== BENCH Option B (offsets-only + lazy read) ===")
        println("db_size_mb=${"%.2f".format(sizeB / 1048576.0)}")
        println("import_ms=${ms(tB1 - tB0)}")
        println("query_1000_lines_ms=${ms(qB1 - qB0)}")
        println("=== BENCH sanity: A/B 文本一致 sumLenA=$sumLenA sumLenB=$sumLenB ===")

        assertTrue(sumLenA == sumLenB, "A/B decoded text must match")
        assertTrue(sizeB < sizeA, "Option B db must be smaller")
    }
}
