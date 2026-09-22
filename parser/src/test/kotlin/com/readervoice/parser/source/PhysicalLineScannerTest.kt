package com.readervoice.parser.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalLineScannerTest {

    private val scanner = PhysicalLineScanner()

    private fun scan(name: String, collect: Boolean = true): PhysicalLineScanner.ScanResult {
        val bytes = Fixtures.bytes(name)
        val det = EncodingDetector.detect(bytes)
        return scanner.scan(bytes, det.charset, det.bomBytes, "test-book", collect)
    }

    @Test
    fun `eof without newline counts as line`() {
        val r = scan("eof_no_newline.txt")
        assertEquals(3, r.lines.size)
        assertEquals("最后一行无换行", r.lines[2].rawText)
        assertEquals(PhysicalLine.NewlineKind.NONE, r.lines[2].newlineKind)
    }

    @Test
    fun `mixed newlines lf crlf cr`() {
        val r = scan("mixed_newlines.txt")
        assertEquals(4, r.lines.size)
        assertEquals(listOf(PhysicalLine.NewlineKind.LF, PhysicalLine.NewlineKind.CRLF, PhysicalLine.NewlineKind.CR, PhysicalLine.NewlineKind.LF),
            r.lines.map { it.newlineKind })
        assertEquals("line2 CRLF", r.lines[1].rawText) // \r 不进入行文本
        assertEquals("line3 CR", r.lines[2].rawText)
    }

    @Test
    fun `bom belongs to source bytes not first line text`() {
        val r = scan("utf8_bom_crlf.txt")
        assertEquals(3, r.lines[0].byteStart) // BOM 3 字节跳过
        assertEquals(0, r.lines[0].charStart) // BOM 不占字符位
        assertTrue(r.lines[0].rawText.startsWith("　　晨雾未散"))
    }

    @Test
    fun `layout whitespace preserved`() {
        val r = scan("fullwidth_indent.txt")
        assertEquals("　　全角缩进两格。", r.lines[0].rawText)
        assertEquals(2, r.lines[0].leadingFullwidthSpace)
        assertEquals(0, r.lines[0].leadingAsciiSpace)
        assertEquals("\tTab 缩进行。", r.lines[1].rawText)
        assertEquals(1, r.lines[1].leadingTab)
        assertEquals("普通行。  ", r.lines[2].rawText)
        assertEquals(2, r.lines[2].trailingSpace)
        assertEquals("　　末尾带半角空格行。 ", r.lines[3].rawText)
        assertEquals(2, r.lines[3].leadingFullwidthSpace)
        assertEquals(1, r.lines[3].trailingSpace)
    }

    @Test
    fun `emoji codepoint counting and byte offsets`() {
        val r = scan("emoji.txt")
        val l1 = r.lines[0]
        // "中文😀混合🀄️字符😀测试。" = 13 code points（🀄+VS16 = 2 cp；😀 出现两次）
        assertEquals(13, l1.charCount)
        assertEquals(42, l1.byteEnd - l1.byteStart) // 13 cp 的 UTF-8 字节数
        assertEquals(0, l1.charStart)
        assertEquals(13, l1.charEnd)
        // 每字符字节偏移（精确 char→byte 表；行1 字节布局：
        // 中(0)文(3)😀(6)混(10)合(13)🀄(16)VS16(20)字(23)符(26)😀(29)测(33)试(36)。(39)）
        val offs = r.charByteOffsets!!
        assertEquals(0, offs[0])          // 中
        assertEquals(3, offs[1])          // 文
        assertEquals(6, offs[2])          // 😀（4 字节）
        assertEquals(10, offs[3])         // 混
        assertEquals(13, offs[4])         // 合
        assertEquals(16, offs[5])         // 🀄（U+1F004）
        assertEquals(20, offs[6])         // VS16（U+FE0F，3 字节）
        assertEquals(23, offs[7])         // 字
        assertEquals(29, offs[9])         // 第二个 😀
        assertEquals(33, offs[10])        // 测
        assertEquals(39, offs[12])        // 。
        val l2 = r.lines[1]
        assertEquals(9, l2.charCount)     // 第二行带国旗🇨🇳。 = 9 cp
        assertEquals(29, l2.byteEnd - l2.byteStart)
    }

    @Test
    fun `very long single line`() {
        val r = scan("very_long_line.txt")
        assertEquals(1, r.lines.size)
        assertTrue(r.lines[0].charCount > 6000)
        assertEquals(PhysicalLine.NewlineKind.NONE, r.lines[0].newlineKind)
    }

    @Test
    fun `empty file zero lines`() {
        val r = scan("empty.txt")
        assertEquals(0, r.lines.size)
    }

    @Test
    fun `blank lines are blank`() {
        val r = scan("blank_lines.txt")
        assertEquals(5, r.lines.size)
        assertTrue(r.lines[0].isBlank.not())
        assertTrue(r.lines[1].isBlank)
        assertTrue(r.lines[2].isBlank)
        assertEquals("第四段", r.lines[3].rawText)
        assertTrue(r.lines[4].isBlank)
    }

    @Test
    fun `line numbers and char continuity`() {
        val r = scan("utf8_lf.txt")
        assertEquals((1..r.lines.size).toList(), r.lines.map { it.lineNo })
        // 字符区间连续无重叠
        for (i in 1 until r.lines.size) {
            assertEquals(r.lines[i - 1].charEnd, r.lines[i].charStart)
            assertEquals(r.lines[i - 1].charCount, r.lines[i].charStart - r.lines[i - 1].charStart)
        }
    }

    @Test
    fun `gb18030 decode`() {
        val bytes = Fixtures.bytes("gb18030.txt")
        val det = EncodingDetector.detect(bytes)
        val r = scanner.scan(bytes, det.charset, det.bomBytes, "b")
        assertEquals(8, r.lines.size) // 6 段 + "最后一行没有句号" + "扩展区字符：𠀀𠀁。"（无换行）
        assertTrue(r.lines[7].rawText.contains("𠀀"))
        assertEquals(PhysicalLine.NewlineKind.NONE, r.lines[7].newlineKind)
    }
}
