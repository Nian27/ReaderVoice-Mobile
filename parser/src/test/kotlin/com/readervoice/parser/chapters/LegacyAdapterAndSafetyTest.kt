package com.readervoice.parser.chapters

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** TASK-020 fixtures（tests/fixtures/chapter/）。 */
object ChapterFixtures {
    fun dir(): Path {
        val candidates = listOf(
            Path.of("..", "tests", "fixtures", "chapter"),
            Path.of("tests", "fixtures", "chapter"),
            Path.of("..", "..", "tests", "fixtures", "chapter"),
        )
        return candidates.first { Files.isDirectory(it) }
    }

    fun legacyExamplesDir(): Path = dir().resolve("legacy_examples")
    fun file(name: String): Path = dir().resolve(name)

    fun legacyRules(): List<ChapterRule> =
        LegacyChapterRuleAdapter.fromJson(
            ChapterStructureCompiler::class.java.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
        )
}

class LegacyAdapterAndSafetyTest {

    @Test
    fun `26 rules loaded with audit mapping`() {
        val rules = ChapterFixtures.legacyRules()
        assertEquals(26, rules.size)
        // -100 空 regex → REJECTED
        val default = rules.first { it.legacyRuleId == -100 }
        assertEquals(RegexEngine.REJECTED, default.regexEngine)
        assertEquals(RegexRisk.EXTREME, default.regexRisk)
        // 危险规则标记
        assertEquals(RegexRisk.HIGH, rules.first { it.legacyRuleId == -5 }.regexRisk)
        assertEquals(RegexRisk.HIGH, rules.first { it.legacyRuleId == -25 }.regexRisk)
        // family 映射抽查
        assertEquals(RuleFamily.PURE_NUMBER, rules.first { it.legacyRuleId == -5 }.family)
        assertEquals(RuleFamily.STANDARD_ZH, rules.first { it.legacyRuleId == -17 }.family) // 审计修正：-17 是综合词表规则
        assertEquals(RuleFamily.ENGLISH_CHAPTER, rules.first { it.legacyRuleId == -12 }.family)
        // serialNumber → priority
        assertEquals(0, rules.first { it.legacyRuleId == -1 }.priority)
        assertEquals(99, rules.first { it.legacyRuleId == -100 }.priority)
    }

    @Test
    fun `enabled count is 12`() {
        assertEquals(12, ChapterFixtures.legacyRules().count { it.enabled })
    }

    @Test
    fun `regex compatibility analysis`() {
        val rules = ChapterFixtures.legacyRules()
        val lb = rules.count { RegexCompatibilityAnalyzer.analyze(it.regex).lookbehind }
        val la = rules.count { RegexCompatibilityAnalyzer.analyze(it.regex).lookahead }
        val re2j = rules.count { RegexCompatibilityAnalyzer.analyze(it.regex).re2jCompatible }
        assertEquals(12, lb) // 审计实测
        assertEquals(13, la)
        assertEquals(7, re2j) // 19/26 含 lookaround
        // 无 backref/named/unicode class
        assertTrue(rules.all { !RegexCompatibilityAnalyzer.analyze(it.regex).backreference })
    }

    @Test
    fun `regex safety rejects danger patterns`() {
        assertFalse(RegexSafetyAnalyzer.analyze("(a+)+").safe)
        assertFalse(RegexSafetyAnalyzer.analyze("(.+)+").safe)
        assertFalse(RegexSafetyAnalyzer.analyze("(.*)+").safe)
        assertFalse(RegexSafetyAnalyzer.analyze("").safe)      // -100 空 regex
        assertFalse(RegexSafetyAnalyzer.analyze("(ab|a)+").safe)
        assertFalse(RegexSafetyAnalyzer.analyze("(a").safe)    // 括号不配对
        // 安全规则通过
        assertTrue(RegexSafetyAnalyzer.analyze("^第[一二三]{1,3}章.{0,30}$").safe)
        assertTrue(RegexSafetyAnalyzer.analyze("^\\d{1,6}[、. ].{0,30}$").safe)
    }

    @Test
    fun `legacy rules all pass safety as trusted`() {
        // 26 条 legacy 静态审计结论：无嵌套量词（危险模式检测应通过——它们走 TRUSTED_LEGACY）
        for (r in ChapterFixtures.legacyRules()) {
            if (r.regex.isBlank()) continue
            assertTrue(RegexSafetyAnalyzer.analyze(r.regex).safe, "legacy rule ${r.ruleId} should be safe")
        }
    }
}
