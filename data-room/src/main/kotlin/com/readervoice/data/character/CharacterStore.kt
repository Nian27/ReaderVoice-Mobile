package com.readervoice.data.character

import com.readervoice.data.Db
import java.sql.Connection

/**
 * CharacterStore（TASK-060）：character 表族持久化。
 * - book_pk NOT NULL 全程强制（DB constraint，§19）
 * - Non-destructive merge：identity_cluster_membership + identity_lineage（ADR-029）
 * - 双查询 API：resolveIdentity（whole-book）/ queryEffectiveVoiceState（causal，ADR-030）
 */
class CharacterStore(private val db: Db) : CharacterReadStore {

    private val conn: Connection get() = db.connection()

    // ---------- Entity ----------

    /** deterministic UNKNOWN uid（§17）：hash(book_uid, earliest_position, scope)。 */
    fun unknownEntityUid(bookUid: String, earliestPosition: Long): String {
        val h = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$bookUid|$earliestPosition|unknown".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "unknown-$h".take(32)
    }

    fun upsertEntity(bookPk: Long, entityUid: String, entityType: String, canonicalName: String?, status: String): Long {
        conn.prepareStatement(
            "INSERT INTO narrative_entity(entity_uid,book_pk,entity_type,canonical_name,status,created_at) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(entity_uid) DO UPDATE SET status=excluded.status"
        ).use { ps ->
            ps.setString(1, entityUid); ps.setLong(2, bookPk); ps.setString(3, entityType)
            ps.setString(4, canonicalName); ps.setString(5, status)
            ps.setString(6, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
        return conn.prepareStatement("SELECT entity_pk FROM narrative_entity WHERE entity_uid=?").use { ps ->
            ps.setString(1, entityUid)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun entityByUid(uid: String): NarrativeEntity? = conn.prepareStatement(
        "SELECT * FROM narrative_entity WHERE entity_uid=?"
    ).use { ps ->
        ps.setString(1, uid)
        ps.executeQuery().use { rs -> if (rs.next()) entityRow(rs) else null }
    }

    /** 按 canonical_name 查实体（TASK-070 NarrationIRBuilder 用）。 */
    override fun entityByCanonicalName(bookPk: Long, name: String): NarrativeEntity? = conn.prepareStatement(
        "SELECT * FROM narrative_entity WHERE book_pk=? AND canonical_name=?"
    ).use { ps ->
        ps.setLong(1, bookPk); ps.setString(2, name)
        ps.executeQuery().use { rs -> if (rs.next()) entityRow(rs) else null }
    }

    /** M3：本书全部规范名（候选编译器 entitySet）。按 entity_pk 升序 ⇒ 确定性。 */
    override fun canonicalNamesOf(bookPk: Long): Set<String> = conn.prepareStatement(
        "SELECT canonical_name FROM narrative_entity WHERE book_pk=? AND canonical_name IS NOT NULL ORDER BY entity_pk"
    ).use { ps ->
        ps.setLong(1, bookPk)
        ps.executeQuery().use { rs ->
            val out = LinkedHashSet<String>()
            while (rs.next()) rs.getString("canonical_name")?.let { out.add(it) }
            out
        }
    }

    /** 按 entity_pk 查实体。 */
    override fun entityByPk(entityPk: Long): NarrativeEntity? = conn.prepareStatement(
        "SELECT * FROM narrative_entity WHERE entity_pk=?"
    ).use { ps ->
        ps.setLong(1, entityPk)
        ps.executeQuery().use { rs -> if (rs.next()) entityRow(rs) else null }
    }

    /** 同一 cluster 的成员 canonical_name（TASK-070 ContextBuilder aliases 用）。 */
    override fun aliasesOf(bookPk: Long, clusterId: String): List<String> = conn.prepareStatement(
        "SELECT ne.canonical_name FROM identity_cluster_membership icm " +
            "JOIN narrative_entity ne ON ne.entity_pk=icm.entity_pk " +
            "WHERE icm.book_pk=? AND icm.cluster_id=? ORDER BY ne.entity_pk"
    ).use { ps ->
        ps.setLong(1, bookPk); ps.setString(2, clusterId)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<String>()
            while (rs.next()) rs.getString(1)?.let { out += it }
            out
        }
    }

    // ---------- Mention ----------

    fun insertMention(rev: Long, bookPk: Long, m: CharacterObservation, paragraphPk: Long?): Long {
        val payload = m.payload
        val surfaceHash = (m.surface ?: "").hashCode().toLong()
        val resolved = payload["resolved_entity_pk"] as? Number
        val candidates = payload["candidate_entity_pks"] as? List<*> ?: emptyList<Any?>()
        return conn.prepareStatement(
            "INSERT INTO mention(character_revision_id,book_pk,paragraph_revision_pk,source_start,source_end,surface_hash,mention_type," +
                "resolved_entity_pk,candidate_entity_pks,confidence,provenance,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setLong(1, rev); ps.setLong(2, bookPk)
            if (paragraphPk != null) ps.setLong(3, paragraphPk) else ps.setNull(3, java.sql.Types.BIGINT)
            if (m.sourceStart != null) ps.setInt(4, m.sourceStart) else ps.setNull(4, java.sql.Types.INTEGER)
            if (m.sourceEnd != null) ps.setInt(5, m.sourceEnd) else ps.setNull(5, java.sql.Types.INTEGER)
            ps.setLong(6, surfaceHash); ps.setString(7, "MENTION")
            if (resolved != null) ps.setLong(8, resolved.toLong()) else ps.setNull(8, java.sql.Types.BIGINT)
            ps.setString(9, candidates.joinToString(",")); ps.setNull(10, java.sql.Types.DOUBLE)
            ps.setString(11, m.provenance); ps.setString(12, java.time.Instant.now().toString())
            ps.executeUpdate()
            ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
        }
    }

    /** Mention 永不删除（§5）；查询带 book_pk。 */
    fun mentionsInRange(bookPk: Long, paraPk: Long): List<Mention> = conn.prepareStatement(
        "SELECT * FROM mention WHERE book_pk=? AND paragraph_revision_pk=?"
    ).use { ps ->
        ps.setLong(1, bookPk); ps.setLong(2, paraPk)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<Mention>()
            while (rs.next()) out += Mention(
                rs.getLong("mention_id"), rs.getLong("character_revision_id"), rs.getLong("book_pk"),
                rs.getLong("paragraph_revision_pk"), rs.getInt("source_start"), rs.getInt("source_end"),
                rs.getString("surface_hash"), rs.getString("mention_type"),
                if (rs.getObject("resolved_entity_pk") == null) null else rs.getLong("resolved_entity_pk"),
                rs.getString("candidate_entity_pks")?.split(",")?.filter { it.isNotBlank() }?.map { it.toLong() } ?: emptyList(),
                rs.getDouble("confidence"), rs.getString("provenance"),
            )
            out
        }
    }

    // ---------- Identity Evidence（§7/§8/§9） ----------

    fun insertEvidence(e: IdentityEvidence): Long = conn.prepareStatement(
        "INSERT INTO identity_evidence(character_revision_id,book_pk,entity_a_pk,entity_b_pk,relation_type,sign,strength,hard_block," +
            "narrative_position,provenance,legacy_weight,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        java.sql.Statement.RETURN_GENERATED_KEYS,
    ).use { ps ->
        ps.setLong(1, e.characterRevisionId); ps.setLong(2, e.bookPk)
        ps.setLong(3, e.entityAPk); ps.setLong(4, e.entityBPk)
        ps.setString(5, e.relationType); ps.setString(6, e.sign)
        ps.setDouble(7, e.strength); ps.setInt(8, if (e.hardBlock) 1 else 0)
        if (e.narrativePosition != null) ps.setLong(9, e.narrativePosition) else ps.setNull(9, java.sql.Types.BIGINT)
        ps.setString(10, e.provenance)
        if (e.legacyWeight != null) ps.setDouble(11, e.legacyWeight) else ps.setNull(11, java.sql.Types.DOUBLE)
        ps.setString(12, java.time.Instant.now().toString())
        ps.executeUpdate()
        ps.generatedKeys.use { ks -> ks.next(); ks.getLong(1) }
    }

    /** Hard negative 存在 → auto merge 禁止（§9/§58）。 */
    override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean =
        conn.prepareStatement(
            "SELECT count(*) FROM identity_evidence WHERE book_pk=? AND hard_block=1 AND " +
                "((entity_a_pk=? AND entity_b_pk=?) OR (entity_a_pk=? AND entity_b_pk=?))"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setLong(2, entityAPk); ps.setLong(3, entityBPk)
            ps.setLong(4, entityBPk); ps.setLong(5, entityAPk)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
        }

    /** 成对证据摘要（TASK-080 Identity Protocol 输入）：relation_type+sign → count（双向）。 */
    override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        conn.prepareStatement(
            "SELECT relation_type, sign, count(*) c FROM identity_evidence WHERE book_pk=? " +
                "AND ((entity_a_pk=? AND entity_b_pk=?) OR (entity_a_pk=? AND entity_b_pk=?)) " +
                "GROUP BY relation_type, sign"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setLong(2, entityAPk); ps.setLong(3, entityBPk)
            ps.setLong(4, entityBPk); ps.setLong(5, entityAPk)
            ps.executeQuery().use { rs ->
                while (rs.next()) out["${rs.getString("relation_type")}:${rs.getString("sign")}"] = rs.getInt("c")
            }
        }
        return out
    }

    /** 关系/控制类证据默认 ≠ SAME_PERSON（§8/§25）：SAME_PERSON 仅显式。 */
    private val NON_IDENTITY_RELATIONS = setOf(
        "KINSHIP", "SOCIAL_RELATION", "POSSESSION", "CONTROL", "EMBODIMENT", "IMITATION",
    )

    fun canBeSamePersonEvidence(relationType: String): Boolean = relationType == "SAME_PERSON" || relationType == "ALIAS"

    /** 合并候选：无 hard negative + 存在 positive SAME/ALIAS 证据（False Merge > False Split：不足则 PROVISIONAL）。 */
    fun mergeCandidate(bookPk: Long, entityAPk: Long, entityBPk: Long, minStrength: Double = 1.5): Boolean {
        if (hasHardNegative(bookPk, entityAPk, entityBPk)) return false
        return conn.prepareStatement(
            "SELECT count(*) FROM identity_evidence WHERE book_pk=? AND sign='POSITIVE' AND relation_type IN ('SAME_PERSON','ALIAS') " +
                "AND strength>=? AND ((entity_a_pk=? AND entity_b_pk=?) OR (entity_a_pk=? AND entity_b_pk=?))"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setDouble(2, minStrength)
            ps.setLong(3, entityAPk); ps.setLong(4, entityBPk)
            ps.setLong(5, entityBPk); ps.setLong(6, entityAPk)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) > 0 }
        }
    }

    // ---------- Non-destructive Merge / Split（ADR-029） ----------

    /** Merge：cluster membership + lineage（旧 entity/evidence 保留，§12/§13/§16）。 */
    fun mergeInto(rev: Long, bookPk: Long, sourceEntityPk: Long, targetEntityPk: Long, reason: String, evidenceIds: List<Long>, automatic: Boolean) {
        conn.autoCommit = false
        try {
            val clusterId = "cluster-${System.nanoTime()}-$targetEntityPk"
            for (ep in listOf(sourceEntityPk, targetEntityPk)) {
                conn.prepareStatement(
                    "INSERT INTO identity_cluster_membership(character_revision_id,book_pk,entity_pk,cluster_id,joined_at) VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(book_pk,entity_pk) DO UPDATE SET cluster_id=excluded.cluster_id"
                ).use { ps ->
                    ps.setLong(1, rev); ps.setLong(2, bookPk); ps.setLong(3, ep)
                    ps.setString(4, clusterId); ps.setString(5, java.time.Instant.now().toString())
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement(
                "INSERT INTO identity_lineage(book_pk,from_entity_pk,to_entity_pk,lineage_type,created_at) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, bookPk); ps.setLong(2, sourceEntityPk); ps.setLong(3, targetEntityPk)
                ps.setString(4, "MERGED_INTO"); ps.setString(5, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Split：cluster membership 拆出 + SPLIT_FROM lineage（mention/evidence 不丢，§14/§15）。 */
    fun splitFrom(rev: Long, bookPk: Long, restoredEntityPk: Long, fromClusterId: String, reason: String) {
        conn.autoCommit = false
        try {
            val newCluster = "cluster-${System.nanoTime()}-$restoredEntityPk"
            conn.prepareStatement(
                "UPDATE identity_cluster_membership SET cluster_id=? WHERE book_pk=? AND entity_pk=?"
            ).use { ps ->
                ps.setString(1, newCluster); ps.setLong(2, bookPk); ps.setLong(3, restoredEntityPk)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO identity_lineage(book_pk,from_entity_pk,to_entity_pk,lineage_type,created_at) VALUES(?,?,?,?,?)"
            ).use { ps ->
                // 从集群分裂：记录 from=cluster 代表（target）→ to=restored
                ps.setLong(1, bookPk); ps.setLong(2, restoredEntityPk); ps.setLong(3, restoredEntityPk)
                ps.setString(4, "SPLIT_FROM"); ps.setString(5, java.time.Instant.now().toString())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** resolveIdentity（whole-book，§21/§63）：允许未来证据；返回 cluster/status。 */
    override fun resolveIdentity(bookPk: Long, entityPk: Long): IdentityResolution {
        val status = conn.prepareStatement("SELECT status FROM narrative_entity WHERE entity_pk=?").use { ps ->
            ps.setLong(1, entityPk)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("status") else "UNKNOWN" }
        }
        val cluster = conn.prepareStatement(
            "SELECT cluster_id FROM identity_cluster_membership WHERE book_pk=? AND entity_pk=?"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setLong(2, entityPk)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("cluster_id") else null }
        }
        return IdentityResolution(entityPk, cluster, status)
    }

    /** queryEffectiveVoiceState（causal，§22/§63）：只允许 position 之前证据。 */
    override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long): EffectiveVoiceState {
        val phases = conn.prepareStatement(
            "SELECT * FROM voice_phase_interval WHERE book_pk=? AND entity_pk=? AND start_position<=? ORDER BY start_position DESC LIMIT 1"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setLong(2, entityPk); ps.setLong(3, position)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("phase") else null
            }
        }
        val temp = conn.prepareStatement(
            "SELECT * FROM temporary_voice_event WHERE book_pk=? AND identity_entity_pk=? AND narrative_position<=? ORDER BY narrative_position DESC LIMIT 1"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setLong(2, entityPk); ps.setLong(3, position)
            ps.executeQuery().use { rs ->
                if (rs.next()) Pair(rs.getString("action"), rs.getString("payload_json")) else null
            }
        }
        val attrs = mutableMapOf<String, CharacterAttribute>()
        conn.prepareStatement(
            "SELECT * FROM character_attribute WHERE book_pk=? AND entity_pk=? AND (narrative_position IS NULL OR narrative_position<=?)"
        ).use { ps ->
            ps.setLong(1, bookPk); ps.setLong(2, entityPk); ps.setLong(3, position)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val a = CharacterAttribute(
                        rs.getLong("attribute_id"), rs.getLong("character_revision_id"), rs.getLong("book_pk"),
                        rs.getLong("entity_pk"), rs.getString("attribute"), rs.getString("value"),
                        rs.getDouble("confidence"), rs.getString("source"),
                        if (rs.getObject("narrative_position") == null) null else rs.getLong("narrative_position"),
                        rs.getInt("user_override") == 1,
                    )
                    attrs[a.attribute] = a
                }
            }
        }
        return EffectiveVoiceState(entityPk, phases, temp?.first, temp?.second, attrs)
    }

    // ---------- Embodiment / Voice events ----------

    fun insertEmbodiment(rev: Long, bookPk: Long, identityPk: Long, bodyPk: Long, stateType: String, start: Long, end: Long?, confidence: Double?) {
        conn.prepareStatement(
            "INSERT INTO embodiment_interval(character_revision_id,book_pk,identity_entity_pk,body_entity_pk,state_type,start_position,end_position,confidence,created_at) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, rev); ps.setLong(2, bookPk); ps.setLong(3, identityPk); ps.setLong(4, bodyPk)
            ps.setString(5, stateType); ps.setLong(6, start)
            if (end != null) ps.setLong(7, end) else ps.setNull(7, java.sql.Types.BIGINT)
            if (confidence != null) ps.setDouble(8, confidence) else ps.setNull(8, java.sql.Types.DOUBLE)
            ps.setString(9, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
    }

    /** whoIsActingThrough(body, position)（§26）。 */
    override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? = conn.prepareStatement(
        "SELECT identity_entity_pk FROM embodiment_interval WHERE book_pk=? AND body_entity_pk=? AND start_position<=? " +
            "AND (end_position IS NULL OR end_position>=?) ORDER BY start_position DESC LIMIT 1"
    ).use { ps ->
        ps.setLong(1, bookPk); ps.setLong(2, bodyEntityPk); ps.setLong(3, position); ps.setLong(4, position)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }

    /** 同 whoIsActingThrough，另返回 state_type（TASK-070 EmbodimentHint 用）。 */
    override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? = conn.prepareStatement(
        "SELECT identity_entity_pk, state_type FROM embodiment_interval WHERE book_pk=? AND body_entity_pk=? AND start_position<=? " +
            "AND (end_position IS NULL OR end_position>=?) ORDER BY start_position DESC LIMIT 1"
    ).use { ps ->
        ps.setLong(1, bookPk); ps.setLong(2, bodyEntityPk); ps.setLong(3, position); ps.setLong(4, position)
        ps.executeQuery().use { rs -> if (rs.next()) Pair(rs.getLong(1), rs.getString("state_type") ?: "POSSESSION") else null }
    }

    fun insertTempVoiceEvent(rev: Long, bookPk: Long, entityPk: Long, action: String, stateType: String, payload: String?, position: Long, scope: String, provenance: String) {
        conn.prepareStatement(
            "INSERT INTO temporary_voice_event(character_revision_id,book_pk,identity_entity_pk,action,state_type,payload_json,narrative_position,scope,provenance,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, rev); ps.setLong(2, bookPk); ps.setLong(3, entityPk)
            ps.setString(4, action); ps.setString(5, stateType); ps.setString(6, payload)
            ps.setLong(7, position); ps.setString(8, scope); ps.setString(9, provenance)
            ps.setString(10, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
    }

    fun insertVoicePhase(rev: Long, bookPk: Long, entityPk: Long, phase: String, start: Long, end: Long?, confidence: Double?) {
        conn.prepareStatement(
            "INSERT INTO voice_phase_interval(character_revision_id,book_pk,entity_pk,phase,start_position,end_position,confidence,created_at) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, rev); ps.setLong(2, bookPk); ps.setLong(3, entityPk)
            ps.setString(4, phase); ps.setLong(5, start)
            if (end != null) ps.setLong(6, end) else ps.setNull(6, java.sql.Types.BIGINT)
            if (confidence != null) ps.setDouble(7, confidence) else ps.setNull(7, java.sql.Types.DOUBLE)
            ps.setString(8, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
    }

    fun setAttribute(rev: Long, bookPk: Long, entityPk: Long, attribute: String, value: String, source: String, position: Long?, userOverride: Boolean, confidence: Double?) {
        conn.prepareStatement(
            "INSERT INTO character_attribute(character_revision_id,book_pk,entity_pk,attribute,value,confidence,source,narrative_position,user_override,created_at) VALUES(?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(book_pk,entity_pk,attribute) DO UPDATE SET value=excluded.value, confidence=excluded.confidence, user_override=excluded.user_override"
        ).use { ps ->
            ps.setLong(1, rev); ps.setLong(2, bookPk); ps.setLong(3, entityPk)
            ps.setString(4, attribute); ps.setString(5, value)
            if (confidence != null) ps.setDouble(6, confidence) else ps.setNull(6, java.sql.Types.DOUBLE)
            ps.setString(7, source)
            if (position != null) ps.setLong(8, position) else ps.setNull(8, java.sql.Types.BIGINT)
            ps.setInt(9, if (userOverride) 1 else 0)
            ps.setString(10, java.time.Instant.now().toString())
            ps.executeUpdate()
        }
    }

    private fun entityRow(rs: java.sql.ResultSet) = NarrativeEntity(
        rs.getLong("entity_pk"), rs.getString("entity_uid"), rs.getLong("book_pk"),
        rs.getString("entity_type"), rs.getString("canonical_name"), rs.getString("status"),
    )
}

