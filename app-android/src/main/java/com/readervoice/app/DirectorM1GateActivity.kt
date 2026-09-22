package com.readervoice.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Internal shared-MNN-runtime gate; it does not create a Book Package or persist model output. */
class DirectorM1GateActivity : Activity() {
    private external fun runDirectorGate(modelDir: String, prompt: String, backend: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setTextSp(14f)
            setTextColor(Color.rgb(15, 23, 42))
            text = "Director M1：正在验证共同 MNN runtime…"
        }
        setContentView(status)
        val backend = intent.getStringExtra(EXTRA_BACKEND) ?: "cpu"
        Thread {
            val modelDir = File(getExternalFilesDir(null), "director-model").apply { mkdirs() }
            val result = runCatching {
                runDirectorGate(
                    // MNN's directory-form createLLM API concatenates filenames directly.
                    // Keep the trailing separator so it resolves director-model/llm_config.json.
                    modelDir = modelDir.absolutePath + File.separator,
                    prompt = "只输出：V=NONE; E=null",
                    backend = backend,
                )
            }.getOrElse { "EXCEPTION ${it.javaClass.simpleName}: ${it.message}" }
            try {
                File(getExternalFilesDir(null), "gate_result.txt").writeText(result)
            } catch (e: Exception) {}
            runOnUiThread { status.text = result }
        }.start()
    }

    companion object {
        const val EXTRA_BACKEND = "backend"

        init {
            System.loadLibrary("readervoice_director_jni")
        }
    }
}