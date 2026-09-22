package com.readervoice.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线音频生产调度器核心（MOBILE-000C）。
 *
 * 数据流：plan(units) → job(PLANNED) → QUEUED → worker 认领 → GENERATING
 *   → renderer.render → completeJob（原子替换资产）/ failJob（指数退避重试）→ READY/FAILED
 *
 * 优先级（冻结）：CURRENT_PLAYBACK > NEXT_UP > CURRENT_CHAPTER > NEXT_CHAPTER > BACKGROUND。
 * 失效（§5 字段级）：plan() 检测 unit 内容变化 → 旧 job 标 STALE → 以新 cache_key 重建；
 * 资产在重生成成功后原子替换，禁整章/整本清缓存。
 * 协作式取消（AGENTS 10）：requestCancel 置位，renderer 通过 cancel() 感知，禁杀线程。
 */
class Scheduler(
    private val db: SchedulerDb,
    private val renderers: Map<String, Renderer>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoffBaseMs: Long = 1_000L,
    private val idlePollMs: Long = 100L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val supervisor: kotlinx.coroutines.Job = scope.coroutineContext[kotlinx.coroutines.Job] ?: SupervisorJob()

    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val workersStarted = AtomicBoolean(false)

    /** 计划输入：unit + 其调度优先级（播放位置映射，M3 由播放器驱动） */
    data class PlannedUnit(val unit: RenderUnitRef, val priority: Priority)

    /**
     * 幂等规划：写入 unit；cache 命中（同 key + 同 voice_revision）不建 job；
     * 内容变化 → 旧 job 标 STALE 后重建；FAILED 显式重规划。
     */
    fun plan(units: List<PlannedUnit>) {
        val now = clock()
        for (p in units) {
            val changed = db.upsertUnit(p.unit, now)
            if (db.hasAsset(p.unit.id, p.unit.cacheKey, p.unit.voiceRevisionId)) {
                continue // 缓存命中，无需 job
            }
            val existing = db.getJobByUnit(p.unit.id)
            if (changed && existing != null && existing.state != JobState.GENERATING) {
                db.markStale(listOf(p.unit.id), now)
            }
            val job = GenerationJob(
                jobId = "job_" + p.unit.id,
                renderUnitId = p.unit.id,
                cacheKey = p.unit.cacheKey,
                priority = p.priority,
                state = JobState.PLANNED,
                attempts = 0,
                maxAttempts = 3,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            db.upsertJob(job)
        }
    }

    /** 显式失效（管线事件，如角色档案重绑定）：标 STALE，下次 plan() 重建 */
    fun invalidate(unitIds: List<String>) {
        db.markStale(unitIds, clock())
    }

    /** 启动 worker：每个 renderer 一个独立循环（引擎可各自限流） */
    fun start() {
        if (!workersStarted.compareAndSet(false, true)) return
        for (tag in renderers.keys) {
            scope.launch { workerLoop(tag) }
        }
    }

    fun requestCancel(jobId: String) {
        cancelFlags[jobId]?.set(true)
    }

    private suspend fun workerLoop(tag: String) {
        val renderer = renderers.getValue(tag)
        while (scope.coroutineContext.isActive) {
            val now = clock()
            db.promotePlanned(now)
            val job = db.claimNextJob(now, tag)
            if (job == null) {
                delay(idlePollMs)
                continue
            }
            val flag = AtomicBoolean(false)
            cancelFlags[job.jobId] = flag
            try {
                val unit = db.getUnit(job.renderUnitId)
                if (unit == null) {
                    db.failJob(job, "render_unit missing", clock(), backoffBaseMs)
                    continue
                }
                val result = renderer.render(unit) { flag.get() }
                when (result) {
                    is RenderResult.Success -> {
                        val asset = AudioAsset(
                            assetId = unit.cacheKey,
                            renderUnitId = unit.id,
                            codec = result.codec,
                            relativePath = result.relativePath,
                            bytes = result.bytes,
                            durationMs = result.durationMs,
                            voiceRevisionId = unit.voiceRevisionId,
                            checksumSha256 = result.checksumSha256,
                            createdAtEpochMs = clock(),
                        )
                        db.completeJob(job, asset, clock())
                    }
                    is RenderResult.Failure -> db.failJob(job, result.error, clock(), backoffBaseMs)
                }
            } catch (e: CancellationException) {
                db.failJob(job, "cancelled: " + (e.message ?: ""), clock(), backoffBaseMs)
            } catch (e: Exception) {
                if (scope.coroutineContext.isActive) {
                    try {
                        db.failJob(job, "exception: " + (e.message ?: ""), clock(), backoffBaseMs)
                    } catch (_: Exception) {
                        // db 已关闭（close 竞态）：忽略
                    }
                }
            } finally {
                cancelFlags.remove(job.jobId)
            }
        }
    }

    fun close() {
        supervisor.cancel()
    }
}
