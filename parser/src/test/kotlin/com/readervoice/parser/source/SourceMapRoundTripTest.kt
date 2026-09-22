package com.readervoice.parser.source

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceMapRoundTripTest {

    private fun mapOf(name: String): Pair<SourceMap, ByteArray> {
        val bytes = Fixtures.bytes(name)
        val det = EncodingDetector.detect(bytes)
        val r = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", collectCharOffsets = true)
        return SourceMap(r.lines, r.charByteOffsets) to bytes
    }

    @Test
    fun `byte range round-trips to exact raw text`() {
        for (name in listOf("utf8_lf.txt", "utf8_bom_crlf.txt", "gb18030.txt", "utf16le.txt", "emoji.txt", "very_long_line.txt")) {
            val (map, bytes) = mapOf(name)
            val det = EncodingDetector.detect(bytes)
            // 随机抽最多 100 行（lineNo 1-based，与 charRange/byteRange API 一致）
            val n = minOf(map.lineCount, 100)
            val idx = (1..map.lineCount).shuffled(Random(42)).take(n)
            for (lineNo in idx.sorted()) {
                val line = map.lineAtByteOffset(map.byteRange(lineNo).first).line
                val decoded = map.decodeLineText(bytes, lineNo, det.charset)
                assertEquals(line.rawText, decoded, "$name line $lineNo byte round-trip")
            }
        }
    }

    @Test
    fun `char offset maps to line and back`() {
        val (map, _) = mapOf("utf8_lf.txt")
        val n = minOf(map.lineCount, 100)
        val idx = (1..map.lineCount).shuffled(Random(7)).take(n)
        for (lineNo in idx.sorted()) {
            val (cs, ce) = map.charRange(lineNo)
            val atStart = map.lineAtCharOffset(cs)
            assertEquals(lineNo, atStart.lineNo)
            assertEquals(0, atStart.offsetInLine)
            if (ce > cs) {
                val atEnd = map.lineAtCharOffset(ce - 1)
                assertEquals(lineNo, atEnd.lineNo)
            }
        }
    }

    @Test
    fun `char to byte table exact`() {
        val (map, _) = mapOf("emoji.txt")
        // 行首字符的 byte 位置必须等于行 byteStart
        for (line in map.linesOf()) {
            if (line.charCount > 0) {
                assertEquals(line.byteStart, map.charToByte(line.charStart), "line ${line.lineNo} first char byte")
            }
        }
    }

    @Test
    fun `random char offsets resolve to consistent line text`() {
        val (map, bytes) = mapOf("utf8_lf.txt")
        val det = EncodingDetector.detect(bytes)
        val total = map.charRange(map.lineCount).second
        val rnd = Random(2026)
        repeat(500) {
            val cp = rnd.nextInt(total)
            val ref = map.lineAtCharOffset(cp)
            val lineText = ref.line.rawText
            val charInLine = ref.offsetInLine
            // 行内第 charInLine 个 code point 存在
            val cps = lineText.codePoints().toArray()
            assertTrue(charInLine < cps.size)
        }
    }

    @Test
    fun `line count matches decoded logical lines`() {
        val (map, bytes) = mapOf("eof_no_newline.txt")
        val det = EncodingDetector.detect(bytes)
        // Physical line count = 解码逻辑行记录数（最后一行无换行也算）
        assertEquals(3, map.lineCount)
        val (map2, _) = mapOf("utf8_lf.txt")
        assertEquals(7, map2.lineCount)
    }
}

// 测试辅助：暴露行列表
private fun SourceMap.linesOf(): List<PhysicalLine> = (1..lineCount).map { lineAtByteOffset(byteRange(it).first).line }
