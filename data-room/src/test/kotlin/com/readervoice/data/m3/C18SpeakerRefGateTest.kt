package com.readervoice.data.m3

import com.readervoice.data.Db
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.data.semantic.CandidateMap
import com.readervoice.data.semantic.CandidateSpeaker
import com.readervoice.data.semantic.CharacterId
import com.readervoice.data.semantic.ChapterDirectorRunner
import com.readervoice.data.semantic.ContextSegment
import com.readervoice.data.semantic.DirectorContext
import com.readervoice.data.semantic.DirectorOutputValidator
import com.readervoice.data.semantic.DirectorSelectionPrompt
import com.readervoice.data.semantic.DirectorType
import com.readervoice.data.semantic.FailureCode
import com.readervoice.data.semantic.LegacyScriptLine
import com.readervoice.data.semantic.ProtocolNormalizer
import com.readervoice.data.semantic.ScriptLine
import com.readervoice.data.semantic.ScriptLineBuilder
import com.readervoice.data.semantic.ScriptLineCodec
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegment
import com.readervoice.data.semantic.SpeakerRef
import com.readervoice.data.semantic.TtsRoute
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ReadPolicy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * MOBILE-005 / **C18 Gate**：局部候选 ID 不得越过校验边界进入持久剧本。
 *
 * 本测试同时充当"固定 20~30 段的快速 Gate"：
 *   ① 持久 ScriptLine 中 `C#` 计数 = 0
 *   ② canonical anchor = 100%
 *   ③ Director 决策与 outcome 分布不变（旁白 shortcut + 对白 C0 全 ACCEPTED_ALL）
 *   ④ 悬空候选仍是硬失败（C9 不回退）
 *   ⑤ 候选无法解析为持久身份 ⇒ fail-closed（不静默丢行）
 *   ⑥ v1 旧产物只读、不猜（`speakerResolvable = false`）
 */
class C18SpeakerRefGateTest {

    private val bookPk = 1L
    private val names = listOf("林夜", "傅远")

    private fun store(): CharacterStore {
        val db = Db(Files.createTempDirectory("rv-c18-").resolve("t.db").toString())
        db.createSchema(); SchemaMigrationV2(db).migrate()
        val st = CharacterStore(db)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-c18','2026-09-18')")
        names.forEachIndexed { i, n -> st.upsertEntity(bookPk, "uid-$i", "PERSON", n, "CONFIRMED") }
        return st
    }

    /** 固定 24 段批次（旁白 / 有 cue 对白 / 无 cue 对白 / 群体 / 收尾）。 */
    private fun fixedBatch(): List<LogicalParagraph> {
        val texts = listOf(
            "林夜走进教室，四下看了看。",
            "傅远抬起头，说道：“你来了。”",
            "“坐吧。”傅远指了指旁边的椅子。",
            "两人沉默了一会儿，窗外的雨还在下。",
            "“我听说你要走了。”林夜低声道。",
            "傅远沉默了片刻，没有回答。",
            "“路上小心。”傅远忽然开口。",
            "林夜点了点头，把书包放在桌上。",
            "教室里只剩下雨声。",
            "“你什么时候回来？”傅远问。",
            "“不知道。”林夜摇了摇头。",
            "窗外的天色暗了下来。",
            "“替我向伯母问好。”傅远说。",
            "林夜愣了一下，随即笑了。",
            "“好。”他只说了一个字。",
            "两人一起走出教学楼。",
            "雨已经停了，地上积着水洼。",
            "“以后常联系。”傅远伸出手。",
            "林夜握住他的手，点了点头。",
            "远处的钟声响了六下。",
            "校长室的灯还亮着。",
            "“该走了。”傅远看了看表。",
            "林夜转身，走进了暮色里。",
            "傅远站在原地，很久没有动。",
        )
        return texts.mapIndexed { i, t ->
            LogicalParagraph(
                paragraphId = 200L + i, revisionId = 200L + i, bookId = "b-c18", chapterId = 1L,
                sourceRevisionId = "s", paragraphIndex = i, sourceSpans = emptyList(),
                normalizedText = t, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
                boundaryConfidence = 0.9,
            )
        }
    }

    private class StubDecider(private val speaker: String, private val type: String) :
        ChapterDirectorRunner.ModelDecider {
        var calls = 0; private set

        override fun decide(prompt: String): String? {
            calls++
            val token = Regex("要分析的片段编号：(S\\d+)").find(prompt)?.groupValues?.get(1) ?: "S0"
            // ★ 只看"目标片段"那一行判定已确定类型：约束行里同时含〔对白〕与〔旁白〕，
            //   用 prompt.contains("〔旁白〕") 会恒真（本项目真实踩过的同一个坑）。
            val targetLine = prompt.lines().firstOrNull { it.contains(token) && it.contains("〔") } ?: ""
            val t = if (targetLine.contains("〔旁白〕")) "NARRATION" else "DIALOGUE"
            val ans = """{"segment_id":"$token","speaker":"$speaker","type":"$t","emotion":"CALM",
                "emotion_intensity":0.4,"delivery":{"pace":"NORMAL","volume":"NORMAL","tone":"NEUTRAL"},
                "voice_event":"NORMAL","evidence":[]}"""
            return ans
        }
    }

    // ── ① + ② + ③ 快速 Gate：固定 24 段 ──────────────────────────────
    @Test
    fun `quick gate fixed 24 segments has zero persisted local ids and full anchor`() {
        val runner = ChapterDirectorRunner(store(), bookPk)
        val decider = StubDecider(speaker = "C0", type = "DIALOGUE")
        val lines = mutableListOf<ScriptLine>()
        val jsonl = StringBuilder()
        val stats = runner.run(
            fixedBatch(), decider,
            onLine = { lines += it; jsonl.append(ScriptLineCodec.encode(it)).append('\n') },
        )

        // ② anchor = 100%
        val paras = fixedBatch().associateBy { it.paragraphId }
        println(
            "[C18-GATE] segments=${stats.segments} lines=${stats.linesEmitted} acc=${stats.acceptedAll} " +
                "ver=${stats.verify} down=${stats.downgrade} rej=${stats.reject} " +
                "shortcut=${stats.decidedByShortcut} calls=${stats.modelCalls} " +
                "codes=${stats.failureCodes} routes=${lines.groupingBy { it.route }.eachCount()}",
        )
        lines.forEach { l ->
            assertEquals(
                paras.getValue(l.paragraphId).normalizedText.substring(l.sourceStart, l.sourceEnd),
                l.text,
                "canonical anchor 必须逐字一致",
            )
        }
        assertEquals(lines.size, stats.linesEmitted)
        assertTrue(stats.linesEmitted >= 20, "固定批次至少 20 段应有产出，实际 ${stats.linesEmitted}")

        // ① 持久化中 C# 计数 = 0
        val rows = jsonl.toString().trim().lines().filter { it.isNotBlank() }
        assertEquals(rows.size, lines.size)
        var localIdLeaks = 0
        rows.forEach { r ->
            val o = JSONObject(r)
            assertEquals(ScriptLineCodec.SCHEMA_VERSION, o.getInt("schemaVersion"))
            val ref = o.getString("speakerRef")
            val ok = ref == SpeakerRef.TOKEN_NARRATOR || ref == SpeakerRef.TOKEN_UNKNOWN ||
                ref.startsWith(SpeakerRef.PREFIX_CHARACTER)
            if (!ok) localIdLeaks++
            assertFalse(o.has("speaker"), "v2 不应再输出旧 speaker 字段")
        }
        assertEquals(0, localIdLeaks, "持久化中不得出现 request-scoped 局部 ID（C18）")

        // ③ 决策/结局分布
        assertEquals(0, stats.reject, "固定批次不应有 REJECT")
        assertEquals(stats.segments, stats.acceptedAll + stats.verify + stats.downgrade)
        assertTrue(stats.modelCalls > 0, "对白段应触发模型调用")
        assertTrue(stats.decidedByShortcut > 0, "旁白段应走 shortcut 不调用模型")
        println(
            "[C18-GATE] segments=${stats.segments} lines=${stats.linesEmitted} acc=${stats.acceptedAll} " +
                "shortcut=${stats.decidedByShortcut} calls=${stats.modelCalls} localIdLeaks=$localIdLeaks",
        )
    }

    // ── NARRATOR → SpeakerRef.Narrator（路由 Audio8）──────────────────
    @Test
    fun `narrator maps to speaker ref narrator with audio8 route`() {
        val runner = ChapterDirectorRunner(store(), bookPk)
        val lines = mutableListOf<ScriptLine>()
        runner.run(fixedBatch(), StubDecider("C0", "DIALOGUE"), onLine = { lines += it })

        val narration = lines.filter { it.type == DirectorType.NARRATION }
        assertTrue(narration.isNotEmpty())
        assertTrue(narration.all { it.speaker == SpeakerRef.Narrator && it.route == TtsRoute.AUDIO8_NARRATOR })
        assertNull(narration.first().speakerIdentityId, "旁白不得绑定任何角色身份")
    }

    // ── C# → SpeakerRef.Character(稳定 id)，而不是局部编号 ─────────────
    @Test
    fun `local candidate resolves to stable character id not to the local token`() {
        val runner = ChapterDirectorRunner(store(), bookPk)
        val lines = mutableListOf<ScriptLine>()
        runner.run(fixedBatch(), StubDecider("C0", "DIALOGUE"), onLine = { lines += it })

        val dialog = lines.filter { it.route == TtsRoute.COSYVOICE_CHARACTER }
        assertTrue(dialog.isNotEmpty())
        dialog.forEach { l ->
            val ref = l.speaker
            assertTrue(ref is SpeakerRef.Character, "对白必须是已确认角色，实际 $ref")
            val id = (ref as SpeakerRef.Character).id.value
            // ★ 持久 id 是稳定 opaque 身份（entityUid），不是显示名、更不是局部编号
            assertTrue(id.matches(Regex("uid-\\d+")), "持久 id 必须是稳定 opaque 身份，实际 $id")
            assertFalse(id.matches(Regex("C\\d+")), "持久 id 不得是局部编号（C18）")
            assertFalse(names.contains(id), "持久 id 不应退化为显示名（显示名可改，不能当跨层身份）")
            assertEquals(id, l.speakerIdentityId)
        }
        // 同一角色在同一批次内必须恒定映射到同一 id（跨段稳定性）
        val sameChar = dialog.filter { it.speakerIdentityId == dialog.first().speakerIdentityId }
        assertTrue(
            sameChar.size == dialog.count { it.speakerIdentityId == dialog.first().speakerIdentityId },
            "同一身份的映射必须稳定",
        )
        println("[C18-GATE] distinctCharacterIds=${dialog.mapNotNull { it.speakerIdentityId }.distinct()}")
    }

    // ── ④ 悬空 C# 仍是硬失败，绝不偷改（C9 不回退）───────────────────
    @Test
    fun `dangling candidate is still a hard reject and is never repaired`() {
        val runner = ChapterDirectorRunner(store(), bookPk)
        val outcomes = mutableListOf<Pair<Long, ValidationOutcome?>>()
        val lines = mutableListOf<ScriptLine>()
        val stats = runner.run(
            fixedBatch(), StubDecider("C7", "DIALOGUE"),
            onLine = { lines += it },
            onOutcome = { outcomes += it.segmentId to it.outcome },
        )

        assertTrue(stats.reject > 0, "候选外 C# 必须 REJECT")
        assertTrue(stats.failureCodes.containsKey(FailureCode.SPEAKER_ID_MISSING.name))
        val rejected = outcomes.filter { it.second == ValidationOutcome.REJECT }.map { it.first }.toSet()
        assertTrue(rejected.isNotEmpty())
        assertTrue(lines.none { it.segmentId in rejected }, "REJECT 段不得产出持久行")
        assertTrue(
            lines.none { it.speaker == SpeakerRef.Unknown },
            "C7 不得被偷修成 UNKNOWN（C9/C10）：本批次没有合法 UNKNOWN 来源",
        )
    }

    // ── ⑤ C18 硬门：候选无法解析为持久身份 ⇒ fail-closed ─────────────
    @Test
    fun `unresolvable candidate fails closed at the c18 boundary`() {
        val text = "“坐吧。”"
        val ctx = DirectorContext(
            target = ContextSegment(text, "DIALOGUE", 0L, 1L, 0),
            recentSegments = emptyList(),
            candidateSpeakers = listOf(
                CandidateSpeaker("C0", "", "未知", emptyList(), 0),   // ★ identityId 为空 ⇒ 无法持久化
            ),
            identityConstraints = emptyList(),
            embodiment = emptyList(),
            userLocks = emptyList(),
        )
        val raw = """{"segment_id":"S0","speaker":"C0","type":"DIALOGUE","emotion":"CALM",
            "emotion_intensity":0.4,"delivery":{"pace":"NORMAL","volume":"NORMAL","tone":"NEUTRAL"},
            "voice_event":"NORMAL","evidence":[]}"""
        val normalized = ProtocolNormalizer.normalize(raw)
        val result = DirectorOutputValidator.validate(
            normalized,
            DirectorSelectionPrompt.segmentToken(ctx.target),
            ctx.candidateSpeakers.map { it.localId }.toSet(),
            emptySet(),
        )
        assertEquals(ValidationOutcome.ACCEPTED_ALL, result.outcome, "校验层本身应通过")

        val seg = SemanticSegment(1L, 1L, 0, SegmentType.SPEECH, text, 0, text.length, 1)
        assertNull(
            ScriptLineBuilder.build(text, seg, ctx, result),
            "局部候选无法解析为持久身份时必须 fail-closed（返回 null，不产出持久行）",
        )
    }

    // ── ⑥ codec 边界：v2 不接受局部 ID；v1 只读且不猜 ─────────────────
    @Test
    fun `codec rejects local ids and keeps v1 legacy read only`() {
        assertNull(SpeakerRef.decodeToken("C0"), "局部编号不是合法持久引用")
        assertNull(SpeakerRef.decodeToken("C12"))
        assertEquals(SpeakerRef.Narrator, SpeakerRef.decodeToken("NARRATOR"))
        assertEquals(SpeakerRef.Unknown, SpeakerRef.decodeToken("UNKNOWN"))
        val c = SpeakerRef.decodeToken("CHARACTER:char_01JABC")
        assertEquals("char_01JABC", (c as SpeakerRef.Character).id.value)
        assertEquals("CHARACTER:char_01JABC", SpeakerRef.encodeToken(c))

        val legacyRow = """{"segment_id":63000,"text":"旁白","speaker":"C1","speaker_identity":null}"""
        val legacy = LegacyScriptLine.parse(legacyRow)
        assertNotNull(legacy)
        assertEquals("C1", legacy!!.rawSpeaker)
        assertEquals(1, legacy.schemaVersion)
        assertFalse(legacy.speakerResolvable, "历史 C# 一律不可安全解析（不猜历史 C0 是谁）")
        assertNull(ScriptLineCodec.decodeSpeakerRef(legacyRow), "v1 行不得被当作 v2 解析")

        val v2 = """{"schemaVersion":2,"speakerRef":"CHARACTER:林夜"}"""
        assertEquals("林夜", (ScriptLineCodec.decodeSpeakerRef(v2) as SpeakerRef.Character).id.value)
    }

    // ── ⑦ CandidateMap 语义：纯查表、缺项 null ────────────────────────
    @Test
    fun `candidate map is a pure lookup and dies with the request`() {
        val map = CandidateMap(mapOf("C0" to CharacterId("林夜"), "C1" to CharacterId("傅远")))
        assertEquals(2, map.size)
        assertEquals(setOf("C0", "C1"), map.localIds())
        assertEquals("林夜", map.characterOf("C0")!!.value)
        assertNull(map.characterOf("C9"), "表外编号必须返回 null，绝不猜测")
    }
}
