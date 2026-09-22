package com.readervoice.parser.paragraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pipeline 集成测试（TASK-030 §61-§65/§66 G1-G9）。
 */
class ParagraphRecoveryPipelineTest {

    private fun run(text: String, chapterAnchors: Set<Int> = emptySet(), volumeAnchors: Set<Int> = emptySet(), tocLines: Set<Int> = emptySet()): ParagraphRecoveryPipeline.PipelineResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = com.readervoice.parser.source.EncodingDetector.detect(bytes)
        val lines = com.readervoice.parser.source.PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        return ParagraphRecoveryPipeline().run(lines, "b", chapterAnchors, volumeAnchors, tocLines, chapterStartLines = chapterAnchors.sorted())
    }

    @Test
    fun `fixed width 20 joins into correct paragraphs`() {
        val r = run(ParagraphFixtures.text("fixed_width_20.txt"))
        assertEquals(TextLayoutProfile.FIXED_WIDTH_HARD_WRAP, r.bookProfile.profileType)
        // 2 段文本 → 2 个逻辑段落（Exact Match，G4）
        assertEquals(2, r.paragraphs.size)
        assertEquals("张三推开门，看见李雪坐在桌边，皱眉说道，你怎么还没回去。外面天已经全黑了，路上不太安全，我送你回去吧，她轻声回答着。", r.paragraphs[0].paragraph.normalizedText)
        assertEquals("他点了点头，转身走进夜色里，身后的灯火渐渐远去。第二天清晨，他收到一封信，信上只有四个字，一切安好。", r.paragraphs[1].paragraph.normalizedText)
    }

    @Test
    fun `fixed width 40 joins`() {
        val r = run(ParagraphFixtures.text("fixed_width_40.txt"))
        assertEquals(2, r.paragraphs.size)
        assertEquals("张三推开门，看见李雪坐在桌边，皱眉说道，你怎么还没回去。外面天已经全黑了，路上不太安全，我送你回去吧。", r.paragraphs[0].paragraph.normalizedText)
    }

    @Test
    fun `paragraph per line stays separate`() {
        val r = run(ParagraphFixtures.text("paragraph_per_line.txt"))
        // 一行一段 + 缩进 → 3 段，不误合并（OverSplit/OverMerge 检查）
        assertEquals(3, r.paragraphs.size)
        assertEquals("晨雾未散，林间小径上传来一阵脚步声。", r.paragraphs[0].paragraph.normalizedText) // 段首缩进已移除
    }

    @Test
    fun `blank line paragraphs`() {
        val r = run(ParagraphFixtures.text("blank_line_paragraph.txt"))
        assertEquals(3, r.paragraphs.size)
    }

    @Test
    fun `poetry not reflowed`() {
        val r = run(ParagraphFixtures.text("poetry.txt"))
        // G5：诗歌不 prose reflow——每行独立段落
        assertEquals(4, r.paragraphs.size)
        assertTrue(r.paragraphs.all { it.paragraph.blockType == BlockType.POETRY })
    }

    @Test
    fun `script not joined`() {
        val r = run(ParagraphFixtures.text("script.txt"))
        assertEquals(3, r.paragraphs.size) // G5：剧本每行独立
        assertTrue(r.paragraphs.all { it.paragraph.blockType == BlockType.SCRIPT })
    }

    @Test
    fun `message log not joined`() {
        val r = run(ParagraphFixtures.text("message_log.txt"))
        assertEquals(4, r.paragraphs.size) // 4 行（含 2 内容行）各自独立
    }

    @Test
    fun `separator is structural break`() {
        val r = run(ParagraphFixtures.text("separator.txt"))
        // 3 段正文；分隔符行不产段落
        assertEquals(3, r.paragraphs.size)
        assertTrue(r.paragraphs.none { it.paragraph.blockType == BlockType.SEPARATOR })
    }

    @Test
    fun `speech cue keeps two paragraphs and links`() {
        val r = run(ParagraphFixtures.text("speech_cue.txt"))
        // G6：speech cue 两行保留两段（P2/§38），不 JOIN
        assertEquals(2, r.paragraphs.size)
        val link = r.links.first { it.type == LinkType.SPEECH_CUE }
        assertEquals(0.98, link.confidence)
        assertEquals("张三说道：", r.paragraphs[0].paragraph.normalizedText)
        assertEquals("“你来了。”", r.paragraphs[1].paragraph.normalizedText)
    }

    @Test
    fun `quote continuation merged into one paragraph`() {
        // 短引语 3 行（无空行）→ 合并 1 段（QUOTE_CONTINUES 证据）；长引语跨段场景由 blank 分割
        val r = run(ParagraphFixtures.text("quote_cross.txt"))
        assertEquals(1, r.paragraphs.size)
        assertEquals("“第一段引语内容，第二段引语内容，第三段引语内容。”", r.paragraphs[0].paragraph.normalizedText)
    }

    @Test
    fun `english wrap joins with spaces`() {
        val r = run(ParagraphFixtures.text("english_wrap.txt"))
        assertEquals(2, r.paragraphs.size)
        assertEquals("This is a long sentence that wraps at fixed width.", r.paragraphs[0].paragraph.normalizedText)
    }

    @Test
    fun `mixed join inserts single space`() {
        val r = run(ParagraphFixtures.text("mixed_join.txt"))
        assertEquals(2, r.paragraphs.size)
        assertEquals("使用 Transformer 模型进行分析。", r.paragraphs[0].paragraph.normalizedText)
        assertEquals("Python 语言非常流行。", r.paragraphs[1].paragraph.normalizedText)
    }

    @Test
    fun `chapter anchor is structural boundary and title block`() {
        val text = "第一章 开局\n　　正文第一段内容。\n第二章 发展\n　　正文第二段内容。\n"
        val r = run(text, chapterAnchors = setOf(1, 3))
        // 标题行不进入正文段落（§49），正文 2 段
        val prose = r.paragraphs.filter { it.paragraph.readPolicy == ReadPolicy.NORMAL }
        assertEquals(2, prose.size)
        val title = r.paragraphs.firstOrNull { it.paragraph.blockType == BlockType.CHAPTER_TITLE }
        assertTrue(title != null, "chapter title paragraph expected")
        // G2：Paragraph 不跨 Chapter anchor
        assertTrue(r.paragraphs.none { p -> p.paragraph.sourceSpans.any { it.lineNo == 1 } && p.paragraph.sourceSpans.any { it.lineNo == 3 } })
    }

    @Test
    fun `user join and split respected`() {
        // G3：USER_JOIN/USER_BREAK 100% 尊重（自动重解析不覆盖）
        val text = "第一段。\n第二段。\n第三段。\n"
        val r1 = run(text)
        assertEquals(3, r1.paragraphs.size)
        // 模拟用户 lock：boundary 2-3 被 USER_JOIN 锁定 → 手动合并验证（override store 层）
        val store = ParagraphOverrideStore()
        store.lockBoundary(2, 3)
        assertTrue(store.isLocked(2, 3))
        val ev = store.join(r1.paragraphs[0].paragraph.paragraphId, r1.paragraphs[1].paragraph.paragraphId, "b")
        assertEquals("PARAGRAPH_JOIN", ev.type)
        val inv = store.invalidationFor(ev)
        assertTrue(inv.paragraphIds.contains(r1.paragraphs[0].paragraph.paragraphId))
        assertTrue(inv.invalidatedDomains.contains("semantic_segment"))
    }

    @Test
    fun `normalized source map round trips`() {
        // G7：normalized char → source（非合成字符 100%）
        val r = run(ParagraphFixtures.text("fixed_width_20.txt"))
        for (bp in r.paragraphs) {
            val spans = r.normalizedSpans.getValue(bp.paragraph.paragraphId)
            val text = bp.paragraph.normalizedText
            // 每个非合成 normalized 字符都有 source 定位
            var normIdx = 0
            for (sp in spans) {
                if (sp.synthetic) {
                    assertEquals(TransformType.INSERTED_SPACE, sp.transformType)
                    assertEquals(null, sp.sourceLineNo) // G8：synthetic 不伪造 offset
                } else {
                    assertTrue(sp.sourceLineNo != null && sp.sourceCodepointStart != null)
                    assertTrue(sp.sourceCodepointEnd!! > sp.sourceCodepointStart!!)
                }
                normIdx = sp.normalizedEnd
            }
            assertEquals(text.length, normIdx)
        }
    }
}
