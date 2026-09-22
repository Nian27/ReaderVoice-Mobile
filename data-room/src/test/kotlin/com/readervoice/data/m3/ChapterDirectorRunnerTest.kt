package com.readervoice.data.m3

import com.readervoice.data.Db
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.data.semantic.ChapterDirectorRunner
import com.readervoice.data.semantic.FailureCode
import com.readervoice.data.semantic.ScriptLineCodec
import com.readervoice.data.semantic.ScriptLineStatus
import com.readervoice.data.semantic.SpeakerRef
import com.readervoice.data.semantic.TtsRoute
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ReadPolicy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3 步骤 5：**ChapterDirectorRunner** 的 Gate 测试。
 *
 * 覆盖用户冻结的四条约束：协作式取消（已产出保留）、增量落盘、旁白不问模型、逐段 fail-closed。
 * 外加 G3a（锚定安全）与 G3b（覆盖率单列）的结构性断言。
 */
class ChapterDirectorRunnerTest {

    private val bookPk = 1L

    private fun store(): CharacterStore {
        val db = Db(createTempDirectory("rv-runner-").resolve("t.db").toString())
        db.createSchema(); SchemaMigrationV2(db).migrate()
        val st = CharacterStore(db)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-runner','2026-09-17')")
        for (n in listOf("林夜", "傅远")) st.upsertEntity(bookPk, "uid-$n", "PERSON", n, "CONFIRMED")
        return st
    }

    /** 一章 = 若干段落：旁白 + 对白（含显式 cue 与无 cue）。 */
    private fun chapter(): List<LogicalParagraph> {
        val texts = listOf(
            "林夜走进教室，四下看了看。",                       // 旁白（无对白）
            "傅远抬起头，说道：“你来了。”",                     // 对白（显式 cue）
            "“坐吧。”傅远指了指旁边的椅子。",                   // 对白（后置 cue）
            "两人沉默了一会儿，窗外的雨还在下。",                 // 旁白
            "“我听说你要走了。”林夜低声道。",                   // 对白
        )
        return texts.mapIndexed { i, t ->
            LogicalParagraph(
                paragraphId = 100L + i, revisionId = 100L + i, bookId = "b-runner", chapterId = 1L,
                sourceRevisionId = "s", paragraphIndex = i, sourceSpans = emptyList(),
                normalizedText = t, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
                boundaryConfidence = 0.9,
            )
        }
    }

    /** 固定回答的 decider（记录被调用次数）。 */
    private class StubDecider(private val answer: String?) : ChapterDirectorRunner.ModelDecider {
        var calls = 0
            private set

        override fun decide(prompt: String): String? {
            calls++
            val token = Regex("要分析的片段编号：(S\\d+)").find(prompt)?.groupValues?.get(1) ?: "S0"
            return answer?.replace("__SEG__", token)
        }
    }

    private fun validAnswerFor(prompt: String, speaker: String = "C0", type: String = "DIALOGUE"): String {
        val token = Regex("要分析的片段编号：(S\\d+)").find(prompt)!!.groupValues[1]
        val t = if (prompt.contains("该片段的类型已由切分器确定：〔旁白〕")) "NARRATION" else type
        return """{"segment_id":"$token","speaker":"$speaker","type":"$t","emotion":"CALM",
            "emotion_intensity":0.4,"delivery":{"pace":"NORMAL","volume":"NORMAL","tone":"NEUTRAL"},
            "voice_event":"NORMAL","evidence":[]}"""
    }

    // ── ① 整章跑通：顺序、覆盖、锚定安全、增量落盘 ──────────────────────
    @Test
    fun `chapter run emits every readable segment in order with canonical text`() {
        val st = store()
        val runner = ChapterDirectorRunner(st, bookPk)
        val decider = object : ChapterDirectorRunner.ModelDecider {
            override fun decide(prompt: String) = validAnswerFor(prompt)
        }
        val lines = mutableListOf<com.readervoice.data.semantic.ScriptLine>()
        val stats = runner.run(chapter(), decider, onLine = { lines += it })

        assertEquals(0, stats.reject, "不应有 REJECT")
        assertEquals(stats.segments, stats.linesEmitted, "每个可朗读片段都应产出一行")
        assertEquals(1.0, stats.coverage, 1e-9)
        assertEquals(0.0, stats.failClosedRate, 1e-9)
        assertFalse(stats.canceled)

        // G3a 锚定安全：文本必须是该段落 canonical 切片的逐字内容
        val paras = chapter().associateBy { it.paragraphId }
        lines.forEach { line ->
            val p = paras.getValue(line.paragraphId)
            assertEquals(p.normalizedText.substring(line.sourceStart, line.sourceEnd), line.text)
        }
        // 顺序单调
        val order = lines.map { it.paragraphId to it.segmentIndex }
        assertEquals(order.sortedWith(compareBy({ it.first }, { it.second })), order, "顺序必须单调")

        // 旁白走 shortcut（不问模型），对白问模型
        assertEquals(2, stats.decidedByShortcut, "两个纯旁白段落应走 shortcut")
        assertEquals(stats.segments - stats.decidedByShortcut, stats.modelCalls)

        // 路由
        val narrator = lines.filter { it.type.name == "NARRATION" }
        assertTrue(narrator.all { it.route == TtsRoute.AUDIO8_NARRATOR && it.speaker == SpeakerRef.Narrator })
        val dialog = lines.filter { it.type.name == "DIALOGUE" }
        assertTrue(dialog.isNotEmpty())
        assertTrue(dialog.all { it.route == TtsRoute.COSYVOICE_CHARACTER })
        assertTrue(lines.all { it.status == ScriptLineStatus.PENDING }, "ACCEPTED_ALL ≠ COMMITTED")
    }

    // ── ② 协作式取消：已产出的行保留，且不再调用模型 ────────────────────
    @Test
    fun `epoch cancel stops promptly and keeps already emitted lines`() {
        val st = store()
        val runner = ChapterDirectorRunner(st, bookPk)
        val decider = object : ChapterDirectorRunner.ModelDecider {
            var calls = 0
            override fun decide(prompt: String): String {
                calls++
                return validAnswerFor(prompt)
            }
        }
        val lines = mutableListOf<com.readervoice.data.semantic.ScriptLine>()
        var emitted = 0
        val stats = runner.run(
            chapter(), decider,
            cancel = { emitted >= 2 },                 // 产出 2 行后取消
            onLine = { lines += it; emitted++ },
        )
        assertTrue(stats.canceled, "应记录取消")
        assertEquals(2, stats.linesEmitted, "取消时已产出的行必须保留")
        assertEquals(2, lines.size)
        // 首段是旁白（shortcut，不调用模型）⇒ 取消前只有 1 次模型调用；取消后不得再调用
        assertEquals(1, stats.modelCalls)
        assertEquals(stats.modelCalls, decider.calls, "取消后不得再调用模型")
    }

    // ── ③ 逐段 fail-closed：坏答案只影响本段 ────────────────────────────
    @Test
    fun `per segment fail closed does not poison the chapter`() {
        val st = store()
        val runner = ChapterDirectorRunner(st, bookPk)
        var n = 0
        val decider = object : ChapterDirectorRunner.ModelDecider {
            override fun decide(prompt: String): String {
                n++
                // 第 1 次调用给悬空候选 C7 ⇒ 硬失败；其余正常
                return if (n == 1) validAnswerFor(prompt).replace("\"C0\"", "\"C7\"")
                else validAnswerFor(prompt)
            }
        }
        val lines = mutableListOf<com.readervoice.data.semantic.ScriptLine>()
        val stats = runner.run(chapter(), decider, onLine = { lines += it })

        assertEquals(1, stats.reject, "只有那一段 REJECT")
        assertTrue(stats.failureCodes.containsKey(FailureCode.SPEAKER_ID_MISSING.name))
        assertEquals(stats.segments - 1, stats.linesEmitted, "其余段照常产出")
        assertTrue(stats.coverage > 0.5)
        assertTrue(stats.failClosedRate > 0.0, "fail-closed 必须是单独指标")
        assertEquals(1.0 - stats.failClosedRate, stats.coverage, 1e-9)
    }

    // ── ④ 模型无输出 ⇒ 计入 fail-closed，不中断 ─────────────────────────
    @Test
    fun `missing model output is counted and does not stop the run`() {
        val st = store()
        val runner = ChapterDirectorRunner(st, bookPk)
        val decider = StubDecider(null)
        val stats = runner.run(chapter(), decider)
        assertEquals(stats.modelCalls, stats.noOutput)
        assertEquals(stats.modelCalls, stats.reject)
        assertEquals(2, stats.linesEmitted, "旁白仍然产出（shortcut 不依赖模型）")
    }

    // ── ⑤ 确定性 + JSONL 编解码 ─────────────────────────────────────────
    @Test
    fun `run is deterministic and jsonl codec round trips`() {
        val st = store()
        fun once(): String {
            val runner = ChapterDirectorRunner(st, bookPk)
            val decider = object : ChapterDirectorRunner.ModelDecider {
                override fun decide(prompt: String) = validAnswerFor(prompt)
            }
            val sb = StringBuilder()
            runner.run(chapter(), decider, onLine = { sb.append(ScriptLineCodec.encode(it)).append('\n') })
            return sb.toString()
        }
        val a = once(); val b = once()
        assertEquals(a, b, "同输入同 decider 必须逐字节一致")
        assertTrue(a.lines().isNotEmpty())
        assertTrue(a.contains("\"route\":"))
        assertTrue(a.contains("\"outcome\":\"ACCEPTED_ALL\"") || a.contains("\"outcome\":\"VERIFY\""))
    }
}
