package com.readervoice.parser.paragraph

/**
 * CrossParagraphLinker（TASK-030 §37-§40）：
 * - SPEECH_CUE：prev 段以 cue 词/冒号结尾 + next 段以引号开头（§38：保留两段 + link，Speaker Resolver 不丢 cue）
 * - QUOTE_CONTINUATION：引号跨段（§39）
 * - CONTINUATION：段间无空行且文本连续（§40 仅结构证据，不做 Scene 推理）
 */
class CrossParagraphLinker {

    private val CUE_RE = Regex("(说道|说|问|答|喊|叫|嚷|喝道|道|低声道|笑道|哭道|叹道|问道|答道)[：:]?$")

    fun link(paragraphs: List<LogicalParagraphBuilder.BuiltParagraph>): List<CrossParagraphLink> {
        val links = mutableListOf<CrossParagraphLink>()
        var linkId = 1L
        for (i in 1 until paragraphs.size) {
            val prev = paragraphs[i - 1].paragraph
            val next = paragraphs[i].paragraph
            val prevText = prev.normalizedText.trim()
            val nextText = next.normalizedText.trim()

            val type: LinkType
            val conf: Double
            val evidence: List<String>
            when {
                CUE_RE.containsMatchIn(prevText) && (nextText.startsWith('“') || nextText.startsWith('「') || nextText.startsWith('『')) -> {
                    type = LinkType.SPEECH_CUE; conf = 0.98; evidence = listOf("SPEECH_CUE_END+QUOTE_START")
                }
                prevText.count { it == '“' } > prevText.count { it == '”' } ||
                    nextText.count { it == '”' } > nextText.count { it == '“' } -> {
                    type = LinkType.QUOTE_CONTINUATION; conf = 0.9; evidence = listOf("QUOTE_SPAN")
                }
                else -> {
                    type = LinkType.CONTINUATION; conf = 0.6; evidence = listOf("ADJACENT_PARAGRAPHS")
                }
            }
            links += CrossParagraphLink(linkId++, prev.paragraphId, next.paragraphId, type, conf, evidence)
        }
        return links
    }
}
