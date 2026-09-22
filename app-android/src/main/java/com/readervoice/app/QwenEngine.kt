package com.readervoice.app

/**
 * 有状态 QwenEngine service：一次 load，多次 generate；backend 可插拔。
 * 上层（ReaderDirector）永远不感知 backend 是 CPU / OpenCL / Hexagon。
 */
class QwenEngine {
    private var nativePtr: Long = 0

    init {
        System.loadLibrary("qwen_engine_jni")
        nativePtr = nativeCreate()
    }

    /** modelDir 必须以 File.separator 结尾（MNN 目录形式 createLLM 直接拼接文件名）。 */
    fun load(modelDir: String, backend: String = "cpu"): Boolean {
        check(nativePtr != 0L) { "QwenEngine already released" }
        return nativeLoad(nativePtr, modelDir, backend)
    }

    /** P16-APP: 让 native 侧把 logits/input_embeds 转储到该目录（App files/），用于 App↔shell 对拍。 */
    fun setDumpDir(dir: String) {
        if (nativePtr != 0L) nativeSetDumpDir(dir)
    }

    /** P16-APP: App 内性能剖析（host 逐 op 计时 + DSP/perf 文件落盘）。必须在 load() 之前调用。 */
    fun setProfiling(on: Boolean) {
        if (nativePtr != 0L) nativeSetProfiling(on)
    }

    /** 协作式取消：设置停止标记，不杀线程。 */
    fun cancel() {
        if (nativePtr != 0L) nativeCancel(nativePtr)
    }

    fun reset() {
        if (nativePtr != 0L) nativeReset(nativePtr)
    }

    fun generate(prompt: String, maxTokens: Int = 64): String {
        check(nativePtr != 0L) { "QwenEngine already released" }
        return nativeGenerate(nativePtr, prompt, maxTokens)
    }

    /**
     * MOBILE-005 / M3：**带 deadline 的生成**。
     *
     * 与 `generate` 的区别：native 侧在每个 token 之间检查 wall-clock deadline，
     * 超时就 `requestCancel()` 让 AR 循环在下一个 token 边界退出（而不是等整轮跑完）。
     * 返回类型化结果，供上层走状态机（CANCEL_REQUESTED → CANCELLED → NO_OUTPUT → ENGINE_STUCK）。
     */
    sealed class GenOutcome {
        data class Ok(val text: String) : GenOutcome()
        /** 超过 deadline；native 已被请求取消。 */
        object Timeout : GenOutcome()
        /** 外部取消（跳章 / epoch 变化）。 */
        object Canceled : GenOutcome()
        object NotLoaded : GenOutcome()
    }

    fun generateDeadline(prompt: String, maxTokens: Int, timeoutMs: Long): GenOutcome {
        if (nativePtr == 0L) return GenOutcome.NotLoaded
        val r = nativeGenerateDeadline(nativePtr, prompt, maxTokens, timeoutMs)
        return when {
            r == null -> GenOutcome.NotLoaded
            r.startsWith("OK:") -> GenOutcome.Ok(r.substring(3))
            r == "TIMEOUT" -> GenOutcome.Timeout
            r == "CANCELED" -> GenOutcome.Canceled
            else -> GenOutcome.NotLoaded
        }
    }

    /** 立刻请求 native 取消（置引擎状态，AR 循环下一 token 边界退出）。 */
    fun cancelGeneration() {
        if (nativePtr != 0L) nativeCancelGeneration(nativePtr)
    }

    fun getBackend(): String =
        if (nativePtr != 0L) nativeGetBackend(nativePtr) else "unknown"

    fun getMetrics(): String =
        if (nativePtr != 0L) nativeGetMetrics(nativePtr) else "{}"

    fun release() {
        if (nativePtr != 0L) {
            nativeRelease(nativePtr)
            nativePtr = 0
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun nativeCreate(): Long
    private external fun nativeRelease(ptr: Long)
    private external fun nativeLoad(ptr: Long, modelDir: String, backend: String): Boolean
    private external fun nativeSetDumpDir(dir: String)
    private external fun nativeSetProfiling(on: Boolean)
    private external fun nativeCancel(ptr: Long)
    private external fun nativeCancelGeneration(ptr: Long)
    private external fun nativeGenerateDeadline(ptr: Long, prompt: String, maxTokens: Int, timeoutMs: Long): String?
    private external fun nativeReset(ptr: Long)
    private external fun nativeGenerate(ptr: Long, prompt: String, maxTokens: Int): String
    private external fun nativeGetBackend(ptr: Long): String
    private external fun nativeGetMetrics(ptr: Long): String
}
