package com.readervoice.parser.paragraph

import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLine
import com.readervoice.parser.source.PhysicalLineScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TASK-030 fixtures。 */
object ParagraphFixtures {
    fun dir(): Path {
        val candidates = listOf(
            Path.of("..", "tests", "fixtures", "paragraph"),
            Path.of("tests", "fixtures", "paragraph"),
            Path.of("..", "..", "tests", "fixtures", "paragraph"),
        )
        return candidates.first { Files.isDirectory(it) }
    }

    fun lines(name: String): List<PhysicalLine> {
        val bytes = Files.readAllBytes(dir().resolve(name))
        val det = EncodingDetector.detect(bytes)
        return PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
    }

    fun text(name: String): String = Files.readString(dir().resolve(name))
}

class LayoutProfilerTest {

    @Test
    fun `fixed width 20 detected`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("fixed_width_20.txt"))
        assertEquals(TextLayoutProfile.FIXED_WIDTH_HARD_WRAP, p.profileType)
        assertTrue(p.dominantModes.isNotEmpty())
    }

    @Test
    fun `fixed width 40 detected`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("fixed_width_40.txt"))
        assertEquals(TextLayoutProfile.FIXED_WIDTH_HARD_WRAP, p.profileType)
    }

    @Test
    fun `paragraph per line with indent`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("paragraph_per_line.txt"))
        assertTrue(
            p.profileType == TextLayoutProfile.INDENTED_PARAGRAPH || p.profileType == TextLayoutProfile.PARAGRAPH_PER_LINE,
            "expected indented/per-line, got ${p.profileType}",
        )
        assertTrue(p.leadingFullwidthIndentRatio > 0.5)
    }

    @Test
    fun `blank line paragraph`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("blank_line_paragraph.txt"))
        assertEquals(TextLayoutProfile.BLANK_LINE_PARAGRAPH, p.profileType)
        assertTrue(p.blankLineRatio >= 0.15)
    }

    @Test
    fun `poetry like`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("poetry.txt"))
        assertEquals(TextLayoutProfile.POETRY_LIKE, p.profileType)
    }

    @Test
    fun `script dialogue`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("script.txt"))
        assertEquals(TextLayoutProfile.SCRIPT_DIALOGUE, p.profileType)
    }

    @Test
    fun `english wrap detected as fixed width`() {
        val p = LayoutProfiler().profile(ParagraphFixtures.lines("english_wrap.txt"))
        assertTrue(p.profileType == TextLayoutProfile.FIXED_WIDTH_HARD_WRAP || p.profileType == TextLayoutProfile.MIXED)
    }

    @Test
    fun `regions segmented on mixed book`() {
        // 前 100 行 fixed-width + 后 100 行 paragraph-per-line
        val sb = StringBuilder()
        for (i in 0 until 50) sb.append("这是一段固定宽度断行的文本内容用于测试区域分段逻辑的合理性。".slice(0 until 20)).append("\n")
        for (i in 0 until 50) sb.append("　　正常段落缩进一行一段的文本内容第${i}行。\n")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val regions = LayoutProfiler().regions(lines, emptyList(), window = 30)
        assertTrue(regions.size >= 2, "expected >=2 regions, got ${regions.size}")
        val types = regions.map { it.profileType }.toSet()
        assertTrue(types.contains(TextLayoutProfile.FIXED_WIDTH_HARD_WRAP), "fixed-width region expected: $types")
    }
}

// 辅助：near-mode 比例（测试用）
private fun LayoutProfiler.BookLayoutProfile.nearModeRatio(mode: Int): Double = 0.0 // 简化；由 dominantModes 直接验证
