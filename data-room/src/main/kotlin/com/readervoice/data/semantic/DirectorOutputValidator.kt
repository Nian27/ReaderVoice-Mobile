package com.readervoice.data.semantic

/**
 * MOBILE-005 / M3 步骤 4b：**DirectorOutputValidator**。
 *
 * 只做两件事：判**硬失败**（协议结构/引用不可信 ⇒ REJECT）与**软失败**（表演字段不可信 ⇒ DOWNGRADE）。
 * **绝不做语义修正**：`C7` 不会变成 `C0`，也不会变成 `UNKNOWN`。
 *
 * 硬失败：segmentId 错/缺 · speaker 缺 · speaker 悬空 · speaker/type 冲突 · JSON 不可恢复
 * 软失败：emotion / intensity / delivery / voice_event 非法 · 部分 evidence 非法
 *
 * 四态：ACCEPTED_ALL / DOWNGRADE / VERIFY / REJECT。
 * ★ UNKNOWN 是**合法**决策：`speaker=UNKNOWN + type=DIALOGUE` ⇒ VERIFY（不是 REJECT/DOWNGRADE）。
 */
object DirectorOutputValidator {

    fun validate(
        normalized: NormalizedOutput,
        expectedSegmentToken: String,
        candidateLocalIds: Set<String>,
        providedEvidenceIds: Set<String>,
    ): ValidationResult {
        val hard = mutableListOf<Failure>()
        val soft = mutableListOf<Failure>()

        normalized.parseFailure?.let { hard += it }
        val d = normalized.decision
        if (d == null) {
            if (hard.isEmpty()) {
                hard += Failure(FailureCode.JSON_PARSE_FAILED, Severity.HARD, "无可用决策")
            }
            return ValidationResult(ValidationOutcome.REJECT, null, hard, soft, normalized.fixes)
        }

        // ── 硬失败 ─────────────────────────────────────────────────────
        if (d.segmentId.isBlank()) {
            hard += Failure(FailureCode.SEGMENT_ID_MISSING, Severity.HARD, "segment_id 缺失")
        } else if (d.segmentId != expectedSegmentToken) {
            hard += Failure(
                FailureCode.SEGMENT_ID_MISMATCH, Severity.HARD,
                "segment_id=${d.segmentId} != 期望 $expectedSegmentToken",
            )
        }
        if (d.speaker.isBlank()) {
            hard += Failure(FailureCode.SPEAKER_MISSING, Severity.HARD, "speaker 缺失")
        } else if (DirectorDecisionV2.isCandidateRef(d.speaker)) {
            if (d.speaker !in candidateLocalIds) {
                // ★ 悬空引用：**不**偷偷改 UNKNOWN（否则局部 ID 安全边界形同虚设）
                hard += Failure(
                    FailureCode.SPEAKER_ID_MISSING, Severity.HARD,
                    "speaker=${d.speaker} 不在本轮候选 ${candidateLocalIds.sorted()}",
                )
            }
        } else if (d.speaker != DirectorDecisionV2.NARRATOR && d.speaker != DirectorDecisionV2.UNKNOWN) {
            hard += Failure(FailureCode.SPEAKER_ID_INVALID, Severity.HARD, "speaker=${d.speaker} 非法形态")
        }
        if (d.speaker.isNotBlank() && !DirectorDecisionV2.relationValid(d.speaker, d.type)) {
            hard += Failure(
                FailureCode.SPEAKER_TYPE_CONFLICT, Severity.HARD,
                "speaker=${d.speaker} 与 type=${d.type} 冲突（NARRATOR→NARRATION；C#/UNKNOWN→DIALOGUE）",
            )
        }

        // ── 软失败（表演字段）───────────────────────────────────────────
        if (d.emotion == Emotion.UNRECOGNIZED) {
            soft += Failure(FailureCode.EMOTION_INVALID, Severity.SOFT, "emotion 非协议枚举")
        }
        if (d.emotionIntensity.isNaN() || d.emotionIntensity !in 0f..1f) {
            soft += Failure(
                FailureCode.EMOTION_INTENSITY_RANGE, Severity.SOFT,
                "emotion_intensity=${d.emotionIntensity} 不在 [0,1]",
            )
        }
        if (d.delivery.pace == Pace.UNRECOGNIZED) soft += Failure(FailureCode.DELIVERY_PACE_INVALID, Severity.SOFT, "pace 非法")
        if (d.delivery.volume == Volume.UNRECOGNIZED) soft += Failure(FailureCode.DELIVERY_VOLUME_INVALID, Severity.SOFT, "volume 非法")
        if (d.delivery.tone == Tone.UNRECOGNIZED) soft += Failure(FailureCode.DELIVERY_TONE_INVALID, Severity.SOFT, "tone 非法")
        if (d.voiceEvent == VoiceEvent.UNRECOGNIZED) soft += Failure(FailureCode.VOICE_EVENT_INVALID, Severity.SOFT, "voice_event 非法")

        val badForm = d.evidence.filter { !Regex("^E\\d+$").matches(it) }
        if (badForm.isNotEmpty()) {
            soft += Failure(FailureCode.EVIDENCE_ID_INVALID, Severity.SOFT, "非法 evidence: ${badForm.take(3)}")
        }
        val dangling = d.evidence.filter { Regex("^E\\d+$").matches(it) && it !in providedEvidenceIds }
        if (dangling.isNotEmpty()) {
            soft += Failure(FailureCode.EVIDENCE_ID_MISSING, Severity.SOFT, "不存在的 evidence: ${dangling.take(3)}")
        }

        // ── 结局 ───────────────────────────────────────────────────────
        return when {
            hard.isNotEmpty() -> ValidationResult(ValidationOutcome.REJECT, null, hard, soft, normalized.fixes)
            soft.isNotEmpty() -> ValidationResult(
                ValidationOutcome.DOWNGRADE, sanitize(d, providedEvidenceIds), hard, soft, normalized.fixes,
            )
            d.speaker == DirectorDecisionV2.UNKNOWN -> ValidationResult(
                // UNKNOWN 合法：文本可安全保留，但**不得**当"已确认角色"
                ValidationOutcome.VERIFY, d, hard, soft, normalized.fixes,
            )
            else -> ValidationResult(ValidationOutcome.ACCEPTED_ALL, d, hard, soft, normalized.fixes)
        }
    }

    /**
     * DOWNGRADE：核心（speaker/type）保持不动，表演字段退化为安全默认值；
     * 仅保留形态合法且确实存在的 evidence 引用。
     */
    internal fun sanitize(d: DirectorDecisionV2, providedEvidenceIds: Set<String>): DirectorDecisionV2 = d.copy(
        emotion = if (d.emotion == Emotion.UNRECOGNIZED) DirectorDecisionV2.SAFE_EMOTION else d.emotion,
        emotionIntensity = if (d.emotionIntensity.isNaN() || d.emotionIntensity !in 0f..1f) {
            DirectorDecisionV2.SAFE_INTENSITY
        } else d.emotionIntensity,
        delivery = Delivery(
            pace = if (d.delivery.pace == Pace.UNRECOGNIZED) Delivery.SAFE.pace else d.delivery.pace,
            volume = if (d.delivery.volume == Volume.UNRECOGNIZED) Delivery.SAFE.volume else d.delivery.volume,
            tone = if (d.delivery.tone == Tone.UNRECOGNIZED) Delivery.SAFE.tone else d.delivery.tone,
        ),
        voiceEvent = if (d.voiceEvent == VoiceEvent.UNRECOGNIZED) DirectorDecisionV2.SAFE_VOICE_EVENT else d.voiceEvent,
        // 只保留"形态合法且确实存在"的引用（悬空引用在降级时剔除）
        evidence = d.evidence.filter { Regex("^E\\d+$").matches(it) && it in providedEvidenceIds },
    )
}

