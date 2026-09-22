package com.readervoice.data.semantic

/**
 * MOBILE-005 / CH-0 质量修复 ①：**候选人名合法性闸门**。
 *
 * ## 要解决的问题（真机实测）
 *
 * `character_director/characters_<bookId>.json` 是「角色发现」的输出，它同时决定
 * **候选 C#** 与 **delivery cue 安全阀**（`knownEntities` 命中即不许判为 cue）。
 * 真机实测（《朕真的不务正业》4.3MB）：发现 371 个"名字"，其中约 80% 是 cue 子句切片：
 *
 * ```
 * 李寄舟询 / 张三丰知 / 郭襄再度 / 黑心虎质 / 师妃暄便 / 泥菩萨哂 / 雄霸一边 …
 * 倘若 / 因为 / 所以 / 只是 / 同时 / 可以 / 最终 / 立马 / 陡然 / 招呼 / 抱拳 / 哈哈 …
 * ```
 *
 * 于是模型在**被污染的候选里作答**：协议全绿（anchor/coverage/ACCEPTED_ALL 都对），
 * 说话人却是"哈哈"（真机 20 段里 6 段）——**协议合规 ≠ 剧本可用**（ADR-055）。
 *
 * 根因不是"词表不够长"，而是发现阶段**从不要求语料证据**：
 * cue 提取只取「cue 动词前最后一个标点块」，剥掉有限的词表后缀后，只要剩 2–4 个汉字
 * 就当人名。第二轮 pass 又把这些碎片当作 `knownEntities` 反过来当安全阀护住（自我固化）。
 *
 * ## 闸门的两类判据
 *
 * **① 语料证据（book-wide）—— 这是关键**
 * 真正的名字会在 **cue 之外**、以 **多种右邻上下文** 反复出现：
 * `李寄舟的 / 李寄舟和 / 李寄舟，/ 看着李寄舟 / 李寄舟是`…
 * 而 cue 切片只在 cue 动词紧邻处存在（`李寄舟询` 每次后面都是 `问/道`）。
 * 实测分离度（371 候选，73 手工标注真名）：
 * ```
 * rightKinds（不同右邻字符数）>= 3  →  真名 73/73 保留，污染 170/298 拦下
 * ```
 * 真名最小值 = 3（李继洲），污染最大值的分布完全重叠在 total 上 —— 所以**必须用
 * "右邻多样性"而不是"出现次数"**：`这一` 出现 1080 次、`所以` 898 次，都是碎片。
 *
 * **② 封闭类形态**
 * 叹词/拟声、虚词副词连词、常见动作短语、介词/指示词起首、结尾虚词字
 * （**称谓后缀豁免**：`风陵师太` 的"太"、"弘心首座"的心/首座都不是虚词）、
 * 以及 **`真名 + 1~2 字残留`**（`李寄舟询` ⇒ 残留 `询`）——残留优先归并到真名。
 *
 * ## 纪律
 * - **宁缺毋滥**（不变量 5：UNKNOWN 是合法结果，禁止强造角色名）。
 *   一个只出现过 2 次、每次都在 cue 里的名字会**被降级为 UNKNOWN** —— 这是可接受的代价。
 * - 被否决的表面**不是被丢弃**：它作为 delivery cue（表演提示）进入 evidence。
 * - `hardReject` 与语料无关，可以在**候选编译期**无条件使用（防旧缓存/外部注入的污染）。
 *
 * 离线评估脚本：`tools/mobile005/name_gate_probe.js`（含 MANUAL_GOLD 与逐条拒因统计）。
 */
data class SurfaceEvidence(
    /** 全文出现次数。 */
    val total: Int,
    /** 紧跟说话 cue 动词（`说/道/问/答/…`）的次数 —— cue 切片会接近 total。 */
    val cueBound: Int,
    /** **不同**右邻字符数 —— 真名的核心证据（实测阈值 3）。 */
    val rightKinds: Int,
    /** 左邻是句读/引号且右邻不是 cue 动词的次数（"独立短语"证据，实测阈值 1）。 */
    val independentCount: Int,
) {
    /** cue 之外的出现次数。 */
    val free: Int get() = total - cueBound

    /**
     * 语料证据是否足以支撑"这是一个真名"。
     *
     * @param strict 是否使用**严档**（`rightKinds >= 10`）。
     *   · **标准档**（cue 块直接抽出的表面）：`rightKinds >= 3`
     *   · **严档**（从句中"提议"的新名字：段首 2–4 字前缀）：`rightKinds >= 10`
     *
     * 为什么必须分档（真机实测，`《朕真的不务正业》`）：
     * ```
     * 雷老虎   total=42  rightKinds=34  indep=15   ← 真名（严档过）
     * 刘三刀   total=22  rightKinds=18  indep= 9   ← 真名（严档过）
     * 深吸     total=98  rightKinds= 3  indep=15   ← 动词短语（标准档会漏过！）
     * 上前一步 total=29  rightKinds= 7  indep= 4   ← 碎片（严档拦下）
     * 摇了摇头 total=89  rightKinds= 5  indep= 3   ← 碎片（严档拦下）
     * ```
     * 提议档不能放松：长篇小说里"段首动词短语"数量巨大，标准档会把它们全放进来。
     */
    fun supportsName(strict: Boolean = false): Boolean =
        rightKinds >= (if (strict) STRICT_RIGHT_KINDS else MIN_RIGHT_KINDS) &&
            independentCount >= MIN_INDEPENDENT

    companion object {
        const val MIN_RIGHT_KINDS = 3

        /** 严档阈值（真机实测分离点：真名最小 12，碎片最大 7 ⇒ 10 留出余量）。 */
        const val STRICT_RIGHT_KINDS = 10
        const val MIN_INDEPENDENT = 1
        val UNKNOWN = SurfaceEvidence(0, 0, 0, 0)
    }
}

/** 否决原因（可审计；`NAME_PLUS_RESIDUE` 额外记录被归并到的真名）。 */
enum class NameReject {
    /** 叹词/拟声（哈哈、哎呀）—— 绝不可能是人名。 */
    INTERJECTION,

    /** 虚词/副词/连词（倘若、因为、所以、只是）—— 封闭类。 */
    FUNCTION_WORD,

    /** 常见动作/身体短语（抱拳、躬身一礼、拍掌）。 */
    ACTION_WORD,

    /** 介词/使役起首（为李寄舟、被张三）。 */
    PREP_PREFIX,

    /** 指示/疑问/代词起首（这位婶娘、这两个字）。 */
    DEMON_PREFIX,

    /** 语料证据不足：只在 cue 位置出现（李寄舟询、张三丰知）。 */
    CUE_ONLY,

    /** 语料证据不足：从未以独立短语出现。 */
    NO_INDEPENDENT,

    /** 结尾是虚词/动词字（不剥称谓后缀）。 */
    BAD_FINAL,

    /** 真名 + 1~2 字残留 ⇒ 归并到真名。 */
    NAME_PLUS_RESIDUE,
}

/** 闸门判决。 */
data class NameVerdict(
    val accepted: Boolean,
    val reject: NameReject?,
    /** `NAME_PLUS_RESIDUE` 时：被归并到的真名。 */
    val residueOf: String? = null,
    val evidence: SurfaceEvidence? = null,
)

object NameLegitimacy {

    /** 闸门版本：进入缓存键，旧缓存（未过闸门）一律失效重算。 */
    const val GATE_VERSION = 1

    /** 说话 cue 动词**首字**：表面紧邻其后 ⇒ 该次出现是 cue 位置。 */
    private val CUE_HEADS = "说道问答喊叫嚷喝斥诉询诘".toSet()

    /** 句读/引号：左侧"独立短语"证据。 */
    private val PUNCT = "，。！？；：、\n“”\"『』「」（）() \t".toSet()

    /**
     * 虚词/副词/连词/高频谓词短语：封闭类，几乎不可能作为人名（整词比对）。
     * 全部来自真机污染的**逐个复核**（见 name_gate_probe.js 的逐条拒因）。
     */
    private val FUNCTION_WORDS = setOf(
        // 连词/副词/介词性
        "倘若", "如实", "因为", "所以", "如果", "那么", "故此", "继而", "要知", "倒不如",
        "然而", "虽然", "唯有", "只是", "而是", "并且", "同时", "可以", "应该", "任何",
        "如此", "这份", "当年", "如今", "最终", "率先", "立马", "立时", "登时", "陡然",
        "好似", "再度", "兀自", "主动", "平淡", "畅快", "突兀", "艰难", "欣慰", "果断",
        "肆意", "豪言", "开怀", "喜好", "保证", "打断", "反驳", "求情", "安慰", "破功",
        "怒斥", "娓娓", "应和", "低语", "释然", "肃然", "傲然", "昂然", "娇嗔",
        "这些", "这种", "那种", "这样", "那样", "什么", "怎么", "就是", "还是", "但是",
        "而且", "然后", "于是", "因此", "已经", "曾经", "正在", "立刻", "马上", "终于",
        "忽然", "突然", "当然", "显然", "其实", "确实", "简直", "几乎", "大概", "也许",
        "或者", "不过", "只有", "还要", "一边", "一面", "一声", "一句", "一手", "一眼",
        "一步", "一阵", "一番", "一场", "一时", "一头", "一起", "一同", "一直", "一定",
        "一样", "一般", "一切", "一些", "一点", "一下",
        // 语料实测补（残留谓词/副词；均为封闭类或高频动词短语，不含姓名用字）
        "不知", "不屑", "就连", "忍不住", "颇有些", "招呼", "当先", "打发", "打听",
        "打算", "打量", "应酬", "寒暄", "见礼", "点头", "摇头", "挥手", "抬手", "伸手",
        "转身", "起身", "抬头", "低头", "回首", "回头", "沉吟", "迟疑", "犹豫", "叹息",
        "叹气", "苦笑", "冷笑", "微笑", "大笑", "冷哼",
        // 长篇/多字残留
        "一字一句", "毛遂自荐", "指点江山", "声音沙哑", "神色僵硬", "意兴阑珊",
        "这两个字", "这位婶娘", "完全可以", "好似是在", "生怕自己", "将两",
        "见一", "还有一", "只有一", "并且一", "然而一", "可这一", "这样一", "那样一",
    )

    /** 常见动作/身体短语：作为整体不是人名。 */
    private val ACTION_WORDS = setOf(
        "拱手抱拳", "躬身抱拳", "躬身一礼", "躬身拜", "拱手执礼", "拱手鞠躬", "抱拳",
        "鞠躬", "拍掌", "拍手称赞", "拜见", "叩首", "诚心叩首", "颔首", "皱眉", "闭目",
        "咬着牙", "冷着脸", "满不在意", "直接了当", "轻松写意", "喜极而泣", "故作不知",
        "故作可惜", "故作调", "连连保证", "立马出列", "开口道歉", "开口询", "上前询",
        "接着询", "仔细询", "立即询", "转而询", "随即询", "当即询",
        // 跨书/真机实测补：常见"动作短语"作为整体不是人名（`拱手抱拳道：` 的最后一块就是它）
        "拱手", "深吸", "摇头", "点头", "上前", "起身", "转身", "抬头", "低头", "回头",
        "挥手", "抬手", "伸手", "叹气", "叹息", "沉吟", "皱眉", "失笑", "冷笑", "微笑",
    )

    /** 称谓后缀：1~2 字残留若是称谓则**不得**当作残留剥掉（空闻**大师**、了空**禅师**）。 */
    private val TITLE_SUFFIX = setOf(
        "大师", "禅师", "师太", "仙子", "娘娘", "先生", "前辈", "长老", "掌门", "真人",
        "道人", "首座", "圣姑", "教主", "姑娘", "夫人", "公子", "小姐", "丫头", "大爷",
        "婆婆", "姑姑", "哥哥", "姐姐", "妹妹", "兄弟", "阁下", "陛下", "殿下", "师兄",
        "师弟", "师姐", "师妹", "师父", "师傅", "老人", "老者",
    )

    /**
     * 结尾虚词/动词字。
     * ★ 不得包含**可在真名末字出现**的字：实测 `弘心首座` 被 "心" 误杀、`莫将` 被 "将" 误杀、
     *   `渡法`（少林渡字辈法号）被 "法" 误杀 —— 三例都已在跨书验证中回滚。
     */
    private val BAD_FINAL = (
        "的了着过但是而如果那么就还也都很太更最把被使让给对向从与或则乃依旧竟却已未不没非常更加" +
            "话事人时后前中里外上下起身手眼口头声气意思情语笑掌拳躬拜别答一动完好象样点面边间处算加"
        ).toSet()

    /** 介词/使役/被动起首（"为李寄舟""被张三"）。 */
    private val PREP_PREFIX = "为被把让使给替由".toSet()

    /** 指示/疑问/代词起首（"这位婶娘""这两个字"）。 */
    private val DEMON_PREFIX = "这那其此该某怎多么".toSet()

    /** 程度/范围副词起首（"很好听""最要紧"）—— 跨书验证在长篇里发现的主要残留类。 */
    private val DEGREE_PREFIX = "很挺更最极特超蛮".toSet()

    /** 数词/量词起首（"一脸""两人"）。中文人名极少以"一/两"起首，宁缺毋滥。 */
    private val NUMERAL_PREFIX = "一两".toSet()

    /** 身体/方位/心理部位短语（"仰脖子""心头一动""脸上"）—— 描述动作，不是人名。 */
    private val BODY_CONTEXT = setOf(
        "脖子", "脑袋", "眼睛", "嘴巴", "手指", "手臂", "肩膀", "胳膊", "脸颊", "额头",
        "眉毛", "鼻子", "耳朵", "心头", "心中", "心里", "身上", "脸上", "手里", "眼中",
        "眼里", "嘴里", "脚下", "眼下", "背后", "身边", "面前", "眼前",
    )

    /**
     * **虚词前缀**：表面以连词/副词起首 ⇒ 整词不是人名。
     * 实测：`虽然不知`（= 虽然 + 不知，两个虚词拼起来的 cue 切片）右邻字符 4 种、
     * 有独立出现位置，形态判据抓不住 —— 必须靠这一条。
     * 只收连词/副词（**不收**动作短语），避免与真名用字冲突。
     */
    private val FUNCTION_PREFIXES = setOf(
        "虽然", "因为", "所以", "倘若", "如果", "那么", "于是", "因此", "然而", "但是",
        "而且", "并且", "同时", "可以", "应该", "任何", "如此", "只是", "只有", "无论",
        "不管", "即使", "即便", "仍然", "依然", "忽然", "突然", "果然", "当然", "显然",
        "其实", "确实", "简直", "几乎", "大概", "也许", "或者", "不过", "一旦", "直到",
        "随即", "立刻", "马上", "终于", "一直", "再度", "再次", "十分", "非常", "尤其",
        "渐渐", "逐渐", "勉强", "不禁", "顿时", "登时", "陡然", "索性", "干脆", "偏偏",
        "恰好", "幸亏", "幸好", "好在", "难道", "不妨", "除非", "唯有", "只要", "万一",
        "若是", "假如", "就算", "哪怕", "纵使", "纵然", "除了", "关于", "由于", "为了",
        "反而", "反倒", "甚至", "不仅", "不但", "况且", "何况", "至于", "从而", "进而",
    )

    // ── 与语料无关的绝对否决（候选编译期也可用）────────────────────────

    /**
     * 硬否决：与全文语料**无关**的判据。可在任何地方无条件调用，
     * 即使该表面出现在（陈旧的）`knownEntities` 里也不得放行。
     * @return null = 未被硬否决
     */
    fun hardReject(surface: String): NameReject? {
        val s = surface.trim()
        if (DeliveryCueLexicon.isInterjection(s)) return NameReject.INTERJECTION
        if (s.length < 2) return NameReject.BAD_FINAL
        closedClassReject(s)?.let { return it }
        if (badFinal(s)) return NameReject.BAD_FINAL
        return null
    }

    /**
     * **封闭类**否决：词表级、与语料无关、**在任何场景下都可安全使用**的判据。
     *
     * 与 [hardReject] 的区别：不含 `BAD_FINAL` 形态判据 —— 后者虽然在本项目真机语料上
     * 73/73 真名零误伤，但它是**语料统计结论**，用在"已有干净实体集"的候选编译期属于多余风险。
     * 候选编译期只需要拦住"封闭类词绝不可能是人名"这一条（防旧缓存/外部注入）。
     */
    fun closedClassReject(surface: String): NameReject? {
        val s = surface.trim()
        if (DeliveryCueLexicon.isInterjection(s)) return NameReject.INTERJECTION
        if (s.length < 2) return NameReject.BAD_FINAL
        if (s in FUNCTION_WORDS) return NameReject.FUNCTION_WORD
        if (s in ACTION_WORDS) return NameReject.ACTION_WORD
        if (s[0] in PREP_PREFIX) return NameReject.PREP_PREFIX
        if (s[0] in DEMON_PREFIX) return NameReject.DEMON_PREFIX
        if (s[0] in DEGREE_PREFIX || s[0] in NUMERAL_PREFIX) return NameReject.FUNCTION_WORD
        if (BODY_CONTEXT.any { it in s }) return NameReject.ACTION_WORD
        return null
    }

    private fun badFinal(s: String): Boolean {
        var stem = s
        for (t in TITLE_SUFFIX) {
            if (stem.length > t.length && stem.endsWith(t)) { stem = stem.dropLast(t.length); break }
        }
        return stem.lastOrNull() in BAD_FINAL
    }

    // ── 语料证据 ───────────────────────────────────────────────────────

    /**
     * 一次全文扫描，为所有候选表面算证据。
     *
     * 性能：4.3MB 正文 × 371 候选，若逐候选 `indexOf` 是 1.6G 次字符比较。
     * 这里用**滚动哈希**按长度分桶：5 遍 O(n) 扫描，只有哈希命中才做真实比较，
     * 因此不产生百万级临时字符串（Android 上 GC 压力可接受）。
     */
    fun corpusEvidence(text: CharSequence, surfaces: Collection<String>): Map<String, SurfaceEvidence> {
        val byLen = HashMap<Int, HashMap<Long, MutableList<String>>>()
        for (s in surfaces) {
            if (s.length !in MIN_LEN..MAX_LEN) continue
            byLen.getOrPut(s.length) { HashMap() }
                .getOrPut(hashOf(s, 0, s.length)) { ArrayList(2) }
                .add(s)
        }
        val total = HashMap<String, Int>()
        val cue = HashMap<String, Int>()
        val indep = HashMap<String, Int>()
        val right = HashMap<String, HashSet<Char>>()
        for ((len, table) in byLen) {
            val n = text.length
            if (n < len) continue
            var h = hashOf(text, 0, len)
            var i = 0
            while (i + len <= n) {
                if (i > 0) {
                    h = h * BASE + text[i + len - 1].code - text[i - 1].code * POW[len]
                }
                val bucket = table[h]
                if (bucket != null) {
                    for (c in bucket) {
                        if (!matchesAt(text, i, c)) continue
                        total[c] = (total[c] ?: 0) + 1
                        val r = if (i + len < n) text[i + len] else '\n'
                        if (r in CUE_HEADS) cue[c] = (cue[c] ?: 0) + 1
                        right.getOrPut(c) { HashSet(4) }.add(r)
                        val l = if (i > 0) text[i - 1] else '\n'
                        if (l in PUNCT && r !in CUE_HEADS) indep[c] = (indep[c] ?: 0) + 1
                    }
                }
                i++
            }
        }
        val out = HashMap<String, SurfaceEvidence>(total.size * 2)
        for (s in surfaces) {
            val t = total[s] ?: 0
            out[s] = if (t == 0) {
                SurfaceEvidence.UNKNOWN
            } else {
                SurfaceEvidence(t, cue[s] ?: 0, right[s]?.size ?: 0, indep[s] ?: 0)
            }
        }
        return out
    }

    private const val MIN_LEN = 2
    private const val MAX_LEN = 6
    private const val BASE = 131L
    private val POW = LongArray(MAX_LEN + 1).also {
        it[0] = 1L
        for (k in 1..MAX_LEN) it[k] = it[k - 1] * BASE
    }

    private fun hashOf(s: CharSequence, from: Int, len: Int): Long {
        var h = 0L
        for (k in 0 until len) h = h * BASE + s[from + k].code
        return h
    }

    private fun matchesAt(text: CharSequence, i: Int, c: String): Boolean {
        if (i + c.length > text.length) return false
        for (k in c.indices) if (text[i + k] != c[k]) return false
        return true
    }

    // ── 判决 ──────────────────────────────────────────────────────────

    /**
     * 闸门判决（顺序即优先级，第一个命中即否决）。
     *
     * @param legit 已接受的合法名字集合（随接受过程增长）——
     *        用于 `NAME_PLUS_RESIDUE`：`李寄舟询` 只有在 `李寄舟` 已被接受后才会被归并。
     * @param strict 对该表面使用**严档**语料证据（从句中"提议"的新名字必须过严档，
     *        防止长篇里"段首动词短语"混进来 —— 见 [SurfaceEvidence.supportsName]）。
     */
    fun evaluate(
        surface: String,
        evidence: SurfaceEvidence?,
        legit: Set<String>,
        strict: Boolean = false,
    ): NameVerdict {
        val s = surface.trim()
        hardReject(s)?.let { return NameVerdict(false, it, evidence = evidence) }
        // ★ 虚词前缀（"虽然不知" = 虽然 + 不知）——形态与语料证据都抓不住的 cue 切片
        if (branchPrefix(s) != null) return NameVerdict(false, NameReject.FUNCTION_WORD, evidence = evidence)
        // ★ 真名 + 1~2 字残留 ⇒ 归并到真名（"李寄舟询" ⇒ "李寄舟"、"师妃暄便" ⇒ "师妃暄"）
        //   放在语料判据之前：它的**拒因更可审计**（告诉你归并到谁），而且语义上先于"是不是名字"。
        for (p in legit) {
            if (p.length < 2 || !s.startsWith(p) || s.length <= p.length) continue
            val rest = s.substring(p.length)
            if (rest.length <= 2 && rest !in TITLE_SUFFIX) {
                return NameVerdict(false, NameReject.NAME_PLUS_RESIDUE, residueOf = p, evidence = evidence)
            }
        }
        val ev = evidence ?: SurfaceEvidence.UNKNOWN
        if (ev.total == 0) return NameVerdict(false, NameReject.CUE_ONLY, evidence = ev)
        if (ev.rightKinds < if (strict) SurfaceEvidence.STRICT_RIGHT_KINDS else SurfaceEvidence.MIN_RIGHT_KINDS) {
            return NameVerdict(false, NameReject.CUE_ONLY, evidence = ev)
        }
        if (ev.independentCount < SurfaceEvidence.MIN_INDEPENDENT) {
            return NameVerdict(false, NameReject.NO_INDEPENDENT, evidence = ev)
        }
        return NameVerdict(true, null, evidence = ev)
    }

    /** 表面是否以虚词起首（留出 ≥2 字余量，避免吞掉短名）。 */
    private fun branchPrefix(s: String): String? {
        for (len in 2..3) {
            if (s.length - len < 2) break
            val p = s.substring(0, len)
            if (p in FUNCTION_PREFIXES) return p
        }
        return null
    }
}
