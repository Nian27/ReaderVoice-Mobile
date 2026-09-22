package com.readervoice.app

import java.io.File
import org.json.JSONObject

/**
 * R4（ADR-056 / PLAN-20260918-062）：**阅读位置是一级 Book 数据**。
 *
 * ## 为什么不能留在 Activity 里
 *
 * 参考产品（Legado 3.x）把"看到哪一章、章内哪个位置、什么时候看的"持久化在 **books 表**里
 * （`durChapterIndex / durChapterPos / durChapterTime`）。这不是 UI 细节：
 * ```
 * 书架        → "继续阅读 第 126 章"
 * 目录        → 高亮当前章、滚到当前位置
 * 阅读器      → 打开就回到原位（而不是每次从第 1 章）
 * Director    → 只准备当前位置附近的剧本（阅读驱动预取）
 * ```
 * 位置一旦只存在 Activity 里，上面四件事全部做不了（我们的现状）。
 *
 * ## 存储
 *
 * ```
 * files/books/<bookId>/reading_state.json
 * {
 *   "chapterId": 13434,          // canonical 章节稳定 id（不用 ordinal：章节集合会变）
 *   "chapterOrdinal": 1,         // 展示用
 *   "paragraphIndex": 37,        // 章内段落序号（从 0 起）
 *   "segmentOrdinal": 37,        // 章内可朗读片段序号（Director 预取用）
 *   "charOffset": 0,             // 段内字符偏移（书签不变量 6：不存 position_ms）
 *   "updatedAt": 1789729764732
 * }
 * ```
 * 与书签一致：**存结构位置（段+偏移），不存屏幕/时间位置**（重排、改字号、重新生成都不失效）。
 */
data class ReadingState(
    /** canonical 章节 id（= 锚点 byteStart）。**持久化主键**。 */
    val chapterId: Long,
    /** 章序号（展示用；章节集合变化时可能变）。 */
    val chapterOrdinal: Int,
    /** 章内段落序号（0-based）。 */
    val paragraphIndex: Int,
    /** 章内可朗读片段序号（0-based）—— Director 预取的坐标。 */
    val segmentOrdinal: Int,
    /** 段内字符偏移。 */
    val charOffset: Int,
    val updatedAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("chapterId", chapterId)
        .put("chapterOrdinal", chapterOrdinal)
        .put("paragraphIndex", paragraphIndex)
        .put("segmentOrdinal", segmentOrdinal)
        .put("charOffset", charOffset)
        .put("updatedAt", updatedAt)
        .toString()

    companion object {
        fun parse(json: String): ReadingState? = runCatching {
            val o = JSONObject(json)
            ReadingState(
                chapterId = o.getLong("chapterId"),
                chapterOrdinal = o.optInt("chapterOrdinal", -1),
                paragraphIndex = o.optInt("paragraphIndex", 0),
                segmentOrdinal = o.optInt("segmentOrdinal", o.optInt("paragraphIndex", 0)),
                charOffset = o.optInt("charOffset", 0),
                updatedAt = o.optLong("updatedAt", 0),
            )
        }.getOrNull()
    }
}

/** 阅读位置的读写（原子替换，避免半写）。 */
object ReadingStateStore {

    private fun file(packageDir: File) = File(packageDir, "reading_state.json")

    fun load(packageDir: File): ReadingState? {
        val f = file(packageDir)
        if (!f.isFile) return null
        return ReadingState.parse(runCatching { f.readText() }.getOrDefault(""))
    }

    fun save(packageDir: File, state: ReadingState) {
        runCatching {
            val f = file(packageDir)
            val tmp = File(packageDir, "reading_state.json.tmp")
            tmp.writeText(state.toJson())
            if (!tmp.renameTo(f)) {
                f.writeText(state.toJson())
                tmp.delete()
            }
        }
    }

    /** 记录"读到某段"（阅读器滚动/翻页时调用；同时更新段落序号）。 */
    fun record(
        packageDir: File,
        chapterId: Long,
        chapterOrdinal: Int,
        paragraphIndex: Int,
        segmentOrdinal: Int,
        charOffset: Int = 0,
    ): ReadingState {
        val s = ReadingState(
            chapterId = chapterId,
            chapterOrdinal = chapterOrdinal,
            paragraphIndex = paragraphIndex,
            segmentOrdinal = segmentOrdinal,
            charOffset = charOffset,
            updatedAt = System.currentTimeMillis(),
        )
        save(packageDir, s)
        return s
    }
}
