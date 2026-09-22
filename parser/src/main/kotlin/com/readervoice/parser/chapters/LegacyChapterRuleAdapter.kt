package com.readervoice.parser.chapters

import org.json.JSONArray
import java.io.InputStream

/**
 * LegacyChapterRuleAdapter（TASK-020 §6）：txtTocRule.json → ChapterRule[]。
 * 原 JSON 不可修改；规则族/target/风险来自 CHAPTER_RULE_AUDIT.md 的静态审计结论。
 */
object LegacyChapterRuleAdapter {

    /** 按审计结论的 family 映射（-100 空 regex 规则直接标注 EXTREME 且引擎 REJECTED）。 */
    private val FAMILY_BY_ID = mapOf(
        -1 to RuleFamily.STANDARD_ZH, -2 to RuleFamily.STANDARD_ZH, -3 to RuleFamily.SPECIAL_TITLE,
        -4 to RuleFamily.STANDARD_ZH, -5 to RuleFamily.PURE_NUMBER, -6 to RuleFamily.PURE_NUMBER,
        -7 to RuleFamily.PURE_NUMBER, -8 to RuleFamily.ARABIC_PREFIX, -9 to RuleFamily.ZH_CHAPTER_WORD,
        -10 to RuleFamily.ARABIC_PREFIX, -11 to RuleFamily.SPECIAL_TITLE, -12 to RuleFamily.ENGLISH_CHAPTER,
        -13 to RuleFamily.ENGLISH_CHAPTER, -14 to RuleFamily.SPECIAL_TITLE, -15 to RuleFamily.SPECIAL_TITLE,
        -16 to RuleFamily.SPECIAL_TITLE, -17 to RuleFamily.STANDARD_ZH, -18 to RuleFamily.SPECIAL_TITLE,
        -19 to RuleFamily.SPECIAL_TITLE, -20 to RuleFamily.SPECIAL_TITLE, -21 to RuleFamily.ARABIC_PREFIX,
        -22 to RuleFamily.ARABIC_PREFIX, -23 to RuleFamily.SPECIAL_TITLE, -24 to RuleFamily.SPECIAL_TITLE,
        -25 to RuleFamily.CUSTOM, -100 to RuleFamily.CUSTOM,
    )

    private val TARGET_BY_ID = mapOf(
        -11 to RuleTarget.PREFACE, -24 to RuleTarget.EXTRA,
        -100 to RuleTarget.UNKNOWN_STRUCTURAL,
    )

    private val RISK_BY_ID = mapOf(
        -5 to RegexRisk.HIGH, -6 to RegexRisk.HIGH, -7 to RegexRisk.HIGH,
        -18 to RegexRisk.HIGH, -25 to RegexRisk.HIGH, -100 to RegexRisk.EXTREME,
    )

    fun fromJson(input: InputStream): List<ChapterRule> {
        val text = input.readBytes().toString(Charsets.UTF_8)
        val arr = JSONArray(text)
        val out = ArrayList<ChapterRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getInt("id")
            val regex = o.optString("rule")
            val enabled = o.optBoolean("enable", false)
            // 空 regex（-100）→ 拒绝；危险规则即使 enabled 也降级为不参与自动确认
            val engine = if (regex.isBlank()) RegexEngine.REJECTED else RegexEngine.TRUSTED_LEGACY
            val risk = RISK_BY_ID[id] ?: RegexRisk.LOW
            val rule = ChapterRule(
                ruleId = "legacy-$id",
                legacyRuleId = id,
                name = o.optString("name"),
                regex = regex,
                family = FAMILY_BY_ID[id] ?: RuleFamily.CUSTOM,
                target = TARGET_BY_ID[id] ?: RuleTarget.CHAPTER,
                priority = o.optInt("serialNumber", 0),
                enabled = enabled,
                confidenceBase = when (risk) {
                    RegexRisk.EXTREME -> 0.2
                    RegexRisk.HIGH -> 0.4
                    RegexRisk.MEDIUM -> 0.6
                    RegexRisk.LOW -> 0.7
                },
                legacyReplacement = o.optStringOrNull("replacement"),
                legacySerialNumber = o.optInt("serialNumber"),
                positiveExamples = listOf(o.optString("example")).filter { it.isNotBlank() },
                regexEngine = engine,
                regexRisk = risk,
            )
            out += rule
        }
        return out
    }

    private fun org.json.JSONObject.optStringOrNull(k: String): String? =
        if (has(k) && !isNull(k)) optString(k) else null
}
