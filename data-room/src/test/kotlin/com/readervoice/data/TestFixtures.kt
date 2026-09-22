package com.readervoice.data

import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLine
import com.readervoice.parser.source.PhysicalLineScanner
import java.nio.file.Files
import java.nio.file.Path

/** data-room 测试 fixtures（复用仓库根 tests/fixtures/，不依赖 parser 测试类）。 */
object TestFixtures {
    private fun repoDir(): Path {
        val candidates = listOf(
            Path.of("..", "tests", "fixtures"),
            Path.of("tests", "fixtures"),
            Path.of("..", "..", "tests", "fixtures"),
        )
        return candidates.first { Files.isDirectory(it) }
    }

    fun goldBookText(): String = Files.readString(repoDir().resolve("chapter/gold_structured_book.txt"))

    fun linesOf(text: String): List<PhysicalLine> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val det = EncodingDetector.detect(bytes)
        return PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
    }
}
