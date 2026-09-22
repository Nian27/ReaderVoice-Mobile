package com.readervoice.parser.chapters

/**
 * ChapterStyleProfiler（TASK-020 §27/§28）：书级/段落级风格画像。
 * 前 N 个高置信正文候选的 family 分布 → 后续同 family 候选获得 style_score 提升。
 * 不假设一整本只有一种格式（允许 Volume/Section Local Profile）。
 */
class ChapterStyleProfiler {

    data class Profile(
        val familyCounts: Map<RuleFamily, Int>,
        val dominantFamily: RuleFamily?,
        val total: Int,
    )

    fun profile(groups: List<SameLineCandidateGroup>, window: Int = 30): Profile {
        val counts = mutableMapOf<RuleFamily, Int>()
        var n = 0
        for (g in groups) {
            if (n >= window) break
            val c = g.winning
            if (c.target != RuleTarget.CHAPTER) continue
            counts[c.family] = (counts[c.family] ?: 0) + 1
            n++
        }
        val dominant = counts.maxByOrNull { it.value }?.key
        return Profile(counts, dominant, n)
    }

    /** 候选风格分：与 dominant 一致 +0.5，同族 +0.3。 */
    fun styleScore(candidate: ChapterCandidate, profile: Profile): Double {
        if (profile.total == 0) return 0.0
        return when (candidate.family) {
            profile.dominantFamily -> 0.5
            in profile.familyCounts -> 0.3
            else -> 0.0
        }
    }
}
