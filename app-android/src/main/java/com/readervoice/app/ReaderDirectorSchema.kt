package com.readervoice.app

import org.json.JSONObject

/**
 * ReaderDirector 结构化输出的 host 校验器（第一版冻结 schema）。
 *
 * 核心：模型自由文本一律 fail-closed，解析失败必须给出明确 reason，
 * 不允许只报"生成失败"。容忍模型吐 ```json 包裹 / 前后缀杂讯。
 */
object ReaderDirectorSchema {

    data class Delivery(
        val pace: String = "normal",
        val volume: String = "normal",
        val tone: String = "neutral",
    )

    data class DirectorResult(
        val speaker: String?,      // null = 叙述或无法判断
        val type: String,          // narration | dialogue
        val text: String,          // 真正朗读文本
        val emotion: String,       // tired/sad/angry/.../neutral
        val emotionIntensity: Double, // 0.0..1.0
        val delivery: Delivery,
        val voiceEvent: String,    // whisper/cry/laugh/shout/normal
        val raw: String,           // 模型原始输出（审计用）
    )

    sealed class ParseOutcome {
        data class Success(val result: DirectorResult) : ParseOutcome()
        data class Failure(val reason: String, val raw: String) : ParseOutcome()
    }

    /** 从模型原始输出中提取第一个 JSON 对象（容忍 ```json 包裹与前后缀杂讯）。 */
    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val start = trimmed.indexOf('{');
        if (start < 0) return null
        // 从第一个 '{' 开始做括号匹配，找配对 '}'（考虑字符串内的花括号）
        var depth = 0;
        var inString = false;
        var escaped = false;
        for (i in start until trimmed.length) {
            val c = trimmed[i];
            if (inString) {
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (c == '"') { inString = false; continue }
                continue;
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return trimmed.substring(start, i + 1) }
            }
        }
        return null;
    }

    private fun jsonString(obj: JSONObject, key: String): String? =
        if (obj.has(key) && !obj.isNull(key)) obj.optString(key) else null

    /** 解析并校验。失败返回 Failure 带原因。 */
    fun parse(raw: String): ParseOutcome {
        val trimmed = raw.trim();
        if (trimmed.isEmpty()) return ParseOutcome.Failure("模型输出为空", raw);
        val jsonStr = extractJsonObject(trimmed)
            ?: return ParseOutcome.Failure("输出中没有找到 JSON 对象（无配对 {}）", raw);
        val obj = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return ParseOutcome.Failure("JSON 解析失败: ${e.message}", raw);
        }

        // 必填字段校验，缺失即 fail-closed 并给原因
        val type = jsonString(obj, "type")
        if (type == null) return ParseOutcome.Failure("missing field \"type\"", raw);
        if (type != "narration" && type != "dialogue")
            return ParseOutcome.Failure("type 取值非法: $type（应为 narration|dialogue）", raw);

        val text = jsonString(obj, "text")
        if (text == null || text.isBlank()) return ParseOutcome.Failure("missing field \"text\"", raw);

        val speaker = jsonString(obj, "speaker");
        val emotion = jsonString(obj, "emotion") ?: "neutral";
        val voiceEvent = jsonString(obj, "voice_event") ?: "normal";

        val intensity = try {
            if (obj.has("emotion_intensity") && !obj.isNull("emotion_intensity")) obj.optDouble("emotion_intensity") else 0.0
        } catch (e: Exception) { 0.0 };

        val deliveryObj = obj.optJSONObject("delivery");
        val delivery = if (deliveryObj != null) {
            Delivery(
                pace = jsonString(deliveryObj, "pace") ?: "normal",
                volume = jsonString(deliveryObj, "volume") ?: "normal",
                tone = jsonString(deliveryObj, "tone") ?: "neutral",
            )
        } else {
            Delivery()
        };

        return ParseOutcome.Success(
            DirectorResult(
                speaker = speaker,
                type = type,
                text = text,
                emotion = emotion,
                emotionIntensity = intensity,
                delivery = delivery,
                voiceEvent = voiceEvent,
                raw = raw,
            )
        );
    }
}
