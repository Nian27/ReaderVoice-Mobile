package com.readervoice.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import com.readervoice.app.ui.MonoStyle
import java.io.File
import com.readervoice.app.ui.ReaderVoiceTheme
import com.readervoice.app.ui.Space

/**
 * 整章导演运行台（debug-only）——**UI 壳**。
 *
 * 2026-09-18 重做：
 *   · UI 迁 Jetpack Compose + Material3，状态栏/手势条用 safeDrawing insets（永不被遮挡）
 *   · ★ **运行本身交给前台服务** `ChapterDirectorService`：用户切走 App 也不会被
 *     cached-app freezer 冻结（实测两次冻结事故的根因）
 *
 * 本 Activity 只做三件事：触发服务、展示状态总线、发送协作式取消。
 *
 * 用法（与之前完全一致）：
 *   adb shell am start -n com.readervoice.app/.ChapterDirectorActivity \
 *     --es book book-4455b46ef2d2 --ei from 60 --ei to 200 --ei maxcalls 60 \
 *     --es backend hexagon --ez autorun true
 *
 * 产物（口径不变）：`files/chapter_director/script_lines.jsonl` + `stats.txt`
 */
class ChapterDirectorActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bookId = intent?.getStringExtra("book") ?: "book-4455b46ef2d2"
        val from = intent?.getIntExtra("from", 60) ?: 60
        val to = intent?.getIntExtra("to", 160) ?: 160
        val maxCalls = intent?.getIntExtra("maxcalls", 30) ?: 30
        val backend = intent?.getStringExtra("backend") ?: "cpu"
        val autorun = intent?.getBooleanExtra("autorun", false) ?: false
        val chapterOrdinal = intent?.getIntExtra("chapter", -1) ?: -1

        ensureNotificationPermission()

        setContent {
            ReaderVoiceTheme {
                val log by ChapterRunBus.log.collectAsState()
                val summary by ChapterRunBus.summary.collectAsState()
                val running by ChapterRunBus.running.collectAsState()
                val done by ChapterRunBus.done.collectAsState()
                val total by ChapterRunBus.total.collectAsState()
                val lineCount by ChapterRunBus.lines.collectAsState()

                // ★ 完成回跳：运行结束后短暂停留（让人看到统计），再回到上一屏
                LaunchedEffect(running, summary) {
                    if (!running && summary != null) {
                        delay(2_500)
                        finish()
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("整章导演运行台") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    // ★ 内容窗口 insets = safeDrawing ⇒ 永不被状态栏/挖孔/手势条遮挡
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { inner ->
                    ConsoleScreen(
                        modifier = Modifier.padding(inner),
                        book = bookId, from = from, to = to, maxCalls = maxCalls,
                        running = running,
                        log = log,
                        summary = summary,
                        done = done,
                        total = total,
                        lineCount = lineCount,
                        onCancel = { startService(ChapterDirectorService.cancelIntent(this)) },
                        onOpenScript = { startActivity(Intent(this, ScriptViewActivity::class.java).putExtra(MainActivity.EXTRA_BOOK_ID, bookId)) },
                    )
                }
            }
        }

        // 不要覆盖正在运行的服务的日志（从别处进入本界面时）
        if (!ChapterRunBus.running.value && ChapterRunBus.log.value.isBlank()) {
            ChapterRunBus.log.value = "待运行：book=$bookId range=$from..$to maxCalls=$maxCalls"
        }
        // ★ 进程重启后不要把已完成的行数显示成 0：从"最新一轮"镜像恢复
        if (!ChapterRunBus.running.value && ChapterRunBus.lines.value == 0) {
            ChapterRunBus.lines.value = runCatching {
                File(filesDir, "chapter_director/script_lines.jsonl").readLines().count { it.isNotBlank() }
            }.getOrDefault(0)
        }
        if (autorun) {
            // ★ 前台服务承载整章运行：Activity 之后可以随时进后台/被切走，不会被冻结
            ContextCompat.startForegroundService(
                this,
                ChapterDirectorService.startIntent(this, bookId, from, to, maxCalls, backend, chapterOrdinal),
            )
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能跑，只是通知不可见 */ }
            .launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

// ── Compose UI ────────────────────────────────────────────────

@Composable
private fun ConsoleScreen(
    modifier: Modifier,
    book: String, from: Int, to: Int, maxCalls: Int,
    running: Boolean,
    log: String,
    summary: String?,
    done: Int,
    total: Int,
    lineCount: Int,
    onCancel: () -> Unit,
    onOpenScript: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.s4, vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                Text("$book   段落 $from..$to   模型上限 $maxCalls", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (running) "运行中（前台服务承载 · 可切走 App）" else "已停止 / 待运行",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                // ★ 进度条：分母是范围内的片段数，分子是已出结局的片段数
                if (running || done > 0) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        if (total > 0) "已处理 $done / $total 段   已产出 $lineCount 行剧本"
                        else "已处理 $done 段   已产出 $lineCount 行剧本",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                summary?.let { Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Button(onClick = onCancel, enabled = running) { Text("取消（epoch++）") }
            OutlinedButton(onClick = onOpenScript, enabled = lineCount > 0 || !running) { Text("查看剧本") }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(Space.s3),
            ) {
                Text(
                    text = log.ifEmpty { "（暂无输出）" },
                    style = MonoStyle,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}



