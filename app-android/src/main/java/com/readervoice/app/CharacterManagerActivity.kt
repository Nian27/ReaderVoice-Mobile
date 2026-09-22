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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.readervoice.app.ui.MonoStyle
import com.readervoice.app.ui.ReaderVoiceTheme
import com.readervoice.app.ui.Space
import com.readervoice.data.character.CharacterProfile
import com.readervoice.data.character.CharacterProfileExtractor
import com.readervoice.data.character.ProfileConfidence
import com.readervoice.data.character.ProfileField
import com.readervoice.data.script.ScriptRepository
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import org.json.JSONObject

/**
 * 角色档案（app 设计）：**用户可见的数据模型**，不是一个后台抽象。
 *
 * 用户原话："人物档案，连年龄都没有，更别说其他的了"。
 *
 * ## 每个字段都带来源
 *
 * ```
 * 年龄  18  [高置信]  ← "李寄舟今年十八岁…"（第 1 行）[看原文]
 * 性别  男  [低置信]  ← 共 3 条证据
 * 身份  魔教教主      ← "李寄舟是魔教教主…"
 * 音色  未接入（TTS 冻结中）
 * ```
 * 抽不到的字段显示 **未知（可编辑）**，绝不编造（不变量 5）。
 * 用户改过的字段标 **已锁定**，后续规则/模型不得覆盖（不变量 8）。
 *
 * 数据来源：
 * - 角色名：`files/chapter_director/characters_<bookId>.json`（角色发现的过闸门结果）
 * - 台词数：`books/<bookId>/scripts/<chapterId>/current.jsonl`（持久化剧本）
 * - 年龄/性别/身份：`CharacterProfileExtractor` 对**原文**做有界规则抽取（带行号证据）
 */
class CharacterManagerActivity : ComponentActivity() {

    private var profiles by mutableStateOf<List<CharacterProfile>>(emptyList())
    private var loading by mutableStateOf(true)
    private var note by mutableStateOf("")
    private var packageDir: File? = null
    private var editing by mutableStateOf<CharacterProfile?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bookId = intent?.getStringExtra(MainActivity.EXTRA_BOOK_ID).orEmpty()
        val dir = BookRepository(this).packageFor(bookId)
        packageDir = dir
        if (dir == null) {
            loading = false
            note = "Book Package 不存在"
        } else {
            Thread { buildProfiles(bookId, dir) }.start()
        }

        setContent {
            ReaderVoiceTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("角色档案") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    contentWindowInsets = WindowInsets.safeDrawing,
                ) { inner ->
                    Column(
                        modifier = Modifier.padding(inner).fillMaxSize().padding(horizontal = Space.s4),
                        verticalArrangement = Arrangement.spacedBy(Space.s2),
                    ) {
                        Text(
                            if (loading) "正在从原文抽取角色档案…" else note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                            items(profiles, key = { it.name }) { p ->
                                ProfileCard(p, onEdit = { editing = p })
                            }
                        }
                    }
                }
                editing?.let { p ->
                    EditDialog(
                        profile = p,
                        onDismiss = { editing = null },
                        onSave = { age, gender, role ->
                            packageDir?.let { d ->
                                CharacterProfileStore.save(
                                    d, p.name,
                                    CharacterProfileStore.Override(
                                        age = age.ifBlank { null },
                                        gender = gender.ifBlank { null },
                                        role = role.ifBlank { null },
                                        locked = true,
                                    ),
                                )
                                profiles = CharacterProfileStore.apply(profiles, CharacterProfileStore.loadAll(d))
                            }
                            editing = null
                        },
                    )
                }
            }
        }
    }

    private fun buildProfiles(bookId: String, dir: File) {
        runCatching {
            val names = loadNames(bookId)
            val lines = runCatching {
                val bytes = File(dir, "source.txt").readBytes()
                val enc = EncodingDetector.detect(bytes)
                PhysicalLineScanner().scan(bytes, enc.charset, enc.bomBytes, bookId).lines.map { it.rawText }
            }.getOrDefault(emptyList())
            val spoken = spokenLineCounts(bookId)
            val extracted = CharacterProfileExtractor.extract(names, lines, spoken)
            val merged = CharacterProfileStore.apply(extracted, CharacterProfileStore.loadAll(dir))
            runOnUiThread {
                profiles = merged
                note = buildString {
                    append("共 ${merged.size} 个角色（来自角色发现 + 原文规则抽取）")
                    val withAge = merged.count { it.age.known }
                    val withGender = merged.count { it.gender.known }
                    append("\n有年龄 $withAge 个，有性别 $withGender 个；其余显示「未知（可编辑）」。")
                    append("\n点任意角色可手工修正 —— 修正后即锁定，后续重算不会覆盖。")
                }
                loading = false
            }
        }.onFailure {
            runOnUiThread {
                note = "抽取失败：${it.message ?: it::class.java.simpleName}"
                loading = false
            }
        }
    }

    /** 角色名：优先用角色发现的过闸门结果（有则用），否则回退到已持久化剧本里的说话人。 */
    private fun loadNames(bookId: String): List<String> {
        val cache = File(filesDir, "chapter_director/characters_$bookId.json")
        if (cache.isFile) {
            runCatching {
                val arr = JSONObject(cache.readText()).optJSONArray("names")
                if (arr != null && arr.length() > 0) {
                    return (0 until arr.length()).map { arr.getString(it) }
                }
            }
        }
        val fromScripts = LinkedHashSet<String>()
        for (st in ScriptRepository(File(filesDir, "books")).list(bookId)) {
            val f = File(filesDir, "books/$bookId/scripts/${st.chapterId}/current.jsonl")
            if (!f.isFile) continue
            f.readLines().forEach { line ->
                runCatching {
                    val ref = JSONObject(line).optString("speakerRef")
                    if (ref.startsWith("CHARACTER:")) fromScripts += ref.removePrefix("CHARACTER:").removePrefix("boot-")
                }
            }
        }
        return fromScripts.toList()
    }

    /** 台词数：从持久化剧本统计（每个角色说了多少句）。 */
    private fun spokenLineCounts(bookId: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (st in ScriptRepository(File(filesDir, "books")).list(bookId)) {
            val f = File(filesDir, "books/$bookId/scripts/${st.chapterId}/current.jsonl")
            if (!f.isFile) continue
            f.readLines().forEach { line ->
                runCatching {
                    val ref = JSONObject(line).optString("speakerRef")
                    if (ref.startsWith("CHARACTER:")) {
                        val n = ref.removePrefix("CHARACTER:").removePrefix("boot-")
                        out[n] = (out[n] ?: 0) + 1
                    }
                }
            }
        }
        return out
    }
}

@Composable
private fun ProfileCard(p: CharacterProfile, onEdit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
    ) {
        Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                if (p.locked) Text("已锁定", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text("台词 ${p.spokenLines} · 提及 ${p.mentions}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            FieldLine("年龄", p.age)
            FieldLine("性别", p.gender)
            FieldLine("身份", p.role)
            if (p.aliases.isNotEmpty()) {
                Text("别称：${p.aliases.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            }
            if (p.firstLine > 0) {
                Text("首次出现：第 ${p.firstLine} 行", style = MonoStyle, color = scheme.onSurfaceVariant)
            }
            Text("音色：未接入（TTS 冻结中）", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                TextButton(onClick = onEdit) { Text("修正") }
            }
        }
    }
}

/** 一行字段：值 + 置信度 + 首条原文证据（可回溯到行号）。未知时显式写"未知（可编辑）"。 */
@Composable
private fun FieldLine(label: String, f: ProfileField) {
    val scheme = MaterialTheme.colorScheme
    if (!f.known) {
        Text(
            "$label：未知（可编辑）",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        return
    }
    val conf = when (f.confidence) {
        ProfileConfidence.HIGH -> "高置信"
        ProfileConfidence.LOW -> "低置信"
        ProfileConfidence.NONE -> ""
    }
    val ev = f.evidence.firstOrNull()
    Column {
        Text("$label：${f.value}　[$conf]", style = MaterialTheme.typography.bodyMedium)
        if (ev != null) {
            Text("　证据（第 ${ev.lineNo} 行）：${ev.quote}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EditDialog(
    profile: CharacterProfile,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var age by mutableStateOf(profile.age.value.orEmpty())
    var gender by mutableStateOf(profile.gender.value.orEmpty())
    var role by mutableStateOf(profile.role.value.orEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修正 ${profile.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("留空 = 保持未知。保存后该角色标记为已锁定，重算不会覆盖。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("年龄（岁）") }, singleLine = true)
                OutlinedTextField(value = gender, onValueChange = { gender = it }, label = { Text("性别（男/女）") }, singleLine = true)
                OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("身份/称呼") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(age, gender, role) }) { Text("保存并锁定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
