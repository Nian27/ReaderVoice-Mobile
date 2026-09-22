package com.voicedesign.app

class VoiceDesignEngine {

    companion object {
        @Volatile private var adspReady = false

        /**
         * ADSP_LIBRARY_PATH 必须包含厂商 rfsa 路径，且必须在任何 MNN 库被 dlopen 之前设置。
         * 依据：MNN source/backend/hexagon/README.md 与官方 MnnLlmChat 的 QnnModule.kt。
         */
        fun prepareAdsp(nativeLibDir: String) {
            if (adspReady) return
            val dirs = listOf(nativeLibDir, "/vendor/lib/rfsa/adsp", "/system/lib/rfsa/adsp")
                .filter { it.isNotBlank() }.joinToString(";")
            try { android.system.Os.setenv("ADSP_LIBRARY_PATH", dirs, true) } catch (_: Throwable) {}
            adspReady = true
        }
    }

    private var nativePtr: Long = 0

    init {
        System.loadLibrary("voicedesign_jni")
        nativePtr = nativeCreate()
    }

    fun release() { if (nativePtr != 0L) { nativeRelease(nativePtr); nativePtr = 0L } }

    fun lastError(): String = if (nativePtr != 0L) nativeLastError(nativePtr) else "engine released"

    fun load(modelDir: String, backend: String, nativeLibDir: String = "", maxPos: Int = 384): Boolean {
        check(nativePtr != 0L) { "VoiceDesignEngine already released" }
        return nativeLoad(nativePtr, modelDir, backend, maxPos, nativeLibDir)
    }

    /**
     * 输入文字生成音色。instruct=音色描述，text=要说的内容。
     * 两者都为空时回退到预置的 prompt_emb.f32。lang 用 codec_language_id（中文=2055）。
     */
    fun run(modelDir: String, outWavPath: String, maxFrames: Int = 300,
            instruct: String = "", text: String = "", lang: Int = 2055): String {
        check(nativePtr != 0L) { "VoiceDesignEngine already released" }
        return nativeRun(nativePtr, modelDir, outWavPath, maxFrames, instruct, text, lang)
    }

    private external fun nativeCreate(): Long
    private external fun nativeRelease(ptr: Long)
    private external fun nativeLastError(ptr: Long): String
    private external fun nativeLoad(ptr: Long, modelDir: String, backend: String, maxPos: Int, nativeLibDir: String): Boolean
    private external fun nativeRun(ptr: Long, modelDir: String, outWavPath: String, maxFrames: Int,
                                    instruct: String, text: String, lang: Int): String
}
