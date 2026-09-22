package com.readervoice.parser.paragraph

import com.readervoice.parser.source.PhysicalLine

/**
 * BoundaryClassifier（TASK-030 §17-§21/§78）v2：
 * deterministic rules + weighted evidence score。
 * - quoteDepth 由外部（pipeline QuoteStack 逐行推进）传入，不维护实例状态（防跨边界泄漏）
 * - 特殊块（poetry/script/message/list 组）由 BlockGrouper 判定，classifier 只接收标记
 */
class BoundaryClassifier(private val profile: LayoutProfiler.BookLayoutProfile) {

    data class Features(
        val prevLength: Int, val nextLength: Int,
        val prevBlank: Boolean, val nextBlank: Boolean,
        val prevIndent: Int, val nextIndent: Int,
        val prevTerminal: Boolean, val prevColon: Boolean, val prevComma: Boolean, val prevSemicolon: Boolean,
        val nextStartsQuote: Boolean, val nextStartsDialogueLike: Boolean,
        val quoteDepthBefore: Int, val quoteDepthAfter: Int,
        val prevNearWrapWidth: Boolean, val nextNearWrapWidth: Boolean,
        val prevInSpecialGroup: Boolean, val nextInSpecialGroup: Boolean,
    )

    data class Decision(val type: LineBoundaryType, val confidence: Double, val evidence: List<String>, val evidenceMask: Long)

    private val TERMINAL = setOf('。', '！', '？', '!', '?', '.', '…', '”', '」', '』', '"', '\'')
    private val CUE_END = Regex("(说道|说|问|答|喊|叫|嚷|喝道|道|低声道|笑道|哭道|叹道|问道|答道)[：:]?$")

    fun features(
        prev: PhysicalLine?, next: PhysicalLine?,
        prevBlank: Boolean, nextBlank: Boolean,
        quoteDepthBefore: Int, quoteDepthAfter: Int,
        prevInSpecialGroup: Boolean, nextInSpecialGroup: Boolean,
    ): Features {
        val p = prev?.rawText ?: ""
        val n = next?.rawText ?: ""
        val mode = profile.dominantModes.firstOrNull() ?: 0
        return Features(
            prevLength = p.codePointCount(0, p.length),
            nextLength = n.codePointCount(0, n.length),
            prevBlank = prevBlank, nextBlank = nextBlank,
            prevIndent = prev?.leadingFullwidthSpace ?: 0,
            nextIndent = next?.leadingFullwidthSpace ?: 0,
            prevTerminal = p.lastOrNull()?.let { it in TERMINAL } == true,
            prevColon = p.lastOrNull() == '：' || p.lastOrNull() == ':',
            prevComma = p.lastOrNull() == '，' || p.lastOrNull() == ',',
            prevSemicolon = p.lastOrNull() == '；' || p.lastOrNull() == ';',
            nextStartsQuote = n.startsWith('“') || n.startsWith('「') || n.startsWith('『'),
            nextStartsDialogueLike = CUE_END.containsMatchIn(p),
            quoteDepthBefore = quoteDepthBefore,
            quoteDepthAfter = quoteDepthAfter,
            prevNearWrapWidth = mode > 0 && kotlin.math.abs(p.codePointCount(0, p.length) - mode) <= 2,
            nextNearWrapWidth = mode > 0 && kotlin.math.abs(n.codePointCount(0, n.length) - mode) <= 2,
            prevInSpecialGroup = prevInSpecialGroup,
            nextInSpecialGroup = nextInSpecialGroup,
        )
    }

    /** 分类（§19/§20/§21）。 */
    fun classify(f: Features, structuralBreak: Boolean = false): Decision {
        if (structuralBreak) {
            return Decision(LineBoundaryType.STRUCTURAL_BREAK, 1.0, listOf("STRUCTURAL_ANCHOR"), maskOf("STRUCTURAL_ANCHOR"))
        }
        // 特殊块组 → AUTHOR_LINE_BREAK（诗歌/剧本/聊天/列表 不 prose reflow，§30/§32/§33）
        if (f.nextInSpecialGroup) {
            return Decision(LineBoundaryType.AUTHOR_LINE_BREAK, 0.92, listOf("SPECIAL_BLOCK_GROUP"), maskOf("SPECIAL_BLOCK_GROUP"))
        }
        if (f.prevInSpecialGroup && !f.nextInSpecialGroup) {
            return Decision(LineBoundaryType.AUTHOR_LINE_BREAK, 0.92, listOf("SPECIAL_BLOCK_GROUP_EXIT"), maskOf("SPECIAL_BLOCK_GROUP_EXIT"))
        }
        if (f.prevBlank || f.nextBlank) {
            val conf = if (profile.profileType == TextLayoutProfile.BLANK_LINE_PARAGRAPH) 0.97 else 0.85
            return Decision(LineBoundaryType.HARD_PARAGRAPH, conf, listOf("BLANK_GAP"), maskOf("BLANK_GAP"))
        }

        var join = 0.0
        var brk = 0.0
        val ev = mutableListOf<String>()

        if (profile.profileType == TextLayoutProfile.FIXED_WIDTH_HARD_WRAP) { join += 2.0; ev += "FIXED_WIDTH_PROFILE" }
        if (f.prevNearWrapWidth) { join += 1.2; ev += "PREV_AT_WRAP_WIDTH" }
        if (!f.prevTerminal && !f.prevColon) { join += 1.4; ev += "PREV_NO_TERMINAL" }
        if (f.prevComma) { join += 1.0; ev += "PREV_ENDS_COMMA" }
        if (f.prevSemicolon) { join += 0.5; ev += "PREV_ENDS_SEMICOLON" }
        if (f.prevTerminal) { brk += 1.0; ev += "PREV_TERMINAL" }
        if (f.quoteDepthBefore > 0 || f.quoteDepthAfter > 0) { join += 0.8; ev += "QUOTE_CONTINUES" }

        if (f.nextIndent >= 2 && profile.leadingFullwidthIndentRatio in 0.3..0.95) { brk += 2.5; ev += "NEXT_STRONG_INDENT" }
        if (f.nextStartsQuote && f.nextIndent >= 2) { brk += 1.8; ev += "NEXT_QUOTE_INDENTED" }

        // Speech cue 两行（§38）：prev cue/冒号 + next 引号 → HARD + 保留 adjacency（不 JOIN）
        if (f.nextStartsQuote && (f.prevColon || f.nextStartsDialogueLike)) {
            return Decision(LineBoundaryType.HARD_PARAGRAPH, 0.95, ev + "SPEECH_CUE_KEEP", maskOf("SPEECH_CUE_KEEP"))
        }

        val diff = join - brk
        val confidence = when {
            kotlin.math.abs(diff) >= 3.0 -> 0.99
            kotlin.math.abs(diff) >= 2.0 -> 0.92
            kotlin.math.abs(diff) >= 1.0 -> 0.82
            else -> 0.6
        }
        val type = when {
            diff >= 0.5 -> LineBoundaryType.SOFT_WRAP
            diff <= -0.5 -> LineBoundaryType.HARD_PARAGRAPH
            else -> LineBoundaryType.UNCERTAIN
        }
        return Decision(type, confidence, ev, maskOf(ev))
    }

    private fun maskOf(vararg names: String): Long = maskOf(names.toList())
    private fun maskOf(names: List<String>): Long {
        var mask = 0L
        for ((i, _) in names.withIndex()) if (i < 63) mask = mask or (1L shl i)
        return mask
    }
}
