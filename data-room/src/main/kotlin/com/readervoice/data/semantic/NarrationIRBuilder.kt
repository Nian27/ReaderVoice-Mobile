package com.readervoice.data.semantic

import com.readervoice.data.character.CharacterReadStore
import com.readervoice.parser.paragraph.LogicalParagraph

/**
 * NarrationIR v1 builder（TASK-070 §3）：SemanticSegment + RuleSpeakerBaseline 结果 + CharacterStore
 * → NarrationIR[]。emotion/dialect 恒为 null（V1 不实现，§3）。
 *
 * - speaker：Surface → resolveIdentity（whole-book，G9 允许未来证据）→ NarrationSpeakerRef{identityId,status,confidence}
 * - acting_identity_id / surface_entity_id：whoIsActingThrough（G7 附身：林雪身体 → 魔尊声音）
 * - voice_state_ref：queryEffectiveVoiceState（causal，G8 只允许 position 前证据）作用于
 *   acting identity（附身时听的是附身者的声音），否则 surface 实体本身
 */
class NarrationIRBuilder(private val store: CharacterReadStore) {

    /** 声音归属实体：附身者优先，否则 surface 实体（供 DatasetExporter VOICE_STATE 复用）。 */
    fun voiceEntityPk(bookPk: Long, surface: String?, position: Long): Long? {
        val entity = surface?.let { store.entityByCanonicalName(bookPk, it) } ?: return null
        return store.whoIsActingThrough(bookPk, entity.entityPk, position) ?: entity.entityPk
    }

    fun build(
        paragraph: LogicalParagraph,
        segments: List<SemanticSegment>,
        speakerResults: Map<Int, RuleSpeakerBaseline.SpeakerResult>,
        bookPk: Long,
        position: Long, // narrative position（causal 基准，用 paragraphId 即可）
    ): List<NarrationIR> = segments.map { seg ->
        val isSpeech = seg.type == SegmentType.SPEECH || seg.type == SegmentType.INNER_MONOLOGUE
        val baseline = if (isSpeech) speakerResults[seg.segmentIndex] else null
        val surface = baseline?.speaker
        // 1) Surface → 实体（canonical_name）
        val entity = surface?.let { store.entityByCanonicalName(bookPk, it) }
        // 2) 附身（G7）：实体是身体 → 谁在通过它说话
        val acting = entity?.let { store.whoIsActingThrough(bookPk, it.entityPk, position) }
        // 3) 声音归属：附身者优先，否则 surface 实体（G8 causal 查询）
        val voiceEntityPk = acting ?: entity?.entityPk
        val vs = voiceEntityPk?.let { store.queryEffectiveVoiceState(bookPk, it, position) }
        // 4) Identity（G9：whole-book 未来证据可提升状态）
        val identityId: String? = entity?.let {
            val res = store.resolveIdentity(bookPk, it.entityPk)
            res.clusterId ?: it.entityUid
        } ?: surface // 实体未建：退化为 surface（规则层决定，不进 character 层）

        NarrationIR(
            segmentId = seg.segmentId,
            segmentType = seg.type.name.lowercase(),
            textRef = "paragraph/${paragraph.paragraphId}/segment/${seg.segmentIndex}",
            speaker = if (isSpeech && identityId != null) {
                NarrationSpeakerRef(
                    identityId = identityId,
                    status = baseline?.status ?: "UNKNOWN",
                    confidence = baseline?.confidence ?: 0.0,
                )
            } else null,
            actingIdentityId = acting?.let { store.entityByPk(it)?.entityUid },
            surfaceEntityId = entity?.entityUid,
            voiceStateRef = vs?.let(::refOf),
            language = "zh",
            emotion = null,   // V1 不实现
            dialect = null,   // V1 不实现
            provenance = baseline?.provenance ?: listOf("RULE_NARRATION"),
        )
    }

    /** EffectiveVoiceState → 紧凑摘要（phase / temp voice / age / fixed-voice override）。 */
    fun refOf(vs: com.readervoice.data.character.EffectiveVoiceState): String? {
        val parts = mutableListOf<String>()
        vs.phase?.let { parts += "phase=$it" }
        if (vs.temporaryAction != null) parts += "temp=${vs.temporaryAction}:${vs.temporaryPayload ?: ""}"
        vs.baseAttributes["voice_age"]?.let { parts += "age=${it.value}" }
        vs.baseAttributes["fixed_voice"]?.let { parts += "fixed=${it.value}" }
        return if (parts.isEmpty()) null else parts.joinToString(";")
    }
}


