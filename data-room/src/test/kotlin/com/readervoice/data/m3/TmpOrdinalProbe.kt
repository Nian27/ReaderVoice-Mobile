package com.readervoice.data.m3

import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.ConfirmedChapterView
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import kotlin.test.Test

/** 临时探针：求真机 chapterId=13434 在确认章节视图里的 ordinal（用完即删）。 */
class TmpOrdinalProbe {
    @Test
    fun probe() {
        val src = File("../runs/mobile_005_director_real/candidate_gate/source.txt")
        org.junit.jupiter.api.Assumptions.assumeTrue(src.isFile, "source.txt 不在")
        val bytes = src.readBytes()
        val det = EncodingDetector.detect(bytes)
        val bookId = "book-d22a7b3878ee"
        val lines = PhysicalLineScanner().scan(bytes, det.charset, det.bomBytes, bookId).lines
        val rules = LegacyChapterRuleAdapter.fromJson(
            javaClass.classLoader.getResourceAsStream("legacy/txtTocRule.json")!!,
        )
        val structure = ChapterStructureCompiler(rules).compile(lines, bookId)
        val confirmed = ConfirmedChapterView.of(structure.revision, lines.size)
        println("[ordinal] confirmed=${confirmed.size}")
        for (c in confirmed.take(6)) println("[ordinal]   ${c.ordinal} id=${c.chapterId} title=${c.title}")
        val hit = confirmed.firstOrNull { it.chapterId == 13434L }
        println("[ordinal] ★ chapterId=13434 -> ordinal=${hit?.ordinal} title=${hit?.title} " +
            "start=${hit?.contentStartLine} end=${hit?.contentEndLine}")
    }
}
