package com.readervoice.parser.paragraph

import com.readervoice.parser.source.PhysicalLine

/**
 * LogicalParagraphBuilder + NormalizedSourceMapBuilder（TASK-030 §41-§48/§84-§93）。
 * - 按 boundaries 分组行 → LogicalParagraph（source_spans + normalized_text + blockType + readPolicy）
 * - NormalizedSourceMap 在构建时同步生成（§46：禁止事后 substring 反推）
 * - 段首全角缩进 → REMOVED_INDENT（transform 记录，不丢映射）；synthetic 空格 → INSERTED_SPACE synthetic=true
 */
class LogicalParagraphBuilder {

    /** 段落 + normalized→source 映射。 */
    data class BuiltParagraph(
        val paragraph: LogicalParagraph,
        val normalizedSpans: List<NormalizedSpan>,
    )

    /** boundaries：键 = 左行号（i 与 i+1 之间），值 = 类型。null 键的左右行 = 空行间隙。 */
    fun build(
        lines: List<PhysicalLine>,
        boundaries: Map<Int, LineBoundary>,
        chapterAnchors: Set<Int> = emptySet(),
        volumeAnchors: Set<Int> = emptySet(),
        tocLines: Set<Int> = emptySet(),
        blockByLine: Map<Int, BlockType> = emptyMap(),
        paragraphIdSeed: Long = 1,
        /**
         * CH-0 / M0.5：**段落 → 章节身份**的映射（按段落起始行）。
         * 由调用方用 `ConfirmedChapterView` 构造；返回 null 表示该段落不在任何确认章内
         * （书头/书尾/卷首）—— 这时**明确留 null，不硬塞给相邻章**。
         */
        chapterOfLine: (Int) -> Long? = { null },
    ): List<BuiltParagraph> {
        // 段落边界 = STRUCTURAL/AUTHOR/HARD/UNCERTAIN/USER_BREAK；SOFT_WRAP/USER_JOIN 合并
        val paragraphBreaks = boundaries.filter { (_, b) ->
            b.type in setOf(
                LineBoundaryType.STRUCTURAL_BREAK, LineBoundaryType.AUTHOR_LINE_BREAK,
                LineBoundaryType.HARD_PARAGRAPH, LineBoundaryType.UNCERTAIN, LineBoundaryType.USER_BREAK,
            )
        }.keys.sorted()

        val out = mutableListOf<BuiltParagraph>()
        var paraId = paragraphIdSeed
        var idx = 0
        var segStart = 0 // 段落起始行下标（0-based 于 lines）
        val segBreaks = paragraphBreaks + lines.size // 行下标边界

        for (end in segBreaks) {
            val segLines = lines.subList(segStart, end)
            // 空行段不产可朗读段落（ADR-021）；仅全空行段跳过
            if (segLines.any { !it.isBlank }) {
                val built = buildOne(segLines, paraId++, idx++, boundaries, blockByLine, chapterOfLine)
                // 分隔符/广告行不产段落（§29/§35：SKIP_READ 内容不进入朗读段落流，与空行一致）
                if (built.paragraph.blockType !in setOf(BlockType.SEPARATOR, BlockType.BOILERPLATE)) {
                    out += built
                }
            }
            segStart = end
        }
        // 尾部残余
        if (segStart < lines.size) {
            val segLines = lines.subList(segStart, lines.size)
            if (segLines.any { !it.isBlank }) {
                val built = buildOne(segLines, paraId++, idx++, boundaries, blockByLine, chapterOfLine)
                if (built.paragraph.blockType !in setOf(BlockType.SEPARATOR, BlockType.BOILERPLATE)) {
                    out += built
                }
            }
        }
        return out
    }

    private fun buildOne(
        segLines: List<PhysicalLine>, paraId: Long, index: Int, boundaries: Map<Int, LineBoundary>,
        blockByLine: Map<Int, BlockType>,
        /** M0.5：段落 → 章节稳定 id（null = 不在任何确认章内） */
        chapterOfLine: (Int) -> Long? = { null },
    ): BuiltParagraph {
        val sb = StringBuilder()
        val spans = mutableListOf<NormalizedSpan>()
        val sourceSpans = mutableListOf<SourceSpan>()

        for ((i, line) in segLines.withIndex()) {
            val text = line.rawText
            // 段首全角缩进（仅段首行）：REMOVED_INDENT
            val indent = line.leadingFullwidthSpace.coerceAtLeast(line.leadingTab)
            val body = text.substring(minOf(indent, text.length))
            val bodyStartCp = line.charStart + minOf(indent, line.charCount)

            // 行间连接（§23-§25）
            var sep = ""
            var synthetic = false
            if (i > 0) {
                sep = JoinPolicy.joinSeparator(segLines[i - 1].rawText, text)
                synthetic = sep.isNotEmpty()
            }
            if (sep.isNotEmpty()) {
                val ns = sb.length
                sb.append(sep)
                spans += NormalizedSpan(ns, ns + sep.length, null, null, null, TransformType.INSERTED_SPACE, synthetic = true)
            }

            // IDENTITY 段（去缩进后的正文）
            val ns = sb.length
            sb.append(body)
            spans += NormalizedSpan(
                ns, sb.length, line.lineNo, bodyStartCp, bodyStartCp + body.codePointCount(0, body.length),
                if (i == 0 && indent > 0) TransformType.REMOVED_INDENT else TransformType.REMOVED_NEWLINE,
            )
            sourceSpans += SourceSpan(
                line.lineNo, line.byteStart + minOf(indent, line.rawText.length),
                line.byteEnd, bodyStartCp, line.charEnd,
            )
        }

        val blockType = classifyBlock(segLines, blockByLine)
        val readPolicy = when (blockType) {
            BlockType.CHAPTER_TITLE -> ReadPolicy.CHAPTER_TITLE
            BlockType.VOLUME_TITLE -> ReadPolicy.VOLUME_TITLE
            BlockType.TOC_ENTRY -> ReadPolicy.TOC_ENTRY
            BlockType.BOILERPLATE, BlockType.SEPARATOR -> ReadPolicy.SKIP_READ
            else -> ReadPolicy.NORMAL
        }
        val paragraph = LogicalParagraph(
            paragraphId = paraId, revisionId = paraId, bookId = segLines.first().bookId,
            // ★ M0.5：段落继承确认章的稳定 id（此前恒为 null ⇒ 按章过滤恒为空）
            chapterId = chapterOfLine(segLines.first().lineNo), paragraphIndex = index,
            sourceSpans = sourceSpans, normalizedText = sb.toString(),
            blockType = blockType, readPolicy = readPolicy,
            boundaryConfidence = boundaries[segLines.first().lineNo - 1]?.confidence ?: 1.0,
        )
        return BuiltParagraph(paragraph, spans)
    }

    /** 块类型：段内行继承 BlockGrouper 的组类型（首个非 PROSE/UNKNOWN）；否则单行分类兜底。 */
    private fun classifyBlock(segLines: List<PhysicalLine>, blockByLine: Map<Int, BlockType>): BlockType {
        for (line in segLines) {
            blockByLine[line.lineNo]?.let { if (it != BlockType.UNKNOWN) return it }
        }
        val det = SpecialBlockDetector()
        if (det.poetryBlock(segLines)) return BlockType.POETRY
        for (line in segLines) {
            val c = det.classify(line, emptySet(), emptySet(), emptySet())
            if (c.blockType != BlockType.PROSE && c.blockType != BlockType.UNKNOWN) return c.blockType
        }
        return BlockType.PROSE
    }
}


