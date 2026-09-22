package com.readervoice.parser.source

import java.lang.Character.UnicodeScript

/**
 * PhysicalLineScanner（TASK-010 §14/§15/§17）。
 *
 * - 流式单遍扫描：不构造 N 份全文 String copy（大文件友好）。
 * - 支持 LF / CRLF / CR，混合换行安全。
 * - EOF 无换行：最后一行仍是有效 PhysicalLine（物理行数 = 解码逻辑行记录数，不是 wc -l）。
 * - BOM 属于源文件字节，但不属于第一行正文字符（byteStart 从 BOM 之后开始）。
 * - 行特征（leading/trailing/blank）在行结束时对行文本做单次遍历计算，避免扫描期状态机错误。
 */
class PhysicalLineScanner {

    /** 扫描结果：行列表 + （可选）每字符 byte 偏移表。 */
    data class ScanResult(
        val lines: List<PhysicalLine>,
        /** 第 i 个 code point 的起始字节位置（i 为全局字符序号，不含 BOM/换行）；collectCharOffsets=false 时为 null */
        val charByteOffsets: IntArray?,
    )

    /**
     * @param bytes 原始文件字节（不可变，只读）
     * @param charset 已检测/用户指定的编码
     * @param bomBytes 源文件前 N 字节为 BOM（跳过，不计入任何行文本）
     * @param bookId 归属书（lineId 由调用方后续分配）
     * @param collectCharOffsets 收集每个 code point 的起始字节偏移（精确 char→byte 映射；大文件 +12MB/3M 字符）
     */
    fun scan(
        bytes: ByteArray,
        charset: CharsetKind,
        bomBytes: Int,
        bookId: String,
        collectCharOffsets: Boolean = false,
    ): ScanResult {
        val decoder = when (charset) {
            CharsetKind.UTF_8, CharsetKind.UTF_8_BOM -> Utf8Decoder
            CharsetKind.UTF_16LE -> Utf16Decoder(bigEndian = false)
            CharsetKind.UTF_16BE -> Utf16Decoder(bigEndian = true)
            CharsetKind.GBK, CharsetKind.GB18030 -> Gb18030Decoder()
        }

        val lines = ArrayList<PhysicalLine>()
        val charOffsets = if (collectCharOffsets) ArrayList<Int>() else null
        var pos = bomBytes
        val end = bytes.size
        var lineByteStart = pos
        var totalChars = 0 // 已处理行文本 code point 总数（换行不占位）
        var charCount = 0
        var hanCount = 0
        val sb = StringBuilder()
        var lineNo = 0

        fun emit(kind: PhysicalLine.NewlineKind, textByteEnd: Int) {
            lineNo++
            val text = sb.toString()
            // 特征单次遍历
            var i = 0
            var ascii = 0
            var fw = 0
            var tab = 0
            while (i < text.length) {
                val c = text[i]
                if (c == ' ') ascii++
                else if (c == '\u3000') fw++
                else if (c == '\t') tab++
                else break
                i++
            }
            var trail = 0
            var j = text.length - 1
            while (j >= 0 && text[j] == ' ') { trail++; j-- }
            val charStart = totalChars
            lines += PhysicalLine(
                lineId = 0L,
                bookId = bookId,
                lineNo = lineNo,
                byteStart = lineByteStart,
                byteEnd = textByteEnd,
                charStart = charStart,
                charEnd = charStart + charCount,
                rawText = text,
                leadingAsciiSpace = ascii,
                leadingFullwidthSpace = fw,
                leadingTab = tab,
                trailingSpace = trail,
                charCount = charCount,
                hanCount = hanCount,
                isBlank = text.isBlank(),
                newlineKind = kind,
            )
            totalChars += charCount
            sb.setLength(0)
            charCount = 0
            hanCount = 0
        }

        while (pos < end) {
            val (cp, next) = decoder.decodeNext(bytes, pos, end)
            when (cp) {
                '\n'.code -> {
                    emit(PhysicalLine.NewlineKind.LF, textByteEnd = pos)
                    lineByteStart = next
                    pos = next
                }
                '\r'.code -> {
                    if (next < end && bytes[next].toInt() == '\n'.code) {
                        // CRLF：换行 span 两字节；消费 \n 防止二次处理产生幽灵行
                        emit(PhysicalLine.NewlineKind.CRLF, textByteEnd = pos)
                        lineByteStart = next + 1
                        pos = next + 1
                    } else {
                        emit(PhysicalLine.NewlineKind.CR, textByteEnd = pos)
                        lineByteStart = next
                        pos = next
                    }
                }
                else -> {
                    charOffsets?.add(pos) // 精确：该字符起始字节位置
                    sb.appendCodePoint(cp)
                    charCount++
                    if (UnicodeScript.of(cp) == UnicodeScript.HAN) hanCount++
                    pos = next
                }
            }
        }
        // EOF：残余字符构成最后一行（即使无换行）；空文件产生 0 行
        if (sb.isNotEmpty()) {
            emit(PhysicalLine.NewlineKind.NONE, textByteEnd = end)
        }
        return ScanResult(lines, charOffsets?.toIntArray())
    }
}
