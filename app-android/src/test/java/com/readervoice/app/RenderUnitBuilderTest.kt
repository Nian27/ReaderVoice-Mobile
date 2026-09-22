package com.readervoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderUnitBuilderTest {

    private val profile = VoiceProfileRef(
        voiceProfileId = "vp_yaolao_base",
        profileHash = "sha256:abc123",
        voiceRevisionId = "vr_001",
        realtimeReady = true,
    )

    private fun director(
        speaker: String? = "药老",
        type: String = "dialogue",
        text: String = "你终于回来了。",
        emotion: String = "tired",
        intensity: Double = 0.6,
        pace: String = "slow",
        volume: String = "normal",
        tone: String = "weary",
        voiceEvent: String = "normal",
    ) = ReaderDirectorSchema.DirectorResult(
        speaker = speaker, type = type, text = text,
        emotion = emotion, emotionIntensity = intensity,
        delivery = ReaderDirectorSchema.Delivery(pace, volume, tone),
        voiceEvent = voiceEvent, raw = "{}",
    )

    private fun req(d: ReaderDirectorSchema.DirectorResult, src: String = "\"你终于回来了。\"") =
        RenderUnitBuilder.BuildRequest(
            unitId = "book_a:ch_003:seg_00023",
            sourceText = src,
            director = d,
            profile = profile,
        )

    @Test
    fun dialogueBindsCharacterAndKeepsRawText() {
        val c = RenderUnitBuilder.build(req(director()))
        assertEquals(RenderUnitBuilder.SEGMENT_DIALOGUE, c.segmentType)
        assertEquals("药老", c.characterId)
        assertEquals("\"你终于回来了。\"", c.textRaw)
        assertEquals("原文不可变 + v1 恒等", c.textRaw, c.textNormalized)
        assertEquals("你终于回来了。", c.spokenText)
        assertEquals(RenderUnitBuilder.TTS_BACKEND, c.ttsBackend)
    }

    @Test
    fun narrationWithSpeakerDoesNotBindCharacter() {
        val c = RenderUnitBuilder.build(req(director(speaker = "药老", type = "narration")))
        assertEquals(RenderUnitBuilder.SEGMENT_NARRATION, c.segmentType)
        assertNull("叙述不得绑定角色", c.characterId)
        assertTrue(c.warnings.any { it.contains("NARRATION 却给出 speaker") })
    }

    @Test
    fun dialogueWithoutSpeakerIsAllowedButWarned() {
        val c = RenderUnitBuilder.build(req(director(speaker = null)))
        assertNull(c.characterId)
        assertTrue(c.warnings.any { it.contains("speaker 为空") })
    }

    @Test
    fun cleanCandidatePassesValidator() {
        val c = RenderUnitBuilder.build(req(director()))
        val v = RenderUnitValidator.validate(c)
        assertTrue("应无违规，实际: " + v.joinToString { it.rule + ":" + it.detail }, v.isEmpty())
    }

    @Test
    fun validatorCatchesCacheKeyTampering() {
        val c = RenderUnitBuilder.build(req(director()))
        val tampered = c.copy(synthesisInput = c.synthesisInput.copy(cacheKey = "deadbeef"))
        val v = RenderUnitValidator.validate(tampered)
        assertTrue(v.any { it.rule == "V7" })
    }

    @Test
    fun validatorCatchesNormalizedTextDrift() {
        val c = RenderUnitBuilder.build(req(director()))
        val v = RenderUnitValidator.validate(c.copy(textNormalized = "被改过的文本"))
        assertTrue(v.any { it.rule == "V3b" })
    }

    @Test
    fun validatorCatchesUnknownInstructionKeys() {
        val c = RenderUnitBuilder.build(req(director(emotion = "smug", voiceEvent = "giggle")))
        val v = RenderUnitValidator.validate(c)
        assertTrue("未知取值必须 fail-closed", v.any { it.rule == "V6" })
    }

    @Test
    fun cacheKeyIsDeterministicAndSensitiveToText() {
        val a = RenderUnitBuilder.build(req(director()))
        val b = RenderUnitBuilder.build(req(director()))
        assertEquals(a.synthesisInput.cacheKey, b.synthesisInput.cacheKey)
        assertEquals(64, a.synthesisInput.cacheKey.length)
        val other = RenderUnitBuilder.build(req(director(text = "别告诉别人。"), src = "\"别告诉别人。\""))
        assertFalse(a.synthesisInput.cacheKey == other.synthesisInput.cacheKey)
    }

    @Test
    fun cacheKeyChangesWithProfileHash() {
        val a = RenderUnitBuilder.build(req(director()))
        val p2 = profile.copy(profileHash = "sha256:zzz999")
        val c = RenderUnitBuilder.build(req(director()).copy(profile = p2))
        assertFalse(a.synthesisInput.cacheKey == c.synthesisInput.cacheKey)
    }

    @Test
    fun whisperProducesInstruct2WithInstruction() {
        val c = RenderUnitBuilder.build(req(director(voiceEvent = "whisper")))
        assertEquals(RenderInstructionCompiler.MODE_INSTRUCT2, c.synthesisInput.instructionMode)
        assertTrue(c.synthesisInput.instruction.isNotBlank())
        assertEquals("WHISPER", c.voiceState)
    }
}
