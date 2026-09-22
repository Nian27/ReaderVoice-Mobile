package com.readervoice.data.character

/**
 * TASK-060 域模型（CharacterObservation 协议 + 状态对象）。
 * 输入 = Sequence<CharacterObservation>（非 wholeBookText，PERF060-P2/P3 契约，ADR-032）。
 */

/** CharacterObservation（§4）：上游（规则/Exporter/未来 ReaderDirector）给状态系统的输入。 */
data class CharacterObservation(
    val type: ObservationType,
    val paragraphRevisionId: Long?,
    val sourceStart: Int?,
    val sourceEnd: Int?,
    val surface: String?,
    val entityHint: String?,
    val payload: Map<String, Any?> = emptyMap(),
    val narrativePosition: Long? = null,
    val provenance: String, // EXPLICIT_RULE / LEGACY_* / HUMAN_GOLD / ...
)

enum class ObservationType {
    MENTION, SAME_IDENTITY_EVIDENCE, DIFFERENT_IDENTITY_EVIDENCE, RELATIONSHIP, CO_PRESENCE,
    CONTROL, POSSESSION, EMBODIMENT, IMITATION, VOICE_AGE_EVIDENCE, TEMP_VOICE_EVENT,
}

/** Mention（§5）：文本发生事实，永不因 Merge 删除。 */
data class Mention(
    val mentionId: Long,
    val characterRevisionId: Long,
    val bookPk: Long,
    val paragraphRevisionPk: Long,
    val sourceStart: Int,
    val sourceEnd: Int,
    val surfaceHash: String,
    val mentionType: String,
    val resolvedEntityPk: Long?,
    val candidateEntityPks: List<Long>,
    val confidence: Double?,
    val provenance: String,
)

/** NarrativeEntity（§6）。 */
data class NarrativeEntity(
    val entityPk: Long,
    val entityUid: String,   // deterministic
    val bookPk: Long,
    val entityType: String,  // PERSON/GROUP/NARRATOR/SYSTEM/NON_PERSON_AGENT/UNKNOWN
    val canonicalName: String?,
    val status: String,      // CANDIDATE/PROVISIONAL/CONFIRMED
)

/** IdentityEvidence（§7/§8）：关系/身份证据；KINSHIP 等默认 ≠ SAME_PERSON。 */
data class IdentityEvidence(
    val evidenceId: Long,
    val characterRevisionId: Long,
    val bookPk: Long,
    val entityAPk: Long,
    val entityBPk: Long,
    val relationType: String, // SAME_PERSON/DIFFERENT_PERSON/ALIAS/TITLE/KINSHIP/SOCIAL_RELATION/CO_PRESENCE/POSSESSION/CONTROL/EMBODIMENT/IMITATION
    val sign: String,         // POSITIVE/NEGATIVE/NEUTRAL
    val strength: Double,
    val hardBlock: Boolean,   // DIFFERENT_PERSON hard（§9）
    val narrativePosition: Long?,
    val provenance: String,
    val legacyWeight: Double? = null,
)

/** EmbodimentInterval（§26）。 */
data class EmbodimentInterval(
    val intervalId: Long,
    val characterRevisionId: Long,
    val bookPk: Long,
    val identityEntityPk: Long,  // 魔尊
    val bodyEntityPk: Long,      // 林雪
    val stateType: String,       // CONTROL/EMBODIMENT/IMITATION/POSSESSION
    val startPosition: Long,
    val endPosition: Long?,
    val confidence: Double?,
)

/** TemporaryVoiceEvent（§32）：事实来源；EffectiveInterval 可编译但事件是真相。 */
data class TemporaryVoiceEvent(
    val eventId: Long,
    val characterRevisionId: Long,
    val bookPk: Long,
    val identityEntityPk: Long,
    val action: String,        // START/CONTINUE/REPLACE/END
    val stateType: String,
    val payload: String?,
    val narrativePosition: Long,
    val scope: String,         // current_dialogue/scene/persistent/uncertain
    val provenance: String,
)

/** VoicePhaseInterval（§30）。 */
data class VoicePhaseInterval(
    val intervalId: Long,
    val characterRevisionId: Long,
    val bookPk: Long,
    val entityPk: Long,
    val phase: String,         // CHILD/TEEN/YOUNG/MIDDLE/OLD
    val startPosition: Long,
    val endPosition: Long?,
    val confidence: Double?,
)

/** CharacterAttribute（§28/§29）。 */
data class CharacterAttribute(
    val attributeId: Long,
    val characterRevisionId: Long,
    val bookPk: Long,
    val entityPk: Long,
    val attribute: String,     // gender_style/chronological_age/appearance_age/voice_age
    val value: String,
    val confidence: Double?,
    val source: String,
    val narrativePosition: Long?,
    val userOverride: Boolean,
)

/** IdentityResolution（§63 双查询 API 之一的结果）。 */
data class IdentityResolution(val entityPk: Long, val clusterId: String?, val status: String)

/** EffectiveVoiceState（§63 双查询 API 之二，causal）。 */
data class EffectiveVoiceState(
    val entityPk: Long,
    val phase: String?,                 // 长期阶段
    val temporaryAction: String?,       // START/CONTINUE/REPLACE/END/NONE
    val temporaryPayload: String?,
    val baseAttributes: Map<String, CharacterAttribute>,
)
