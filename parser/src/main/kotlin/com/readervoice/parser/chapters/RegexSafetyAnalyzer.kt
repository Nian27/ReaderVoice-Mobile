package com.readervoice.parser.chapters

/**
 * Regex 安全分析（TASK-020 §5.2 / REGEX_SAFETY_POLICY.md）。
 * 拦截明显 ReDoS 模式与空/过宽 regex，不尝试证明无回溯（不可判定）。
 */
object RegexSafetyAnalyzer {

    data class Verdict(val safe: Boolean, val reasons: List<String>)

    fun analyze(pattern: String): Verdict {
        val reasons = mutableListOf<String>()

        if (pattern.isBlank()) reasons += "EMPTY_REGEX" // -100 教训

        // 括号配对粗检
        var depth = 0
        var escaped = false
        for (c in pattern) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '(' -> depth++
                c == ')' -> depth--
                else -> Unit
            }
        }
        if (depth != 0) reasons += "UNBALANCED_PAREN"

        // 嵌套量词：(?:X+)+ / (X+)+ / (?:ab|c)+ 等——组内以量词结尾且组外紧跟量词
        val nestedQuant = Regex("\\(\\?:[^()]*[+*?]\\s*\\)\\s*[+*?]|" + "\\([^()?][^()]*[+*?]\\s*\\)\\s*[+*?]")
        if (nestedQuant.containsMatchIn(pattern)) reasons += "NESTED_QUANTIFIER"

        // 歧义重复交替：(?:ab|a)+ / (ab|a)+
        val altQuant = Regex("\\(\\?:[^()]*\\|[^()]*\\)\\s*[+*]|" + "\\([^()?][^()]*\\|[^()]*\\)\\s*[+*]")
        if (altQuant.containsMatchIn(pattern)) reasons += "AMBIGUOUS_ALTERNATION"

        return Verdict(reasons.isEmpty(), reasons)
    }
}
