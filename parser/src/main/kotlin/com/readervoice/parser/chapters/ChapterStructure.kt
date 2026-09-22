package com.readervoice.parser.chapters

import com.readervoice.parser.source.PhysicalLine

/**
 * TASK-020 结构数据对象（§52）：候选/卷/章/TOC/修订/覆盖。
 * Chapter 不复制正文——content_end 由下一结构 anchor 推导（§53）。
 */

data class ChapterCandidate(
    val candidateId: Long,
    val lineNo: Int,                // PhysicalLine 1-based
    val ruleId: String,
    val family: RuleFamily,
    val target: RuleTarget,
    val rawLine: String,
    val rawTitle: String,           // 匹配文本原样（legacy 标题语义）
    val cleanTitle: String,         // 去序号/空白（展示用，不改 source）
    val serialRaw: String?,
    val serialValue: Int?,

    val regexScore: Double = 0.0,
    val rulePriorityScore: Double = 0.0,
    val lengthScore: Double = 0.0,
    val spacingScore: Double = 0.0,
    val styleScore: Double = 0.0,
    val sequenceScore: Double = 0.0,
    val neighborScore: Double = 0.0,
    val tocScore: Double = 0.0,
    val volumeScore: Double = 0.0,
    val finalScore: Double = 0.0,
    val evidence: List<String> = emptyList(),
)

/** 同一行多规则命中（§13）：winning + alternatives。 */
data class SameLineCandidateGroup(
    val lineNo: Int,
    val winning: ChapterCandidate,
    val alternatives: List<ChapterCandidate>,
)

data class Volume(
    val volumeId: Long,
    val bookId: String,
    val volumeIndex: Int,
    val serialRaw: String?,
    val serialValue: Int?,
    val title: String,
    val startLine: Int,
    var endLine: Int? = null,
    val confidence: Double,
)

data class Chapter(
    val chapterId: Long,
    val bookId: String,
    val volumeId: Long?,
    val chapterIndex: Int,          // 书内顺序（连续递增，不管 serial 重复/缺失）
    val serialValue: Int?,
    val serialPart: String? = null, // 上/中/下
    val titleRaw: String,
    val titleDisplay: String,
    val anchorLine: Int,
    val anchorByteStart: Int,
    val anchorCodepointStart: Int,
    val contentStartLine: Int,      // anchor 下一行（正文起点）
    var contentEndLine: Int? = null,// 下一结构 anchor 推导
    val state: ResolveState,
    val finalScore: Double,
    val evidence: List<String>,
)

enum class ResolveState { CONFIRMED, PROVISIONAL, REJECTED, TOC_ENTRY }

data class TocBlock(
    val startLine: Int,
    val endLine: Int,
    val entryCount: Int,
)

data class TocEntry(
    val lineNo: Int,
    val serialRaw: String?,
    val serialValue: Int?,
    val title: String,
)

/** TOC 条目与正文 Chapter 的链接（§20）：只作 evidence，不凭空造 Chapter。 */
data class TocBodyLink(
    val tocLineNo: Int,
    val bodyLineNo: Int?,
    val serialValue: Int?,
    val titleSimilarity: Double,
)

/** 结构修订（§52）：解析结果版本化，供用户修正回滚。 */
data class StructureRevision(
    val revisionId: Long,
    val bookId: String,
    val createdAt: String,
    val reason: String,
    val volumes: List<Volume>,
    val chapters: List<Chapter>,
    val tocBlocks: List<TocBlock>,
    val parentRevisionId: Long? = null,
)

/** 用户结构覆盖（§38/§39）：USER_LOCKED 优先于自动 Resolver。 */
data class StructureOverride(
    val overrideId: String,
    val bookId: String,
    val type: OverrideType,
    val targetLine: Int? = null,
    val payload: Map<String, String> = emptyMap(),
    val userLocked: Boolean = true,
)

enum class OverrideType { ADD_CHAPTER, REMOVE_CHAPTER, CHANGE_TYPE, CHANGE_TITLE, ASSIGN_VOLUME }
