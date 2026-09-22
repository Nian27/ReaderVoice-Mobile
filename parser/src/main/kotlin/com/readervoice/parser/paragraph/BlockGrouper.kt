package com.readervoice.parser.paragraph

import com.readervoice.parser.source.PhysicalLine

/**
 * BlockGrouper：组级特殊块检测（TASK-030 §28-§33/§30）。
 * 单行分类不足以区分"cue 行"与"剧本"——组特征：
 * - SCRIPT：连续 ≥2 行匹配 名字：内容 模式（单行冒号行 = speech cue，不判剧本）
 * - MESSAGE_LOG：连续 ≥2 行时间戳模式
 * - LIST：连续 ≥2 行列表项
 * - POETRY：连续 ≥3 行短行、无句末标点、长度接近
 * 输出：行号 → BlockType（组内行继承块类型）。
 */
class BlockGrouper {

    private val SCRIPT_RE = Regex("^[^，。！？；：]{1,10}[：:]\\s*[^\\n]{0,40}$")
    private val MESSAGE_RE = Regex("^.{1,16}\\s+\\d{1,2}:\\d{2}\\s*$")
    private val LIST_RE = Regex("^\\s*\\d{1,3}[.、)）]\\s*\\S+.*$")
    private val TERMINAL = setOf('。', '！', '？', '!', '?', '.', '…')

    fun group(lines: List<PhysicalLine>, chapterAnchors: Set<Int>, volumeAnchors: Set<Int>, tocLines: Set<Int>): Map<Int, BlockType> {
        val out = mutableMapOf<Int, BlockType>()
        val det = SpecialBlockDetector()
        val nonblank = lines.filter { !it.isBlank }

        // 1. 单行确定类型
        for (line in lines) {
            val c = det.classify(line, chapterAnchors, volumeAnchors, tocLines)
            when (c.blockType) {
                BlockType.SEPARATOR, BlockType.BOILERPLATE, BlockType.LYRICS, BlockType.SCREEN_TEXT,
                BlockType.CHAPTER_TITLE, BlockType.VOLUME_TITLE, BlockType.TOC_ENTRY, BlockType.LETTER ->
                    out[line.lineNo] = c.blockType
                else -> Unit
            }
        }

        // 2. SCRIPT / MESSAGE / LIST 连续组（≥2）
        var i = 0
        while (i < nonblank.size) {
            val kind = when {
                MESSAGE_RE.matches(nonblank[i].rawText.trim()) -> BlockType.MESSAGE_LOG // 时间戳优先于 SCRIPT（"张三 20:31" 含冒号）
                SCRIPT_RE.matches(nonblank[i].rawText.trim()) -> BlockType.SCRIPT
                LIST_RE.matches(nonblank[i].rawText.trim()) -> BlockType.LIST
                else -> null
            }
            if (kind != null) {
                var j = i
                while (j + 1 < nonblank.size) {
                    val nextKind = when (kind) {
                        BlockType.SCRIPT -> SCRIPT_RE.matches(nonblank[j + 1].rawText.trim())
                        BlockType.MESSAGE_LOG -> MESSAGE_RE.matches(nonblank[j + 1].rawText.trim())
                        BlockType.LIST -> LIST_RE.matches(nonblank[j + 1].rawText.trim())
                        else -> false
                    }
                    if (!nextKind) break
                    j++
                }
                val count = j - i + 1
                // MESSAGE 时间行单行也标记（聊天内容行不得被 prose 合并，§33）；SCRIPT/LIST 需 ≥2（cue 行非剧本）
                val minCount = if (kind == BlockType.MESSAGE_LOG) 1 else 2
                if (count >= minCount) {
                    for (k in i..j) out[nonblank[k].lineNo] = kind
                }
                i = j + 1
            } else {
                i++
            }
        }

        // 3. POETRY 连续组（≥3 短行、无句末标点、长度接近；含引号的行不是诗歌——引语行排除）
        var p = 0
        while (p < nonblank.size) {
            val line = nonblank[p]
            val short = line.charCount in 4..22
            val noTerminal = line.rawText.lastOrNull()?.let { it !in TERMINAL } ?: false
            val noQuote = !line.rawText.contains('“') && !line.rawText.contains('”')
            if (short && noTerminal && noQuote) {
                var q = p
                while (q + 1 < nonblank.size) {
                    val nl = nonblank[q + 1]
                    val ok = nl.charCount in 4..22 &&
                        (nl.rawText.lastOrNull()?.let { it !in TERMINAL } ?: false) &&
                        !nl.rawText.contains('“') && !nl.rawText.contains('”') &&
                        kotlin.math.abs(nl.charCount - line.charCount) <= 6
                    if (!ok) break
                    q++
                }
                val count = q - p + 1
                if (count >= 3) {
                    for (k in p..q) {
                        if (!out.containsKey(nonblank[k].lineNo)) out[nonblank[k].lineNo] = BlockType.POETRY
                    }
                }
                p = q + 1
            } else {
                p++
            }
        }
        return out
    }

    private companion object {
        val TERMINAL = setOf('。', '！', '？', '!', '?', '.', '…', '”', '」', '』', '"', '\'')
    }
}
