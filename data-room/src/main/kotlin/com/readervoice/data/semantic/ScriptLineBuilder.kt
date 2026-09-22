package com.readervoice.data.semantic

/**
 * MOBILE-005 / M3 步骤 4c：**ScriptLineBuilder**。
 *
 * 铁律：`ScriptLine.text` **永远**是 canonical `SemanticSegment.sourceSpan` 的切片，
 * 与模型输出无关 —— 这条不留任何例外（ADR-055）。这里用运行时断言把它钉死：
 * 若 `segment.text` 与段落切片的实际内容不一致，直接 fail-closed 抛错（不允许静默继续）。
 *
 * 状态与结局分离：
 *   Director outcome（ACCEPTED_ALL/DOWNGRADE/VERIFY/REJECT）
 *     → ScriptLine（status=PENDING）
 *     → TTS/render（RENDERED）
 *     → COMMITTED（已播/不可替换）
 *   `ACCEPTED_ALL` 只表示"Director 判定可接受"，**不等于** COMMITTED；
 *   `User Locked` 挂在 ScriptLine 状态上，与模型校验状态互不混淆。
 */

enum class ScriptLineStatus { PENDING, RENDERED, COMMITTED, USER_LOCKED }

/** TTS 路由（由 speaker/type 唯一决定）。 */
enum class TtsRoute {
    AUDIO8_NARRATOR,             // NARRATOR → 旁白音色
    COSYVOICE_CHARACTER,         // C# → 角色音色（经 CharacterStore 绑定）
    UNKNOWN_DIALOGUE_FALLBACK,   // UNKNOWN → 暂不绑定具体角色音色
}

data class ScriptLine(
    val segmentId: Long,                 // canonical（SemanticSegment.segmentId）
    val paragraphId: Long,
    val segmentIndex: Int,
    /** ★ 生产文本：canonical sourceSpan 切片，与模型输出无关。 */
    val text: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    /**
     * ★ C18：**持久说话人**，只能是 [SpeakerRef]（Narrator | Unknown | Character(稳定 id)）。
     * 局部 `C#` 在该字段**绝不允许出现** —— 它在 [LocalCandidateResolver] 处已经死亡。
     */
    val speaker: SpeakerRef,
    val type: DirectorType,
    val emotion: Emotion,
    val emotionIntensity: Float,
    val delivery: Delivery,
    val voiceEvent: VoiceEvent,
    val evidence: List<String>,
    val outcome: ValidationOutcome,
    val route: TtsRoute,
    val status: ScriptLineStatus = ScriptLineStatus.PENDING,
    /** 审计：这次决策经过哪些表示层修复 / 失败。 */
    val cosmeticFixes: List<CosmeticFix> = emptyList(),
    val softFailures: List<FailureCode> = emptyList(),
) {
    /** 审计访问器（不持久化局部 ID）：仅当说话人是已确认角色时给出稳定身份 id。 */
    val speakerIdentityId: String? get() = (speaker as? SpeakerRef.Character)?.id?.value

    /** 兼容访问器（不持久化）：`NARRATOR` / `UNKNOWN` / `CHARACTER`（不再是 C#）。 */
    val speakerKind: String
        get() = when (speaker) {
            SpeakerRef.Narrator -> SpeakerRef.TOKEN_NARRATOR
            SpeakerRef.Unknown -> SpeakerRef.TOKEN_UNKNOWN
            is SpeakerRef.Character -> "CHARACTER"
        }
}

object ScriptLineBuilder {

    /**
     * @param paragraphText 该段落的 canonical 归一化文本（`LogicalParagraph.normalizedText`）
     * @return null 表示**不生成生产 ScriptLine**：REJECT，或(极少)局部候选无法解析为持久身份（C18 fail-closed）
     */
    fun build(
        paragraphText: String,
        segment: SemanticSegment,
        ctx: DirectorContext,
        result: ValidationResult,
    ): ScriptLine? {
        val d = result.decision ?: return null            // REJECT
        if (!result.ok) return null

        // ★ canonical 文本：以段落切片为准，并断言 segment.text 与之一致（fail-closed）
        val slice = paragraphText.substring(segment.sourceStart, segment.sourceEnd)
        check(slice == segment.text) {
            "CANONICAL_SPAN_MISMATCH: segment=${segment.segmentId} " +
                "slice=[${slice.take(20)}] segment.text=[${segment.text.take(20)}]"
        }

        // ★ C18：局部 C# 在此解析为持久 SpeakerRef；解析不出来就 fail-closed（绝不猜身份）
        val speakerRef = LocalCandidateResolver.toSpeakerRef(d, CandidateMap.from(ctx)) ?: return null

        val route = when (speakerRef) {
            SpeakerRef.Narrator -> TtsRoute.AUDIO8_NARRATOR
            SpeakerRef.Unknown -> TtsRoute.UNKNOWN_DIALOGUE_FALLBACK
            is SpeakerRef.Character -> TtsRoute.COSYVOICE_CHARACTER
        }

        return ScriptLine(
            segmentId = segment.segmentId,
            paragraphId = segment.paragraphRevisionId,
            segmentIndex = segment.segmentIndex,
            text = slice,
            sourceStart = segment.sourceStart,
            sourceEnd = segment.sourceEnd,
            speaker = speakerRef,
            type = d.type,
            emotion = d.emotion,
            emotionIntensity = d.emotionIntensity,
            delivery = d.delivery,
            voiceEvent = d.voiceEvent,
            evidence = d.evidence,
            outcome = result.outcome,
            route = route,
            status = ScriptLineStatus.PENDING,
            cosmeticFixes = result.cosmeticFixes,
            softFailures = result.softFailures.map { it.code },
        )
    }

    /** 便捷串联：raw → normalize → validate → build（生产链路的单入口）。 */
    fun fromRaw(
        raw: String,
        paragraphText: String,
        segment: SemanticSegment,
        ctx: DirectorContext,
    ): Pair<ValidationResult, ScriptLine?> {
        val normalized = ProtocolNormalizer.normalize(raw)
        val result = DirectorOutputValidator.validate(
            normalized = normalized,
            expectedSegmentToken = DirectorSelectionPrompt.segmentToken(ctx.target),
            candidateLocalIds = ctx.candidateSpeakers.map { it.localId }.toSet(),
            providedEvidenceIds = ctx.evidenceItems.map { it.id }.toSet(),
        )
        return result to build(paragraphText, segment, ctx, result)
    }
}
