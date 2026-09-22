package com.readervoice.scheduler

import java.security.MessageDigest

enum class SegmentType { NARRATION, DIALOGUE }

/**
 * RenderUnit v1 的调度侧投影（RENDERUNIT_V1_SCHEMA §1 9 字段的子集，纯数据）。
 * 引擎无关：不含任何 TTS 私有字段（R3）。
 *
 * cache.key 公式（§5 冻结）：
 * sha256(tts_config.model + "|" + character.profile_hash + "|" + render_instruction.mode
 *   + "|" + normalizedInstruction + "|" + tts_config.speed + "|" + tts_config.sample_rate
 *   + "|" + text.normalized)
 */
data class RenderUnitRef(
    val id: String,                     // book_a:ch_003:seg_00023
    val segmentType: SegmentType,
    val textNormalized: String,
    val voiceProfileId: String,
    val profileHash: String,            // 档案内容 sha256
    val instructionMode: String,        // INSTRUCT2 | ZERO_SHOT
    val instruction: String,            // 归一化指令文本（ZERO_SHOT 时为空）
    val ttsModel: String,               // 引擎+模型版本，如 cv3-mnn-v1
    val speed: Float,
    val sampleRate: Int,
    val voiceRevisionId: String,        // AudioAsset 必须绑定（AGENTS 不变量 13）
) {
    val cacheKey: String by lazy { computeCacheKey() }

    private fun computeCacheKey(): String {
        val raw = ttsModel + "|" + profileHash + "|" + instructionMode + "|" + instruction + "|" + speed + "|" + sampleRate + "|" + textNormalized
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
