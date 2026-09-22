package com.readervoice.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.readervoice.app.ui.MonoStyle
import com.readervoice.app.ui.ReaderVoiceTheme
import com.readervoice.app.ui.Space
import com.readervoice.data.script.ScriptRepository
import com.readervoice.data.script.ScriptState
import java.io.File
import org.json.JSONObject

/**
 * 剧本 + 角色档案（2026-09-18 修订）。
 *
 * 用户反馈两点，都在这里解决：
 *   ① **「剧本还是没有分章」** ⇒ 顶部是**章列表**（来自 `ScriptRepository.list(bookId)`，
 *      每章一个独立剧本），点一章看那一章的剧本 —— 不再是混在一起的"最新一份"。
 *   ② **「角色档案呢」** ⇒ 增加【角色档案】视图：从剧本行直接派生
 *      （`speakerRef=CHARACTER:<id>` ⇒ 人名、台词数、情绪集合、首次出现），**不需要模型**。
 *
 * 数据来源全部是持久化产物（`books/<bookId>/scripts/<chapterId>/current.jsonl`），
 * 因此"退出再进来"仍在。
 */
class ScriptViewActivity : ComponentActivity() {

    private var states by mutableStateOf<List<ScriptState>>(emptyList())
    private var selected by mutableStateOf<Long?>(null)
    private var rows by mutableStateOf<List<ScriptRow>>(emptyList())
    private var showCharacters by mutableStateOf(false)
    private var header by mutableStateOf("")

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bookId = intent?.getStringExtra(MainActivity.EXTRA_BOOK_ID).orEmpty()
        val repo = ScriptRepository(File(filesDir, "books"))
        states = if (bookId.isNotEmpty()) repo.list(bookId) else emptyList()
        val pick = intent?.getLongExtra(EXTRA_CHAPTER_ID, -1L)?.takeIf { it > 0 } ?: states.maxByOrNull { it.updatedAt }?.chapterId
        selected = pick
        if (pick != null) rows = loadRows(repo, bookId, pick)
        header = buildHeader(bookId)

        setContent {
            ReaderVoiceTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (showCharacters) "角色档案" else "剧本") },
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
                        Text(header, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // ① 章列表（每章一个独立剧本）
                        if (states.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                            ) {
                                states.forEachIndexed { i, st ->
                                    FilterChip(
                                        selected = st.chapterId == selected,
                                        onClick = {
                                            selected = st.chapterId
                                            rows = loadRows(repo, bookId, st.chapterId)
                                            header = buildHeader(bookId)
                                        },
                                        label = { Text("第 ${i + 1} 章 · ${st.lineCount} 行") },
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            FilterChip(selected = !showCharacters, onClick = { showCharacters = false }, label = { Text("剧本") })
                            FilterChip(selected = showCharacters, onClick = { showCharacters = true }, label = { Text("角色档案") })
                        }

                        if (showCharacters) {
                            val prof = CharacterProfile.from(rows)
                            if (prof.isEmpty()) {
                                Text("本章没有识别到角色（全是旁白）。换一章，或先跑【导演本章】。", style = MaterialTheme.typography.bodyMedium)
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                                items(prof) { p -> CharacterCard(p) }
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                                items(rows) { row -> ScriptRowView(row) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadRows(repo: ScriptRepository, bookId: String, chapterId: Long): List<ScriptRow> =
        repo.loadLines(bookId, chapterId).mapNotNull { runCatching { ScriptRow.parse(it) }.getOrNull() }

    private fun buildHeader(bookId: String): String {
        if (states.isEmpty()) return "还没有持久化剧本。回到章节页点【导演本章】先生成。"
        val st = states.firstOrNull { it.chapterId == selected }
        val idx = states.indexOfFirst { it.chapterId == selected }
        return buildString {
            append("本书共 ${states.size} 章有剧本（每章独立持久化）")
            if (st != null) {
                append("\n当前：第 ${idx + 1} 章　chapterId=${st.chapterId}　${rows.size} 行")
                append("　status=${st.status}　已提交 S${st.nextSegmentIndex}/${st.totalSegments}")
                append("\naccepted=${st.accepted} verify=${st.verify} rejected=${st.rejected}")
                if (st.status == "RUNNING") append("　★ 未跑完，回章节页可继续")
            }
        }
    }

    companion object {
        const val EXTRA_CHAPTER_ID = "chapter_id"
    }
}

/** 一行剧本（JSONL v2）。 */
private data class ScriptRow(
    val segmentId: Long,
    val speakerRef: String,
    val text: String,
    val type: String,
    val emotion: String,
    val intensity: Double,
    val pace: String,
    val outcome: String,
    val route: String,
) {
    val isNarrator: Boolean get() = speakerRef == "NARRATOR"
    val isUnknown: Boolean get() = speakerRef == "UNKNOWN"
    val characterId: String? get() = speakerRef.removePrefix("CHARACTER:").takeIf { speakerRef.startsWith("CHARACTER:") }

    /** `CHARACTER:boot-林夜` → `林夜`（自举阶段身份 id 即人名）。 */
    val displayName: String
        get() = when {
            isNarrator -> "旁白"
            isUnknown -> "未知说话人"
            else -> speakerRef.removePrefix("CHARACTER:").removePrefix("boot-")
        }

    val performance: String
        get() {
            if (isNarrator) return ""
            val parts = mutableListOf<String>()
            if (emotion != "NEUTRAL" || intensity != 0.5) parts += "$emotion·${"%.1f".format(intensity)}"
            if (pace != "NORMAL") parts += pace
            return parts.joinToString(", ")
        }

    companion object {
        fun parse(json: String): ScriptRow {
            val o = JSONObject(json)
            val d = o.optJSONObject("delivery")
            return ScriptRow(
                segmentId = o.optLong("segment_id"),
                speakerRef = o.optString("speakerRef"),
                text = o.optString("text"),
                type = o.optString("type"),
                emotion = o.optString("emotion", "NEUTRAL"),
                intensity = o.optDouble("emotion_intensity", 0.5),
                pace = d?.optString("pace", "NORMAL") ?: "NORMAL",
                outcome = o.optString("outcome"),
                route = o.optString("route"),
            )
        }
    }
}

/** 角色档案（从剧本行派生，不需要模型）。 */
private data class CharacterProfile(
    val id: String,
    val name: String,
    val lines: Int,
    val emotions: List<String>,
    val firstSegmentId: Long,
    val sample: String,
) {
    companion object {
        fun from(rows: List<ScriptRow>): List<CharacterProfile> =
            rows.filter { it.characterId != null }
                .groupBy { it.characterId!! }
                .map { (id, group) ->
                    CharacterProfile(
                        id = id,
                        name = group.first().displayName,
                        lines = group.size,
                        emotions = group.map { it.emotion }.distinct(),
                        firstSegmentId = group.minOf { it.segmentId },
                        sample = group.first().text.take(30),
                    )
                }
                .sortedByDescending { it.lines }
    }
}

@Composable
private fun CharacterCard(p: CharacterProfile) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                Text("${p.lines} 句", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Text("情绪：${p.emotions.joinToString(" / ")}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text("首次出现 segment=${p.firstSegmentId}", style = MonoStyle, color = scheme.onSurfaceVariant)
            Text("例：${p.sample}…", style = MaterialTheme.typography.bodySmall)
            Text("稳定 id：${p.id}", style = MonoStyle, color = scheme.onSurfaceVariant)
        }
    }
}



@Composable
private fun ScriptRowView(row: ScriptRow) {
    val scheme = MaterialTheme.colorScheme
    val accent = when {
        row.isNarrator -> scheme.onSurfaceVariant
        row.isUnknown -> Color(0xFFB5852F)
        else -> scheme.primary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    text = row.displayName + if (row.performance.isNotEmpty()) "[${row.performance}]" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                if (row.outcome != "ACCEPTED_ALL" && !row.isNarrator) {
                    Text(row.outcome, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB5852F))
                }
            }
            Text(
                text = if (row.isNarrator) row.text else "“${row.text.trim('“', '”')}”",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

