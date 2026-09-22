package com.readervoice.parser.chapters

import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterStructureCompilerTest {

    private fun compileBook(text: String): ChapterStructureCompiler.PipelineResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "book", false).lines
        return ChapterStructureCompiler(ChapterFixtures.legacyRules()).compile(lines, "book")
    }

    private fun compileFixture(name: String): ChapterStructureCompiler.PipelineResult {
        val text = Files.readString(ChapterFixtures.file(name))
        return compileBook(text)
    }

    // ---------- G1：Legacy enabled examples Candidate Recall 100% ----------

    @Test
    fun `legacy enabled examples produce candidates`() {
        val rules = ChapterFixtures.legacyRules().filter { it.enabled }
        var total = 0
        var hit = 0
        val inconsistent = mutableListOf<String>()
        for (rule in rules) {
            for (ex in rule.positiveExamples) {
                total++
                // CONTEXT_AUGMENTED：部分 legacy 规则要求行前空白（lookbehind (?<=[　\s])），
                // example 省略了上下文——用前置全角空格的真实书籍变体验证（非静默修正，标注记录）
                val probe = if (rule.regex.contains("(?<=[　\\s])") || rule.regex.contains("(?<=[\\s　])")) "　$ex" else ex
                val bytes = probe.toByteArray(Charsets.UTF_8)
                val det = EncodingDetector.detect(bytes)
                val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
                val result = ChapterStructureCompiler(ChapterFixtures.legacyRules()).compile(lines, "b")
                val matched = result.candidates.any { it.ruleId == rule.ruleId }
                if (matched) hit++
                else inconsistent += "${rule.ruleId} (${rule.name}) example=\"$ex\""
            }
        }
        println("G1: enabled legacy examples candidate recall $hit/$total")
        if (inconsistent.isNotEmpty()) {
            println("LEGACY_FIXTURE_INCONSISTENT（记录，不静默修正）:")
            for (s in inconsistent) println("  $s")
        }
        assertEquals(total, hit, "enabled legacy positive examples must all produce candidates (G1)")
    }

    @Test
    fun `same line multiple rules resolve to one winner`() {
        val text = "第一章 标题\n"
        val r = compileBook(text)
        val groups = r.groups
        assertEquals(1, groups.size)
        // 同一行最终一个 winner（G6）
        assertEquals(1, r.revision.chapters.count { it.anchorLine == 1 })
    }

    // ---------- Gold book ----------

    @Test
    fun `gold structured book detects toc volume sequence extras`() {
        val r = compileFixture("gold_structured_book.txt")
        val confirmed = r.revision.chapters.filter { it.state == ResolveState.CONFIRMED }
        println(StructurePreview.render(r.revision, r.profile, r.groups))

        // TOC 块（前部 12 条目录）被识别且不产出正文 Chapter
        assertTrue(r.toc.blocks.isNotEmpty(), "TOC block expected")
        assertTrue(r.toc.entries.size >= 8)
        val tocAnchors = r.revision.chapters.filter { it.state == ResolveState.TOC_ENTRY }
        assertTrue(tocAnchors.isNotEmpty())

        // 卷：2 卷
        assertEquals(2, r.volumes.size)
        assertEquals(1, r.volumes[0].serialValue)
        assertEquals("风起", r.volumes[0].title)

        // 正文 Chapter：卷一章 1-5 + 卷二章 1-5 + 番外 + 尾声
        val body = r.revision.chapters.filter { it.state != ResolveState.TOC_ENTRY && it.state != ResolveState.REJECTED }
        val titles = body.map { it.titleRaw }
        assertTrue(titles.any { it.contains("卷一章1") }, "vol1 ch1 expected: $titles")
        assertTrue(titles.any { it.contains("卷二章5") }, "vol2 ch5 expected")
        assertTrue(titles.any { it.contains("番外") }, "extra expected")
        assertTrue(titles.any { it.contains("尾声") }, "afterword expected")
        assertEquals(12, body.size, "expect 10 chapters + extra + afterword")

        // 纯数字陷阱行（2026/520）不得成为 Chapter（G3）
        assertTrue(confirmed.none { it.titleRaw.trim() == "2026" }, "year line must not be a chapter")
        assertTrue(confirmed.none { it.titleRaw.trim() == "520" }, "number line must not be a chapter")
    }

    @Test
    fun `chapter indices are sequential and content ranges derive from next anchor`() {
        val r = compileFixture("gold_structured_book.txt")
        val anchors = r.revision.chapters
            .filter { it.state == ResolveState.CONFIRMED || it.state == ResolveState.PROVISIONAL }
            .sortedBy { it.anchorLine }
        // chapterIndex 连续递增（§31：serial 可重复/缺失，index 必须连续）
        assertEquals((1..anchors.size).toList(), anchors.map { it.chapterIndex })
        // content_end = 下一 anchor - 1
        for (i in anchors.indices) {
            val expected = if (i + 1 < anchors.size) anchors[i + 1].anchorLine - 1 else r.revision.tocBlocks.lastOrNull()?.let { 0 } ?: 0
            if (i + 1 < anchors.size) assertEquals(expected, anchors[i].contentEndLine)
        }
    }

    @Test
    fun `volume serial reset is legal`() {
        val r = compileFixture("gold_structured_book.txt")
        // 卷二第1章 serial=1 在卷一章 serial=5 之后——不能因"回退"被拒
        val vol2 = r.volumes.getOrNull(1) ?: return
        val after = r.revision.chapters.filter { it.anchorLine > vol2.startLine && it.state != ResolveState.REJECTED }
        assertTrue(after.any { it.serialValue == 1 }, "vol2 ch1 (serial 1) must survive reset (G4)")
    }

    // ---------- Round-trip（G7） ----------

    @Test
    fun `chapter anchors round-trip to source offsets`() {
        val text = "第一卷 风起\n第一章 开局\n　　正文一。\n第二章 发展\n　　正文二。\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val r = ChapterStructureCompiler(ChapterFixtures.legacyRules()).compile(lines, "b")
        val ch = r.revision.chapters.first { it.state == ResolveState.CONFIRMED && it.serialValue == 1 }
        // anchor 行可映射回原文本
        val line = lines[ch.anchorLine - 1]
        assertEquals("第一章 开局", line.rawText)
        assertEquals(ch.anchorByteStart, line.byteStart)
        assertEquals(ch.anchorCodepointStart, line.charStart)
    }

    // ---------- Override（G8） ----------

    @Test
    fun `user override survives and wins`() {
        val r = compileFixture("gold_structured_book.txt")
        val store = StructureOverrideStore()
        // 删除 520 行（若有候选）并改一个标题
        val target = r.groups.firstOrNull { it.winning.rawTitle.trim() == "520" }?.lineNo
        if (target != null) {
            store.add(StructureOverride("o1", "book", OverrideType.REMOVE_CHAPTER, targetLine = target))
        }
        val someChapter = r.revision.chapters.first { it.state == ResolveState.CONFIRMED }
        store.add(StructureOverride("o2", "book", OverrideType.CHANGE_TITLE, targetLine = someChapter.anchorLine, payload = mapOf("title" to "用户改的标题")))
        val revised = store.apply(r.revision, "book")
        val changed = revised.chapters.first { it.anchorLine == someChapter.anchorLine }
        assertEquals("用户改的标题", changed.titleRaw)
        if (target != null) {
            assertTrue(revised.chapters.none { it.anchorLine == target }, "removed by override")
        }
    }
}
