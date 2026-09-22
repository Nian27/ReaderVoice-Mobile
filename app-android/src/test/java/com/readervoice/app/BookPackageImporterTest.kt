package com.readervoice.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class BookPackageImporterTest {
    @Test
    fun `legacy manifest without origin is recognized as an automatic index`() {
        val legacy = JSONObject().apply {
            put("book_id", "book-legacy")
            put("title", "测试书")
            put("source_file_name", "测试书.txt")
            put("source_sha256", "abc")
            put("source_bytes", 1)
            put("imported_at_epoch_ms", 1)
            put("structure_state", "STRUCTURE_READY")
            put("chapter_count", 1)
            put("structure_format_version", 2)
        }.toString()

        val decoded = BookManifestCodec.decode(legacy)

        assertEquals(2, decoded.structureFormatVersion)
        assertEquals(STRUCTURE_ORIGIN_AUTO_LEGACY_RULEPACK, decoded.structureOrigin)
    }

    @Test
    fun `imports immutable source and creates frozen package layout`() {
        val root = Files.createTempDirectory("readervoice-books").toFile()
        try {
            val bytes = "第一章 开始\n正文".toByteArray(Charsets.UTF_8)
            val importer = BookPackageImporter(root, now = { 42L }) { directory, _ ->
                File(directory, "book.db").writeText("test-index")
                BookIndexResult("STRUCTURE_READY", 1)
            }

            val outcome = importer.import("测试书.txt", ByteArrayInputStream(bytes))
            val imported = outcome as BookPackageImporter.Outcome.Imported
            assertEquals("STRUCTURE_READY", imported.manifest.structureState)
            assertEquals(1, imported.manifest.chapterCount)
            assertEquals(3, imported.manifest.structureFormatVersion)
            assertEquals(STRUCTURE_ORIGIN_AUTO_LEGACY_RULEPACK, imported.manifest.structureOrigin)
            assertArrayEquals(bytes, File(imported.directory, "source.txt").readBytes())
            assertTrue(File(imported.directory, "manifest.json").isFile)
            assertTrue(File(imported.directory, "book.db").isFile)
            assertTrue(File(imported.directory, "audio").isDirectory)
            assertTrue(File(imported.directory, "voices").isDirectory)
            assertTrue(File(imported.directory, "cache").isDirectory)
            assertTrue(File(imported.directory, "render_units").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same bytes are an exact duplicate and do not make a second package`() {
        val root = Files.createTempDirectory("readervoice-books").toFile()
        try {
            val bytes = "相同原文".toByteArray(Charsets.UTF_8)
            val importer = BookPackageImporter(root, initializeIndex = { directory, _ ->
                File(directory, "book.db").writeText("index")
                BookIndexResult("STRUCTURE_READY", 1)
            })
            importer.import("a.txt", ByteArrayInputStream(bytes))
            val duplicate = importer.import("renamed.txt", ByteArrayInputStream(bytes))
            assertTrue(duplicate is BookPackageImporter.Outcome.ExactDuplicate)
            assertEquals(1, root.listFiles { file -> file.isDirectory && !file.name.startsWith('.') }!!.size)
        } finally {
            root.deleteRecursively()
        }
    }
}
