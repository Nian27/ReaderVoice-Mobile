package com.readervoice.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** A cache entry is a local artifact, never a request to invoke TTS on the UI thread. */
data class CachedAudioAsset(
    val relativePath: String,
    val bytes: Long,
)

object AudioCacheScanner {
    fun scan(packageDir: File): List<CachedAudioAsset> {
        val audioRoot = File(packageDir, "audio")
        if (!audioRoot.isDirectory) return emptyList()
        val canonicalRoot = audioRoot.canonicalFile
        return audioRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
            .mapNotNull { file ->
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) null
                else CachedAudioAsset(
                    relativePath = canonical.relativeTo(packageDir.canonicalFile).invariantSeparatorsPath,
                    bytes = canonical.length(),
                )
            }
            .sortedBy { it.relativePath }
            .toList()
    }
}

/** M2 consumes cached WAV only; TTS enqueueing remains a later persistent-scheduler task. */
class CachedAudioPlayer(
    private val context: Context,
    private val packageDir: File,
    private val onStatus: (String) -> Unit,
) {
    private var player: MediaPlayer? = null
    private var queue: List<CachedAudioAsset> = emptyList()
    private var currentIndex = 0

    fun play(assets: List<CachedAudioAsset>) {
        release()
        queue = assets
        currentIndex = 0
        if (queue.isEmpty()) {
            onStatus("没有已缓存的 WAV；待生成任务尚未接入。")
            return
        }
        playCurrent()
    }

    fun release() {
        player?.release()
        player = null
        queue = emptyList()
    }

    private fun playCurrent() {
        if (currentIndex >= queue.size) {
            release()
            onStatus("缓存队列播放完成。")
            return
        }
        val asset = queue[currentIndex]
        val file = File(packageDir, asset.relativePath)
        if (!file.isFile) {
            currentIndex++
            playCurrent()
            return
        }
        onStatus("正在播放缓存音频 ${currentIndex + 1}/${queue.size}")
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                it.release()
                player = null
                currentIndex++
                playCurrent()
            }
            setOnErrorListener { failed, _, _ ->
                failed.release()
                player = null
                currentIndex++
                playCurrent()
                true
            }
            setDataSource(file.absolutePath)
            prepareAsync()
        }
    }
}
