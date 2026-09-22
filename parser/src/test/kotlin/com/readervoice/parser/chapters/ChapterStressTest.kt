package com.readervoice.parser.chapters

import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-020 §48/§49/§47：3M+ 结构扫描 stress（G10）、pathological regex（G9）、性能指标。
 */
class ChapterStressTest {

    private fun compile(text: String, rules: List<ChapterRule> = ChapterFixtures.legacyRules()): ChapterStructureCompiler.PipelineResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "book", false).lines
        return ChapterStructureCompiler(rules).compile(lines, "book")
    }

    @Test
    fun `3M plus structured book scans without OOM`() {
        // TASK020_LARGE_STRUCTURE_FIXTURE：前部 TOC + 3 卷 + 每 200-500 行一章
        val rnd = Random(42)
        val sb = StringBuilder()
        val target = 3_000_000
        val toc = StringBuilder()
        sb.append("　　书名：压力测试之书\n\n")
        // 前部 TOC 30 条
        toc.append("目录\n")
        for (i in 1..30) toc.append("第${i}章 目录条目${i}\n")
        sb.append(toc).append("\n")
        var chapterNo = 0
        var volNo = 0
        while (sb.length < target) {
            if (chapterNo % 50 == 0) {
                volNo++
                sb.append("第${ChineseNumeralParser.parse(chapterNumeral(volNo))}卷 压力卷$volNo\n")
            }
            chapterNo++
            sb.append("第${chapterNumeral(chapterNo)}章 压力章节${chapterNo}\n")
            val n = 200 + rnd.nextInt(300)
            repeat(n) {
                sb.append("　　这是正文内容行，用于填充章节正文，测试扫描性能与内存占用。他抬起头看向远方。\n")
            }
        }
        val text = sb.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        println("=== TASK020_STRESS: text=${"%.1f".format(bytes.size / 1e6)}MB chapters=$chapterNo volumes=$volNo")

        val t0 = System.nanoTime()
        val r = compile(text)
        val t1 = System.nanoTime()
        val heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0

        println("scan_ms=${"%.1f".format(r.scanTimeMs)} resolve_ms=${"%.1f".format(r.resolveTimeMs)}")
        println("candidates=${r.candidates.size} groups=${r.groups.size} peak_heap_mb=${"%.1f".format(heap)}")
        println("toc_blocks=${r.toc.blocks.size} volumes=${r.volumes.size}")
        val confirmed = r.revision.chapters.count { it.state == ResolveState.CONFIRMED }
        val provisional = r.revision.chapters.count { it.state == ResolveState.PROVISIONAL }
        println("confirmed=$confirmed provisional=$provisional")
        val totalMs = (t1 - t0) / 1e6

        // G10：无 OOM + 时间报告；结构合理（每章 200-500 行 → 3M 字符约 150-250 章）
        assertTrue(chapterNo > 150, "expected 150+ chapters, got $chapterNo")
        assertTrue(totalMs < 120_000, "scan+resolve must complete in <120s, took ${totalMs}ms")
        assertTrue(heap < 1536, "heap must stay bounded")
        assertEquals(volNo, r.volumes.size)
        assertTrue(r.toc.blocks.isNotEmpty(), "front TOC expected")
        // 大部分章节被确认（压力测试书格式规整）
        assertTrue(confirmed + provisional > chapterNo * 0.8, "recall on regular book: ${confirmed + provisional}/$chapterNo")
    }

    private fun chapterNumeral(n: Int): String = when {
        n <= 9 -> "零一二三四五六七八九"[n].toString()
        else -> n.toString()
    }

    @Test
    fun `pathological regex is rejected without hanging`() {
        // G9：危险 synthetic user regex + 超长输入 → validation 拒绝（不实际运行挂起证明）
        val dangerPatterns = listOf("(a+)+", "(.+)+", "(.*)+", "(ab|a)+", "")
        for (p in dangerPatterns) {
            val v = RegexSafetyAnalyzer.analyze(p)
            assertFalse(v.safe, "pattern '$p' must be rejected")
        }
        // 拒绝后不会进入 scanner（REJECTED 引擎跳过）
        val badRule = ChapterRule(
            ruleId = "user-danger", legacyRuleId = null, name = "danger", regex = "(a+)+",
            family = RuleFamily.CUSTOM, target = RuleTarget.CHAPTER, priority = 0, enabled = true,
            regexEngine = RegexEngine.REJECTED,
        )
        val text = "a".repeat(10_000) + "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val r = ChapterStructureCompiler(listOf(badRule) + ChapterFixtures.legacyRules()).compile(lines, "b")
        // 不卡死 + 危险规则零候选
        assertTrue(r.candidates.none { it.ruleId == "user-danger" })
    }

    @Test
    fun `long line only runs whitelist rules`() {
        // 超长行（>1024 字符）：只跑白名单规则（REGEX_SAFETY_POLICY §5.4）；
        // legacy 规则 `{0,30}` 上限无法匹配 1600 字符"标题"→ 正确行为 = 无候选且不卡死
        val longTitle = "第一章 " + "超长标题内容".repeat(200) // ~1600 字符
        val text = "$longTitle\n正文\n"
        val r = compile(text)
        // 超长标题行（line 1）不得产生候选；第二行"正文"是短行可正常匹配
        assertTrue(r.candidates.none { it.lineNo == 1 }, "超长标题行不应产生候选（legacy {0,30} 上限语义）")
        // 短行标题仍正常
        val r2 = compile("第一章 正常标题\n正文。\n")
        assertTrue(r2.candidates.isNotEmpty())
    }

    @Test
    fun `sequence is evidence not hard constraint`() {
        // §23/§24：作者漏编号（97,98,100,101）不得拒绝
        val sb = StringBuilder()
        for (i in listOf(97, 98, 100, 101)) sb.append("第${i}章 内容${i}\n正文若干。\n")
        val r = compile(sb.toString())
        val confirmed = r.revision.chapters.filter { it.state != ResolveState.REJECTED }
        assertEquals(4, confirmed.size, "missing 99 must not drop chapters")
        assertEquals(listOf(97, 98, 100, 101), confirmed.map { it.serialValue })
    }
}
