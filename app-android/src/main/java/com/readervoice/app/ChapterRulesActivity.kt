package com.readervoice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.readervoice.app.ui.MonoStyle
import com.readervoice.app.ui.ReaderVoiceTheme
import com.readervoice.app.ui.Space
import com.readervoice.parser.chapters.ChapterRule
import java.io.File

/**
 * 章节规则：**按规则重新分章**。
 *
 * 产品行为：
 *   · 列出规则包里的全部规则（族 / 目标 / 正则片段），可逐条勾选
 *   · 勾选后点「按所选规则重新分章」⇒ 重跑章节结构编译并原子替换章节目录
 *   · 选择持久化在书包内（`chapter_rules.json`），随书走；**原始终不可变**（C1）
 *
 * 为什么需要它：网文目录规则差异极大（`第一章` / `1.` / `#12` / `卷一` …），
 * 自动规则不可能一次命中所有书；给用户一个"按规则重分章"的显式入口，
 * 比让用户去改文件、或者忍受错误目录更符合产品直觉。
 */
class ChapterRulesActivity : ComponentActivity() {

    private var rules by mutableStateOf<List<ChapterRule>>(emptyList())
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var result by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private lateinit var packageDir: File
    private lateinit var bookId: String

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bookId = intent?.getStringExtra(MainActivity.EXTRA_BOOK_ID).orEmpty()
        packageDir = BookRepository(this).packageFor(bookId) ?: File("/nonexistent")
        val repository = BookRepository(this)
        rules = runCatching { repository.chapterRules() }.getOrDefault(emptyList())
        selected = ChapterRuleSelection.load(packageDir) ?: rules.filter { it.enabled }.map { it.ruleId }.toSet()
        result = "当前目录：${BookDatabase.chapters(packageDir).size} 章。勾选规则后重新分章。"

        setContent {
            ReaderVoiceTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("章节规则") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { inner ->
                    Column(
                        modifier = Modifier
                            .padding(inner)
                            .fillMaxSize()
                            .padding(horizontal = Space.s4, vertical = Space.s3),
                        verticalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        Text(
                            "已选 ${selected.size} / ${rules.size} 条规则。原始终不可变，只重算目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                result = "重新分章中…"
                                Thread {
                                    val r = runCatching {
                                        repository.recompileChapters(packageDir, bookId, selected)
                                    }
                                    runOnUiThread {
                                        result = r.fold(
                                            onSuccess = { "已按所选规则重新分章：识别到 ${it.chapterCount} 章。" },
                                            onFailure = { "重新分章失败：${it.message ?: it::class.java.simpleName}" },
                                        )
                                        busy = false
                                    }
                                }.start()
                            },
                        ) { Text(if (busy) "处理中…" else "按所选规则重新分章") }
                        Text(result.orEmpty(), style = MaterialTheme.typography.bodyMedium)

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                            items(rules, key = { it.ruleId }) { rule ->
                                RuleRow(rule, rule.ruleId in selected) { checked ->
                                    selected = if (checked) selected + rule.ruleId else selected - rule.ruleId
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: ChapterRule, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Checkbox(checked = checked, onCheckedChange = onToggle)
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                Text(
                    "${rule.name}　[${rule.family} → ${rule.target}]",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    rule.regex,
                    style = MonoStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
