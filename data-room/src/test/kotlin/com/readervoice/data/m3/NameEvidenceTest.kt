package com.readervoice.data.m3

import com.readervoice.data.semantic.NameEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3：**名称证据与群体判据**的证据测试。
 *
 * 规则形态参考 v90.9（RESEARCH_ONLY 研究资产），实现为自写代码。
 * 重点验证两件事：
 *   ① 高精度：该命中的命中（别名/非同人/群体）
 *   ② 有界性：模式**不得跨句**、gap **不得无限** —— 否则会退化成"关键词命中 = 事实"
 */
class NameEvidenceTest {

    // ── 群体称呼 ────────────────────────────────────────────────────────
    @Test
    fun `group names are recognized and never merge with a single person`() {
        val groups = listOf(
            "众人", "二人", "两人", "三人", "几人", "数人", "一行人", "一群人", "大家",
            "三名弟子", "几个修士", "一群女子", "一男一女", "在座修士", "众弟子", "他们",
        )
        for (g in groups) assertTrue(NameEvidence.isGroupName(g), "未识别为群体: $g")
        for (n in listOf("张明", "李雪", "王老", "林夜", "闫妮", "曹健")) {
            assertFalse(NameEvidence.isGroupName(n), "真人名被误判为群体: $n")
        }
        assertEquals("群体/单人不合并", NameEvidence.mergeBlockReason("众人", "张明"))
        assertEquals("", NameEvidence.mergeBlockReason("张明", "李雪"))
        assertEquals("", NameEvidence.mergeBlockReason("张明", "张明"))
    }

    // ── 别名正向证据 ────────────────────────────────────────────────────
    @Test
    fun `alias evidence is detected with bounded gap`() {
        assertNotNull(NameEvidence.aliasEvidence("林凡", "林夜", "林凡，本名林夜，自幼……"))
        assertNotNull(NameEvidence.aliasEvidence("林夜", "林凡", "林夜（林凡）站在原地。"))
        assertNotNull(NameEvidence.aliasEvidence("张三", "李四", "此人又叫李四。".replace("此人", "张三")))
        assertNull(NameEvidence.aliasEvidence("张三", "李四", "张三走了。李四来了。"))
    }

    @Test
    fun `alias evidence never crosses a sentence boundary`() {
        // 跨句：中间有句号 ⇒ 不得命中（有界纪律）
        val crossSentence = "林凡转过身去。他想起林夜这个名字。"
        assertNull(NameEvidence.aliasEvidence("林凡", "林夜", crossSentence), "模式跨越了句界")
        // 句内但太远（>50 字）⇒ 不得命中
        val farApart = "林凡" + "走".repeat(60) + "本名林夜"
        assertNull(NameEvidence.aliasEvidence("林凡", "林夜", farApart), "gap 未设上限")
    }

    // ── 反向证据 ────────────────────────────────────────────────────────
    @Test
    fun `contradiction evidence covers negation relation and dialogue`() {
        assertNotNull(NameEvidence.contradictionEvidence("张三", "李四", "张三并不是李四。"))
        assertNotNull(NameEvidence.contradictionEvidence("张三", "李四", "张三和李四不是同一个人。"))
        assertNotNull(NameEvidence.contradictionEvidence("张三", "李四", "张三和李四是两个人。"))
        // 关系 ≠ 同一人（师父≠徒弟 这一不变量在文本层的判据）
        assertNotNull(NameEvidence.contradictionEvidence("王老", "王明", "王老只是王明的父亲。"))
        // 对话 ⇒ 不同人
        assertNotNull(NameEvidence.contradictionEvidence("张三", "李四", "张三正在对李四说话。"))
        // 反例：真正的同一人陈述不得被判为矛盾
        assertNull(NameEvidence.contradictionEvidence("林凡", "林夜", "林凡就是林夜。"))
    }

    @Test
    fun `contradiction never crosses a sentence boundary`() {
        assertNull(NameEvidence.contradictionEvidence("张三", "李四", "张三走了。他并不是李四。"))
        assertNull(NameEvidence.contradictionEvidence("张三", "李四", "张三" + "走".repeat(40) + "不是李四"))
    }
}
