package com.readervoice.parser.chapters

import com.readervoice.parser.source.PhysicalLine

/**
 * ChapterCandidateScanner（TASK-020 §12/§17/§2.1）。
 * - Regex 只产 Candidate，不判最终章节；
 * - 与 legacy 一致用 find（非整行 matches），规则自带行边界锚定；
 * - **默认保守（2026-09-18 产品模型修正）**：规则包里 `enable=false` 的规则**一律不参与扫描**，
 *   与 Legado 3.30.6 内置 `txtTocRule.json` 的产品语义一致 —— 激进规则（`《…》成对`、
 *   `====` 特定符号标题、`顶格标题`、`通用规则`）默认关闭，**由用户在【章节规则】页显式启用**。
 *   修复前的行为是"只跳过禁用的 HIGH/EXTREME 风险规则"，于是禁用中的低/中风险宽泛规则照样
 *   产出候选并进入解析序列 —— 真机实测 4.33MB 的书因此产生 16,060 个"章节"（含 `====` 与
 *   `《这么可爱真是抱歉》`）。**可选规则不得混进生产章节序列。**
 * - REJECTED 规则（空 regex）跳过；超长行（>lineLengthCap）只跑 LOW 风险锚定规则（REGEX_SAFETY_POLICY §5）。
 */
class ChapterCandidateScanner(
    private val rules: List<ChapterRule>,
    private val lineLengthCap: Int = 1024,
) {

    private val compiled: List<Pair<ChapterRule, Regex?>> = rules
        .filter { it.regexEngine != RegexEngine.REJECTED }
        .map { it to runCatching { Regex(it.regex, RegexOption.MULTILINE) }.getOrNull() }

    /** 超长行白名单：明确安全、锚定、低复杂度规则（REGEX_SAFETY_POLICY §5.4）。 */
    private val longLineWhitelist = setOf(
        "legacy--2", "legacy--8", "legacy--9", "legacy--11", "legacy--12",
        "legacy--14", "legacy--17", "legacy--21", "legacy--22",
    )

    fun scan(lines: List<PhysicalLine>, bookId: String): List<ChapterCandidate> {
        val out = ArrayList<ChapterCandidate>()
        var candidateId = 0L
        for (line in lines) {
            val text = line.rawText
            if (text.isBlank()) continue
            val long = text.length > lineLengthCap
            for ((rule, re) in compiled) {
                val re2 = re ?: continue
                // ★ 默认保守：规则包 enable=false ⇒ 不扫描（用户可在【章节规则】页显式启用）
                if (!rule.enabled) continue
                if (long && rule.ruleId !in longLineWhitelist) continue
                if (!re2.containsMatchIn(text)) continue
                out += buildCandidate(++candidateId, line, rule, text)
            }
        }
        return out
    }

    private fun buildCandidate(id: Long, line: PhysicalLine, rule: ChapterRule, text: String): ChapterCandidate {
        val serial = extractSerial(text, rule.family)
        val clean = cleanTitle(text, rule.family, serial?.raw)
        val length = text.length
        val lengthScore = when {
            length <= 30 -> 1.0
            length <= 60 -> 0.7
            length <= 100 -> 0.4
            else -> 0.15
        }
        val spacingScore = when {
            line.leadingFullwidthSpace == 0 && line.leadingAsciiSpace == 0 && line.leadingTab == 0 -> 1.0
            line.leadingFullwidthSpace <= 2 -> 0.8
            else -> 0.4
        }
        return ChapterCandidate(
            candidateId = id,
            lineNo = line.lineNo,
            ruleId = rule.ruleId,
            family = rule.family,
            target = rule.target,
            rawLine = text,
            rawTitle = text,
            cleanTitle = clean,
            serialRaw = serial?.raw,
            serialValue = serial?.value,
            regexScore = rule.confidenceBase,
            rulePriorityScore = (100 - rule.priority).coerceIn(0, 100) / 100.0,
            lengthScore = lengthScore,
            spacingScore = spacingScore,
            evidence = listOf("REGEX_${rule.family.name} +${rule.confidenceBase}"),
        )
    }

    private data class SerialResult(val raw: String, val value: Int)

    /** 序号提取：家族相关。返回 (原始串, 值)；失败 null。 */
    private fun extractSerial(text: String, family: RuleFamily): SerialResult? {
        val normalized = when (family) {
            RuleFamily.ENGLISH_CHAPTER -> Regex("(?i)(?:chapter|section|part|episode)\\s{0,4}(\\d{1,6})")
                .find(text)?.groupValues?.get(1)
            RuleFamily.VOLUME -> Regex("[卷章]\\s{0,4}([\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,6})")
                .find(text)?.groupValues?.get(1)
            RuleFamily.STANDARD_ZH, RuleFamily.ZH_CHAPTER_WORD, RuleFamily.HASH_NUMBER ->
                Regex("第\\s{0,4}([\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8})\\s{0,4}(?:章|节|卷|集|回|话|场|篇|部)")
                    .find(text)?.groupValues?.get(1)
            RuleFamily.PURE_NUMBER ->
                Regex("^[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+")
                    .find(text)?.value
            RuleFamily.ARABIC_PREFIX ->
                Regex("^(\\d{1,6})[、. 　]").find(text)?.groupValues?.get(1)
                ?: Regex("(\\d{1,6})\\s*[）)]\\s*$").find(text)?.groupValues?.get(1)
                ?: Regex("^(\\d{1,6})$").find(text)?.groupValues?.get(1)
            else -> Regex("(\\d{1,6})").find(text)?.groupValues?.get(1)
        } ?: return null
        val v = ChineseNumeralParser.parse(normalized) ?: return null
        return SerialResult(normalized, v)
    }

    /** 标题清洗（展示用；不改 source）：去行首序号/分隔符/前后空白。 */
    private fun cleanTitle(text: String, family: RuleFamily, serialRaw: String?): String {
        var t = text
        when (family) {
            RuleFamily.STANDARD_ZH, RuleFamily.ZH_CHAPTER_WORD ->
                t = Regex("^第\\s{0,4}[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\\s{0,4}(?:章|节|卷|集|回|话|场|篇|部)\\s{0,4}")
                    .replaceFirst(t, "")
            RuleFamily.ARABIC_PREFIX -> t = Regex("^\\d{1,6}\\s*[、. 　]").replaceFirst(t, "")
            RuleFamily.PURE_NUMBER -> t = Regex("^[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[ 　\t]*").replaceFirst(t, "")
            RuleFamily.ENGLISH_CHAPTER -> t = Regex("(?i)^(?:chapter|section|part|episode)\\s{0,4}\\d{1,6}\\s{0,4}")
                .replaceFirst(t, "")
            else -> Unit
        }
        return t.trim(' ', '\t', '\u3000')
    }
}
