package com.readervoice.app

import android.content.Context
import io.legado.app.cosy.CosyVoiceHardwarePlan
import io.legado.app.cosy.CosyVoiceInferenceMode
import io.legado.app.cosy.CosyVoiceStore
import java.io.File

/**
 * Product-facing adapter around the verified CosyVoice MNN chain.
 * Native declarations intentionally live in io.legado.app.cosy because the shipped JNI ABI
 * exports that package name; no legacy package leaks into RenderUnit or Book Package data.
 */
class ReaderVoiceCosyRuntime(
    context: Context,
    private val packageDir: File,
) : CosyVoiceRuntime {
    private val store = CosyVoiceStore(context.applicationContext)

    init {
        io.legado.app.cosy.CosyVoiceRuntime.initialize(context.applicationContext)
    }

    override suspend fun synthesize(
        route: SpeechRoute,
        renderUnit: RenderUnitSynthesisInput,
    ): SynthesisResult {
        if (route.engine != SpeechEngineId.COSYVOICE3_MNN) {
            return SynthesisResult.Failed("CosyVoice received a non-Cosy route")
        }
        val model = store.modelStatus()
        if (!model.ready) {
            return SynthesisResult.Unavailable(
                "CosyVoice 模型未安装：${model.missingFiles.joinToString()}",
            )
        }
        val fileName = CosyVoiceCachePath.fileNameFor(renderUnit.cacheKey)
            ?: return SynthesisResult.Failed("非法 CosyVoice cache key")
        val output = File(File(packageDir, "audio/cosyvoice"), fileName)
        if (output.isFile && output.length() > 44L) {
            return SynthesisResult.Completed(
                relativeWavPath = output.relativeTo(packageDir).invariantSeparatorsPath,
                cacheKey = renderUnit.cacheKey,
                voiceRevisionId = route.profile.voiceRevisionId,
                sampleRate = CosyVoiceStore.SAMPLE_RATE,
            )
        }
        val options = runCatching {
            val mode = if (
                renderUnit.instructionMode == "INSTRUCT2" && renderUnit.instruction.isNotBlank()
            ) CosyVoiceInferenceMode.INSTRUCT2 else CosyVoiceInferenceMode.ZERO_SHOT
            io.legado.app.cosy.CosyVoiceSynthesisOptions(
                hardwarePlan = CPU_ONLY_PLAN,
                voiceProfileId = route.profile.voiceProfileId,
                inferenceMode = mode,
                instruction = if (mode == CosyVoiceInferenceMode.INSTRUCT2) renderUnit.instruction else "",
            )
        }.getOrElse { return SynthesisResult.Failed(it.message ?: "CosyVoice option invalid") }

        return runCatching {
            io.legado.app.cosy.CosyVoiceRuntime.synthesize(
                store = store,
                text = renderUnit.normalizedText,
                output = output,
                options = options,
            )
            SynthesisResult.Completed(
                relativeWavPath = output.relativeTo(packageDir).invariantSeparatorsPath,
                cacheKey = renderUnit.cacheKey,
                voiceRevisionId = route.profile.voiceRevisionId,
                sampleRate = CosyVoiceStore.SAMPLE_RATE,
            )
        }.getOrElse { error ->
            SynthesisResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        // OpenCL flow is available on this device: MNN_OPENCL=ON (SEP build ships CL in
        // libMNN_CL.so) and the Adreno driver exposes /vendor/lib64/libOpenCL.so. The earlier
        // "OpenCL=OFF / Android17 driver" diagnosis was wrong: extractNativeLibs=false left
        // nativeLibraryDir empty so dlopen(libMNN_CL.so) failed. useLegacyPackaging=true (in
        // build.gradle.kts) fixed that, so OpenCL flow is now reachable.
        private val CPU_ONLY_PLAN = CosyVoiceHardwarePlan(
            flowBackend = "opencl",
            flowGpuMode = 68,
            llmBackend = "cpu",
            // A/B result (2026-08-25): HiFT core on OpenCL HANGS after inference on this
            // device (report written but wav never produced, native thread stuck in CL
            // cleanup) -> HiFT stays on CPU 6 threads. Flow OpenCL is fine.
            hiftCoreBackend = "cpu",
            hiftGpuMode = 4,
            cpuThreads = 6,
            decision = "M2: Flow OpenCL + LLM/HiFT CPU (HiFT OpenCL hangs, A/B failed)",
            npuStatus = "not selected by this extraction gate",
        )
    }
}

/** Cache keys must remain a single safe filename component inside the current Book Package. */
object CosyVoiceCachePath {
    private val validKey = Regex("[A-Za-z0-9._-]{1,128}")

    fun fileNameFor(cacheKey: String): String? =
        cacheKey.takeIf(validKey::matches)?.let { "cosy-$it.wav" }
}
