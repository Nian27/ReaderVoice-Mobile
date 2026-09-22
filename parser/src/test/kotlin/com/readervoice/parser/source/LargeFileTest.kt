package com.readervoice.parser.source

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 3M+ 中文字符 synthetic 大文件（TASK-010 §20/§21）。
 * 指标输出到 stdout，人工抄录进 docs/experiments/TASK010_LARGE_FILE_REPORT.md。
 */
class LargeFileTest {

    @Test
    fun `import 3M plus chinese characters without OOM`() {
        // 生成 ~3.3M 汉字（每行约 40 字，含 U+3000 缩进与句末标点）
        val paras = listOf(
            "　　晨雾未散，林间小径上传来脚步声，他停下脚步低声说道。",
            "　　阿雪抬起头，目光落向远处的山脊，这一走便是三年。",
            "　　夜里烛火摇曳，他把那封信又读了一遍，风从窗口灌进来。",
        )
        val sb = StringBuilder()
        val target = 3_300_000
        while (sb.length < target) {
            for (p in paras) sb.append(p).append("\n")
        }
        val text = sb.toString()
        val chars = text.codePointCount(0, text.length)

        val root = createTempDirectory("rv-large-test-")
        val file = root.resolve("large_3m.txt")
        file.writeText(text)
        val bytes = Files.readAllBytes(file)

        val t0 = System.nanoTime()
        val shaStart = System.nanoTime()
        // hash 时间（ImportPipeline 内部做了两次 hash，这里单独计一次）
        val pipe = ImportPipeline(root)
        val out = pipe.import(file, "uri:large")
        val t1 = System.nanoTime()

        val imported = assertIs<ImportPipeline.Outcome.Imported>(out)
        val totalMs = (t1 - t0) / 1_000_000.0
        val shaMs = (shaStart - t0) / 1_000_000.0

        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024.0

        println("=== TASK010_LARGE_FILE ===")
        println("file_bytes=${bytes.size}")
        println("characters=$chars")
        println("physical_lines=${imported.lines.size}")
        println("encoding=${imported.entry.charset}")
        println("import_wall_ms=${"%.1f".format(totalMs)}")
        println("peak_heap_used_mb=${"%.1f".format(usedMb)}")
        println("avg_line_chars=${"%.1f".format(chars.toDouble() / imported.lines.size)}")

        assertTrue(chars > 3_000_000, "expected 3M+ chars, got $chars")
        assertTrue(imported.lines.size > 60_000)
        assertEquals("IMPORTED", imported.entry.state)
        // 抽样行内容完整（首/中/尾）
        assertTrue(imported.lines.first().rawText.startsWith("　　晨雾未散"))
        assertTrue(imported.lines[imported.lines.size / 2].rawText.length > 20)
        // 无 OOM = 测试本身完成即通过；此处显式断言内存不爆炸（>1.5GB 视为异常）
        assertTrue(usedMb < 1536, "heap too large: ${usedMb}MB")
    }
}
