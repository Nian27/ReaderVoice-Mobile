package com.readervoice.parser.chapters

import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLine
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CH-0 / M0（P0）：**真实书章节边界 Gate**。
 *
 * 为什么优先做它：下游一切（段落 → 语义片段 → 角色上下文 → 批次进度 → 剧本归档）
 * 都依赖 `Chapter.contentStartLine/contentEndLine`；边界错了，后面全错。
 *
 * 本 Gate **不重写 parser**，只用真实书体检，并把证据落盘：
 *   · 抽样第 1/50/100/249 章（不足则取能取到的最大序号）
 *   · 每章 dump 7 个字段：title / anchorByteStart / contentStartLine / contentEndLine /
 *     首 100 字 / 尾 100 字 / 下一章首 100 字
 *   · 断言 4 条不变式：相邻不重叠、正文不留空洞、章节顺序单调、anchorByte 单调
 *
 * 证据文件：`../runs/mobile_005_director_real/ch0_chapter_gate/report.md`
 */
class ChapterBoundaryGateTest {

    private val sampleOrdinals = listOf(0, 49, 99, 248)

    /** 真实书候选：仓库顶层 + books_private（取前 3 本，控制耗时）。 */
    private fun candidateBooks(): List<File> {
        val root = File("..")
        val plain = root.listFiles { f -> f.isFile && f.extension == "txt" }?.sortedBy { it.name }.orEmpty()
        val privateDir = File(root, "books_private")
        val priv = privateDir.listFiles { f -> f.isFile && f.extension == "txt" }?.sortedBy { it.name }.orEmpty()
        // 优先选体积大的（章节多，才能覆盖 1/50/100/249 抽样）
        return (priv.sortedByDescending { it.length() } + plain.sortedByDescending { it.length() }).take(3)
    }

    private data class Probe(
        val ordinal: Int,
        val title: String,
        val anchorByteStart: Int,
        val contentStartLine: Int,
        val contentEndLine: Int,
        val head100: String,
        val tail100: String,
        val nextHead100: String,
        val state: String,
    )

    private fun probeOf(chapters: List<Chapter>, allLines: List<PhysicalLine>, ordinal: Int): Probe? {
        val ch = chapters.getOrNull(ordinal) ?: return null
        val end = ch.contentEndLine ?: allLines.size
        val body = allLines.filter { it.lineNo >= ch.contentStartLine && it.lineNo <= end }.map { it.rawText }
        val text = body.joinToString("\n")
        val next = chapters.getOrNull(ordinal + 1)
        val nextEnd = next?.let { it.contentEndLine ?: allLines.size } ?: 0
        val nextText = next?.let { n ->
            allLines.filter { it.lineNo >= n.contentStartLine && it.lineNo <= nextEnd }
                .joinToString("\n") { it.rawText }
        } ?: ""
        return Probe(
            ordinal = ordinal,
            title = ch.titleDisplay,
            anchorByteStart = ch.anchorByteStart,
            contentStartLine = ch.contentStartLine,
            contentEndLine = end,
            head100 = text.take(100).replace("\n", "⏎"),
            tail100 = text.takeLast(100).replace("\n", "⏎"),
            nextHead100 = nextText.take(100).replace("\n", "⏎"),
            state = ch.state.name,
        )
    }

    /** 4 条不变式；返回违规描述列表。 */
    private fun violations(chapters: List<Chapter>, lines: List<PhysicalLine>): List<String> {
        val out = mutableListOf<String>()
        // lines 按 lineNo 连续递增（1-based）⇒ 用下标 O(1)，绝不再线性扫（否则 1600 章 × 50 万行会卡死）
        fun blank(n: Int): Boolean {
            if (n < 1 || n > lines.size) return true
            val idx = n - 1
            return lines[idx].lineNo == n && lines[idx].rawText.isBlank()
        }

        chapters.forEachIndexed { i, ch ->
            val end = ch.contentEndLine ?: lines.size
            if (ch.contentStartLine < ch.anchorLine) {
                out += "第 ${i + 1} 章 contentStartLine(${ch.contentStartLine}) < anchorLine(${ch.anchorLine})"
            }
            if (end < ch.contentStartLine - 1) {
                out += "第 ${i + 1} 章 contentEndLine($end) < contentStartLine-1(${ch.contentStartLine - 1})"
            }
            if (end > lines.size) out += "第 ${i + 1} 章 contentEndLine($end) > 总行数(${lines.size})"
            if (i > 0) {
                val prev = chapters[i - 1]
                val prevEnd = prev.contentEndLine ?: lines.size
                // ① 不重叠
                if (ch.contentStartLine <= prevEnd) {
                    out += "重叠：第 $i 章 end=$prevEnd ≥ 第 ${i + 1} 章 start=${ch.contentStartLine}"
                }
                // ② 正文不留空洞（允许跳过空行）
                var nonBlank = 0
                var firstNb = 0
                var lastNb = 0
                for (n in (prevEnd + 1)..(ch.contentStartLine - 1)) {
                    if (n >= 1 && n <= lines.size && !blank(n)) {
                        nonBlank++
                        if (firstNb == 0) firstNb = n
                        lastNb = n
                    }
                }
                if (nonBlank > 0) {
                    out += "空洞：第 $i→${i + 1} 章之间跳过 $nonBlank 行非空正文（行 $firstNb..$lastNb）"
                }
                // ③ 章节顺序单调
                if (ch.chapterIndex != prev.chapterIndex + 1) {
                    out += "顺序：chapterIndex ${prev.chapterIndex} → ${ch.chapterIndex} 不是 +1"
                }
                // ④ anchorByte 单调
                if (ch.anchorByteStart <= prev.anchorByteStart) {
                    out += "anchorByte 非单调：第 $i 章 ${prev.anchorByteStart} ≥ 第 ${i + 1} 章 ${ch.anchorByteStart}"
                }
            }
        }
        return out
    }

    @Test
    fun `real book chapter boundaries hold the four invariants`() {
        val books = candidateBooks()
        assertTrue(books.isNotEmpty(), "找不到真实书（仓库顶层或 books_private/*.txt）")

        val rules = File("../parser/src/main/resources/legacy/txtTocRule.json").takeIf { it.isFile }
            ?.inputStream()?.use(LegacyChapterRuleAdapter::fromJson)
            ?: error("规则包不存在：parser/src/main/resources/legacy/txtTocRule.json")

        val report = StringBuilder()
        report.appendLine("# CH-0 / M0 — 真实书章节边界 Gate")
        report.appendLine()
        report.appendLine("规则包：`parser/src/main/resources/legacy/txtTocRule.json`（${rules.size} 条，启用 ${rules.count { it.enabled }}）")
        report.appendLine()

        var totalViolations = 0
        var probed = 0

        books.forEach { book ->
            val bytes = book.readBytes()
            val enc = EncodingDetector.detect(bytes)
            val lines = PhysicalLineScanner().scan(bytes, enc.charset, enc.bomBytes, "gate").lines
            val revision = ChapterStructureCompiler(rules).compile(lines, "gate").revision

            // ★ M0.1 + M0.2：产品口径 = 已确认章节视图（重算连续 ordinal 与正文区间）
            val view = ConfirmedChapterView.of(revision, eofLine = lines.size)
            val viewBad = ConfirmedChapterView.violations(view, lines.size)
            totalViolations += viewBad.size

            // 对照：原始候选集合（只用于诊断，产品不得消费）
            val rawBad = violations(revision.chapters, lines)

            report.appendLine("## ${book.name}")
            report.appendLine()
            report.appendLine("- 大小 ${bytes.size / 1024} KB　行数 ${lines.size}")
            report.appendLine("- **rawCandidates（诊断用）：${revision.chapters.size}**，其中 CONFIRMED=${revision.chapters.count { it.state == ResolveState.CONFIRMED }}、PROVISIONAL=${revision.chapters.count { it.state == ResolveState.PROVISIONAL }}、REJECTED=${revision.chapters.count { it.state == ResolveState.REJECTED }}、TOC_ENTRY=${revision.chapters.count { it.state == ResolveState.TOC_ENTRY }}")
            report.appendLine("- **ConfirmedChapterView（产品口径）：${view.size} 章**")
            report.appendLine("- rawCandidates 不变式违规：**${rawBad.size}**（对照，未修）")
            val viewMark = if (viewBad.isEmpty()) "OK" else "FAIL"
            report.appendLine("- confirmedView 不变式违规：**${viewBad.size}** [$viewMark]")
            viewBad.take(8).forEach { v -> report.appendLine("  - $v") }
            report.appendLine()
            if (view.isEmpty()) {
                report.appendLine("（无确认章节，跳过抽样）")
                report.appendLine()
                return@forEach
            }

            report.appendLine("### 抽样（第 1/50/100/249 章，不足则取最大可用序号）")
            report.appendLine()
            sampleOrdinals.forEach { want ->
                val ord = if (want < view.size) want else view.size - 1
                val c = view[ord]
                probed++
                val body = lines.filter { it.lineNo >= c.contentStartLine && it.lineNo <= c.contentEndLine }
                    .joinToString("\n") { it.rawText }
                val nextStart = view.getOrNull(ord + 1)?.contentStartLine
                val nextHead = if (nextStart != null) {
                    lines.filter { it.lineNo >= nextStart && it.lineNo < nextStart + 12 }
                        .joinToString("\n") { it.rawText }.take(100)
                } else ""
                report.appendLine("**第 ${c.ordinal + 1} 章**　state=${c.state}　chapterId=${c.chapterId}")
                report.appendLine("- title: `${c.title}`")
                report.appendLine("- anchorByteStart: ${c.anchorByteStart}　contentStartLine: ${c.contentStartLine}　contentEndLine: ${c.contentEndLine}（${c.lineCount} 行）")
                report.appendLine("- 首100字: ${body.take(100).replace("\n", "⏎")}")
                report.appendLine("- 尾100字: ${body.takeLast(100).replace("\n", "⏎")}")
                report.appendLine("- 下一章首100字: ${nextHead.replace("\n", "⏎")}")
                report.appendLine()
            }
        }

        report.appendLine("## 汇总")
        report.appendLine()
        report.appendLine("- 受检书籍：${books.size} 本　抽样：$probed 章")
        report.appendLine("- **ConfirmedChapterView 违规合计：$totalViolations**（产品口径）")
        report.appendLine("- 不变式：① 相邻 Chapter 不重叠　② 区间不倒置/不超 EOF　③ ordinal 连续　④ anchorByteStart 单调")

        val out = File("../runs/mobile_005_director_real/ch0_chapter_gate")
        out.mkdirs()
        File(out, "report.md").writeText(report.toString(), Charsets.UTF_8)
        println(report)
        println("[CH0-GATE] books=${books.size} probed=$probed violations=$totalViolations → ${File(out, "report.md").absolutePath}")

        assertEqualsZero(totalViolations, books)
    }

    private fun assertEqualsZero(total: Int, books: List<File>) {
        assertTrue(
            total == 0,
            "章节边界不变式违规 $total 条（见 report.md）。按 PLAN-20260918-060：只定位规则并记录，不重写 parser。书：${books.map { it.name }}",
        )
    }
}
