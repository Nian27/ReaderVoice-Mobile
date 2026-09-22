package com.readervoice.scheduler

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SchedulerStateMachineTest {

    @TempDir
    lateinit var tmp: Path

    private fun newScheduler(db: SchedulerDb, renderer: Renderer, backoffBaseMs: Long = 1L): Scheduler =
        Scheduler(db, mapOf(renderer.tag to renderer), backoffBaseMs = backoffBaseMs, idlePollMs = 2L)

    @Test
    fun planRenderReadyLifecycle() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val order = mutableListOf<String>()
        val s = newScheduler(db, FakeRenderer(renderedOrder = order))
        try {
            val u = TestSupport.unit("b:1:1")
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.CURRENT_PLAYBACK)))
            assertEquals(JobState.PLANNED, db.getJobByUnit(u.id)?.state)
            s.start()
            waitUntil { db.getJobByUnit(u.id)?.state == JobState.READY }
            val asset = db.getAssetByUnit(u.id)
            assertNotNull(asset)
            assertEquals(u.cacheKey, asset!!.assetId)
            assertEquals("rev-1", asset.voiceRevisionId)
            assertEquals(Codec.WAV, asset.codec)
            // 播放队列可取出
            val q = PlaybackQueue(db)
            assertEquals(1, q.available(listOf(u.id)).size)
            assertNotNull(q.assetFor(u.id))
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun cacheHitSkipsJob() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val order = mutableListOf<String>()
        val s = newScheduler(db, FakeRenderer(renderedOrder = order))
        try {
            val u = TestSupport.unit("b:1:1")
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.CURRENT_PLAYBACK)))
            s.start()
            waitUntil { db.getJobByUnit(u.id)?.state == JobState.READY }
            assertEquals(1, order.size)
            // 再次 plan 同内容：缓存命中，不重建 job
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.CURRENT_PLAYBACK)))
            kotlinx.coroutines.delay(100)
            assertEquals(1, order.size)
            assertEquals(JobState.READY, db.getJobByUnit(u.id)?.state)
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun failureExhaustsToFailed() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val s = newScheduler(db, FakeRenderer(failIds = setOf("b:1:1")))
        try {
            val u = TestSupport.unit("b:1:1")
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.CURRENT_PLAYBACK)))
            s.start()
            waitUntil { db.getJobByUnit(u.id)?.state == JobState.FAILED }
            val job = db.getJobByUnit(u.id)!!
            assertEquals(3, job.attempts) // maxAttempts=3
            assertNotNull(job.lastError)
            assertNull(db.getAssetByUnit(u.id))
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun transientFailureRetriesThenSucceeds() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val order = mutableListOf<String>()
        val s = newScheduler(db, FakeRenderer(failTimes = mapOf("b:1:1" to 1), renderedOrder = order))
        try {
            val u = TestSupport.unit("b:1:1")
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.CURRENT_PLAYBACK)))
            s.start()
            waitUntil { db.getJobByUnit(u.id)?.state == JobState.READY }
            val job = db.getJobByUnit(u.id)!!
            assertEquals(1, job.attempts) // attempts 只计失败次数
            assertEquals(2, order.size)   // 失败 1 次 + 成功 1 次
            assertNotNull(db.getAssetByUnit(u.id))
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun priorityOrdering() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val order = mutableListOf<String>()
        val s = newScheduler(db, FakeRenderer(delayMs = 20L, renderedOrder = order))
        try {
            // 先规划低优先级，再规划高优先级：高优先级先渲染
            val bg = TestSupport.unit("b:1:bg")
            val cur = TestSupport.unit("b:1:cur")
            s.plan(listOf(Scheduler.PlannedUnit(bg, Priority.BACKGROUND)))
            s.plan(listOf(Scheduler.PlannedUnit(cur, Priority.CURRENT_PLAYBACK)))
            s.start()
            waitUntil { order.size >= 2 }
            assertEquals("b:1:cur", order[0])
            assertEquals("b:1:bg", order[1])
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun invalidationReRendersOnlyAffected() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val order = mutableListOf<String>()
        val s = newScheduler(db, FakeRenderer(renderedOrder = order))
        try {
            val u1 = TestSupport.unit("b:1:1", text = "原文一。")
            val u2 = TestSupport.unit("b:1:2", text = "原文二。")
            s.plan(listOf(Scheduler.PlannedUnit(u1, Priority.BACKGROUND), Scheduler.PlannedUnit(u2, Priority.BACKGROUND)))
            s.start()
            waitUntil { order.size >= 2 && db.getJobByUnit(u2.id)?.state == JobState.READY && db.getJobByUnit(u1.id)?.state == JobState.READY }
            val oldAsset1 = db.getAssetByUnit(u1.id)!!
            val oldAsset2 = db.getAssetByUnit(u2.id)!!
            // 只改 u1 文本 → 只有 u1 重渲染，u2 不受影响
            val newU1 = u1.copy(textNormalized = "修正后的文本一。")
            s.plan(listOf(Scheduler.PlannedUnit(newU1, Priority.BACKGROUND)))
            waitUntil { db.getAssetByUnit(u1.id)?.assetId != oldAsset1.assetId }
            val newAsset1 = db.getAssetByUnit(u1.id)!!
            assertEquals(newU1.cacheKey, newAsset1.assetId)
            assertEquals(oldAsset2.assetId, db.getAssetByUnit(u2.id)!!.assetId) // u2 不受影响
            assertEquals(JobState.READY, db.getJobByUnit(u2.id)?.state)
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun voiceRevisionChangeReplacesAsset() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val order = mutableListOf<String>()
        val s = newScheduler(db, FakeRenderer(renderedOrder = order))
        try {
            val u = TestSupport.unit("b:1:1", voiceRevisionId = "rev-1")
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.BACKGROUND)))
            s.start()
            waitUntil { db.getAssetByUnit(u.id) != null }
            assertEquals("rev-1", db.getAssetByUnit(u.id)!!.voiceRevisionId)
            // 角色档案重绑定 → 新 revision
            val u2 = u.copy(voiceRevisionId = "rev-2", profileHash = "ph-2")
            s.plan(listOf(Scheduler.PlannedUnit(u2, Priority.BACKGROUND)))
            waitUntil { db.getAssetByUnit(u.id)?.voiceRevisionId == "rev-2" }
            val asset = db.getAssetByUnit(u.id)!!
            assertEquals(u2.cacheKey, asset.assetId)
            assertEquals("rev-2", asset.voiceRevisionId)
        } finally {
            s.close(); db.close()
        }
    }

    @Test
    fun explicitInvalidateMarksStale() = runBlocking {
        val db = SchedulerDb(TestSupport.tempDbPath())
        val s = newScheduler(db, FakeRenderer())
        try {
            val u = TestSupport.unit("b:1:1")
            s.plan(listOf(Scheduler.PlannedUnit(u, Priority.BACKGROUND)))
            s.start()
            waitUntil { db.getJobByUnit(u.id)?.state == JobState.READY }
            s.invalidate(listOf(u.id))
            assertEquals(JobState.STALE, db.getJobByUnit(u.id)?.state)
            assertTrue(db.getJobByUnit(u.id)!!.state == JobState.STALE)
        } finally {
            s.close(); db.close()
        }
    }
}
