package com.readervoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDirectorSchemaTest {

    @Test
    fun parsesValidDialogueJson() {
        val raw = """{"speaker":"药老","type":"dialogue","text":"你终于回来了。","emotion":"tired","emotion_intensity":0.62,"delivery":{"pace":"slow","volume":"normal","tone":"weary"},"voice_event":"normal"}"""
        val outcome = ReaderDirectorSchema.parse(raw)
        assertTrue(outcome is ReaderDirectorSchema.ParseOutcome.Success)
        val r = (outcome as ReaderDirectorSchema.ParseOutcome.Success).result
        assertEquals("药老", r.speaker)
        assertEquals("dialogue", r.type)
        assertEquals("tired", r.emotion)
        assertEquals(0.62, r.emotionIntensity, 1e-6)
        assertEquals("slow", r.delivery.pace)
        assertEquals("weary", r.delivery.tone)
    }

    @Test
    fun toleratesMarkdownCodeFence() {
        val raw = "Here is the JSON:\n```json\n{\"speaker\":null,\"type\":\"narration\",\"text\":\"萧炎推开房门。\",\"emotion\":\"neutral\"}\n```"
        val outcome = ReaderDirectorSchema.parse(raw)
        assertTrue(outcome is ReaderDirectorSchema.ParseOutcome.Success)
        val r = (outcome as ReaderDirectorSchema.ParseOutcome.Success).result
        assertEquals(null, r.speaker)
        assertEquals("narration", r.type)
    }

    @Test
    fun failsOnMissingType() {
        val outcome = ReaderDirectorSchema.parse("{\"text\":\"你好\"}")
        assertTrue(outcome is ReaderDirectorSchema.ParseOutcome.Failure)
        val f = outcome as ReaderDirectorSchema.ParseOutcome.Failure
        assertTrue(f.reason.contains("type"))
    }

    @Test
    fun failsOnEmptyOutput() {
        assertTrue(ReaderDirectorSchema.parse("") is ReaderDirectorSchema.ParseOutcome.Failure)
        assertTrue(ReaderDirectorSchema.parse("   ") is ReaderDirectorSchema.ParseOutcome.Failure)
    }

    @Test
    fun failsOnInvalidType() {
        val outcome = ReaderDirectorSchema.parse("{\"type\":\"foo\",\"text\":\"x\"}")
        assertTrue(outcome is ReaderDirectorSchema.ParseOutcome.Failure)
        val f = outcome as ReaderDirectorSchema.ParseOutcome.Failure
        assertTrue(f.reason.contains("非法"))
    }
}
