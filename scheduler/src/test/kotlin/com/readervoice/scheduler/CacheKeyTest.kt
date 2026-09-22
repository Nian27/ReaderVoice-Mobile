package com.readervoice.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * §5 缓存键公式锁定：
 * sha256(tts_model + "|" + profile_hash + "|" + mode + "|" + instruction + "|" + speed + "|" + sample_rate + "|" + text)
 */
class CacheKeyTest {

    @Test
    fun sameInputSameKey() {
        val a = TestSupport.unit("b:c:1")
        val b = TestSupport.unit("b:c:1")
        assertEquals(a.cacheKey, b.cacheKey)
    }

    @Test
    fun keyMatchesFrozenFormula() {
        val u = TestSupport.unit("b:c:1", text = "别告诉别人。", profileHash = "ph-1", instruction = "请平静地说。")
        val raw = "cv3-mnn-v1|ph-1|INSTRUCT2|请平静地说。|1.0|24000|别告诉别人。"
        assertEquals(TestSupport.sha256(raw), u.cacheKey)
    }

    @Test
    fun eachFieldChangeChangesKey() {
        val base = TestSupport.unit("b:c:1")
        val variants = listOf(
            base.copy(textNormalized = "改"),
            base.copy(profileHash = "ph-2"),
            base.copy(instruction = "请大声说。"),
            base.copy(ttsModel = "cv3-mnn-v2"),
            base.copy(speed = 1.2f),
            base.copy(sampleRate = 16000),
            base.copy(instructionMode = "ZERO_SHOT"),
        )
        for (v in variants) assertNotEquals(base.cacheKey, v.cacheKey, "variant should differ: " + v)
    }

    @Test
    fun unitIdDoesNotAffectKey() {
        val a = TestSupport.unit("b:c:1")
        val b = TestSupport.unit("b:c:2")
        assertEquals(a.cacheKey, b.cacheKey)
    }
}
