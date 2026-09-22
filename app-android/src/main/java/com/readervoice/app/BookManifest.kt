package com.readervoice.app

import org.json.JSONObject

/**
 * Book Package 的元数据；source.txt 只保存原始字节，展示/索引一律另存。
 */
data class BookManifest(
    val bookId: String,
    val title: String,
    val sourceFileName: String,
    val sourceSha256: String,
    val sourceBytes: Long,
    val importedAtEpochMs: Long,
    val structureState: String,
    val chapterCount: Int,
    val structureFormatVersion: Int = 3,
    /** AUTO 才允许被规则格式迁移重编译；人工修订必须写成 USER_EDITED。 */
    val structureOrigin: String = STRUCTURE_ORIGIN_AUTO_LEGACY_RULEPACK,
)

const val STRUCTURE_ORIGIN_AUTO_LEGACY_RULEPACK = "AUTO_LEGACY_RULEPACK"
const val STRUCTURE_ORIGIN_USER_EDITED = "USER_EDITED"

object BookManifestCodec {
    fun encode(manifest: BookManifest): String = JSONObject().apply {
        put("book_id", manifest.bookId)
        put("title", manifest.title)
        put("source_file_name", manifest.sourceFileName)
        put("source_sha256", manifest.sourceSha256)
        put("source_bytes", manifest.sourceBytes)
        put("imported_at_epoch_ms", manifest.importedAtEpochMs)
        put("structure_state", manifest.structureState)
        put("chapter_count", manifest.chapterCount)
        put("structure_format_version", manifest.structureFormatVersion)
        put("structure_origin", manifest.structureOrigin)
    }.toString(2)

    fun decode(text: String): BookManifest {
        val json = JSONObject(text)
        return BookManifest(
            bookId = json.getString("book_id"),
            title = json.getString("title"),
            sourceFileName = json.getString("source_file_name"),
            sourceSha256 = json.getString("source_sha256"),
            sourceBytes = json.getLong("source_bytes"),
            importedAtEpochMs = json.getLong("imported_at_epoch_ms"),
            structureState = json.getString("structure_state"),
            chapterCount = json.getInt("chapter_count"),
            structureFormatVersion = json.optInt("structure_format_version", 1),
            // v1/v2 没有人工修订入口，缺省值只能解释为旧自动索引。
            structureOrigin = json.optString("structure_origin", STRUCTURE_ORIGIN_AUTO_LEGACY_RULEPACK),
        )
    }
}
