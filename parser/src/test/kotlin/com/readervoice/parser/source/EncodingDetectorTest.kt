package com.readervoice.parser.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncodingDetectorTest {

    private fun detect(name: String) = EncodingDetector.detect(Fixtures.bytes(name))

    @Test
    fun `utf8 lf`() {
        val r = detect("utf8_lf.txt")
        assertEquals(CharsetKind.UTF_8, r.charset)
        assertTrue(r.confidence >= 0.8)
    }

    @Test
    fun `utf8 bom crlf`() {
        val r = detect("utf8_bom_crlf.txt")
        assertEquals(CharsetKind.UTF_8_BOM, r.charset)
        assertEquals(1.0, r.confidence)
        assertEquals(3, r.bomBytes) // BOM 属于源字节
    }

    @Test
    fun `gb18030 with 4-byte sequences`() {
        val r = detect("gb18030.txt")
        assertEquals(CharsetKind.GB18030, r.charset) // 4 字节序列 → 声称 GB18030 而非 GBK
        assertTrue(r.confidence >= 0.6)
    }

    @Test
    fun `gbk without 4-byte sequences claims GBK not GB18030`() {
        // 构造纯双字节 GBK 文本（无 GB18030 特有 4 字节序列）
        val text = "这是一段纯GBK编码的中文文本，用于验证GBK声称逻辑。"
        val bytes = text.toByteArray(java.nio.charset.Charset.forName("GBK"))
        val r = EncodingDetector.detect(bytes)
        assertEquals(CharsetKind.GBK, r.charset)
    }

    @Test
    fun `utf16 le and be`() {
        assertEquals(CharsetKind.UTF_16LE, detect("utf16le.txt").charset)
        assertEquals(CharsetKind.UTF_16BE, detect("utf16be.txt").charset)
    }

    @Test
    fun `plain utf8 variants`() {
        assertEquals(CharsetKind.UTF_8, detect("eof_no_newline.txt").charset)
        assertEquals(CharsetKind.UTF_8, detect("mixed_newlines.txt").charset)
        assertEquals(CharsetKind.UTF_8, detect("fullwidth_indent.txt").charset)
        assertEquals(CharsetKind.UTF_8, detect("very_long_line.txt").charset)
        assertEquals(CharsetKind.UTF_8, detect("emoji.txt").charset)
        assertEquals(CharsetKind.UTF_8, detect("one_line.txt").charset)
    }

    @Test
    fun `empty file default utf8 low confidence`() {
        val r = detect("empty.txt")
        assertEquals(CharsetKind.UTF_8, r.charset)
        assertTrue(r.confidence < 0.6)
    }

    @Test
    fun `malformed utf8 yields no candidate`() {
        val r = detect("malformed_utf8.bin")
        assertEquals(0.0, r.confidence) // 无候选 → 调用方报 UNSUPPORTED_ENCODING
    }

    @Test
    fun `nul-heavy binary is valid ascii but rejected later`() {
        // detector 层面：NUL 是合法 ASCII/UTF-8，detector 报 UTF-8；拒收发生在 ImportPipeline 的 non-text 层
        val r = detect("binary_nul.bin")
        assertEquals(CharsetKind.UTF_8, r.charset)
    }
}
