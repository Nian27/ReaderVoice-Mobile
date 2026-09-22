package com.readervoice.data.snapshot

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MOBILE-005 / M2：**回放路径的自校验**（不依赖设备）。
 *
 * 设备端没有 JDBC，它用 TraceCharacterStore 回放桌面记录的只读调用。本测试在桌面走**同一条回放路径**：
 *   trace.json → TraceCharacterStore → M2ParityRunner → 与 desktop_canonical.txt 逐行比对
 * 它能证明两件事：
 *   ① trace 覆盖完备（缺任何 key，回放 store 会 fail-closed 抛异常）
 *   ② 回放路径与真实 JDBC 路径得到**逐字相同**的语义结论
 * 设备端跑同一函数只是换运行时；若本测试通过而设备端不同，问题必在 Android 侧而非语义层。
 */
class TraceReplayParityTest {

    private val dir = File("../runs/mobile_005_director_real/m2_semantic_parity")
    private val bookId = "book-4455b46ef2d2"

    @Test
    fun `replay trace reproduces desktop semantic outputs`() {
        val input = File(dir, "input.txt")
        val trace = File(dir, "trace.json")
        val expected = File(dir, "desktop_canonical.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            input.exists() && trace.exists() && expected.exists(),
            "M2 artifacts not present (run M2SemanticParityTest first)"
        )

        val bytes = input.readBytes()
        val encoding = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
        val rules = javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
            .use(LegacyChapterRuleAdapter::fromJson)
        ChapterStructureCompiler(rules).compile(lines, bookId)
        val paragraphs = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
            .paragraphs.map { it.paragraph }

        val store = TraceCharacterStore(trace.readText())   // 缺 key 会抛异常（fail-closed）
        val got = mutableListOf<String>()
        val n = M2ParityRunner.run(paragraphs, 1L, store) { line ->
            got += M2ParityRunner.canonicalLine(JSONObject(line))
        }

        val want = expected.readText().trim().split("\n")
        println("[M2-replay] traceKeys=${JSONObject(trace.readText()).length()} targets=$n expectedLines=${want.size}")
        assertEquals(want.size, got.size, "target count differs")
        assertTrue(want.isNotEmpty())
        var firstDiff = -1
        for (i in want.indices) {
            if (want[i] != got[i]) { firstDiff = i; break }
        }
        if (firstDiff >= 0) {
            println("[M2-replay] first diff at line $firstDiff")
            println("  expected: ${want[firstDiff]}")
            println("  replay  : ${got[firstDiff]}")
        }
        assertEquals(-1, firstDiff, "replay output differs from desktop JDBC output")
        println("[M2-replay] PASS —— 回放路径与 JDBC 路径逐字一致（$n 条）")
    }
}
