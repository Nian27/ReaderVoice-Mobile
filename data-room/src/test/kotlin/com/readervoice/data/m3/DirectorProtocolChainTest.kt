package com.readervoice.data.m3

import com.readervoice.data.character.CharacterAttribute
import com.readervoice.data.character.CharacterReadStore
import com.readervoice.data.character.EffectiveVoiceState
import com.readervoice.data.character.IdentityResolution
import com.readervoice.data.character.NarrativeEntity
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.CosmeticFix
import com.readervoice.data.semantic.Delivery
import com.readervoice.data.semantic.DirectorDecisionV2
import com.readervoice.data.semantic.DirectorOutputValidator
import com.readervoice.data.semantic.DirectorType
import com.readervoice.data.semantic.Emotion
import com.readervoice.data.semantic.FailureCode
import com.readervoice.data.semantic.Pace
import com.readervoice.data.semantic.ProtocolNormalizer
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.ScriptLineBuilder
import com.readervoice.data.semantic.ScriptLineStatus
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegment
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.semantic.Tone
import com.readervoice.data.semantic.TtsRoute
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.data.semantic.VoiceEvent
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ReadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3 步骤 3+4：**协议闭环**（DirectorDecisionV2 → Normalizer → Validator → ScriptLine）。
 *
 * 本文件是"正常izer 只能修形式、不能修答案"这条铁律的可执行规格：
 * 每个【允许】的修复与每个【绝不允许】的推断都有一条测试。
 */
class DirectorProtocolChainTest {

    // ── fixture：一个含候选与证据的上下文 ────────────────────────────────
    private fun para(text: String, id: Long = 21L) = LogicalParagraph(
        paragraphId = id, revisionId = id, bookId = "b", chapterId = null,
        sourceRevisionId = "s", paragraphIndex = 0, sourceSpans = emptyList(),
        normalizedText = text, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
        boundaryConfidence = 0.9,
    )

    private object Store : CharacterReadStore {
        private val byName = mapOf(
            "林夜" to NarrativeEntity(1, "e-linye", 1, "PERSON", "林夜", "CONFIRMED"),
            "傅远" to NarrativeEntity(2, "e-fuyuan", 1, "PERSON", "傅远", "CONFIRMED"),
        )

        override fun entityByPk(entityPk: Long) = byName.values.firstOrNull { it.entityPk == entityPk }
        override fun entityByCanonicalName(bookPk: Long, name: String) = byName[name]
        override fun canonicalNamesOf(bookPk: Long): Set<String> = byName.keys
        override fun aliasesOf(bookPk: Long, clusterId: String) = emptyList<String>()
        override fun resolveIdentity(bookPk: Long, entityPk: Long) =
            IdentityResolution(entityPk, null, "CONFIRMED")

        override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long) =
            EffectiveVoiceState(entityPk, null, null, null, emptyMap<String, CharacterAttribute>())

        override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? = null
        override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? = null
        override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long) = false
        override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long) = emptyMap<String, Int>()
    }

    private data class Ctx(
        val paragraph: LogicalParagraph,
        val segment: SemanticSegment,
        val context: com.readervoice.data.semantic.DirectorContext,
    )

    private fun ctx(text: String = "林夜看着他，说道：“你终于来了。”"): Ctx {
        val p = para(text)
        val segs = SemanticSegmenter().segment(p)
        val res = RuleSpeakerBaseline(Store.canonicalNamesOf(1L)).assign(p, segs, emptyList())
        val c = ContextBuilder(Store).build(1L, p, segs, res, emptyList(), p.paragraphId)
        val target = segs.first { it.segmentId == c.target.segmentId }
        return Ctx(p, target, c)
    }

    private fun validate(raw: String, f: Ctx = ctx()) = DirectorOutputValidator.validate(
        ProtocolNormalizer.normalize(raw),
        expectedSegmentToken = "S${f.segment.sourceIndex()}",
        candidateLocalIds = f.context.candidateSpeakers.map { it.localId }.toSet(),
        providedEvidenceIds = f.context.evidenceItems.map { it.id }.toSet(),
    )

    private fun SemanticSegment.sourceIndex(): Int = segmentIndex

    /** 大多数测试用：speaker=C0 的合法 JSON。 */
    private fun okJson(speaker: String = "C0", type: String = "DIALOGUE", extra: String = "") =
        """{"segment_id":"S${ctx().segment.segmentIndex}","speaker":"$speaker","type":"$type",
            "emotion":"NEUTRAL","emotion_intensity":0.5,
            "delivery":{"pace":"NORMAL","volume":"NORMAL","tone":"NEUTRAL"},"voice_event":"NORMAL",
            "evidence":[]$extra}""".trimIndent()

    // ══ Normalizer：允许的表示层修复 ═════════════════════════════════════
    @Test
    fun `normalizer fixes representation only`() {
        val f = ctx()
        val raw = """{"segment_id":"S${f.segment.segmentIndex}","speaker": C0, "type": dialogue,
            "emotion": neutral, "emotion_intensity": "0.6",
            "delivery.pace": normal, "delivery.volume": normal, "delivery.tone": neutral,
            "voice_event": normal, "evidence": ["E0: RULE: EXPLICIT_SPEECH_CUE"]}""".trimIndent()
        val n = ProtocolNormalizer.normalize(raw)
        val d = assertNotNull(n.decision, "应能容错解析: ${n.parseFailure?.detail}")
        assertEquals("C0", d.speaker)
        assertEquals(DirectorType.DIALOGUE, d.type)
        assertEquals(Emotion.NEUTRAL, d.emotion)
        assertEquals(0.6f, d.emotionIntensity)
        assertEquals(Pace.NORMAL, d.delivery.pace)
        assertEquals(listOf("E0"), d.evidence, "带尾随文字的证据应只取前导 E#")
        assertTrue(CosmeticFix.BARE_TOKEN_QUOTED in n.fixes)
        assertTrue(CosmeticFix.ENUM_CASE in n.fixes)
        assertTrue(CosmeticFix.FLAT_DELIVERY_KEY in n.fixes)
        assertTrue(CosmeticFix.EVIDENCE_TRAILING_TEXT in n.fixes)
    }

    @Test
    fun `normalizer strips code fence and surrounding prose`() {
        val f = ctx()
        val raw = "好的，结果如下：\n```json\n" + okJson("NARRATOR", "NARRATION") + "\n```\n希望有帮助。"
        val n = ProtocolNormalizer.normalize(raw)
        assertNotNull(n.decision)
        assertTrue(CosmeticFix.CODE_FENCE_STRIPPED in n.fixes)
        assertTrue(CosmeticFix.JSON_OBJECT_EXTRACTED in n.fixes)
    }

    // ══ Normalizer：绝不允许的语义修复 ═══════════════════════════════════
    @Test
    fun `normalizer never repairs semantic answers`() {
        val f = ctx()
        // ① C7 不存在 ⇒ 保持 C7（由 Validator 判 SPEAKER_ID_MISSING → REJECT），不得改成 C0/UNKNOWN
        val dang = ProtocolNormalizer.normalize(okJson("C7")).decision!!
        assertEquals("C7", dang.speaker, "Normalizer 不得把悬空引用改成合法候选")
        // ② 非协议取值 ⇒ 落到 UNRECOGNIZED，不得猜一个
        val bad = ProtocolNormalizer.normalize(
            """{"segment_id":"S${f.segment.segmentIndex}","speaker":"邀请","type":"DIALOGUE",
                "emotion":"超级无敌伤心","emotion_intensity":0.5,
                "delivery":{"pace":"飞快飞快","volume":"NORMAL","tone":"NEUTRAL"},"voice_event":"NORMAL"}"""
        ).decision!!
        assertEquals("邀请", bad.speaker, "Normalizer 不得把非人名猜成角色")
        assertEquals(Emotion.UNRECOGNIZED, bad.emotion, "Normalizer 不得猜情绪")
        assertEquals(Pace.UNRECOGNIZED, bad.delivery.pace)
        // ③ speaker 缺失 ⇒ 保持空（由 Validator 判 SPEAKER_MISSING → REJECT），不得填 NARRATOR
        val miss = ProtocolNormalizer.normalize(
            """{"segment_id":"S${f.segment.segmentIndex}","type":"DIALOGUE"}"""
        ).decision!!
        assertEquals("", miss.speaker, "Normalizer 不得为缺失的 speaker 兜底")
    }

    // ══ Validator：硬失败 ⇒ REJECT ═══════════════════════════════════════
    @Test
    fun `dangling speaker reference is a hard failure and never silently changed`() {
        val r = validate(okJson("C7"))
        assertEquals(ValidationOutcome.REJECT, r.outcome)
        assertTrue(r.hardFailures.any { it.code == FailureCode.SPEAKER_ID_MISSING })
        assertNull(r.decision, "REJECT 不得产出决策")
    }

    @Test
    fun `speaker type conflict is a hard failure`() {
        // NARRATOR 配 DIALOGUE
        val r1 = validate(okJson("NARRATOR", "DIALOGUE"))
        assertEquals(ValidationOutcome.REJECT, r1.outcome)
        assertTrue(r1.hardFailures.any { it.code == FailureCode.SPEAKER_TYPE_CONFLICT })
        // C0 配 NARRATION
        val r2 = validate(okJson("C0", "NARRATION"))
        assertEquals(ValidationOutcome.REJECT, r2.outcome)
        assertTrue(r2.hardFailures.any { it.code == FailureCode.SPEAKER_TYPE_CONFLICT })
    }

    @Test
    fun `segment id mismatch and missing speaker are hard failures`() {
        val f = ctx()
        val wrongSeg = validate(okJson().replace("S${f.segment.segmentIndex}", "S99"))
        assertEquals(ValidationOutcome.REJECT, wrongSeg.outcome)
        assertTrue(wrongSeg.hardFailures.any { it.code == FailureCode.SEGMENT_ID_MISMATCH })

        val noSpeaker = validate(
            """{"segment_id":"S${f.segment.segmentIndex}","type":"DIALOGUE"}"""
        )
        assertEquals(ValidationOutcome.REJECT, noSpeaker.outcome)
        assertTrue(noSpeaker.hardFailures.any { it.code == FailureCode.SPEAKER_MISSING })

        val broken = validate("{ not json at all")
        assertEquals(ValidationOutcome.REJECT, broken.outcome)
        assertTrue(broken.hardFailures.any { it.code == FailureCode.JSON_PARSE_FAILED })
    }

    // ══ Validator：软失败 ⇒ DOWNGRADE（核心仍可用）═══════════════════════
    @Test
    fun `soft failures downgrade performance fields but keep the line`() {
        val f = ctx()
        val raw = """{"segment_id":"S${f.segment.segmentIndex}","speaker":"C0","type":"DIALOGUE",
            "emotion":"超级无敌伤心","emotion_intensity":9.9,
            "delivery":{"pace":"飞快飞快","volume":"震天响","tone":"莫名其妙"},"voice_event":"狂笑不止",
            "evidence":["E0","E99","不是编号"]}"""
        val r = validate(raw, f)
        assertEquals(ValidationOutcome.DOWNGRADE, r.outcome)
        val d = assertNotNull(r.decision)
        assertEquals("C0", d.speaker, "核心 speaker 必须保持不动")
        assertEquals(DirectorType.DIALOGUE, d.type)
        assertEquals(Emotion.NEUTRAL, d.emotion)
        assertEquals(DirectorDecisionV2.SAFE_INTENSITY, d.emotionIntensity)
        assertEquals(Delivery.SAFE, d.delivery)
        assertEquals(VoiceEvent.NORMAL, d.voiceEvent)
        assertEquals(listOf("E0"), d.evidence, "非法/悬空 evidence 在降级时剔除")
        assertTrue(r.softFailures.any { it.code == FailureCode.EMOTION_INVALID })
        assertTrue(r.softFailures.any { it.code == FailureCode.EMOTION_INTENSITY_RANGE })
        assertTrue(r.softFailures.any { it.code == FailureCode.EVIDENCE_ID_INVALID })
        assertTrue(r.softFailures.any { it.code == FailureCode.EVIDENCE_ID_MISSING })
    }

    // ══ Validator：UNKNOWN 是合法决策 ⇒ VERIFY ═══════════════════════════
    @Test
    fun `legal unknown yields verify not reject`() {
        val r = validate(okJson("UNKNOWN", "DIALOGUE"))
        assertEquals(ValidationOutcome.VERIFY, r.outcome, "UNKNOWN+DIALOGUE 合法，不得判 REJECT/DOWNGRADE")
        assertTrue(r.hardFailures.isEmpty())
        assertTrue(r.softFailures.isEmpty())
        assertEquals("UNKNOWN", r.decision?.speaker)
    }

    @Test
    fun `narrator accepted all`() {
        val r = validate(okJson("NARRATOR", "NARRATION"))
        assertEquals(ValidationOutcome.ACCEPTED_ALL, r.outcome)
    }

    @Test
    fun `unknown narrator combination has no place in the contract`() {
        // UNKNOWN + NARRATION：契约里不存在（旁白直接 NARRATOR）⇒ 按冲突判硬失败
        val r = validate(okJson("UNKNOWN", "NARRATION"))
        assertEquals(ValidationOutcome.REJECT, r.outcome)
        assertTrue(r.hardFailures.any { it.code == FailureCode.SPEAKER_TYPE_CONFLICT })
    }

    // ══ ScriptLineBuilder：文本永远来自 canonical span ════════════════════
    @Test
    fun `script line text always comes from the canonical span`() {
        val f = ctx()
        val r = validate(okJson("C0"), f)
        val line = assertNotNull(ScriptLineBuilder.build(f.paragraph.normalizedText, f.segment, f.context, r))
        assertEquals(f.segment.text, line.text, "文本必须等于 canonical 切片")
        assertEquals(
            f.paragraph.normalizedText.substring(f.segment.sourceStart, f.segment.sourceEnd),
            line.text,
        )
        assertFalse(line.text.contains("segment_id"), "不得包含模型输出的任何文本")
        assertEquals(ScriptLineStatus.PENDING, line.status, "ACCEPTED_ALL ≠ COMMITTED")
        assertEquals(TtsRoute.COSYVOICE_CHARACTER, line.route)
        assertNotNull(line.speakerIdentityId, "C# 应能映射回内部身份供审计")
    }

    @Test
    fun `reject produces no script line`() {
        val f = ctx()
        val r = validate(okJson("C7"), f)
        assertNull(ScriptLineBuilder.build(f.paragraph.normalizedText, f.segment, f.context, r))
    }

    @Test
    fun `routes follow the frozen speaker type relation`() {
        val f = ctx()
        val nar = validate(okJson("NARRATOR", "NARRATION"), f)
        assertEquals(TtsRoute.AUDIO8_NARRATOR, ScriptLineBuilder.build(f.paragraph.normalizedText, f.segment, f.context, nar)!!.route)
        val unk = validate(okJson("UNKNOWN", "DIALOGUE"), f)
        val unkLine = ScriptLineBuilder.build(f.paragraph.normalizedText, f.segment, f.context, unk)!!
        assertEquals(TtsRoute.UNKNOWN_DIALOGUE_FALLBACK, unkLine.route)
        assertEquals(ValidationOutcome.VERIFY, unkLine.outcome)
        assertNull(unkLine.speakerIdentityId, "UNKNOWN 不得绑定具体角色音色")
    }

    @Test
    fun `canonical span mismatch fails closed`() {
        val f = ctx()
        val r = validate(okJson("C0"), f)
        val tampered = f.segment.copy(text = "被篡改的文本")
        assertFailsWith<IllegalStateException> {
            ScriptLineBuilder.build(f.paragraph.normalizedText, tampered, f.context, r)
        }
    }

    @Test
    fun `downgrade still yields a script line with safe defaults`() {
        val f = ctx()
        val r = validate(
            """{"segment_id":"S${f.segment.segmentIndex}","speaker":"C0","type":"DIALOGUE",
                "emotion":"???","emotion_intensity":5,"delivery":{"pace":"?","volume":"?","tone":"?"},
                "voice_event":"?"}""",
            f,
        )
        val line = assertNotNull(ScriptLineBuilder.build(f.paragraph.normalizedText, f.segment, f.context, r))
        assertEquals(ValidationOutcome.DOWNGRADE, line.outcome)
        assertEquals(Tone.NEUTRAL, line.delivery.tone)
        assertTrue(line.softFailures.isNotEmpty())
    }
}
