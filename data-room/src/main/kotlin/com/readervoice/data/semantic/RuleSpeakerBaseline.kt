package com.readervoice.data.semantic

import com.readervoice.parser.paragraph.LogicalParagraph

/**
 * Rule-only Speaker Baseline（TASK-070 §2）。
 * 分级（G3/G4/G5）：
 * - 显式 cue（"张三说：X"）→ CONFIRMED / EXPLICIT_SPEECH_CUE → **Easy Gold**（isEasy=true）
 * - 跨段 cue（上段末尾"张三说道："+ 本段对白）→ CONFIRMED / CROSS_PARAGRAPH_CUE（G4）
 * - 轮换追踪（近 2 名说话人交替）→ PROVISIONAL / TURN_TRACKING
 * - 无法判断 → UNKNOWN / UNKNOWN（G5：不乱猜）
 * 名字必须落在干净名单规则内（2-4 字 CJK、非停用词）——宁缺毋滥，防止污染 Easy Gold。
 */
class RuleSpeakerBaseline(
    /**
     * MOBILE-005 / M3：已知实体集（安全阀）。命中者**永不**被判为 delivery cue ——
     * 形态规则不得吃掉真人名（"缓缓"若确实是角色名，则仍是说话人）。
     */
    private val knownEntities: Set<String> = emptySet(),
) {

    data class SpeakerResult(
        val speaker: String?,        // null = UNKNOWN
        val status: String,          // CONFIRMED/PROVISIONAL/UNKNOWN
        val confidence: Double,
        val provenance: List<String>,
        val isEasy: Boolean,         // 显式 cue → Easy Gold 候选
        /**
         * M3：本段 cue 位置发现的**表演提示**（如"饶有兴致""冷冷地"）。
         * 它们不是人物，不进候选/gold，转为 ContextBuilder 的 evidence（DELIVERY_CUE）。
         */
        val deliveryCues: List<DeliveryCue> = emptyList(),
        /** M3：gold 型别（规则永远是规则，不是 gold；见 GoldPolicy）。 */
        val goldLabel: GoldLabel? = null,
    )

    // 注意：复合词必须排在单字前（答道 先于 答；说 先于…），否则 findAll 命中单字后吞掉复合词。
    // M3 补（真实书实测的两个残留："闫妮追问道"只吃"问道"→残留"追"、"曹健开玩笑道"只吃"笑道"→残留"开玩"）：
    //   把"动词短语"整体纳入，长词必须排在短词之前。
    private val CUE_VERB = Regex(
        "(?:开玩笑道|开玩笑说|追问道|反问道|补充道|解释道|提醒道|感慨道|感叹道|抱怨道|嘟囔道|附和道|赞同道" +
            "|出声道|应声道|接着说|继续说道|笑着说|哭着说|摇头道|点头道|沉着脸道|大笑道|微笑着" +
            "|低声道|厉声道|沉声道|淡淡道|冷冷道|说道|答道|问道|喊道|应道|回道|叫道|怒道|笑道|哭道|叹道" +
            "|开口|回答|说|问|答|喊|叫|嚷|喝|道)"
    )
    private val CJK_NAME = Regex("^[\\u4e00-\\u9fa5]{2,4}$")
    private val STOPLIST = setOf(
        "没有", "什么", "这样", "那样", "不是", "就是", "自己", "怎么", "我们", "你们", "他们",
        "这里", "那里", "这个", "那个", "时候", "现在", "已经", "还是", "或者", "一直", "忽然",
        "他说", "她说", "我说", "你说", "他", "她", "我", "你", "大家", "众人", "她们",
        "老人", "女子", "男子", "青年", "少年", "姑娘", "小伙子", "大汉", "壮汉", "汉子", "老者",
        "女人", "男人", "小孩", "孩子", "仆人", "丫鬟", "下人", "侍卫", "管家", "客人", "路人", "同伴",
    )
    /** 人名前不可出现的前缀（副词/情态）。 */
    private val ADVERB_PREFIX = listOf(
        "忽然", "突然", "连忙", "赶紧", "又", "再次", "终于", "缓缓", "冷冷", "淡淡", "轻轻",
        "大声", "小声", "低声", "轻声", "柔声", "颤声", "娇声", "重声", "微微", "狠狠", "急切",
        "尖声", "高声", "厉声", "默默", "忙",
        "也", "却", "依然", "仍旧", "于是", "随即", "立刻", "马上", "当场", "忽然间", "突然之间",
    )
    /** cue 动词前的残留副词（"张三又说" → 剥"又"）。 */
    // M3 补：真实书实测 "曹伟却说道：" ⇒ 剥后得 "曹伟却"（4 字、被判为人名）✗
    //   副词既能出现在名字【前】（ADVERB_PREFIX），也能夹在名字与 cue 动词【之间】，两边都要剥。
    private val TRAILING_ADVERB = listOf(
        "又", "也", "还", "都", "就", "仍", "再", "才", "刚", "正",
        "却", "则", "便", "即", "更", "已", "尚", "仍", "却仍", "这才", "这才又",
        // M3 补：真实书实测 "闫呢忙道"→"闫呢忙"、"曹健终于说道"→"曹健终于"
        "忙", "终于", "立刻", "顿时", "连忙", "赶紧", "随即", "当下",
    )
    /** 名字与动词之间的方式副词（"李雪轻声说" → 剥"轻声"）。 */
    private val MANNER_TRAILING = listOf(
        "轻声", "低声", "大声", "小声", "厉声", "柔声", "颤声", "娇声", "重声", "尖声", "高声", "齐声",
        // M3 补：这些同样会夹在"名字"与"道/说"之间（"赵六沉声道"），不剥会把整块当人名/提示语
        "沉声", "冷声", "寒声", "幽幽", "悠悠", "徐徐", "悻悻", "恨恨", "愤愤",
        "淡淡", "冷冷", "轻轻", "微微", "缓缓", "急切", "狠狠", "默默", "平静", "严肃", "认真", "喃喃",
        "忽然", "突然", "连忙", "赶紧", "气恼", "恼怒", "温和",
    )
    /** 名字后常见的动作短语（剥除后剩人名）。 */
    private val ACTION_PHRASE = listOf(
        "叹了口气", "叹口气", "点点头", "点了点头", "摇了摇头", "微微一笑", "皱了皱眉", "沉思片刻",
        "顿了顿", "抬起头", "低下头", "转过身", "站起来", "站起身", "走过去", "走上前", "后退一步",
        "咳了一声", "哼了一声", "笑了笑", "看了他一眼", "看了她一眼", "看了看", "想了片刻", "犹豫片刻",
        "拍了拍手", "站起身来", "叹了口气道", "沉默片刻", "变了声音", "压低了声音", "提高了声音",
        "推开门", "推门", "打开门", "关上门", "走进来", "走出去", "走了出去", "走了过来", "走上前来",
        "退后一步", "向前一步", "清了清嗓子", "看了看四周", "站起身来", "低下头去", "抬起头来",
        "低头", "抬头", "点头", "摇头", "转身",
    )
    /** 动作短语结尾 → 非人名（推开门/站起来/点点头/看了看/叹了口气…）。
     *  TASK-100 候选污染修复（2026-08-14）：扩展谓词/虚词结尾（劝/拦/骂/拍/打/即/续/板/街/常/反/没/的/是/对/案/还/直/接/继/能/要/会/只），
     *  消灭"她还是劝道"→"她还是劝"、"她当即"→"她当即" 类 cue 切片污染。宁缺毋滥（真名几乎不以这些字结尾）。 */
    private val ACTION_SUFFIX = listOf(
        "了", "着", "过", "门", "头", "身", "手", "眼", "步", "口", "声", "气",
        "里", "前", "后", "来", "去", "上", "下", "开", "起", "进", "出", "看", "想", "顿",
        "劝", "拦", "骂", "拍", "打", "即", "续", "板", "街", "常", "反", "没", "的", "是",
        "对", "案", "还", "直", "接", "继", "能", "要", "会", "只",
    )
    /**
     * 名字不得包含的代词/指代单字（"他还是劝道"→"他还是劝" 含"他" → 无显式名）。 */
    private val PRONOUN_CHARS = "他她你我咱们"

    companion object {
        /**
         * 段首主语**提议**不得以这些字起首（代词/指示/程度/数词 —— 不可能是人名）。
         * 见 [proposeSubjects]：提议只是候选，最终由 `NameLegitimacy` 严档语料证据裁决。
         */
        internal val PROPOSAL_SKIP_FIRST = "他她你我咱们这那其此该某怎多么很挺更最极特超蛮一两".toSet()

        /**
         * M3：可能粘在【真名前】的引语介词/动作词（"对闫呢道"→对闫呢、"指着甜妹道"→指着甜妹）。
         * 只在这张表内做后缀消歧 —— 不做"任意前缀裁剪"，否则"小王明"会被误截成"王明"。
         */
        internal val LEADING_CUE_PARTICLE = listOf(
            "对", "向", "朝", "冲", "和", "与", "跟", "给", "叫", "问", "邀",
            "指着", "拉着", "看着", "望着", "对着", "拍着", "扶着", "邀请", "拦住", "盯着",
        )

        /**
         * 实体发现用：给出一个 cue 表面的**可能真名变体**（含自身）。
         * 动机：真名可能从不单独作为 cue 主语出现（"对闫呢道" 里只有带残留的形态），
         * 若发现阶段只收原始表面，实体集里就永远没有"闫呢"，后面的消歧也无从触发（自洽循环）。
         * 生产路径的实体来自 mention 发现；本方法供离线素材发现/审计使用。
         */
        fun candidateNameVariants(surface: String): List<String> {
            val s = surface.trim()
            if (s.length < 3) return listOf(s)
            val out = mutableListOf(s)
            for (p in LEADING_CUE_PARTICLE) {
                if (s.startsWith(p)) {
                    val rest = s.removePrefix(p)
                    if (rest.length in 2..4 && rest.all { it in '\u4e00'..'\u9fa5' }) out += rest
                }
            }
            return out
        }
    }

    /**
     * @param paragraph 当前段
     * @param segments 该段已切分的 SemanticSegment
     * @param recentSpeakers 最近说话人（近→远）
     * @param crossParagraphCue 上段末尾显式 cue 提取的说话人（G4），null=无
     * @return segmentIndex → SpeakerResult
     */
    fun assign(
        paragraph: LogicalParagraph,
        segments: List<SemanticSegment>,
        recentSpeakers: List<String>,
        crossParagraphCue: String? = null,
    ): Map<Int, SpeakerResult> {
        val results = mutableMapOf<Int, SpeakerResult>()
        var lastSpeechEnd = 0 // 上一句对白结束位置；cue 文本在 [lastSpeechEnd, seg.start) 内
        for ((si, seg) in segments.withIndex()) {
            if (seg.type == SegmentType.SPEECH || seg.type == SegmentType.INNER_MONOLOGUE) {
                val cueBefore = paragraph.normalizedText.substring(lastSpeechEnd, seg.sourceStart)
                val inlineCue = cueOf(cueBefore)
                val inline = inlineCue.speaker
                // 后置 cue（"是我。"钱二答道。 → 钱二）：对白之后到下一句对白前
                val nextSpeechStart = segments.drop(si + 1).firstOrNull { it.type == SegmentType.SPEECH || it.type == SegmentType.INNER_MONOLOGUE }?.sourceStart
                    ?: paragraph.normalizedText.length
                val postCue = cueOf(paragraph.normalizedText.substring(seg.sourceEnd, nextSpeechStart))
                val post = postCue.speaker
                val speaker = inline ?: post ?: if (cueBefore.isBlank()) crossParagraphCue else null
                // M3：cue 位置发现的表演提示（"饶有兴致"/"冷冷地"）——不是人物，转 evidence。
                // ★ 必须挂在【所有分支】上：cue 是 delivery 时 speaker 为 null，正好落到 turnTrack 分支，
                //   如果只在 CONFIRMED 分支挂 cues，这类句子就再也拿不到表演提示了。
                val cues = (inlineCue.delivery + postCue.delivery).distinctBy { it.text }
                val base = when {
                    inline != null -> SpeakerResult(
                        speaker = inline, status = "CONFIRMED", confidence = 0.95,
                        provenance = listOf("EXPLICIT_SPEECH_CUE"), isEasy = true,
                    )
                    post != null -> SpeakerResult(
                        speaker = post, status = "CONFIRMED", confidence = 0.85,
                        provenance = listOf("POSTPOSED_SPEECH_CUE"), isEasy = true,
                    )
                    speaker != null -> SpeakerResult(
                        speaker = speaker, status = "CONFIRMED", confidence = 0.85,
                        provenance = listOf("CROSS_PARAGRAPH_CUE"), isEasy = true,
                    )
                    else -> turnTrack(recentSpeakers)
                }
                results[seg.segmentIndex] = base.copy(deliveryCues = cues)
                lastSpeechEnd = seg.sourceEnd
            }
        }
        // M3：为每个结果标注 gold 型别（规则输出永远不是 gold；只有人工标注才是 MANUAL_GOLD）
        return results.mapValues { (_, r) -> r.copy(goldLabel = GoldPolicy.fromRuleResult(r)) }
    }

    /** cue 提取结果：说话人（可能 null）+ 发现的表演提示（delivery cue）。 */
    private data class CueResult(val speaker: String?, val delivery: List<DeliveryCue>)

    /**
     * 冒号型 cue（**没有说/道动词**）：`雷老虎也失笑的摇了摇头：`
     *
     * 真机实测：《朕真的不务正业》大量使用这种写法，而旧实现只认 CUE_VERB，
     * 于是 `雷老虎`/`刘三刀`（本章最主要的两个说话人）**从未被任何一轮发现**，
     * 他们的 16 段对白全部落成 UNKNOWN，模型只能从"提及"里挑 ⇒ 挑成了 `李寄舟`。
     *
     * 判据：文本（去尾部空白/引号/冒号后）以 `：`/`:` 结尾，且冒号前不是明显非说话子句。
     */
    private fun colonCueClause(textBefore: String): String? {
        var t = textBefore.trimEnd()
        // 允许 ：“ / ：" 这种"冒号+开引号"（对白紧跟在引号里）
        while (t.isNotEmpty() && t.last() in "“”\"「」『』") t = t.dropLast(1).trimEnd()
        if (t.isEmpty() || (t.last() != '：' && t.last() != ':')) return null
        return t.dropLast(1).trimEnd().ifEmpty { null }
    }

    /**
     * 从 cue 子句里解析说话人（**新的第二通道**）。
     *
     * 旧通道只看"动词前最后一个标点块"；真机里大量说话人**不是**那个块：
     * ```
     * 深吸一口气，雷老虎上前一步，拱手抱拳道：   ← 名字在倒数第二个块
     * 雷老虎也失笑的摇了摇头：                  ← 连动词都没有（冒号型）
     * ```
     * 因此这里按块**从右向左**扫描，对每个块做**已知实体子串匹配**（取块内**最靠左**的实体，
     * 因为中文主语在左）。没有实体集时不做任何猜测（不变量 5：禁止强造角色名）。
     *
     * @return 说话人；null = 无法解析
     */
    private fun speakerFromClause(clause: String): String? {
        if (knownEntities.isEmpty()) return null
        val blocks = clause.split(Regex("[，。！？；：、\\s]")).filter { it.isNotBlank() }
        for (b in blocks.asReversed()) {
            // 表演提示块不是说话人（"饶有兴致"/"拱手抱拳"）
            if (DeliveryCueLexicon.isDeliveryCue(b, knownEntities)) continue
            if (NameEvidence.isGroupName(b)) continue
            // 块内取【最靠左】的最长已知实体（主语位置优先）
            var best: String? = null
            var bestPos = Int.MAX_VALUE
            for (e in knownEntities) {
                if (e.length < 2 || e.length > 4) continue
                val pos = b.indexOf(e)
                if (pos < 0) continue
                if (pos < bestPos || (pos == bestPos && e.length > (best?.length ?: 0))) {
                    best = e; bestPos = pos
                }
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * 为**发现阶段**提议新的角色名（第三通道：只提议、不拍板）。
     *
     * 为什么需要：`雷老虎`/`刘三刀` 从未出现在"最后一个块"里，靠 cue 块永远发现不到 ——
     * 而它们又是本章最主要的说话人（自举循环）。这里把 cue 子句**段首**的 2–4 字前缀
     * 作为**提议**交给调用方，由 `NameLegitimacy` 用**严档语料证据**（rightKinds ≥ 10）裁决：
     * ```
     * 雷老虎   rightKinds=34 → 接受      深吸   rightKinds=3 → 拒绝
     * 刘三刀   rightKinds=18 → 接受      上前一步 rightKinds=7 → 拒绝
     * ```
     * 提议档**绝不放宽**，否则长篇里成千上万的段首动词短语会全部涌进来。
     */
    fun proposeSubjects(paragraph: LogicalParagraph): List<String> {
        val t = paragraph.normalizedText
        // 说话 cue 的边界：优先取**第一个冒号之前**（冒号型 / `X道：` / `X道：“…”` 都覆盖），
        // 否则取最后一个 cue 动词之前。提议只是"候选"，最终由严档语料证据裁决。
        val colon = t.indexOfFirst { it == '：' || it == ':' }
        val clause = if (colon >= 0) {
            t.substring(0, colon)
        } else {
            val m = CUE_VERB.findAll(t).lastOrNull() ?: return emptyList()
            t.substring(0, m.range.first)
        }
        if (clause.isBlank()) return emptyList()
        val blocks = clause.split(Regex("[，。！？；：、\\s“”\"「」『』（）()]")).filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        // 只回看**最后 2 个块**（说话人只在 cue 边界附近：`X道：` 的最后块，或 `X 动作道：` 的倒数第二块）。
        // ★ 不要更远的块：`深吸一口气，雷老虎…道：` 里的第一个块会把 `深吸` 也提议出来 ——
        //   实测放宽到 3 块会把接受集从 81 炸到 484。
        // 每块提议 **3–4 字**前缀：2 字前缀在长篇里几乎全是常用词/动宾片段，`rightKinds>=10` 也拦不住。
        // 2 字真名由 cue 块通道负责，不需要提议通道。
        for (b in blocks.takeLast(2).asReversed()) {
            if (b.length < 3 || b.length > 8) continue
            if (b.any { it !in '\u4e00'..'\u9fa5' }) continue
            if (b[0] in PROPOSAL_SKIP_FIRST) continue
            for (len in 3..4) {
                if (len > b.length) break
                out += b.substring(0, len)
            }
        }
        return out.toList()
    }

    /** 显式 cue：取 cue 前最后一个标点块；剥前缀副词/动作短语/叠词后为干净 2-4 字 CJK 才认。 */
    private fun explicitCue(textBefore: String): String? = cueOf(textBefore).speaker

    /**
     * M3 版 cue 提取：除了说话人，还把【表演提示】识别出来。
     * `傅远坐下扫了一眼，饶有兴致道：` ⇒ speaker=null（不再产生"饶有兴致"这个假人名）、
     *                                    delivery=[DELIVERY_CUE("饶有兴致", interested)]
     */
    private fun cueOf(textBefore: String): CueResult {
        val t = textBefore.trim()
        if (t.isEmpty()) return CueResult(null, emptyList())
        val m = CUE_VERB.findAll(t).lastOrNull()
        if (m == null) {
            // ★ 冒号型 cue（无说/道动词）：`雷老虎也失笑的摇了摇头：`
            val clause = colonCueClause(t) ?: return CueResult(null, emptyList())
            val sp = speakerFromClause(clause)
            val cues = DeliveryCueLexicon.classify(clause, knownEntities)?.let { listOf(it) } ?: emptyList()
            return CueResult(sp, cues)
        }
        // 单字 cue 动词（叫/喊/嚷/喝/答/问/说/道）需防动作误判：
        // "惨叫一声"/"惊叫起来" 不是说话 cue——前一字符非 惨/惊/尖，后不接 一声/起来/出声
        val verb = m.value
        if (verb.length == 1) {
            val prev = t.getOrNull(m.range.first - 1)
            val next = t.substring(m.range.last + 1).take(2)
            if (prev != null && prev in "惨惊尖狂吼痛" || next in listOf("一声", "起来", "出声", "着说")) {
                return CueResult(null, emptyList())
            }
        }
        val blocks = t.substring(0, m.range.first).split(Regex("[，。！？；：、\\s]")).filter { it.isNotBlank() }
        var name = blocks.lastOrNull() ?: return CueResult(null, emptyList())
        val rawBlock = name                       // 原始块（可能本身就是提示语）
        val strippedManner = LinkedHashSet<String>()   // 被剥掉的方式副词 = 表演提示
        // 剥动作短语（王老叹了口气，说 → 王老）
        var changed = true
        while (changed) {
            changed = false
            for (p in ACTION_PHRASE) if (name.endsWith(p)) { name = name.removeSuffix(p).trim(); changed = true }
        }
        // 剥前缀副词（孙三娘忽然尖声叫道 → 孙三娘）
        var adv = true
        while (adv) {
            adv = false
            for (p in ADVERB_PREFIX) if (name.startsWith(p)) {
                strippedManner += p; name = name.removePrefix(p).trim(); adv = true
            }
        }
        // 剥叠词 cue（开口说/说道/回答/说/道）及单字 cue 动词（叫/喊/嚷/喝/答/问/应）
        name = name
            .removeSuffix("说道").removeSuffix("开口").removeSuffix("回答")
            .removeSuffix("说").removeSuffix("道")
            .removeSuffix("叫").removeSuffix("喊").removeSuffix("嚷").removeSuffix("喝")
            .removeSuffix("答").removeSuffix("问").removeSuffix("应").trim()
        // 剥残留副词（张三又说 → 张三；李雪轻声说 → 李雪）；★ 剥下来的就是表演提示
        var man = true
        while (man) {
            man = false
            for (a in MANNER_TRAILING) if (name.endsWith(a)) {
                strippedManner += a; name = name.removeSuffix(a).trim(); man = true
            }
        }
        for (a in TRAILING_ADVERB) name = name.removeSuffix(a).trim()

        val clean = name.ifEmpty { null }
        val rawIsName = clean != null && CJK_NAME.matches(clean)
        // 提示语来源：① 剥下来的方式副词 ② 剥离后的残留 ③ 【当且仅当剥离后不成人名时】整块
        val cueSources = LinkedHashSet<String>()
        cueSources += strippedManner
        clean?.let { cueSources += it }
        if (!rawIsName) cueSources += rawBlock
        val cues = cueSources.mapNotNull { DeliveryCueLexicon.classify(it, knownEntities) }
            .distinctBy { it.text }
            .toMutableList()
        // ★ cue 动词自身携带的表演提示（"沉声道"/"淡淡道"/"怒道"）——复合动词不会落进名字块
        DeliveryCueLexicon.classifyVerbStem(verb.removeSuffix("道").removeSuffix("说"))?.let { v ->
            if (cues.none { it.text == v.text }) cues += v
        }

        // ★ 第二通道（新）：最后一个块解析不出人名时，从句中回扫已知实体。
        //   真机实测：《朕真的不务正业》大量写作
        //     `深吸一口气，雷老虎上前一步，拱手抱拳道：`  ← 名字不在最后块
        //     `雷老虎也失笑的摇了摇头：`                    ← 连说/道动词都没有
        //   旧实现只认"动词前最后一个块"⇒ `雷老虎`/`刘三刀` 从未被发现 ⇒ 16 段对白落 UNKNOWN，
        //   模型只能从"提及"里挑，于是挑成了 `李寄舟`（错误归属）。
        //   回扫**只认已知实体**（knownEntities）：没有实体集时绝不猜测（不变量 5）。
        val clauseText = t.substring(0, m.range.first)
        fun fallback(): CueResult = CueResult(speakerFromClause(clauseText), cues)

        if (clean == null) return fallback()
        // ★ M3：残留本身若是表演提示（"饶有兴致"），不许当人名 —— 转提示语
        if (DeliveryCueLexicon.isDeliveryCue(clean, knownEntities)) return fallback()
        // ★ M3：群体称呼不是单人说话人（"众人齐声道" ⇒ 不能成为某个人名；单人 ≠ 群体）
        if (NameEvidence.isGroupName(clean)) return fallback()
        // ★ M3 实体前缀消歧（安全网，必须在长度/停用词检查【之前】）：
        //   动词短语没被词表吃净时块里会残留动词（"曹健开玩"←"曹健开玩笑"、"闫妮追赶"←"闫妮追赶道"）。
        //   若整块【不是】已知实体、而它的某个前缀【是】已知实体，且残留很短 ⇒ 取前缀。
        //   不会误伤长名：真名一旦出现在 cue 里就会被发现并进入 knownEntities，
        //   于是不再满足"不是已知实体"这一前提。
        val base: String = clean
        var resolved: String = base
        if (knownEntities.isNotEmpty() && base !in knownEntities && base.length >= 3) {
            // (a) 前缀消歧：动词残留粘在【后】（"曹健开玩"←"曹健开玩笑"）
            val pref = knownEntities
                .filter { base.startsWith(it) && it.length >= 2 && it.length < base.length }
                .maxByOrNull { it.length }
            if (pref != null && base.length - pref.length <= 2) resolved = pref
            // (b) 后缀消歧：介词/动作残留粘在【前】（"对闫呢"←"对闫呢道"、"指着甜妹"←"指着甜妹道"）
            //     仅当残留本身是明确的引语介词/动作词时才启用（避免"小王明"被误截成"王明"）
            if (resolved == base) {
                for (p in LEADING_CUE_PARTICLE) {
                    if (!base.startsWith(p)) continue
                    val rest = base.removePrefix(p)
                    if (rest in knownEntities) { resolved = rest; break }
                }
            }
        }
        if (!CJK_NAME.matches(resolved) || resolved in STOPLIST) return fallback()
        // TASK-100 候选污染修复：含代词 → 无显式名（"她还是劝道"的主语是"她"不是"她还是劝"）
        if (resolved.any { it in PRONOUN_CHARS }) return fallback()
        // 动作短语结尾（推开门/站起来/点点头/看了看/叹了口气…）不是人名——宁缺毋滥
        if (ACTION_SUFFIX.any { resolved.endsWith(it) }) return fallback()
        // ★ 关键排序（真机事故）：**cue 子句里的已知实体优先于"块派生的未知表面"**。
        //   `深吸一口气，雷老虎上前一步，拱手抱拳道：` 的最后一块是 `拱手抱拳`（4 字 CJK，形态检查全过），
        //   于是旧实现把"拱手抱拳"当说话人、却放过了子句里真正的 `雷老虎` —— 结果 `雷老虎` 全书 42 次
        //   从未被任何一轮发现，他的对白全部落 UNKNOWN（或让模型从"提及"里乱挑）。
        if (knownEntities.isNotEmpty() && resolved !in knownEntities) {
            speakerFromClause(clauseText)?.let { return CueResult(it, cues) }
        }
        return CueResult(resolved, cues)
    }

    /** 跨段 cue（G4）：段落末尾 "张三说道：" → "张三"。供 DatasetExporter/上下文链接用。 */
    fun extractTrailingCue(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val m = CUE_VERB.findAll(t).lastOrNull() ?: return null
        // 动词须在末尾附近（末尾可带冒号/引号闭合/空白）
        val tail = t.substring(m.range.last + 1).trim()
            .removeSuffix("：").removeSuffix(":")
            .removeSuffix("”").removeSuffix("」").removeSuffix("』")
        if (tail.isNotEmpty()) return null
        return explicitCue(t.substring(0, m.range.last + 1))
    }

    /** 轮换追踪：近 2 名不同说话人 → 交替（取非最近者）；仅 1 人 → 延续；否则 UNKNOWN。 */
    private fun turnTrack(recent: List<String>): SpeakerResult {
        val distinct = recent.distinct()
        return when {
            distinct.size >= 2 -> SpeakerResult(
                distinct.first { it != recent.first() }, "PROVISIONAL", 0.5, listOf("TURN_TRACKING"), false,
            )
            distinct.size == 1 -> SpeakerResult(
                distinct.first(), "PROVISIONAL", 0.5, listOf("TURN_TRACKING"), false,
            )
            else -> SpeakerResult(null, "UNKNOWN", 0.0, listOf("UNKNOWN"), false)
        }
    }
}
