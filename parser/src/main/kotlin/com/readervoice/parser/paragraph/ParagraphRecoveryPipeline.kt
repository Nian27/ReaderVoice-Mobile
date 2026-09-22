package com.readervoice.parser.paragraph

import com.readervoice.parser.source.PhysicalLine

/**
 * ParagraphRecoveryPipeline（TASK-030 门面）v2：
 * PhysicalLine + Chapter/Volume anchors → Layout Profile → BlockGrouper → Boundary → Paragraph + SourceMap + Links。
 */
class ParagraphRecoveryPipeline {

    data class PipelineResult(
        val bookProfile: LayoutProfiler.BookLayoutProfile,
        val regions: List<LayoutRegion>,
        val boundaries: List<LineBoundary>,
        val paragraphs: List<LogicalParagraphBuilder.BuiltParagraph>,
        val normalizedSpans: Map<Long, List<NormalizedSpan>>,
        val links: List<CrossParagraphLink>,
        val spacingBlocks: List<SpacingBlock>,
        val profileTimeMs: Double,
        val boundaryTimeMs: Double,
        val buildTimeMs: Double,
    )

    fun run(
        lines: List<PhysicalLine>,
        bookId: String,
        chapterAnchors: Set<Int>,
        volumeAnchors: Set<Int>,
        tocLines: Set<Int>,
        chapterStartLines: List<Int>,
        /**
         * CH-0 / M0.5：确认章视图（按 `anchorByteStart` 升序）。传入后段落会带上稳定 `chapterId`，
         * 「按章导演 / 按章取文」才真正可用；不传则保持旧行为（chapterId = null）。
         */
        chapters: List<com.readervoice.parser.chapters.ConfirmedChapter> = emptyList(),
    ): PipelineResult {
        val t0 = System.nanoTime()
        val profiler = LayoutProfiler()
        val bookProfile = profiler.profile(lines)
        val regions = profiler.regions(lines, chapterStartLines)
        val grouper = BlockGrouper()
        val blockByLine = grouper.group(lines, chapterAnchors, volumeAnchors, tocLines)
        val t1 = System.nanoTime()

        val classifier = BoundaryClassifier(bookProfile)
        val quoteStack = QuoteStack()
        val boundaries = mutableListOf<LineBoundary>()
        var boundaryId = 1L
        val structural = chapterAnchors + volumeAnchors + tocLines
        val boundaryMap = mutableMapOf<Int, LineBoundary>()

        var prevLine: PhysicalLine? = null
        var prevBlank = false
        var quoteState = QuoteStack.State(0, emptyList())

        for (line in lines) {
            if (prevLine != null) {
                val nextBlank = line.isBlank
                // 引号状态逐行推进（§15）：depth_before = 进入本行前的状态
                val quoteBefore = quoteState.depth
                val quoteAfter = quoteStack.scanLine(line.rawText, quoteState).second.depth
                val prevInGroup = prevLine.lineNo in blockByLine
                val nextInGroup = line.lineNo in blockByLine
                val isStructural = line.lineNo in structural || prevLine.lineNo in structural ||
                    blockByLine[line.lineNo] == BlockType.SEPARATOR || blockByLine[prevLine.lineNo] == BlockType.SEPARATOR
                val f = classifier.features(prevLine, line, prevBlank, nextBlank, quoteBefore, quoteAfter, prevInGroup, nextInGroup)
                val d = classifier.classify(f, structuralBreak = isStructural)
                val b = LineBoundary(
                    boundaryId = boundaryId++, bookId = bookId, chapterId = null,
                    leftLineNo = prevLine.lineNo, rightLineNo = line.lineNo,
                    type = d.type, confidence = d.confidence,
                    evidenceMask = d.evidenceMask, evidence = d.evidence,
                    layoutProfileId = regions.firstOrNull { line.lineNo in it.startLine..it.endLine }?.regionId?.toString(),
                )
                boundaries += b
                if (d.type != LineBoundaryType.SOFT_WRAP) boundaryMap[prevLine.lineNo] = b
            }
            quoteState = quoteStack.scanLine(line.rawText, quoteState).second
            prevBlank = line.isBlank
            prevLine = line
        }
        val t2 = System.nanoTime()

        // 段落构建
        // ★ CH-0 / M0.5：段落 → 确认章稳定 id（按起始行；chapters 升序 ⇒ 游标 O(1) 摊还）
        var chCursor = 0
        val chapterOfLine: (Int) -> Long? = { line ->
            while (chCursor < chapters.size && line > chapters[chCursor].contentEndLine) chCursor++
            chapters.getOrNull(chCursor)?.takeIf { line >= it.contentStartLine }?.chapterId
        }
        val builder = LogicalParagraphBuilder()
        val built = builder.build(
            lines, boundaryMap, chapterAnchors, volumeAnchors, tocLines, blockByLine,
            chapterOfLine = chapterOfLine,
        )
        val spansByPara = built.associate { it.paragraph.paragraphId to it.normalizedSpans }
        val linker = CrossParagraphLinker()
        val links = linker.link(built)
        val t3 = System.nanoTime()

        return PipelineResult(
            bookProfile = bookProfile, regions = regions, boundaries = boundaries,
            paragraphs = built, normalizedSpans = spansByPara, links = links,
            spacingBlocks = emptyList(),
            profileTimeMs = (t1 - t0) / 1e6, boundaryTimeMs = (t2 - t1) / 1e6, buildTimeMs = (t3 - t2) / 1e6,
        )
    }
}

