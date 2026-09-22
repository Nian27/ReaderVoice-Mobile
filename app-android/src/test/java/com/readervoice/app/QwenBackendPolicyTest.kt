package com.readervoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenBackendPolicyTest {

    @Test
    fun autoNeverResolvesToHexagonWhileGateOpen() {
        assertFalse("Gate 常量必须保持 false 直到有真机证据", QwenBackendPolicy.HEXAGON_CORRECTNESS_PASSED)
        val sel = QwenBackendPolicy.resolve(QwenBackendMode.AUTO)
        assertTrue(sel is QwenBackendSelection.Selected)
        sel as QwenBackendSelection.Selected
        assertEquals(QwenBackend.CPU, sel.backend)
        assertFalse(sel.experimental)
    }

    @Test
    fun explicitCpuIsCorrectnessPath() {
        val sel = QwenBackendPolicy.resolve(QwenBackendMode.CPU) as QwenBackendSelection.Selected
        assertEquals(QwenBackend.CPU, sel.backend)
        assertFalse(sel.experimental)
    }

    @Test
    fun npuExperimentalIsMarkedExperimental() {
        val sel = QwenBackendPolicy.resolve(QwenBackendMode.NPU_EXPERIMENTAL) as QwenBackendSelection.Selected
        assertEquals(QwenBackend.HEXAGON, sel.backend)
        assertTrue(sel.experimental)
    }

    @Test
    fun commitTrustRequiresNonExperimentalAndMatchingBackend() {
        val cpu = QwenBackendPolicy.resolve(QwenBackendMode.CPU)
        assertTrue(QwenBackendPolicy.isTrustworthyForCommit(cpu, "cpu"))
        assertFalse("实际跑在 hexagon 就不是 CPU correctness 结果", QwenBackendPolicy.isTrustworthyForCommit(cpu, "hexagon"))

        val exp = QwenBackendPolicy.resolve(QwenBackendMode.NPU_EXPERIMENTAL)
        assertFalse("实验 backend 结果不得进入 COMMITTED", QwenBackendPolicy.isTrustworthyForCommit(exp, "hexagon"))
    }

    @Test
    fun unknownReportedBackendFailsClosed() {
        val cpu = QwenBackendPolicy.resolve(QwenBackendMode.CPU)
        assertFalse(QwenBackendPolicy.isTrustworthyForCommit(cpu, "vulkan"))
        assertFalse(QwenBackendPolicy.isTrustworthyForCommit(cpu, ""))
    }

    @Test
    fun normalizeIsCaseInsensitiveAndTrimmed() {
        assertEquals(QwenBackend.HEXAGON, QwenBackendPolicy.normalizeReported("  Hexagon "))
        assertEquals(QwenBackend.OPENCL, QwenBackendPolicy.normalizeReported("opencl"))
    }
}
