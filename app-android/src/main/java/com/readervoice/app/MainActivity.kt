package com.readervoice.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.readervoice.app.ui.ReaderVoiceTheme
import com.readervoice.app.ui.Space
import java.io.File

/**
 * 书架（产品入口）。
 *
 * 2026-09-18 UI 重做：
 *   · 迁 Jetpack Compose + Material3，状态栏/手势条用 safeDrawing insets
 *   · ★ **恢复为 LAUNCHER 入口**（此前临时挂在调试页上，导致"打开 App 没有导入书籍"）
 *   · 导入链路口径不变：SAF `ACTION_OPEN_DOCUMENT` → `BookRepository.importDocument(uri)` →
 *     私有 Book Package（原文不可变）+ 目录编译；重复原文走 ExactDuplicate 不重复导入
 */
class MainActivity : ComponentActivity() {

    private lateinit var repository: BookRepository

    private var entries by mutableStateOf<List<Pair<BookManifest, File>>>(emptyList())
    private var feedback by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    private val pickTxt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importFrom(uri)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = BookRepository(this)

        setContent {
            ReaderVoiceTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("ReaderVoice") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { inner ->
                    ShelfScreen(
                        modifier = Modifier.padding(inner),
                        books = entries,
                        status = feedback ?: if (entries.isEmpty()) {
                            "还没有书。点「导入 TXT」选择本地小说（原文会复制进本机 Book Package，不上传）。"
                        } else {
                            "已导入 ${entries.size} 本；目录已在本机生成。"
                        },
                        busy = busy,
                        onImport = { pickTxt.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        onOpen = { manifest ->
                            startActivity(
                                Intent(this, ChapterListActivity::class.java).putExtra(EXTRA_BOOK_ID, manifest.bookId),
                            )
                        },
                        // ★ 继续阅读：直接回到上次位置（位置是书的数据，不是 Activity 状态）
                        onContinue = { manifest ->
                            startActivity(
                                Intent(this, ReaderActivity::class.java).putExtra(EXTRA_BOOK_ID, manifest.bookId),
                            )
                        },
                    )
                }
            }
        }

        // 启动时后台补齐"已生成但未编译目录"的书（口径不变）
        Thread {
            val upgraded = runCatching { repository.upgradeGeneratedPackages() }.getOrDefault(0)
            if (upgraded > 0) runOnUiThread {
                feedback = "已完成 $upgraded 本图书的目录编译。"
                refresh()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = runCatching { repository.books() }.getOrDefault(emptyList())
    }

    private fun importFrom(uri: Uri) {
        // 原文立刻复制进私有 Book Package；provider 不支持 persistable grant 时仍可安全导入
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        busy = true
        feedback = "正在导入…"
        Thread {
            val result = runCatching { repository.importDocument(uri) }
            runOnUiThread {
                feedback = result.fold(
                    onSuccess = { outcome ->
                        when (outcome) {
                            is BookPackageImporter.Outcome.Imported -> "已导入：${outcome.manifest.title}（目录已编译）"
                            is BookPackageImporter.Outcome.ExactDuplicate -> "已存在相同原文：${outcome.manifest.title}"
                        }
                    },
                    onFailure = { "导入失败：${it.message ?: it::class.java.simpleName}" },
                )
                busy = false
                refresh()
            }
        }.start()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
    }
}

@Composable
private fun ShelfScreen(
    modifier: Modifier,
    books: List<Pair<BookManifest, File>>,
    status: String,
    busy: Boolean,
    onImport: () -> Unit,
    onOpen: (BookManifest) -> Unit,
    onContinue: (BookManifest) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.s4, vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Text("本地有声书工作台", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "导入中…" else "导入 TXT")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("书架", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            items(books, key = { it.first.bookId }) { (manifest, packageDir) ->
                val reading = ReadingStateStore.load(packageDir)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(manifest) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                        Text(manifest.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${manifest.chapterCount} 章 · ${structureLabel(manifest.structureState)}" +
                                // ★ 阅读进度是书的持久状态（对齐参考产品 books.durChapterIndex 的产品语义）
                                if (reading != null) " · 读到第 ${reading.chapterOrdinal + 1} 章" else " · 未开始",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            Button(
                                onClick = { onContinue(manifest) },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (reading != null) "继续阅读" else "开始阅读") }
                            OutlinedButton(
                                onClick = { onOpen(manifest) },
                                modifier = Modifier.weight(1f),
                            ) { Text("目录") }
                        }
                    }
                }
            }
        }
    }
}
