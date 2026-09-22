package com.readervoice.app

/** 上层声明的 backend 期望。 */
enum class QwenBackendMode { AUTO, CPU, NPU_EXPERIMENTAL }

/** 实际下发给 MNN Llm 的 backend 名（必须与 qwen_engine_jni.cpp 的字符串契约一致）。 */
enum class QwenBackend(val nativeName: String) {
    CPU("cpu"), OPENCL("opencl"), HEXAGON("hexagon");

    companion object {
        fun fromNativeName(name: String): QwenBackend? =
            entries.firstOrNull { it.nativeName == name.trim().lowercase() }
    }
}

sealed interface QwenBackendSelection {
    data class Selected(
        val backend: QwenBackend,
        val mode: QwenBackendMode,
        val experimental: Boolean,
        val reason: String,
    ) : QwenBackendSelection

    data class Rejected(val reason: String) : QwenBackendSelection
}

/**
 * backend 选择层。
 *
 * 硬不变量：Hexagon 正确性 Gate 未通过之前，AUTO 永远不得解析出 HEXAGON。
 * 选择结果必须携带 experimental 标记，上层据此拒绝把结果写进 COMMITTED 状态。
 * 未知/无法识别的 native 名一律 fail-closed，不静默回落。
 */
object QwenBackendPolicy {

    /**
     * HTP/Hexagon correctness Gate。未通过前固定 false。
     * 改动此常量必须附真机证据（runs/ 下的 report）并在 DECISIONS 记录。
     */
    const val HEXAGON_CORRECTNESS_PASSED = false

    fun resolve(mode: QwenBackendMode): QwenBackendSelection = when (mode) {
        QwenBackendMode.CPU -> QwenBackendSelection.Selected(
            QwenBackend.CPU, mode, experimental = false,
            reason = "显式请求 CPU correctness 路径"
        )

        QwenBackendMode.AUTO -> if (HEXAGON_CORRECTNESS_PASSED) {
            QwenBackendSelection.Selected(
                QwenBackend.HEXAGON, mode, experimental = false,
                reason = "Hexagon correctness Gate 已通过，AUTO 选 NPU"
            )
        } else {
            QwenBackendSelection.Selected(
                QwenBackend.CPU, mode, experimental = false,
                reason = "Hexagon correctness Gate 未通过 -> AUTO 回落 CPU"
            )
        }

        QwenBackendMode.NPU_EXPERIMENTAL -> QwenBackendSelection.Selected(
            QwenBackend.HEXAGON, mode, experimental = true,
            reason = "显式实验请求；hexagonCorrectnessPassed=false，结果不得进入 COMMITTED"
        )
    }

    /** 真机回报的 backend 名 → 归一化枚举；无法识别返回 null（调用方必须 fail-closed）。 */
    fun normalizeReported(nativeName: String): QwenBackend? = QwenBackend.fromNativeName(nativeName)

    /**
     * 判定一次推理结果是否可被上层信任为 correctness 路径。
     * 条件：非 experimental，且实际 backend 与选择一致。
     */
    fun isTrustworthyForCommit(selection: QwenBackendSelection, reportedNative: String): Boolean {
        if (selection !is QwenBackendSelection.Selected) return false
        if (selection.experimental) return false
        val reported = normalizeReported(reportedNative) ?: return false
        return reported == selection.backend
    }
}
