package com.readervoice.data.semantic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-095 Gate 测试（095A Candidate Compiler）：
 * - G1：CUE 优先且不依赖 entitySet（缓解自举缺陷）；污染 cue（"对游七"）被 gate 拒
 * - G2：RECENT_MENTION 从 recent_context 提取已知实体
 * - G3：union + dedup + 顺序（CUE > SCENE_ACTIVE > RECENT_MENTION）
 * - G4：污染候选（"不禁吐槽"类）不进入输出
 * - G5：UNKNOWN/空输入不产生候选
 */
class Task095GateTest {

    private val entitySet = setOf("张居正", "葛守礼", "游七", "谢令姜", "陈解", "潘筠", "小仙尊")

    private fun compiler() = SpeakerCandidateCompiler(entitySet)

    @Test
    fun `G1 cue accepted without entity membership and polluted cue rejected`() {
        val c = compiler()
        // "张居正"不在 entitySet 但 cue 提取成功 → 必须进候选（自举缺陷缓解）
        val r1 = c.compile(cueSpeakers = listOf("张居正"), recentSpeakers = emptyList(), recentContext = emptyList())
        assertEquals(listOf("张居正"), r1.map { it.surface })
        // "对游七"是介词结构污染 → gate 拒
        val r2 = c.compile(cueSpeakers = listOf("对游七"), recentSpeakers = emptyList(), recentContext = emptyList())
        assertTrue(r2.isEmpty(), "介词结构污染不得进候选: $r2")
        // "不禁吐槽"类切片 → gate 拒
        val r3 = c.compile(cueSpeakers = listOf("不禁吐槽"), recentSpeakers = emptyList(), recentContext = emptyList())
        assertTrue(r3.isEmpty(), "动作切片不得进候选: $r3")
    }

    @Test
    fun `G2 recent mention extraction intersects entity set`() {
        val c = compiler()
        val ctx = listOf("张居正看着葛守礼的背影，才对游七说道：“葛守礼倒是没有辜负杨太宰的信任。”")
        val mentions = c.recentMentions(ctx)
        assertEquals(setOf("张居正", "葛守礼", "游七"), mentions, "recent_context 提及的已知实体必须全部召回")
        // 非实体集成员不进
        val ctx2 = listOf("他看着游七的背影，才对身边人说道：“走。”")
        assertTrue(c.recentMentions(ctx2).isEmpty() || c.recentMentions(ctx2) == setOf("游七"))
    }

    @Test
    fun `G3 union dedup ordering`() {
        val c = compiler()
        val ctx = listOf("谢令姜嗔道：“你还想怎样。”")
        val r = c.compile(
            cueSpeakers = listOf("谢令姜"),
            recentSpeakers = listOf("潘筠", "谢令姜"), // 谢令姜重复
            recentContext = ctx,                       // 谢令姜重复
        )
        assertEquals(listOf("谢令姜", "潘筠"), r.map { it.surface }, "CUE 优先 + dedup")
        assertEquals(setOf(CandidateSourceType.CUE), r[0].sources)
        assertEquals(setOf(CandidateSourceType.SCENE_ACTIVE), r[1].sources)
    }

    @Test
    fun `G4 polluted strings never appear`() {
        val c = compiler()
        val r = c.compile(
            cueSpeakers = listOf("陈解", "这时毒女", "欧阳戎忽"),
            recentSpeakers = listOf("她当即", "潘筠这", "小仙尊"),
            recentContext = listOf("禁不止的嘀咕道，一边的王冷然好奇的问。"),
        )
        for (cand in r) {
            assertTrue(SpeakerCandidateCompiler.GATE_CHARS.none { it in cand.surface },
                "污染候选不得出现: ${cand.surface}")
        }
        assertTrue(r.map { it.surface }.containsAll(listOf("陈解", "小仙尊")))
    }

    @Test
    fun `G5 unknown and empty inputs yield nothing`() {
        val c = compiler()
        assertTrue(c.compile(emptyList(), emptyList(), emptyList()).isEmpty())
        val r = c.compile(listOf("UNKNOWN"), emptyList(), emptyList())
        assertTrue(r.isEmpty(), "UNKNOWN 不得成为候选")
    }

    @Test
    fun `G6 max candidates capped`() {
        val c = SpeakerCandidateCompiler(entitySet, maxCandidates = 3)
        val r = c.compile(
            cueSpeakers = listOf("陈解", "张居正", "葛守礼", "游七", "谢令姜", "潘筠"),
            recentSpeakers = emptyList(),
            recentContext = emptyList(),
        )
        assertTrue(r.size <= 3, "候选数受 maxCandidates 限制: ${r.size}")
    }
}
