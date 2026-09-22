package com.readervoice.parser.paragraph

import com.readervoice.parser.source.PhysicalLine

/**
 * SpecialBlockDetector（TASK-030 §28-§36/§50）。
 * 识别 PROSE/POETRY/LYRICS/SCRIPT/MESSAGE_LOG/LIST/LETTER/SCREEN_TEXT/SEPARATOR/BOILERPLATE/
 * CHAPTER_TITLE/VOLUME_TITLE/TOC_ENTRY/UNKNOWN。
 * 保守原则：BOILERPLATE 需要整行模式/位置/重复证据，不允许"请记住我"因含"请记住"被删（§36）。
 */
class SpecialBlockDetector {

    data class LineClass(val blockType: BlockType, val skipRead: Boolean, val evidence: List<String>)

    private val SEPARATOR_RE = Regex("^[\\*\\-—＝=~☆★●○◆◇▁▂▃▄]{3,}\\s*$")
    private val BOILERPLATE_RE = listOf(
        Regex("^.*(?:最新网址|请收藏本站|收藏本站|请记住域名|本章未完|手机用户请访问|最快更新|天才一秒记住|笔趣阁|顶点小说).*$"),
        Regex("^https?://\\S+$"),
        Regex("^www\\..+\\.[a-z]{2,}$"),
    )
    private val LIST_RE = Regex("^\\s*\\d{1,3}[.、)）]\\s*\\S+.*$")
    private val MESSAGE_RE = Regex("^.{1,16}\\s+\\d{1,2}:\\d{2}\\s*$")
    private val SCRIPT_RE = Regex("^[^，。！？；：]{1,10}[：:]\\s*[^\\n]{0,40}$")
    private val LYRICS_RE = Regex("^\\[.*\\]\\s*$")
    private val SCREEN_RE = Regex("^【.{1,20}】\\s*$")

    /** 单行分类（结构性；不判谁在说话）。 */
    fun classify(line: PhysicalLine, chapterAnchors: Set<Int>, volumeAnchors: Set<Int>, tocLines: Set<Int>): LineClass {
        val text = line.rawText
        val trimmed = text.trim()
        if (line.lineNo in tocLines) return LineClass(BlockType.TOC_ENTRY, true, listOf("IN_TOC_BLOCK"))
        if (line.lineNo in volumeAnchors) return LineClass(BlockType.VOLUME_TITLE, false, listOf("VOLUME_ANCHOR"))
        if (line.lineNo in chapterAnchors) return LineClass(BlockType.CHAPTER_TITLE, false, listOf("CHAPTER_ANCHOR"))
        if (trimmed.isEmpty()) return LineClass(BlockType.UNKNOWN, true, listOf("BLANK"))
        if (SEPARATOR_RE.matches(trimmed)) return LineClass(BlockType.SEPARATOR, true, listOf("SEPARATOR_RE"))
        if (BOILERPLATE_RE.any { it.matches(trimmed) }) return LineClass(BlockType.BOILERPLATE, true, listOf("BOILERPLATE_PATTERN"))
        if (LYRICS_RE.matches(trimmed)) return LineClass(BlockType.LYRICS, false, listOf("LYRICS_MARKER"))
        if (SCREEN_RE.matches(trimmed)) return LineClass(BlockType.SCREEN_TEXT, false, listOf("SCREEN_TEXT"))
        if (MESSAGE_RE.matches(trimmed)) return LineClass(BlockType.MESSAGE_LOG, false, listOf("TIME_STAMP"))
        if (LIST_RE.matches(trimmed)) return LineClass(BlockType.LIST, false, listOf("LIST_ITEM"))
        if (SCRIPT_RE.matches(trimmed)) return LineClass(BlockType.SCRIPT, false, listOf("SPEAKER_COLON"))
        if (trimmed.startsWith("亲爱的") && trimmed.contains("：")) return LineClass(BlockType.LETTER, false, listOf("LETTER_SALUTATION"))
        return LineClass(BlockType.PROSE, false, emptyList())
    }

    /** 行组分类：诗歌（连续短行、无终止、长度接近）。终止符集与 classifier/grouper 统一。 */
    fun poetryBlock(lines: List<PhysicalLine>, window: Int = 4): Boolean {
        if (lines.size < 3) return false
        val cand = lines.take(window).filter { !it.isBlank }
        if (cand.size < 3) return false
        val lens = cand.map { it.charCount }
        val noTerminal = cand.all { l ->
            val last = l.rawText.lastOrNull()
            last == null || last !in TERMINAL
        }
        val short = lens.all { it in 4..22 }
        val close = lens.maxOrNull()!! - lens.minOrNull()!! <= 6
        return noTerminal && short && close
    }

    companion object {
        val TERMINAL = setOf('。', '！', '？', '!', '?', '.', '…', '”', '」', '』', '"', '\'')
    }
}
