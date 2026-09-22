package com.readervoice.parser.source

/**
 * PhysicalLine（TASK-010 §16）。
 *
 * Offset 口径（详见 docs/protocols/SOURCE_OFFSET_CONVENTION.md）：
 * - byteStart/byteEnd：原始文件内绝对字节区间 [start, end)，含 BOM 字节之前的部分不属于任何行；
 *   行文本字节区间不含换行字节。
 * - charStart/charEnd：解码字符流内 Unicode code point 区间 [start, end)，BOM 不占字符位。
 * - utf16（Kotlin String index）不存储，需要时用派生函数计算。
 */
data class PhysicalLine(
    val lineId: Long,
    val bookId: String,
    val lineNo: Int,            // 1-based

    val byteStart: Int,         // 行文本首字节（不含 BOM/换行）
    val byteEnd: Int,           // 行文本末字节后（不含换行）
    val charStart: Int,         // code point 区间起点
    val charEnd: Int,           // code point 区间终点

    val rawText: String,

    val leadingAsciiSpace: Int,
    val leadingFullwidthSpace: Int,
    val leadingTab: Int,
    val trailingSpace: Int,     // 行尾 ASCII 空格数（U+3000 不算 trailing，见 leading）

    val charCount: Int,         // code point 数（不含换行）
    val hanCount: Int,

    val isBlank: Boolean,
    val newlineKind: NewlineKind,
) {
    enum class NewlineKind { LF, CRLF, CR, NONE }
}
