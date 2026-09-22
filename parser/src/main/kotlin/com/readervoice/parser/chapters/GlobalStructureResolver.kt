package com.readervoice.parser.chapters

import com.readervoice.parser.source.PhysicalLine

/**
 * GlobalStructureResolver（TASK-020 §35/§36/§37）：候选 → 最终结构。
 * - 评分可解释：evidence 列表（REGEX_* / SERIAL_CONTINUITY / BODY_SPACING / STYLE_MATCH / NOT_TOC ...）；
 * - sequence 是证据不是硬约束（§23/§24）；
 * - PURE_NUMBER 必须依赖全局序列增强（§25）；
 * - 状态：CONFIRMED / PROVISIONAL / REJECTED / TOC_ENTRY（§37）。
 */
class GlobalStructureResolver(
    private val confirmThreshold: Double = 2.0,
    private val provisionalThreshold: Double = 1.1,
) {

    fun resolve(
        groups: List<SameLineCandidateGroup>,
        lines: List<PhysicalLine>,
        bookId: String,
        toc: TocDetector.TocResult,
        volumes: List<Volume>,
        profile: ChapterStyleProfiler.Profile,
    ): StructureRevision {
        val tocLines = toc.blocks.flatMap { (s, e, _) -> s..e }.toSet()
        val tocSerials = toc.entries.mapNotNull { it.serialValue }.toSet()
        val volByLine = volumes.associateBy { it.startLine }

        // 预计算序列邻域（前/后一有 serial 的非卷候选；卷标题行与正文行不参与序列）
        val n = groups.size
        val isVol = groups.map { VolumeResolver.isVolumeTitle(it.winning.rawTitle) }
        fun prevSerial(i: Int): Int? {
            var j = i - 1
            while (j >= 0) {
                val s = groups[j].winning.serialValue
                if (!isVol[j] && s != null) return s
                j--
            }
            return null
        }
        fun nextSerial(i: Int): Int? {
            var j = i + 1
            while (j < n) {
                val s = groups[j].winning.serialValue
                if (!isVol[j] && s != null) return s
                j++
            }
            return null
        }

        val chapters = mutableListOf<Chapter>()
        var chapterIndex = 0
        var lastVolumeId: Long? = null

        for (i in 0 until n) {
            val g = groups[i]
            val c = g.winning
            val evidence = c.evidence.toMutableList()
            var score = c.regexScore + c.rulePriorityScore * 0.3 + c.lengthScore * 0.4 + c.spacingScore * 0.3

            // 卷锚点不产出普通 Chapter（§22：Volume 本身不能作为普通朗读正文；已由 VolumeResolver 处理）
            if (VolumeResolver.isVolumeTitle(c.rawTitle)) {
                chapters += Chapter(
                    chapterId = 0, bookId = bookId, volumeId = null, chapterIndex = 0,
                    serialValue = c.serialValue, titleRaw = c.rawTitle, titleDisplay = c.cleanTitle,
                    anchorLine = g.lineNo, anchorByteStart = 0, anchorCodepointStart = 0,
                    contentStartLine = g.lineNo + 1, contentEndLine = null,
                    state = ResolveState.REJECTED, finalScore = score,
                    evidence = evidence + "VOLUME_ANCHOR (not a chapter)",
                )
                continue
            }

            // 整行纯数字/日期型（cleanTitle 空 或 全数字字符行，含中文数字）：孤立时强拒（§25/§42 陷阱）
            if (c.cleanTitle.isEmpty() || c.rawTitle.trim().all { it in NUMERIC_CHARS }) {
                score -= 1.5
                evidence += "NUMBER_ONLY_LINE -1.5"
            }
            // 时间/日期格式行（"10:30"、"2026-08-12"）：同样拒
            if (TIME_DATE_RE.matches(c.rawTitle.trim())) {
                score -= 1.5
                evidence += "TIME_DATE_LINE -1.5"
            }
            // 正文性行弱化：章节标题不应含句末标点；"正文..."开头且无 serial 多为正文行误报
            if (c.rawTitle.any { it == '。' || it == '！' || it == '？' }) {
                score -= 1.0
                evidence += "HAS_SENTENCE_END -1.0"
            }
            if (c.rawTitle.startsWith("正文") && c.serialValue == null) {
                score -= 0.8
                evidence += "PROSE_LINE_LIKE -0.8"
            }

            // TOC 块内 → TOC_ENTRY，不产出正文 Chapter
            if (g.lineNo in tocLines) {
                val e = Chapter(
                    chapterId = 0, bookId = bookId, volumeId = lastVolumeId, chapterIndex = 0,
                    serialValue = c.serialValue, titleRaw = c.rawTitle, titleDisplay = c.cleanTitle,
                    anchorLine = g.lineNo, anchorByteStart = 0, anchorCodepointStart = 0,
                    contentStartLine = g.lineNo + 1, contentEndLine = null,
                    state = ResolveState.TOC_ENTRY, finalScore = score,
                    evidence = evidence + "IN_TOC_BLOCK -2.0",
                )
                chapters += e
                continue
            }

            // 序列证据（§24；邻域排除卷标题行）
            val prev = prevSerial(i)
            val next = nextSerial(i)
            val prevDiff = if (c.serialValue != null && prev != null) c.serialValue - prev else null
            val nextDiff = if (c.serialValue != null && next != null) next - c.serialValue else null
            if (c.serialValue != null) {
                val continuity = listOfNotNull(prevDiff, nextDiff).count { it == 1 }
                when {
                    continuity >= 2 -> { score += 1.2; evidence += "SERIAL_CONTINUITY +1.2" }
                    prevDiff == 1 || nextDiff == 1 -> { score += 0.7; evidence += "SERIAL_CONTINUITY +0.7" }
                    else -> {
                        // 卷边界 reset 合法（§21）
                        val afterVolume = volByLine.keys.any { it < g.lineNo } &&
                            (c.serialValue ?: 0) < (prev ?: 0) && prev != null
                        if (afterVolume) { score += 0.3; evidence += "VOLUME_RESET +0.3" }
                        else { score -= 0.5; evidence += "SERIAL_GAP -0.5" }
                    }
                }
            } else {
                score -= 0.3
                evidence += "NO_SERIAL -0.3"
            }

            // 风格证据
            val style = ChapterStyleProfiler().styleScore(c, profile)
            if (style > 0) { score += style; evidence += "STYLE_MATCH +$style" }

            // TOC 正文链接证据（§20）
            if (c.serialValue != null && c.serialValue in tocSerials) {
                score += 0.5
                evidence += "TOC_BODY_LINK +0.5"
            }

            // PURE_NUMBER 弱化：无连续性直接压低（§25）
            if (c.family == RuleFamily.PURE_NUMBER && c.serialValue != null) {
                if ((prevDiff != 1 && nextDiff != 1)) {
                    score -= 1.0
                    evidence += "PURE_NUMBER_ISOLATED -1.0"
                }
            }
            // HIGH 风险规则候选：未显式启用时分数打折
            if (c.ruleId in RISK_RULES) {
                score *= 0.6
                evidence += "HIGH_RISK_RULE *0.6"
            }

            // 卷归属（不含卷锚点自身）
            volumes.filter { it.startLine < g.lineNo }.maxByOrNull { it.startLine }?.let { v ->
                lastVolumeId = v.volumeId
            }

            val state = when {
                score >= confirmThreshold -> ResolveState.CONFIRMED
                score >= provisionalThreshold -> ResolveState.PROVISIONAL
                else -> ResolveState.REJECTED
            }
            val line = lines.getOrNull(g.lineNo - 1)
            val ch = Chapter(
                // ★ CH-0 / M0.4：稳定唯一 chapterId。
                //   用 anchor 行的原始字节偏移派生：源不可变（C1）⇒ 重编译稳定；
                //   行字节偏移严格递增 ⇒ 章内唯一；规则变化使章节集合变化时，
                //   仍然存在的章保持同一 id ⇒ 可安全作为持久化主键（替代 ordinal）。
                chapterId = (line?.byteStart ?: g.lineNo).toLong(),
                bookId = bookId, volumeId = lastVolumeId,
                chapterIndex = if (state != ResolveState.REJECTED) ++chapterIndex else 0,
                serialValue = c.serialValue, serialPart = serialPart(c.cleanTitle),
                titleRaw = c.rawTitle, titleDisplay = c.cleanTitle.ifBlank { c.rawTitle },
                anchorLine = g.lineNo,
                anchorByteStart = line?.byteStart ?: 0,
                anchorCodepointStart = line?.charStart ?: 0,
                contentStartLine = g.lineNo + 1, contentEndLine = null,
                state = state, finalScore = score, evidence = evidence,
            )
            chapters += ch
        }

        // content_end 由下一 anchor 推导（§53）；不复制正文
        val anchors = chapters.filter { it.state == ResolveState.CONFIRMED || it.state == ResolveState.PROVISIONAL }
            .sortedBy { it.anchorLine }
        for (i in anchors.indices) {
            val end = if (i + 1 < anchors.size) anchors[i + 1].anchorLine - 1 else lines.size
            anchors[i].contentEndLine = end
        }

        return StructureRevision(
            revisionId = 1, bookId = bookId, createdAt = java.time.Instant.now().toString(),
            reason = "auto-resolve", volumes = volumes, chapters = chapters,
            tocBlocks = toc.blocks, parentRevisionId = null,
        )
    }

    private fun serialPart(title: String): String? = when {
        title.endsWith("上") -> "UPPER"
        title.endsWith("中") -> "MIDDLE"
        title.endsWith("下") -> "LOWER"
        else -> null
    }

    private companion object {
        val RISK_RULES = setOf("legacy--5", "legacy--6", "legacy--7", "legacy--18", "legacy--25")
        val NUMERIC_CHARS = "0123456789〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟".toSet()
        val TIME_DATE_RE = Regex("^[\\d:：/\\-年月日时分秒. ]{1,24}$")
    }
}
