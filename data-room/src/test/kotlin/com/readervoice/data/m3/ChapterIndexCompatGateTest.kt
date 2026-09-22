package com.readervoice.data.m3

import com.readervoice.data.script.ChapterIndexQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R1/R4 兼容层门：**加列不得让旧包崩溃**。
 *
 * ## 这条门是怎么来的（真机事故复盘）
 *
 * 给 `chapter_index` 加了 `content_start_line/content_end_line` 之后，读路径直接按新列 SELECT，
 * 而用户旧包（本字段引入前生成）的 `book.db` 里没有这两列：
 * ```
 * android.database.sqlite.SQLiteException: no such column: content_start_line
 *   at BookDatabase.chapters(BookDatabase.kt:85)
 *   at ChapterListActivity.onCreate(ChapterListActivity.kt:40)   ← 点啥闪退啥
 * ```
 * 本可用一条纯函数用例挡住 —— 所以把"缺列怎么办"抽成 [ChapterIndexQuery] 并在此钉死。
 */
class ChapterIndexCompatGateTest {

    @Test
    fun `old schema without range columns still queries and degrades to no-range`() {
        val old = setOf("ordinal", "title", "anchor_byte", "state")
        assertFalse(ChapterIndexQuery.hasRange(old), "旧 schema 必须被识别为无区间")
        val sql = ChapterIndexQuery.chapters(old)
        assertTrue(sql.contains("SELECT ordinal,title,anchor_byte,state "), "旧 schema 查询列不对: $sql")
        assertFalse(sql.contains("content_start_line"), "旧 schema 查询不得引用新列（否则真机崩溃）: $sql")
    }

    @Test
    fun `new schema queries the range columns`() {
        val new = setOf("ordinal", "title", "anchor_byte", "state", "content_start_line", "content_end_line")
        assertTrue(ChapterIndexQuery.hasRange(new))
        val sql = ChapterIndexQuery.chapters(new)
        assertTrue(sql.contains("content_start_line") && sql.contains("content_end_line"), "新 schema 应读区间: $sql")
        assertTrue(sql.endsWith("ORDER BY ordinal"), "章节顺序必须按 canonical ordinal: $sql")
    }

    @Test
    fun `half migrated schema is treated as no range`() {
        // 只成功加了一列（例如迁移中途失败）⇒ 仍按无区间读，绝不假设两列都在
        val half = setOf("ordinal", "title", "anchor_byte", "state", "content_start_line")
        assertFalse(ChapterIndexQuery.hasRange(half), "只加了一列也必须降级")
        assertFalse(ChapterIndexQuery.chapters(half).contains("content_end_line"))
    }

    @Test
    fun `empty column set yields the legacy query shape`() {
        assertEquals(
            "SELECT ordinal,title,anchor_byte,state FROM chapter_index ORDER BY ordinal",
            ChapterIndexQuery.chapters(emptySet()),
        )
    }
}
