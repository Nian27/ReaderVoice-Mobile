package com.readervoice.app

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.legado.app.cosy.CosyVoiceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Interactive M2 probe (debug build only). Type any text, tap 合成 to synthesize and hear it.
 * Native sessions are kept warm across taps (released only in onDestroy), so you can change the
 * text and synthesize again freely. Each tap prints total wall time and the per-stage parameters
 * (LLM load/prefill/decode/tokens, Flow resize/inference/sequence, HiFT core/f0/istft/audio/peak/rms)
 * read from the newest run's jsonl reports.
 */
class CosyVoiceM2ProbeActivity : Activity() {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: MediaPlayer? = null
    private var runtime: ReaderVoiceCosyRuntime? = null
    private var profile: VoiceProfileRef? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val input = EditText(this).apply {
            setText("不能和我一起吗？咱们三个过好比什么都重要。")
            setTextSize(18f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val go = Button(this).apply { text = "合成并播放（可反复换文字）" }
        val status = TextView(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextSp(15f)
            setTextColor(Color.rgb(15, 23, 42))
        }
        val scroll = ScrollView(this).apply { addView(status) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(16), dp(32), dp(16), dp(32))
            addView(input)
            addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        go.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) { status.text = "请输入文字"; return@setOnClickListener }
            go.isEnabled = false
            worker.launch {
                val lines = mutableListOf<String>()
                try {
                    if (runtime == null) {
                        runtime = ReaderVoiceCosyRuntime(
                            context = this@CosyVoiceM2ProbeActivity,
                            packageDir = File(filesDir, "m2-cosyvoice-probe"),
                        )
                        profile = VoiceProfileRef(
                            voiceProfileId = CosyVoiceStore.DEFAULT_VOICE_PROFILE_ID,
                            profileHash = "m2-builtin-baseline",
                            voiceRevisionId = "m2-builtin-baseline-r1",
                            realtimeReady = true,
                        )
                    }
                    val rt = runtime!!
                    val pr = profile!!
                    val t0 = System.currentTimeMillis()
                    // Unique cache key per tap so consecutive taps always re-synthesize
                    // (no output-cache short-circuit) - needed to measure warm reuse.
                    val key = "m2-" + t0
                    // Keep only the newest few wavs so the audio dir does not accumulate.
                    runCatching {
                        File(filesDir, "m2-cosyvoice-probe/audio/cosyvoice").listFiles()
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(5)?.forEach { it.delete() }
                    }
                    val result = rt.synthesize(
                        route = SpeechRoute(
                            engine = SpeechEngineId.COSYVOICE3_MNN,
                            profile = pr,
                            reason = "M2 probe tap",
                        ),
                        renderUnit = RenderUnitSynthesisInput(
                            id = key,
                            normalizedText = text,
                            instructionMode = "ZERO_SHOT",
                            instruction = "",
                            cacheKey = key,
                            sampleRate = CosyVoiceStore.SAMPLE_RATE,
                            speed = 1f,
                        ),
                    )
                    val ms = System.currentTimeMillis() - t0
                    when (result) {
                        is SynthesisResult.Completed -> {
                            lines += "[完成] 总耗时 " + ms + "ms " + result.relativeWavPath
                            lines += ""
                            lines += "--- 各阶段参数（最新 run jsonl）---"
                            lines += stageDumps()
                            // Real file name is cosy-<cacheKey>.wav (CosyVoiceCachePath adds the prefix).
                            val wav = File(filesDir, "m2-cosyvoice-probe").resolve(result.relativeWavPath)
                            if (wav.isFile && wav.length() > 44L) {
                                lines += ""
                                lines += "播放: " + wav.absolutePath
                                runOnUiThread { playWav(wav) }
                            }
                        }
                        is SynthesisResult.Unavailable -> lines += "[不可用] " + ms + "ms " + result.reason
                        is SynthesisResult.Failed -> lines += "[失败] " + ms + "ms " + result.reason
                    }
                } catch (e: Throwable) {
                    lines += "[异常] " + (e.message ?: e.javaClass.simpleName)
                }
                runOnUiThread {
                    status.text = lines.joinToString("\n")
                    go.isEnabled = true
                }
            }
        }
    }

    // Conservative: plain concatenation (no Swift-style :0f), null-safe JSON reads.
    private fun stageDumps(): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val workDir = File(applicationContext.cacheDir, "cosyvoice3-mnn-work")
            val newest = workDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("run-") }
                ?.maxByOrNull { it.name } ?: return listOf("  (无 run 目录)")
            val llmF = File(newest, "llm-output/llm-persistent.jsonl")
            if (llmF.isFile) {
                runCatching {
                    val j = JSONObject(llmF.readLines().firstOrNull() ?: "{}")
                    out.add("  LLM: load=" + j.optDouble("loadMs", 0.0).toInt() + "ms prefill=" + j.optDouble("prefillMs", 0.0).toInt() + "ms decode=" + j.optDouble("decodeMs", 0.0).toInt() + "ms")
                    out.add("        tok/s=" + j.optDouble("tokensPerSecond", 0.0) + " tokens=" + j.optInt("speechTokens", 0))
                }
            }
            val flowF = File(newest, "flow-report.jsonl")
            if (flowF.isFile) {
                runCatching {
                    val j = JSONObject(flowF.readLines().firstOrNull() ?: "{}")
                    out.add("  Flow: resize=" + j.optDouble("resizeMs", 0.0).toInt() + "ms infer=" + j.optDouble("inferenceMs", 0.0).toInt() + "ms")
                    out.add("        seq=" + j.optInt("sequenceLength", 0) + "->" + j.optInt("runtimeSequenceLength", 0) + " frames=" + j.optInt("targetFrames", 0))
                }
            }
            val hiftF = File(newest, "hift-report.jsonl")
            if (hiftF.isFile) {
                runCatching {
                    val j = JSONObject(hiftF.readLines().firstOrNull() ?: "{}")
                    out.add("  HiFT: core=" + j.optDouble("coreMs", 0.0).toInt() + "ms f0=" + j.optDouble("f0Ms", 0.0).toInt() + "ms istft=" + j.optDouble("istftMs", 0.0).toInt() + "ms")
                    out.add("        audio=" + j.optDouble("audioSeconds", 0.0) + "s peak=" + j.optDouble("pcmPeak", 0.0) + " rms=" + j.optDouble("pcmRms", 0.0))
                }
            }
        }
        return out
    }

    private fun playWav(file: File) {
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        }.onFailure { Log.e("CosyVoiceM2Probe", "播放失败: " + it.message, it) }
    }

    override fun onDestroy() {
        worker.cancel()
        player?.release()
        player = null
        // Release all native sessions (LLM/Flow/HiFT weights leave RSS) only when the probe dies.
        runCatching { runBlocking { io.legado.app.cosy.CosyVoiceRuntime.close() } }
        super.onDestroy()
    }
}
