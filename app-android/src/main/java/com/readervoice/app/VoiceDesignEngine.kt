package com.readervoice.app

/**
 * VoiceDesign HTP 全链 native 封装。
 *
 * 链：prompt_emb（离线 tokenize 的提示词 embedding）-> GraphB x L（prefill）
 *     -> codec_head 采样 code0 -> codepred（cached AR）code1..15 -> frame_emb -> GraphB step
 *     -> EOS -> tokenizer decoder -> reference.wav
 *
 * backend 由 QwenBackendPolicy 决定；NPU 属 experimental（hexagonCorrectnessPassed=false）。
 */
class VoiceDesignEngine {

    private var nativePtr: Long = 0

    init {
        System.loadLibrary("voicedesign_jni")
        nativePtr = nativeCreate()
    }

    fun release() {
        if (nativePtr != 0L) { nativeRelease(nativePtr); nativePtr = 0L }
    }

    fun lastError(): String = if (nativePtr != 0L) nativeLastError(nativePtr) else "engine released"

    /** modelDir 必须以 '/' 结尾；maxPos 为 talker rope 表覆盖的最大位置数。 */
    fun load(modelDir: String, backend: String, nativeLibDir: String = "", maxPos: Int = 384): Boolean {
        check(nativePtr != 0L) { "VoiceDesignEngine already released" }
        return nativeLoad(nativePtr, modelDir, backend, maxPos, nativeLibDir)
    }

    /** 运行整链并写出 WAV；返回可读状态字符串。 */
    fun run(modelDir: String, outWavPath: String, maxFrames: Int = 300): String {
        check(nativePtr != 0L) { "VoiceDesignEngine already released" }
        return nativeRun(nativePtr, modelDir, outWavPath, maxFrames)
    }

    private external fun nativeCreate(): Long
    private external fun nativeRelease(ptr: Long)
    private external fun nativeLastError(ptr: Long): String
    private external fun nativeLoad(ptr: Long, modelDir: String, backend: String, maxPos: Int, nativeLibDir: String): Boolean
    private external fun nativeRun(ptr: Long, modelDir: String, outWavPath: String, maxFrames: Int): String
}
