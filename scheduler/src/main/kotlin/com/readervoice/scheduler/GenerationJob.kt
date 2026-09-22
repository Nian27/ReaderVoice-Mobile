package com.readervoice.scheduler

/**
 * 一条渲染任务。与 render_unit 一对一（render_unit_id UNIQUE）。
 */
data class GenerationJob(
    val jobId: String,
    val renderUnitId: String,
    val cacheKey: String,
    val priority: Priority,
    val state: JobState,
    val attempts: Int,
    val maxAttempts: Int = 3,
    val renderer: String = "",          // 引擎 tag；空 = 未分配（由 Router/000D 指定）
    val lastError: String? = null,
    val backoffUntilEpochMs: Long = 0L,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun withState(s: JobState, now: Long): GenerationJob =
        copy(state = s, updatedAtEpochMs = now)
}
