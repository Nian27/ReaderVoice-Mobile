package com.readervoice.scheduler

/**
 * 引擎无关渲染器 SPI（MOBILE-000D 的 CosyVoice/Audio8 适配器实现此接口）。
 * 协作式取消契约（AGENTS 不变量 10）：render 实现必须定期检查 cancel()，
 * 返回 true 时应尽快抛 CancellationException 或返回失败，禁止杀线程。
 */
interface Renderer {
    val tag: String

    /** @param unit   待渲染的 RenderUnit 投影
     *  @param cancel 协作式取消标志（true = 请求停止）
     *  @return 渲染结果；Success 必须携带产物元数据
     */
    suspend fun render(unit: RenderUnitRef, cancel: () -> Boolean): RenderResult
}

sealed interface RenderResult {
    data class Success(
        val codec: Codec,
        val relativePath: String,
        val bytes: Long,
        val durationMs: Long,
        val checksumSha256: String,
    ) : RenderResult

    data class Failure(val error: String) : RenderResult
}
