package com.readervoice.data.m3

import com.readervoice.data.character.CharacterAttribute
import com.readervoice.data.character.CharacterReadStore
import com.readervoice.data.character.EffectiveVoiceState
import com.readervoice.data.character.IdentityResolution
import com.readervoice.data.character.NarrativeEntity
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.DirectorSelectionPrompt
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ReadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3 步骤 2：**局部 ID 契约**与**协议渲染**。
 *
 * 必须保证的三件事：
 *   ① 候选只有局部 ID（C0…）+ 每样本确定性 permutation，且**内部 identityId 不进入 prompt**
 *   ② 证据用 E# 编号并带 emotion_hint
 *   ③ 片段编号可复述（S#），且"空候选 + 对白 ⇒ 不调用模型直接 UNKNOWN"
 */
class DirectorContextLocalIdTest {

    private fun para(text: String, id: Long = 11L) = LogicalParagraph(
        paragraphId = id, revisionId = id, bookId = "b", chapterId = null,
        sourceRevisionId = "s", paragraphIndex = 0, sourceSpans = emptyList(),
        normalizedText = text, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
        boundaryConfidence = 0.9,
    )

    /** store：林夜/傅远 两个实体；林夜的 cluster 里还有别名"林凡"。 */
    private object Store : CharacterReadStore {
        private val byName = mapOf(
            "林夜" to NarrativeEntity(1, "e-linye", 1, "PERSON", "林夜", "CONFIRMED"),
            "傅远" to NarrativeEntity(2, "e-fuyuan", 1, "PERSON", "傅远", "CONFIRMED"),
            "众人" to NarrativeEntity(3, "e-zhongren", 1, "GROUP", "众人", "CONFIRMED"),
        )

        override fun entityByPk(entityPk: Long): NarrativeEntity? = byName.values.firstOrNull { it.entityPk == entityPk }
        override fun entityByCanonicalName(bookPk: Long, name: String) = byName[name]
        override fun canonicalNamesOf(bookPk: Long): Set<String> = byName.keys
        override fun aliasesOf(bookPk: Long, clusterId: String): List<String> =
            if (clusterId == "cl-linye") listOf("林夜", "林凡") else emptyList()

        override fun resolveIdentity(bookPk: Long, entityPk: Long) = when (entityPk) {
            1L -> IdentityResolution(1, "cl-linye", "CONFIRMED")
            else -> IdentityResolution(entityPk, null, "CONFIRMED")
        }

        override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long) =
            EffectiveVoiceState(entityPk, null, null, null, emptyMap<String, CharacterAttribute>())

        override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? = null
        override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? = null
        override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean = false
        override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int> = emptyMap()
    }

    private fun context(text: String, id: Long = 11L): com.readervoice.data.semantic.DirectorContext {
        val p = para(text, id)
        val segs = SemanticSegmenter().segment(p)
        val res = RuleSpeakerBaseline(knownEntities = Store.canonicalNamesOf(1L)).assign(p, segs, emptyList())
        return ContextBuilder(Store).build(1L, p, segs, res, emptyList(), p.paragraphId)
    }

    // ── ① 局部 ID + 不泄漏身份 ──────────────────────────────────────────
    @Test
    fun `candidates use local ids and never leak identity ids`() {
        val ctx = context("林夜看了傅远一眼，说道：“你来了。”")
        assertTrue(ctx.candidateSpeakers.isNotEmpty(), "应有候选")
        ctx.candidateSpeakers.forEachIndexed { i, c ->
            assertEquals("C$i", c.localId, "局部 ID 必须连续且从 C0 开始")
            assertTrue(c.identityId.isNotEmpty())
        }
        val prompt = DirectorSelectionPrompt.render(ctx)
        for (c in ctx.candidateSpeakers) {
            assertFalse(prompt.contains(c.identityId), "★ prompt 泄漏了内部 identityId: ${c.identityId}")
        }
        assertTrue(prompt.contains("C0"), "prompt 应给出局部候选 ID")
        // 约束也用局部 ID
        ctx.identityConstraints.forEach {
            assertFalse(it.contains("e-"), "约束泄漏内部 ID: $it")
        }
    }

    @Test
    fun `local id permutation is deterministic per sample`() {
        val a = context("林夜看了傅远一眼，说道：“你来了。”").candidateSpeakers.map { it.localId to it.name }
        val b = context("林夜看了傅远一眼，说道：“你来了。”").candidateSpeakers.map { it.localId to it.name }
        assertEquals(a, b, "同一样本两次构建必须得到相同编号（两端才能对拍）")
        // 不同段落的种子不同 ⇒ 不保证顺序一致（这正是 permutation 的目的）
        val c = context("林夜看了傅远一眼，说道：“你来了。”", id = 99L).candidateSpeakers.map { it.localId to it.name }
        assertNotNull(c)
    }

    // ── ② 证据编号 + emotion_hint ──────────────────────────────────────
    @Test
    fun `evidence items are numbered and carry emotion hints`() {
        val ctx = context("傅远坐下扫了一眼，饶有兴致道：“有点意思。”")
        val dc = ctx.evidenceItems.filter { it.kind == "DELIVERY_CUE" }
        assertTrue(dc.isNotEmpty(), "delivery cue 应进入证据: ${ctx.evidenceItems}")
        assertEquals("interested", dc.first().emotionHint)
        assertEquals(ctx.evidenceItems.indices.map { "E$it" }, ctx.evidenceItems.map { it.id })

        val prompt = DirectorSelectionPrompt.render(ctx)
        assertTrue(prompt.contains("${dc.first().id} [DELIVERY_CUE]"), "证据段应含 E# 与类型")
        assertTrue(prompt.contains("情绪参考 interested"), "证据段应含 emotion_hint")
    }

    // ── ③ 片段编号可复述 + 空候选 shortcut ──────────────────────────────
    @Test
    fun `target segment token is renderable and echoed as required field`() {
        val ctx = context("林夜说道：“走。”")
        val token = DirectorSelectionPrompt.segmentToken(ctx.target)
        assertTrue(token.startsWith("S"))
        val prompt = DirectorSelectionPrompt.render(ctx)
        assertTrue(prompt.contains("要分析的片段编号：$token"))
        assertTrue(prompt.contains("- segment_id: 固定填 $token"))
    }

    @Test
    fun `no candidates means skip the model and answer UNKNOWN`() {
        // 没有任何已知实体 ⇒ 候选为空 ⇒ 不调用模型
        val p = para("陌生人甲说道：“谁？”", 77L)
        val segs = SemanticSegmenter().segment(p)
        val res = RuleSpeakerBaseline().assign(p, segs, emptyList())
        val ctx = ContextBuilder(Store).build(1L, p, segs, res, emptyList(), p.paragraphId)
        // 候选可能为空也可能含"陌生人甲"（未知实体也可能被 cue 召回）；这里断言的是【契约行为】
        if (ctx.candidateSpeakers.isEmpty()) {
            assertEquals("UNKNOWN", DirectorSelectionPrompt.skipDecision(ctx))
        } else {
            assertNull(DirectorSelectionPrompt.skipDecision(ctx))
        }
    }

    @Test
    fun `group name never becomes a candidate`() {
        val ctx = context("众人齐声道：“遵命。”")
        assertFalse(ctx.candidateSpeakers.any { it.name == "众人" }, "群体称呼不得成为单人人名候选")
    }

    // ── 9. shortcut：旁白段不问模型；对白无候选不猜名字 ─────────────────────
    @Test
    fun `narration segments are decided without calling the model`() {
        val ctx = context("林夜走进教室，坐了下来。")     // 纯旁白
        assertEquals("NARRATION", ctx.target.segmentType.uppercase())
        assertEquals("NARRATOR", DirectorSelectionPrompt.skipDecision(ctx))
        assertTrue(DirectorSelectionPrompt.isShortcut(ctx))
    }

    @Test
    fun `dialogue with candidates must call the model`() {
        val ctx = context("林夜说道：“走。”")
        assertNull(DirectorSelectionPrompt.skipDecision(ctx), "有候选的对白必须交给模型")
    }
}
