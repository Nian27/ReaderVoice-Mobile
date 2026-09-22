package com.readervoice.data.character

/**
 * LegacyBehavior Adapter（TASK-060 §47）：LegacyBehaviorRecord → CharacterObservation。
 * provenance 保留 LEGACY_*（绝不升级成 Gold，§85/§47）。
 */
object LegacyBehaviorAdapter {

    private fun legacyOutput(record: Map<String, Any?>): Map<*, *>? =
        record["legacy_output"] as? Map<*, *>

    fun toObservation(record: Map<String, Any?>): CharacterObservation? {
        val behavior = record["behavior_type"] as? String ?: return null
        val provenance = "LEGACY_${record["legacy_version"] ?: "V907"}"
        val out = legacyOutput(record)
        val surface = out?.get("speaker") as? String
        val target = out?.get("target") as? String
        val position = (record["narrative_position"] as? Map<*, *>)?.get("source_codepoint_start") as? Number
        val paragraphId = (record["paragraph_uid"] as? String)?.hashCode()?.toLong()

        return when (behavior) {
            "IDENTITY_SAME" -> CharacterObservation(
                ObservationType.SAME_IDENTITY_EVIDENCE, paragraphId, null, null, null, null,
                payload = mapOf<String, Any?>(
                    Pair<String, Any?>("entity_a", surface),
                    Pair<String, Any?>("entity_b", target),
                    Pair<String, Any?>("strength", 2.0),
                    Pair<String, Any?>("legacy_weight", 2.0),
                ),
                narrativePosition = position?.toLong(), provenance = provenance,
            )
            "IDENTITY_DIFFERENT" -> CharacterObservation(
                ObservationType.DIFFERENT_IDENTITY_EVIDENCE, paragraphId, null, null, surface, null,
                payload = mapOf<String, Any?>(
                    Pair<String, Any?>("entity_a", surface),
                    Pair<String, Any?>("entity_b", target),
                    Pair<String, Any?>("hard_block", true),
                    Pair<String, Any?>("strength", 4.0),
                ),
                narrativePosition = position?.toLong(), provenance = provenance,
            )
            "RELATION_EVIDENCE" -> CharacterObservation(
                ObservationType.RELATIONSHIP, paragraphId, null, null, null, null,
                payload = mapOf<String, Any?>(
                    Pair<String, Any?>("relation", out?.get("relation")?.toString() ?: "KINSHIP"),
                    Pair<String, Any?>("entity_a", surface),
                    Pair<String, Any?>("entity_b", target),
                ),
                narrativePosition = position?.toLong(), provenance = provenance,
            )
            "TEMP_VOICE_START", "TEMP_VOICE_CONTINUE", "TEMP_VOICE_REPLACE", "TEMP_VOICE_END" -> CharacterObservation(
                ObservationType.TEMP_VOICE_EVENT, paragraphId, null, null, surface, null,
                payload = mapOf<String, Any?>(
                    Pair<String, Any?>("action", behavior.removePrefix("TEMP_VOICE_")),
                    Pair<String, Any?>("identity", surface),
                    Pair<String, Any?>("scope", "scene"),
                ),
                narrativePosition = position?.toLong(), provenance = provenance,
            )
            "VOICE_AGE_UPDATE" -> CharacterObservation(
                ObservationType.VOICE_AGE_EVIDENCE, paragraphId, null, null, surface, null,
                payload = mapOf<String, Any?>(
                    Pair<String, Any?>("voice_age", out?.get("voice_age")?.toString() ?: "UNKNOWN"),
                ),
                narrativePosition = position?.toLong(), provenance = provenance,
            )
            else -> null // 其他类型不映射（§83：不创造）
        }
    }
}
