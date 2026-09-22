package com.readervoice.parser.source

import java.nio.file.Files
import java.nio.file.Path

/** TASK-010 Gold Fixtures 定位（tests/fixtures/txt/，全部自制/合成，无私人素材）。 */
object Fixtures {
    fun dir(): Path {
        val candidates = listOf(
            Path.of("..", "tests", "fixtures", "txt"),
            Path.of("tests", "fixtures", "txt"),
            Path.of("..", "..", "tests", "fixtures", "txt"),
        )
        return candidates.first { Files.isDirectory(it) }
    }

    fun file(name: String): Path = dir().resolve(name)
    fun bytes(name: String): ByteArray = Files.readAllBytes(file(name))
}
