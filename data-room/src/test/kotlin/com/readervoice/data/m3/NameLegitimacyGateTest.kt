package com.readervoice.data.m3

import com.readervoice.data.semantic.ChapterDirectorRunner
import com.readervoice.data.semantic.CharacterDiscovery
import com.readervoice.data.semantic.ContextBuilder
import com.readervoice.data.semantic.DirectorDecisionV2
import com.readervoice.data.semantic.Delivery
import com.readervoice.data.semantic.NameLegitimacy
import com.readervoice.data.semantic.NameReject
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.ScriptLine
import com.readervoice.data.semantic.ScriptLineCodec
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.semantic.SpeakerCandidateCompiler
import com.readervoice.data.semantic.SpeakerRef
import com.readervoice.data.semantic.SurfaceEvidence
import com.readervoice.data.snapshot.BootstrapCharacterStore
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.ConfirmedChapterView
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * MOBILE-005 / CH-0 质量修复 ①：**候选人名合法性闸门 Gate**。
 *
 * ## 被这个 Gate 钉住的事故（真机实测）
 *
 * 设备上 `characters_book-d22a7b3878ee.json` 有 371 个"角色名"，其中约 80% 是 cue 子句切片
 * （`李寄舟询`/`张三丰知`/`倘若`/`招呼`/`哈哈`…）。20 段真实剧本里 **9 段的说话人不是人**
 * （`哈哈` ×6、`招呼` ×3）—— 而协议指标（anchor/coverage/ACCEPTED_ALL）**全绿**。
 *
 * ## Gate 的三个层次
 *
 * 1. **离线量化**（`tools/mobile005/name_gate_probe.js`）：371 候选 + 73 手工标注真名
 *    → 闸门保留 73/73 真名、拦下 290/298 污染。
 * 2. **本测试的真实书发现闸门**：同一份真机源文件跑 `CharacterDiscovery`，
 *    断言"污染表面全部被拦 + 标注真名一个不少"。
 * 3. **产品路径端到端**：同一批段落，**未过闸门的实体集**会让 `哈哈/招呼` 进入候选
 *    （= 复现事故）；**过闸门后**它们不再进入候选，也不再出现在任何 ScriptLine 的说话人上。
 *    第 3 层是"改动必须在产品路径可见"的强制断言（不是只测一个 gate 函数）。
 */
class NameLegitimacyGateTest {

    private val dir = File("../runs/mobile_005_director_real/candidate_gate")
    private val source = File(dir, "source.txt")
    /** 真机 discovery 缓存（**修复前**，未过闸门）——用于复现事故。 */
    private val deviceCache = File(dir, "characters_cache.json")
    private val labels = File(dir, "labels_candidate.json")

    private companion object {
        const val DEVICE_BOOK_ID = "book-d22a7b3878ee"

        /** 真机剧本 `scripts/13434/` 的章 id（M0.4 内容派生）。 */
        const val DEVICE_CHAPTER_ID = 13434L
    }

    // ── 1. 封闭类否决（与语料无关）────────────────────────────────────

    @Test
    fun `closed class rejections are absolute and do not touch real names`() {
        // 叹词/拟声：任何情况下都不是人名（先于实体集安全阀）
        for (x in listOf("哈哈", "呵呵", "哎呀", "唉", "哼")) {
            assertEquals(NameReject.INTERJECTION, NameLegitimacy.closedClassReject(x), x)
            assertTrue(isInterjectionCue(x), "叹词应被判为 delivery cue: $x")
        }
        // 虚词/副词
        for (x in listOf("倘若", "因为", "所以", "只是", "同时", "招呼", "不知", "忍不住")) {
            assertEquals(NameReject.FUNCTION_WORD, NameLegitimacy.closedClassReject(x), x)
        }
        // 介词/指示词起首
        assertEquals(NameReject.PREP_PREFIX, NameLegitimacy.closedClassReject("为李寄舟"))
        assertEquals(NameReject.DEMON_PREFIX, NameLegitimacy.closedClassReject("这位公子"))
        // 虚词前缀（双虚词拼接的 cue 切片；形态与语料证据都抓不住）
        assertEquals(
            NameReject.FUNCTION_WORD,
            NameLegitimacy.evaluate("虽然不知", SurfaceEvidence(22, 19, 4, 4), emptySet()).reject,
        )
        // 真名/角色称谓不受影响
        for (x in listOf("李寄舟", "张三丰", "空闻大师", "侯希白", "莫将", "弘心首座", "圣姑")) {
            assertNull(NameLegitimacy.closedClassReject(x), "真名被封闭类误伤: $x")
        }
    }

    private fun isInterjectionCue(x: String) =
        com.readervoice.data.semantic.DeliveryCueLexicon.classify(x, emptySet())?.matchedBy == "INTERJECTION"

    // ── 2. 语料证据分离度（合成语料，不依赖真机文件）──────────────────

    @Test
    fun `corpus evidence separates cue slices from names`() {
        // 真名"李寄舟"出现在多种非 cue 上下文；cue 切片"李寄舟询"只出现在 cue 里
        val corpus = buildString {
            append("李寄舟走了进来。李寄舟的书还在桌上。看着李寄舟，张三丰沉默。\n")
            append("李寄舟问道：“你是谁？”李寄舟询问道：“你是谁？”李寄舟询问道：“走吗？”\n")
            append("。李寄舟随了他。李寄舟随后的动作。李寄舟随手拿起。\n")
            append("张三丰笑了。\n")
        }
        val ev = NameLegitimacy.corpusEvidence(corpus, listOf("李寄舟", "李寄舟询", "李寄舟随", "招呼"))
        val name = ev.getValue("李寄舟")
        val slice = ev.getValue("李寄舟询")
        println("[gate] 李寄舟=$name  李寄舟询=$slice  李寄舟随=${ev.getValue("李寄舟随")}")
        assertTrue(name.supportsName(), "真名应满足语料证据: $name")
        assertTrue(!slice.supportsName(), "cue 切片不应满足语料证据: $slice")
        assertEquals(1, slice.rightKinds, "cue 切片的右邻字符只有 cue 动词")
        // 真名先被接受后，切片按"真名+残留"归并（无论它自身语料证据如何）
        val v = NameLegitimacy.evaluate("李寄舟询", slice, setOf("李寄舟"))
        assertTrue(!v.accepted && v.reject == NameReject.NAME_PLUS_RESIDUE && v.residueOf == "李寄舟", "$v")
        // 有语料证据的残留（"李寄舟随"右邻字符 3 种）同样先按"真名+残留"归并 —— 拒因更可审计
        val v2 = NameLegitimacy.evaluate("李寄舟随", ev.getValue("李寄舟随"), setOf("李寄舟"))
        assertTrue(!v2.accepted && v2.reject == NameReject.NAME_PLUS_RESIDUE, "$v2")
        // 语料里不存在的表面 → 无证据 ⇒ 拒
        assertEquals(NameReject.CUE_ONLY, NameLegitimacy.evaluate("张小明", SurfaceEvidence.UNKNOWN, emptySet()).reject)
        // 封闭类优先于语料判据
        assertEquals(NameReject.FUNCTION_WORD, NameLegitimacy.evaluate("招呼", ev.getValue("招呼"), emptySet()).reject)
    }

    // ── 3. 真实书：发现闸门的量化结果 ─────────────────────────────────

    @Test
    fun `real book discovery removes cue pollution and keeps every labeled name`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            source.isFile && labels.isFile, "真机源文件/标注不在，跳过（$source）",
        )
        val paragraphs = paragraphs()
        val result = CharacterDiscovery.discover(paragraphs)
        val labeled = JSONObject(labels.readText())
        val nameLabels = labeled.keys().asSequence().filter { labeled.getString(it) == "name" }.toList()
        val preGate = JSONObject(deviceCache.readText()).getJSONArray("names").let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        }

        val keptNames = nameLabels.filter { it in result.names }
        val keptPollution = preGate.filter { it !in nameLabels.toSet() && it in result.names }

        val report = buildString {
            appendLine("# 候选人名合法性闸门 —— 真实书结果")
            appendLine()
            appendLine("源文件：`${source.name}`（${source.length()} B）")
            appendLine()
            appendLine("| 指标 | 修复前（真机 discovery 缓存） | 修复后（闸门） |")
            appendLine("|---|---|---|")
            appendLine("| 名字数 | ${preGate.size} | ${result.names.size} |")
            appendLine("| 手工标注真名保留 | — | ${keptNames.size}/${nameLabels.size} |")
            appendLine("| 非标注表面保留（污染残余） | ${preGate.size - nameLabels.count { it in preGate }} | ${keptPollution.size} |")
            appendLine()
            appendLine("## 拒因分布")
            appendLine()
            result.byReason().forEach { (k, v) -> appendLine("- `${k.name}`: $v") }
            appendLine()
            appendLine("## 修复前存在、修复后被拦下的高频污染（前 30）")
            appendLine()
            result.rejected.sortedByDescending { it.evidence?.total ?: 0 }.take(30).forEach {
                appendLine("- `${it.surface}` — ${it.reject.name} (total=${it.evidence?.total ?: 0})")
            }
            appendLine()
            appendLine("## 残留（非标注但被保留）")
            appendLine()
            appendLine(keptPollution.sorted().joinToString(" ") { "`$it`" })
        }
        File(dir, "GATE_RESULT.md").writeText(report)
        println(report)

        // ★ 事故表面必须被拦
        for (bad in listOf("哈哈", "招呼", "李寄舟询", "张三丰知", "倘若", "抱拳", "师妃暄便")) {
            assertTrue(bad !in result.names, "污染表面未被拦下: $bad")
        }
        // ★ 召回：标注真名一个都不能少
        assertEquals(nameLabels.size, keptNames.size, "真名被误伤: ${nameLabels - keptNames.toSet()}")
        // ★ 量化：污染必须被大幅削减（本机实测 290/298）
        val pollutionBefore = preGate.size - nameLabels.count { it in preGate }
        assertTrue(
            keptPollution.size <= pollutionBefore / 10,
            "污染削减不足：before=$pollutionBefore after=${keptPollution.size}",
        )
        // ★ 保留的残余只能是"称谓/角色指代"，不得是 cue 切片或虚词
        assertEquals(emptyList(), result.names.filter { NameLegitimacy.closedClassReject(it) != null })
    }

    // ── 4. 产品路径端到端：候选与剧本行 ───────────────────────────────

    @Test
    fun `product path - ungated entity set reproduces the bug and the gate removes it`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            source.isFile && deviceCache.isFile, "真机源文件/缓存不在，跳过",
        )
        val paragraphs = paragraphs()
        val discovery = CharacterDiscovery.discover(paragraphs)
        val gated = discovery.names.toSet()
        val ungated = JSONObject(deviceCache.readText()).getJSONArray("names").let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        }
        val bogus = setOf("哈哈", "招呼")
        assertTrue(bogus.all { it in ungated }, "真机缓存里应含事故表面（复现前提）")
        assertTrue(bogus.none { it in gated }, "闸门后不得再含事故表面")

        // ★ 用**真机同章**作用域（chapterId=13434，真机脚本 source 就是这一章）
        val scope = paragraphs.filter { it.chapterId == DEVICE_CHAPTER_ID }
        assertTrue(scope.size >= 12, "章作用域段落数异常: ${scope.size}（真机为 paragraph 125..139）")
        val ids = scope.map { it.paragraphId }
        println("[gate] 章作用域 chapterId=$DEVICE_CHAPTER_ID paragraphs=${scope.size} ids=${ids.first()}..${ids.last()}")

        val candsUngated = candidateSurfaces(scope, ungated)
        val candsGated = candidateSurfaces(scope, gated)
        val hitUngated = bogus.intersect(candsUngated)
        val hitGated = bogus.intersect(candsGated)
        println("[gate] ungated 编译候选=$candsUngated")
        println("[gate] gated   编译候选=$candsGated")

        // ★ 层 1（向量定位）：**原始召回**路径（recentMentions，不过任何闸门）——
        //   污染实体集会把窗口文本里的"哈哈/招呼"召回成候选；这就是事故的入口。
        val windowText = scope.map { it.normalizedText }
        val recallUngated = SpeakerCandidateCompiler(ungated).recentMentions(windowText)
        val recallGated = SpeakerCandidateCompiler(gated).recentMentions(windowText)
        val recallHit = bogus.intersect(recallUngated)
        println("[gate] ungated 原始召回命中=$recallHit  gated 原始召回命中=${bogus.intersect(recallGated)}")
        assertTrue(
            recallHit.isNotEmpty(),
            "污染实体集应能从窗口文本召回 $bogus（否则本 Gate 无法证明修复有效）：$recallUngated",
        )
        assertTrue(bogus.none { it in recallGated }, "干净实体集不得再召回事故表面: $recallGated")

        // ★ 层 2（编译期防御纵深）：**即使实体集仍被污染**，封闭类闸门也不许它们进候选
        assertTrue(
            bogus.none { it in candsUngated },
            "编译期闸门失效：污染实体集下候选出现事故表面 $hitUngated",
        )
        // ★ 层 3（发现期闸门）：干净实体集下候选完全不含事故表面
        assertTrue(hitGated.isEmpty(), "闸门后候选仍含事故表面: $hitGated")

        // ★ 层 3：跑完整 runner（确定性 decider 恒选候选列表第一个），剧本行不得归给任何被拦下的表面
        val rejected = discovery.rejected.map { it.surface }.toSet()
        val lines = ArrayList<ScriptLine>()
        var calls = 0
        ChapterDirectorRunner(BootstrapCharacterStore(gated), bookPk = 1L)
            .run(
                scope,
                decider = { prompt -> calls++; pickTopCandidate(prompt) },
                onLine = { lines += it },
            )
        val characterNames = lines.mapNotNull { (it.speaker as? SpeakerRef.Character)?.id?.value?.removePrefix("boot-") }
        val bogusIds = characterNames.filter { it in rejected || it in bogus }
        println("[gate] lines=${lines.size} modelCalls=$calls characterLines=${characterNames.size} names=${characterNames.distinct()}")
        assertTrue(bogusIds.isEmpty(), "剧本行说话人落在被拦表面: $bogusIds")
        assertTrue(lines.isNotEmpty(), "窗口内应产出 ScriptLine")
        val jsonl = File(dir, "sample_script_lines.jsonl")
        jsonl.writeText(lines.joinToString("") { ScriptLineCodec.encode(it) + "\n" })
        println("[gate] 产出样本：$jsonl")

        // ★ 层 4：回归报告（修复前/后对照），落在同一目录供人工复核
        File(dir, "PRODUCT_PATH_RESULT.md").writeText(
            buildString {
                appendLine("# 产品路径对照：候选与剧本行（真机同章 chapterId=$DEVICE_CHAPTER_ID）")
                appendLine()
                appendLine("- 章作用域段落：${scope.size}（paragraphId ${ids.first()}..${ids.last()}）")
                appendLine("- 原始召回（recentMentions，不过闸门）：污染实体集命中 **$recallHit**；干净实体集命中 **${bogus.intersect(recallGated)}**")
                appendLine("- 编译候选（SpeakerCandidateCompiler）：污染实体集 ${candsUngated.size} 个命中 **${bogus.intersect(candsUngated)}**；干净实体集 ${candsGated.size} 个（事故命中 ${hitGated.size}）")
                appendLine("- 剧本行：${lines.size} 行，角色行 ${characterNames.size}，涉及角色 ${characterNames.distinct()}")
                appendLine("- 说话人落在被拦表面的行：${bogusIds.size}")
                appendLine()
                appendLine("## 污染实体集下的原始召回（污染可见的入口）")
                appendLine()
                appendLine(recallUngated.joinToString(" ") { "`$it`" })
                appendLine()
                appendLine("## 污染实体集下的编译候选（编译期闸门已拦）")
                appendLine()
                appendLine(candsUngated.joinToString(" ") { "`$it`" })
                appendLine()
                appendLine("## 干净实体集下的编译候选")
                appendLine()
                appendLine(candsGated.joinToString(" ") { "`$it`" })
            },
        )
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /** 走产品路径（ContextBuilder → SpeakerCandidateCompiler）收集窗口内出现过的候选表面。 */
    private fun candidateSurfaces(slice: List<LogicalParagraph>, entities: Set<String>): Set<String> {
        val segmenter = SemanticSegmenter()
        val baseline = RuleSpeakerBaseline(entities)
        val builder = ContextBuilder(BootstrapCharacterStore(entities))
        val window = ArrayDeque<ContextBuilder.WindowParagraph>()
        val recent = ArrayDeque<String>()
        var prevCue: String? = null
        val out = LinkedHashSet<String>()
        for (p in slice) {
            val segs = segmenter.segment(p)
            if (segs.isEmpty()) continue
            val res = baseline.assign(p, segs, recent.toList(), prevCue)
            val ctx = builder.build(1L, p, segs, res, window.toList(), p.paragraphId, 8, emptyList())
            ctx.candidateSpeakers.forEach { out += it.name }
            for (s in segs) {
                val r = res[s.segmentIndex] ?: continue
                r.speaker?.takeIf { r.status != "UNKNOWN" }?.let { recent.remove(it); recent.addFirst(it) }
            }
            while (recent.size > 16) recent.removeLast()
            prevCue = baseline.extractTrailingCue(p.normalizedText)
            window.addLast(ContextBuilder.WindowParagraph(p, res))
            while (window.size > 8) window.removeFirst()
        }
        return out
    }

    /** adversarial decider：永远选候选列表里的第一个（CUE 优先级最高 ⇒ 最坏情况）。 */
    private fun pickTopCandidate(prompt: String): String {
        val token = Regex("要分析的片段编号：(S\\d+)").find(prompt)?.groupValues?.get(1) ?: "S0"
        val isDialogue = prompt.contains("该片段的类型已由切分器确定：〔对白〕")
        val candidate = Regex("(?m)^(C\\d+) ").find(prompt)?.groupValues?.get(1)
        val speaker = if (isDialogue) (candidate ?: DirectorDecisionV2.UNKNOWN) else DirectorDecisionV2.NARRATOR
        return JSONObject()
            .put("segment_id", token)
            .put("speaker", speaker)
            .put("type", if (isDialogue) "DIALOGUE" else "NARRATION")
            .put("emotion", DirectorDecisionV2.SAFE_EMOTION.name)
            .put("emotion_intensity", DirectorDecisionV2.SAFE_INTENSITY.toDouble())
            .put(
                "delivery",
                JSONObject()
                    .put("pace", Delivery.SAFE.pace.name)
                    .put("volume", Delivery.SAFE.volume.name)
                    .put("tone", Delivery.SAFE.tone.name),
            )
            .put("voice_event", DirectorDecisionV2.SAFE_VOICE_EVENT.name)
            .put("evidence", JSONArray())
            .toString()
    }

    private val paragraphsCache: List<LogicalParagraph> by lazy {
        val bytes = source.readBytes()
        val det = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, DEVICE_BOOK_ID).lines
        // ★ 必须与真机同一条链路：章节结构 → ConfirmedChapterView → 段落继承 chapterId（M0.3/M0.5）
        val structure = ChapterStructureCompiler(legacyRules()).compile(lines, DEVICE_BOOK_ID)
        val confirmed = ConfirmedChapterView.of(structure.revision, lines.size)
        ParagraphRecoveryPipeline().run(
            lines, DEVICE_BOOK_ID, emptySet(), emptySet(), emptySet(), emptyList(), chapters = confirmed,
        ).paragraphs.map { it.paragraph }
    }

    private fun legacyRules() = LegacyChapterRuleAdapter.fromJson(
        javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!,
    )

    private fun paragraphs(): List<LogicalParagraph> {
        // 位置断言：真机日志里 paragraph 125..139 承载事故；本地恢复必须能定位到同一内容
        assertTrue(paragraphsCache.size > 150, "段落数异常: ${paragraphsCache.size}")
        return paragraphsCache
    }

    @Test
    fun `segment type of the anchor window is dialogue as recorded on device`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(source.isFile, "真机源文件不在，跳过")
        val anchor = paragraphs().indexOfFirst { it.normalizedText.contains("大宋都死了几十年了") }
        val segs = SemanticSegmenter().segment(paragraphs()[anchor])
        assertTrue(segs.any { it.type == SegmentType.SPEECH }, "锚点段落应含对白段")
    }
}
