package com.readervoice.parser.chapters

/**
 * Regex 兼容性分析（TASK-020 §4）：检测 regex 使用的 feature，决定引擎选择。
 * RE2/J 不支持 lookaround/backreference/named group——含这些的规则必须走 audited Java Pattern。
 */
object RegexCompatibilityAnalyzer {

    data class Features(
        val lookbehind: Boolean,
        val lookahead: Boolean,
        val backreference: Boolean,
        val namedGroup: Boolean,
        val unicodeClass: Boolean,
        val alternation: Boolean,
        val anchors: Boolean,
    ) {
        val re2jCompatible: Boolean
            get() = !lookbehind && !lookahead && !backreference && !namedGroup
    }

    fun analyze(pattern: String): Features = Features(
        lookbehind = Regex("\\(\\?<=|<!" ).containsMatchIn(pattern),
        lookahead = Regex("\\(\\?(?==|!)").containsMatchIn(pattern),
        backreference = Regex("\\\\[1-9]").containsMatchIn(pattern),
        namedGroup = Regex("\\(\\?<[a-zA-Z]").containsMatchIn(pattern),
        unicodeClass = Regex("\\\\p\\{").containsMatchIn(pattern),
        alternation = pattern.contains('|'),
        anchors = pattern.startsWith("^") || pattern.contains('$'),
    )
}
