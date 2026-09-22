package com.readervoice.app

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * SAF 输入的不可变落盘：先写 staging，再在同一 books/ 根下 rename 成 Book Package。
 * 绝不改写用户选择的源文件；source.txt 保存输入的原始字节。
 */
class BookPackageImporter(
    private val booksRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val initializeIndex: (File, String) -> BookIndexResult,
) {
    sealed interface Outcome {
        data class Imported(val manifest: BookManifest, val directory: File) : Outcome
        data class ExactDuplicate(val manifest: BookManifest, val directory: File) : Outcome
    }

    fun import(sourceName: String, input: InputStream): Outcome {
        require(booksRoot.exists() || booksRoot.mkdirs()) { "cannot create books root" }
        val staging = File(booksRoot, ".staging-${UUID.randomUUID()}")
        require(staging.mkdir()) { "cannot create import staging" }
        val source = File(staging, "source.txt")
        val digest = MessageDigest.getInstance("SHA-256")
        val size = copyAndDigest(input, source, digest)
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val bookId = "book-${sha256.take(12)}"
        val finalDir = File(booksRoot, bookId)

        if (finalDir.isDirectory) {
            val existing = readManifest(finalDir)
            check(existing.sourceSha256 == sha256) { "book id collision: $bookId" }
            staging.deleteRecursively() // only this call's private, uncommitted staging directory
            return Outcome.ExactDuplicate(existing, finalDir)
        }

        File(staging, "render_units").mkdir()
        File(staging, "audio").mkdir()
        File(staging, "voices").mkdir()
        File(staging, "cache").mkdir()
        val index = initializeIndex(staging, bookId)
        val manifest = BookManifest(
            bookId = bookId,
            title = displayTitle(sourceName),
            sourceFileName = sourceName,
            sourceSha256 = sha256,
            sourceBytes = size,
            importedAtEpochMs = now(),
            structureState = index.structureState,
            chapterCount = index.chapterCount,
        )
        File(staging, "manifest.json").writeText(BookManifestCodec.encode(manifest), Charsets.UTF_8)
        check(staging.renameTo(finalDir)) { "atomic book package commit failed" }
        return Outcome.Imported(manifest, finalDir)
    }

    private fun copyAndDigest(input: InputStream, destination: File, digest: MessageDigest): Long {
        DigestInputStream(input, digest).use { source ->
            FileOutputStream(destination).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.fd.sync()
                return total
            }
        }
    }

    private fun readManifest(directory: File): BookManifest =
        BookManifestCodec.decode(File(directory, "manifest.json").readText(Charsets.UTF_8))

    private fun displayTitle(sourceName: String): String =
        sourceName.substringBeforeLast('.', sourceName).ifBlank { "未命名书籍" }
}
