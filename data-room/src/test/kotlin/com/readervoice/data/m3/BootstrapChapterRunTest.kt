package com.readervoice.data.m3

import com.readervoice.data.semantic.ChapterDirectorRunner
import com.readervoice.data.snapshot.BootstrapCharacterStore
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ReadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3 步骤 5：**自举 store + 整章运行的设备可行性**。
 *
 * 设备上没有角色库；本测试证明"仅有角色名集合"也能让整章链路跑通，
 * 且缺失信息一律退化为默认值（不假装有数据）。
 */
class BootstrapChapterRunTest {

    private fun chapter(): List<LogicalParagraph> = listOf(
        "林夜推开教室的门，雨声一下子涌了进来。",
        "傅远正坐在窗边，抬头看了他一眼。",
        "傅远说道：“你终于来了。”",
        "“路上堵车。”林夜把伞靠在墙上。",
        "两人都没再说话，只有雨点敲着玻璃。",
    ).mapIndexed { i, t ->
        LogicalParagraph(
            paragraphId = 500L + i, revisionId = 500L + i, bookId = "b-boot", chapterId = 9L,
            sourceRevisionId = "s", paragraphIndex = i, sourceSpans = emptyList(),
            normalizedText = t, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
            boundaryConfidence = 0.9,
        )
    }

    @Test
    fun `bootstrap store supports a full chapter run`() {
        val store = BootstrapCharacterStore(setOf("林夜", "傅远"))
        assertEquals(setOf("林夜", "傅远"), store.canonicalNamesOf(1L))
        assertTrue(store.entityByCanonicalName(1L, "林夜") != null)
        assertEquals("PROVISIONAL", store.resolveIdentity(1L, 1L).status)

        val runner = ChapterDirectorRunner(store, bookPk = 1L)
        val answers = mutableListOf<String>()
        val decider = object : ChapterDirectorRunner.ModelDecider {
            override fun decide(prompt: String): String {
                val token = Regex("要分析的片段编号：(S\\d+)").find(prompt)!!.groupValues[1]
                answers += token
                return """{"segment_id":"$token","speaker":"C0","type":"DIALOGUE","emotion":"CALM",
                    "emotion_intensity":0.4,"delivery":{"pace":"NORMAL","volume":"NORMAL","tone":"NEUTRAL"},
                    "voice_event":"NORMAL","evidence":[]}"""
            }
        }
        val emitted = mutableListOf<String>()
        val stats = runner.run(chapter(), decider, onLine = { emitted += it.text })

        assertTrue(stats.segments > 0)
        assertEquals(stats.segments, stats.linesEmitted, "自举 store 下仍应全量产出")
        assertEquals(1.0, stats.coverage, 1e-9)
        assertEquals(0, stats.reject)
        // 旁白段不问模型；问到的段都拿到了回答
        assertEquals(stats.modelCalls, answers.size)
        assertTrue(stats.decidedByShortcut > 0, "本章含纯旁白段，应有 shortcut")
        // 产出文本必须是原文子串（canonical 锚定）
        val all = chapter().joinToString("\u0000") { it.normalizedText }
        emitted.forEach { assertTrue(all.contains(it), "产出文本不在原文中: $it") }
        println("[BOOT] " + stats.pretty().replace("\n", " | "))
    }
}
