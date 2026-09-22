package com.readervoice.data.m0

import com.readervoice.data.Db
import com.readervoice.data.character.CharacterStore
import com.readervoice.data.character.SchemaMigrationV2
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.DirectorSelectionPrompt
import com.readervoice.data.semantic.GoldPolicy
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegment
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M0：从**真实书**产出 selection-style 协议素材（S/C/E-ID 选择题）。
 *
 * 目的：回答"当前 readerdirector-mnn-v2 是否能够稳定执行 selection-style 协议"，
 * 而不是"哪种协议分数高"。因此本生成器刻意【不产生任何需要模型写文本的字段】：
 *   - segments 里给出 S0..Sn 与它们的原文（原文来自 parser/segmenter，是既有事实）
 *   - candidates 由 SpeakerCandidateCompiler/ContextBuilder 产出，编号 C0..Cn（确定性）
 *   - evidence 由 RuleSpeakerBaseline 的 provenance 产出，编号 E0..Em（确定性）
 *   - gold 记录 rule baseline 的判定与其 isEasy 标记（用于诚实报告准确率）
 *
 * 产物：runs/mobile_005_director_real/m0_protocol_probe/
 *   fixture.jsonl   每行一个目标 segment 的完整素材（含 gold/来源，供判分）
 *   prompt_XX.txt   渲染好的选择题 prompt（交给设备侧 llm_demo / App 用同一文本）
 *   stats.txt       素材统计（段落数/segment 数/目标数/候选分布/候选召回）
 */
class M0SelectionFixtureTest {

    private val book = File("../娱乐：从1990年开始 作者：咖啡香草.txt")
    private val out = File("../runs/mobile_005_director_real/m0_protocol_probe")
    private val targets = 25
    private val maxParagraphs = 4000
    private val windowSize = 8
    private val skipFrontMatter = 60
    private val minCandidates = 2
    private val maxEntities = 80

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!
    )

    private fun newDb(): Db {
        val db = Db(createTempDirectory("rv-m0-").resolve("t.db").toString())
        db.createSchema()
        SchemaMigrationV2(db).migrate()
        return db
    }

    @Test
    fun `build selection style fixture from real book`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(book.exists(), "real book fixture not present")
        out.mkdirs()

        // ── 1. 真实书 → PhysicalLine → Chapter → LogicalParagraph ──
        val bytes = book.readBytes()
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, "b", false).lines
        val struct = ChapterStructureCompiler(legacyRules()).compile(lines, "book")
        val paraResult = ParagraphRecoveryPipeline().run(lines, "book", emptySet(), emptySet(), emptySet(), emptyList())
        var paragraphs = paraResult.paragraphs.map { it.paragraph }
        if (paragraphs.size > maxParagraphs) paragraphs = paragraphs.subList(0, maxParagraphs)
        println("[M0] lines=${lines.size} chapters=${struct.revision.chapters.size} paragraphs=${paragraphs.size}")

        // 诊断：段落到底长什么样（决定 M0 素材是否成立）
        run {
            val lens = paragraphs.map { it.normalizedText.length }.sorted()
            val withQuote = paragraphs.count { it.normalizedText.contains('“') }
            println("[M0-DIAG] len min=${lens.first()} p50=${lens[lens.size / 2]} p90=${lens[(lens.size * 9) / 10]} max=${lens.last()}")
            println("[M0-DIAG] withQuote=$withQuote/${paragraphs.size}")
            println("[M0-DIAG] blockType=" + paragraphs.groupingBy { it.blockType }.eachCount())
            println("[M0-DIAG] readPolicy=" + paragraphs.groupingBy { it.readPolicy }.eachCount())
            for (k in intArrayOf(60, 200, 1000, 3000)) {
                if (k < paragraphs.size) {
                    val p = paragraphs[k]
                    println("[M0-DIAG] p[$k] block=${p.blockType} policy=${p.readPolicy} len=${p.normalizedText.length} quote=${p.normalizedText.contains('“')} text=${p.normalizedText.take(80)}")
                }
            }
        }

        // ── 2. baseline 发现"角色 surface"（= 真实系统里会被写进 store 的实体）──
        // ★ M3：分两轮。第一轮没有实体集 ⇒ 消歧不生效，cue 残留会被"发现"成实体（自污染）；
        //   第二轮带上第一轮的实体集重跑 ⇒ delivery cue 判定与前后缀消歧生效，残留被换成真名。
        //   这模拟生产：生产里 knownEntities 来自角色层，不是来自 cue 本身。
        val segmenter = SemanticSegmenter()
        fun discover(entities: Set<String>): Triple<LinkedHashMap<String, Int>,
            HashMap<Long, List<SemanticSegment>>, HashMap<Long, Map<Int, RuleSpeakerBaseline.SpeakerResult>>> {
            val baseline = RuleSpeakerBaseline(entities)
            val found = LinkedHashMap<String, Int>()
            val segsByPara = HashMap<Long, List<SemanticSegment>>()
            val resByPara = HashMap<Long, Map<Int, RuleSpeakerBaseline.SpeakerResult>>()
            val recent = ArrayDeque<String>()
            var prevCue: String? = null
            for (p in paragraphs) {
                val segs = segmenter.segment(p)
                if (segs.isEmpty()) continue
                val res = baseline.assign(p, segs, recent.toList(), prevCue)
                segsByPara[p.paragraphId] = segs
                resByPara[p.paragraphId] = res
                for (s in segs) {
                    val r = res[s.segmentIndex]
                    if (r?.speaker != null && r.status != "UNKNOWN") {
                        val sp = r.speaker!!
                        // ★ 发现阶段模拟"角色层"：只收【干净名】，不收带引语介词/动作残留的表面。
                        //   否则实体集里会同时存在 "对闫呢" 与 "闫呢"，消歧会被"整块已是实体"短路（自洽循环）。
                        val variants = RuleSpeakerBaseline.candidateNameVariants(sp)
                        if (variants.size == 1) {
                            found.merge(sp, 1, Int::plus)          // 干净名：直接收
                        } else {
                            variants.drop(1).forEach { v -> found.merge(v, 1, Int::plus) }  // 带残留：只收剥离后的真名
                        }
                        recent.remove(sp); recent.addFirst(sp)
                    }
                }
                while (recent.size > windowSize * 2) recent.removeLast()
                prevCue = baseline.extractTrailingCue(p.normalizedText)
            }
            return Triple(found, segsByPara, resByPara)
        }
        val round1 = discover(emptySet())
        val baseline = RuleSpeakerBaseline(round1.first.keys)
        val round2 = discover(round1.first.keys)
        val discovered = round2.first
        val paraSegs = round2.second
        val paraRes = round2.third
        println("[M0] discovered surfaces: round1=${round1.first.size} → round2=${discovered.size}（消歧生效）")

        // ── 3. 建 store（实体 = 上面发现的 surface；与生产同构：store 决定候选能不能被召回）──
        val db = newDb()
        val store = CharacterStore(db)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b-m0','2026-09-16')")
        val bookPk = 1L
        val entityTop = discovered.entries.sortedByDescending { it.value }.take(maxEntities)
        for ((name, _) in entityTop) store.upsertEntity(bookPk, "e-$name", "PERSON", name, "CONFIRMED")
        println("[M0] store entities=${entityTop.size} (discovered=${discovered.size})")

        // ── 4. 第二遍：逐段构建 DirectorContext（含候选），产出目标 segment 的素材 ──
        val ctxBuilder = ContextBuilder(store)
        val window = ArrayDeque<ContextBuilder.WindowParagraph>()
        val fixturesRaw = mutableListOf<JSONObject>()
        val ctxByKey = HashMap<String, com.readervoice.data.semantic.DirectorContext>()
        var speechSegs = 0
        var candHit = 0
        var easyTargets = 0
        var skippedFewCandidates = 0
        var skippedNoRuleSpeaker = 0

        outer@ for ((pi, p) in paragraphs.withIndex()) {
            val segs = paraSegs[p.paragraphId] ?: continue
            val res = paraRes[p.paragraphId] ?: continue
            for (seg in segs) {
                if (seg.type != SegmentType.SPEECH && seg.type != SegmentType.INNER_MONOLOGUE) continue
                if (pi < skipFrontMatter) continue
                speechSegs++
                if (fixturesRaw.size >= targets * 12) continue
                val sr = res[seg.segmentIndex]
                // M0 素材要求：目标有 rule 判定的说话人（= 有"标准答案"可判分）
                if (sr?.speaker == null) { skippedNoRuleSpeaker++; continue }
                val ctx = ctxBuilder.build(bookPk, p, segs, res, window.toList(), p.paragraphId, windowSize, emptyList())

                // ── 候选：用【生产候选】（compiler 产出），加每样本确定性 permutation ──
                // 与 docs/DECISIONS.md 冻结设计一致：模型只看局部 ID（C0/C1…，每样本随机 permutation），
                // 避免"参考说话人恒在 C0"的位置偏差，也避免暴露持久 entity ID。
                val ruleSpeaker = sr.speaker!!
                val ruleLabel = GoldPolicy.fromRuleResult(sr)
                // ★ M3 步骤 2：局部 ID（C0…）与每样本 permutation 已在 ContextBuilder 内落地，
                //   消费端不再自行编号 —— 否则两端/两处实现会漂移。
                val pipelineCands = ctx.candidateSpeakers
                val pipelineHit = pipelineCands.any { it.name == ruleSpeaker }
                val candArr = JSONArray()
                var ruleC = ""
                pipelineCands.forEach { c ->
                    if (c.name == ruleSpeaker) ruleC = c.localId
                    candArr.put(
                        JSONObject().put("id", c.localId).put("name", c.name)
                            .put("recent_turn_distance", c.recentTurnDistance)
                            .put("sources", JSONArray(c.sources))
                    )
                }
                if (pipelineHit) candHit++

                // ── 证据编号：直接用 ContextBuilder 的 evidenceItems（含 DELIVERY_CUE + emotion_hint）──
                //    ★ M3：不再是"rule provenance 拼串"，而是结构化证据（与 M2/M3 同一来源）
                val evArr = JSONArray()
                val evIds = JSONArray()
                ctx.evidenceItems.forEach { e ->
                    evArr.put(
                        JSONObject().put("id", e.id).put("kind", e.kind).put("text", e.text)
                            .put("emotion_hint", e.emotionHint ?: JSONObject.NULL)
                    )
                    evIds.put(e.id)
                }
                if (sr.isEasy) easyTargets++

                // S 编号：当前段落的 segments，按 segmentIndex 顺序
                val segArr = JSONArray()
                for (s in segs) {
                    segArr.put(
                        JSONObject()
                            .put("id", "S${s.segmentIndex}")
                            .put("type", s.type.name.lowercase())
                            .put("text", s.text)
                    )
                }

                val rec = JSONObject()
                    .put("target_segment_id", "S${seg.segmentIndex}")
                    .put("paragraph_id", p.paragraphId)
                    .put("segment_index", seg.segmentIndex)
                    .put("segment_type", seg.type.name.lowercase())
                    .put("text", seg.text)
                    .put("source_start", seg.sourceStart)
                    .put("source_end", seg.sourceEnd)
                    .put("segments", segArr)
                    .put("candidates", candArr)
                    .put("evidence", evArr)
                    .put("evidence_ids", evIds)
                    // ★ M3：规则层输出【不叫 gold】——见 GoldPolicy。只有人工标注才是 MANUAL_GOLD。
                    .put("rule_speaker", ruleSpeaker)
                    .put("rule_candidate", ruleC)
                    .put("rule_status", sr.status)
                    .put("rule_is_easy", sr.isEasy)
                    .put("gold_kind", ruleLabel.kind.name)
                    .put("candidate_origin", "PIPELINE(compiler)+permutation")
                    .put("pipeline_candidates", JSONArray(pipelineCands.map { it.name }))
                    .put("pipeline_recall_hit", pipelineHit)
                    .put("recent_context", JSONArray(ctx.recentSegments.map { it.text }.takeLast(6)))
                fixturesRaw.add(rec)
                ctxByKey["${p.paragraphId}:${seg.segmentIndex}"] = ctx
            }
            window.addLast(ContextBuilder.WindowParagraph(p, res))
            while (window.size > windowSize) window.removeFirst()
            if (fixturesRaw.size >= targets * 12) break@outer
        }

        // 目标挑选：先易（显式 cue = 真 gold）后难、候选多的优先（选择题更有区分度），保持确定性
        val fixtures = fixturesRaw
            .sortedWith(
                compareByDescending<JSONObject> { it.getBoolean("rule_is_easy") }
                    .thenByDescending { it.getJSONArray("candidates").length() }
                    .thenBy { it.getLong("paragraph_id") }
            )
            .take(targets)
        candHit = fixtures.count { it.getBoolean("pipeline_recall_hit") }
        easyTargets = fixtures.count { it.getBoolean("rule_is_easy") }

        // ── 5. 落盘：fixture.jsonl + prompt_XX.txt + stats ──
        val fixtureFile = File(out, "fixture.jsonl")
        fixtureFile.writeText(fixtures.joinToString("\n") { it.toString() })

        fixtures.forEachIndexed { i, rec ->
            File(out, "prompt_%02d.txt".format(i)).writeText(
                DirectorSelectionPrompt.render(
                    ctxByKey["${rec.getLong("paragraph_id")}:${rec.getInt("segment_index")}"]
                        ?: error("missing ctx for prompt $i")
                )
            )
        }

        val stats = buildString {
            appendLine("book=${book.name}")
            appendLine("paragraphs=${paragraphs.size} speech_like_segments=$speechSegs targets=${fixtures.size}")
            appendLine("skip: rule_speaker_null=$skippedNoRuleSpeaker few_candidates=$skippedFewCandidates (front_matter<$skipFrontMatter skipped)")
            appendLine("discovered_surfaces=${discovered.size} store_entities=${entityTop.size}")
            appendLine("candidate_origin=PIPELINE(compiler) + per-sample permutation（与 DECISIONS 冻结设计一致）")
            appendLine("pipeline_candidate_recall(rule speaker in compiler candidates)=$candHit/${fixtures.size}")
            appendLine("easy_targets(rule isEasy)=$easyTargets/${fixtures.size}")
            appendLine("rule_status=" + fixtures.map { it.getString("rule_status") }.groupingBy { it }.eachCount())
            appendLine("candidate_count_per_target=" + fixtures.map { it.getJSONArray("candidates").length() })
            appendLine("evidence_count_per_target=" + fixtures.map { it.getJSONArray("evidence").length() })
        }
        File(out, "stats.txt").writeText(stats)
        println("[M0] $stats")
        assertTrue(fixtures.isNotEmpty(), "no fixture produced")
    }

}






