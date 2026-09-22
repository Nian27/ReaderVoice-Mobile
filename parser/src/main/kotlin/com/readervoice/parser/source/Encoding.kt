package com.readervoice.parser.source

/**
 * TASK-010 编码支持范围（v5 §7 / TASK-010 §8）。
 *
 * 注意：GBK 是 GB18030 的子集场景之一。检测器只有在字节流中出现
 * GB18030 特有的 4 字节序列时才声称 GB18030，否则声称 GBK——
 * 避免"decoder 命名造成错误声称"。
 */
enum class CharsetKind(val label: String, val javaCharset: String) {
    UTF_8("UTF-8", "UTF-8"),
    UTF_8_BOM("UTF-8 BOM", "UTF-8"),
    GBK("GBK", "GBK"),
    GB18030("GB18030", "GB18030"),
    UTF_16LE("UTF-16LE", "UTF-16LE"),
    UTF_16BE("UTF-16BE", "UTF-16BE"),
}

/** 编码检测结果（TASK-010 §10）。 */
data class EncodingResult(
    val charset: CharsetKind,
    val confidence: Double,          // 0..1
    val detectionMethod: String,     // BOM / strict-utf8 / heuristic / scoring / user-override
    val bomBytes: Int = 0,           // 源文件前 N 字节属于 BOM（不属于第一行正文字符）
    val userLocked: Boolean = false,
    val userOverrideCharset: CharsetKind? = null,
    val alternatives: List<CharsetKind> = emptyList(), // 低置信时的候选项
)

/** 解码错误（严格模式，首个错误位置）。 */
class DecodeException(val bytePos: Int, val reason: String) :
    Exception("decode error at byte $bytePos: $reason")

/** 严格逐字符解码器：解码一个字符，返回 (codepoint, 下一个字节位置)。 */
interface CharDecoder {
    val name: String
    fun decodeNext(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int>
}

/** 严格 UTF-8（拒绝 overlong / surrogate / >U+10FFFF / 截断）。 */
object Utf8Decoder : CharDecoder {
    override val name = "UTF-8"
    override fun decodeNext(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        val b0 = bytes[start].toInt() and 0xFF
        if (b0 < 0x80) return b0 to (start + 1)
        val (n, cp0) = when (b0) {
            in 0xC2..0xDF -> 1 to (b0 and 0x1F)
            in 0xE0..0xEF -> 2 to (b0 and 0x0F)
            in 0xF0..0xF4 -> 3 to (b0 and 0x07)
            else -> throw DecodeException(start, "invalid UTF-8 lead byte 0x%02X".format(b0))
        }
        if (start + n >= end) throw DecodeException(start, "truncated UTF-8 sequence")
        var cp = cp0
        for (i in 1..n) {
            val b = bytes[start + i].toInt() and 0xFF
            if (b !in 0x80..0xBF) throw DecodeException(start + i, "invalid continuation byte 0x%02X".format(b))
            cp = (cp shl 6) or (b and 0x3F)
        }
        // 严格性：overlong / surrogate / 超出范围
        val min = when (n) {
            1 -> 0x80; 2 -> 0x800; else -> 0x10000
        }
        if (cp < min) throw DecodeException(start, "overlong UTF-8 sequence")
        if (cp in 0xD800..0xDFFF) throw DecodeException(start, "UTF-8 must not encode surrogates")
        if (cp > 0x10FFFF) throw DecodeException(start, "UTF-8 codepoint out of range")
        return cp to (start + n + 1)
    }
}

/** 严格 UTF-16（LE/BE）。surrogate pair 合成为一个 codepoint；孤立 surrogate 报错。 */
class Utf16Decoder(private val bigEndian: Boolean) : CharDecoder {
    override val name = if (bigEndian) "UTF-16BE" else "UTF-16LE"

    override fun decodeNext(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        if (start + 2 > end) throw DecodeException(start, "truncated UTF-16 unit")
        val u0 = unit(bytes, start)
        if (u0 in 0xD800..0xDBFF) { // high surrogate → 必须跟 low
            if (start + 4 > end) throw DecodeException(start, "truncated surrogate pair")
            val u1 = unit(bytes, start + 2)
            if (u1 !in 0xDC00..0xDFFF) throw DecodeException(start + 2, "isolated high surrogate")
            return (0x10000 + ((u0 - 0xD800) shl 10) + (u1 - 0xDC00)) to (start + 4)
        }
        if (u0 in 0xDC00..0xDFFF) throw DecodeException(start, "isolated low surrogate")
        return u0 to (start + 2)
    }

    private fun unit(bytes: ByteArray, pos: Int): Int {
        val a = bytes[pos].toInt() and 0xFF
        val b = bytes[pos + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else a or (b shl 8)
    }
}

/**
 * 严格 GB18030/GBK。序列边界（1/2/4 字节）由本类判定以保字节偏移精度；
 * 内容映射交给 JDK Charset（GBK 是 GB18030 子集，单序列解码不会产生偏移歧义）。
 */
class Gb18030Decoder : CharDecoder {
    override val name = "GB18030"
    private val dec = java.nio.charset.Charset.forName("GB18030").newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

    override fun decodeNext(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        val b0 = bytes[start].toInt() and 0xFF
        if (b0 < 0x80) return b0 to (start + 1) // ASCII
        if (b0 !in 0x81..0xFE) throw DecodeException(start, "invalid GB18030 lead byte 0x%02X".format(b0))
        if (start + 1 >= end) throw DecodeException(start, "truncated GB18030 sequence")
        val b1 = bytes[start + 1].toInt() and 0xFF
        val len = if (b1 in 0x40..0xFE && b1 != 0x7F) {
            2
        } else if (b1 in 0x30..0x39) {
            4
        } else {
            throw DecodeException(start + 1, "invalid GB18030 trail byte 0x%02X".format(b1))
        }
        if (start + len > end) throw DecodeException(start, "truncated GB18030 sequence")
        if (len == 4) {
            val b2 = bytes[start + 2].toInt() and 0xFF
            val b3 = bytes[start + 3].toInt() and 0xFF
            if (b2 !in 0x81..0xFE || b3 !in 0x30..0x39)
                throw DecodeException(start + 2, "invalid GB18030 4-byte tail")
        }
        val cb = dec.decode(java.nio.ByteBuffer.wrap(bytes, start, len))
        return Character.codePointAt(cb, 0) to (start + len)
    }
}
