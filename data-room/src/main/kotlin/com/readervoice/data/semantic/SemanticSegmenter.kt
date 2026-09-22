package com.readervoice.data.semantic

import com.readervoice.parser.paragraph.LogicalParagraph

/**
 * SemanticSegmenter（TASK-070 §1）：LogicalParagraph → SemanticSegment[]。
 * 纯规则：quote stack + speech cue + 标点/结构（无 LLM）。
 * - 引号（“”「」『』）内 → SPEECH / INNER_MONOLOGUE（引号前"心想/暗道"等 cue 启发）
 * - 引号外叙述 → NARRATION
 * - 多段共段："张明推开门，说："你来了。"李雪抬头："嗯。"" → S1..S4（手册 §29）
 * - "旁白 + 对白 + 旁白" 100% 正确切分（G2）
 * segment 文本 = 原文精确切片（含引号，source-exact，G1 round-trip 无损）；
 * segmentId 确定性生成：paragraphId * 1000 + segmentIndex。
 */
class SemanticSegmenter {

    // 内心独白 cue：心想/暗道/寻思/琢磨/盘算/嘀咕/自言自语 等（"说："/"说道：" 是对白，不是内心）
    private val INNER_RE = Regex("(?:心里|心中|暗自|不由|默默)?(?:想|念|嘀咕|盘算|寻思|琢磨|自言自语)(?:道|着|：|:)?$")

    /** 段落 → 段列表；sourceStart/sourceEnd 基于段落 normalizedText（round-trip 基准）。 */
    fun segment(paragraph: LogicalParagraph): List<SemanticSegment> {
        val text = paragraph.normalizedText
        val n = text.length
        val segments = mutableListOf<SemanticSegment>()
        var cursor = 0              // 上一个已收段的结束位置
        var lastSpeechEnd = 0       // 上一句对白结束位置（引号前 cue 的搜索起点）
        var i = 0
        val quoteStarts = ArrayDeque<Int>()

        while (i < n) {
            val c = text[i]
            when (c) {
                '“', '「', '『' -> {
                    // 引号外有未切文本 → 先切为 NARRATION 段
                    if (quoteStarts.isEmpty() && i > cursor) {
                        segments += cut(paragraph, segments.size, text, cursor, i, SegmentType.NARRATION)
                        cursor = i
                    }
                    quoteStarts.addLast(i)
                    i++
                }
                '”', '」', '』' -> {
                    if (quoteStarts.isNotEmpty()) {
                        val start = quoteStarts.removeLast()
                        // 仅最外层引号闭合时收段（内层嵌套不打断）
                        if (quoteStarts.isEmpty()) {
                            val cueBefore = text.substring(lastSpeechEnd, start) // 引号前的叙述部分
                            val type = if (INNER_RE.containsMatchIn(cueBefore.trim())) SegmentType.INNER_MONOLOGUE
                            else SegmentType.SPEECH
                            segments += cut(paragraph, segments.size, text, start, i + 1, type)
                            cursor = i + 1
                            lastSpeechEnd = i + 1
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
        // 残余（引号外未切文本；空引号等情况兜底）
        if (cursor < n && text.substring(cursor).isNotBlank()) {
            segments += cut(paragraph, segments.size, text, cursor, n, SegmentType.NARRATION)
        }
        return segments
    }

    private fun cut(p: LogicalParagraph, index: Int, text: String, start: Int, end: Int, type: SegmentType): SemanticSegment =
        SemanticSegment(
            segmentId = p.paragraphId * 1000 + index,
            paragraphRevisionId = p.paragraphId,
            segmentIndex = index,
            type = type,
            text = text.substring(start, end),
            sourceStart = start,
            sourceEnd = end,
            quoteDepth = 0,
        )

    /** 段落是否以 speech cue 结尾（CrossParagraphLink SPEECH_CUE / G4 配合）。 */
    fun endsWithSpeechCue(paragraph: LogicalParagraph): Boolean =
        CUE_SUFFIX.containsMatchIn(paragraph.normalizedText.trim())

    private val CUE_SUFFIX = Regex("(?:说道|说|问|答|喊|叫|嚷|喝道|道|低声道|笑道|哭道|叹道|问道|答道|厉声道|沉声道|道：|说：)[”」』]?$")
}
