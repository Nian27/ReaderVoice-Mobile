package com.readervoice.data.script

import com.readervoice.data.semantic.CharacterId
import com.readervoice.data.semantic.Delivery
import com.readervoice.data.semantic.DirectorType
import com.readervoice.data.semantic.Emotion
import com.readervoice.data.semantic.Pace
import com.readervoice.data.semantic.ScriptLine
import com.readervoice.data.semantic.ScriptLineStatus
import com.readervoice.data.semantic.SpeakerRef
import com.readervoice.data.semantic.Tone
import com.readervoice.data.semantic.TtsRoute
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.data.semantic.VoiceEvent
import com.readervoice.data.semantic.Volume
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CH-0 / P0-B：**剧本持久化 Gate**（用户定义的 PASS 标准）。
 *
 * ```
 * 生成 20 段 → 强杀（进程重启 = 新建 repository 实例）→ 重进章节 → 20 条恢复
 * 再继续     → 从第 21 段开始 → 没有重复行
 * ```
 *
 * 注意：**"文件存在"不算 PASS** —— 必须验证"重进能看到 + 从断点继续 + 不重复"。
 */
class ScriptPersistenceGateTest {

    private val bookId = "book_test"
    private val chapterId = 961L          // CH-0/M0.4 的内容派生 id 形态
    private val srcRev = "src_1"

    private fun root(): File = Files.createTempDirectory("rv-script-").toFile()

    private fun line(i: Int, outcome: ValidationOutcome = ValidationOutcome.ACCEPTED_ALL): ScriptLine = ScriptLine(
        segmentId = 1000L + i,
        paragraphId = 200L + i,
        segmentIndex = 0,
        text = "第 $i 段文本",
        sourceStart = 0,
        sourceEnd = 6,
        speaker = if (i % 3 == 0) SpeakerRef.Character(CharacterId("boot-林夜")) else SpeakerRef.Narrator,
        type = if (i % 3 == 0) DirectorType.DIALOGUE else DirectorType.NARRATION,
        emotion = Emotion.NEUTRAL,
        emotionIntensity = 0.5f,
        delivery = Delivery(Pace.NORMAL, Volume.NORMAL, Tone.NEUTRAL),
        voiceEvent = VoiceEvent.NORMAL,
        evidence = emptyList(),
        outcome = outcome,
        route = if (i % 3 == 0) TtsRoute.COSYVOICE_CHARACTER else TtsRoute.AUDIO8_NARRATOR,
        status = ScriptLineStatus.PENDING,
    )

    @Test
    fun `generate 20 - process death - reopen restores 20 - resume from 21 without duplicates`() {
        val root = root()

        // ── ① 生成 20 段 ────────────────────────────────────────────
        run {
            val repo = ScriptRepository(root)
            val run = repo.beginOrResume(bookId, chapterId, srcRev, totalSegments = 140)
            assertTrue(!run.resumed, "首次应为新建")
            for (i in 1..20) run.append(line(i))
            run.commit(20)                                  // ★ 提交点
            assertEquals(20, repo.loadLines(bookId, chapterId).size)
        }

        // ── ② 强杀：丢弃一切内存状态，只用磁盘重建 ───────────────────
        val repo2 = ScriptRepository(root)
        val restored = repo2.loadLines(bookId, chapterId)
        assertEquals(20, restored.size, "重进必须能看到已生成的 20 条（这就是产品意义上的“已保存”）")
        val st = assertNotNull(repo2.loadState(bookId, chapterId))
        assertEquals(20, st.nextSegmentIndex, "检查点应停在 20")
        assertEquals(ScriptState.RUNNING, st.status)
        assertEquals(20, st.lineCount)

        // ── ③ 续跑：从第 21 段开始，且不重复 ─────────────────────────
        val run2 = repo2.beginOrResume(bookId, chapterId, srcRev, totalSegments = 140)
        assertTrue(run2.resumed, "同 sourceRevisionId 的 RUNNING 状态应被复用")
        assertTrue(run2.shouldSkip(19), "第 20 段应被跳过")
        assertTrue(!run2.shouldSkip(20), "第 21 段应被执行")

        for (i in 21..21) run2.append(line(i))
        run2.commit(21)
        run2.finish(ScriptState.COMPLETED)

        val all = repo2.loadLines(bookId, chapterId)
        assertEquals(21, all.size)
        val ids = all.map { Regex("\"segment_id\":(\\d+)").find(it)!!.groupValues[1] }
        assertEquals(ids.size, ids.distinct().size, "不得出现重复 ScriptLine")
        assertEquals("1020", ids[19])                       // 第 20 条仍是原来的（segmentId = 1000+20）
        assertEquals("1021", ids[20])                       // 第 21 条是新追加的（segmentId = 1000+21）
        assertEquals(ScriptState.COMPLETED, repo2.loadState(bookId, chapterId)!!.status)
    }

    @Test
    fun `segment reject does not fail the run`() {
        val root = root()
        val repo = ScriptRepository(root)
        val run = repo.beginOrResume(bookId, chapterId, srcRev, totalSegments = 10)
        run.append(line(1))
        run.append(line(2, ValidationOutcome.VERIFY))
        run.countRejected()                                  // 段级 REJECT
        run.commit(3)
        val st = repo.loadState(bookId, chapterId)!!
        assertEquals(ScriptState.RUNNING, st.status, "段级 REJECT ≠ RUN FAILED")
        assertEquals(1, st.rejected)
        assertEquals(1, st.verify)
        assertEquals(2, st.lineCount)
    }

    @Test
    fun `different source revision starts a new round and keeps old revision file`() {
        val root = root()
        val repo = ScriptRepository(root)
        val a = repo.beginOrResume(bookId, chapterId, "src_1", totalSegments = 5)
        a.append(line(1)); a.commit(1)
        val b = repo.beginOrResume(bookId, chapterId, "src_2", totalSegments = 5)
        assertTrue(!b.resumed, "源版本变化必须开新一轮，不能接着旧检查点")
        assertEquals(0, repo.loadLines(bookId, chapterId).size, "新一轮的正文从空开始")
        val revisions = File(File(File(File(root, bookId), "scripts"), chapterId.toString()), "revisions")
        assertTrue(revisions.listFiles()!!.size >= 2, "旧 revision 文件必须保留")
        assertNotEquals(a.state.revisionId, b.state.revisionId)
    }
}
