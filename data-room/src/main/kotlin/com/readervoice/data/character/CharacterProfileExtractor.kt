package com.readervoice.data.character

import com.readervoice.parser.chapters.ChineseNumeralParser

/**
 * R2/app 设计：**角色档案的规则抽取**（`CharacterManagerActivity` 的数据源）。
 *
 * ## 原则（不许编造）
 *
 * 用户的原话：**"人物档案，连年龄都没有，更别说其他的了"**。
 * 但同时项目的铁律是**不许编造**（不变量 5）：抽不到就必须显式显示「未知」，
 * 而不是给一个看起来合理的假值。所以本抽取器的产出是：
 * ```
 * 值 + 证据（原文片段 + 行号）+ 命中次数 + 置信度；抽不到 => null（UI 显示"未知（可编辑）"）
 * ```
 *
 * ## 抽取项与判据（全部**有界、不跨句**，与 NameEvidence 同纪律）
 *
 * | 字段 | 判据 | 例子 |
 * |---|---|---|
 * | 年龄 | `名字 … N 岁` / `名字 … 年方 N` / `N 岁 … 名字` | `李寄舟今年十八岁` |
 * | 性别 | 同句代词（他/她）多数；或身份词（男子/女子/少年/少女/老者/妇人…） | `她` × 12 → 女 |
 * | 身份 | `名字 (是\|乃\|为\|正是) X，`；或名字自带称谓后缀 | `李寄舟是魔教教主` |
 * | 别称 | 名字的后缀变体（`X 大师`/`X 长老`）由称谓表给出 | `空闻` → `空闻大师` |
 *
 * 置信度：同一字段命中 ≥2 次且证据互不相同 ⇒ **HIGH**；命中 1 次 ⇒ **LOW**；0 次 ⇒ 未知。
 * LOW 在 UI 上标注「低置信」，用户可以改（改后即 User Locked，模型不得覆盖 —— 不变量 8）。
 */
data class ProfileEvidence(
    /** 字段名：age / gender / role / alias */
    val field: String,
    val value: String,
    /** 原文片段（截断到 48 字，避免把整段塞进档案） */
    val quote: String,
    /** 行号（1-based；源文件行，用于"跳回原文"） */
    val lineNo: Int,
)

enum class ProfileConfidence { NONE, LOW, HIGH }

data class ProfileField(
    val value: String?,
    val confidence: ProfileConfidence,
    val evidence: List<ProfileEvidence> = emptyList(),
) {
    val known: Boolean get() = value != null
    companion object {
        val UNKNOWN = ProfileField(null, ProfileConfidence.NONE)
    }
}

data class CharacterProfile(
    val name: String,
    /** 年龄（岁）。null = 未知。 */
    val age: ProfileField = ProfileField.UNKNOWN,
    /** 性别："男" / "女"。null = 未知。 */
    val gender: ProfileField = ProfileField.UNKNOWN,
    /** 身份/称呼（如 魔教教主 / 武当掌门 / 店小二）。 */
    val role: ProfileField = ProfileField.UNKNOWN,
    /** 别称（含称谓后缀的常见写法）。 */
    val aliases: List<String> = emptyList(),
    /** 全书出现次数（名字表面）。 */
    val mentions: Int = 0,
    /** 台词数（来自剧本；没有剧本时为 0）。 */
    val spokenLines: Int = 0,
    /** 首次出现行（1-based）。 */
    val firstLine: Int = -1,
    /** 用户锁定：锁定字段不再被规则/模型覆盖（不变量 8）。 */
    val locked: Boolean = false,
)

object CharacterProfileExtractor {

    /** 与 NameLegitimacy.TITLE_SUFFIX 同源（身份后缀 ⇒ 别称/身份）。 */
    private val TITLE_SUFFIX = listOf(
        "大师", "禅师", "师太", "仙子", "娘娘", "先生", "前辈", "长老", "掌门", "真人",
        "道人", "首座", "教主", "姑娘", "夫人", "公子", "小姐", "丫头", "大爷", "婆婆",
        "姑姑", "哥哥", "姐姐", "妹妹", "兄弟", "阁下", "陛下", "殿下", "师兄", "师弟",
    )

    private val MALE_WORDS = listOf("男子", "少年", "老者", "老汉", "大汉", "青年", "公子", "师父", "老头", "男孩", "汉子")
    private val FEMALE_WORDS = listOf("女子", "少女", "姑娘", "妇人", "老婆婆", "婆婆", "丫头", "女孩", "少妇", "女子")

    private val AGE_CHARS = "一二三四五六七八九十百零两廿卅0123456789"
    private val AGE_RE = Regex("[$AGE_CHARS]{1,3}\\s*岁")
    private val AGE_ALT_RE = Regex("年方\\s*([$AGE_CHARS]{1,3})")
    private val ROLE_RE = Regex("(?:是|乃|为|正是|便是|身为)\\s*([^，。！？；\\s]{2,12})")
    private val SENTENCE_SPLIT = Regex("[。！？；\\n]")

    /**
     * @param names 已知角色名（来自角色发现/剧本）
     * @param lines 全书正文行（1-based 行号 = index + 1）
     * @param spokenLines 角色 → 台词数（来自剧本；可选）
     */
    fun extract(
        names: Collection<String>,
        lines: List<String>,
        spokenLines: Map<String, Int> = emptyMap(),
    ): List<CharacterProfile> {
        if (names.isEmpty()) return emptyList()
        // 按名字长度降序（最长匹配优先：避免"张三丰"被"张三"截断）
        val ordered = names.filter { it.length >= 2 }.sortedByDescending { it.length }
        val acc = LinkedHashMap<String, Acc>()
        ordered.forEach { acc[it] = Acc() }
        // ★ 按首字分桶：否则每个字符位置都要遍历全部名字 —— 80 个角色 × 20 万字
        //   = 上亿次 startsWith，真机会卡死（2026-09-18 实测卡顿的根因）。
        val byFirst = ordered.groupBy { it[0] }

        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lineNo = idx + 1
            // 一行可能含多句；按句处理，保证"有界、不跨句"
            for (sentence in line.split(SENTENCE_SPLIT)) {
                val s = sentence.trim()
                if (s.length < 3) continue
                // ★ 从左到右**最长匹配**推进：避免短名冒领长名的出现
                var i = 0
                while (i < s.length) {
                    val candidates = byFirst[s[i]]
                    val name = candidates?.firstOrNull { s.startsWith(it, i) }
                    if (name == null) {
                        i++
                        continue
                    }
                    val a = acc.getValue(name)
                    a.mentions++
                    if (a.firstLine < 0) a.firstLine = lineNo
                    extractAge(s, name, i, lineNo, a)
                    extractRole(s, name, i, lineNo, a)
                    extractGender(s, name, lineNo, a)
                    // 别称：紧跟在名字后的称谓后缀（同一趟里判定，避免二次全量扫描原文）
                    val after = i + name.length
                    if (after < s.length) {
                        for (suf in TITLE_SUFFIX) {
                            if (s.startsWith(suf, after) && a.aliases.size < 4) {
                                a.aliases += name + suf
                                break
                            }
                        }
                    }
                    i += name.length
                }
            }
        }

        return ordered.map { name ->
            val a = acc.getValue(name)
            CharacterProfile(
                name = name,
                age = fieldOf("age", a.age, a.ageEvidence),
                gender = fieldOf("gender", a.gender, a.genderEvidence),
                role = fieldOf("role", a.role, a.roleEvidence),
                aliases = a.aliases.distinct().take(4),
                mentions = a.mentions,
                spokenLines = spokenLines[name] ?: 0,
                firstLine = a.firstLine,
            )
        }.sortedWith(compareByDescending<CharacterProfile> { it.spokenLines }.thenByDescending { it.mentions })
    }

    private fun fieldOf(field: String, value: String?, evidence: List<ProfileEvidence>): ProfileField {
        if (value == null) return ProfileField.UNKNOWN
        val distinct = evidence.distinctBy { it.quote }
        val conf = if (distinct.size >= 2) ProfileConfidence.HIGH else ProfileConfidence.LOW
        return ProfileField(value, conf, distinct.take(3))
    }

    // ── 年龄 ────────────────────────────────────────────────────────────

    private fun extractAge(s: String, name: String, at: Int, lineNo: Int, a: Acc) {
        // ① "名字 … N 岁"（名字后 0..10 字内）
        val tail = s.substring(at + name.length).take(12)
        AGE_RE.find(tail)?.let { m ->
            ChineseNumeralParser.parse(m.value.removeSuffix("岁").trim())?.let { n ->
                if (n in 0..130) {
                    a.age = n.toString()
                    a.ageEvidence += ProfileEvidence("age", "$n", quote(s), lineNo)
                    return
                }
            }
        }
        AGE_ALT_RE.find(tail)?.let { m ->
            ChineseNumeralParser.parse(m.groupValues[1])?.let { n ->
                if (n in 0..130) {
                    a.age = n.toString()
                    a.ageEvidence += ProfileEvidence("age", "$n", quote(s), lineNo)
                }
            }
        }
        // ② "N 岁 … 名字"（名字前 0..10 字内）——网文常见"十八岁的李寄舟"
        val head = s.substring(0, at).takeLast(12)
        AGE_RE.find(head)?.let { m ->
            ChineseNumeralParser.parse(m.value.removeSuffix("岁").trim())?.let { n ->
                if (n in 0..130) {
                    a.age = a.age ?: n.toString()
                    a.ageEvidence += ProfileEvidence("age", "$n", quote(s), lineNo)
                }
            }
        }
    }

    // ── 身份 ────────────────────────────────────────────────────────────

    private fun extractRole(s: String, name: String, at: Int, lineNo: Int, a: Acc) {
        val tail = s.substring(at + name.length)
        ROLE_RE.find(tail)?.let { m ->
            val candidate = m.groupValues[1].trim()
            // 过滤：只保留"像身份"的短名词（避免把动作短语当身份）
            if (candidate.length in 2..8 && candidate.none { it in "的了着过不没" }) {
                if (a.role == null) a.role = candidate
                a.roleEvidence += ProfileEvidence("role", candidate, quote(s), lineNo)
            }
        }
    }

    // ── 性别 ────────────────────────────────────────────────────────────

    private fun extractGender(s: String, name: String, lineNo: Int, a: Acc) {
        // ① 身份词（高精度）
        MALE_WORDS.firstOrNull { s.contains(it) }?.let {
            a.maleVotes++
            a.genderEvidence += ProfileEvidence("gender", "男", quote(s), lineNo)
        }
        FEMALE_WORDS.firstOrNull { s.contains(it) }?.let {
            a.femaleVotes++
            a.genderEvidence += ProfileEvidence("gender", "女", quote(s), lineNo)
        }
        // ② 代词（弱证据，只计数不直接产出）
        if (s.contains("他")) a.maleVotes++
        if (s.contains("她")) a.femaleVotes++
        a.gender = when {
            a.maleVotes > a.femaleVotes -> "男"
            a.femaleVotes > a.maleVotes -> "女"
            else -> null
        }
    }

    private fun quote(s: String) = if (s.length <= 48) s else s.take(48) + "…"

    private class Acc {
        var mentions = 0
        var firstLine = -1
        var age: String? = null
        val ageEvidence = mutableListOf<ProfileEvidence>()
        var gender: String? = null
        var maleVotes = 0
        var femaleVotes = 0
        val genderEvidence = mutableListOf<ProfileEvidence>()
        var role: String? = null
        val roleEvidence = mutableListOf<ProfileEvidence>()
        val aliases = mutableListOf<String>()
    }
}
