package com.readervoice.data.semantic

import com.readervoice.data.character.CharacterReadStore
import com.readervoice.parser.paragraph.LogicalParagraph
import org.json.JSONArray
import org.json.JSONObject

/**
 * MOBILE-005 / M3 步骤 5：**ChapterDirectorRunner**。
 *
 * 逐 `SemanticSegment` 走完整链路：
 *   LogicalParagraph → SemanticSegment[] → DirectorContext → (shortcut | 模型)
 *     → ProtocolNormalizer → DirectorOutputValidator → ScriptLineBuilder → 逐行回调落盘
 *
 * 设计约束（用户冻结）：
 *   · **协作式取消**（epoch）：每段之间检查令牌，取消即停；**已产出的行保留**（不杀线程、不回滚）
 *   · **增量落盘**：每产出一行立即回调（`onLine`），整章不驻留内存
 *   · **旁白不问模型**：shortcut 直接给 NARRATOR/NARRATION（省调用、免"提到谁就选谁"）
 *   · **逐段 fail-closed**：一段 REJECT 不影响其它段（G3b 覆盖率的分子/分母分开统计）
 *   · 顺序单调：行按 (paragraph, segmentIndex) 自然顺序产出，不重排
 */
class ChapterDirectorRunner(
    private val store: CharacterReadStore,
    private val bookPk: Long,
    private val windowSize: Int = 8,
) {

    /** 模型调用（返回原始 JSON 文本；null = 无输出）。 */
    fun interface ModelDecider {
        fun decide(prompt: String): String?
    }

    /** 协作式取消令牌（epoch）。函数式接口：`CancelToken { myEpoch != current }`。 */
    fun interface CancelToken {
        fun isCanceled(): Boolean
    }

    enum class DecidedBy { SHORTCUT, MODEL, NO_OUTPUT }

    data class SegmentOutcome(
        val paragraphId: Long,
        val segmentIndex: Int,
        val segmentId: Long,
        val decidedBy: DecidedBy,
        val outcome: ValidationOutcome?,
        val line: ScriptLine?,
        val failureCodes: List<FailureCode>,
        val cosmeticFixes: List<CosmeticFix>,
    )

    data class Stats(
        val paragraphs: Int,
        val segments: Int,
        val decidedByShortcut: Int,
        val modelCalls: Int,
        val noOutput: Int,
        val acceptedAll: Int,
        val downgrade: Int,
        val verify: Int,
        val reject: Int,
        val cosmeticFixes: Int,
        val failureCodes: Map<String, Int>,
        val linesEmitted: Int,
        val canceled: Boolean,
    ) {
        /** G3b：可朗读片段的 ScriptLine 覆盖率（fail-closed 单列，不与"锚定安全"混谈）。 */
        val coverage: Double get() = if (segments == 0) 0.0 else linesEmitted.toDouble() / segments
        val failClosedRate: Double get() = if (segments == 0) 0.0 else reject.toDouble() / segments
        val shortcutRate: Double get() = if (segments == 0) 0.0 else decidedByShortcut.toDouble() / segments
        /** 每次模型调用平均触发的表示层修复数（不是比率，可能 >1）。 */
        val cosmeticFixesPerModelCall: Double get() =
            if (modelCalls == 0) 0.0 else cosmeticFixes.toDouble() / modelCalls

        fun pretty(): String = buildString {
            appendLine("paragraphs=$paragraphs segments=$segments")
            appendLine("decidedBy: shortcut=$decidedByShortcut model=$modelCalls noOutput=$noOutput")
            appendLine("outcome: ACCEPTED_ALL=$acceptedAll DOWNGRADE=$downgrade VERIFY=$verify REJECT=$reject")
            appendLine("linesEmitted=$linesEmitted coverage=%.1f%% failClosed=%.1f%% shortcut=%.1f%% cosmeticFixes/call=%.2f"
                .format(coverage * 100, failClosedRate * 100, shortcutRate * 100, cosmeticFixesPerModelCall))
            appendLine("failureCodes=$failureCodes")
            appendLine("canceled=$canceled")
        }
    }

    /**
     * @param onLine 每产出一行立即调用（增量落盘；章节中途取消时已产出的行保留）
     */
    fun run(
        paragraphs: List<LogicalParagraph>,
        decider: ModelDecider,
        cancel: CancelToken? = null,
        onLine: (ScriptLine) -> Unit = {},
        onOutcome: (SegmentOutcome) -> Unit = {},
        /**
         * CH-0 / P0-B 断点续跑：跳过前 [resumeFrom] 个片段（已由 checkpoint 提交）。
         * 被跳过的段**不计入任何统计、不产出行** —— 它们已经落盘在剧本里。
         */
        resumeFrom: Int = 0,
    ): Stats {
        val segmenter = SemanticSegmenter()
        // ★ baseline 必须拿到实体集（delivery-cue 判定与前后缀消歧都依赖它）
        val baseline = RuleSpeakerBaseline(store.canonicalNamesOf(bookPk))
        val ctxBuilder = ContextBuilder(store)

        var segments = 0; var shortcut = 0; var calls = 0; var noOutput = 0
        var acc = 0; var down = 0; var ver = 0; var rej = 0; var fixes = 0; var emitted = 0
        var canceled = false
        val failureCodes = LinkedHashMap<String, Int>()

        val window = ArrayDeque<ContextBuilder.WindowParagraph>()
        val recent = ArrayDeque<String>()
        var prevCue: String? = null

        outer@ for (p in paragraphs) {
            val segs = segmenter.segment(p)
            if (segs.isEmpty()) continue
            val res = baseline.assign(p, segs, recent.toList(), prevCue)

            for (seg in segs) {
                if (cancel?.isCanceled() == true) { canceled = true; break@outer }
                if (seg.type == SegmentType.UNKNOWN) continue
                segments++
                // ★ 断点续跑：已提交的片段直接跳过（不算、不产出、不调模型）
                if (segments - 1 < resumeFrom) continue

                val ctx = ctxBuilder.build(
                    bookPk, p, segs, res, window.toList(), p.paragraphId, windowSize, emptyList(),
                )
                val expectedToken = DirectorSelectionPrompt.segmentToken(ctx.target)

                val skip = DirectorSelectionPrompt.skipDecision(ctx)
                val raw: String?
                val decidedBy: DecidedBy
                if (skip != null) {
                    shortcut++
                    raw = synthesizedDecision(expectedToken, skip, ctx)
                    decidedBy = DecidedBy.SHORTCUT
                } else {
                    calls++
                    raw = decider.decide(DirectorSelectionPrompt.render(ctx))
                    decidedBy = if (raw == null) DecidedBy.NO_OUTPUT else DecidedBy.MODEL
                    if (raw == null) noOutput++
                }

                if (raw == null) {
                    rej++
                    failureCodes.merge(FailureCode.JSON_PARSE_FAILED.name, 1, Int::plus)
                    onOutcome(SegmentOutcome(p.paragraphId, seg.segmentIndex, seg.segmentId,
                        decidedBy, null, null, listOf(FailureCode.JSON_PARSE_FAILED), emptyList()))
                    continue
                }

                val normalized = ProtocolNormalizer.normalize(raw)
                val result = DirectorOutputValidator.validate(
                    normalized, expectedToken,
                    ctx.candidateSpeakers.map { it.localId }.toSet(),
                    ctx.evidenceItems.map { it.id }.toSet(),
                )
                fixes += normalized.fixes.size
                (result.hardFailures + result.softFailures).forEach { failureCodes.merge(it.code.name, 1, Int::plus) }

                // ★ C18 硬门：先建行再计结局。若局部候选无法解析为持久身份，build 返回 null
                //   ⇒ 与"悬空 C#"同等对待（REJECT + SPEAKER_ID_MISSING），绝不静默丢行。
                val line = ScriptLineBuilder.build(p.normalizedText, seg, ctx, result)
                val effectiveOutcome =
                    if (line == null && result.outcome != ValidationOutcome.REJECT) {
                        failureCodes.merge(FailureCode.SPEAKER_ID_MISSING.name, 1, Int::plus)
                        ValidationOutcome.REJECT
                    } else {
                        result.outcome
                    }
                when (effectiveOutcome) {
                    ValidationOutcome.ACCEPTED_ALL -> acc++
                    ValidationOutcome.DOWNGRADE -> down++
                    ValidationOutcome.VERIFY -> ver++
                    ValidationOutcome.REJECT -> rej++
                }
                if (line != null) { emitted++; onLine(line) }
                onOutcome(SegmentOutcome(
                    p.paragraphId, seg.segmentIndex, seg.segmentId, decidedBy, effectiveOutcome, line,
                    (result.hardFailures + result.softFailures).map { it.code }, result.cosmeticFixes,
                ))
            }

            for (s in segs) {
                val r = res[s.segmentIndex] ?: continue
                val sp = r.speaker
                if (sp != null && r.status != "UNKNOWN") { recent.remove(sp); recent.addFirst(sp) }
            }
            while (recent.size > windowSize * 2) recent.removeLast()
            prevCue = baseline.extractTrailingCue(p.normalizedText)
            window.addLast(ContextBuilder.WindowParagraph(p, res))
            while (window.size > windowSize) window.removeFirst()
        }

        return Stats(
            paragraphs = paragraphs.size, segments = segments,
            decidedByShortcut = shortcut, modelCalls = calls, noOutput = noOutput,
            acceptedAll = acc, downgrade = down, verify = ver, reject = rej,
            cosmeticFixes = fixes, failureCodes = failureCodes,
            linesEmitted = emitted, canceled = canceled,
        )
    }

    /** shortcut 的合成决策（仍走完整校验链，不绕过 Validator）。 */
    private fun synthesizedDecision(token: String, speaker: String, ctx: DirectorContext): String {
        val type = if (speaker == DirectorDecisionV2.NARRATOR) "NARRATION" else "DIALOGUE"
        return JSONObject()
            .put("segment_id", token)
            .put("speaker", speaker)
            .put("type", type)
            .put("emotion", DirectorDecisionV2.SAFE_EMOTION.name)
            .put("emotion_intensity", DirectorDecisionV2.SAFE_INTENSITY.toDouble())
            .put("delivery", JSONObject()
                .put("pace", Delivery.SAFE.pace.name)
                .put("volume", Delivery.SAFE.volume.name)
                .put("tone", Delivery.SAFE.tone.name))
            .put("voice_event", DirectorDecisionV2.SAFE_VOICE_EVENT.name)
            .put("evidence", JSONArray())
            .toString()
    }
}

/**
 * ScriptLine ⇄ JSONL（增量落盘 / 回放）。
 *
 * **schemaVersion 2（C18）**：持久化 `speakerRef` = `NARRATOR` | `UNKNOWN` | `CHARACTER:<stable-id>`。
 * v2 **禁止**出现局部 `C#`（`C0/C1/...`）—— 它是 request-scoped 地址，离开本轮 CandidateMap 后不可解释。
 *
 * **schemaVersion 1（legacy，只读）**：旧 run 的 `speaker = "C0"` 一律**不迁移、不猜测**
 * （离开当时的 CandidateMap 已无法安全解释），只作为历史/调试产物保留。
 */
object ScriptLineCodec {

    const val SCHEMA_VERSION = 2

    fun encode(line: ScriptLine): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("segment_id", line.segmentId)
        .put("paragraph_id", line.paragraphId)
        .put("segment_index", line.segmentIndex)
        .put("text", line.text)
        .put("source_start", line.sourceStart)
        .put("source_end", line.sourceEnd)
        .put("speakerRef", SpeakerRef.encodeToken(line.speaker))
        .put("type", line.type.name)
        .put("emotion", line.emotion.name)
        .put("emotion_intensity", line.emotionIntensity.toDouble())
        .put("delivery", JSONObject()
            .put("pace", line.delivery.pace.name)
            .put("volume", line.delivery.volume.name)
            .put("tone", line.delivery.tone.name))
        .put("voice_event", line.voiceEvent.name)
        .put("evidence", JSONArray(line.evidence))
        .put("outcome", line.outcome.name)
        .put("route", line.route.name)
        .put("status", line.status.name)
        .put("cosmetic_fixes", JSONArray(line.cosmeticFixes.map { it.name }))
        .put("soft_failures", JSONArray(line.softFailures.map { it.name }))
        .toString()

    /** 只读解析 v2；v1/非法行返回 null（调用方用 [LegacyScriptLine.parse] 处理历史产物）。 */
    fun decodeSpeakerRef(json: String): SpeakerRef? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optInt("schemaVersion", 1) < SCHEMA_VERSION) return null
        return SpeakerRef.decodeToken(o.optString("speakerRef", ""))
    }
}

/**
 * schemaVersion 1 的历史产物：**只读**，明确标记"说话人不可解析"。
 * 不提供到 [SpeakerRef] 的任何转换 —— 猜历史 `C0` 是谁会制造假身份（C18 / ADR-055）。
 */
data class LegacyScriptLine(
    val segmentId: Long,
    val text: String,
    /** 原始字符串（可能是 `C0` / `NARRATOR` / `UNKNOWN`） */
    val rawSpeaker: String,
    val schemaVersion: Int,
) {
    /** 旧产物里的说话人**不可安全解析**：一律当作"历史、不可用于生产恢复"。 */
    val speakerResolvable: Boolean get() = false

    companion object {
        fun parse(json: String): LegacyScriptLine? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val v = o.optInt("schemaVersion", 1)
            if (v >= ScriptLineCodec.SCHEMA_VERSION) return null
            return LegacyScriptLine(
                segmentId = o.optLong("segment_id"),
                text = o.optString("text", ""),
                rawSpeaker = o.optString("speaker", ""),
                schemaVersion = v,
            )
        }
    }
}


