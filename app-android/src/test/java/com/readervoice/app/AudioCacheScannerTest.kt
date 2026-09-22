package com.readervoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AudioCacheScannerTest {
    @Test
    fun `only wav files under audio are ordered as cache assets`() {
        val packageDir = Files.createTempDirectory("readervoice-audio-cache").toFile()
        try {
            val audio = packageDir.resolve("audio").apply { mkdir() }
            audio.resolve("0002.wav").writeBytes(byteArrayOf(2))
            audio.resolve("0001.WAV").writeBytes(byteArrayOf(1))
            audio.resolve("ignore.mp3").writeBytes(byteArrayOf(3))
            val assets = AudioCacheScanner.scan(packageDir)
            assertEquals(listOf("audio/0001.WAV", "audio/0002.wav"), assets.map { it.relativePath })
            assertTrue(assets.all { it.bytes == 1L })
        } finally {
            packageDir.deleteRecursively()
        }
    }
}
