package com.readervoice.parser.paragraph

/**
 * QuoteStack（TASK-030 §15/§16）：
 * 支持 “” 「」 『』 "" ''；维护 quote_type/depth；测试嵌套、跨行、多行闭合。
 * 结构层只输出 QUOTE_SPAN 事实，不判定 DIALOGUE（§16）。
 */
class QuoteStack {

    data class State(val depth: Int, val typeStack: List<Char>)

    private val pairs = mapOf('“' to '”', '「' to '」', '『' to '』', '"' to '"', '\'' to '\'')

    /** 扫描一行，返回 (depth_before, depth_after)。 */
    fun scanLine(text: String, state: State = State(0, emptyList())): Pair<State, State> {
        var depth = state.depth
        val stack = state.typeStack.toMutableList()
        val before = State(depth, stack.toList())
        for (c in text) {
            val open = pairs.keys.firstOrNull { it == c }
            if (open != null) {
                // 引号对同字符（" '）：已打开同类型则闭合，否则打开
                val close = pairs.getValue(open)
                if (stack.isNotEmpty() && stack.last() == open && open == close) {
                    stack.removeAt(stack.size - 1)
                    depth--
                } else if (stack.isNotEmpty() && stack.last() == open) {
                    stack.removeAt(stack.size - 1)
                    depth--
                } else if (open == close && stack.isNotEmpty() && stack.last() != open) {
                    // 不同类型下同字符引号：按打开处理（保守）
                    stack += open
                    depth++
                } else {
                    stack += open
                    depth++
                }
            } else if (c == '”' || c == '」' || c == '』') {
                // 闭合引号：按栈顶类型匹配（跨类型时按最近打开闭合，宽松）
                val last = stack.lastOrNull()
                if (last != null) {
                    stack.removeAt(stack.size - 1)
                    depth = (depth - 1).coerceAtLeast(0)
                }
            }
        }
        return before to State(depth, stack)
    }

    /** 行内引号是否未闭合（跨行引语）。 */
    fun isOpen(text: String, state: State = State(0, emptyList())): Boolean = scanLine(text, state).second.depth > 0
}
