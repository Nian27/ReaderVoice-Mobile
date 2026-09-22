package com.readervoice.parser.paragraph

/**
 * ParagraphOverrideStore（TASK-030 §53-§55）：
 * backend join/split + USER lock（自动重解析不覆盖）；局部失效事件接口（下游未建先冻结）。
 */
class ParagraphOverrideStore {

    /** 用户修正事件。 */
    data class CorrectionEvent(
        val eventId: String,
        val bookId: String,
        val type: String,          // PARAGRAPH_JOIN / PARAGRAPH_SPLIT
        val leftParagraphId: Long,
        val rightParagraphId: Long? = null,
        val sourceBoundaryLine: Int? = null,
        val userLocked: Boolean = true,
        val createdAt: String = java.time.Instant.now().toString(),
    )

    private val corrections = mutableListOf<CorrectionEvent>()
    private val lockedBoundaries = mutableSetOf<Pair<Int, Int>>() // (leftLineNo, rightLineNo)

    fun join(leftId: Long, rightId: Long, bookId: String): CorrectionEvent {
        val e = CorrectionEvent("join-${System.nanoTime()}", bookId, "PARAGRAPH_JOIN", leftId, rightId)
        corrections += e
        return e
    }

    fun split(paragraphId: Long, sourceBoundaryLine: Int, bookId: String): CorrectionEvent {
        val e = CorrectionEvent("split-${System.nanoTime()}", bookId, "PARAGRAPH_SPLIT", paragraphId, sourceBoundaryLine = sourceBoundaryLine)
        corrections += e
        return e
    }

    /** 用户锁定的行间边界（自动重解析不得覆盖）。 */
    fun lockBoundary(leftLineNo: Int, rightLineNo: Int) {
        lockedBoundaries += leftLineNo to rightLineNo
    }

    fun isLocked(leftLineNo: Int, rightLineNo: Int): Boolean = (leftLineNo to rightLineNo) in lockedBoundaries

    fun listCorrections(): List<CorrectionEvent> = corrections.toList()

    /** 修正产生的下游失效事件（§55：只失效局部段落及下游，不整书）。 */
    fun invalidationFor(event: CorrectionEvent): DependencyInvalidationEvent {
        val ids = if (event.type == "PARAGRAPH_JOIN") listOf(event.leftParagraphId, event.rightParagraphId ?: -1)
        else listOf(event.leftParagraphId)
        return DependencyInvalidationEvent(
            eventId = "inv-${event.eventId}",
            paragraphIds = ids.filter { it > 0 },
            reason = event.type,
        )
    }
}
