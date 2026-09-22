package com.readervoice.parser.chapters

import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-020 §46：Legacy Raw Regex Baseline vs New Candidate+Resolver。
 * Legacy baseline 语义复刻（TextFile.getTocRule：按匹配数竞争选规则，标题=匹配原文）。
 */
class LegacyBaselineTest {

    private fun linesOf(text: String) = run {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
    }

    /** Legacy baseline：每 enabled 规则在全文跑，选匹配数最多的规则（getTocRule 语义），标题=匹配行原文。 */
    private fun legacyBaseline(text: String): Pair<String, Int> {
        val rules = ChapterFixtures.legacyRules().filter { it.enabled && it.regex.isNotBlank() }
        var bestRule = ""
        var bestCount = 0
        for (rule in rules) {
            val re = Regex(rule.regex, RegexOption.MULTILINE)
            val matches = re.findAll(text).count()
            if (matches > bestCount) { bestCount = matches; bestRule = rule.name }
        }
        return bestRule to bestCount
    }

    @Test
    fun `new resolver does not reduce chapter recall on gold book`() {
        val text = java.nio.file.Files.readString(ChapterFixtures.file("gold_structured_book.txt"))
        val (_, legacyCount) = legacyBaseline(text)

        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val r = ChapterStructureCompiler(ChapterFixtures.legacyRules()).compile(lines, "b")
        val newCount = r.revision.chapters.count { it.state == ResolveState.CONFIRMED || it.state == ResolveState.PROVISIONAL }

        println("E2: legacy raw match count=$legacyCount vs new resolver confirmed=$newCount")
        // G2：新 Resolver 不得低于 Legacy baseline 的正文 Chapter 数（gold 真值 12：10 章 + 番外 + 尾声）
        assertTrue(newCount >= 12, "new resolver must keep gold chapters: $newCount")
    }

    @Test
    fun `new resolver reduces false positives on number lines`() {
        // G3：纯数字/日期陷阱行——legacy raw 会把"2026/520/10086/二零二六年"当候选，new resolver 必须拒绝
        val trap = listOf("2026", "520", "10086", "1920", "二零二六年", "10:30", "2026-08-12")
        val text = trap.joinToString("\n") + "\n" + "第一章 真实章节\n正文。\n" + "第二章 真实章节二\n正文。\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val r = ChapterStructureCompiler(ChapterFixtures.legacyRules()).compile(lines, "b")
        val rejected = r.revision.chapters.filter { it.state == ResolveState.REJECTED }.map { it.titleRaw.trim() }
        // 陷阱行不得成为 CONFIRMED/PROVISIONAL
        val accepted = r.revision.chapters.filter { it.state != ResolveState.REJECTED && it.state != ResolveState.TOC_ENTRY }
        for (t in trap) {
            assertTrue(accepted.none { it.titleRaw.trim() == t }, "trap '$t' must not be a chapter")
        }
        // 真实章节确认
        assertTrue(accepted.any { it.titleRaw.contains("真实章节") })
    }
}
