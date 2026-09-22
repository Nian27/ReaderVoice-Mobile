package com.readervoice.data.character

import com.readervoice.data.Db
import com.readervoice.data.RevisionStore

/**
 * CharacterCompiler（TASK-060 §42-§44）：流式处理 CharacterObservation。
 * - 输入 Sequence（chapter/window → batch → release，PERF060-P3，ADR-032）
 * - 输出：CharacterRevision（CHARACTER ArtifactType）+ 状态落库
 * - False Merge > False Split（§10）：证据不足 → PROVISIONAL/UNKNOWN，不硬合并
 */
class CharacterCompiler(
    private val db: Db,
    private val revisions: RevisionStore,
    private val store: CharacterStore,
) {

    data class CompileResult(
        val characterRevisionId: Long,
        val observationsProcessed: Int,
        val mentionsInserted: Int,
        val entitiesCreated: Int,
        val evidenceInserted: Int,
        val mergeAttempted: Int,
        val mergeExecuted: Int,
    )

    /**
     * 流式编译一个 chapter/window 的 observations（batch persist + 内部状态释放）。
     * @param bookPk 书（book_pk NOT NULL 强制）
     * @param sourceRevisionId 来源（用于 CHARACTER ArtifactRevision）
     * @param observations 本窗口观察序列（上游产生，非 whole-book 驻留）
     */
    fun compile(
        bookPk: Long,
        sourceRevisionId: String,
        observations: Sequence<CharacterObservation>,
        parentRevisionId: Long? = null,
    ): CompileResult {
        // 窗口幂等：同 input hash 复用（obs 哈希）
        val obsList = observations.toList()
        val inputHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(obsList.joinToString("|") { "${it.type}:${it.surface}:${it.narrativePosition}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val owner = "book:$bookPk"
        val rev = revisions.create(
            artifactType = "CHARACTER", ownerScope = owner,
            bookId = "$bookPk", sourceRevisionId = sourceRevisionId,
            parentRevisionId = parentRevisionId ?: revisions.head(owner, "PARAGRAPH_RECOVERY")?.activeRevisionId,
            inputHash = inputHash, algorithmVersion = "character_compiler_v1",
        )
        revisions.head(owner, "PARAGRAPH_RECOVERY")?.let { p ->
            revisions.addDependency(owner, rev.revisionId, p.activeRevisionId)
        }

        var mentions = 0
        var entities = 0
        var evidence = 0
        var mergeAttempt = 0
        var mergeExec = 0
        val seenUids = mutableMapOf<String, Long>() // surface → entity（窗口内缓存；跨窗口持久化按书查询）

        for (obs in obsList) {
            when (obs.type) {
                ObservationType.MENTION -> {
                    val surface = obs.surface ?: continue
                    val entityPk = resolveOrCreate(bookPk, surface, obs.entityHint, seenUids)
                    if (entityPk != null) entities++
                    store.insertMention(rev.revisionId, bookPk, obs, obs.paragraphRevisionId)
                    mentions++
                }
                ObservationType.SAME_IDENTITY_EVIDENCE -> {
                    val a = resolveOrCreate(bookPk, obs.payload["entity_a"] as? String, obs.entityHint, seenUids)
                    val b = resolveOrCreate(bookPk, obs.payload["entity_b"] as? String, obs.entityHint, seenUids)
                    if (a != null && b != null && a != b) {
                        store.insertEvidence(IdentityEvidence(
                            evidenceId = 0, characterRevisionId = rev.revisionId, bookPk = bookPk,
                            entityAPk = a, entityBPk = b, relationType = "ALIAS", sign = "POSITIVE",
                            strength = (obs.payload["strength"] as? Number)?.toDouble() ?: 2.0,
                            hardBlock = false, narrativePosition = obs.narrativePosition,
                            provenance = obs.provenance,
                            legacyWeight = (obs.payload["legacy_weight"] as? Number)?.toDouble(),
                        ))
                        evidence++
                        mergeAttempt++
                        if (store.mergeCandidate(bookPk, a, b)) {
                            store.mergeInto(rev.revisionId, bookPk, b, a, "auto-same-evidence", emptyList(), automatic = true)
                            mergeExec++
                        }
                    }
                }
                ObservationType.DIFFERENT_IDENTITY_EVIDENCE -> {
                    val (a, b) = pairOf(bookPk, obs, seenUids) ?: continue
                    store.insertEvidence(IdentityEvidence(
                        evidenceId = 0, characterRevisionId = rev.revisionId, bookPk = bookPk,
                        entityAPk = a, entityBPk = b, relationType = "DIFFERENT_PERSON", sign = "NEGATIVE",
                        strength = (obs.payload["strength"] as? Number)?.toDouble() ?: 4.0,
                        hardBlock = (obs.payload["hard_block"] as? Boolean) ?: true,
                        narrativePosition = obs.narrativePosition, provenance = obs.provenance,
                    ))
                    evidence++
                }
                ObservationType.RELATIONSHIP -> {
                    val (a, b) = pairOf(bookPk, obs, seenUids) ?: continue
                    // KINSHIP/SOCIAL 默认 ≠ SAME_PERSON（§8/§56）：只记关系证据，绝不转正证
                    store.insertEvidence(IdentityEvidence(
                        evidenceId = 0, characterRevisionId = rev.revisionId, bookPk = bookPk,
                        entityAPk = a, entityBPk = b, relationType = (obs.payload["relation"] as? String) ?: "KINSHIP",
                        sign = "NEUTRAL", strength = 0.0, hardBlock = false,
                        narrativePosition = obs.narrativePosition, provenance = obs.provenance,
                    ))
                    evidence++
                }
                ObservationType.EMBODIMENT, ObservationType.CONTROL, ObservationType.IMITATION, ObservationType.POSSESSION -> {
                    val identity = resolveOrCreate(bookPk, obs.payload["identity"] as? String, "PERSON", seenUids) ?: continue
                    val body = resolveOrCreate(bookPk, obs.payload["body"] as? String, "PERSON", seenUids) ?: continue
                    val start = (obs.payload["start_position"] as? Number)?.toLong() ?: obs.narrativePosition ?: 0
                    val end = (obs.payload["end_position"] as? Number)?.toLong()
                    store.insertEmbodiment(rev.revisionId, bookPk, identity, body, obs.type.name, start, end, null)
                    // CONTROL/EMBODIMENT 永远 ≠ SAME_PERSON（§25）：记录中性关系（不触发 merge）
                    store.insertEvidence(IdentityEvidence(
                        evidenceId = 0, characterRevisionId = rev.revisionId, bookPk = bookPk,
                        entityAPk = identity, entityBPk = body, relationType = obs.type.name,
                        sign = "NEUTRAL", strength = 0.0, hardBlock = false,
                        narrativePosition = obs.narrativePosition, provenance = obs.provenance,
                    ))
                    evidence++
                }
                ObservationType.VOICE_AGE_EVIDENCE -> {
                    val entity = resolveOrCreate(bookPk, obs.surface ?: obs.payload["identity"] as? String, obs.entityHint, seenUids) ?: continue
                    store.setAttribute(
                        rev.revisionId, bookPk, entity, "voice_age",
                        (obs.payload["voice_age"] as? String) ?: "UNKNOWN",
                        obs.provenance, obs.narrativePosition, userOverride = false,
                        confidence = (obs.payload["confidence"] as? Number)?.toDouble(),
                    )
                    (obs.payload["phase"] as? String)?.let { phase ->
                        store.insertVoicePhase(rev.revisionId, bookPk, entity, phase, obs.narrativePosition ?: 0, null, null)
                    }
                }
                ObservationType.TEMP_VOICE_EVENT -> {
                    val entity = resolveOrCreate(bookPk, obs.surface ?: obs.payload["identity"] as? String, obs.entityHint, seenUids) ?: continue
                    store.insertTempVoiceEvent(
                        rev.revisionId, bookPk, entity,
                        (obs.payload["action"] as? String) ?: "START",
                        (obs.payload["state_type"] as? String) ?: "TEMPORARY",
                        obs.payload["payload"] as? String,
                        obs.narrativePosition ?: 0,
                        (obs.payload["scope"] as? String) ?: "scene",
                        obs.provenance,
                    )
                }
                ObservationType.CO_PRESENCE -> {
                    // 共现 → 反证候选（v90.7 共现负边语义）：记录 NEUTRAL，不自动 merge
                    val (a, b) = pairOf(bookPk, obs, seenUids) ?: continue
                    store.insertEvidence(IdentityEvidence(
                        evidenceId = 0, characterRevisionId = rev.revisionId, bookPk = bookPk,
                        entityAPk = a, entityBPk = b, relationType = "CO_PRESENCE", sign = "NEUTRAL",
                        strength = (obs.payload["strength"] as? Number)?.toDouble() ?: 0.7,
                        hardBlock = false, narrativePosition = obs.narrativePosition, provenance = obs.provenance,
                    ))
                    evidence++
                }
            }
        }
        revisions.promote(owner, "CHARACTER", rev.revisionId)
        return CompileResult(rev.revisionId, obsList.size, mentions, entities, evidence, mergeAttempt, mergeExec)
    }

    /** resolve 或创建 entity（窗口内缓存 + 按书唯一名查库）。 */
    private fun resolveOrCreate(bookPk: Long, surface: String?, entityHint: String?, cache: MutableMap<String, Long>): Long? {
        val s = surface?.trim() ?: return null
        if (s.isEmpty() || s == "未知") return null
        cache[s]?.let { return it }
        // 按书 + canonical_name 查（cross-book 复合约束）
        var pk = 0L
        db.query("SELECT entity_pk FROM narrative_entity WHERE book_pk=$bookPk AND canonical_name='${s.replace("'", "''")}'") { rs ->
            if (rs.next()) pk = rs.getLong(1)
        }
        if (pk == 0L) {
            val uid = java.security.MessageDigest.getInstance("SHA-256")
                .digest("$bookPk|$s".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            pk = store.upsertEntity(bookPk, uid, entityHint ?: "PERSON", s, "CANDIDATE")
        }
        cache[s] = pk
        return pk
    }

    private fun pairOf(bookPk: Long, obs: CharacterObservation, cache: MutableMap<String, Long>): Pair<Long, Long>? {
        val a = resolveOrCreate(bookPk, obs.payload["entity_a"] as? String, obs.entityHint, cache)
        val b = resolveOrCreate(bookPk, obs.payload["entity_b"] as? String, obs.entityHint, cache)
        return if (a != null && b != null && a != b) a to b else null
    }

    }
