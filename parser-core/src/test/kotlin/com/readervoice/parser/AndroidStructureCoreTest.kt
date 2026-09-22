package com.readervoice.parser

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.chapters.ResolveState
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidStructureCoreTest {
    @Test
    fun `frozen legacy rules compile confirmed chapters without a single-regex shortcut`() {
        val rules = javaClass.classLoader
            .getResourceAsStream("legacy/txtTocRule.json")
            .use(LegacyChapterRuleAdapter::fromJson)
        val bytes = "第一章，开局\n正文一。\n第二章，发展\n正文二。\n".toByteArray()
        val encoding = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, "book").lines

        val chapters = ChapterStructureCompiler(rules).compile(lines, "book").revision.chapters
            .filter { it.state == ResolveState.CONFIRMED }

        assertEquals(2, chapters.size)
        assertTrue(chapters.all { it.anchorByteStart >= 0 })
        assertEquals(listOf(1, 2), chapters.map { it.chapterIndex })
        // Android navigation persists titleRaw, not this derived suffix. Keep this test
        // coupled to the source-preserving invariant rather than punctuation cleanup.
        assertEquals(listOf("第一章，开局", "第二章，发展"), chapters.map { it.titleRaw })
    }
}
