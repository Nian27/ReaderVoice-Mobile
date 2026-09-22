package com.readervoice.parser.paragraph

/** TASK-030 数据模型（§4/§5/§41/§44/§45/§47/§82）。 */

/** LineBoundary 类型（§4）。 */
enum class LineBoundaryType { SOFT_WRAP, HARD_PARAGRAPH, AUTHOR_LINE_BREAK, STRUCTURAL_BREAK, UNCERTAIN, USER_JOIN, USER_BREAK }

/** 布局画像类型（§7）。POETRY_LIKE 是 Layout Profile；POETRY 是 BlockType——不混为一谈。 */
enum class TextLayoutProfile {
    PARAGRAPH_PER_LINE, FIXED_WIDTH_HARD_WRAP, BLANK_LINE_PARAGRAPH, INDENTED_PARAGRAPH,
    MIXED, SCRIPT_DIALOGUE, POETRY_LIKE, UNKNOWN,
}

/** 特殊块类型（§28/§50）。 */
enum class BlockType {
    PROSE, POETRY, LYRICS, SCRIPT, MESSAGE_LOG, LIST, LETTER, SCREEN_TEXT,
    SEPARATOR, BOILERPLATE, CHAPTER_TITLE, VOLUME_TITLE, TOC_ENTRY, UNKNOWN,
}

/** 段落修订原因（§42）。 */
enum class RevisionReason { AUTO_REFLOW, USER_JOIN, USER_SPLIT, RULE_UPDATE, LAYOUT_PROFILE_UPDATE, SOURCE_REVISION }

/** 变换类型（§47）。 */
enum class TransformType { IDENTITY, REMOVED_NEWLINE, INSERTED_SPACE, REMOVED_INDENT }

/** 阅读策略（§49）。 */
enum class ReadPolicy { NORMAL, CHAPTER_TITLE, VOLUME_TITLE, TOC_ENTRY, SKIP_READ }

/** 空行表示（§86 冻结方案 ADR-021）：空行不生成可朗读段落，仅作为 Boundary 证据；SpacingBlock 仅记录区间。 */
data class SpacingBlock(val startLine: Int, val endLine: Int, val blankLineCount: Int)

/** LineBoundary（§5）。 */
data class LineBoundary(
    val boundaryId: Long,
    val bookId: String,
    val chapterId: Long?,
    val leftLineNo: Int,
    val rightLineNo: Int,
    val type: LineBoundaryType,
    val confidence: Double,
    val evidenceMask: Long,          // 运行期 compact（§79）
    val evidence: List<String>,      // 调试/报告展开
    val layoutProfileId: String?,
    val ruleVersion: String = "task030-v1",
    val userLocked: Boolean = false,
    val createdRevision: Long = 0,
)

/** 段落来源区间（§44）：行级 span，不要求连续 normalized offset。 */
data class SourceSpan(val lineNo: Int, val byteStart: Int, val byteEnd: Int, val charStart: Int, val charEnd: Int)

/** NormalizedSpan（§47/§48）：piecewise 映射；synthetic 空格不伪造 source offset。 */
data class NormalizedSpan(
    val normalizedStart: Int,
    val normalizedEnd: Int,
    val sourceLineNo: Int?,
    val sourceCodepointStart: Int?,
    val sourceCodepointEnd: Int?,
    val transformType: TransformType,
    val synthetic: Boolean = false, // INSERTED_SPACE 时为 true
)

/** LogicalParagraphRevision（§41）。 */
data class LogicalParagraph(
    val paragraphId: Long,       // 稳定语义 ID
    val revisionId: Long,        // 版本
    val bookId: String,
    val chapterId: Long?,
    val sourceRevisionId: String? = null, // ADR-016/§96：绑定 source revision
    val paragraphIndex: Int,
    val sourceSpans: List<SourceSpan>,
    val normalizedText: String,
    val blockType: BlockType,
    val readPolicy: ReadPolicy = ReadPolicy.NORMAL,
    val boundaryConfidence: Double,
    val parentRevision: Long? = null,
    val revisionReason: RevisionReason = RevisionReason.AUTO_REFLOW,
    val active: Boolean = true,
)

/** CrossParagraphLink（§37）。 */
enum class LinkType { SPEECH_CUE, QUOTE_CONTINUATION, CONTINUATION, SCENE_CONTINUATION, NONE }

data class CrossParagraphLink(
    val linkId: Long,
    val leftParagraphId: Long,
    val rightParagraphId: Long,
    val type: LinkType,
    val confidence: Double,
    val evidence: List<String>,
)

/** LayoutRegion（§82）：profile 分段；每 Boundary 引用所属 region。 */
data class LayoutRegion(
    val regionId: Int,
    val startLine: Int,
    val endLine: Int,
    val profileType: TextLayoutProfile,
    val confidence: Double,
)

/** 依赖失效事件（§55）：下游模块未建，先冻结接口。 */
data class DependencyInvalidationEvent(
    val eventId: String,
    val paragraphIds: List<Long>,
    val reason: String,
    val invalidatedDomains: List<String> = listOf("cross_links", "semantic_segment", "render_unit"),
)
