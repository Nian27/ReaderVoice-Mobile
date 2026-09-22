package com.readervoice.scheduler

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * book.db 持久层（Book Package §7 冻结结构）。每书一个 DB 文件。
 *
 * DB 红线（AGENTS ADR-025/G11）：核心表一律 UPSERT（INSERT ... ON CONFLICT DO UPDATE），
 * 禁止 INSERT OR REPLACE（避免 rowid 变化与 FK 级联误删）。
 * 单连接 + synchronized：worker 池并发由调度器控制，此层串行化即可。
 */
class SchedulerDb(private val dbPath: Path) : AutoCloseable {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())

    init {
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE IF NOT EXISTS render_units (
                    id TEXT PRIMARY KEY,
                    segment_type TEXT NOT NULL,
                    text_normalized TEXT NOT NULL,
                    voice_profile_id TEXT NOT NULL,
                    profile_hash TEXT NOT NULL,
                    instruction_mode TEXT NOT NULL,
                    instruction TEXT NOT NULL,
                    tts_model TEXT NOT NULL,
                    speed REAL NOT NULL,
                    sample_rate INTEGER NOT NULL,
                    voice_revision_id TEXT NOT NULL,
                    cache_key TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS generation_jobs (
                    job_id TEXT PRIMARY KEY,
                    render_unit_id TEXT NOT NULL UNIQUE,
                    cache_key TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 3,
                    renderer TEXT NOT NULL DEFAULT '',
                    last_error TEXT,
                    backoff_until INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS audio_assets (
                    asset_id TEXT PRIMARY KEY,
                    render_unit_id TEXT NOT NULL,
                    codec TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    bytes INTEGER NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    voice_revision_id TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )"""
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_jobs_state_prio ON generation_jobs(state, priority, updated_at)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_assets_unit ON audio_assets(render_unit_id)")
        }
    }

    @Synchronized
    fun upsertUnit(unit: RenderUnitRef, now: Long): Boolean {
        // returns true if the unit content changed (i.e. invalidation needed)
        val old = getUnit(unit.id)
        val changed = old != null && old != unit
        val sql = """
            INSERT INTO render_units (id, segment_type, text_normalized, voice_profile_id, profile_hash,
                instruction_mode, instruction, tts_model, speed, sample_rate, voice_revision_id, cache_key, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                segment_type=excluded.segment_type,
                text_normalized=excluded.text_normalized,
                voice_profile_id=excluded.voice_profile_id,
                profile_hash=excluded.profile_hash,
                instruction_mode=excluded.instruction_mode,
                instruction=excluded.instruction,
                tts_model=excluded.tts_model,
                speed=excluded.speed,
                sample_rate=excluded.sample_rate,
                voice_revision_id=excluded.voice_revision_id,
                cache_key=excluded.cache_key,
                updated_at=excluded.updated_at
        """
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, unit.id); ps.setString(2, unit.segmentType.name)
            ps.setString(3, unit.textNormalized); ps.setString(4, unit.voiceProfileId)
            ps.setString(5, unit.profileHash); ps.setString(6, unit.instructionMode)
            ps.setString(7, unit.instruction); ps.setString(8, unit.ttsModel)
            ps.setFloat(9, unit.speed); ps.setInt(10, unit.sampleRate)
            ps.setString(11, unit.voiceRevisionId); ps.setString(12, unit.cacheKey)
            ps.setLong(13, now)
            ps.executeUpdate()
        }
        return changed
    }

    @Synchronized
    fun getUnit(unitId: String): RenderUnitRef? {
        val sql = "SELECT * FROM render_units WHERE id=?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, unitId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.toUnit() else null
            }
        }
    }

    @Synchronized
    fun hasAsset(unitId: String, cacheKey: String, voiceRevisionId: String): Boolean {
        val sql = "SELECT 1 FROM audio_assets WHERE render_unit_id=? AND asset_id=? AND voice_revision_id=?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, unitId); ps.setString(2, cacheKey); ps.setString(3, voiceRevisionId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    @Synchronized
    fun getJobByUnit(unitId: String): GenerationJob? {
        val sql = "SELECT * FROM generation_jobs WHERE render_unit_id=?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, unitId)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.toJob() else null }
        }
    }

    /** UPSERT job（重规划/状态回写共用） */
    @Synchronized
    fun upsertJob(job: GenerationJob) {
        val sql = """
            INSERT INTO generation_jobs (job_id, render_unit_id, cache_key, priority, state, attempts,
                max_attempts, renderer, last_error, backoff_until, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(render_unit_id) DO UPDATE SET
                job_id=excluded.job_id,
                cache_key=excluded.cache_key,
                priority=excluded.priority,
                state=excluded.state,
                attempts=excluded.attempts,
                max_attempts=excluded.max_attempts,
                renderer=excluded.renderer,
                last_error=excluded.last_error,
                backoff_until=excluded.backoff_until,
                created_at=excluded.created_at,
                updated_at=excluded.updated_at
        """
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, job.jobId); ps.setString(2, job.renderUnitId)
            ps.setString(3, job.cacheKey); ps.setInt(4, job.priority.rank)
            ps.setString(5, job.state.name); ps.setInt(6, job.attempts)
            ps.setInt(7, job.maxAttempts); ps.setString(8, job.renderer)
            ps.setString(9, job.lastError); ps.setLong(10, job.backoffUntilEpochMs)
            ps.setLong(11, job.createdAtEpochMs); ps.setLong(12, job.updatedAtEpochMs)
            ps.executeUpdate()
        }
    }

    /** PLANNED → QUEUED（供 worker 循环调用；PLANNED 是持久化前的短暂态） */
    @Synchronized
    fun promotePlanned(now: Long) {
        val sql = "UPDATE generation_jobs SET state='QUEUED', updated_at=? WHERE state='PLANNED'"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }
    }

    /** 事务式认领：QUEUED 且退避到期 → GENERATING，返回被认领的 job */
    @Synchronized
    fun claimNextJob(now: Long, rendererTag: String): GenerationJob? {
        conn.autoCommit = false
        try {
            val select = """
                SELECT job_id FROM generation_jobs
                WHERE state='QUEUED' AND backoff_until<=? AND (renderer='' OR renderer=?)
                ORDER BY priority ASC, updated_at ASC LIMIT 1
            """
            var jobId: String? = null
            conn.prepareStatement(select).use { ps ->
                ps.setLong(1, now); ps.setString(2, rendererTag)
                ps.executeQuery().use { rs -> if (rs.next()) jobId = rs.getString(1) }
            }
            if (jobId == null) { conn.autoCommit = true; return null }
            val upd = "UPDATE generation_jobs SET state='GENERATING', renderer=?, updated_at=? WHERE job_id=?"
            conn.prepareStatement(upd).use { ps ->
                ps.setString(1, rendererTag); ps.setLong(2, now); ps.setString(3, jobId)
                ps.executeUpdate()
            }
            conn.commit()
            conn.autoCommit = true
            val job = getJob(jobId!!)
            return job?.copy(state = JobState.GENERATING, renderer = rendererTag, updatedAtEpochMs = now)
        } catch (e: Exception) {
            conn.rollback(); conn.autoCommit = true
            throw e
        }
    }

    @Synchronized
    private fun getJob(jobId: String): GenerationJob? {
        val sql = "SELECT * FROM generation_jobs WHERE job_id=?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.toJob() else null }
        }
    }

    /** 完成：原子替换旧资产版本 + job → READY */
    @Synchronized
    fun completeJob(job: GenerationJob, asset: AudioAsset, now: Long) {
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM audio_assets WHERE render_unit_id=? AND asset_id<>?").use { ps ->
                ps.setString(1, job.renderUnitId); ps.setString(2, asset.assetId)
                ps.executeUpdate()
            }
            val sql = """
                INSERT INTO audio_assets (asset_id, render_unit_id, codec, relative_path, bytes,
                    duration_ms, voice_revision_id, checksum, created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(asset_id) DO UPDATE SET
                    render_unit_id=excluded.render_unit_id,
                    codec=excluded.codec,
                    relative_path=excluded.relative_path,
                    bytes=excluded.bytes,
                    duration_ms=excluded.duration_ms,
                    voice_revision_id=excluded.voice_revision_id,
                    checksum=excluded.checksum,
                    created_at=excluded.created_at
            """
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, asset.assetId); ps.setString(2, asset.renderUnitId)
                ps.setString(3, asset.codec.name); ps.setString(4, asset.relativePath)
                ps.setLong(5, asset.bytes); ps.setLong(6, asset.durationMs)
                ps.setString(7, asset.voiceRevisionId); ps.setString(8, asset.checksumSha256)
                ps.setLong(9, asset.createdAtEpochMs)
                ps.executeUpdate()
            }
            val upd = "UPDATE generation_jobs SET state='READY', last_error=NULL, updated_at=? WHERE job_id=?"
            conn.prepareStatement(upd).use { ps ->
                ps.setLong(1, now); ps.setString(2, job.jobId)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** 失败：attempts+1；耗尽 → FAILED，否则 QUEUED + 指数退避 */
    @Synchronized
    fun failJob(job: GenerationJob, error: String, now: Long, backoffBaseMs: Long): GenerationJob {
        val attempts = job.attempts + 1
        val exhausted = attempts >= job.maxAttempts
        val backoff = if (exhausted) 0L else now + backoffBaseMs * (1L shl (attempts - 1))
        val state = if (exhausted) JobState.FAILED else JobState.QUEUED
        val updated = job.copy(
            state = state, attempts = attempts, lastError = error,
            backoffUntilEpochMs = backoff, updatedAtEpochMs = now
        )
        upsertJob(updated)
        return updated
    }

    /** 显式失效（管线事件驱动）：标记 STALE，资产保留至重生成原子替换 */
    @Synchronized
    fun markStale(unitIds: List<String>, now: Long) {
        if (unitIds.isEmpty()) return
        val placeholders = unitIds.joinToString(",") { "?" }
        val sql = "UPDATE generation_jobs SET state='STALE', updated_at=? WHERE render_unit_id IN ($placeholders) AND state NOT IN ('STALE','GENERATING')"
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, now)
            unitIds.forEachIndexed { i, id -> ps.setString(i + 2, id) }
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun getAssetByUnit(unitId: String): AudioAsset? {
        val sql = "SELECT * FROM audio_assets WHERE render_unit_id=? ORDER BY created_at DESC LIMIT 1"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, unitId)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.toAsset() else null }
        }
    }

    @Synchronized
    fun assetsInOrder(unitIds: List<String>): List<AudioAsset> {
        if (unitIds.isEmpty()) return emptyList()
        val placeholders = unitIds.joinToString(",") { "?" }
        val sql = "SELECT * FROM audio_assets WHERE render_unit_id IN ($placeholders)"
        val byUnit = HashMap<String, AudioAsset>()
        conn.prepareStatement(sql).use { ps ->
            unitIds.forEachIndexed { i, id -> ps.setString(i + 1, id) }
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val a = rs.toAsset()
                    byUnit[a.renderUnitId] = a
                }
            }
        }
        return unitIds.mapNotNull { byUnit[it] }
    }

    private fun ResultSet.toUnit(): RenderUnitRef = RenderUnitRef(
        id = getString("id"),
        segmentType = SegmentType.valueOf(getString("segment_type")),
        textNormalized = getString("text_normalized"),
        voiceProfileId = getString("voice_profile_id"),
        profileHash = getString("profile_hash"),
        instructionMode = getString("instruction_mode"),
        instruction = getString("instruction"),
        ttsModel = getString("tts_model"),
        speed = getFloat("speed"),
        sampleRate = getInt("sample_rate"),
        voiceRevisionId = getString("voice_revision_id"),
    )

    private fun ResultSet.toJob(): GenerationJob = GenerationJob(
        jobId = getString("job_id"),
        renderUnitId = getString("render_unit_id"),
        cacheKey = getString("cache_key"),
        priority = Priority.fromRank(getInt("priority")),
        state = JobState.valueOf(getString("state")),
        attempts = getInt("attempts"),
        maxAttempts = getInt("max_attempts"),
        renderer = getString("renderer"),
        lastError = getString("last_error"),
        backoffUntilEpochMs = getLong("backoff_until"),
        createdAtEpochMs = getLong("created_at"),
        updatedAtEpochMs = getLong("updated_at"),
    )

    private fun ResultSet.toAsset(): AudioAsset = AudioAsset(
        assetId = getString("asset_id"),
        renderUnitId = getString("render_unit_id"),
        codec = Codec.valueOf(getString("codec")),
        relativePath = getString("relative_path"),
        bytes = getLong("bytes"),
        durationMs = getLong("duration_ms"),
        voiceRevisionId = getString("voice_revision_id"),
        checksumSha256 = getString("checksum"),
        createdAtEpochMs = getLong("created_at"),
    )

    /** 供红线静态测试：暴露建表 SQL */
    val schemaSql: String
        get() = buildString {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL").use { rs ->
                    while (rs.next()) append(rs.getString(1)).append('\n')
                }
            }
        }

    @Synchronized
    override fun close() {
        conn.close()
    }
}
