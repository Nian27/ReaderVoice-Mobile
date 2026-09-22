package com.readervoice.parser.paragraph

/**
 * JoinPolicy（TASK-030 §22-§27）：
 * 物理行合并为段落文本时的连接规则。
 * - 中文 HAN+HAN：不加空格（"张三看着"+"李雪。"→"张三看着李雪。"）
 * - 英文 ASCII+ASCII：加空格（"This is a long"+"sentence."→"...long sentence."）
 * - 混合：HAN+ASCII 双向都加空格（"使用 Transformer"、"Python 语言"）
 * - 前行尾已有 trailing 空格：不重复加
 */
object JoinPolicy {

    /** 返回连接两行文本所需的分隔符（"" 或 " "）。 */
    fun joinSeparator(prevLine: String, nextLine: String): String {
        if (prevLine.endsWith(' ') || prevLine.endsWith('\t')) return ""
        val prev = prevLine.codePoints().toArray()
        val next = nextLine.codePoints().toArray()
        if (prev.isEmpty() || next.isEmpty()) return ""
        val lastCp = prev.last()
        val firstCp = next.first()
        return when {
            LayoutProfiler.isHan(lastCp) && LayoutProfiler.isHan(firstCp) -> ""
            LayoutProfiler.isAsciiAlphaNum(lastCp) && LayoutProfiler.isAsciiAlphaNum(firstCp) -> " "
            LayoutProfiler.isHan(lastCp) && LayoutProfiler.isAsciiAlphaNum(firstCp) -> " "
            LayoutProfiler.isAsciiAlphaNum(lastCp) && LayoutProfiler.isHan(firstCp) -> " "
            else -> ""
        }
    }
}
