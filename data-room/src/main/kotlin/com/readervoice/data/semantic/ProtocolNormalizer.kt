package com.readervoice.data.semantic

import org.json.JSONArray
import org.json.JSONObject

/**
 * MOBILE-005 / M3 步骤 4a：**ProtocolNormalizer**。
 *
 * 铁律：**只能改"表示形式"，不能改"语义答案"。**
 *
 * 允许（并逐项计入 CosmeticFix）：
 *   `C0` → `"C0"`；`dialogue` → `DIALOGUE`；`"E0: RULE: xxx"` → `E0`（**唯一前导 E# 匹配**）；
 *   ```json 围栏、前后解释文字、尾逗号、`delivery.pace` 扁平键、`"0.6"` 字符串强度。
 *
 * 绝不允许（这些必须留给 Validator 判失败 / DecisionPolicy 降级）：
 *   `C7`（不存在）→ `C0`；`邀请` → 某人物；speaker 缺失 → `NARRATOR`；
 *   `DIALOGUE + C0` 不一致 → `UNKNOWN`；非协议枚举（`超级无敌伤心`）→ 猜一个情绪。
 */
object ProtocolNormalizer {

    fun normalize(raw: String): NormalizedOutput {
        val fixes = LinkedHashSet<CosmeticFix>()

        // ① 取出唯一 JSON 对象（前后可能是解释文字与/或代码围栏）
        val text = raw.trim()
        val jsonText = extractObject(text) ?: return NormalizedOutput(
            null, fixes.toList(),
            Failure(FailureCode.JSON_PARSE_FAILED, Severity.HARD, "找不到 JSON 对象"), raw,
        )
        if (jsonText != text) {
            if (text.contains("```")) fixes += CosmeticFix.CODE_FENCE_STRIPPED
            fixes += CosmeticFix.JSON_OBJECT_EXTRACTED
        }

        // ③ 尾逗号
        var repaired = jsonText
        val noTrailing = Regex(",\\s*([}\\]])").replace(repaired) { m -> m.groupValues[1] }
        if (noTrailing != repaired) { repaired = noTrailing; fixes += CosmeticFix.TRAILING_COMMA }

        // ④ 裸 token（`"speaker": C0` / `"emotion": neutral`）
        val quoted = Regex(":\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*([,}\\]])").replace(repaired) { m ->
            val tok = m.groupValues[1]
            if (tok == "true" || tok == "false" || tok == "null") m.value
            else { fixes += CosmeticFix.BARE_TOKEN_QUOTED; ": \"$tok\"${m.groupValues[2]}" }
        }
        if (quoted != repaired) repaired = quoted

        val obj = try {
            JSONObject(repaired)
        } catch (t: Throwable) {
            return NormalizedOutput(
                null, fixes.toList(),
                Failure(FailureCode.JSON_PARSE_FAILED, Severity.HARD, t.message ?: "JSON 解析失败"), raw,
            )
        }

        // ⑤ 扁平 delivery 键归一
        var deliveryObj = obj.optJSONObject("delivery")
        if (deliveryObj == null && (obj.has("delivery.pace") || obj.has("delivery.volume") || obj.has("delivery.tone"))) {
            deliveryObj = JSONObject().apply {
                obj.optStringOrNull("delivery.pace")?.let { put("pace", it) }
                obj.optStringOrNull("delivery.volume")?.let { put("volume", it) }
                obj.optStringOrNull("delivery.tone")?.let { put("tone", it) }
            }
            fixes += CosmeticFix.FLAT_DELIVERY_KEY
        }

        // ⑥ 字段读取（**不做任何语义推断**：缺失就用空/UNRECOGNIZED 表示）
        val segmentId = obj.optStringOrNull("segment_id") ?: ""
        val speaker = obj.optStringOrNull("speaker") ?: ""
        val type = mapType(obj.optStringOrNull("type"))
        val emotion = mapEnum(obj.optStringOrNull("emotion"), EMOTION, Emotion.UNRECOGNIZED) { fixes += CosmeticFix.ENUM_CASE }
        val intensity = readIntensity(obj)
        val delivery = Delivery(
            pace = mapEnum(deliveryObj?.optStringOrNull("pace"), PACE, Pace.UNRECOGNIZED) { fixes += CosmeticFix.ENUM_CASE },
            volume = mapEnum(deliveryObj?.optStringOrNull("volume"), VOLUME, Volume.UNRECOGNIZED) { fixes += CosmeticFix.ENUM_CASE },
            tone = mapEnum(deliveryObj?.optStringOrNull("tone"), TONE, Tone.UNRECOGNIZED) { fixes += CosmeticFix.ENUM_CASE },
        )
        val voiceEvent = mapEnum(obj.optStringOrNull("voice_event"), VOICE_EVENT, VoiceEvent.UNRECOGNIZED) {
            fixes += CosmeticFix.ENUM_CASE
        }
        val evidence = readEvidence(obj) { fixes += CosmeticFix.EVIDENCE_TRAILING_TEXT }

        val decision = DirectorDecisionV2(
            segmentId = segmentId,
            speaker = speaker,
            type = type,
            emotion = emotion,
            emotionIntensity = intensity,
            delivery = delivery,
            voiceEvent = voiceEvent,
            evidence = evidence,
        )
        return NormalizedOutput(decision, fixes.toList(), null, raw)
    }

    // ── 内部工具 ────────────────────────────────────────────────────────

    private val EMOTION = mapOf(
        "NEUTRAL" to Emotion.NEUTRAL, "TIRED" to Emotion.TIRED, "SAD" to Emotion.SAD,
        "ANGRY" to Emotion.ANGRY, "CALM" to Emotion.CALM, "JOYFUL" to Emotion.JOYFUL,
        "FEARFUL" to Emotion.FEARFUL, "SURPRISED" to Emotion.SURPRISED, "GENTLE" to Emotion.GENTLE,
        "COLD" to Emotion.COLD, "EXCITED" to Emotion.EXCITED, "SARCASTIC" to Emotion.SARCASTIC,
        "RESIGNED" to Emotion.RESIGNED,
    )
    private val PACE = mapOf("SLOW" to Pace.SLOW, "NORMAL" to Pace.NORMAL, "FAST" to Pace.FAST)
    private val VOLUME = mapOf("QUIET" to Volume.QUIET, "NORMAL" to Volume.NORMAL, "LOUD" to Volume.LOUD)
    private val TONE = mapOf(
        "NEUTRAL" to Tone.NEUTRAL, "WEARY" to Tone.WEARY, "GENTLE" to Tone.GENTLE, "COLD" to Tone.COLD,
        "SERIOUS" to Tone.SERIOUS, "WARM" to Tone.WARM, "SARCASTIC" to Tone.SARCASTIC,
    )
    private val VOICE_EVENT = mapOf(
        "NORMAL" to VoiceEvent.NORMAL, "WHISPER" to VoiceEvent.WHISPER, "CRY" to VoiceEvent.CRY,
        "LAUGH" to VoiceEvent.LAUGH, "SHOUT" to VoiceEvent.SHOUT, "SIGH" to VoiceEvent.SIGH,
        "MUMBLE" to VoiceEvent.MUMBLE,
    )

    /** 找出文本里第一个平衡的 `{...}`。 */
    private fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return text.substring(start)          // 未闭合：交给 JSON 解析报错
    }

    /** 大小写归一。**不做同义词/中文猜测**（那属于语义）。 */
    private fun <T> mapEnum(raw: String?, table: Map<String, T>, fallback: T, onCaseFix: () -> Unit): T {
        val v = raw?.trim() ?: return fallback
        table[v]?.let { return it }
        val up = v.uppercase()
        table[up]?.let { onCaseFix(); return it }
        return fallback
    }

    private fun mapType(raw: String?): DirectorType = when (raw?.trim()?.uppercase()) {
        "NARRATION" -> DirectorType.NARRATION
        "DIALOGUE" -> DirectorType.DIALOGUE
        else -> DirectorType.UNRECOGNIZED
    }

    private fun readIntensity(obj: JSONObject): Float {
        if (!obj.has("emotion_intensity") || obj.isNull("emotion_intensity")) return Float.NaN
        val v = obj.get("emotion_intensity")
        return when (v) {
            is Number -> v.toFloat()
            is String -> v.trim().toFloatOrNull() ?: Float.NaN
            else -> Float.NaN
        }
    }

    /**
     * evidence 只做"唯一前导 E# 匹配"（`"E0: RULE: xxx"` → `E0`）。
     * **绝不做**"按自然语言找最像的 evidence" —— 那是把语义推断塞回 Normalizer。
     */
    private fun readEvidence(obj: JSONObject, onTrailingText: () -> Unit): List<String> {
        val arr: JSONArray = when (val v = obj.opt("evidence")) {
            is JSONArray -> v
            is String -> JSONArray().put(v)      // 单个字符串也算表示层容错
            else -> return emptyList()
        }
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.opt(i)?.toString()?.trim() ?: continue
            val m = Regex("^(E\\d+)\\b").find(s)
            if (m != null) {
                if (m.value != s) onTrailingText()
                out += m.groupValues[1]
            } else {
                out += s                          // 非法形态原样保留 ⇒ Validator 判 EVIDENCE_ID_INVALID
            }
        }
        return out
    }
}
