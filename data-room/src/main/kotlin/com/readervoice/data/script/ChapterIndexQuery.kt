package com.readervoice.data.script

/**
 * 章节索引的**纯查询构造**（R1/R4 兼容层）。
 *
 * ## 为什么值得单独存在
 *
 * 2026-09-18 真机事故：给 `chapter_index` 加了正文区间列后，读路径直接按新列 SELECT，
 * 而用户旧包里的 `book.db` 没有这两列 ⇒
 * ```
 * android.database.sqlite.SQLiteException: no such column: content_start_line
 *   at BookDatabase.chapters(...)
 *   at ChapterListActivity.onCreate(...)     ← 点啥闪退啥
 * ```
 * 结论：「加列」这类 schema 演进必须在**读路径**上也做兼容，而且这个决策应该是一个
 * **不需要 Android runtime 就能测**的纯函数（本对象），而不是埋在 SQLite 调用里靠真机发现。
 *
 * 读路径**不做破坏性迁移**：只按现有列读，缺列为 -1，由调用方回退到重编译路径。
 */
object ChapterIndexQuery {

    const val TABLE = "chapter_index"

    const val COL_RANGE_START = "content_start_line"
    const val COL_RANGE_END = "content_end_line"

    /** 该表是否已有正文区间列。 */
    fun hasRange(existingColumns: Collection<String>): Boolean =
        COL_RANGE_START in existingColumns && COL_RANGE_END in existingColumns

    /**
     * 按实际存在的列拼 SELECT。
     *
     * ★ 调用方必须用 [hasRange] 决定列下标（有区间时 4/5，无区间时置 -1），
     *   两边共用同一个判定，避免"拼了旧 SQL 却按新下标读"这类错位。
     */
    fun chapters(existingColumns: Collection<String>): String =
        if (hasRange(existingColumns)) {
            "SELECT ordinal,title,anchor_byte,state,$COL_RANGE_START,$COL_RANGE_END FROM $TABLE ORDER BY ordinal"
        } else {
            "SELECT ordinal,title,anchor_byte,state FROM $TABLE ORDER BY ordinal"
        }
}
