package com.readervoice.scheduler

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 测试工具：临时 DB + fake renderer */
object TestSupport {

    fun tempDbPath(): Path = Files.createTempDirectory("sched-test").resolve("book.db")

    fun unit(
        id: String,
        text: String = "测试文本",
        profileHash: String = "ph-1",
        instruction: String = "请平静地说。",
        voiceRevisionId: String = "rev-1",
        ttsModel: String = "cv3-mnn-v1",
    ) = RenderUnitRef(
        id = id,
        segmentType = SegmentType.DIALOGUE,
        textNormalized = text,
        voiceProfileId = "vp_1",
        profileHash = profileHash,
        instructionMode = "INSTRUCT2",
        instruction = instruction,
        ttsModel = ttsModel,
        speed = 1.0f,
        sampleRate = 24000,
        voiceRevisionId = voiceRevisionId,
    )

    fun sha256(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 可配置 fake renderer：
 * - failIds: 这些 unit 永远失败
 * - failTimes: unit id -> 需要失败多少次后才成功
 * - renderedOrder: 渲染顺序记录（线程安全）
 * - delayMs: 每次渲染模拟耗时
 */
class FakeRenderer(
    val failIds: Set<String> = emptySet(),
    val failTimes: Map<String, Int> = emptyMap(),
    val delayMs: Long = 1L,
    val renderedOrder: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
) : Renderer {
    private val attemptCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    override val tag: String = "fake"

    override suspend fun render(unit: RenderUnitRef, cancel: () -> Boolean): RenderResult {
        renderedOrder.add(unit.id)
        kotlinx.coroutines.delay(delayMs)
        if (cancel()) return RenderResult.Failure("cancelled")
        if (unit.id in failIds) return RenderResult.Failure("always-fail")
        val attempts = attemptCount.merge(unit.id, 1, Int::plus)!!
        val maxFails = failTimes[unit.id] ?: 0
        if (attempts <= maxFails) return RenderResult.Failure("transient-" + attempts)
        val content = unit.id + "|" + unit.cacheKey
        return RenderResult.Success(
            codec = Codec.WAV,
            relativePath = "audio/" + unit.id + ".wav",
            bytes = content.length.toLong(),
            durationMs = 1000L,
            checksumSha256 = TestSupport.sha256(content),
        )
    }
}

/** 轮询等待条件成立（带超时） */
suspend fun waitUntil(timeoutMs: Long = 5000L, intervalMs: Long = 10L, cond: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (cond()) return
        kotlinx.coroutines.delay(intervalMs)
    }
    throw AssertionError("waitUntil timeout")
}
