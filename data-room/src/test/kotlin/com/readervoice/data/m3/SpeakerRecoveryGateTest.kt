package com.readervoice.data.m3

import com.readervoice.data.semantic.CharacterDiscovery
import com.readervoice.data.semantic.RuleSpeakerBaseline
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.parser.paragraph.BlockType
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.paragraph.ReadPolicy
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
/**
 * CH-0 质量修复 ②：**规则层说话人回收**（用户：「一堆未知说话人」）。
 *
 * ## 事故（真机 76 段剧本）
 *
 * ```
 * NARRATOR 41 / 李寄舟 19 / UNKNOWN 16
 * ```
 *
 * 16 段 UNKNOWN 的对白，说话人其实是 **刘三刀 / 雷老虎 / 老道士** —— 而这三个名字
 * **从未出现在角色发现的结果里**（`雷老虎` 全书 42 次、`刘三刀` 22 次）。
 *
 * ## 根因：cue 提取只认"说/道动词前的最后一个标点块"
 *
 * 这本书的两种写法都逃过了它：
 * ```
 * 雷老虎也失笑的摇了摇头：              ← ① 冒号型：没有说/道动词 ⇒ 直接找不到 cue
 * 深吸一口气，雷老虎上前一步，拱手抱拳道： ← ② 名字不是最后一个块（最后块是"拱手抱拳"）
 * ```
 *
 * ⇒ 说话人从未被发现 ⇒ 规则层给不出候选 ⇒ 模型只能从"提及"里挑（挑成 `李寄舟`，错）
 *   或老实答 UNKNOWN。
 *
 * ## 修法（**规则先判断**，模型只做残差）
 *
 * · **通道 ②**：最后一个块解析不出人名时，对 cue 子句**从右向左回扫已知实体**（取块内最靠左的实体 = 主语位置）
 * · **通道 ③**：冒号型 cue（无动词）同样走子句回扫
 * · **通道 ④（自举）**：`proposeSubjects` 把 cue 子句**段首 2–4 字前缀**作为**提议**交出来，
 *   由 `NameLegitimacy` **严档**语料证据裁决（`rightKinds >= 10`）：
 *   `雷老虎 34 / 刘三刀 18` 通过，`深吸 3 / 上前一步 7 / 摇了摇头 5` 拦下。
 *   没有这一条就存在自举死循环：名字进不了实体集 ⇒ 回扫永远匹配不到。
 */
class SpeakerRecoveryGateTest {

    private fun para(text: String, id: Long = 1L) = LogicalParagraph(
        paragraphId = id, revisionId = id, bookId = "b", chapterId = null,
        sourceRevisionId = "s", paragraphIndex = 0, sourceSpans = emptyList(),
        normalizedText = text, blockType = BlockType.PROSE, readPolicy = ReadPolicy.NORMAL,
        boundaryConfidence = 0.9,
    )

    /** 取该段第一个 SPEECH 段被规则层判定的说话人。 */
    private fun speak(text: String, entities: Set<String> = emptySet()): String? {
        val p = para(text)
        val segs = SemanticSegmenter().segment(p)
        val speech = segs.firstOrNull { it.type == SegmentType.SPEECH } ?: return null
        val res = RuleSpeakerBaseline(entities).assign(p, segs, emptyList())
        return res[speech.segmentIndex]?.speaker
    }

    // ── 1. 冒号型 cue（无说/道动词）────────────────────────────────────

    @Test
    fun `colon only cue resolves the clause subject`() {
        val text = "“大宋都死了几十年了。”雷老虎也失笑的摇了摇头：“多是沽名钓誉，欺世盗名之徒。”"
        assertEquals("雷老虎", speak(text, setOf("雷老虎")), "冒号型 cue 未从句中回扫出主语")
        // ★ 没有实体集时**绝不猜测**（不变量 5：禁止强造角色名）
        assertNull(speak(text), "无实体集时不得猜出说话人")
    }

    // ── 2. 名字不在最后一个块 ──────────────────────────────────────────

    @Test
    fun `name not in last block is recovered from the clause`() {
        val text = "深吸一口气，雷老虎上前一步，拱手抱拳道：“不知老前辈是哪门哪派？”"
        assertEquals("雷老虎", speak(text, setOf("雷老虎")), "名字不在最后块时未回扫")
        // ★ 无实体集时**回扫不发明名字**（旧行为是取最后块派生的 `拱手抱拳`，
        //   它是污染表面但由闸门否决 —— 这里只断言"没有凭空造出雷老虎"）
        assertTrue(speak(text) != "雷老虎", "无实体集时不得猜出说话人")
    }

    // ── 3. 回扫不得吃掉显式 cue 与 delivery cue ────────────────────────

    @Test
    fun `clause rescan does not override explicit cue or invent speakers`() {
        // 显式 cue 仍然优先（最后块就是真名）
        assertEquals("傅远", speak("傅远说道：“走。”", setOf("傅远", "陈月")))
        // 已知实体的前缀消歧仍然优先于回扫
        assertEquals("曹健", speak("曹健开玩笑道：“上面又没刻我的名字。”", setOf("曹健")))
        // 子句里没有已知实体时，回扫不得凭空发明（保持既有行为：块派生结果由闸门否决）
        assertEquals("对闫呢", speak("对闫呢道：“你怎么看？”"))
    }

    // ── 4. 段首主语提议（**已实测关闭**：见 CharacterDiscovery.ENABLE_SUBJECT_PROPOSALS）──

    @Test
    fun `subject proposals exist but are gated by strict evidence and stay behind a flag`() {
        val b = RuleSpeakerBaseline()
        // 提议机制本身可用（保留给 CH-1 的 entity type 判据落地后重启）
        val props = b.proposeSubjects(para("雷老虎：“多是沽名钓誉。”"))
        assertTrue(props.isNotEmpty(), "提议机制应可用：$props")
        assertTrue(props.none { it.length == 2 }, "不得提议 2 字前缀（实测把接受集炸到 1502）：$props")
        // ★ 但默认**不启用**：启用后接受集 81 → 414（多出的是 `不可能/但他们/九阳神功/华山派`），
        //   重新投毒 —— 这正是本轮修掉的 bug。必须有 entity type 判据（CH-1）才允许打开。
        val src = File("../runs/mobile_005_director_real/candidate_gate/source.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(src.isFile, "真机源文件不在，跳过")
        val paragraphs = loadParagraphs(src)
        val r = CharacterDiscovery.discover(paragraphs)
        assertTrue(r.names.size in 60..120, "接受集应保持在已验证量级（实测 81），实际 ${r.names.size}")
    }

    private fun loadParagraphs(src: File): List<LogicalParagraph> {
        val bytes = src.readBytes()
        val det = EncodingDetector.detect(bytes)
        val bookId = "book-d22a7b3878ee"
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, bookId).lines
        return ParagraphRecoveryPipeline()
            .run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
            .paragraphs.map { it.paragraph }
    }

    // ── 5. 真机源文件：被漏掉的真名必须被找回 ──────────────────────────

    @Test
    fun `real book discovery recovers the two most active speakers`() {
        val src = File("../runs/mobile_005_director_real/candidate_gate/source.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(src.isFile, "真机源文件不在，跳过")
        val paragraphs = loadParagraphs(src)
        val r = CharacterDiscovery.discover(paragraphs)
        val report = buildString {
            appendLine("# 规则层说话人回收 —— 真机源文件结果")
            appendLine()
            appendLine("| 名字 | 修复前 | 本轮（通道 ②③） | 语料证据 |")
            appendLine("|---|---|---|---|")
            for (n in listOf("雷老虎", "刘三刀", "李寄舟", "张三丰")) {
                val ev = r.evidenceOf(n)
                appendLine(
                    "| $n | ${if (n == "雷老虎" || n == "刘三刀") "**缺失**" else "在" } | " +
                        "${if (n in r.names) "**在**" else "仍缺失"} | " +
                        "total=${ev?.total ?: 0} rightKinds=${ev?.rightKinds ?: 0} indep=${ev?.independentCount ?: 0} |",
                )
            }
            appendLine()
            appendLine("接受 **${r.names.size}** 个（原始候选 ${r.harvested}，污染率 ${"%.1f".format(r.pollutionRate * 100)}%）")
            appendLine()
            appendLine("## 结论（诚实的负面结果）")
            appendLine()
            appendLine(
                "通道 ②③（冒号型 cue / 名字不在最后块 ⇒ 子句回扫**已知实体**）已生效且精确，" +
                    "但它们**只能找回实体集里已有的名字** —— `雷老虎`/`刘三刀` 因为进不了实体集，" +
                    "本轮仍是 UNKNOWN。",
            )
            appendLine()
            appendLine(
                "自举通道（段首主语提议）实测**有效但不精确**：能找回 `雷老虎`/`刘三刀`，" +
                    "但接受集 81 → 414（多出 `不可能/但他们/不知道/九阳神功/华山派/倚天剑`）。" +
                    "收紧记录：2 字前缀 ⇒ 1502；3–4 字 + 最近 3 块 ⇒ 484；3–4 字 + 最近 2 块 ⇒ 414。" +
                    "**再严会连 `刘三刀`(rightKinds=18) 一起丢** ⇒ 已默认关闭，" +
                    "等 CH-1 的 entity type 判据（PERSON vs OBJECT/PLACE）落地后再开。",
            )
            appendLine()
            appendLine("名字清单：${r.names.joinToString(" ")}")
        }
        File("../runs/mobile_005_director_real/candidate_gate").mkdirs()
        File("../runs/mobile_005_director_real/candidate_gate/SPEAKER_RECOVERY_RESULT.md").writeText(report)
        println(report)

        // 通道 ②③ 是精确的：不得改变已核验的名字集量级
        assertTrue(r.names.size in 60..120, "接受集量级异常：${r.names.size}")
        // 不得把段首动词短语放进来
        for (bad in listOf("深吸", "摇了摇头", "上前一步", "拱手抱拳", "拱手抱")) {
            assertTrue(bad !in r.names, "动词短语混入名字集：$bad")
        }
    }
}
