package com.readervoice.app

/**
 * DirectorResult → RenderUnit v1 候选（纯数据）。
 *
 * R4：候选无执行逻辑，可校验、可缓存、可重放。
 * R5：TTS 不重新理解文本 —— synthesisInput 只携带已确定的朗读文本与 instruction。
 *
 * 关于 text 字段的显式取舍（审计要点）：
 * - 冻结 schema 规定 text.normalized 在 v1 恒等于 text.raw（原文不可变）。
 * - Director 产出的 text 是"真正需要朗读的文本"（对话去引号）。
 * - 因此本 Builder 保留 textRaw/textNormalized = 原文，另用 spokenText 承载朗读文本，
 *   并在两者不一致时记入 warnings，绝不做静默替换。
 */
object RenderUnitBuilder {

    const val SEGMENT_NARRATION = "NARRATION"
    const val SEGMENT_DIALOGUE = "DIALOGUE"
    const val TTS_BACKEND = "cosyvoice"
    const val TTS_MODEL = "cv3-mnn-v1"
    const val DEFAULT_SAMPLE_RATE = 24000

    data class BuildRequest(
        val unitId: String,
        val sourceText: String,
        val director: ReaderDirectorSchema.DirectorResult,
        val profile: VoiceProfileRef,
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val speed: Float = 1.0f,
    )

    data class Candidate(
        val id: String,
        val segmentType: String,
        val textRaw: String,
        val textNormalized: String,
        val spokenText: String,
        val characterId: String?,
        val voiceProfileId: String,
        val profileHash: String,
        val voiceState: String,
        val voiceEvent: String,
        val emotion: String,
        val emotionIntensity: Double,
        val renderInstruction: RenderInstructionCompiler.Compiled,
        val ttsBackend: String,
        val ttsModel: String,
        val synthesisInput: RenderUnitSynthesisInput,
        val warnings: List<String>,
    )

    fun build(req: BuildRequest): Candidate {
        val warnings = mutableListOf<String>()
        val d = req.director

        val segmentType = if (d.type == "dialogue") SEGMENT_DIALOGUE else SEGMENT_NARRATION

        val textRaw = req.sourceText
        val textNormalized = req.sourceText // v1 恒等（冻结 schema）
        val spoken = d.text.trim().ifBlank {
            warnings += "director.text 为空，朗读文本回落原文"
            req.sourceText
        }
        if (spoken != textRaw) warnings += "朗读文本与原文不同（去引号/归一），已记入 spokenText"

        if (segmentType == SEGMENT_DIALOGUE && d.speaker.isNullOrBlank()) {
            warnings += "DIALOGUE 但 speaker 为空 -> 角色未解析（UNKNOWN 合法，需上层补角色）"
        }
        if (segmentType == SEGMENT_NARRATION && !d.speaker.isNullOrBlank()) {
            warnings += "NARRATION 却给出 speaker，已忽略 speaker 作为角色绑定"
        }

        val compiled = RenderInstructionCompiler.compile(d)
        if (compiled.hasUnknown) {
            warnings += "instruction 编译遇到未知取值: " + compiled.unknownKeys.joinToString(",")
        }

        val characterId =
            if (segmentType == SEGMENT_DIALOGUE) d.speaker?.trim()?.takeIf { it.isNotEmpty() } else null

        val cacheKey = cacheKey(
            profileHash = req.profile.profileHash,
            mode = compiled.mode,
            instructionHash = compiled.instructionHash,
            speed = req.speed,
            sampleRate = req.sampleRate,
            text = spoken,
        )

        val synthesisInput = RenderUnitSynthesisInput(
            id = req.unitId,
            normalizedText = spoken,
            instructionMode = compiled.mode,
            instruction = compiled.instruction,
            cacheKey = cacheKey,
            sampleRate = req.sampleRate,
            speed = req.speed,
        )

        return Candidate(
            id = req.unitId,
            segmentType = segmentType,
            textRaw = textRaw,
            textNormalized = textNormalized,
            spokenText = spoken,
            characterId = characterId,
            voiceProfileId = req.profile.voiceProfileId,
            profileHash = req.profile.profileHash,
            voiceState = compiled.voiceState,
            voiceEvent = d.voiceEvent.trim().lowercase(),
            emotion = d.emotion.trim().lowercase(),
            emotionIntensity = d.emotionIntensity,
            renderInstruction = compiled,
            ttsBackend = TTS_BACKEND,
            ttsModel = TTS_MODEL,
            synthesisInput = synthesisInput,
            warnings = warnings.toList(),
        )
    }

    /** 冻结公式：sha256(model|profile_hash|mode|inst_hash|speed|sample_rate|text_sha)。 */
    fun cacheKey(
        profileHash: String,
        mode: String,
        instructionHash: String,
        speed: Float,
        sampleRate: Int,
        text: String,
    ): String {
        val textSha = RenderInstructionCompiler.sha256Hex(text)
        val speedStr = speed.toString()
        return RenderInstructionCompiler.sha256Hex(
            listOf(TTS_MODEL, profileHash, mode.lowercase(), instructionHash, speedStr, sampleRate.toString(), textSha)
                .joinToString("|")
        )
    }
}

/** RenderUnit v1 候选的 fail-closed 校验器。返回空列表 = 通过。 */
object RenderUnitValidator {

    private val VALID_MODES = setOf(
        RenderInstructionCompiler.MODE_INSTRUCT1,
        RenderInstructionCompiler.MODE_INSTRUCT2,
        RenderInstructionCompiler.MODE_ZERO_SHOT,
    )

    data class Violation(val rule: String, val detail: String)

    fun validate(c: RenderUnitBuilder.Candidate): List<Violation> {
        val v = mutableListOf<Violation>()
        if (c.id.isBlank()) v += Violation("V1", "id 为空")
        if (c.segmentType !in setOf(RenderUnitBuilder.SEGMENT_NARRATION, RenderUnitBuilder.SEGMENT_DIALOGUE))
            v += Violation("V2", "segmentType 非法: ${c.segmentType}")
        if (c.textRaw.isBlank()) v += Violation("V3a", "text.raw 为空（违反原文不可变）")
        if (c.textNormalized != c.textRaw) v += Violation("V3b", "text.normalized != text.raw（v1 要求恒等）")
        if (c.spokenText.isBlank()) v += Violation("V3c", "spokenText 为空，无可合成文本")
        if (c.renderInstruction.mode !in VALID_MODES)
            v += Violation("V4", "instructionMode 非法: ${c.renderInstruction.mode}")
        if (c.synthesisInput.instructionMode == RenderInstructionCompiler.MODE_INSTRUCT2 &&
            c.synthesisInput.instruction.isBlank()
        ) v += Violation("V5", "INSTRUCT2 但 instruction 为空")
        if (c.renderInstruction.hasUnknown)
            v += Violation("V6", "instruction 编译存在未知取值: ${c.renderInstruction.unknownKeys.joinToString(",")}")
        if (c.synthesisInput.cacheKey != RenderUnitBuilder.cacheKey(
                profileHash = c.profileHash,
                mode = c.renderInstruction.mode,
                instructionHash = c.renderInstruction.instructionHash,
                speed = c.synthesisInput.speed,
                sampleRate = c.synthesisInput.sampleRate,
                text = c.spokenText,
            )
        ) v += Violation("V7", "cacheKey 与冻结公式不一致")
        if (c.synthesisInput.sampleRate != RenderUnitBuilder.DEFAULT_SAMPLE_RATE)
            v += Violation("V8", "sampleRate != ${RenderUnitBuilder.DEFAULT_SAMPLE_RATE}")
        if (c.synthesisInput.speed <= 0f || c.synthesisInput.speed > 3f)
            v += Violation("V9", "speed 越界: ${c.synthesisInput.speed}")
        if (c.voiceProfileId.isBlank() || c.profileHash.isBlank())
            v += Violation("V10", "voice_profile 引用不完整")
        return v
    }
}
