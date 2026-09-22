package com.readervoice.data.m3

import com.readervoice.data.semantic.CosmeticFix
import com.readervoice.data.semantic.DirectorDecisionV2
import com.readervoice.data.semantic.DirectorOutputValidator
import com.readervoice.data.semantic.DirectorSelectionPrompt
import com.readervoice.data.semantic.ProtocolNormalizer
import com.readervoice.data.semantic.ScriptLineBuilder
import com.readervoice.data.semantic.SemanticSegment
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.data.semantic.CandidateSpeaker
import com.readervoice.data.semantic.ContextSegment
import com.readervoice.data.semantic.DirectorContext
import com.readervoice.data.semantic.EvidenceItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MOBILE-005 / M3 步骤 3+4 的**第一批评估**（用户指定的四项指标）。
 *
 * 批次：M0 的 25 个真实 segment（每段 3 次 greedy 采样，raw 输出已在
 * `runs/mobile_005_director_real/m0_protocol_probe/out_*.txt`）。
 *
 * 链路：raw → ProtocolNormalizer → DirectorDecisionV2 → DirectorOutputValidator → ValidationOutcome
 *       → ScriptLineBuilder ⇒ 四项指标：
 *   ① 协议硬失败率 hardFailRate = REJECT / 全部运行
 *   ② cosmetic fix rate = 至少含一项表示层修复的运行占比（+ 修复类型分布）
 *   ③ UNKNOWN / VERIFY 率
 *   ④ ScriptLine 安全生成率 = 成功产出 ScriptLine 的运行占比（且文本必为 canonical 切片）
 */
class M3BatchMetricsTest {

    private val dir = File("../runs/mobile_005_director_real/m0_protocol_probe")

    @Test
    fun `batch metrics over M0 corpus`() {
        val fixture = File(dir, "fixture.jsonl")
        org.junit.jupiter.api.Assumptions.assumeTrue(fixture.exists(), "M0 fixture 不在")

        val recs = fixture.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        var runs = 0
        var hardFail = 0
        var cosmetic = 0
        var unknown = 0
        var verify = 0
        var downgrade = 0
        var accepted = 0
        var lineOk = 0
        val fixKinds = LinkedHashMap<String, Int>()
        val failureKinds = LinkedHashMap<String, Int>()
        val perOutcome = LinkedHashMap<String, Int>()
        val rejectSamples = mutableListOf<String>()

        recs.forEachIndexed { i, rec ->
            val env = M3BatchEnv.of(rec)
            val ctx = env.ctx

            val f = File(dir, "out_%02d.txt".format(i))
            if (!f.exists()) return@forEachIndexed
            val answers = f.readLines().map { it.trim() }.filter { it.startsWith("{") }
            if (answers.isEmpty()) return@forEachIndexed

            for (raw in answers) {
                runs++
                val normalized = ProtocolNormalizer.normalize(raw)
                val result = DirectorOutputValidator.validate(
                    normalized,
                    expectedSegmentToken = DirectorSelectionPrompt.segmentToken(ctx.target),
                    candidateLocalIds = env.candidateIds,
                    providedEvidenceIds = env.evidenceIds,
                )
                if (normalized.fixes.isNotEmpty()) {
                    cosmetic++
                    normalized.fixes.forEach { fixKinds.merge(it.name, 1, Int::plus) }
                }
                (result.hardFailures + result.softFailures).forEach { failureKinds.merge(it.code.name, 1, Int::plus) }
                perOutcome.merge(result.outcome.name, 1, Int::plus)
                when (result.outcome) {
                    ValidationOutcome.REJECT -> {
                        hardFail++
                        if (rejectSamples.size < 5) {
                            rejectSamples += "[$i] " + result.hardFailures.joinToString("; ") { "${it.code}: ${it.detail}" }
                        }
                    }
                    ValidationOutcome.VERIFY -> verify++
                    ValidationOutcome.DOWNGRADE -> downgrade++
                    ValidationOutcome.ACCEPTED_ALL -> accepted++
                }
                if (result.decision?.speaker == DirectorDecisionV2.UNKNOWN) unknown++
                val line = ScriptLineBuilder.build(env.paragraphText, env.segment, ctx, result)
                if (line != null) {
                    // 安全生成的双重断言：文本必须等于 canonical 切片
                    check(line.text == env.segment.text) { "ScriptLine.text 非 canonical 切片" }
                    lineOk++
                }
            }
        }

        val pct = { x: Int -> "%.1f%%".format(100.0 * x / runs) }
        val report = buildString {
            appendLine("=== M3 第一批评估（M0 语料）===")
            appendLine("runs=$runs（${recs.size} 目标 × 3 次 greedy）")
            appendLine("① 协议硬失败率        : ${pct(hardFail)}  ($hardFail/$runs)")
            appendLine("② cosmetic fix rate   : ${pct(cosmetic)}  ($cosmetic/$runs)")
            appendLine("   修复类型分布         : $fixKinds")
            appendLine("③ UNKNOWN / VERIFY    : UNKNOWN ${pct(unknown)} · VERIFY ${pct(verify)}")
            appendLine("④ ScriptLine 安全生成率: ${pct(lineOk)}  ($lineOk/$runs)")
            appendLine("outcome 分布          : $perOutcome")
            appendLine("失败码分布            : $failureKinds")
            if (rejectSamples.isNotEmpty()) {
                appendLine("REJECT 样例：")
                rejectSamples.forEach { appendLine("   $it") }
            }
        }
        File(dir, "m3_batch_metrics.txt").writeText(report)
        println(report)
        assertTrue(runs > 0, "没有可评估的运行")
    }
}
