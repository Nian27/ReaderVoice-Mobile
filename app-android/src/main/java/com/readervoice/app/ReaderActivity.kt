package com.readervoice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.readervoice.app.ui.ReaderVoiceTheme
import com.readervoice.app.ui.Space
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.ConfirmedChapter
import com.readervoice.parser.chapters.ConfirmedChapterView
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 阅读器（ADR-056 Reader-first）。
 *
 * ## 与旧版"原文查看器"的三点区别
 *
 * 1. **打开就快**：章节列表与**正文行区间**都来自 `book.db`（R1 起 canonical 且带区间），
 *    不再为了显示一章而重编译整本结构（4.3MB 书原先要几十秒）。只有旧包（无区间列）
 *    才回退到重编译路径。
 * 2. **位置不丢**：正文用 `LazyColumn` 渲染，段落序号 = `firstVisibleItemIndex`（精确），
 *    滚动停止 600ms 后写入 `files/books/<id>/reading_state.json`；再次打开直接回到原位。
 *    位置存的是**结构位置（章 id + 段序号 + 段内偏移）**，改字号/重排都不失效（同书签纪律）。
 * 3. **正常阅读入口**：默认打开"上次读到的那一章"；上/下一章、目录、字号都在触手可及处。
 *    Director/剧本是后台缓存，**不在阅读路径上**（不变量 9）。
 */
class ReaderActivity : ComponentActivity() {

    private var chapters by mutableStateOf<List<ChapterIndexEntry>>(emptyList())
    private var bookTitle by mutableStateOf("")
    private var current by mutableStateOf(-1)          // 当前章在 chapters 里的下标
    private var paragraphs by mutableStateOf<List<String>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var startParagraph by mutableStateOf(0)
    private var restoring by mutableStateOf(true)
    private var bookId: String = ""
    private var packageDir: File? = null
    private var lines: List<String> = emptyList()
    private var serverChapters: List<com.readervoice.parser.chapters.ConfirmedChapter> = emptyList()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bookId = intent?.getStringExtra(MainActivity.EXTRA_BOOK_ID).orEmpty()
        val wantChapterId = intent?.getLongExtra(EXTRA_CHAPTER_ID, -1L) ?: -1L
        val wantOrdinal = intent?.getIntExtra(EXTRA_CHAPTER_ORDINAL, -1) ?: -1

        val repo = BookRepository(this)
        packageDir = repo.packageFor(bookId)
        val dir = packageDir
        if (dir == null) {
            error = "Book Package 不存在"
        } else {
            bookTitle = runCatching {
                BookManifestCodec.decode(File(dir, "manifest.json").readText(Charsets.UTF_8)).title
            }.getOrDefault(bookId)
            loadChapters(dir, repo)
            // 打开位置：显式指定 > 上次阅读位置 > 第 1 章
            val saved = ReadingStateStore.load(dir)
            Thread {
                val idx = when {
                    wantChapterId > 0 -> chapters.indexOfFirst { it.anchorByte == wantChapterId }
                    wantOrdinal >= 0 -> wantOrdinal
                    saved != null -> chapters.indexOfFirst { it.anchorByte == saved.chapterId }
                    else -> 0
                }
                runOnUiThread {
                    startParagraph = saved?.paragraphIndex ?: 0
                    open(if (idx >= 0) idx else 0)
                    restoring = false
                }
            }.start()
        }

        setContent {
            ReaderVoiceTheme {
                var fontSize by mutableStateOf(ReaderPrefs.load(this@ReaderActivity).fontSizeSp)
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = startParagraph.coerceAtLeast(0),
                )
                val ch = chapters.getOrNull(current)

                // 位置持久化：滚动停止即写盘（debounce 避免每帧写文件）
                LaunchedEffect(current, ch?.anchorByte) {
                    val d = packageDir ?: return@LaunchedEffect
                    val id = ch?.anchorByte ?: return@LaunchedEffect
                    snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                        .collectLatest { (idx, off) ->
                            delay(600)          // 滚动停止 600ms 才写盘（不是每帧写文件）
                            ReadingStateStore.record(
                                packageDir = d,
                                chapterId = id,
                                chapterOrdinal = ch.ordinal,
                                paragraphIndex = idx,
                                segmentOrdinal = idx,      // 段落序号即剧本片段坐标（章内）
                                charOffset = off,
                            )
                        }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(ch?.title ?: bookTitle.ifEmpty { "阅读" }, maxLines = 1) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            actions = {
                                TextButton(onClick = {
                                    startActivity(
                                        android.content.Intent(this@ReaderActivity, CharacterManagerActivity::class.java)
                                            .putExtra(MainActivity.EXTRA_BOOK_ID, bookId),
                                    )
                                }) { Text("角色") }
                                TextButton(onClick = { finish() }) { Text("书架") }
                            },
                        )
                    },
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { inner ->
                    Column(Modifier.padding(inner).fillMaxSize()) {
                        // 状态行：章节位置 + 字号 + 上下章
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Space.s4, vertical = Space.s1),
                            horizontalArrangement = Arrangement.spacedBy(Space.s2),
                        ) {
                            Text(
                                if (ch == null) (error ?: "加载中…") else "${ch.ordinal + 1}/${chapters.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                fontSize = (fontSize - 2).coerceAtLeast(12)
                                ReaderPrefs.save(this@ReaderActivity, fontSize)
                            }) { Text("A-") }
                            TextButton(onClick = {
                                fontSize = (fontSize + 2).coerceAtMost(34)
                                ReaderPrefs.save(this@ReaderActivity, fontSize)
                            }) { Text("A+") }
                        }

                        if (restoring) {
                            Text("载入中…", modifier = Modifier.padding(Space.s4))
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).padding(horizontal = Space.s4),
                                verticalArrangement = Arrangement.spacedBy(Space.s2),
                            ) {
                                items(paragraphs) { p ->
                                    Text(
                                        p,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = fontSize.sp,
                                            lineHeight = (fontSize * 1.7f).sp,
                                            // 中文正文首行缩进两字（阅读器常规排版）
                                            textIndent = TextIndent(firstLine = (fontSize * 2).sp),
                                        ),
                                    )
                                }
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = Space.s4),
                                        horizontalArrangement = Arrangement.spacedBy(Space.s2),
                                    ) {
                                        OutlinedButton(
                                            onClick = { open(current - 1) },
                                            enabled = current > 0,
                                            modifier = Modifier.weight(1f),
                                        ) { Text("上一章") }
                                        OutlinedButton(
                                            onClick = { open(current + 1) },
                                            enabled = current in 0 until chapters.size - 1,
                                            modifier = Modifier.weight(1f),
                                        ) { Text("下一章") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 章节目录：优先读 `book.db`（canonical + 正文区间 ⇒ 打开快）；
     * 旧包没有区间列时回退到"重编译结构"（一次性，之后会被 R1 的写入路径补上区间）。
     */
    private fun loadChapters(dir: File, repo: BookRepository) {
        val indexed = BookDatabase.chapters(dir)
        if (indexed.isNotEmpty() && indexed.all { it.hasRange }) {
            chapters = indexed
            // 正文按需读：只需要知道行数，用一次轻量扫描
            lines = runCatching {
                val bytes = File(dir, "source.txt").readBytes()
                val enc = EncodingDetector.detect(bytes)
                PhysicalLineScanner().scan(bytes, enc.charset, enc.bomBytes, bookId).lines.map { it.rawText }
            }.getOrDefault(emptyList())
            return
        }
        // 回退路径：重编译（并把 canonical 结果写回索引，下次就走快路径）
        Thread {
            runCatching {
                val bytes = File(dir, "source.txt").readBytes()
                val enc = EncodingDetector.detect(bytes)
                val scanned = PhysicalLineScanner().scan(bytes, enc.charset, enc.bomBytes, bookId)
                lines = scanned.lines.map { it.rawText }
                val rules = ChapterRuleSelection.apply(repo.chapterRules(), ChapterRuleSelection.load(dir))
                val revision = ChapterStructureCompiler(rules).compile(scanned.lines, bookId).revision
                val canonical = ConfirmedChapterView.of(revision, scanned.lines.size)
                serverChapters = canonical
                runCatching { BookDatabase.replaceWithConfirmedChapters(dir, canonical) }
                chapters = canonical.map {
                    ChapterIndexEntry(
                        ordinal = it.ordinal,
                        title = it.titleRaw.trim().ifEmpty { it.title },
                        anchorByte = it.chapterId,
                        state = it.state.name,
                        contentStartLine = it.contentStartLine,
                        contentEndLine = it.contentEndLine,
                    )
                }
            }.onFailure { error = "读取失败：${it.message ?: it::class.java.simpleName}" }
            runOnUiThread {
                startParagraph = ReadingStateStore.load(dir)?.paragraphIndex ?: 0
                open(0)
                restoring = false
            }
        }.start()
    }

    /** 打开第 [index] 章（越界时忽略）。 */
    private fun open(index: Int) {
        if (chapters.isEmpty()) return
        val i = index.coerceIn(0, chapters.size - 1)
        current = i
        val ch = chapters[i]
        paragraphs = if (ch.hasRange) {
            lines.subList(
                (ch.contentStartLine - 1).coerceIn(0, lines.size),
                ch.contentEndLine.coerceIn(0, lines.size),
            ).filter { it.isNotBlank() }
        } else {
            serverChapters.getOrNull(i)?.let { c ->
                lines.subList(
                    (c.contentStartLine - 1).coerceIn(0, lines.size),
                    c.contentEndLine.coerceIn(0, lines.size),
                ).filter { it.isNotBlank() }
            } ?: emptyList()
        }
        // 章首即记录位置（避免"翻到新章但没滚动"时不落盘）
        packageDir?.let {
            ReadingStateStore.record(it, ch.anchorByte, ch.ordinal, 0, 0, 0)
        }
    }

    companion object {
        const val EXTRA_CHAPTER_ORDINAL = "chapter_ordinal"
        const val EXTRA_CHAPTER_ID = "chapter_id"
    }
}

/** 阅读器偏好（字号等）：与书无关，存包级目录。 */
data class ReaderPrefs(val fontSizeSp: Int) {
    companion object {
        private fun file(dir: File) = File(dir, "reader_prefs.json")
        fun load(ctx: android.content.Context): ReaderPrefs = runCatching {
            val f = file(ctx.filesDir)
            if (!f.isFile) return@runCatching ReaderPrefs(18)
            ReaderPrefs(org.json.JSONObject(f.readText()).optInt("fontSizeSp", 18))
        }.getOrDefault(ReaderPrefs(18))

        fun save(ctx: android.content.Context, fontSizeSp: Int) {
            runCatching {
                file(ctx.filesDir).writeText(org.json.JSONObject().put("fontSizeSp", fontSizeSp).toString())
            }
        }
    }
}
