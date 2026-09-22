package com.readervoice.parser.source

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.json.JSONObject
import org.json.JSONArray

/**
 * ImportPipeline（TASK-010 §3/§4/§5/§6/§7/§28）：不可变 TXT 导入 + 原子提交 + 幂等恢复。
 *
 * 状态机：NEW → COPYING → HASHING → DECODING → INDEXING_LINES → IMPORTED；失败 → FAILED_IMPORT。
 * 磁盘布局（等价原子语义）：
 *   <root>/incoming/<bookId>.tmp       复制中的临时文件
 *   <root>/books/<bookId>.txt          已提交的不可变源文件（原子 rename 进入）
 *   <root>/books/<bookId>.status.json  状态（COPYING/HASHING/DECODING/INDEXING/IMPORTED/FAILED_IMPORT）
 *   <root>/index.json                  全局索引（原子写：.tmp → rename）
 *
 * 恢复规则：进程死亡后重新 import 同一源文件 → 发现残留 tmp/status 未完成 → 清理后重来；
 * 已完成 IMPORTED 的 sha256 命中 → EXACT_DUPLICATE，不复制第二份。
 * 同名文件 hash 不同 → 新 book_id + parent_source_file_id 记录（revised edition，不做全文 diff）。
 *
 * 注意：DB 层（Room）在 TASK-040 落地；本类用 status.json 模拟等价原子语义。
 */
class ImportPipeline(
    private val root: Path,
) {
    private val incomingDir: Path = root.resolve("incoming")
    private val booksDir: Path = root.resolve("books")
    private val indexFile: Path = root.resolve("index.json")

    data class BookEntry(
        val bookId: String,
        val sha256: String,
        val byteSize: Long,
        val fileName: String,
        val localPath: String,
        val state: String,
        val importedAt: String,
        val parentSourceFileId: String? = null,
        val charset: String? = null,
        val confidence: Double? = null,
    )

    sealed class Outcome {
        data class Imported(val bookId: String, val entry: BookEntry, val lines: List<PhysicalLine>) : Outcome()
        data class ExactDuplicate(val bookId: String, val entry: BookEntry) : Outcome()
        /** 同文件名不同 hash：新 Book，无 parent 链（文件名不是版本证据，ADR-016） */
        data class DistinctSource(val bookId: String, val entry: BookEntry) : Outcome()
        /** 显式 reimport/update（或显式 parent）：同一逻辑 Book 的新 SourceRevision */
        data class SourceRevision(val newBookId: String, val parentBookId: String, val entry: BookEntry) : Outcome()
        data class Failed(val error: ImportError, val message: String) : Outcome()
    }

    init {
        Files.createDirectories(incomingDir)
        Files.createDirectories(booksDir)
    }

    /**
     * 导入 [source] 文件。
     * @param originalUri 用户来源（URI/路径），仅记录
     * @param charsetOverride 用户指定编码（user locked）；null 则自动检测
     * @param collectCharOffsets 是否收集 char→byte 精确表（大文件按需）
     * @param parentBookId 显式"更新这本书"时传入 → SOURCE_REVISION；null 时同名不同 hash 只是 DISTINCT_SOURCE（ADR-016）
     */
    fun import(
        source: Path,
        originalUri: String,
        charsetOverride: CharsetKind? = null,
        collectCharOffsets: Boolean = true,
        parentBookId: String? = null,
    ): Outcome {
        val bytes = try {
            Files.readAllBytes(source)
        } catch (e: java.nio.file.AccessDeniedException) {
            return Outcome.Failed(ImportError.PERMISSION_ERROR, e.message ?: "")
        } catch (e: java.io.IOException) {
            return Outcome.Failed(ImportError.IO_ERROR, e.message ?: "")
        }
        if (bytes.isEmpty()) {
            // 空文件允许导入（0 行），但编码未知
        }
        val sha = sha256(bytes) ?: return Outcome.Failed(ImportError.HASH_ERROR, "sha256 failed")
        val fileName = source.fileName.toString()

        // 1. exact duplicate：sha256 命中已 IMPORTED
        loadIndex().firstOrNull { it.sha256 == sha && it.state == "IMPORTED" }?.let { dup ->
            return Outcome.ExactDuplicate(dup.bookId, dup)
        }
        // 2. 显式 parent（用户"更新这本书"）→ SOURCE_REVISION；否则同名不同 hash 不自动成链
        val explicitParent = parentBookId?.takeIf { id -> loadIndex().any { it.bookId == id && it.state == "IMPORTED" } }
        val parentId = explicitParent

        val bookId = "book-" + sha.take(12)
        val statusFile = booksDir.resolve("$bookId.status.json")
        val finalFile = booksDir.resolve("$bookId.txt")

        // 3. 残留恢复：未完成的旧 tmp/status 清理（幂等重来）
        recover()

        // 4. COPYING：写 tmp + fsync
        writeStatus(statusFile, "COPYING", bookId)
        val tmp = incomingDir.resolve("$bookId.tmp")
        try {
            Files.newOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                // fsync（等价语义）
                (out as? java.io.FileOutputStream)?.fd?.sync()
            }
        } catch (e: java.io.IOException) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.IO_ERROR.name, e.message)
            return Outcome.Failed(ImportError.IO_ERROR, e.message ?: "")
        }

        // 5. HASHING（对已写 tmp 复验，防写入期间变化）
        writeStatus(statusFile, "HASHING", bookId)
        val tmpSha = sha256(Files.readAllBytes(tmp))
        if (tmpSha != sha) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.HASH_ERROR.name, "hash mismatch")
            return Outcome.Failed(ImportError.HASH_ERROR, "tmp hash mismatch")
        }

        // 6. DECODING + INDEXING_LINES（解码失败 → FAILED_IMPORT，源文件不动）
        writeStatus(statusFile, "DECODING", bookId)
        val detection = charsetOverride?.let {
            EncodingResult(it, 1.0, "user-override", userLocked = true, userOverrideCharset = it)
        } ?: EncodingDetector.detect(bytes)
        if (detection.confidence <= 0.0) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.UNSUPPORTED_ENCODING.name, "no candidate encoding")
            return Outcome.Failed(ImportError.UNSUPPORTED_ENCODING, "no candidate encoding")
        }

        // 先解码：非文本预检必须在置信度判定之前（二进制文件可能被多候选"勉强解码"）
        writeStatus(statusFile, "INDEXING_LINES", bookId)
        val scanResult = try {
            PhysicalLineScanner().scan(bytes, detection.charset, detection.bomBytes, bookId, collectCharOffsets)
        } catch (e: DecodeException) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.CORRUPT_TEXT.name, e.message)
            return Outcome.Failed(ImportError.CORRUPT_TEXT, e.message ?: "")
        }
        val lines = scanResult.lines
        // 非文本拒收：NUL/control 异常（避免乱码 Book；扩展名 .txt 不等于文本）
        val suspicious = lines.sumOf { l ->
            l.rawText.count { c -> c.code == 0 || c.code in 1..8 || c.code in 11..12 || c.code in 14..31 }
        }
        if (suspicious > bytes.size / 16) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.CORRUPT_TEXT.name, "binary/non-text content")
            return Outcome.Failed(ImportError.CORRUPT_TEXT, "binary/non-text content")
        }

        // 低置信：不自动判死，交给用户 override（backend API 支持预览/重解析）
        if (detection.confidence < 0.55 && !detection.userLocked) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.AMBIGUOUS_ENCODING.name, "confidence=${detection.confidence}")
            return Outcome.Failed(
                ImportError.AMBIGUOUS_ENCODING,
                "ambiguous encoding, top=${detection.charset.label} conf=${"%.2f".format(detection.confidence)}, alternatives=${detection.alternatives.map { it.label }}"
            )
        }

        // 7. 原子提交：tmp → rename 到 books/
        try {
            Files.move(tmp, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, finalFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.io.IOException) {
            writeStatus(statusFile, "FAILED_IMPORT", bookId, ImportError.OUT_OF_SPACE.name, e.message)
            return Outcome.Failed(ImportError.OUT_OF_SPACE, e.message ?: "")
        }

        // 8. 更新全局索引（原子写）
        val entry = BookEntry(
            bookId = bookId,
            sha256 = sha,
            byteSize = bytes.size.toLong(),
            fileName = fileName,
            localPath = finalFile.toString(),
            state = "IMPORTED",
            importedAt = java.time.Instant.now().toString(),
            parentSourceFileId = parentId,
            charset = detection.charset.label,
            confidence = detection.confidence,
        )
        writeStatus(statusFile, "IMPORTED", bookId)
        upsertIndex(entry) ?: return Outcome.Failed(ImportError.INDEX_ERROR, "index write failed")

        return if (parentId != null) Outcome.SourceRevision(bookId, parentId, entry)
        else if (fileName != null && loadIndex().any { it.fileName == fileName && it.bookId != bookId && it.state == "IMPORTED" })
            Outcome.DistinctSource(bookId, entry)
        else Outcome.Imported(bookId, entry, lines)
    }

    /** 恢复：清理未完成 import 的残留（tmp/status），不伪装成功。 */
    fun recover(): List<String> {
        val cleaned = mutableListOf<String>()
        Files.newDirectoryStream(incomingDir).use { dir ->
            for (p in dir) {
                val name = p.fileName.toString()
                if (name.endsWith(".tmp")) {
                    val bookId = name.removeSuffix(".tmp")
                    val status = booksDir.resolve("$bookId.status.json")
                    val isFinalized = status.exists() && status.readText().contains("\"IMPORTED\"")
                    if (!isFinalized) {
                        p.toFile().delete()
                        cleaned += "removed leftover tmp: $name"
                    }
                }
            }
        }
        // 状态文件为 FAILED_IMPORT 的残留标记保留（诊断用）；完成状态缺失的 tmp 已在上面清理
        return cleaned
    }

    /** 当前索引（供查询）。 */
    fun listBooks(): List<BookEntry> = loadIndex()

    private fun sha256(bytes: ByteArray): String? = try {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun writeStatus(file: Path, state: String, bookId: String, errorCode: String? = null, errorMsg: String? = null) {
        val obj = JSONObject()
        obj.put("book_id", bookId)
        obj.put("state", state)
        obj.put("updated_at", java.time.Instant.now().toString())
        if (errorCode != null) obj.put("error_code", errorCode)
        if (errorMsg != null) obj.put("error_message", errorMsg)
        file.writeText(obj.toString(2))
    }

    private fun loadIndex(): List<BookEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookEntry(
                    bookId = o.getString("book_id"),
                    sha256 = o.getString("sha256"),
                    byteSize = o.getLong("byte_size"),
                    fileName = o.getString("file_name"),
                    localPath = o.getString("local_path"),
                    state = o.getString("state"),
                    importedAt = o.optString("imported_at"),
                    parentSourceFileId = o.optStringOrNull("parent_source_file_id"),
                    charset = o.optStringOrNull("charset"),
                    confidence = if (o.has("confidence") && !o.isNull("confidence")) o.getDouble("confidence") else null,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun upsertIndex(entry: BookEntry): Boolean = try {
        val arr = JSONArray()
        for (b in loadIndex()) {
            if (b.bookId != entry.bookId) {
                val o = JSONObject()
                o.put("book_id", b.bookId)
                o.put("sha256", b.sha256)
                o.put("byte_size", b.byteSize)
                o.put("file_name", b.fileName)
                o.put("local_path", b.localPath)
                o.put("state", b.state)
                o.put("imported_at", b.importedAt)
                b.parentSourceFileId?.let { o.put("parent_source_file_id", it) }
                b.charset?.let { o.put("charset", it) }
                b.confidence?.let { o.put("confidence", it) }
                arr.put(o)
            }
        }
        val o = JSONObject()
        o.put("book_id", entry.bookId)
        o.put("sha256", entry.sha256)
        o.put("byte_size", entry.byteSize)
        o.put("file_name", entry.fileName)
        o.put("local_path", entry.localPath)
        o.put("state", entry.state)
        o.put("imported_at", entry.importedAt)
        entry.parentSourceFileId?.let { o.put("parent_source_file_id", it) }
        entry.charset?.let { o.put("charset", it) }
        entry.confidence?.let { o.put("confidence", it) }
        arr.put(o)
        val tmp = indexFile.resolveSibling("index.json.tmp")
        tmp.writeText(arr.toString(2))
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        false
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null
}
