package com.readervoice.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.readervoice.data.script.ChapterIndexQuery
import com.readervoice.parser.chapters.ConfirmedChapter
import java.io.File

data class ChapterIndexEntry(
    val ordinal: Int,
    val title: String,
    /** 章节稳定 id（= anchor byteStart）；阅读位置 / 剧本缓存都用它做键。 */
    val anchorByte: Long,
    val state: String,
    /**
     * 正文行区间（1-based，含端点）。**阅读器只读这一段** ——
     * 有它就不必为了显示一章而重编译整本结构（这是"打开就快"的关键）。
     * 旧包（该列不存在）为 -1，调用方需回退到重编译路径。
     */
    val contentStartLine: Int = -1,
    val contentEndLine: Int = -1,
) {
    val hasRange: Boolean get() = contentStartLine >= 1 && contentEndLine >= contentStartLine
}

data class BookIndexResult(
    val structureState: String,
    val chapterCount: Int,
    val structureFormatVersion: Int = 3,
)

/** Book Package 的导航索引。结构编译只持久化 CONFIRMED anchor，不复制原文。 */
object BookDatabase {
    private const val TABLE = "chapter_index"
    private const val AUDIO_TABLE = "audio_asset"

    /**
     * 用 **canonical chapters** 原子替换导航索引。
     *
     * ★ R1（ADR-056）：入口只接受 `ConfirmedChapter`（`ConfirmedChapterView` 的产物），
     *   不再接受 `revision.chapters` 原始候选列表 —— 过滤必须发生在**调用方之前**，
     *   否则"生产只吃 canonical"这条纪律就没有编译期约束，只剩注释。
     *
     * ★ 同时修掉一个真实 bug：旧实现把 `chapterIndex` 写进 `ordinal`（主键），
     *   而 `chapterIndex` 实测有 `8 → 0` 回退 ⇒ 目录顺序错乱/主键冲突。
     *   现在写 canonical `ordinal`（连续、按 anchorByteStart 升序）。
     */
    fun replaceWithConfirmedChapters(packageDir: File, chapters: List<ConfirmedChapter>): BookIndexResult {
        val dbFile = File(packageDir, "book.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            ensureSchema(db)
            db.beginTransaction()
            try {
                // 仅用于 PENDING 占位升级或尚未提交的 staging 包；不触碰用户修订的真实结构。
                db.delete(TABLE, null, null)
                chapters.forEach { chapter ->
                    val values = ContentValues().apply {
                        put("ordinal", chapter.ordinal)
                        // titleRaw 是保留源样的导航标签；titleDisplay 可能刻意省略"第 N 章"，
                        // 因此不能单独作为用户可见标题。
                        put("title", chapter.titleRaw.trim().ifEmpty { chapter.title.trim() })
                        // anchor_byte 同时就是**章节稳定 id**（chapterId 内容派生自锚点 byteStart）
                        put("anchor_byte", chapter.chapterId)
                        put("state", chapter.state.name)
                        // ★ 正文区间：阅读器据此只读需要的行，不必重编译整本结构
                        put("content_start_line", chapter.contentStartLine)
                        put("content_end_line", chapter.contentEndLine)
                    }
                    db.insertOrThrow(TABLE, null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return BookIndexResult(
            structureState = if (chapters.isEmpty()) "STRUCTURE_EMPTY" else "STRUCTURE_READY",
            chapterCount = chapters.size,
        )
    }

    /**
     * 读章节索引。
     *
     * ★ 必须先探测列再拼 SQL：旧包（本字段引入前生成的 `book.db`）没有
     *   `content_start_line/content_end_line`，直接按新列 SELECT 会
     *   `SQLiteException: no such column` 把目录页/阅读器**直接崩掉**
     *   （2026-09-18 真机事故：点啥闪退啥）。读路径不做破坏性迁移 ——
     *   只按现有列读，区间缺失时置 -1，由调用方回退到重编译路径（回退时会把新区间写回）。
     */
    fun chapters(packageDir: File): List<ChapterIndexEntry> {
        val dbFile = File(packageDir, "book.db")
        if (!dbFile.isFile) return emptyList()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val cols = columnNames(db, TABLE)
            if (cols.isEmpty()) return emptyList()
            val hasRange = ChapterIndexQuery.hasRange(cols)
            db.rawQuery(ChapterIndexQuery.chapters(cols), null).use { cursor ->
                val out = ArrayList<ChapterIndexEntry>()
                while (cursor.moveToNext()) {
                    out += ChapterIndexEntry(
                        ordinal = cursor.getInt(0),
                        title = cursor.getString(1),
                        anchorByte = cursor.getLong(2),
                        state = cursor.getString(3),
                        contentStartLine = if (hasRange) cursor.getInt(4) else -1,
                        contentEndLine = if (hasRange) cursor.getInt(5) else -1,
                    )
                }
                return out
            }
        }
    }

    /** 表的实际列集合（缺表返回空集）。 */
    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) if (nameIdx >= 0) out += c.getString(nameIdx)
            }
        }
        return out
    }


    /**
     * 同步"已缓存的音频资产"索引（供播放器发现可播文件；TTS 未接入时通常为空）。
     * Scan is for manually provisioned M2 fixtures and file-cache recovery.
     */
    fun syncCachedAudioAssets(packageDir: File): List<CachedAudioAsset> {
        val discovered = AudioCacheScanner.scan(packageDir)
        val dbFile = File(packageDir, "book.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            ensureSchema(db)
            db.beginTransaction()
            try {
                discovered.forEach { asset ->
                    val values = ContentValues().apply {
                        put("relative_path", asset.relativePath)
                        put("bytes", asset.bytes)
                    }
                    db.insertWithOnConflict(AUDIO_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    db.update(AUDIO_TABLE, values, "relative_path=?", arrayOf(asset.relativePath))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return discovered
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "ordinal INTEGER PRIMARY KEY, title TEXT NOT NULL, anchor_byte INTEGER NOT NULL, state TEXT NOT NULL," +
                "content_start_line INTEGER NOT NULL DEFAULT -1, content_end_line INTEGER NOT NULL DEFAULT -1)"
        )
        // 非破坏迁移：旧包缺正文区间列 ⇒ 加列（默认 -1，阅读器回退到重编译路径；不删用户数据）
        for (col in listOf(
            "content_start_line INTEGER NOT NULL DEFAULT -1",
            "content_end_line INTEGER NOT NULL DEFAULT -1",
        )) {
            runCatching { db.execSQL("ALTER TABLE $TABLE ADD COLUMN $col") }
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $AUDIO_TABLE (" +
                "relative_path TEXT PRIMARY KEY, bytes INTEGER NOT NULL)"
        )
    }
}
