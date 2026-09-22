package com.readervoice.data.semantic

/**
 * MOBILE-005 / M3：**名称证据与群体判据**。
 *
 * 来源说明：本项目对 v90.x 第三方工程一贯采用「逆向 → 自研重建」，不搬运其代码。
 * 本文件是**自写实现**，其规则形态参考了 `research/private/legado-v909/`（RESEARCH_ONLY、LICENSE_UNKNOWN）
 * 中长期迭代出的三类判据（见 `docs/baseline/v909/` 的自写分析）：
 *   ① 群体称呼不可与单人互并（单人 ≠ 群体，对应本项目不变量 3/6）
 *   ② 同一人/别名 的**有界**正向证据（不跨句；gap 有上限）
 *   ③ 非同人/亲属关系/对话 的反向证据（师父 ≠ 徒弟；A 与 B 对话 ⇒ 不同人）
 *
 * 设计纪律（同样来自那份工程的经验）：
 *   · 所有模式 **有界且不跨句**（`[^。！？\n]{0,n}`），避免"关键词命中 = 事实"的误判；
 *   · 只做**高精度**判据，不用泛词（如"附身/冒充/假冒"）扫描 —— 泛词命中率低、误伤高。
 */
object NameEvidence {

    /** 句界：模式不得跨越它（与 legacy 同款纪律）。 */
    private const val SENT_END = "[^。！？!?\\n]"

    /** 显式群体称呼。 */
    private val GROUP_EXPLICIT = setOf(
        "众人", "众修士", "众弟子", "诸人", "诸修", "众女", "众男", "大家", "所有人", "全场",
        "二人", "两人", "三人", "四人", "五人", "几人", "数人", "一行人", "一群人",
        "其他几人", "其他修士", "在座修士", "其余人", "剩下的人", "一群修士",
    )

    /** "N 名/个/位/人 + 身份" 形式（三个修士 / 几名弟子 / 一群女子）。 */
    private val GROUP_COUNTED = Regex(
        "^[一二两三四五六七八九十百数几0-9]+(名|个|位|人|群|帮|伙).*(修士|女子|男子|弟子|老者|大汉|少年|少女|儒生|汉子|法师|长老|僧人|人)$"
    )

    /** "高矮/一胖一瘦/一男一女 + 身份" 形式（身份后缀可省，如"一男一女"本身即群体）。 */
    private val GROUP_PAIRED = Regex(
        "^(高矮|一高一矮|一胖一瘦|一男一女|两男|两女)(.*(修士|男子|女子|法师|老者|人))?$"
    )

    /** 以群体量词收尾（…二人 / …众人）。 */
    private val GROUP_SUFFIX = Regex("(二人|两人|三人|四人|几人|数人|众人|一行人|一群人|们)$")

    /** 别名/同一人 的正向提示词（有界模式中作为"连接词"）。 */
    private val ALIAS_CUE = listOf(
        "本名", "真名", "原名", "又名", "别名", "又称", "又叫", "又叫做", "也叫", "名叫", "名为", "名唤",
        "叫做", "叫作", "唤作", "唤为", "自称", "号称", "人称", "道号", "法号", "尊号",
        "就是", "即是", "即为", "正是", "便是", "乃是", "也就是", "其实就是",
        "同一人", "同一个人", "化名", "被称为", "被称作", "称为", "称作", "介绍为", "引见为",
    )

    /** 亲属/从属关系词：关系 ≠ 同一人（师父≠徒弟）。 */
    private val RELATION_WORDS = listOf(
        "师父", "师傅", "师尊", "徒弟", "弟子", "手下", "属下", "下属", "父亲", "母亲",
        "儿子", "女儿", "妻子", "丈夫", "老板", "上司", "领导", "同事", "同学", "朋友",
        "亲戚", "敌人", "仇人", "道侣", "主人", "仆人", "哥哥", "弟弟", "姐姐", "妹妹",
    )

    private val NEGATION = listOf("不是", "并非", "绝非", "并不是", "非")

    /** 群体称呼（不得作为单人人名，也不得与单人合并）。 */
    fun isGroupName(surface: String): Boolean {
        val s = surface.trim()
        if (s.isEmpty()) return false
        if (s in GROUP_EXPLICIT) return true
        if (GROUP_COUNTED.matches(s)) return true
        if (GROUP_PAIRED.matches(s)) return true
        if (GROUP_SUFFIX.containsMatchIn(s)) return true
        return false
    }

    /** 群体 ↔ 单人 不得合并；返回原因（"" = 可合并）。 */
    fun mergeBlockReason(a: String, b: String): String {
        val x = a.trim(); val y = b.trim()
        if (x.isEmpty() || y.isEmpty() || x == y) return ""
        if (isGroupName(x) != isGroupName(y)) return "群体/单人不合并"
        return ""
    }

    /**
     * 同一人/别名 的**正向证据**（有界 gap，不跨句）。
     * @return 命中的模式标签，未命中返回 null
     */
    fun aliasEvidence(a: String, b: String, text: String): String? {
        val x = a.trim(); val y = b.trim()
        if (x.isEmpty() || y.isEmpty() || x == y || text.isEmpty()) return null
        val ex = Regex.escape(x); val ey = Regex.escape(y)
        val gap = "$SENT_END{0,50}"
        for (cue in ALIAS_CUE) {
            if (Regex("$ex$gap${Regex.escape(cue)}$gap$ey").containsMatchIn(text)) return "ALIAS_CUE:$cue"
            if (Regex("$ey$gap${Regex.escape(cue)}$gap$ex").containsMatchIn(text)) return "ALIAS_CUE:$cue"
        }
        // 括注形式：A（B） / A(B)
        if (Regex("$ex[（(]$ey[）)]").containsMatchIn(text)) return "PARENTHETICAL"
        if (Regex("$ey[（(]$ex[）)]").containsMatchIn(text)) return "PARENTHETICAL"
        return null
    }

    /**
     * 非同人/关系/对话 的**反向证据**（有界 gap，不跨句）。
     * @return 命中的模式标签，未命中返回 null
     */
    fun contradictionEvidence(a: String, b: String, text: String): String? {
        val x = a.trim(); val y = b.trim()
        if (x.isEmpty() || y.isEmpty() || x == y || text.isEmpty()) return null
        val ex = Regex.escape(x); val ey = Regex.escape(y)
        val g = "$SENT_END{0,16}"
        val neg = NEGATION.joinToString("|") { Regex.escape(it) }
        val rel = RELATION_WORDS.joinToString("|") { Regex.escape(it) }

        // ① A 不是 B
        if (Regex("$ex$g($neg)$g$ey").containsMatchIn(text)) return "NEGATION"
        if (Regex("$ey$g($neg)$g$ex").containsMatchIn(text)) return "NEGATION"
        // ② A 和 B 不是同一人/别名
        val conj = "(和|与|跟|及)"
        if (Regex("$ex$g$conj$g$ey$g($neg)$g(同一人|同一个人|一人|别名)").containsMatchIn(text)) return "NOT_SAME_PERSON"
        if (Regex("$ey$g$conj$g$ex$g($neg)$g(同一人|同一个人|一人|别名)").containsMatchIn(text)) return "NOT_SAME_PERSON"
        // ③ A 和 B 是两个人/不同人物
        if (Regex("$ex$g$conj$g$ey$g(是|为|属于)?$g(两个人|不同人物|不同的人|不同角色|两个角色)").containsMatchIn(text)) return "TWO_PEOPLE"
        if (Regex("$ey$g$conj$g$ex$g(是|为|属于)?$g(两个人|不同人物|不同的人|不同角色|两个角色)").containsMatchIn(text)) return "TWO_PEOPLE"
        // ④ A 只是 B 的(师父/父亲/…)  ⇒ 关系 ≠ 同一人
        if (Regex("$ex${g}只是${g}$ey${g}的($rel)").containsMatchIn(text)) return "RELATION"
        if (Regex("$ey${g}只是${g}$ex${g}的($rel)").containsMatchIn(text)) return "RELATION"
        // ⑤ A 对 B 说话 ⇒ 二者不同
        if (Regex("$ex${g}(正在|曾经)?(对|向|朝|冲)${g}$ey").containsMatchIn(text)) return "DIALOGUE_BETWEEN"
        if (Regex("$ey${g}(正在|曾经)?(对|向|朝|冲)${g}$ex").containsMatchIn(text)) return "DIALOGUE_BETWEEN"
        return null
    }
}
