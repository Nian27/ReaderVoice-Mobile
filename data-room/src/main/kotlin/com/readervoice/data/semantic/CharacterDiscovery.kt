package com.readervoice.data.semantic

import com.readervoice.parser.paragraph.LogicalParagraph

/**
 * MOBILE-005 / CH-0 质量修复 ①：**角色发现（带合法性闸门）**。
 *
 * ## 为什么把发现逻辑从 app 层搬到这里
 *
 * 原来它在 `ChapterDirectorService.discoverNames()`（Android 模块私有方法）里：
 * 只有真机跑得动、JVM Gate 测不到 —— 正是"编译通过 + 单测绿 ≠ 数据通了"的温床。
 * 现在它是 data-room 的一等模块，**产品路径与 Gate 走同一份代码**。
 *
 * ## 三段式（关键：闸门必须建立在语料证据上）
 *
 * ```
 * Phase 1  harvest(entities = ∅)      —— 无实体集抽取"可能当名字的原始表面"（含残留）
 * Phase 2  语料证据 + 闸门             —— NameLegitimacy：rightKinds/独立短语/封闭类形态
 * Phase 3  harvest(entities = 干净集)  —— 用干净实体集再抽一轮（残留归并 ⇒ 得到干净真名）
 *          → 并集再过一次闸门（确定性顺序）→ 最终名字集
 * ```
 *
 * Phase 3 不可省：`闫妮追赶道` 这类表面只有在 `knownEntities` 里已有 `闫妮` 时才会被
 * `RuleSpeakerBaseline` 的前缀消歧收敛成真名；若只做 Phase 1+2，真名会连同残留一起被闸门拦掉。
 *
 * ## 实测（《朕真的不务正业》4.3MB 真机源文件）
 *
 * ```
 * 修复前：371 个"名字"（约 80% 是 cue 切片，含 哈哈 / 招呼 / 李寄舟询 / 张三丰知）
 * 修复后：81 个（73 个手工标注真名全部保留，290/298 污染被拦）
 * ```
 * Gate 见 `NameLegitimacyGateTest`；离线评估见 `tools/mobile005/name_gate_probe.js`。
 */
object CharacterDiscovery {

    /**
     * 缓存版本 = 闸门版本。**旧缓存（未过闸门）一律失效重算**。
     * 变更闸门语义时必须 +1，否则设备会继续吃污染缓存。
     */
    const val CACHE_VERSION = NameLegitimacy.GATE_VERSION

    /** 被闸门否决的表面（可审计：为什么它不是人名）。 */
    data class Rejected(
        val surface: String,
        val reject: NameReject,
        /** `NAME_PLUS_RESIDUE` 时：归并到的真名。 */
        val residueOf: String?,
        val evidence: SurfaceEvidence?,
    )

    data class Result(
        /** 最终合法角色名（按字典序稳定排序；**只含过闸门的表面**）。 */
        val names: List<String>,
        /** 被拦下的表面（含拒因与语料证据）——用于报告与回归。 */
        val rejected: List<Rejected>,
        /** Phase 1 抽到的原始表面数（= 修复前的"名字"规模，供对照）。 */
        val harvested: Int,
        /** 全部被评估表面的语料证据（供指标、跨书复核与人工抽检）。 */
        val evidence: Map<String, SurfaceEvidence> = emptyMap(),
        /**
         * 抽取阶段就被**降级为 delivery cue**、因此从未成为候选的表面
         * （如叹词 `哈哈`：`DeliveryCueLexicon` 的绝对判据在 cue 提取处就把它转成表演提示）。
         * 它们是"非人说话人判分"的参照集的一部分 —— 让审计文件自洽，不依赖工具侧词表。
         */
        val demotedCues: List<String> = emptyList(),
    ) {
        /** **污染率** = 被拦表面 / 原始候选。修复前恒为 0（没有闸门），修复后是闸门最直接的指标。 */
        val pollutionRate: Double get() = if (harvested == 0) 0.0 else rejected.size.toDouble() / harvested

        fun byReason(): Map<NameReject, Int> =
            rejected.groupingBy { it.reject }.eachCount().toSortedMap(compareBy { it.name })

        /** 该表面的语料证据（未被评估过则为 null）。 */
        fun evidenceOf(surface: String): SurfaceEvidence? = evidence[surface]

        /** 人可读审计（设备侧落盘证据）。 */
        fun audit(bookId: String): String = buildString {
            appendLine("bookId=$bookId cacheVersion=$CACHE_VERSION")
            appendLine(
                "harvested=$harvested  accepted=${names.size}  rejected=${rejected.size}  " +
                    "pollutionRate=%.1f%%".format(pollutionRate * 100),
            )
            appendLine("byReason=${byReason().entries.joinToString(" ") { "${it.key}=${it.value}" }}")
            appendLine("--- rejected ---")
            rejected.sortedWith(compareByDescending<Rejected> { it.evidence?.total ?: 0 }.thenBy { it.surface })
                .forEach { r ->
                    append(r.surface)
                    append('\t').append(r.reject.name)
                    r.residueOf?.let { append('\t').append("->").append(it) }
                    r.evidence?.let { append("\ttotal=${it.total} cue=${it.cueBound} right=${it.rightKinds} indep=${it.independentCount}") }
                    appendLine()
                }
            // 抽取阶段降级为 delivery cue 的表面（不是被丢弃）：判分参照集需要它们
            appendLine("--- demoted-cues ---")
            demotedCues.sorted().forEach { appendLine(it) }
        }
    }

    private const val RECENT_WINDOW = 16

    /**
     * **段首主语提议通道：默认关闭**（2026-09-18 实测结论，不要在没有新判据前打开）。
     *
     * 它的目的：`雷老虎`/`刘三刀` 从不出现在"cue 动词前最后一个块"里（本书写作
     * `雷老虎也失笑的摇了摇头：` / `深吸一口气，雷老虎上前一步，拱手抱拳道：`），
     * 靠 cue 块永远发现不到 ⇒ 他们的 16 段对白全部落 UNKNOWN。
     *
     * 实测它**有效但不精确**（真机源文件）：`雷老虎`(rightKinds=34) / `刘三刀`(18) 被找回，
     * 但接受集从 **81 → 414**，多出来的 333 个几乎全是：
     * ```
     * 不可能 不属于 不知道 之所以 也不再 但他们 只不他 即使他 只不过他 在李世民 对李寄舟   ← 虚词短语
     * 九阳神功 九阴真经 倚天剑 华山派 古墓派 仙灵岛 和氏璧 冰玄劲 凌云窟 大日如来        ← 物品/门派/地点
     * ```
     * 收紧过程记录（每一档都实测过）：`2 字前缀` ⇒ 1502；`3–4 字 + 最近 3 块` ⇒ 484；
     * `3–4 字 + 最近 2 块` ⇒ 414。**阈值再加严会连 `刘三刀`(18) 一起丢掉**。
     * 结论：语料统计分不开"人名"与"虚词短语/物品名"，需要 **entity type 判据**（PERSON vs
     * OBJECT/PLACE）—— 属于 CH-1（O-1）。在那之前，宁可 UNKNOWN，也不把候选集重新投毒
     * （那正是本轮修掉的那个 bug）。
     */
    private const val ENABLE_SUBJECT_PROPOSALS = false

    /**
     * 角色发现入口。**确定性**：同输入同输出（顺序也稳定）。
     *
     * @param paragraphs 全书段落（顺序 = 阅读顺序）
     */
    fun discover(
        paragraphs: List<LogicalParagraph>,
        segmenter: SemanticSegmenter = SemanticSegmenter(),
    ): Result {
        if (paragraphs.isEmpty()) return Result(emptyList(), emptyList(), 0)

        // Phase 1：无实体集抽取（"原始表面"= 污染前的规模）；同时收集**段首主语提议**
        val h1 = harvest(paragraphs, segmenter, emptySet())
        val raw = LinkedHashSet<String>(h1.names).also { it += h1.proposed }

        // Phase 2：全文语料证据（一次扫描）+ 闸门（提议档走严档）
        val corpus = StringBuilder(1 shl 22)
        for (p in paragraphs) { corpus.append(p.normalizedText); corpus.append('\n') }
        val ev1 = NameLegitimacy.corpusEvidence(corpus, raw)
        val strict1 = h1.proposed - h1.names
        val pass1 = gate(raw, ev1, strict1)

        // Phase 3：用干净实体集再抽一轮（残留归并 + 句中回扫），并集再过闸门
        val h2 = harvest(paragraphs, segmenter, pass1.names.toSet())
        val union = LinkedHashSet<String>(raw).also { it += h2.names; it += h2.proposed }
        val ev2 = if (union.size == raw.size) ev1 else NameLegitimacy.corpusEvidence(corpus, union)
        val strict2 = (h1.proposed + h2.proposed) - (h1.names + h2.names)
        val pass2 = gate(union, ev2, strict2)
        val demoted = (h1.demoted + h2.demoted).distinct().sorted()

        return Result(pass2.names.sorted(), pass2.rejected, raw.size, ev2, demoted)
    }

    private class Gated(val names: List<String>, val rejected: List<Rejected>)

    /**
     * 按**确定性顺序**（出现次数降序 + 表面升序）逐个过闸门。
     *
     * 顺序有意义：`NAME_PLUS_RESIDUE` 需要"真名先被接受"。
     * 用出现次数降序保证 `李寄舟`(8179) 先于 `李寄舟询`(12) 进入合法集 ——
     * 这比依赖抽取顺序更稳（抽取顺序会随文本顺序抖动）。
     *
     * @param strictSurfaces 走**严档**语料证据的表面（= 只由"段首主语提议"产生、从未作为干净 cue 块出现）
     */
    private fun gate(
        surfaces: Collection<String>,
        evidence: Map<String, SurfaceEvidence>,
        strictSurfaces: Set<String> = emptySet(),
    ): Gated {
        val ordered = surfaces.sortedWith(
            compareByDescending<String> { evidence[it]?.total ?: 0 }.thenBy { it },
        )
        val legit = LinkedHashSet<String>()
        val rejected = ArrayList<Rejected>()
        for (s in ordered) {
            val v = NameLegitimacy.evaluate(s, evidence[s], legit, strict = s in strictSurfaces)
            if (v.accepted) legit += s else rejected += Rejected(s, v.reject!!, v.residueOf, v.evidence)
        }
        return Gated(legit.toList(), rejected)
    }

    /** 一轮抽取的结果：干净 cue 块给出的名字 + 段首主语**提议** + 抽取期降级的 delivery cue。 */
    private class Harvest {
        val names = LinkedHashSet<String>()
        val proposed = LinkedHashSet<String>()
        val demoted = LinkedHashSet<String>()
    }

    /**
     * 单轮抽取（与既有实现一致的两轮自举语义）：
     * cue 确认的说话人 → `candidateNameVariants` 变体；同时维护 recent/prevCue；
     * 另收集 `proposeSubjects`（段首 2–4 字前缀，交给严档闸门裁决）。
     */
    private fun harvest(
        paragraphs: List<LogicalParagraph>,
        segmenter: SemanticSegmenter,
        entities: Set<String>,
    ): Harvest {
        val baseline = RuleSpeakerBaseline(entities)
        val out = Harvest()
        val recent = ArrayDeque<String>()
        var prevCue: String? = null
        for (p in paragraphs) {
            val segs = segmenter.segment(p)
            if (segs.isEmpty()) continue
            val res = baseline.assign(p, segs, recent.toList(), prevCue)
            for (s in segs) {
                val r = res[s.segmentIndex] ?: continue
                // 抽取期被降级为 delivery cue 的表面（如叹词"哈哈"）——判分参照集需要它们
                r.deliveryCues.forEach { out.demoted += it.text }
                val sp = r.speaker ?: continue
                if (r.status == "UNKNOWN") continue
                val v = RuleSpeakerBaseline.candidateNameVariants(sp)
                if (v.size == 1) out.names += sp else out.names += v.drop(1)
                recent.remove(sp); recent.addFirst(sp)
            }
            if (ENABLE_SUBJECT_PROPOSALS) out.proposed += baseline.proposeSubjects(p)
            while (recent.size > RECENT_WINDOW) recent.removeLast()
            prevCue = baseline.extractTrailingCue(p.normalizedText)
        }
        return out
    }
}