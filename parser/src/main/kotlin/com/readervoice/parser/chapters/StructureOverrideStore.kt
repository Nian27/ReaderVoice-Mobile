package com.readervoice.parser.chapters

/**
 * StructureOverrideStore（TASK-020 §38/§39）：用户修正优先于自动 Resolver（USER_LOCKED）。
 * 本任务实现 backend API/内存版本；Room 持久化在 TASK-040。
 *
 * CANONICAL-JOIN: 本类**按设计**必须读写 `revision.chapters` 原始候选集合 ——
 * 用户 JOIN/BREAK/改标题是在**候选层**做的修正，修正结果再走 Resolver 产出 canonical 视图。
 * 它不是产品读取路径（产品只消费 `ConfirmedChapterView`）。
 */
class StructureOverrideStore {

    private val overrides = mutableListOf<StructureOverride>()

    fun add(o: StructureOverride) {
        overrides.removeAll { it.targetLine == o.targetLine && it.type == o.type }
        overrides += o
    }

    fun list(): List<StructureOverride> = overrides.toList()

    fun clear() = overrides.clear()

    /** 应用覆盖到解析结果（返回新 revision；原 revision 不变）。 */
    fun apply(revision: StructureRevision, bookId: String): StructureRevision {
        var chapters = revision.chapters.toMutableList()
        var volumes = revision.volumes.toMutableList()

        for (o in overrides.filter { it.bookId == bookId }) {
            when (o.type) {
                OverrideType.REMOVE_CHAPTER -> {
                    chapters = chapters.filter { it.anchorLine != o.targetLine }.toMutableList()
                }
                OverrideType.CHANGE_TITLE -> {
                    val t = o.payload["title"]
                    if (t != null) {
                        chapters = chapters.map {
                            if (it.anchorLine == o.targetLine) it.copy(titleRaw = t, titleDisplay = t) else it
                        }.toMutableList()
                    }
                }
                OverrideType.CHANGE_TYPE -> {
                    val t = o.payload["target"]
                    if (t != null) {
                        chapters = chapters.map {
                            if (it.anchorLine == o.targetLine) {
                                val state = if (t == "REMOVE") ResolveState.REJECTED else ResolveState.CONFIRMED
                                it.copy(state = state)
                            } else it
                        }.toMutableList()
                    }
                }
                OverrideType.ADD_CHAPTER -> {
                    val line = o.targetLine ?: 0
                    val title = o.payload["title"] ?: "新章节"
                    chapters = (chapters + Chapter(
                        chapterId = 0, bookId = bookId, volumeId = null, chapterIndex = 0,
                        serialValue = null, titleRaw = title, titleDisplay = title,
                        anchorLine = line, anchorByteStart = 0, anchorCodepointStart = 0,
                        contentStartLine = line + 1, contentEndLine = null,
                        state = ResolveState.CONFIRMED, finalScore = 99.0,
                        evidence = listOf("USER_OVERRIDE +99"),
                    )).toMutableList()
                }
                OverrideType.ASSIGN_VOLUME -> {
                    val volId = o.payload["volume_id"]?.toLongOrNull()
                    if (volId != null) {
                        chapters = chapters.map {
                            if (it.anchorLine == o.targetLine) it.copy(volumeId = volId) else it
                        }.toMutableList()
                    }
                }
            }
        }
        chapters = chapters.sortedBy { it.anchorLine }.toMutableList()
        return revision.copy(chapters = chapters, reason = revision.reason + ";overrides")
    }
}

/**
 * StructurePreview（TASK-020 §40）：文本摘要输出（后续 import UI 可直接展示）。
 */
object StructurePreview {

    fun render(revision: StructureRevision, profile: ChapterStyleProfiler.Profile, groups: List<SameLineCandidateGroup>): String {
        val confirmed = revision.chapters.count { it.state == ResolveState.CONFIRMED }
        val provisional = revision.chapters.count { it.state == ResolveState.PROVISIONAL }
        val rejected = revision.chapters.count { it.state == ResolveState.REJECTED }
        val tocEntries = revision.tocBlocks.sumOf { it.entryCount }
        val familyDist = profile.familyCounts.entries.sortedByDescending { it.value }
            .joinToString(" ") { "${it.key.name} ${it.value}" }

        val sb = StringBuilder()
        sb.appendLine("检测到 ${confirmed + provisional} 章（CONFIRMED $confirmed / PROVISIONAL $provisional / REJECTED $rejected）")
        sb.appendLine("卷 ${revision.volumes.size} 个，目录块 ${revision.tocBlocks.size} 个（$tocEntries 条）")
        sb.appendLine("候选总数 ${groups.size}，规则分布: $familyDist")
        val lowConf = revision.chapters.filter { it.state == ResolveState.PROVISIONAL || it.state == ResolveState.REJECTED }
            .take(5)
        if (lowConf.isNotEmpty()) {
            sb.appendLine("低置信:")
            for (c in lowConf) sb.appendLine("  L${c.anchorLine} [${c.state}] ${c.titleRaw} (score=${"%.1f".format(c.finalScore)})")
        }
        return sb.toString()
    }
}
