package com.readervoice.data.semantic

import com.readervoice.data.Db
import com.readervoice.data.RevisionStore
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.IdentityEvidence
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ReadPolicy
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-070 Gate 测试（G1-G12）：
 * 1. SemanticSegmenter（G1 round-trip / G2 旁白+对白+旁白）
 * 2. RuleSpeakerBaseline（G3 显式 cue→Easy Gold / G4 跨段 cue / G5 UNKNOWN）
 * 3. NarrationIRBuilder（G7 embodiment / G8 causal voice / G9 identity future evidence）
 * 4. ContextBuilder（G6 candidate restriction）
 * 5. DatasetExporter（G10 provenance / G11 book-level split / G12 model-ready JSONL）
 */
class Task070GateTest {

    private val seg = SemanticSegmenter()
    private val baseline = RuleSpeakerBaseline()

    private fun para(id: Long, text: String) = LogicalParagraph(
        paragraphId = id, revisionId = id, bookId = "b1", chapterId = null,
        sourceRevisionId = "s1", paragraphIndex = id.toInt(),
        sourceSpans = emptyList(), normalizedText = text,
        blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL, boundaryConfidence = 0.9,
    )

    private fun speechSegs(p: LogicalParagraph) = seg.segment(p).filter { it.type == SegmentType.SPEECH }

    // ---------- G1 / G2 ----------

    @Test
    fun `G1 round-trip text and offsets are exact`() {
        val cases = listOf(
            "张三推开门，说：“你来了。”屋外下着雨。",
            "张明推开门，说：“你来了。”李雪抬头：“嗯。”",
            "“我不信。”李四摇了摇头。",
            "他心想：“这下完了。”",
            "纯旁白段落，没有任何引号。",
        )
        for (c in cases) {
            val p = para(10, c)
            val segs = seg.segment(p)
            assertTrue(segs.isNotEmpty(), "空段列表: $c")
            // 拼接 = 原文（G1 主判据）
            assertEquals(c, segs.joinToString("") { it.text }, "round-trip 拼接不等: $c")
            // 偏移连续且精确
            for ((i, s) in segs.withIndex()) {
                assertEquals(c.substring(s.sourceStart, s.sourceEnd), s.text, "偏移错位: $c seg$i")
                if (i > 0) assertEquals(segs[i - 1].sourceEnd, s.sourceStart, "偏移不连续: $c seg$i")
            }
            // segmentId 确定性
            assertEquals(p.paragraphId * 1000, segs.first().segmentId)
        }
    }

    @Test
    fun `G2 narration dialogue narration splits 100 percent`() {
        val p = para(1, "张三推开门，说：“你来了。”屋外下着雨。")
        val segs = seg.segment(p)
        assertEquals(3, segs.size)
        assertEquals(SegmentType.NARRATION, segs[0].type)
        assertEquals("张三推开门，说：", segs[0].text)
        assertEquals(SegmentType.SPEECH, segs[1].type)
        assertEquals("“你来了。”", segs[1].text) // 段文本=原文精确切片（含引号，source-exact）
        assertEquals(SegmentType.NARRATION, segs[2].type)
        assertEquals("屋外下着雨。", segs[2].text)
    }

    @Test
    fun `G2b manual section29 four segments`() {
        val p = para(2, "张明推开门，说：“你来了。”李雪抬头：“嗯。”")
        val segs = seg.segment(p)
        assertEquals(4, segs.size)
        assertEquals(listOf(SegmentType.NARRATION, SegmentType.SPEECH, SegmentType.NARRATION, SegmentType.SPEECH), segs.map { it.type })
        // 段文本 = 原文精确切片（含引号，source-exact）
        assertEquals(listOf("张明推开门，说：", "“你来了。”", "李雪抬头：", "“嗯。”"), segs.map { it.text })
    }

    @Test
    fun `G2c inner monologue heuristic`() {
        val p = para(3, "他心想：“这下完了。”")
        val segs = seg.segment(p)
        assertEquals(2, segs.size)
        assertEquals(SegmentType.INNER_MONOLOGUE, segs[1].type)
    }

    // ---------- G3 / G4 / G5 ----------

    @Test
    fun `G3 explicit cue yields easy gold`() {
        val p = para(4, "张三说道：“你来了。”")
        val segs = seg.segment(p)
        val r = baseline.assign(p, segs, emptyList())
        val res = r[segs[1].segmentIndex]
        assertNotNull(res)
        assertEquals("张三", res.speaker)
        assertEquals("CONFIRMED", res.status)
        assertTrue(res.isEasy, "显式 cue 必须是 Easy Gold")
        assertEquals(listOf("EXPLICIT_SPEECH_CUE"), res.provenance)
    }

    @Test
    fun `G4 cross paragraph speech cue links`() {
        // 上段末尾 cue
        assertEquals("张三", baseline.extractTrailingCue("张三说道："))
        assertEquals("李四", baseline.extractTrailingCue("李四开口说"))
        assertNull(baseline.extractTrailingCue("他推开门走了出去。"))
        // 下段对白被链接
        val prev = para(5, "张三说道：")
        val next = para(6, "“你来了。”")
        val segs = seg.segment(next)
        val r = baseline.assign(next, segs, emptyList(), baseline.extractTrailingCue(prev.normalizedText))
        val res = r[segs[0].segmentIndex]
        assertNotNull(res)
        assertEquals("张三", res.speaker)
        assertEquals("CONFIRMED", res.status)
        assertEquals(listOf("CROSS_PARAGRAPH_CUE"), res.provenance)
        assertTrue(res.isEasy)
    }

    @Test
    fun `G5 unknown never guesses`() {
        val p = para(7, "“你来了。”")
        val segs = seg.segment(p)
        val r = baseline.assign(p, segs, emptyList()) // 无 cue、无历史
        val res = r[segs[0].segmentIndex]
        assertNotNull(res)
        assertNull(res.speaker, "无证据时不得猜说话人")
        assertEquals("UNKNOWN", res.status)
        assertFalse(res.isEasy)
    }

    // ---------- TASK-100 候选污染回归（cue 副词切片不得成为说话人） ----------

    @Test
    fun `T100 cue adverb fragments never become speaker`() {
        // "他还是劝道"→"他还是劝"、"她当即说道"→"她当即" 类切片曾是说话人（污染候选），修复后必须 UNKNOWN
        val cases = listOf(
            "他还是劝道：“别去了。”",
            "她当即说道：“快走！”",
            "他继续道：“听着。”",
            "她好奇的问：“这是谁？”",
            "他淡淡的道：“随你。”",
            "她还是道：“走吧。”",
            "她只能道：“好吧。”",
        )
        for ((i, text) in cases.withIndex()) {
            val p = para(20L + i, text)
            val segs = seg.segment(p)
            val r = baseline.assign(p, segs, emptyList())
            val res = r[segs[1].segmentIndex]
            assertNotNull(res, "case: $text")
            assertNull(res.speaker, "cue 副词切片不得成为说话人: $text -> ${res.speaker}")
            assertEquals("UNKNOWN", res.status, "case: $text")
        }
        // 真名 cue 必须仍然工作
        val ok = baseline.assign(para(40, "张三说道：“你来了。”"), seg.segment(para(40, "张三说道：“你来了。”")), emptyList())
        assertEquals("张三", ok[1]?.speaker)
    }

    // ---------- G6 ----------

    @Test
    fun `G6 candidates restricted to recent window`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val bookPk = 1L
        // 书中存在很多实体（不该出现在候选）
        for (n in listOf("张三", "李四", "王五", "赵六", "钱七", "孙八")) store.upsertEntity(bookPk, "uid-$n", "PERSON", n, "CANDIDATE")
        val ctxBuilder = ContextBuilder(store)

        val window = listOf(
            ContextBuilder.WindowParagraph(para(20, "张三说道：“一。”"), mapOf(0 to baselineResult("张三"))),
            ContextBuilder.WindowParagraph(para(21, "李四答道：“二。”"), mapOf(0 to baselineResult("李四"))),
        )
        val cur = para(22, "“三。”")
        val segs = seg.segment(cur)
        val results = baseline.assign(cur, segs, listOf("李四", "张三"))
        val ctx = ctxBuilder.build(bookPk, cur, segs, results, window, position = 22)

        val cands = ctx.candidateSpeakers
        assertEquals(2, cands.size, "候选必须限制在窗口内：${cands.map { it.name }}")
        assertEquals(setOf("张三", "李四"), cands.map { it.name }.toSet(), "王五等全书角色不得出现（G6）")
        val liSi = cands.first { it.name == "李四" }
        val zhangSan = cands.first { it.name == "张三" }
        assertTrue(liSi.recentTurnDistance < zhangSan.recentTurnDistance, "李四更近")
        assertTrue(ctx.recentSegments.isNotEmpty())
        // ADR-055：约束改用【局部 ID】（模型不得看到内部身份）；
        // 内部身份仍可经 candidates 映射，用于回填与审计。
        assertTrue(
            ctx.identityConstraints.any { it.contains("!=") },
            "必须存在异 identity 约束: ${ctx.identityConstraints}",
        )
        ctx.identityConstraints.forEach { c ->
            assertFalse(c.contains("uid-"), "约束泄漏内部身份: $c")
        }
        assertEquals(
            setOf("uid-张三", "uid-李四"), cands.map { it.identityId }.toSet(),
            "局部 ID 必须仍能映射回内部身份（审计链路）",
        )
    }

    private fun baselineResult(name: String) = RuleSpeakerBaseline.SpeakerResult(
        speaker = name, status = "CONFIRMED", confidence = 0.95, provenance = listOf("EXPLICIT_SPEECH_CUE"), isEasy = true,
    )

    // ---------- G7 / G8 / G9 ----------

    @Test
    fun `G7 embodiment surface and acting identity`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val rv = rev.create("CHARACTER", "book:1", "1", "src", null, "h7", "v1").revisionId
        val mozun = store.upsertEntity(1, "uid-mozun", "PERSON", "魔尊", "CONFIRMED")
        val linxue = store.upsertEntity(1, "uid-linxue", "PERSON", "林雪", "CONFIRMED")
        store.insertEmbodiment(rv, 1, mozun, linxue, "POSSESSION", 0, null, 1.0)
        store.insertVoicePhase(rv, 1, mozun, "ADULT", 0, null, 1.0)

        val p = para(30, "林雪说道：“我要这天下。”")
        val segs = seg.segment(p)
        val results = baseline.assign(p, segs, emptyList())
        val irs = NarrationIRBuilder(store).build(p, segs, results, 1, position = 30)
        val ir = irs[1]
        assertNotNull(ir.speaker)
        assertEquals("uid-linxue", ir.surfaceEntityId, "表面实体=林雪")
        assertEquals("uid-mozun", ir.actingIdentityId, "附身者=魔尊（G7）")
        assertNotNull(ir.voiceStateRef, "声音应归属附身者（phase=ADULT）")
        assertTrue(ir.voiceStateRef!!.contains("phase=ADULT"))
    }

    @Test
    fun `G8 voice state is causal only`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val rv = rev.create("CHARACTER", "book:1", "1", "src", null, "h8", "v1").revisionId
        val zs = store.upsertEntity(1, "uid-zs", "PERSON", "张三", "CONFIRMED")
        store.insertTempVoiceEvent(rv, 1, zs, "START", "AUTHORITATIVE", "{\"w\":1}", 100, "scene", "TEST")
        store.setAttribute(rv, 1, zs, "voice_age", "45", "RULE", 90, false, 0.9)

        val builder = NarrationIRBuilder(store)
        val before = buildIrs(builder, 40, "张三说：“早。”", position = 50)
        assertNull(before[1].voiceStateRef, "position=50 看不到 position=90/100 的事件（G8 causal）")
        val after = buildIrs(builder, 41, "张三说：“晚。”", position = 150)
        val ref = after[1].voiceStateRef
        assertNotNull(ref)
        assertTrue(ref.contains("temp=START"), "temp 事件应进入摘要: $ref")
        assertTrue(ref.contains("age=45"))
    }

    private fun buildIrs(b: NarrationIRBuilder, id: Long, text: String, position: Long): List<NarrationIR> {
        val p = para(id, text)
        val segs = seg.segment(p)
        val results = baseline.assign(p, segs, emptyList())
        return b.build(p, segs, results, 1, position)
    }

    @Test
    fun `G9 identity future evidence via whole-book resolve`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val rv = rev.create("CHARACTER", "book:1", "1", "src", null, "h9", "v1").revisionId
        val wang = store.upsertEntity(1, "uid-wang", "PERSON", "王老", "CANDIDATE")
        val oldWang = store.upsertEntity(1, "uid-oldwang", "PERSON", "老王头", "CANDIDATE")
        // 证据出现在 position 500（远在当前段之后）——whole-book 仍应合并（G9）
        store.insertEvidence(IdentityEvidence(
            evidenceId = 0, characterRevisionId = rv, bookPk = 1,
            entityAPk = wang, entityBPk = oldWang,
            relationType = "SAME_PERSON", sign = "POSITIVE", strength = 2.0, hardBlock = false,
            narrativePosition = 500, provenance = "TEST", legacyWeight = 0.0,
        ))
        assertTrue(store.mergeCandidate(1, wang, oldWang, 1.5), "强度足够应可合并")
        store.mergeInto(rv, 1, oldWang, wang, "G9 test merge", emptyList(), automatic = true)
        // 当前段 position=10：causal 查询看不到证据，但 resolveIdentity（whole-book）能看到
        val p = para(50, "王老说道：“成了。”")
        val segs = seg.segment(p)
        val results = baseline.assign(p, segs, emptyList())
        val irs = NarrationIRBuilder(store).build(p, segs, results, 1, position = 10)
        val ir = irs[1]
        assertNotNull(ir.speaker)
        assertTrue(ir.speaker.identityId.startsWith("cluster"), "whole-book 应解析到 cluster: ${ir.speaker.identityId}")
    }

    // ---------- G10 / G11 / G12 ----------

    @Test
    fun `G10 provenance legacy never gold`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val exporter = DatasetExporter(store)
        val paras = listOf(
            para(60, "张三说道：“你好。”"),
            para(61, "“在吗？”"),
        )
        val legacy = mapOf(
            "paragraph/60/segment/1" to mapOf("speaker" to "某角色", "harness" to false),
        )
        val book = exporter.samplesForBook(1, "hashA", paras, legacyRecords = legacy)
        val byRef = book.samples.groupBy { it.input["text_ref"] as String }

        // 有 legacy 记录的样本
        val legacySamples = byRef.getValue("paragraph/60/segment/1")
        assertEquals("LEGACY_V907", legacySamples.first { it.task == "SPEAKER" }.provenance)
        assertTrue(legacySamples.first { it.task == "SPEAKER" }.legacyLabel, "legacy 恒 legacyLabel（G10）")
        val legacySpeaker = legacySamples.first { it.task == "SPEAKER" }
        assertNull(legacySpeaker.target["speaker"], "legacy 样本 target 不得伪装成 gold")
        assertEquals("某角色", (legacySpeaker.target["legacy_output"] as Map<*, *>)["speaker"])

        // 无 legacy 的显式 cue → EXPLICIT_RULE_GOLD
        val goldSamples = byRef.getValue("paragraph/61/segment/0")
        assertEquals("UNKNOWN", goldSamples.first { it.task == "SPEAKER" }.provenance, "无 cue 无 legacy → UNKNOWN")
        // 全部样本 provenance 都在枚举内
        val allowed = setOf("EXPLICIT_RULE_GOLD", "HUMAN_GOLD", "LEGACY_V907", "LEGACY_V907_HARNESS", "TEACHER", "UNKNOWN")
        assertTrue(book.samples.all { it.provenance in allowed })
    }

    @Test
    fun `G11 book level split never splits a book`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val exporter = DatasetExporter(store)
        val books = listOf(
            exporter.samplesForBook(1, "bookA", listOf(para(1, "张三说道：“a。”"), para(2, "“b。”"))),
            exporter.samplesForBook(1, "bookB", listOf(para(1, "李四说道：“c。”"), para(2, "“d。”"))),
            exporter.samplesForBook(1, "bookC", listOf(para(1, "王五说道：“e。”"), para(2, "“f。”"))),
            exporter.samplesForBook(1, "bookD", listOf(para(1, "赵六说道：“g。”"), para(2, "“h。”"))),
        )
        val dir = createTempDirectory("rv-ds-").toFile()
        val stats = exporter.export(dir, books)
        assertTrue(stats.train > 0 && stats.dev > 0 && stats.test > 0, "train=${stats.train} dev=${stats.dev} test=${stats.test}")
        val inFiles = mutableMapOf<String, String>()
        for (f in listOf("train", "dev", "test")) {
            File(dir, "$f.jsonl").readLines().forEach { line ->
                val hash = Regex("\"book_hash\":\"([^\"]+)\"").find(line)!!.groupValues[1]
                inFiles.merge(hash, f) { a, b -> if (a == b) a else "SPLIT:$a,$b" }
            }
        }
        assertEquals(4, inFiles.size, "每本书必须整体在一个 split（G11）: $inFiles")
        assertTrue(inFiles.values.none { it.startsWith("SPLIT") }, "同一本书不得跨 split: $inFiles")
    }

    @Test
    fun `G12 model ready jsonl stable and deterministic`() {
        val db = newDb(); val rev = RevisionStore(db); val store = CharacterStore(db)
        val exporter = DatasetExporter(store)
        val paras = listOf(
            para(70, "张三说道：“你好。”"),
            para(71, "“在吗？”"),
            para(72, "他沉默了。"),
            para(73, "李四说道：“好。”"), // 使窗口 ≥2 说话人 → 成对 IDENTITY 样本
        )
        val book = exporter.samplesForBook(1, "hashG", paras)

        assertEquals(setOf("SPEAKER", "IDENTITY", "VOICE_STATE"), book.samples.map { it.task }.toSet())
        assertEquals(3, book.samples.count { it.task == "SPEAKER" }, "3 句对白 → 3 个 SPEAKER 样本")
        assertTrue(book.samples.any { it.task == "IDENTITY" }, "≥2 候选时应产生成对 IDENTITY 样本")

        val dir1 = createTempDirectory("rv-ds-").toFile()
        val dir2 = createTempDirectory("rv-ds-").toFile()
        exporter.export(dir1, listOf(book))
        exporter.export(dir2, listOf(book))
        assertEquals(
            File(dir1, "train.jsonl").readText(),
            File(dir2, "train.jsonl").readText(),
            "export 必须确定性（G12）",
        )
        // 每行可解析且字段齐全
        for (line in File(dir1, "train.jsonl").readLines()) {
            val o = JSONObject(line)
            assertTrue(o.has("sample_id") && o.has("task") && o.has("book_hash") && o.has("input") && o.has("target") && o.has("provenance") && o.has("legacy_label"))
            val input = o.getJSONObject("input")
            for (k in listOf("text", "segment_type", "text_ref")) {
                assertTrue(input.has(k), "input 缺字段 $k")
            }
            if (o.getString("task") == "SPEAKER") {
                for (k in listOf("recent_context", "scene_roles", "rule_candidates", "identity_constraints", "embodiment", "overrides")) {
                    assertTrue(input.has(k), "SPEAKER input 缺字段 $k")
                }
            }
            assertEquals(16, o.getString("sample_id").length)
        }
    }

    // ---------- helpers ----------

    private fun newDb(): Db {
        val db = Db(createTempDirectory("rv-sem-").resolve("t.db").toString())
        db.createSchema()
        SchemaMigrationV2(db).migrate()
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b1','2026-08-12')")
        return db
    }
}
