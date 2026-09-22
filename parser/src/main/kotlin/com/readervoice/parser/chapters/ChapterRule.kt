package com.readervoice.parser.chapters

/** 规则族（v5 §10 / TASK-020 §8）。 */
enum class RuleFamily { STANDARD_ZH, ZH_CHAPTER_WORD, ARABIC_PREFIX, HASH_NUMBER, PURE_NUMBER, SPECIAL_TITLE, VOLUME, ENGLISH_CHAPTER, CUSTOM }

/** 目标类型（TASK-020 §9）。 */
enum class RuleTarget { CHAPTER, VOLUME, PREFACE, AFTERWORD, EXTRA, INTERLUDE, TOC_ENTRY, UNKNOWN_STRUCTURAL }

/** 正则引擎与风险标记（ADR-017）。 */
enum class RegexEngine { TRUSTED_LEGACY, AUDITED_JAVA_PATTERN, REJECTED }
enum class RegexRisk { LOW, MEDIUM, HIGH, EXTREME }

/**
 * ChapterRule（TASK-020 §6）。
 * @param serialParser 该规则的序号提取方式（内部正则或 null = 用匹配串直接喂 ChineseNumeralParser）
 */
data class ChapterRule(
    val ruleId: String,
    val legacyRuleId: Int?,
    val name: String,
    val regex: String,
    val family: RuleFamily,
    val target: RuleTarget,
    val priority: Int,          // legacy serialNumber（排序键）
    val enabled: Boolean,
    val scope: RuleScope = RuleScope.GLOBAL,
    val confidenceBase: Double = 0.6,
    val serialParser: Regex? = null,
    val titleParser: Regex? = null,
    val legacyReplacement: String? = null,
    val legacySerialNumber: Int? = null,
    val positiveExamples: List<String> = emptyList(),
    val negativeExamples: List<String> = emptyList(),
    val regexEngine: RegexEngine = RegexEngine.AUDITED_JAVA_PATTERN,
    val regexRisk: RegexRisk = RegexRisk.LOW,
    val userLocked: Boolean = false,
)

enum class RuleScope { GLOBAL, BOOK, SERIES }
