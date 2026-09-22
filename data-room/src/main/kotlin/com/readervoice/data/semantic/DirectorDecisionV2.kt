package com.readervoice.data.semantic

import org.json.JSONObject

/**
 * MOBILE-005 / M3 步骤 3：**DirectorDecisionV2（冻结契约）**。
 *
 * 铁律（ADR-055）：本对象**只承载导演决策，绝不携带生产文本**。
 * 生产文本永远是 `SemanticSegment.sourceSpan` 的切片，由 `ScriptLineBuilder` 生成，无任何例外。
 *
 * 枚举里保留 `UNRECOGNIZED`：Normalizer **只改表示形式**，遇到无法映射的取值时不做语义猜测，
 * 而是落到 UNRECOGNIZED，由 Validator 判为软失败、由 DecisionPolicy 降级为安全默认值。
 */

enum class DirectorType {
    NARRATION,
    DIALOGUE,

    /** 缺失或无法映射（Normalizer 不做推断）—— 与 speaker 组合必然触发 SPEAKER_TYPE_CONFLICT。 */
    UNRECOGNIZED,
}

enum class Emotion {
    NEUTRAL, TIRED, SAD, ANGRY, CALM, JOYFUL, FEARFUL, SURPRISED,
    GENTLE, COLD, EXCITED, SARCASTIC, RESIGNED,
    UNRECOGNIZED,
}

enum class Pace { SLOW, NORMAL, FAST, UNRECOGNIZED }

enum class Volume { QUIET, NORMAL, LOUD, UNRECOGNIZED }

enum class Tone { NEUTRAL, WEARY, GENTLE, COLD, SERIOUS, WARM, SARCASTIC, UNRECOGNIZED }

enum class VoiceEvent { NORMAL, WHISPER, CRY, LAUGH, SHOUT, SIGH, MUMBLE, UNRECOGNIZED }

data class Delivery(
    val pace: Pace = Pace.NORMAL,
    val volume: Volume = Volume.NORMAL,
    val tone: Tone = Tone.NEUTRAL,
) {
    companion object {
        /** DOWNGRADE 时使用的保守默认值（可安全朗读）。 */
        val SAFE = Delivery(Pace.NORMAL, Volume.NORMAL, Tone.NEUTRAL)
    }
}

data class DirectorDecisionV2(
    /** 片段局部编号（S0/S1…），必须与本次请求的 target 一致。 */
    val segmentId: String,
    /** `C#` | `NARRATOR` | `UNKNOWN`（局部 ID，绝不携带内部身份）。 */
    val speaker: String,
    val type: DirectorType,
    val emotion: Emotion,
    val emotionIntensity: Float,
    val delivery: Delivery,
    val voiceEvent: VoiceEvent,
    /** 只能引用本轮提供的 `E#`。 */
    val evidence: List<String>,
) {
    companion object {
        const val NARRATOR = "NARRATOR"
        const val UNKNOWN = "UNKNOWN"

        /** 安全默认（DOWNGRADE 用）。 */
        const val SAFE_INTENSITY = 0.5f
        val SAFE_EMOTION = Emotion.NEUTRAL
        val SAFE_VOICE_EVENT = VoiceEvent.NORMAL

        fun isCandidateRef(speaker: String): Boolean = Regex("^C\\d+$").matches(speaker)

        /** speaker/type 关系（冻结）：NARRATOR→NARRATION；C#→DIALOGUE；UNKNOWN→DIALOGUE。 */
        fun relationValid(speaker: String, type: DirectorType): Boolean = when {
            speaker == NARRATOR -> type == DirectorType.NARRATION
            speaker == UNKNOWN -> type == DirectorType.DIALOGUE
            isCandidateRef(speaker) -> type == DirectorType.DIALOGUE
            else -> false
        }
    }
}

/** Normalizer 能做的**唯一**一类修改：表示形式（绝不改语义答案）。 */
enum class CosmeticFix {
    BARE_TOKEN_QUOTED,        // C0 → "C0"
    ENUM_CASE,                // dialogue → DIALOGUE
    EVIDENCE_TRAILING_TEXT,   // "E0: RULE: xxx" → "E0"
    FLAT_DELIVERY_KEY,        // "delivery.pace" → delivery.pace
    CODE_FENCE_STRIPPED,      // ```json … ```
    TRAILING_COMMA,
    JSON_OBJECT_EXTRACTED,    // 前后有解释文字，取出唯一 JSON 对象
    INTENSITY_AS_STRING,      // "0.6" → 0.6
}

/** 校验失败码（冻结清单）。 */
enum class FailureCode {
    JSON_PARSE_FAILED,
    SEGMENT_ID_MISSING,
    SEGMENT_ID_MISMATCH,
    SPEAKER_MISSING,
    SPEAKER_ID_INVALID,
    SPEAKER_ID_MISSING,
    SPEAKER_TYPE_CONFLICT,
    EVIDENCE_ID_INVALID,
    EVIDENCE_ID_MISSING,
    EMOTION_INVALID,
    EMOTION_INTENSITY_RANGE,
    DELIVERY_PACE_INVALID,
    DELIVERY_VOLUME_INVALID,
    DELIVERY_TONE_INVALID,
    VOICE_EVENT_INVALID,
}

enum class Severity { HARD, SOFT }

data class Failure(val code: FailureCode, val severity: Severity, val detail: String)

/**
 * 四态结局（冻结）。**UNKNOWN ≠ REJECT ≠ DOWNGRADE。**
 *  - ACCEPTED_ALL：核心 + 表演字段全部可信 → 生成 ScriptLine
 *  - DOWNGRADE   ：核心（speaker/type）可信，表演字段有问题 → 降级为安全默认值后生成
 *  - VERIFY      ：文本可安全保留，但说话人/证据存在语义不确定性 → 生成但不得当"已确认角色"
 *  - REJECT      ：协议结构或引用已不可信 → **不生成** ScriptLine
 */
enum class ValidationOutcome { ACCEPTED_ALL, DOWNGRADE, VERIFY, REJECT }

data class ValidationResult(
    val outcome: ValidationOutcome,
    /** 通过校验的决策（DOWNGRADE 时表演字段已替换为安全默认值）。REJECT 时为 null。 */
    val decision: DirectorDecisionV2?,
    val hardFailures: List<Failure>,
    val softFailures: List<Failure>,
    val cosmeticFixes: List<CosmeticFix>,
) {
    val ok: Boolean get() = outcome != ValidationOutcome.REJECT
}

/** Normalizer 输出：可解析的决策 + 表示层修复记录。 */
data class NormalizedOutput(
    val decision: DirectorDecisionV2?,
    val fixes: List<CosmeticFix>,
    val parseFailure: Failure?,
    val raw: String,
) {
    val parsed: Boolean get() = decision != null
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else get(key).toString().trim().ifEmpty { null }
