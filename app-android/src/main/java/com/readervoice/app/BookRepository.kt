package com.readervoice.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.ConfirmedChapterView
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File

class BookRepository(private val context: Context) {
    private val booksRoot = File(context.filesDir, "books")

    fun importDocument(uri: Uri): BookPackageImporter.Outcome {
        val sourceName = displayName(context.contentResolver, uri)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open selected document" }
            return BookPackageImporter(booksRoot, initializeIndex = ::compileStructure)
                .import(sourceName, input)
        }
    }

    fun books(): List<Pair<BookManifest, File>> {
        if (!booksRoot.isDirectory) return emptyList()
        return booksRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { directory ->
                runCatching {
                    BookManifestCodec.decode(File(directory, "manifest.json").readText(Charsets.UTF_8)) to directory
                }.getOrNull()
            }
            .sortedByDescending { it.first.importedAtEpochMs }
            .toList()
    }

    fun packageFor(bookId: String): File? = File(booksRoot, bookId).takeIf { it.isDirectory }

    /**
     * Upgrade only generated structures: the former placeholder, v1 titleDisplay indexes,
     * and v2 indexes compiled with an accidental punctuation-cleaning semantic change.
     * USER_EDITED structures are deliberately outside this migration.
     */
    fun upgradeGeneratedPackages(): Int {
        var upgraded = 0
        books().forEach { (manifest, directory) ->
            val needsUpgrade = manifest.structureState == "PENDING_STRUCTURE" ||
                (manifest.structureState == "STRUCTURE_READY" &&
                    manifest.structureOrigin == STRUCTURE_ORIGIN_AUTO_LEGACY_RULEPACK &&
                    manifest.structureFormatVersion < 3)
            if (!needsUpgrade) return@forEach
            val result = compileStructure(directory, manifest.bookId)
            writeManifest(directory, manifest.copy(
                structureState = result.structureState,
                chapterCount = result.chapterCount,
                structureFormatVersion = result.structureFormatVersion,
            ))
            upgraded++
        }
        return upgraded
    }

    private fun compileStructure(packageDir: File, bookId: String): BookIndexResult {
        val bytes = File(packageDir, "source.txt").readBytes()
        val encoding = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
        val rules = ChapterRuleSelection.apply(chapterRules(), ChapterRuleSelection.load(packageDir))
        val revision = ChapterStructureCompiler(rules).compile(lines, bookId).revision
        // ★ R1：只把 canonical chapters 交给导航索引（不再把候选集合递给数据库去过滤）
        return BookDatabase.replaceWithConfirmedChapters(
            packageDir,
            ConfirmedChapterView.of(revision, lines.size),
        )
    }

    /** 规则包本体（assets 资产，只读）。 */
    fun chapterRules(): List<com.readervoice.parser.chapters.ChapterRule> =
        context.assets.open("legacy/txtTocRule.json").use(LegacyChapterRuleAdapter::fromJson)

    /**
     * 按用户选中的规则**重新分章**：持久化选择 → 重编译 → 原子替换章节目录 → 同步 manifest。
     * 原文与规则包都不被修改（C1）。
     */
    fun recompileChapters(packageDir: File, bookId: String, enabledRuleIds: Collection<String>): BookIndexResult {
        ChapterRuleSelection.save(packageDir, enabledRuleIds)
        val bytes = File(packageDir, "source.txt").readBytes()
        val encoding = EncodingDetector.detect(bytes)
        val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
        val rules = ChapterRuleSelection.apply(chapterRules(), enabledRuleIds.toSet())
        val revision = ChapterStructureCompiler(rules).compile(lines, bookId).revision
        val result = BookDatabase.replaceWithConfirmedChapters(
            packageDir,
            ConfirmedChapterView.of(revision, lines.size),
        )
        runCatching {
            val manifestFile = File(packageDir, "manifest.json")
            val manifest = BookManifestCodec.decode(manifestFile.readText(Charsets.UTF_8))
            writeManifest(
                packageDir,
                manifest.copy(
                    structureState = result.structureState,
                    chapterCount = result.chapterCount,
                    structureFormatVersion = result.structureFormatVersion,
                ),
            )
        }
        return result
    }

    private fun writeManifest(directory: File, manifest: BookManifest) {
        val target = File(directory, "manifest.json")
        val staging = File(directory, "manifest.json.tmp")
        staging.writeText(BookManifestCodec.encode(manifest), Charsets.UTF_8)
        check(staging.renameTo(target)) { "cannot atomically update Book Package manifest" }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return "source.txt"
    }
}
