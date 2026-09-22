package com.readervoice.app

/** Android projection of frozen RenderUnit fields required by a renderer. */
data class RenderUnitSynthesisInput(
    val id: String,
    val normalizedText: String,
    val instructionMode: String,
    val instruction: String,
    val cacheKey: String,
    val sampleRate: Int,
    val speed: Float,
)

/** Profile reference only. Engine-specific runtime files stay behind each adapter. */
data class VoiceProfileRef(
    val voiceProfileId: String,
    val profileHash: String,
    val voiceRevisionId: String,
    val realtimeReady: Boolean,
)

enum class SpeakerRouteKind { NARRATOR, CHARACTER, UNKNOWN_SPEAKER }
enum class SpeechEngineId { COSYVOICE3_MNN, AUDIO8 }
enum class EngineAvailability { AVAILABLE, UNAVAILABLE }

data class SpeechRoute(
    val engine: SpeechEngineId,
    val profile: VoiceProfileRef,
    val reason: String,
)

sealed interface SpeechRouteResult {
    data class Routed(val route: SpeechRoute) : SpeechRouteResult
    data class Unavailable(val reason: String) : SpeechRouteResult
}

data class SpeechRouteRequest(
    val profile: VoiceProfileRef,
    val speakerKind: SpeakerRouteKind,
)

/**
 * Fail closed: an engine can be selected only after its device capability Gate says AVAILABLE.
 * This keeps unvalidated Audio8 out of the product path while retaining a stable Router seam.
 */
class SpeechRouter(private val availability: Map<SpeechEngineId, EngineAvailability>) {
    fun route(request: SpeechRouteRequest): SpeechRouteResult {
        if (!request.profile.realtimeReady) {
            return SpeechRouteResult.Unavailable("VoiceProfile runtime assets are not ready")
        }
        val target = when (request.speakerKind) {
            SpeakerRouteKind.NARRATOR, SpeakerRouteKind.UNKNOWN_SPEAKER -> SpeechEngineId.AUDIO8
            SpeakerRouteKind.CHARACTER -> SpeechEngineId.COSYVOICE3_MNN
        }
        if (availability[target] == EngineAvailability.AVAILABLE) {
            val reason = when (request.speakerKind) {
                SpeakerRouteKind.NARRATOR -> "narrator bulk route"
                SpeakerRouteKind.UNKNOWN_SPEAKER -> "unknown speaker uses narrator fallback"
                SpeakerRouteKind.CHARACTER -> "character identity route"
            }
            return SpeechRouteResult.Routed(SpeechRoute(target, request.profile, reason))
        }
        return SpeechRouteResult.Unavailable("Required ${target.name} device Gate has not passed")
    }
}

sealed interface SynthesisResult {
    data class Completed(
        val relativeWavPath: String,
        val cacheKey: String,
        val voiceRevisionId: String,
        val sampleRate: Int,
    ) : SynthesisResult
    data class Unavailable(val reason: String) : SynthesisResult
    data class Failed(val reason: String) : SynthesisResult
}

interface TTSEngine {
    val id: SpeechEngineId
    val availability: EngineAvailability
    suspend fun synthesize(
        route: SpeechRoute,
        renderUnit: RenderUnitSynthesisInput,
    ): SynthesisResult
}

/** Native implementation is supplied only by the upcoming CosyVoice extraction task. */
interface CosyVoiceRuntime {
    suspend fun synthesize(
        route: SpeechRoute,
        renderUnit: RenderUnitSynthesisInput,
    ): SynthesisResult
}

class CosyVoiceEngine(private val runtime: CosyVoiceRuntime?) : TTSEngine {
    override val id: SpeechEngineId = SpeechEngineId.COSYVOICE3_MNN
    override val availability: EngineAvailability = if (runtime == null) EngineAvailability.UNAVAILABLE else EngineAvailability.AVAILABLE

    override suspend fun synthesize(route: SpeechRoute, renderUnit: RenderUnitSynthesisInput): SynthesisResult =
        runtime?.synthesize(route, renderUnit) ?: SynthesisResult.Unavailable("CosyVoice runtime is not installed")
}

/** Audio8 is intentionally present as a contract placeholder, but currently cannot render. */
class Audio8Engine : TTSEngine {
    override val id: SpeechEngineId = SpeechEngineId.AUDIO8
    override val availability: EngineAvailability = EngineAvailability.UNAVAILABLE

    override suspend fun synthesize(route: SpeechRoute, renderUnit: RenderUnitSynthesisInput): SynthesisResult =
        SynthesisResult.Unavailable("Audio8 Android ORT Gate has not passed")
}
