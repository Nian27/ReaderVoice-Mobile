package com.readervoice.data.m3

import com.readervoice.data.character.CharacterAttribute
import com.readervoice.data.character.CharacterReadStore
import com.readervoice.data.character.EffectiveVoiceState
import com.readervoice.data.character.IdentityResolution
import com.readervoice.data.character.NarrativeEntity
import com.readervoice.data.semantic.CueKind
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.DeliveryCueLexicon
import com.readervoice.data.semantic.GoldKind
import com.readervoice.data.semantic.GoldPolicy
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.semantic.SpeakerCandidateCompiler
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
 * MOBILE-005 / M3 步骤 1：**cue 污染与 gold 口径**的证据测试。
 *
 * 事故：`傅远坐下扫了一眼，饶有兴致道：` ⇒ 候选与 gold 同时出现"饶有兴致"，准确率 100% 而产品荒谬。
 * 修复要求：delivery cue 不进候选、不当 speaker、不吃真名，并转为 evidence（带 emotion_hint）。
 */
class DeliveryCueAndGoldPolicyTest {

    private fun para(text: String, id: Long = 1L) = LogicalParagraph(
        paragraphId = id, revisionId = id, bookId = "b", chapterId = null,
        sourceRevisionId = "s", paragraphIndex = 0, sourceSpans = emptyList(),
        normalizedText = text, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
        boundaryConfidence = 0.9,
    )

    private fun speak(text: String): RuleSpeakerBaseline.SpeakerResult {
        val p = para(text)
        val segs = SemanticSegmenter().segment(p)
        val results = RuleSpeakerBaseline().assign(p, segs, emptyList())
        val speech = segs.first { it.type == SegmentType.SPEECH }
        return results.getValue(speech.segmentIndex)
    }

    // ── 1. delivery cue 不再变成说话人 ──────────────────────────────────
    @Test
    fun `delivery cue phrases never become speakers`() {
        val polluted = listOf(
            "傅远坐下扫了一眼，饶有兴致道：“有点意思。”",
            "他抬起头，冷冷地说道：“滚。”",
            "李四皱了皱眉，不耐烦地问道：“还有多久？”",
            "王五缓缓开口道：“不急。”",
            "赵六沉声道：“退下。”",
        )
        for (text in polluted) {
            val r = speak(text)
            val sp = r.speaker
            assertFalse(
                sp != null && DeliveryCueLexicon.isDeliveryCue(sp),
                "delivery cue 仍被当成说话人: text=$text speaker=$sp"
            )
            assertTrue(r.deliveryCues.isNotEmpty(), "应识别出 delivery cue: text=$text")
            assertEquals(CueKind.DELIVERY_CUE, r.deliveryCues.first().kind)
            assertNotNull(r.deliveryCues.first().emotionHint, "delivery cue 应带 emotion_hint: $text")
        }
    }

    // ── 2. 真名不被误吃（反向保护）────────────────────────────────────
    @Test
    fun `real character names still resolve`() {
        val cases = mapOf(
            "傅远说道：“走。”" to "傅远",
            "陈月喊道：“小心！”" to "陈月",
            "王老叹了口气，说：“明儿还要早起呢。”" to "王老",
            "李雪轻声说：“雪儿，你怎么还不睡？”" to "李雪",
        )
        for ((text, expected) in cases) {
            val r = speak(text)
            assertEquals(expected, r.speaker, "真名被误判: text=$text")
        }
    }

    // ── 3. 候选端同样过滤（且不影响真候选）──────────────────────────────
    @Test
    fun `candidate compiler drops delivery cues but keeps names`() {
        val compiler = SpeakerCandidateCompiler(entitySet = setOf("傅远", "陈月"))
        val cands = compiler.compile(
            cueSpeakers = listOf("饶有兴致", "冷冷地", "傅远"),
            recentSpeakers = listOf("陈月"),
            recentContext = listOf("陈月看了他一眼。"),
        ).map { it.surface }
        assertTrue("傅远" in cands, "真候选被误删: $cands")
        assertTrue("陈月" in cands, "窗口活跃候选被误删: $cands")
        assertFalse("饶有兴致" in cands, "delivery cue 进了候选: $cands")
        assertFalse("冷冷地" in cands, "delivery cue 进了候选: $cands")

        val cues = compiler.deliveryCuesOf(listOf("饶有兴致", "冷冷地", "傅远")).map { it.text }
        assertEquals(listOf("饶有兴致", "冷冷地"), cues)
    }

    // ── 4. 安全阀：实体集优先于形态规则 ─────────────────────────────────
    @Test
    fun `entity set overrides delivery cue morphology`() {
        assertNotNull(DeliveryCueLexicon.classify("缓缓", emptySet()), "无实体时应判为 cue")
        assertNull(DeliveryCueLexicon.classify("缓缓", setOf("缓缓")), "★ 实体集命中时不得判为 cue（不得吃真名）")
        assertNull(DeliveryCueLexicon.classify("饶有兴致", setOf("饶有兴致")))
        // 2 字人名（含易混字）不应被吃
        for (n in listOf("李喜", "张笑", "王冷", "赵淡")) {
            assertNull(DeliveryCueLexicon.classify(n, emptySet()), "2 字人名被误吃: $n")
        }
    }

    // ── 5. gold 口径分型 ────────────────────────────────────────────────
    @Test
    fun `gold policy separates manual weak rule and none`() {
        val explicit = RuleSpeakerBaseline.SpeakerResult("傅远", "CONFIRMED", 0.95, listOf("EXPLICIT_SPEECH_CUE"), true)
        val turn = RuleSpeakerBaseline.SpeakerResult("傅远", "PROVISIONAL", 0.5, listOf("TURN_TRACKING"), false)
        val unknown = RuleSpeakerBaseline.SpeakerResult(null, "UNKNOWN", 0.0, listOf("UNKNOWN"), false)

        val g1 = GoldPolicy.fromRuleResult(explicit)
        assertEquals(GoldKind.WEAK_GOLD, g1.kind)
        assertFalse(g1.hardGateUsable, "规则产生的标签不得进硬准确率 Gate")
        assertEquals(GoldKind.RULE_BASELINE, GoldPolicy.fromRuleResult(turn).kind)
        assertEquals(GoldKind.NONE, GoldPolicy.fromRuleResult(unknown).kind)

        val manual = GoldPolicy.fromDatasetProvenance("HUMAN_GOLD", "傅远")
        assertEquals(GoldKind.MANUAL_GOLD, manual.kind)
        assertTrue(manual.hardGateUsable)
        assertEquals(GoldKind.WEAK_GOLD, GoldPolicy.fromDatasetProvenance("EXPLICIT_RULE_GOLD", "傅远").kind)

        // 硬 Gate 守卫：弱标签直接报错，而不是"看起来 100%"
        assertFailsWith<IllegalStateException> { GoldPolicy.requireHardGate(g1, "speaker accuracy") }
        GoldPolicy.requireHardGate(manual, "speaker accuracy")
    }

    // ── 6. cue 转 evidence（带 emotion_hint），且 segmentId 落到 ContextSegment ──
    @Test
    fun `delivery cue becomes evidence and context carries segment id`() {
        val text = "傅远坐下扫了一眼，饶有兴致道：“有点意思。”"
        val p = para(text, id = 77L)
        val segs = SemanticSegmenter().segment(p)
        val res = RuleSpeakerBaseline().assign(p, segs, emptyList())
        val ctx = ContextBuilder(NoopStore).build(1L, p, segs, res, emptyList(), p.paragraphId)

        val dc = ctx.evidenceItems.filter { it.kind == "DELIVERY_CUE" }
        assertTrue(dc.isNotEmpty(), "delivery cue 应进入 evidence: ${ctx.evidenceItems}")
        assertEquals("饶有兴致", dc.first().text)
        assertEquals("interested", dc.first().emotionHint)
        assertTrue(ctx.evidenceItems.all { it.id.startsWith("E") }, "证据必须是 E# 局部 ID")
        // 证据编号确定性
        assertEquals(ctx.evidenceItems.map { it.id }, ctx.evidenceItems.indices.map { "E$it" })

        // segmentId 必须落到 ContextSegment（M3 协议要用它回填）
        val target = segs.first { it.type == SegmentType.SPEECH }
        assertEquals(target.segmentId, ctx.target.segmentId)
        assertEquals(target.segmentIndex, ctx.target.segmentIndex)
    }

    // ── 7. 残余动词短语不得粘在名字上（真实书实测：闫妮追问 / 曹健开玩笑）──────
    @Test
    fun `verb phrase residue never sticks to the name`() {
        assertEquals("闫妮", speak("闫妮追问道：“那我什么时候会遇到贵人？”").speaker)
        assertEquals("曹健", speak("曹健开玩笑道：“上面又没刻我的名字。”").speaker)
        assertEquals("林夜", speak("林夜解释道：“我不是那个意思。”").speaker)
    }

    // ── 8. 实体前缀消歧（安全网）：词表没吃净时靠 knownEntities 收敛 ──────────
    @Test
    fun `known entity prefix disambiguates residue`() {
        // 真实形态：4 字块（真名 2 字 + 2 字动词残留），块本身不是已知实体、其前缀是
        val text = "闫妮追赶道：“别跑！”"
        val p = para(text)
        val segs = SemanticSegmenter().segment(p)
        val res = RuleSpeakerBaseline(knownEntities = setOf("闫妮")).assign(p, segs, emptyList())
        val speech = segs.first { it.type == SegmentType.SPEECH }
        assertEquals("闫妮", res.getValue(speech.segmentIndex).speaker, "实体前缀消歧失败")
        // 无实体集时不得乱截断（保持既有行为）
        val res2 = RuleSpeakerBaseline().assign(p, segs, emptyList())
        assertEquals("闫妮追赶", res2.getValue(speech.segmentIndex).speaker, "无实体集时不应启用前缀消歧")
    }

    /** 最小只读 store：本测试不需要角色库数据。 */
    private object NoopStore : CharacterReadStore {
        override fun entityByPk(entityPk: Long): NarrativeEntity? = null
        override fun entityByCanonicalName(bookPk: Long, name: String): NarrativeEntity? = null
        override fun canonicalNamesOf(bookPk: Long): Set<String> = emptySet()
        override fun aliasesOf(bookPk: Long, clusterId: String): List<String> = emptyList()
        override fun resolveIdentity(bookPk: Long, entityPk: Long) = IdentityResolution(entityPk, null, "UNKNOWN")
        override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long) =
            EffectiveVoiceState(entityPk, null, null, null, emptyMap<String, CharacterAttribute>())

        override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? = null
        override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? = null
        override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean = false
        override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int> = emptyMap()
    }

    // ── 9. 介词/动作残留粘在真名前（真实书实测：对闫呢 / 指着甜妹）────────────
    @Test
    fun `leading cue particle is stripped when the remainder is a known entity`() {
        for ((text, expect) in listOf(
            "对闫呢道：“你怎么看？”" to "闫呢",
            "指着甜妹道：“就是她。”" to "甜妹",
            "望着曹健道：“你来了。”" to "曹健",
        )) {
            val p = para(text)
            val segs = SemanticSegmenter().segment(p)
            val known = setOf("闫呢", "甜妹", "曹健")
            val res = RuleSpeakerBaseline(knownEntities = known).assign(p, segs, emptyList())
            val speech = segs.first { it.type == SegmentType.SPEECH }
            assertEquals(expect, res.getValue(speech.segmentIndex).speaker, "后缀消歧失败: $text")
        }
        // 安全阀：无实体集时不得裁剪（保持既有行为）
        val p2 = para("对闫呢道：“你怎么看？”")
        val segs2 = SemanticSegmenter().segment(p2)
        val r2 = RuleSpeakerBaseline().assign(p2, segs2, emptyList())
        val sp2 = segs2.first { it.type == SegmentType.SPEECH }
        assertEquals("对闫呢", r2.getValue(sp2.segmentIndex).speaker, "无实体集时不应启用后缀消歧")
    }
}
