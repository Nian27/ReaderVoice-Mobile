package com.readervoice.parser.source

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportPipelineTest {

    private fun newPipeline(): Pair<ImportPipeline, Path> {
        val root = createTempDirectory("rv-import-test-")
        return ImportPipeline(root) to root
    }

    @Test
    fun `import utf8 book succeeds with index entry`() {
        val (pipe, _) = newPipeline()
        val out = pipe.import(Fixtures.file("utf8_lf.txt"), "content://fixture/utf8_lf.txt")
        val imported = assertIs<ImportPipeline.Outcome.Imported>(out)
        assertTrue(imported.bookId.startsWith("book-"))
        assertEquals(7, imported.lines.size)
        assertEquals(1, pipe.listBooks().size)
        assertEquals("IMPORTED", pipe.listBooks()[0].state)
        // 原始源文件未被修改
        assertEquals(Fixtures.bytes("utf8_lf.txt").toList(), Files.readAllBytes(Fixtures.file("utf8_lf.txt")).toList())
    }

    @Test
    fun `exact duplicate recognized`() {
        val (pipe, _) = newPipeline()
        val first = assertIs<ImportPipeline.Outcome.Imported>(pipe.import(Fixtures.file("utf8_lf.txt"), "uri:1"))
        val second = pipe.import(Fixtures.file("utf8_lf.txt"), "uri:2")
        val dup = assertIs<ImportPipeline.Outcome.ExactDuplicate>(second)
        assertEquals(first.bookId, dup.bookId)
        assertEquals(1, pipe.listBooks().size) // 不创建第二份
    }

    @Test
    fun `same filename different hash is distinct source not revision`() {
        val (pipe, root) = newPipeline()
        val src = root.resolve("book.txt")
        src.writeText("第一版内容。\n")
        val first = assertIs<ImportPipeline.Outcome.Imported>(pipe.import(src, "uri:a"))
        src.writeText("第二版内容，内容有修改。\n")
        val second = pipe.import(src, "uri:a")
        // ADR-016：文件名不是版本证据——同名不同 hash = 独立 Book，无 parent 链
        val distinct = assertIs<ImportPipeline.Outcome.DistinctSource>(second)
        assertTrue(distinct.bookId != first.bookId)
        assertEquals(null, distinct.entry.parentSourceFileId)
        assertEquals(2, pipe.listBooks().size)
    }

    @Test
    fun `explicit parent produces source revision`() {
        val (pipe, root) = newPipeline()
        val src = root.resolve("book.txt")
        src.writeText("第一版内容。\n")
        val first = assertIs<ImportPipeline.Outcome.Imported>(pipe.import(src, "uri:a"))
        src.writeText("第二版内容，用户显式更新。\n")
        val second = pipe.import(src, "uri:a", parentBookId = first.bookId)
        val rev = assertIs<ImportPipeline.Outcome.SourceRevision>(second)
        assertEquals(first.bookId, rev.parentBookId)
        assertEquals(first.bookId, rev.entry.parentSourceFileId)
        assertEquals(2, pipe.listBooks().size)
    }

    @Test
    fun `crash recovery cleans leftover tmp`() {
        val (pipe, root) = newPipeline()
        // 模拟进程死亡：留下 tmp 且无完成状态
        val tmp = root.resolve("incoming/book-deadbeef.tmp")
        tmp.writeBytes(byteArrayOf(1, 2, 3))
        val cleaned = pipe.recover()
        assertTrue(cleaned.any { it.contains("book-deadbeef.tmp") })
        assertTrue(!Files.exists(tmp))
        // 残留清理后正常导入
        val out = pipe.import(Fixtures.file("eof_no_newline.txt"), "uri:x")
        assertIs<ImportPipeline.Outcome.Imported>(out)
    }

    @Test
    fun `binary file rejected as corrupt text`() {
        val (pipe, _) = newPipeline()
        val out = pipe.import(Fixtures.file("binary_nul.bin"), "uri:bin")
        val failed = assertIs<ImportPipeline.Outcome.Failed>(out)
        assertEquals(ImportError.CORRUPT_TEXT, failed.error)
    }

    @Test
    fun `malformed utf8 rejected as unsupported encoding`() {
        val (pipe, _) = newPipeline()
        val out = pipe.import(Fixtures.file("malformed_utf8.bin"), "uri:bad")
        val failed = assertIs<ImportPipeline.Outcome.Failed>(out)
        assertEquals(ImportError.UNSUPPORTED_ENCODING, failed.error)
    }

    @Test
    fun `gbk file imports with GBK claim`() {
        val (pipe, root) = newPipeline()
        val gbkFile = root.resolve("gbk.txt")
        gbkFile.writeBytes("纯GBK编码的中文内容，不含四字节序列。".toByteArray(java.nio.charset.Charset.forName("GBK")))
        val out = pipe.import(gbkFile, "uri:gbk")
        val imported = assertIs<ImportPipeline.Outcome.Imported>(out)
        assertEquals("GBK", imported.entry.charset)
        assertTrue(imported.lines.isNotEmpty())
    }

    @Test
    fun `user charset override works and is locked`() {
        val (pipe, root) = newPipeline()
        // 故意用 GB18030 内容但先以错误编码尝试会被拒？这里验证 override 路径
        val f = root.resolve("mixed.txt")
        f.writeBytes("用户指定编码的测试内容。".toByteArray(java.nio.charset.Charset.forName("GB18030")))
        val out = pipe.import(f, "uri:o", charsetOverride = CharsetKind.GB18030)
        val imported = assertIs<ImportPipeline.Outcome.Imported>(out)
        assertEquals("GB18030", imported.entry.charset)
        assertEquals(1.0, imported.entry.confidence) // user locked → confidence 1.0
    }

    @Test
    fun `failed import leaves no IMPORTED fake state`() {
        val (pipe, root) = newPipeline()
        val bad = root.resolve("bad.txt")
        bad.writeBytes("正常开头".toByteArray(java.nio.charset.Charset.forName("UTF-8")) + byteArrayOf(0xC3.toByte(), 0x28))
        val out = pipe.import(bad, "uri:bad2")
        assertIs<ImportPipeline.Outcome.Failed>(out)
        assertTrue(pipe.listBooks().none { it.state == "IMPORTED" })
        // 索引文件不应包含该 book
        assertTrue(pipe.listBooks().isEmpty())
    }

    @Test
    fun `utf16 book round trip`() {
        val (pipe, _) = newPipeline()
        val out = pipe.import(Fixtures.file("utf16le.txt"), "uri:u16")
        val imported = assertIs<ImportPipeline.Outcome.Imported>(out)
        assertEquals("UTF-16LE", imported.entry.charset)
        assertTrue(imported.lines[0].rawText.startsWith("　　晨雾未散"))
    }
}
