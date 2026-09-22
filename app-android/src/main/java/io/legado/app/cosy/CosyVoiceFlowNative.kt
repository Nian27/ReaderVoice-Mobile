package io.legado.app.cosy

import java.io.File

internal object CosyVoiceFlowNative {
    @Volatile private var loaded = false

    @Synchronized
    fun registerOpenCl(libDir: String): Boolean {
        if (!loaded) {
            System.loadLibrary("cosy_flow_jni")
            loaded = true
        }
        return runCatching { registerOpenClNative(libDir) }.getOrDefault(false)
    }

    private external fun registerOpenClNative(libDir: String): Boolean

    external fun run(
        modelPath: String,
        manifestPath: String,
        backend: String,
        precision: String,
        threads: Int,
        reportPath: String,
        cachePath: String
    ): Int

    external fun reset()
}