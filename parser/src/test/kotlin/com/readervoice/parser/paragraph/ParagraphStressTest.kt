package com.readervoice.parser.paragraph

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TASK-030 §68/§69/§70：3M+ 字符段落恢复 stress（G10）+ 内存观察。
 */
class ParagraphStressTest {

    @Test
    fun `3M plus paragraph recovery without OOM`() {
        // 80% 40 字符断行 + 20% 正常段落（§68）
        val sb = StringBuilder()
        val wrapPara = "张三推开门，看见李雪坐在桌边，皱眉说道，你怎么还没回去。外面天已经全黑了，路上不太安全，我送你回去吧。"
        val normalPara = "　　晨雾未散，林间小径上传来脚步声，他停下脚步低声说道，前面好像有人。"
        var n = 0
        while (sb.length < 3_000_000) {
            if (n % 5 == 0) sb.append(normalPara).append("\n")
            else for (i in 0 until wrapPara.length step 40) sb.append(wrapPara.substring(i, minOf(i + 40, wrapPara.length))).append("\n")
            sb.append("\n")
            n++
        }
        val text = sb.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = com.readervoice.parser.source.EncodingDetector.detect(bytes)
        val lines = com.readervoice.parser.source.PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines

        val t0 = System.nanoTime()
        val r = ParagraphRecoveryPipeline().run(lines, "b", emptySet(), emptySet(), emptySet(), emptyList())
        val totalMs = (System.nanoTime() - t0) / 1e6
        val heap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0

        println("=== TASK030_STRESS ===")
        println("bytes=${bytes.size} physical_lines=${lines.size}")
        println("paragraphs=${r.paragraphs.size} profile=${r.bookProfile.profileType}")
        println("profile_ms=${"%.0f".format(r.profileTimeMs)} boundary_ms=${"%.0f".format(r.boundaryTimeMs)} build_ms=${"%.0f".format(r.buildTimeMs)} total_ms=${"%.0f".format(totalMs)}")
        println("peak_heap_mb=${"%.1f".format(heap)}")
        val avg = r.paragraphs.map { it.paragraph.sourceSpans.size }.average()
        println("avg_lines_per_paragraph=${"%.1f".format(avg)}")

        assertTrue(totalMs < 120_000, "must complete, took ${totalMs}ms")
        assertTrue(heap < 1536, "heap must stay bounded: ${heap}MB")
        assertTrue(r.paragraphs.isNotEmpty())
    }
}
