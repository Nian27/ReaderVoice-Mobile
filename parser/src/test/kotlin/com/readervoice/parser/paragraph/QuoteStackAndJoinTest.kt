package com.readervoice.parser.paragraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuoteStackAndJoinTest {

    @Test
    fun `quote crosses physical line`() {
        val q = QuoteStack()
        val (before1, after1) = q.scanLine("“第一段引语内容，")
        assertTrue(after1.depth > 0)
        val (before2, after2) = q.scanLine("第二段引语内容，", after1)
        assertTrue(before2.depth > 0)
        assertTrue(after2.depth > 0)
        val (_, after3) = q.scanLine("第三段引语内容。”", after2)
        assertEquals(0, after3.depth)
    }

    @Test
    fun `nested quotes`() {
        val q = QuoteStack()
        val (_, after) = q.scanLine("他说：“这里写着「禁止」。”")
        assertEquals(0, after.depth)
    }

    @Test
    fun `quote closes after multiple lines`() {
        val q = QuoteStack()
        var state = QuoteStack.State(0, emptyList())
        state = q.scanLine("“第一行", state).second
        state = q.scanLine("第二行", state).second
        state = q.scanLine("第三行。”", state).second
        assertEquals(0, state.depth)
    }

    @Test
    fun `quote is not dialogue`() {
        // 结构层只跟踪引号，不判定对话（§16）
        val q = QuoteStack()
        val (_, after) = q.scanLine("墙上写着“禁止入内”")
        assertEquals(0, after.depth)
    }

    @Test
    fun `chinese join no space`() {
        assertEquals("", JoinPolicy.joinSeparator("张三看着", "李雪。"))
    }

    @Test
    fun `english join with space`() {
        assertEquals(" ", JoinPolicy.joinSeparator("This is a long", "sentence."))
    }

    @Test
    fun `mixed join`() {
        assertEquals(" ", JoinPolicy.joinSeparator("使用 Transformer", "模型进行分析。"))
        assertEquals(" ", JoinPolicy.joinSeparator("Python", "语言非常流行。"))
    }

    @Test
    fun `trailing space avoids duplicate`() {
        assertEquals("", JoinPolicy.joinSeparator("已带尾空格 ", "下一行"))
    }
}

class SpecialBlockDetectorTest {

    private val det = SpecialBlockDetector()

    private fun line(text: String) = com.readervoice.parser.source.PhysicalLine(
        lineId = 0, bookId = "b", lineNo = 1,
        byteStart = 0, byteEnd = text.length, charStart = 0, charEnd = text.codePointCount(0, text.length),
        rawText = text, leadingAsciiSpace = 0, leadingFullwidthSpace = 0, leadingTab = 0, trailingSpace = 0,
        charCount = text.codePointCount(0, text.length), hanCount = 0, isBlank = text.isBlank(),
        newlineKind = com.readervoice.parser.source.PhysicalLine.NewlineKind.LF,
    )

    @Test
    fun `separator detected`() {
        for (s in listOf("***", "————", "☆☆☆☆", "====")) {
            assertEquals(BlockType.SEPARATOR, det.classify(line(s), emptySet(), emptySet(), emptySet()).blockType, s)
        }
    }

    @Test
    fun `boilerplate detected conservatively`() {
        assertEquals(BlockType.BOILERPLATE, det.classify(line("最新网址：www.example.com"), emptySet(), emptySet(), emptySet()).blockType)
        assertEquals(BlockType.BOILERPLATE, det.classify(line("请收藏本站"), emptySet(), emptySet(), emptySet()).blockType)
        assertEquals(BlockType.BOILERPLATE, det.classify(line("https://example.com/novel/123"), emptySet(), emptySet(), emptySet()).blockType)
        // 保守：含"请记住"的普通句子不得误判（§36）
        assertTrue(det.classify(line("请记住我。"), emptySet(), emptySet(), emptySet()).blockType != BlockType.BOILERPLATE)
    }

    @Test
    fun `script and message detected`() {
        assertEquals(BlockType.SCRIPT, det.classify(line("张三：你好。"), emptySet(), emptySet(), emptySet()).blockType)
        assertEquals(BlockType.MESSAGE_LOG, det.classify(line("张三 20:31"), emptySet(), emptySet(), emptySet()).blockType)
        assertEquals(BlockType.LIST, det.classify(line("1. 苹果"), emptySet(), emptySet(), emptySet()).blockType)
    }

    @Test
    fun `poetry block group`() {
        val lines = ParagraphFixtures.lines("poetry.txt")
        assertTrue(det.poetryBlock(lines))
        assertFalse(det.poetryBlock(ParagraphFixtures.lines("paragraph_per_line.txt")))
    }

    @Test
    fun `chapter and volume anchors`() {
        assertEquals(BlockType.CHAPTER_TITLE, det.classify(line("第一章 开局"), setOf(1), emptySet(), emptySet()).blockType)
        assertEquals(BlockType.VOLUME_TITLE, det.classify(line("第一卷 风起"), emptySet(), setOf(1), emptySet()).blockType)
        assertEquals(BlockType.TOC_ENTRY, det.classify(line("第1章 目录条目"), emptySet(), emptySet(), setOf(1)).blockType)
    }
}
