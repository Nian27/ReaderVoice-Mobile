package com.readervoice.parser.source

/**
 * SourceMap（TASK-010 §22）：由 PhysicalLine 索引构建的 byte/char → 行 双向映射。
 *
 * 口径：byte 与 char 均为 0-based；char 为 Unicode code point 序号（不含 BOM，换行不占位）。
 * round-trip 保证：任何 (byteStart,byteEnd) 或 (charStart,charEnd) 都能回到原行原文。
 *
 * @param charByteOffsets 来自 PhysicalLineScanner 的精确 char→byte 表（可选；null 时 charToByte 不可用）
 */
class SourceMap(
    private val lines: List<PhysicalLine>,
    private val charByteOffsets: IntArray? = null,
) {

    data class LineRef(val lineNo: Int, val offsetInLine: Int, val line: PhysicalLine)

    /** char offset（code point 序号）→ 行引用；越界抛 IllegalArgumentException。 */
    fun lineAtCharOffset(cp: Int): LineRef {
        require(cp >= 0) { "char offset must be >= 0" }
        // 二分：找最后一条 charStart <= cp 的行
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].charStart <= cp) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        val line = lines[ans]
        require(cp <= line.charEnd) { "char offset $cp out of range (max ${lines.lastOrNull()?.charEnd})" }
        return LineRef(line.lineNo, cp - line.charStart, line)
    }

    /** byte offset → 行引用；越界抛 IllegalArgumentException。 */
    fun lineAtByteOffset(byte: Int): LineRef {
        require(byte >= 0) { "byte offset must be >= 0" }
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].byteStart <= byte) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        val line = lines[ans]
        require(byte <= line.byteEnd) { "byte offset $byte out of range" }
        return LineRef(line.lineNo, byte - line.byteStart, line)
    }

    /** 行文本的字节区间（与 PhysicalLine.byteStart/byteEnd 一致）。 */
    fun byteRange(lineNo: Int): Pair<Int, Int> {
        val l = lines[lineNo - 1]
        return l.byteStart to l.byteEnd
    }

    /** 行文本的字符区间（code point）。 */
    fun charRange(lineNo: Int): Pair<Int, Int> {
        val l = lines[lineNo - 1]
        return l.charStart to l.charEnd
    }

    /** 从原始字节解码出行文本（round-trip 验证用，与存储的 rawText 必须一致）。 */
    fun decodeLineText(bytes: ByteArray, lineNo: Int, charset: CharsetKind): String {
        val (bs, be) = byteRange(lineNo)
        val dec = when (charset) {
            CharsetKind.UTF_8, CharsetKind.UTF_8_BOM -> Utf8Decoder
            CharsetKind.UTF_16LE -> Utf16Decoder(false)
            CharsetKind.UTF_16BE -> Utf16Decoder(true)
            CharsetKind.GBK, CharsetKind.GB18030 -> Gb18030Decoder()
        }
        val sb = StringBuilder()
        var pos = bs
        while (pos < be) {
            val (cp, next) = dec.decodeNext(bytes, pos, be)
            sb.appendCodePoint(cp)
            pos = next
        }
        return sb.toString()
    }

    val lineCount: Int get() = lines.size

    /**
     * 精确 char→byte：第 [cp] 个 code point 的起始字节位置。
     * 需要 scanner 的 collectCharOffsets=true 产物；否则抛 IllegalStateException。
     */
    fun charToByte(cp: Int): Int {
        val offs = charByteOffsets ?: throw IllegalStateException("charByteOffsets not collected (scan with collectCharOffsets=true)")
        require(cp in 0 until offs.size) { "char offset $cp out of range (size ${offs.size})" }
        return offs[cp]
    }
}
