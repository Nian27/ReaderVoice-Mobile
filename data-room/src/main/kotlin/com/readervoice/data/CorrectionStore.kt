package com.readervoice.data

import java.sql.Connection

/**
 * Correction 核心（TASK-040 §34-§40/§31-§32）：
 * - CorrectionEvent：不可变日志（§34/§35，禁止 UPDATE）
 * - OverrideRule：当前 Compiler 必须遵守的约束（§36/§37），优先 USER_LOCKED>USER_OVERRIDE>SYSTEM_CONFIRMED>AUTO（§39）
 * - Undo：新增事件 + head 切换（§40：不删除历史）
 * - ParagraphLineage（§31/§32：MERGED_INTO/SPLIT_FROM/SUPERSEDES）
 */
class CorrectionStore(private val db: Db) {

    enum class OverridePriority { USER_LOCKED, USER_OVERRIDE, SYSTEM_CONFIRMED, AUTO }
    enum class OverrideStatus { ACTIVE, SUPERSEDED, DISABLED, NEEDS_REBIND }
    enum class LineageType { MERGED_INTO, SPLIT_FROM, SUPERSEDES }

    data class CorrectionEvent(
        val correctionId: Long,
        val bookId: String,
        val sourceRevisionId: String?,
        val type: String,
        val targetType: String,
        val targetId: String,
        val oldPayload: String?,
        val newPayload: String?,
        val userLocked: Boolean,
        val createdAt: String,
        val compiledOverrideId: Long?,
    )

    data class OverrideRule(
        val ruleId: Long,
        val bookId: String?,
        val scope: String,
        val ruleType: String,
        val matchPayload: String,
        val actionPayload: String,
        val priority: OverridePriority,
        val status: OverrideStatus,
        val userLocked: Boolean,
    )

    data class Lineage(val fromParagraphPk: Long, val toParagraphPk: Long, val type: LineageType, val createdAt: String)

    private val conn: Connection get() = db.connection()

    // ---------- CorrectionEvent（不可变日志） ----------

    fun recordCorrection(
        bookId: String, sourceRevisionId: String?, type: String, targetType: String, targetId: String,
        oldPayload: String? = null, newPayload: String? = null, userLocked: Boolean = true,
    ): CorrectionEvent {
        val id = conn.prepareStatement(
            "INSERT INTO correction_event(book_id,source_revision_id,type,target_type,target_id,old_payload,new_payload,user_locked,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, bookId); ps.setString(2, sourceRevisionId)
            ps.setString(3, type); ps.setString(4, targetType); ps.setString(5, targetId)
            ps.setString(6, oldPayload); ps.setString(7, newPayload)
            ps.setInt(8, if (userLocked) 1 else 0)
            ps.setString(9, java.time.Instant.now().toString())
            ps.executeUpdate()
            ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
        }
        return getCorrection(id)!!
    }

    fun getCorrection(id: Long): CorrectionEvent? = conn.prepareStatement("SELECT * FROM correction_event WHERE correction_id=?").use { ps ->
        ps.setLong(1, id)
        ps.executeQuery().use { rs -> if (rs.next()) correctionRow(rs) else null }
    }

    fun listCorrections(bookId: String): List<CorrectionEvent> = conn.prepareStatement(
        "SELECT * FROM correction_event WHERE book_id=? ORDER BY correction_id"
    ).use { ps ->
        ps.setString(1, bookId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<CorrectionEvent>()
            while (rs.next()) out += correctionRow(rs)
            out
        }
    }

    /** §40：Undo = 新增事件 + head 切换（不删除旧事件）。 */
    fun undo(originalEventId: Long): CorrectionEvent {
        val orig = getCorrection(originalEventId) ?: error("event not found")
        // 生成 UNDO 事件（old/new 对调）
        return recordCorrection(
            bookId = orig.bookId, sourceRevisionId = orig.sourceRevisionId,
            type = "UNDO:${orig.type}", targetType = orig.targetType, targetId = orig.targetId,
            oldPayload = orig.newPayload, newPayload = orig.oldPayload, userLocked = orig.userLocked,
        )
    }

    // ---------- OverrideRule（当前生效约束） ----------

    /** 编译 CorrectionEvent → OverrideRule（§37）。§39：priority 强 = ordinal 小（USER_LOCKED 最强）。
     * 同 match 同 type：新优先级不弱于旧才 supersede 旧；弱于旧 → 新规则 DISABLED（不生效）。 */
    fun compileOverride(
        bookId: String, ruleType: String, matchPayload: String, actionPayload: String,
        priority: OverridePriority = OverridePriority.USER_OVERRIDE, scope: String = "BOOK",
    ): OverrideRule {
        // 旧 ACTIVE 最高优先级（ordinal 最小者最强）
        var oldStrongest: OverridePriority? = null
        conn.prepareStatement(
            "SELECT priority FROM override_rule WHERE book_id=? AND rule_type=? AND match_payload=? AND status='ACTIVE'"
        ).use { ps ->
            ps.setString(1, bookId); ps.setString(2, ruleType); ps.setString(3, matchPayload)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val p = OverridePriority.valueOf(rs.getString("priority"))
                    if (oldStrongest == null || p.ordinal < oldStrongest!!.ordinal) oldStrongest = p
                }
            }
        }
        // 新规则弱于现有最强 → 不生效（DISABLED）
        if (oldStrongest != null && priority.ordinal > oldStrongest!!.ordinal) {
            val id = conn.prepareStatement(
                "INSERT INTO override_rule(book_id,scope,rule_type,match_payload,action_payload,priority,status,user_locked) VALUES(?,?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setString(1, bookId); ps.setString(2, scope); ps.setString(3, ruleType)
                ps.setString(4, matchPayload); ps.setString(5, actionPayload)
                ps.setString(6, priority.name); ps.setString(7, OverrideStatus.DISABLED.name)
                ps.setInt(8, 1)
                ps.executeUpdate()
                ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
            }
            return getOverride(id)!!
        }
        // 新优先级不弱于旧 → supersede 旧（同强或更强）
        conn.prepareStatement(
            "UPDATE override_rule SET status='SUPERSEDED' WHERE book_id=? AND rule_type=? AND match_payload=? AND status='ACTIVE'"
        ).use { ps ->
            ps.setString(1, bookId); ps.setString(2, ruleType); ps.setString(3, matchPayload)
            ps.executeUpdate()
        }
        val id = conn.prepareStatement(
            "INSERT INTO override_rule(book_id,scope,rule_type,match_payload,action_payload,priority,status,user_locked) VALUES(?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, bookId); ps.setString(2, scope); ps.setString(3, ruleType)
            ps.setString(4, matchPayload); ps.setString(5, actionPayload)
            ps.setString(6, priority.name); ps.setString(7, OverrideStatus.ACTIVE.name)
            ps.setInt(8, 1)
            ps.executeUpdate()
            ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
        }
        return getOverride(id)!!
    }

    fun getOverride(id: Long): OverrideRule? = conn.prepareStatement("SELECT * FROM override_rule WHERE rule_id=?").use { ps ->
        ps.setLong(1, id)
        ps.executeQuery().use { rs -> if (rs.next()) overrideRow(rs) else null }
    }

    fun activeOverrides(bookId: String): List<OverrideRule> = conn.prepareStatement(
        "SELECT * FROM override_rule WHERE book_id=? AND status='ACTIVE' ORDER BY " +
            "CASE priority WHEN 'USER_LOCKED' THEN 0 WHEN 'USER_OVERRIDE' THEN 1 WHEN 'SYSTEM_CONFIRMED' THEN 2 ELSE 3 END"
    ).use { ps ->
        ps.setString(1, bookId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<OverrideRule>()
            while (rs.next()) out += overrideRow(rs)
            out
        }
    }

    /** USER_LOCKED 最高（§39）：自动重新解析时先加载 Override。 */
    fun isLocked(bookId: String, ruleType: String, matchPayload: String): Boolean =
        conn.prepareStatement(
            "SELECT count(*) FROM override_rule WHERE book_id=? AND rule_type=? AND match_payload=? AND status='ACTIVE' AND priority='USER_LOCKED'"
        ).use { ps ->
            ps.setString(1, bookId); ps.setString(2, ruleType); ps.setString(3, matchPayload)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
        }

    fun markNeedsRebind(ruleId: Long) {
        conn.prepareStatement("UPDATE override_rule SET status='NEEDS_REBIND' WHERE rule_id=?").use { ps ->
            ps.setLong(1, ruleId); ps.executeUpdate()
        }
    }

    // ---------- ParagraphLineage（§31/§32） ----------

    fun recordLineage(fromParagraphPk: Long, toParagraphPk: Long, type: LineageType): Lineage {
        conn.prepareStatement(
            "INSERT INTO paragraph_lineage(from_paragraph_pk,to_paragraph_pk,lineage_type,created_at) VALUES(?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, fromParagraphPk); ps.setLong(2, toParagraphPk)
            ps.setString(3, type.name); ps.setString(4, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
        return Lineage(fromParagraphPk, toParagraphPk, type, java.time.Instant.now().toString())
    }

    fun lineageOf(paragraphPk: Long): List<Lineage> = conn.prepareStatement(
        "SELECT * FROM paragraph_lineage WHERE from_paragraph_pk=? OR to_paragraph_pk=? ORDER BY created_at"
    ).use { ps ->
        ps.setLong(1, paragraphPk); ps.setLong(2, paragraphPk)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<Lineage>()
            while (rs.next()) out += Lineage(rs.getLong("from_paragraph_pk"), rs.getLong("to_paragraph_pk"), LineageType.valueOf(rs.getString("lineage_type")), rs.getString("created_at"))
            out
        }
    }

    private fun correctionRow(rs: java.sql.ResultSet) = CorrectionEvent(
        correctionId = rs.getLong("correction_id"),
        bookId = rs.getString("book_id"),
        sourceRevisionId = rs.getString("source_revision_id"),
        type = rs.getString("type"),
        targetType = rs.getString("target_type"),
        targetId = rs.getString("target_id"),
        oldPayload = rs.getString("old_payload"),
        newPayload = rs.getString("new_payload"),
        userLocked = rs.getInt("user_locked") == 1,
        createdAt = rs.getString("created_at"),
        compiledOverrideId = if (rs.getObject("compiled_override_id") == null) null else rs.getLong("compiled_override_id"),
    )

    private fun overrideRow(rs: java.sql.ResultSet) = OverrideRule(
        ruleId = rs.getLong("rule_id"),
        bookId = rs.getString("book_id"),
        scope = rs.getString("scope"),
        ruleType = rs.getString("rule_type"),
        matchPayload = rs.getString("match_payload"),
        actionPayload = rs.getString("action_payload"),
        priority = OverridePriority.valueOf(rs.getString("priority")),
        status = OverrideStatus.valueOf(rs.getString("status")),
        userLocked = rs.getInt("user_locked") == 1,
    )
}
