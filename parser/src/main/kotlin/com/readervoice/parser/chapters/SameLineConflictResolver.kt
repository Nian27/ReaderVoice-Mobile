package com.readervoice.parser.chapters

/**
 * SameLineConflictResolver（TASK-020 §13/§14）：一行多规则命中 → 一个 winner。
 * 比较：specificity（family 明确度）> 规则优先级 > serial 解析成功 > confidenceBase。
 * 保留 alternative_candidates 供调试。
 */
object SameLineConflictResolver {

    private val FAMILY_SPECIFICITY = mapOf(
        RuleFamily.STANDARD_ZH to 5,
        RuleFamily.ZH_CHAPTER_WORD to 5,
        RuleFamily.VOLUME to 5,
        RuleFamily.ENGLISH_CHAPTER to 4,
        RuleFamily.SPECIAL_TITLE to 4,
        RuleFamily.ARABIC_PREFIX to 3,
        RuleFamily.HASH_NUMBER to 3,
        RuleFamily.PURE_NUMBER to 1, // 纯数字最弱（§25）
        RuleFamily.CUSTOM to 2,
    )

    fun resolve(candidates: List<ChapterCandidate>): List<SameLineCandidateGroup> =
        candidates.groupBy { it.lineNo }
            .map { (lineNo, cs) ->
                val winning = cs.maxWithOrNull(compareBy(
                    { FAMILY_SPECIFICITY[it.family] ?: 0 },
                    { -it.rulePriorityScore },
                    { if (it.serialValue != null) 1 else 0 },
                    { it.regexScore },
                ))!!
                SameLineCandidateGroup(lineNo, winning, cs.filter { it !== winning })
            }
            .sortedBy { it.lineNo }
}
