package com.readervoice.app

import java.security.MessageDigest

/**
 * 确定性 RenderInstruction 编译器（R2：LLM 不直接控制 TTS）。
 *
 * LLM 只产出结构化事件（emotion / voice_event / delivery），本编译器按**冻结映射表**
 * 生成最终 instruction。同输入必同输出；未知取值**不静默丢弃**，记入 unknownKeys 供校验器判定。
 */
object RenderInstructionCompiler {

    const val COMPILER_VERSION = "ric-v1"

    const val MODE_INSTRUCT2 = "INSTRUCT2"
    const val MODE_INSTRUCT1 = "INSTRUCT1"
    const val MODE_ZERO_SHOT = "ZERO_SHOT"

    /** 情绪强度阈值：低于 LOW 不提情绪；高于 HIGH 追加"强烈"。 */
    const val INTENSITY_LOW = 0.25
    const val INTENSITY_HIGH = 0.75

    private val EMOTION_PHRASE = mapOf(
        "tired" to "疲惫", "sad" to "悲伤", "angry" to "愤怒",
        "calm" to "平静", "joyful" to "愉悦", "anxious" to "焦虑",
        "cold" to "冷淡", "fearful" to "惶恐", "neutral" to "",
    )
    private val PACE_PHRASE = mapOf("slow" to "语速放慢", "normal" to "", "fast" to "语速加快")
    private val VOLUME_PHRASE = mapOf("quiet" to "音量放低", "normal" to "", "loud" to "音量提高")
    private val VOICE_EVENT_PHRASE = mapOf(
        "whisper" to "用耳语般、轻声的方式",
        "cry" to "带着哭腔",
        "laugh" to "带着笑意",
        "shout" to "提高声音喊出",
        "normal" to "",
    )

    private val VOICE_EVENT_STATE = mapOf(
        "whisper" to "WHISPER", "cry" to "CRY", "laugh" to "LAUGH",
        "shout" to "SHOUT", "normal" to "BASE",
    )

    data class Compiled(
        val mode: String,
        val instruction: String,
        val voiceState: String,
        val compilerVersion: String,
        val instructionHash: String,
        val unknownKeys: List<String>,
    ) {
        val hasUnknown: Boolean get() = unknownKeys.isNotEmpty()
    }

    fun compile(result: ReaderDirectorSchema.DirectorResult): Compiled {
        val unknown = mutableListOf<String>()
        val parts = mutableListOf<String>()

        val event = result.voiceEvent.trim().lowercase()
        val eventPhrase = VOICE_EVENT_PHRASE[event]
        if (eventPhrase == null) unknown += "voice_event=$event"
        else if (eventPhrase.isNotEmpty()) parts += eventPhrase

        val emotion = result.emotion.trim().lowercase()
        val emotionPhrase = EMOTION_PHRASE[emotion]
        if (emotionPhrase == null) {
            unknown += "emotion=$emotion"
        } else if (emotionPhrase.isNotEmpty()) {
            val i = result.emotionIntensity
            val phrase = when {
                i < INTENSITY_LOW -> ""
                i >= INTENSITY_HIGH -> "强烈$emotionPhrase"
                else -> emotionPhrase
            }
            if (phrase.isNotEmpty()) parts += phrase
        }

        val pace = result.delivery.pace.trim().lowercase()
        val pacePhrase = PACE_PHRASE[pace]
        if (pacePhrase == null) unknown += "delivery.pace=$pace"
        else if (pacePhrase.isNotEmpty()) parts += pacePhrase

        val volume = result.delivery.volume.trim().lowercase()
        val volumePhrase = VOLUME_PHRASE[volume]
        if (volumePhrase == null) unknown += "delivery.volume=$volume"
        else if (volumePhrase.isNotEmpty()) parts += volumePhrase

        // tone 为自由描述，但不是直通 TTS 的标签：只作为自然语言修饰进入 instruction。
        val tone = result.delivery.tone.trim().lowercase()
        if (tone.isNotEmpty() && tone != "neutral") {
            if (tone.length > MAX_TONE_LEN || !TONE_ALLOWED.matches(tone)) {
                unknown += "delivery.tone=$tone"
            } else {
                parts += "语气$tone"
            }
        }

        val instruction = if (parts.isEmpty()) {
            DEFAULT_INSTRUCTION
        } else {
            "请用" + parts.joinToString("、") + "的方式说这句话。"
        }
        val mode = if (parts.isEmpty()) MODE_ZERO_SHOT else MODE_INSTRUCT2
        val state = VOICE_EVENT_STATE[event] ?: "BASE"

        return Compiled(
            mode = mode,
            instruction = instruction,
            voiceState = state,
            compilerVersion = COMPILER_VERSION,
            instructionHash = sha256Hex(instruction),
            unknownKeys = unknown.toList(),
        )
    }

    private const val MAX_TONE_LEN = 12
    private val TONE_ALLOWED = Regex("^[a-z_]{1,12}$")
    private val DEFAULT_INSTRUCTION = "请用自然、平稳的语气说这句话。"

    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }
}
