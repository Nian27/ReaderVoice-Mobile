package com.readervoice.data

import java.sql.Connection

/**
 * Revision 核心（TASK-040 §5-§10/§86-§89/§102-§105/§113-§114）：
 * ArtifactRevision（stage-level）+ ArtifactHead + ArtifactDependency + BuildSession + RevisionSnapshot。
 * - Promotion 原子（§7）：BEGIN → 旧 head SUPERSEDED → 新 ACTIVE → head 指向新 → COMMIT
 * - Idempotent build（§86）：同 input_hash+algorithm_version+override_hash → 复用
 * - Cycle 拒绝（§81）：application-level 检查（SQLite 不保证 DAG）
 * - Crash 恢复（§113/§114）：启动时 BUILDING 无 session → FAILED_INTERRUPTED
 */
class RevisionStore(private val db: Db) {

    enum class Status { BUILDING, ACTIVE, SUPERSEDED, FAILED, STALE }

    data class ArtifactRevision(
        val revisionId: Long,
        val artifactType: String,
        val ownerScope: String,
        val bookId: String?,
        val sourceRevisionId: String?,
        val parentRevisionId: Long?,
        val inputHash: String,
        val algorithmVersion: String,
        val status: Status,
        val createdAt: String,
        val activatedAt: String? = null,
        val supersededAt: String? = null,
        val reason: String? = null,
    )

    data class Head(val ownerScope: String, val artifactType: String, val activeRevisionId: Long)

    data class RevisionSnapshot(
        val bookId: String,
        val sourceRevisionId: String?,
        val structureRevisionId: Long?,
        val paragraphRecoveryRevisionId: Long?,
        val capturedAt: String = java.time.Instant.now().toString(),
    )

    private val conn: Connection get() = db.connection()

    // ---------- 创建 / 幂等（§86/§87） ----------

    /** 幂等：同 owner+type+inputHash+algorithmVersion 已有 ACTIVE/SUPERSEDED → 复用。 */
    fun findExisting(ownerScope: String, artifactType: String, inputHash: String, algorithmVersion: String): ArtifactRevision? =
        conn.prepareStatement(
            "SELECT * FROM artifact_revision WHERE owner_scope=? AND artifact_type=? AND input_hash=? AND algorithm_version=? AND status IN ('ACTIVE','SUPERSEDED') ORDER BY revision_id DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, ownerScope); ps.setString(2, artifactType)
            ps.setString(3, inputHash); ps.setString(4, algorithmVersion)
            ps.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }

    fun create(
        artifactType: String, ownerScope: String, bookId: String?, sourceRevisionId: String?,
        parentRevisionId: Long?, inputHash: String, algorithmVersion: String, reason: String? = null,
    ): ArtifactRevision {
        // 幂等（§86：不创建一模一样的 R103/R104/R105）
        findExisting(ownerScope, artifactType, inputHash, algorithmVersion)?.let { return it }
        val id = conn.prepareStatement(
            "INSERT INTO artifact_revision(artifact_type,owner_scope,book_id,source_revision_id,parent_revision_id,input_hash,algorithm_version,status,created_at,reason) VALUES(?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, artifactType); ps.setString(2, ownerScope)
            ps.setString(3, bookId); ps.setString(4, sourceRevisionId)
            if (parentRevisionId != null) ps.setLong(5, parentRevisionId) else ps.setNull(5, java.sql.Types.BIGINT)
            ps.setString(6, inputHash); ps.setString(7, algorithmVersion)
            ps.setString(8, Status.BUILDING.name)
            ps.setString(9, java.time.Instant.now().toString())
            ps.setString(10, reason)
            ps.executeUpdate()
            ps.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        return get(id)!!
    }

    fun get(revisionId: Long): ArtifactRevision? = conn.prepareStatement("SELECT * FROM artifact_revision WHERE revision_id=?").use { ps ->
        ps.setLong(1, revisionId)
        ps.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
    }

    fun markFailed(revisionId: Long, reason: String) {
        conn.prepareStatement("UPDATE artifact_revision SET status='FAILED', reason=? WHERE revision_id=?").use { ps ->
            ps.setString(1, reason); ps.setLong(2, revisionId); ps.executeUpdate()
        }
    }

    // ---------- 原子 Promotion（§7/§79/§80） ----------

    /**
     * 原子切换 head：R2 BUILDING → ACTIVE；旧 head ACTIVE → SUPERSEDED；ArtifactHead → R2。
     * 任一步失败 → 回滚，R1 仍 ACTIVE。
     */
    fun promote(ownerScope: String, artifactType: String, newRevisionId: Long): Head {
        conn.autoCommit = false
        try {
            // 唯一 head 约束检查（§80：owner+type 至多一个 ACTIVE）
            conn.prepareStatement("SELECT count(*) FROM artifact_revision WHERE owner_scope=? AND artifact_type=? AND status='ACTIVE'").use { ps ->
                ps.setString(1, ownerScope); ps.setString(2, artifactType)
                ps.executeQuery().use { rs -> rs.next(); require(rs.getInt(1) <= 1) { "multiple ACTIVE heads detected" } }
            }
            // 新 revision 必须 BUILDING（不允许跳状态）
            val rev = get(newRevisionId) ?: error("revision not found")
            require(rev.status == Status.BUILDING) { "promote requires BUILDING, got ${rev.status}" }

            // 旧 head SUPERSEDED
            conn.prepareStatement(
                "UPDATE artifact_revision SET status='SUPERSEDED', superseded_at=? WHERE owner_scope=? AND artifact_type=? AND status='ACTIVE'"
            ).use { ps ->
                ps.setString(1, java.time.Instant.now().toString())
                ps.setString(2, ownerScope); ps.setString(3, artifactType)
                ps.executeUpdate()
            }
            // 新 revision ACTIVE
            conn.prepareStatement(
                "UPDATE artifact_revision SET status='ACTIVE', activated_at=? WHERE revision_id=?"
            ).use { ps ->
                ps.setString(1, java.time.Instant.now().toString()); ps.setLong(2, newRevisionId)
                ps.executeUpdate()
            }
            // head 指向新（UPSERT 语义——不用 INSERT OR REPLACE，ADR-025）
            conn.prepareStatement(
                "INSERT INTO artifact_head(owner_scope,artifact_type,active_revision_id) VALUES(?,?,?) " +
                    "ON CONFLICT(owner_scope,artifact_type) DO UPDATE SET active_revision_id=excluded.active_revision_id"
            ).use { ps ->
                ps.setString(1, ownerScope); ps.setString(2, artifactType); ps.setLong(3, newRevisionId)
                ps.executeUpdate()
            }
            conn.commit()
            return Head(ownerScope, artifactType, newRevisionId)
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun head(ownerScope: String, artifactType: String): Head? =
        conn.prepareStatement("SELECT * FROM artifact_head WHERE owner_scope=? AND artifact_type=?").use { ps ->
            ps.setString(1, ownerScope); ps.setString(2, artifactType)
            ps.executeQuery().use { rs ->
                if (rs.next()) Head(rs.getString("owner_scope"), rs.getString("artifact_type"), rs.getLong("active_revision_id")) else null
            }
        }

    fun activeRevision(ownerScope: String, artifactType: String): ArtifactRevision? =
        head(ownerScope, artifactType)?.let { get(it.activeRevisionId) }

    // ---------- 依赖 DAG（§9/§81） ----------

    /** 插入依赖边；若形成 cycle → 拒绝。 */
    fun addDependency(ownerScope: String, dependentRevisionId: Long, dependencyRevisionId: Long): Boolean {
        if (createsCycle(dependentRevisionId, dependencyRevisionId)) return false
        conn.prepareStatement(
            "INSERT OR IGNORE INTO artifact_dependency(owner_scope,dependent_revision_id,dependency_revision_id) VALUES(?,?,?)"
        ).use { ps ->
            ps.setString(1, ownerScope); ps.setLong(2, dependentRevisionId); ps.setLong(3, dependencyRevisionId)
            ps.executeUpdate()
        }
        return true
    }

    /** 从 dependency 出发沿"被依赖链"（cur 依赖谁）搜索；若到达 dependent 本身 → cycle。 */
    private fun createsCycle(dependentRevisionId: Long, dependencyRevisionId: Long): Boolean {
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(dependencyRevisionId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == dependentRevisionId) return true
            if (!visited.add(cur)) continue
            conn.prepareStatement("SELECT dependency_revision_id FROM artifact_dependency WHERE dependent_revision_id=?").use { ps ->
                ps.setLong(1, cur)
                ps.executeQuery().use { rs -> while (rs.next()) queue.add(rs.getLong(1)) }
            }
        }
        return false
    }

    /** 全链失效（§16）：downstream 全标 STALE。 */
    fun markDownstreamStale(ownerScope: String, staleRevisionId: Long) {
        val downstream = mutableListOf<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(staleRevisionId)
        val visited = mutableSetOf<Long>()
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!visited.add(cur)) continue
            conn.prepareStatement("SELECT dependent_revision_id FROM artifact_dependency WHERE dependency_revision_id=?").use { ps ->
                ps.setLong(1, cur)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val d = rs.getLong(1)
                        downstream += d
                        queue.add(d)
                    }
                }
            }
        }
        for (id in downstream.distinct()) {
            conn.prepareStatement("UPDATE artifact_revision SET status='STALE' WHERE revision_id=?").use { ps ->
                ps.setLong(1, id); ps.executeUpdate()
            }
        }
    }

    fun dependenciesOf(revisionId: Long): List<Long> = conn.prepareStatement(
        "SELECT dependency_revision_id FROM artifact_dependency WHERE dependent_revision_id=?"
    ).use { ps ->
        ps.setLong(1, revisionId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<Long>()
            while (rs.next()) out += rs.getLong(1)
            out
        }
    }

    // ---------- RevisionSnapshot（§102-§105） ----------

    /** 从 heads 读取一致快照（§102-§105）；ownerScope 即 "book:<pk>"。 */
    fun snapshot(ownerScope: String): RevisionSnapshot {
        val srcHead = head(ownerScope, "SOURCE")?.activeRevisionId
        val structHead = head(ownerScope, "STRUCTURE")?.activeRevisionId
        val paraHead = head(ownerScope, "PARAGRAPH_RECOVERY")?.activeRevisionId
        val srcId = srcHead?.let { get(it)?.sourceRevisionId }
        return RevisionSnapshot(ownerScope.removePrefix("book:"), srcId, structHead, paraHead)
    }

    // ---------- BuildSession / Crash 恢复（§113/§114） ----------

    fun startSession(artifactType: String, revisionId: Long): String {
        val sessionId = "sess-${System.nanoTime()}"
        conn.prepareStatement(
            "INSERT INTO build_session(session_id,artifact_type,revision_id,state,started_at,heartbeat_at) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, sessionId); ps.setString(2, artifactType); ps.setLong(3, revisionId)
            ps.setString(4, "RUNNING")
            val now = java.time.Instant.now().toString()
            ps.setString(5, now); ps.setString(6, now)
            ps.executeUpdate()
        }
        return sessionId
    }

    fun completeSession(sessionId: String) {
        conn.prepareStatement("UPDATE build_session SET state='COMPLETED', completed_at=? WHERE session_id=?").use { ps ->
            ps.setString(1, java.time.Instant.now().toString()); ps.setString(2, sessionId); ps.executeUpdate()
        }
    }

    /** 启动恢复（§114）：BUILDING revision 且无 RUNNING session → FAILED_INTERRUPTED。旧 ACTIVE 不变。 */
    fun recoverInterruptedBuilds(): List<Pair<Long, String>> {
        val recovered = mutableListOf<Pair<Long, String>>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT revision_id FROM artifact_revision WHERE status='BUILDING' " +
                    "AND revision_id NOT IN (SELECT revision_id FROM build_session WHERE state='RUNNING')"
            ).use { rs ->
                while (rs.next()) recovered += rs.getLong(1) to "FAILED_INTERRUPTED"
            }
        }
        for ((id, reason) in recovered) {
            conn.prepareStatement("UPDATE artifact_revision SET status='FAILED', reason=? WHERE revision_id=?").use { ps ->
                ps.setString(1, reason); ps.setLong(2, id); ps.executeUpdate()
            }
        }
        return recovered
    }

    fun activeHeads(): List<Head> = conn.createStatement().use { st ->
        st.executeQuery("SELECT * FROM artifact_head").use { rs ->
            val out = mutableListOf<Head>()
            while (rs.next()) out += Head(rs.getString("owner_scope"), rs.getString("artifact_type"), rs.getLong("active_revision_id"))
            out
        }
    }

    private fun row(rs: java.sql.ResultSet) = ArtifactRevision(
        revisionId = rs.getLong("revision_id"),
        artifactType = rs.getString("artifact_type"),
        ownerScope = rs.getString("owner_scope"),
        bookId = rs.getString("book_id"),
        sourceRevisionId = rs.getString("source_revision_id"),
        parentRevisionId = rs.getLongOrNull("parent_revision_id"),
        inputHash = rs.getString("input_hash"),
        algorithmVersion = rs.getString("algorithm_version"),
        status = Status.valueOf(rs.getString("status")),
        createdAt = rs.getString("created_at"),
        activatedAt = rs.getString("activated_at"),
        supersededAt = rs.getString("superseded_at"),
        reason = rs.getString("reason"),
    )

    private fun java.sql.ResultSet.getLongOrNull(col: String): Long? =
        if (getObject(col) == null) null else getLong(col)
}
