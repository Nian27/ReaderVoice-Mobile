package com.readervoice.parser.paragraph

import com.readervoice.parser.source.PhysicalLine
import java.lang.Character.UnicodeScript

/**
 * LayoutProfiler（TASK-030 §6-§14/§81-§83）：
 * Book/Chapter/Local 三层画像 + LayoutRegion 分段 + fixed-width 检测。
 * 必须先做 Profile 再判 Boundary（§6：单行无句号不足以判断硬换行）。
 */
class LayoutProfiler {

    /** 书级统计（§9）。 */
    data class BookLayoutProfile(
        val nonblankLineCount: Int,
        val medianLineLength: Double,
        val meanLineLength: Double,
        val stdLineLength: Double,
        val lengthHistogram: Map<Int, Int>,       // 长度桶（±2 归桶）
        val dominantModes: List<Int>,             // 集中度最高的长度 mode（§11）
        val blankLineRatio: Double,
        val leadingFullwidthIndentRatio: Double,
        val leadingAsciiIndentRatio: Double,
        val leadingTabRatio: Double,
        val terminalPunctuationRatio: Double,
        val colonEndRatio: Double,
        val quoteOpenRatio: Double,
        val quoteCrossLineRatio: Double,
        val separatorRatio: Double,
        val veryShortLineRatio: Double,           // <=4
        val veryLongLineRatio: Double,            // >1024
        val profileType: TextLayoutProfile,
        val confidence: Double,
    )

    data class ChapterLayoutProfile(val chapterId: Long?, val startLine: Int, val endLine: Int, val profile: BookLayoutProfile)

    data class LocalLayoutProfile(val startLine: Int, val endLine: Int, val profile: BookLayoutProfile)

    private val TERMINAL = setOf('。', '！', '？', '!', '?', '.', '…')
    private val SEPARATOR_RE = Regex("^[\\*\\-—＝=~☆★●○◆◇▁▂▃▄]{3,}\\s*$")

    fun profile(lines: List<PhysicalLine>): BookLayoutProfile {
        val nonblank = lines.filter { !it.isBlank }
        val n = nonblank.size
        if (n == 0) return emptyProfile()

        val lengths = nonblank.map { it.charCount }
        val mean = lengths.average()
        val sorted = lengths.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble()
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val variance = lengths.map { (it - mean) * (it - mean) }.average()
        val std = kotlin.math.sqrt(variance)

        // 长度直方图（±2 桶）
        val hist = sorted.groupingBy { it / 5 * 5 }.eachCount() // 5 字宽桶
        val dominant = hist.entries.sortedByDescending { it.value }.take(3).map { it.key }
        val mode = dominant.firstOrNull() ?: 0
        val nearMode = lengths.count { kotlin.math.abs(it - mode) <= 2 }.toDouble() / n

        val blanks = lines.count { it.isBlank }
        val blankRatio = blanks.toDouble() / lines.size
        val fwIndent = nonblank.count { it.leadingFullwidthSpace >= 2 }.toDouble() / n
        val asciiIndent = nonblank.count { it.leadingAsciiSpace >= 1 }.toDouble() / n
        val tabIndent = nonblank.count { it.leadingTab >= 1 }.toDouble() / n
        val terminal = nonblank.count { l -> l.rawText.lastOrNull()?.let { it in TERMINAL } == true }.toDouble() / n
        val colonEnd = nonblank.count { l -> l.rawText.lastOrNull() == '：' || l.rawText.lastOrNull() == ':' }.toDouble() / n
        val colonContain = nonblank.count { l -> l.rawText.contains('：') || l.rawText.contains(':') }.toDouble() / n
        val quoteOpen = nonblank.count { l -> l.rawText.count { it == '“' } > l.rawText.count { it == '”' } }.toDouble() / n
        // 引号跨行：行内未闭合
        var quoteState = 0
        var crossLine = 0
        for (l in nonblank) {
            val before = quoteState
            for (c in l.rawText) {
                if (c == '“') quoteState++
                else if (c == '”') quoteState = (quoteState - 1).coerceAtLeast(0)
            }
            if (before > 0) crossLine++
        }
        val quoteCrossRatio = crossLine.toDouble() / n
        val separatorRatio = nonblank.count { SEPARATOR_RE.matches(it.rawText.trim()) }.toDouble() / n
        val veryShort = lengths.count { it <= 6 }.toDouble() / n
        val veryLong = lengths.count { it > 1024 }.toDouble() / n

        val profile = classify(
            n, median, std, nearMode, mode, blankRatio, fwIndent, asciiIndent, tabIndent,
            terminal, colonEnd, colonContain, quoteCrossRatio, separatorRatio, veryShort, veryLong,
            uniqueLens = lengths.toSet().size,
        )
        val conf = profileConfidence(profile, nearMode, blankRatio, fwIndent, terminal)

        return BookLayoutProfile(
            nonblankLineCount = n, medianLineLength = median, meanLineLength = mean, stdLineLength = std,
            lengthHistogram = hist, dominantModes = dominant, blankLineRatio = blankRatio,
            leadingFullwidthIndentRatio = fwIndent, leadingAsciiIndentRatio = asciiIndent, leadingTabRatio = tabIndent,
            terminalPunctuationRatio = terminal, colonEndRatio = colonEnd, quoteOpenRatio = quoteOpen,
            quoteCrossLineRatio = quoteCrossRatio, separatorRatio = separatorRatio,
            veryShortLineRatio = veryShort, veryLongLineRatio = veryLong,
            profileType = profile, confidence = conf,
        )
    }

    /** 分类（§10/§12/§13/§14）。 */
    private fun classify(
        n: Int, median: Double, std: Double, nearMode: Double, mode: Int,
        blankRatio: Double, fwIndent: Double, asciiIndent: Double, tabIndent: Double,
        terminal: Double, colonEnd: Double, colonContain: Double, quoteCross: Double, separator: Double,
        veryShort: Double, veryLong: Double, uniqueLens: Int,
    ): TextLayoutProfile {
        if (separator > 0.05) return TextLayoutProfile.MIXED
        // 诗歌：短行密集 + 无终止 + 长度集中
        if (veryShort > 0.5 && terminal < 0.3 && median < 24 && std < 8) return TextLayoutProfile.POETRY_LIKE
        // 剧本：行内含冒号比例高 + 短行（"张三：你好。"冒号在行中）
        if (colonContain > 0.5 && median < 30) return TextLayoutProfile.SCRIPT_DIALOGUE
        // 固定宽度硬换行（§10）：行集中 + 到达 wrap 宽度 + 无句末标点 + 无缩进
        // （末行不满 wrap 宽度、段末句号落行尾是常态；阈值 Gold 校准）
        if (nearMode >= 0.45 && mode >= 8 && terminal < 0.55 && fwIndent < 0.1 && asciiIndent < 0.1) {
            return TextLayoutProfile.FIXED_WIDTH_HARD_WRAP
        }
        // 空行分段（§13）
        if (blankRatio >= 0.15) return TextLayoutProfile.BLANK_LINE_PARAGRAPH
        // 缩进分段（§14）：0.3-0.95 有区分力；>0.95 每行都缩进 → 降级
        if (fwIndent in 0.3..0.95 && terminal > 0.5) return TextLayoutProfile.INDENTED_PARAGRAPH
        // 一行一段（§12）：长度离散 + 终止完整 + 空行少（std 或长度多样性）
        if (terminal > 0.6 && blankRatio < 0.12 && (std > 2 || uniqueLens > 3)) return TextLayoutProfile.PARAGRAPH_PER_LINE
        if (veryLong > 0.1) return TextLayoutProfile.UNKNOWN
        return TextLayoutProfile.MIXED
    }

    private fun profileConfidence(
        p: TextLayoutProfile, nearMode: Double, blankRatio: Double, fwIndent: Double, terminal: Double,
    ): Double = when (p) {
        TextLayoutProfile.FIXED_WIDTH_HARD_WRAP -> (nearMode * 0.6 + (1 - terminal) * 0.4).coerceIn(0.5, 1.0)
        TextLayoutProfile.BLANK_LINE_PARAGRAPH -> blankRatio.coerceAtMost(1.0) * 0.8 + 0.2
        TextLayoutProfile.INDENTED_PARAGRAPH -> fwIndent.coerceAtMost(1.0) * 0.5 + terminal * 0.3 + 0.2
        TextLayoutProfile.PARAGRAPH_PER_LINE -> terminal * 0.6 + 0.3
        TextLayoutProfile.POETRY_LIKE -> 0.8
        TextLayoutProfile.SCRIPT_DIALOGUE -> 0.8
        else -> 0.5
    }

    /** Chapter 级画像（§83：Chapter 是天然 region 边界候选，但不假设每章同格式）。 */
    fun chapterProfiles(lines: List<PhysicalLine>, chapterStartLines: List<Int>): List<ChapterLayoutProfile> {
        if (chapterStartLines.isEmpty()) {
            val p = profile(lines)
            return listOf(ChapterLayoutProfile(null, 1, lines.size, p))
        }
        val bounds = chapterStartLines + (lines.size + 1)
        return chapterStartLines.mapIndexed { i, start ->
            val end = bounds[i + 1] - 1
            val slice = lines.filter { it.lineNo in start..end }
            ChapterLayoutProfile(i.toLong(), start, end, profile(slice))
        }
    }

    /** Region 分段（§81/§82）：chapter 内窗口（200 行）检测 profile 突变。 */
    fun regions(lines: List<PhysicalLine>, chapterStartLines: List<Int>, window: Int = 200): List<LayoutRegion> {
        val starts = chapterStartLines.toMutableList()
        if (starts.isEmpty()) starts += 1
        val regions = mutableListOf<LayoutRegion>()
        var regionId = 0
        for (i in starts.indices) {
            val segStart = starts[i]
            val segEnd = if (i + 1 < starts.size) starts[i + 1] - 1 else lines.size
            val seg = lines.filter { it.lineNo in segStart..segEnd }
            if (seg.isEmpty()) continue
            val global = profile(seg)
            // 滑动窗口找突变：窗口 profile 与全局类型不同 → 新 region
            var winStart = segStart
            var pos = segStart
            while (pos <= segEnd) {
                val winEnd = minOf(pos + window - 1, segEnd)
                val win = lines.filter { it.lineNo in winStart..winEnd }
                val wp = profile(win)
                if (wp.profileType != global.profileType && winEnd > winStart) {
                    regions += LayoutRegion(regionId++, winStart, winEnd, wp.profileType, wp.confidence)
                    winStart = winEnd + 1
                    pos = winEnd + 1
                } else {
                    pos = winEnd + 1
                    if (winEnd == segEnd && winStart <= winEnd) {
                        // 剩余归入一个 region
                        val rest = lines.filter { it.lineNo in winStart..segEnd }
                        val rp = profile(rest)
                        regions += LayoutRegion(regionId++, winStart, segEnd, rp.profileType, rp.confidence)
                        winStart = segEnd + 1
                    }
                }
            }
        }
        return regions
    }

    private fun emptyProfile() = BookLayoutProfile(
        0, 0.0, 0.0, 0.0, emptyMap(), emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, TextLayoutProfile.UNKNOWN, 0.0,
    )

    companion object {
        /** 行字符是否 HAN（JoinPolicy 与 profiler 共用）。 */
        fun isHan(cp: Int): Boolean = UnicodeScript.of(cp) == UnicodeScript.HAN
        fun isAsciiAlphaNum(cp: Int): Boolean = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code
    }
}
