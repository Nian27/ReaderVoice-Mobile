package com.readervoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRouterTest {
    private val profile = VoiceProfileRef("vp_test", "sha256:test", "vpr_test_1", realtimeReady = true)

    @Test
    fun `unverified Audio8 blocks narrator instead of rerouting it to CosyVoice`() {
        val router = SpeechRouter(mapOf(
            SpeechEngineId.COSYVOICE3_MNN to EngineAvailability.AVAILABLE,
            SpeechEngineId.AUDIO8 to EngineAvailability.UNAVAILABLE,
        ))
        val result = router.route(SpeechRouteRequest(profile, SpeakerRouteKind.NARRATOR))
        assertTrue(result is SpeechRouteResult.Unavailable)
    }

    @Test
    fun `verified routes split narrator and character by narrative identity`() {
        val router = SpeechRouter(mapOf(
            SpeechEngineId.COSYVOICE3_MNN to EngineAvailability.AVAILABLE,
            SpeechEngineId.AUDIO8 to EngineAvailability.AVAILABLE,
        ))
        val narrator = router.route(SpeechRouteRequest(profile, SpeakerRouteKind.NARRATOR))
        val unknown = router.route(SpeechRouteRequest(profile, SpeakerRouteKind.UNKNOWN_SPEAKER))
        val character = router.route(SpeechRouteRequest(profile, SpeakerRouteKind.CHARACTER))
        assertEquals(SpeechEngineId.AUDIO8, (narrator as SpeechRouteResult.Routed).route.engine)
        assertEquals(SpeechEngineId.AUDIO8, (unknown as SpeechRouteResult.Routed).route.engine)
        assertEquals(SpeechEngineId.COSYVOICE3_MNN, (character as SpeechRouteResult.Routed).route.engine)
    }

    @Test
    fun `missing profile or engines is explicit unavailable`() {
        val router = SpeechRouter(emptyMap())
        val noEngine = router.route(SpeechRouteRequest(profile, SpeakerRouteKind.NARRATOR))
        val noProfile = router.route(SpeechRouteRequest(profile.copy(realtimeReady = false), SpeakerRouteKind.NARRATOR))
        assertTrue(noEngine is SpeechRouteResult.Unavailable)
        assertTrue(noProfile is SpeechRouteResult.Unavailable)
    }
}
