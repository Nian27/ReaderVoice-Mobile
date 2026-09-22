package com.readervoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CosyVoiceCachePathTest {
    @Test
    fun `safe cache key remains inside generated filename`() {
        assertEquals("cosy-seg_001-a.wav", CosyVoiceCachePath.fileNameFor("seg_001-a"))
    }

    @Test
    fun `path-like cache key is rejected`() {
        assertNull(CosyVoiceCachePath.fileNameFor("../outside"))
        assertNull(CosyVoiceCachePath.fileNameFor("nested/file"))
    }
}
