package com.readervoice.parser.chapters

/**
 * TOCDetector（TASK-020 §18/§19/§20）。
 * 证据：候选密度高（连续 ≥8）、候选间正文行极少（<3）、序号连续/单调、位于书前部、标题风格重复。
 * "看到目录两个字"不构成 TOC（§19）；TOC 条目与正文 Chapter 链接只作 evidence，不凭空造章（§20）。
 */
object TocDetector {

    data class TocResult(val blocks: List<TocBlock>, val entries: List<TocEntry>, val bodyLinks: List<TocBodyLink>)

    private const val MIN_ENTRIES = 8
    private const val MAX_GAP_LINES = 3

    fun detect(groups: List<SameLineCandidateGroup>, totalLines: Int): TocResult {
        val blocks = mutableListOf<TocBlock>()
        val entries = mutableListOf<TocEntry>()

        var i = 0
        while (i < groups.size) {
            val start = i
            var end = i
            var gapSum = 0
            while (end + 1 < groups.size) {
                val next = groups[end + 1].winning
                // 卷标题不属 TOC 条目流：终止块（卷后紧跟正文）
                if (VolumeResolver.isVolumeTitle(next.rawTitle)) break
                // 无序号候选（正文行误报等）终止条目流：TOC 条目流应有连续序号
                if (next.serialValue == null) break
                val gap = groups[end + 1].lineNo - groups[end].lineNo - 1
                if (gap > MAX_GAP_LINES) break
                gapSum += gap
                // 密度判据：块内间隔行总数不得超过候选数 30%（否则是正文流而非 TOC）
                val countSoFar = end + 1 - start + 1
                if (gapSum > countSoFar * 0.3) break
                end++
            }
            val count = end - start + 1
            val serials = (start..end).mapNotNull { groups[it].winning.serialValue }
            val monotonic = serials.zipWithNext().all { (a, b) -> b >= a }
            val nearFront = groups[start].lineNo < totalLines * 0.15
            if (count >= MIN_ENTRIES && monotonic && nearFront) {
                blocks += TocBlock(groups[start].lineNo, groups[end].lineNo, count)
                for (g in groups.subList(start, end + 1)) {
                    entries += TocEntry(g.lineNo, g.winning.serialRaw, g.winning.serialValue, g.winning.rawTitle)
                }
                i = end + 1
            } else {
                i++
            }
        }

        // TOC ↔ 正文链接（serial 匹配；仅作 evidence）
        val bodySerials = groups.map { it.winning.serialValue }.toSet()
        val links = entries.mapNotNull { e ->
            if (e.serialValue != null && e.serialValue in bodySerials) {
                TocBodyLink(e.lineNo, null, e.serialValue, 1.0)
            } else null
        }
        return TocResult(blocks, entries, links)
    }
}
