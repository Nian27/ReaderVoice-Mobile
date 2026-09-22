package com.readervoice.data.semantic

/**
 * MOBILE-005 / M3 步骤 1：**delivery cue（表演提示）与人物 mention 的分野**。
 *
 * 问题（真实书实测）：`傅远坐下扫了一眼，饶有兴致道：` 里，cue 提取会把 `饶有兴致` 当成说话人
 * （2-4 字 CJK、不在 STOPLIST、无代词、无动作后缀）⇒ 同时污染 **候选** 与 **gold**，
 * 于是出现"模型答 饶有兴致 / gold 也是 饶有兴致 / 准确率 100% / 产品结果荒谬"。
 *
 * 判定：`饶有兴致 / 冷冷地 / 不耐烦地 / 缓缓 / 沉声` 本质是 **delivery/emotion cue**，
 * 不是人物 mention。它们应当：
 *   · 不进入候选人（不占 C# 位、不可能是 gold）
 *   · 进入 **evidence**（type = DELIVERY_CUE + emotion_hint），反而成为导演表演的有效证据
 *
 * 安全阀（宁缺毋滥，绝不吃掉真人名）：
 *   1. `knownEntities` 命中 ⇒ 一律当人名（实体集优先于任何形态规则）
 *   2. 判定只认【高精度形态】：地/然 结尾、明确的方式-情绪词根、显式四字状语短语表
 *   3. 2 字表面只接受无歧义词根（避免"李喜/张笑"这类人名被误吃）
 */
enum class CueKind { DELIVERY_CUE, SPEAKER_CUE }

data class DeliveryCue(
    val text: String,
    val kind: CueKind,
    val emotionHint: String?,
    /** 命中的规则（可审计：LEXICON / SUFFIX / STEM / PHRASE） */
    val matchedBy: String,
)

object DeliveryCueLexicon {

    /** 地/然 结尾的方式状语（冷冷地 / 淡淡地 / 漠然 / 悠然）。 */
    private val ADVERBIAL_SUFFIX = listOf("地", "然")

    /** 显式四字状语短语表（cue 前置、常见于网文）。 */
    private val PHRASES = setOf(
        "饶有兴致", "若有所思", "意味深长", "不动声色", "不紧不慢", "一字一顿", "慢条斯理",
        "兴致勃勃", "似笑非笑", "咬牙切齿", "语重心长", "漫不经心", "心不在焉", "不假思索",
        "阴阳怪气", "郑重其事", "一本正经", "小心翼翼", "幸灾乐祸", "无可奈何", "气急败坏",
        "眉开眼笑", "愁眉苦脸", "如释重负", "不以为然", "嗤之以鼻", "恍然大悟", "若有深意",
    )

    /** 明确的方式/情绪词根（可在 2-4 字表面中作为子串出现）。 */
    private val STEMS = setOf(
        "沉声", "低声", "轻声", "厉声", "高声", "大声", "小声", "冷声", "柔声", "寒声",
        "冷冷", "淡淡", "缓缓", "徐徐", "悠悠", "幽幽", "悻悻", "狠狠", "恨恨", "愤愤",
        "苦笑", "冷笑", "嗤笑", "轻笑", "微笑", "干笑", "狞笑", "冷哼", "怒哼",
        "沉吟", "迟疑", "犹豫", "兴奋", "得意", "尴尬", "温柔", "平静", "认真", "郑重",
        "好奇", "疑惑", "惊讶", "吃惊", "叹息", "叹气", "咬牙", "皱眉", "点头", "摇头",
        "无奈", "黯然", "茫然", "愕然", "默然", "欣然", "坦然", "恍然", "悠然", "漠然", "淡然",
    )

    /** 2 字表面只接受这些（无歧义、几乎不可能是人名）。 */
    private val SAFE_2CHAR_STEMS = setOf(
        "沉声", "低声", "轻声", "厉声", "高声", "小声", "冷声", "柔声",
        "冷冷", "淡淡", "缓缓", "徐徐", "悠悠", "幽幽", "悻悻", "狠狠",
        "苦笑", "冷笑", "轻笑", "干笑", "冷哼", "沉吟", "迟疑", "犹豫",
        "兴奋", "得意", "尴尬", "温柔", "平静", "认真", "郑重", "好奇",
        "疑惑", "惊讶", "吃惊", "叹息", "叹气", "咬牙", "皱眉", "无奈",
        "黯然", "茫然", "愕然", "默然", "欣然", "坦然", "恍然", "悠然", "漠然", "淡然",
    )

    /** 词根 → emotion hint（用于 evidence.emotion_hint，供 Director 参考）。 */
    private val HINTS: List<Pair<String, String>> = listOf(
        "饶有兴致" to "interested", "兴致勃勃" to "interested", "好奇" to "curious",
        "冷笑" to "sardonic", "嗤笑" to "sardonic", "似笑非笑" to "sardonic", "阴阳怪气" to "sardonic",
        "苦笑" to "bitter", "无奈" to "resigned", "无可奈何" to "resigned", "如释重负" to "relieved",
        "怒" to "angry", "愤" to "angry", "咬牙" to "angry", "气急败坏" to "angry", "咬牙切齿" to "angry",
        "不耐烦" to "impatient", "急切" to "urgent", "急促" to "urgent", "急忙" to "urgent",
        "不悦" to "annoyed", "不满" to "annoyed", "不屑" to "disdainful", "不以为然" to "dismissive",
        "叹" to "weary", "黯然" to "sad", "悲" to "sad", "愁" to "sad",
        "惊讶" to "surprised", "吃惊" to "surprised", "愕然" to "surprised",
        "疑惑" to "uncertain", "迟疑" to "uncertain", "犹豫" to "uncertain", "茫然" to "uncertain",
        "温柔" to "gentle", "轻声" to "gentle", "柔声" to "gentle", "柔" to "gentle",
        "郑重" to "serious", "认真" to "serious", "一本正经" to "serious", "语重心长" to "serious",
        "沉声" to "grave", "厉声" to "stern", "高声" to "loud", "大声" to "loud", "小声" to "quiet",
        "低声" to "quiet", "得意" to "smug", "幸灾乐祸" to "smug",
        "冷冷" to "cold", "冷声" to "cold", "寒" to "cold", "淡漠" to "cold", "漠然" to "cold",
        "平静" to "calm", "淡淡" to "calm", "淡然" to "calm", "坦然" to "calm", "缓缓" to "steady",
        "徐徐" to "steady", "悠悠" to "steady", "幽幽" to "steady", "兴奋" to "excited",
        "尴尬" to "awkward", "心不在焉" to "distracted", "若有所思" to "thoughtful",
        "意味深长" to "meaningful", "慢条斯理" to "unhurried", "一字一顿" to "emphatic",
    )

    /** "边…边…" 类方式状语（边跑边说道 / 边走边说）——描述怎么说话，不是人名。 */
    private val PARALLEL_ADVERBIAL = Regex("^边.{1,2}边$")

    /**
     * MOBILE-005 / CH-0 质量修复 ①：**叹词/拟声表**。
     *
     * 真机实测：`“哈？！…”` / `哈哈道：` 让 discovery 把 `哈哈` 收成"角色名"，
     * 于是 20 段剧本里 6 段的说话人是"哈哈"。
     *
     * ★ 这是**封闭类绝对判据**：任何一部小说里都不存在名叫"哈哈/哎呀/嗯"的人。
     *   因此它必须排在 `knownEntities` 安全阀**之前** —— 否则一旦污染进了实体集
     *   （旧缓存 / 上游噪声），安全阀会反过来把污染永久锁死。
     */
    val INTERJECTIONS = setOf(
        "哈哈", "呵呵", "嘿嘿", "嘻嘻", "哎呀", "哎哟", "咦", "唉", "嗯", "哦", "噢",
        "嘿", "喂", "呸", "哇", "喔", "呦", "哼", "嘁", "切", "啊", "呀", "哈", "嘻", "嘎", "噗",
    )

    /** 叹词/拟声 → emotion_hint（供 evidence 使用）。 */
    private val INTERJECTION_HINTS = mapOf(
        "哈哈" to "amused", "呵呵" to "sardonic", "嘿嘿" to "sly", "嘻嘻" to "amused",
        "哼" to "sardonic", "唉" to "weary", "哎呀" to "surprised", "哎哟" to "pained",
        "咦" to "curious", "嗯" to "assent", "哦" to "realizing", "啊" to "surprised",
        "哇" to "admiring", "呸" to "contemptuous",
    )

    fun isInterjection(surface: String): Boolean = surface.trim() in INTERJECTIONS

    /** 是否 delivery cue（不是人名）。knownEntities 命中时一律 false（人名优先）。
     *  ★ 例外：叹词/拟声是**绝对**判据，不受实体集保护。 */
    fun isDeliveryCue(surface: String, knownEntities: Set<String> = emptySet()): Boolean =
        classify(surface, knownEntities) != null

    /** 判定并给出可审计的命中原因；null = 不是 delivery cue。 */
    fun classify(surface: String, knownEntities: Set<String> = emptySet()): DeliveryCue? {
        val s = surface.trim()
        if (s.isEmpty() || s == "UNKNOWN") return null
        // ★ 绝对判据（先于实体集安全阀）：叹词/拟声永远不是人名
        if (s in INTERJECTIONS) {
            return DeliveryCue(s, CueKind.DELIVERY_CUE, INTERJECTION_HINTS[s] ?: "interjection", "INTERJECTION")
        }
        if (s in knownEntities) return null                       // ★ 安全阀：实体集优先
        if (PARALLEL_ADVERBIAL.matches(s)) return DeliveryCue(s, CueKind.DELIVERY_CUE, "hurried", "PARALLEL")
        if (s.length !in 2..6) return null

        if (s in PHRASES) return DeliveryCue(s, CueKind.DELIVERY_CUE, hint(s), "PHRASE")
        if (ADVERBIAL_SUFFIX.any { s.endsWith(it) } && s.length >= 3) {
            return DeliveryCue(s, CueKind.DELIVERY_CUE, hint(s), "SUFFIX")
        }
        if (s.length == 2) {
            return if (s in SAFE_2CHAR_STEMS) DeliveryCue(s, CueKind.DELIVERY_CUE, hint(s), "STEM") else null
        }
        val stem = STEMS.firstOrNull { s.contains(it) }
        if (stem != null) return DeliveryCue(s, CueKind.DELIVERY_CUE, hint(s), "STEM")
        return null
    }

    fun hint(surface: String): String? = HINTS.firstOrNull { surface.contains(it.first) }?.second

    /**
     * cue **动词**本身携带的表演提示（"沉声道"/"低声道"/"淡淡道"/"怒道"）。
     * 这些是 CUE_VERB 复合词，不会落进"名字块"，所以单独识别。
     * 单字情绪动词（怒/笑/叹/哭/哼）在动词位置是安全的（不会与姓名混淆）。
     */
    fun classifyVerbStem(stem: String): DeliveryCue? {
        val s = stem.trim()
        if (s.length >= 2) return classify(s)
        val h = SINGLE_CHAR_VERB_HINT[s] ?: return null
        return DeliveryCue(s, CueKind.DELIVERY_CUE, h, "VERB")
    }

    private val SINGLE_CHAR_VERB_HINT = mapOf(
        "怒" to "angry", "笑" to "amused", "叹" to "weary", "哭" to "sad", "哼" to "sardonic",
        "吼" to "angry", "叫" to "loud", "喊" to "loud",
    )
}
