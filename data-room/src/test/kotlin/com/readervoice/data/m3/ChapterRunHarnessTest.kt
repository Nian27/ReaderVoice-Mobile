package com.readervoice.data.m3

import com.readervoice.data.Db
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.data.semantic.ChapterDirectorRunner
import com.readervoice.data.semantic.DirectorSelectionPrompt
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.ScriptLineCodec
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.snapshot.BootstrapCharacterStore
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3 步骤 5+6：**真实书连续段落的整章运行（录制 / 设备执行 / 回放）**。
 *
 * 为什么用"录制→设备→回放"：设备上的 0.8B 只能通过 shell `llm_demo` 或 App JNI 调用，
 * 而 runner 需要一个同步 decider。于是：
 *   ① record：用 recording decider 跑 runner 一次，把 prompt 落盘（runner 是确定性的）
 *   ② device：shell 逐条跑 llm_demo（P15_REPEAT=3）
 *   ③ replay：用落盘的回答作 decider 再跑 runner ⇒ 产出 script_lines.jsonl + G3a/G3b 指标
 * 两阶段共用同一 runner 与同一输入 ⇒ 顺序与上下文完全一致（确定性由 ①=③ 的输出对比保证）。
 */
class ChapterRunHarnessTest {

    private val out = File("../runs/mobile_005_director_real/chapter_run")
    private val book = File("../娱乐：从1990年开始 作者：咖啡香草.txt")
    private val bookId = "book-ch1"
    private val rangeStart = 60
    private val rangeEnd = 160          // 连续 100 段（约一章的开头）
    private val maxModelCalls = 999     // 全量：把本章所有对白 prompt 都跑掉（覆盖率推到 ~100%）

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    private fun loadParagraphs(): List<com.readervoice.parser.paragraph.LogicalParagraph> {
        val bytes = book.readBytes()
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, bookId).lines
        ChapterStructureCompiler(legacyRules()).compile(lines, bookId)
        val all = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
            .paragraphs.map { it.paragraph }
        return all.subList(rangeStart, minOf(rangeEnd, all.size))
    }

    /** 全书扫描发现角色名（设备侧用 BootstrapCharacterStore 承载）。 */
    private fun discoverNames(): Set<String> {
        val bytes = book.readBytes()
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, bookId).lines
        val all = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
            .paragraphs.map { it.paragraph }
        val segmenter = SemanticSegmenter()
        fun pass(entities: Set<String>): LinkedHashSet<String> {
            val baseline = RuleSpeakerBaseline(entities)
            val found = LinkedHashSet<String>()
            val recent = ArrayDeque<String>(); var prevCue: String? = null
            for (p in all) {
                val segs = segmenter.segment(p); if (segs.isEmpty()) continue
                val res = baseline.assign(p, segs, recent.toList(), prevCue)
                for (s in segs) {
                    val r = res[s.segmentIndex] ?: continue
                    val sp = r.speaker ?: continue
                    if (r.status == "UNKNOWN") continue
                    val v = RuleSpeakerBaseline.candidateNameVariants(sp)
                    if (v.size == 1) found += sp else found += v.drop(1)
                    recent.remove(sp); recent.addFirst(sp)
                }
                while (recent.size > 16) recent.removeLast()
                prevCue = baseline.extractTrailingCue(p.normalizedText)
            }
            return found
        }
        val r1 = pass(emptySet())
        return pass(r1)
    }

    @Test
    fun `record stage writes prompts for the device`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(book.exists(), "real book 不在")
        val runDir = File(out, "prompts")
        runDir.mkdirs()
        runDir.listFiles()?.forEach { it.delete() }

        val names = discoverNames()
        val store = BootstrapCharacterStore(names.take(80).toSet())
        val runner = ChapterDirectorRunner(store, bookPk = 1L)
        val prompts = mutableListOf<String>()
        val index = JSONObject()

        val stats = runner.run(
            loadParagraphs(),
            decider = { prompt ->
                if (prompts.size < maxModelCalls) {
                    File(runDir, "%02d.txt".format(prompts.size)).writeText(prompt)
                    prompts += prompt
                }
                null                       // 录制阶段：不产出结论，只收 prompt
            },
        )
        index.put("book", book.name).put("range", "$rangeStart..$rangeEnd")
            .put("names", names.size).put("prompts", prompts.size)
            .put("segments", stats.segments).put("shortcut", stats.decidedByShortcut)
            .put("modelCalls", stats.modelCalls)
        File(out, "plan.json").writeText(index.toString())
        println("[CH] names=${names.size} segments=${stats.segments} shortcut=${stats.decidedByShortcut} " +
            "modelCalls=${stats.modelCalls} prompts=${prompts.size}")
        assertTrue(prompts.isNotEmpty(), "应产出至少一个 prompt")
    }

    @Test
    fun `replay stage produces script lines and gate metrics`() {
        val runDir = File(out, "prompts")
        org.junit.jupiter.api.Assumptions.assumeTrue(runDir.exists() && runDir.listFiles()!!.isNotEmpty(), "先跑 record")
        val answers = HashMap<String, String>()
        runDir.listFiles()!!.sortedBy { it.name }.forEachIndexed { i, f ->
            val src = File(out, "out_%02d.txt".format(i))
            if (!src.exists()) return@forEachIndexed
            val first = src.readLines().map { it.trim() }.firstOrNull { it.startsWith("{") } ?: return@forEachIndexed
            answers[f.readText()] = first
        }

        val names = discoverNames()
        val store = BootstrapCharacterStore(names.take(80).toSet())
        val runner = ChapterDirectorRunner(store, bookPk = 1L)
        val jsonl = File(out, "script_lines.jsonl")
        jsonl.writeText("")
        var miss = 0
        val stats = runner.run(
            loadParagraphs(),
            decider = { prompt ->
                val a = answers[prompt]
                if (a == null) miss++
                a
            },
            onLine = { jsonl.appendText(ScriptLineCodec.encode(it) + "\n") },
        )
        val report = buildString {
            appendLine("=== M3 真实书整章运行（连续 100 段，录制/回放）===")
            appendLine(stats.pretty())
            appendLine("replay hit=${answers.size} miss=$miss（miss 计入 NO_OUTPUT ⇒ fail-closed）")
        }
        File(out, "chapter_report.txt").writeText(report)
        println(report)
        assertTrue(jsonl.readLines().isNotEmpty(), "应产出至少一行 ScriptLine")
    }
}


