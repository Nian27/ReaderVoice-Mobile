package com.readervoice.scheduler

enum class Codec { WAV, OPUS }

/**
 * 渲染产物。asset_id = cache key（audio_assets 主键，§5）。
 * 必须绑定 voice_revision_id（AGENTS 不变量 13）；旧版本由原子替换清理。
 */
data class AudioAsset(
    val assetId: String,                // = cacheKey
    val renderUnitId: String,
    val codec: Codec,
    val relativePath: String,           // audio/seg000001.wav（Book Package §7）
    val bytes: Long,
    val durationMs: Long,
    val voiceRevisionId: String,
    val checksumSha256: String,
    val createdAtEpochMs: Long,
)
