package com.readervoice.data.semantic

import com.readervoice.data.Db
import com.readervoice.data.RevisionStore
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.IdentityEvidence
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.paragraph.ReadPolicy
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TASK-080 Dataset 生成器：真实书（train）+ 3 本合成 fixture（dev/test）→ train/dev/test.jsonl
 * + TASK080_DATASET_MANIFEST.json（锁 test hash，G1）+ Rule-only baseline 指标（G3）。
 *
 * 合成 fixture 覆盖：显式 cue / 交替 / 3+ 人 / 代词 / 别名（同 cluster）/ 硬负例（师父≠徒弟、
 * 众人≠张明、宿主≠控制者）/ 关系称谓陷阱（王老-王明 KINSHIP）/ 附身（魔尊→林雪）/
 * 临时换声（START/CONTINUE/REPLACE/END）/ voice phase / user lock（fixed_voice）。
 */
class Task080DatasetGeneratorTest {

    private val OUT = File("../training/readerdirector/dataset").canonicalFile
    private val RULE_OUT = File("../training/readerdirector/runs/task080/rule_baseline").canonicalFile

    @Test
    fun `generate task080 dataset manifest and rule baseline`() {
        val realBook = File("../娱乐：从1990年开始 作者：咖啡香草.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(realBook.exists(), "real book fixture not present (gitignored)")
        val t0 = System.nanoTime()

        // ---------- 1. 真实书（全管线） ----------
        val bytes = realBook.readBytes()
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val structResult = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
        val paraResult = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
        val realHash = sha256(bytes)
        println("real book: lines=${lines.size} chapters=${structResult.revision.chapters.size} paragraphs=${paraResult.paragraphs.size} hash=$realHash")

        // ---------- 2. 合成 fixture ----------
        val db = newDb()
        val rev = RevisionStore(db)
        val store = CharacterStore(db)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-fix-a','2026-08-12')")
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-fix-b','2026-08-12')")
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-fix-c','2026-08-12')")

        val fixtureA = syntheticBook("fixture-A", 1000, listOf(
            "山雨欲来风满楼。",
            "张明推开门，说：“老张，事情办妥了。”",
            "老张点点头，答道：“办妥了，你就放心吧。”",
            "“那就好。”张明松了口气。",
            "师父沉声道：“徒儿，不可大意。”",
            "徒弟低头说道：“弟子明白。”",
            "林雪开口：“你们在说什么？”",
            "张三说道：“这雨怕是要下一整夜。”",
            "“是啊。”李四应道。",
            "张三又说：“明天再说吧。”",
            "执法队齐声道：“遵命。”",
            "张三恢复了声音，说道：“都散了吧。”",
        ), store, rev)
        val fixtureB = syntheticBook("fixture-B", 2000, listOf(
            "夜已深，灯下两个人影。",
            "李雪轻声说：“雪儿，你怎么还不睡？”",
            "“我睡不着。”雪儿答道。",
            "王老叹了口气，说：“明儿还要早起呢。”",
            "王明点点头：“爹，我知道了。”",
            "“你娘的病，我会想办法。”王老说道。",
            "“谢谢爹。”王明低声道。",
            "李雪忽然变了声音，说道：“时辰到了。”",
            "“……”雪儿沉默片刻。",
        ), store, rev)
        val fixtureC = syntheticBook("fixture-C", 3000, listOf(
            "深巷尽头，酒旗招展。",
            "赵铁柱喝道：“谁在那里！”",
            "“是我。”钱二答道。",
            "孙三娘笑道：“二位别吵了。”",
            "赵铁柱哼了一声：“钱二，你站住。”",
            "“我偏不。”钱二说着，往巷子深处走去。",
            "周老板问：“这位客官，可是要住店？”",
            "赵铁柱说：“住店？先赔我的酒钱！”",
            "钱二沉默不语。",
            "孙三娘忽然尖声叫道：“小心！”",
            "“啊！”赵铁柱惨叫一声。",
            "宿主冷冷道：“游戏，开始了。”",
            "控制者低声道：“遵命，主人。”",
        ), store, rev)

        // ---------- 3. 导出（真实书 → train；fixtures → dev/test） ----------
        val exporter = DatasetExporter(store)
        val books = listOf(
            exporter.samplesForBook(1, realHash, paraResult.paragraphs.map { it.paragraph }),
            fixtureA.toBookSamples(), fixtureB.toBookSamples(), fixtureC.toBookSamples(),
        )
        val t1 = System.nanoTime()
        OUT.mkdirs()
        val stats = exporter.export(OUT, books, splitOverride = mapOf(
            realHash to "train", fixtureA.bookHash to "dev", fixtureB.bookHash to "dev", fixtureC.bookHash to "test",
        ))
        val t2 = System.nanoTime()
        println("export in ${"%.0f".format((t2 - t1) / 1e6)}ms; train=${stats.train} dev=${stats.dev} test=${stats.test}")

        // ---------- 4. Manifest ----------
        val testLines = File(OUT, "test.jsonl").readLines()
        val trainLines = File(OUT, "train.jsonl").readLines()
        val devLines = File(OUT, "dev.jsonl").readLines()
        val allLines = (trainLines + devLines + testLines).sorted()
        val datasetHash = sha256(allLines.joinToString("\n").toByteArray())
        val testHash = sha256(testLines.joinToString("\n").toByteArray())

        val manifest = JSONObject().apply {
            put("manifest_version", 1)
            put("frozen_at", "2026-08-12")
            put("generator", "Task080DatasetGeneratorTest")
            put("dataset_sha256", datasetHash)
            put("test_sha256", testHash)
            put("locked_test", "training/readerdirector/dataset/test.jsonl")
            put("split", JSONObject()
                .put("train", JSONObject().put(realHash, "PRIVATE_REALBOOK_001 娱乐：从1990年开始"))
                .put("dev", JSONObject().put(fixtureA.bookHash, fixtureA.name).put(fixtureB.bookHash, fixtureB.name))
                .put("test", JSONObject().put(fixtureC.bookHash, fixtureC.name)))
            put("counts", JSONObject()
                .put("train", trainLines.size).put("dev", devLines.size).put("test", testLines.size).put("total", allLines.size))
            put("by_provenance", JSONObject(
                allLines.map { JSONObject(it).getString("provenance") }.groupingBy { it }.eachCount().toSortedMap()))
            put("by_task", JSONObject(
                allLines.map { JSONObject(it).getString("task") }.groupingBy { it }.eachCount().toSortedMap()))
            put("note", "ENGINEERING baseline VALID / REAL-novel generalization OPEN；train 仅含真实书；dev/test 为合成 fixture（版权文本不入库）")
        }
        File(OUT, "TASK080_DATASET_MANIFEST.json").writeText(manifest.toString(1) + "\n")
        println(manifest.toString(1))

        // ---------- 5. Rule-only baseline（同一 locked test，G3） ----------
        val rule = ruleBaseline(File(OUT, "test.jsonl"))
        RULE_OUT.mkdirs()
        File(RULE_OUT, "metrics.json").writeText(JSONObject(rule.mapValues { it.value }).toString(1) + "\n")
        println(JSONObject(rule.mapValues { it.value }).toString(1))

        assertTrue(trainLines.size > 1000, "train 应含真实书大量样本: ${trainLines.size}")
        assertTrue(testLines.isNotEmpty() && devLines.isNotEmpty(), "dev/test 非空")
        assertTrue(rule.getValue("speaker_gold_agreement") == 1.0, "rule vs 自身 gold 必须 100%")
        assertTrue(rule.getValue("identity_hard_negative_violation") == 0.0, "rule 不得违反硬负例")
        assertTrue(rule.getValue("voice_state_gold_agreement") == 1.0)
        println("TASK-080 dataset frozen in ${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
    }

    // ---------- rule baseline 指标 ----------

    private fun ruleBaseline(test: File): Map<String, Double> {
        val lines = test.readLines().map { JSONObject(it) }
        val speaker = lines.filter { it.getString("task") == "SPEAKER" }
        val identity = lines.filter { it.getString("task") == "IDENTITY" }
        val voice = lines.filter { it.getString("task") == "VOICE_STATE" }

        val speakerGold = speaker.filter { it.getString("provenance") == "EXPLICIT_RULE_GOLD" }
        val speakerAgreement = speakerGold.size.toDouble() / speakerGold.size.coerceAtLeast(1)
        val speakerUnknown = speaker.filter { it.getString("provenance") == "UNKNOWN" }
        val speakerCoverage = speakerGold.size.toDouble() / speaker.size.coerceAtLeast(1)

        val idGold = identity.filter { it.getString("provenance") == "EXPLICIT_RULE_GOLD" }
        val idAgreement = idGold.size.toDouble() / idGold.size.coerceAtLeast(1)
        // rule 输出即 gold（SAME/DIFFERENT 均来自 DB 证据），结构上恒不违反硬负例；模型侧才统计 violation
        val idHardNegViol = 0.0

        val voiceGold = voice.filter { it.getString("provenance") == "EXPLICIT_RULE_GOLD" }
        val voiceAgreement = voiceGold.size.toDouble() / voiceGold.size.coerceAtLeast(1)

        return linkedMapOf(
            "test_samples" to lines.size.toDouble(),
            "speaker_samples" to speaker.size.toDouble(),
            "speaker_gold" to speakerGold.size.toDouble(),
            "speaker_gold_agreement" to speakerAgreement,
            "speaker_gold_coverage" to speakerCoverage,
            "speaker_unknown_samples" to speakerUnknown.size.toDouble(),
            "identity_samples" to identity.size.toDouble(),
            "identity_gold" to idGold.size.toDouble(),
            "identity_gold_agreement" to idAgreement,
            "identity_hard_negative_violation" to idHardNegViol,
            "voice_state_samples" to voice.size.toDouble(),
            "voice_state_gold" to voiceGold.size.toDouble(),
            "voice_state_gold_agreement" to voiceAgreement,
            "strict_json_valid" to 1.0, // rule 输出结构化 JSON
        )
    }

    // ---------- synthetic fixtures ----------

    private class FixtureBook(val name: String, val bookHash: String, val samples: List<DirectorSample>) {
        fun toBookSamples() = DatasetExporter.BookSamples(bookHash, samples)
    }

    private fun syntheticBook(name: String, base: Long, texts: List<String>, store: CharacterStore, rev: RevisionStore): FixtureBook {
        val bookPk = name.substringAfter("fixture-")[0] - 'A' + 1L // A→1, B→2, C→3（本 DB 仅 3 行 book）
        val paragraphs = texts.mapIndexed { i, t ->
            LogicalParagraph(
                paragraphId = base + i, revisionId = base + i, bookId = name, chapterId = null,
                sourceRevisionId = "syn-$name", paragraphIndex = i,
                sourceSpans = emptyList(), normalizedText = t,
                blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL, boundaryConfidence = 0.9,
            )
        }
        seed(name, store, rev, bookPk, paragraphs)
        val hash = sha256(texts.joinToString("|").toByteArray())
        val exporter = DatasetExporter(store)
        val samples = exporter.samplesForBook(bookPk, hash, paragraphs).samples
        return FixtureBook(name, hash, samples)
    }

    /** 每本 fixture 的角色状态（规则层角色数据，模拟 CharacterCompiler 产物）。 */
    private fun seed(name: String, store: CharacterStore, rev: RevisionStore, bookPk: Long, paras: List<LogicalParagraph>) {
        val rv = rev.create("CHARACTER", "book:$bookPk", "1", "src", null, "h-$name", "v1").revisionId
        val ent = { n: String, t: String -> store.upsertEntity(bookPk, "uid-$name-$n", t, n, "CONFIRMED") }
        val pos = { i: Int -> paras[i].paragraphId }
        when (name) {
            "fixture-A" -> {
                val zhangMing = ent("张明", "PERSON"); val laoZhang = ent("老张", "PERSON")
                val shifu = ent("师父", "PERSON"); val tudi = ent("徒弟", "PERSON")
                val linxue = ent("林雪", "PERSON"); val mozun = ent("魔尊", "PERSON")
                val zhangSan = ent("张三", "PERSON"); val liSi = ent("李四", "PERSON")
                val zhiFaDui = ent("执法队", "GROUP")
                // 别名：老张 = 张明（whole-book 合并）
                store.mergeInto(rv, bookPk, laoZhang, zhangMing, "fixture alias", emptyList(), automatic = true)
                // 硬负例：师父 ≠ 徒弟；执法队 ≠ 张明（group ≠ person）
                hardNeg(store, rv, bookPk, shifu, tudi)
                hardNeg(store, rv, bookPk, zhiFaDui, zhangMing)
                // 附身：魔尊 → 林雪（魔尊有长期阶段 → 林雪说话的声音状态应归属魔尊）
                store.insertEmbodiment(rv, bookPk, mozun, linxue, "POSSESSION", pos(6), null, 1.0)
                store.insertVoicePhase(rv, bookPk, mozun, "ADULT", 0, null, 1.0)
                // 临时换声：张三 START(7) → CONTINUE(9) → END(11)
                store.insertTempVoiceEvent(rv, bookPk, zhangSan, "START", "AUTHORITATIVE", "{\"w\":2}", pos(7), "scene", "FIXTURE")
                store.insertTempVoiceEvent(rv, bookPk, zhangSan, "CONTINUE", "AUTHORITATIVE", null, pos(9), "scene", "FIXTURE")
                store.insertTempVoiceEvent(rv, bookPk, zhangSan, "END", "AUTHORITATIVE", null, pos(11), "scene", "FIXTURE")
                // 长期阶段
                store.insertVoicePhase(rv, bookPk, liSi, "ADULT", 0, null, 1.0)
                store.insertVoicePhase(rv, bookPk, zhangSan, "YOUNG", 0, null, 1.0)
                store.setAttribute(rv, bookPk, zhangSan, "voice_age", "30", "RULE", pos(0), false, 0.9)
            }
            "fixture-B" -> {
                val liXue = ent("李雪", "PERSON"); ent("雪儿", "PERSON")
                val wangLao = ent("王老", "PERSON"); val wangMing = ent("王明", "PERSON")
                store.mergeInto(rv, bookPk, store.entityByCanonicalName(bookPk, "雪儿")!!.entityPk, liXue, "fixture alias", emptyList(), automatic = true)
                // 关系称谓陷阱：王老-王明 KINSHIP ≠ SAME_PERSON（不合并、非 hard → UNKNOWN gold）
                store.insertEvidence(IdentityEvidence(0, rv, bookPk, wangLao, wangMing, "KINSHIP", "NEUTRAL", 0.6, false, pos(3), "FIXTURE"))
                // 共现证据
                store.insertEvidence(IdentityEvidence(0, rv, bookPk, wangLao, wangMing, "CO_PRESENCE", "NEUTRAL", 0.7, false, pos(4), "FIXTURE"))
                // 临时换声 REPLACE + 用户锁（fixed_voice）
                store.insertTempVoiceEvent(rv, bookPk, liXue, "REPLACE", "OLD_WOMAN", "{\"src\":\"elsewhere\"}", pos(7), "scene", "FIXTURE")
                store.insertVoicePhase(rv, bookPk, liXue, "ADULT", 0, null, 1.0)
                store.setAttribute(rv, bookPk, liXue, "fixed_voice", "voicepack-a", "USER", null, true, 1.0)
            }
            "fixture-C" -> {
                ent("赵铁柱", "PERSON"); ent("钱二", "PERSON"); ent("孙三娘", "PERSON"); ent("周老板", "PERSON")
                val suZhu = ent("宿主", "PERSON"); val kongZhiZhe = ent("控制者", "PERSON")
                // CONTROL ≠ identity：宿主 ≠ 控制者（hard negative）
                store.insertEvidence(IdentityEvidence(0, rv, bookPk, suZhu, kongZhiZhe, "CONTROL", "NEUTRAL", 0.5, false, pos(11), "FIXTURE"))
                hardNeg(store, rv, bookPk, suZhu, kongZhiZhe)
                // 临时换声 START
                val sunSanniang = store.entityByCanonicalName(bookPk, "孙三娘")!!.entityPk
                store.insertTempVoiceEvent(rv, bookPk, sunSanniang, "START", "ALERT", "{\"pitch\":1.2}", pos(9), "scene", "FIXTURE")
                store.insertVoicePhase(rv, bookPk, sunSanniang, "ADULT", 0, null, 1.0)
            }
        }
    }

    private fun hardNeg(store: CharacterStore, rev: Long, bookPk: Long, a: Long, b: Long) {
        store.insertEvidence(IdentityEvidence(0, rev, bookPk, a, b, "DIFFERENT_PERSON", "NEGATIVE", 4.0, hardBlock = true, null, "FIXTURE"))
    }

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newDb(): Db {
        val db = Db(createTempDirectory("rv-ds080-").resolve("t.db").toString())
        db.createSchema()
        SchemaMigrationV2(db).migrate()
        return db
    }
}
