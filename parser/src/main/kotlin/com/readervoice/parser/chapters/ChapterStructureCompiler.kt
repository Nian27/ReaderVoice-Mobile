package com.readervoice.parser.chapters

import com.readervoice.parser.source.PhysicalLine

/**
 * ChapterStructureCompiler（TASK-020 门面）：Legacy Rules → Candidate → Conflict → TOC → Volume
 * → Style → Global Resolver → StructureRevision（+ Preview）。
 */
class ChapterStructureCompiler(
    private val rules: List<ChapterRule>,
    private val lineLengthCap: Int = 1024,
    private val confirmThreshold: Double = 2.0,
    private val provisionalThreshold: Double = 1.1,
) {

    data class PipelineResult(
        val candidates: List<ChapterCandidate>,
        val groups: List<SameLineCandidateGroup>,
        val toc: TocDetector.TocResult,
        val volumes: List<Volume>,
        val profile: ChapterStyleProfiler.Profile,
        val revision: StructureRevision,
        val scanTimeMs: Double,
        val resolveTimeMs: Double,
    )

    fun compile(lines: List<PhysicalLine>, bookId: String): PipelineResult {
        val t0 = System.nanoTime()
        val scanner = ChapterCandidateScanner(rules, lineLengthCap)
        val candidates = scanner.scan(lines, bookId)
        val groups = SameLineConflictResolver.resolve(candidates)
        val t1 = System.nanoTime()

        val toc = TocDetector.detect(groups, lines.size)
        val volumes = VolumeResolver.resolve(groups, bookId)
        val profile = ChapterStyleProfiler().profile(groups)
        val revision = GlobalStructureResolver(confirmThreshold, provisionalThreshold)
            .resolve(groups, lines, bookId, toc, volumes, profile)
        val t2 = System.nanoTime()

        return PipelineResult(
            candidates, groups, toc, volumes, profile, revision,
            scanTimeMs = (t1 - t0) / 1_000_000.0,
            resolveTimeMs = (t2 - t1) / 1_000_000.0,
        )
    }
}
