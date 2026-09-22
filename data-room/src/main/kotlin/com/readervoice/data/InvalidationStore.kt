package com.readervoice.data

import java.sql.Connection

/**
 * Invalidation 核心（TASK-040 §12-§25/§82-§85）：
 * InvalidationEvent（reason/origin/scope）+ 依赖传播。
 * Scope：WHOLE_BOOK/WHOLE_SOURCE_REVISION/LINE_RANGE/CHAPTER_RANGE/PARAGRAPH_SET（§13）。
 */
class InvalidationStore(private val db: Db, private val revisions: RevisionStore) {

    enum class ScopeType { WHOLE_BOOK, WHOLE_SOURCE_REVISION, LINE_RANGE, CHAPTER_RANGE, PARAGRAPH_SET }

    data class InvalidationEvent(
        val eventId: Long,
        val bookId: String,
        val reasonType: String,
        val originType: String,
        val originId: String,
        val scopeType: ScopeType,
        val scopePayload: String?,
        val createdAt: String,
        val processed: Boolean,
    )

    private val conn: Connection get() = db.connection()

    fun record(
        bookId: String, reasonType: String, originType: String, originId: String,
        scopeType: ScopeType, scopePayload: String? = null,
    ): InvalidationEvent {
        val id = conn.prepareStatement(
            "INSERT INTO invalidation_event(book_id,reason_type,origin_type,origin_id,scope_type,scope_payload,created_at,processed) VALUES(?,?,?,?,?,?,?,0)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, bookId); ps.setString(2, reasonType)
            ps.setString(3, originType); ps.setString(4, originId)
            ps.setString(5, scopeType.name); ps.setString(6, scopePayload)
            ps.setString(7, java.time.Instant.now().toString())
            ps.executeUpdate()
            ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
        }
        return get(id)!!
    }

    fun get(eventId: Long): InvalidationEvent? = conn.prepareStatement("SELECT * FROM invalidation_event WHERE event_id=?").use { ps ->
        ps.setLong(1, eventId)
        ps.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
    }

    fun markProcessed(eventId: Long) {
        conn.prepareStatement("UPDATE invalidation_event SET processed=1 WHERE event_id=?").use { ps ->
            ps.setLong(1, eventId); ps.executeUpdate()
        }
    }

    // ---------- 传播（§16-§25 矩阵） ----------

    /**
     * 应用失效：scope 决定下游标记范围。
     * - WHOLE_BOOK / WHOLE_SOURCE_REVISION：SOURCE 链下游全 STALE（§16/§17）
     * - CHAPTER_RANGE / LINE_RANGE / PARAGRAPH_SET：仅本地（affected 集合由调用方记录，
     *   这里标记对应 PARAGRAPH_RECOVERY 链 STALE 由调用方控制局部性）
     */
    fun propagate(event: InvalidationEvent): List<String> {
        val marks = mutableListOf<String>()
        when (event.scopeType) {
            ScopeType.WHOLE_BOOK, ScopeType.WHOLE_SOURCE_REVISION -> {
                // 该 book 的 SOURCE head → 全链 STALE（§16/§17：encode override = new SourceRevision）
                // event.bookId 即 ownerScope（"book:<pk>"），避免双重前缀
                val srcHead = revisions.head(event.bookId, "SOURCE")?.activeRevisionId
                if (srcHead != null) {
                    revisions.markDownstreamStale(event.bookId, srcHead)
                    marks += "SOURCE:$srcHead -> downstream STALE"
                }
            }
            ScopeType.CHAPTER_RANGE, ScopeType.LINE_RANGE, ScopeType.PARAGRAPH_SET -> {
                // 局部：只标记 PARAGRAPH_RECOVERY 为局部重建候选（不整链 STALE）
                // 实际 affected paragraph 集合由上层 Persister/CorrectionStore 记录
                marks += "${event.scopeType.name}:${event.scopePayload ?: ""} -> local only"
            }
        }
        markProcessed(event.eventId)
        return marks
    }

    private fun row(rs: java.sql.ResultSet) = InvalidationEvent(
        eventId = rs.getLong("event_id"),
        bookId = rs.getString("book_id"),
        reasonType = rs.getString("reason_type"),
        originType = rs.getString("origin_type"),
        originId = rs.getString("origin_id"),
        scopeType = ScopeType.valueOf(rs.getString("scope_type")),
        scopePayload = rs.getString("scope_payload"),
        createdAt = rs.getString("created_at"),
        processed = rs.getInt("processed") == 1,
    )
}
