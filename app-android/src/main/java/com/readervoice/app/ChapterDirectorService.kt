package com.readervoice.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.readervoice.data.script.ScriptRepository
import com.readervoice.data.script.ScriptRun
import com.readervoice.data.script.ScriptState
import com.readervoice.data.semantic.ChapterDirectorRunner
import com.readervoice.data.semantic.CharacterDiscovery
import com.readervoice.data.semantic.ScriptLineCodec
import com.readervoice.data.semantic.SemanticSegmenter
import com.readervoice.data.semantic.ValidationOutcome
import com.readervoice.data.snapshot.BootstrapCharacterStore
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.ConfirmedChapterView
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.LogicalParagraph
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * 运行台状态总线（同一进程内 Service ⇄ Activity）。
 *
 * 之所以用进程内总线而不是 bind：整章运行 ≥ 数分钟，Activity 可能被重建/切后台，
 * 绑定会引入生命周期耦合；而运行的**唯一真源**是 `files/chapter_director/` 下的产物，
 * 这里只是给 UI 看的镜像。
 */
object ChapterRunBus {
    val log = MutableStateFlow("")
    val summary = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    /** 进度：已完成片段 / 总片段（-1 表示未知）。 */
    val done = MutableStateFlow(0)
    val total = MutableStateFlow(-1)
    /** 本轮产出的剧本行数（用于"完成回跳"与剧本视图）。 */
    val lines = MutableStateFlow(0)
}

/**
 * MOBILE-005 / 整章运行宿主：**前台服务**（`dataSync`）。
 *
 * 为什么必须这样：整章运行 ≥ 数分钟，若挂在 Activity 上，用户切到别的 App 时
 * Activity 不可见 ⇒ 进程进入 cached ⇒ 被 **Android Freezer 冻结** ⇒ 采集零进度
 * （本项目已实测两次）。前台服务让进程保持前台优先级，用户可正常用手机。
 *
 * ★ 语义与产物口径与之前的 Activity 实现**逐字一致**（Gate 证据口径冻结）：
 *   · 同样的 extras（book/from/to/maxcalls/backend）
 *   · 同样的 runner + EngineWatchdog(30s/15s) + maxTokens=96
 *   · 同样的产物 `files/chapter_director/{script_lines.jsonl, stats.txt}` 与 JSON 字段名
 *   · 同样的日志标签 `ChapterDirector`
 *   · 取消仍是 **epoch 协作式**（`cancel = { epoch > 0 || watchdog.isStuck }`），绝不杀线程
 */
class ChapterDirectorService : Service() {

    companion object {
        const val ACTION_START = "com.readervoice.app.action.CHAPTER_START"
        const val ACTION_CANCEL = "com.readervoice.app.action.CHAPTER_CANCEL"
        private const val CHANNEL_ID = "chapter_director"
        private const val NOTIF_ID = 4711
        private const val COMMIT_EVERY = 12
        private const val TAG = "ChapterDirector"

        fun startIntent(
            ctx: Context, bookId: String, from: Int, to: Int, maxCalls: Int, backend: String,
            /** 章节序号；>= 0 时只导演该章（服务内部用 LogicalParagraph.chapterId 过滤）。 */
            chapterOrdinal: Int = -1,
        ): Intent =
            Intent(ctx, ChapterDirectorService::class.java).apply {
                action = ACTION_START
                putExtra("book", bookId)
                putExtra("from", from)
                putExtra("to", to)
                putExtra("maxcalls", maxCalls)
                putExtra("backend", backend)
                putExtra("chapter", chapterOrdinal)
            }

        fun cancelIntent(ctx: Context): Intent =
            Intent(ctx, ChapterDirectorService::class.java).apply { action = ACTION_CANCEL }
    }

    @Volatile private var epoch = 0
    @Volatile private var running = false
    private val ui = StringBuilder()
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                epoch++
                append("已请求取消（epoch=$epoch）\n")
                notify("取消中…", "${if (worker?.isAlive == true) "等待当前片段结束" else "已停止"}")
            }
            else -> if (!running) {
                createChannel()
                startForegroundCompat("整章导演运行中…", "准备中")
                running = true
                ChapterRunBus.running.value = true
                val bookId = intent?.getStringExtra("book") ?: "book-4455b46ef2d2"
                val from = intent?.getIntExtra("from", 60) ?: 60
                val to = intent?.getIntExtra("to", 160) ?: 160
                val maxCalls = intent?.getIntExtra("maxcalls", 30) ?: 30
                val backend = intent?.getStringExtra("backend") ?: "cpu"
                val chapterOrdinal = intent?.getIntExtra("chapter", -1) ?: -1
                worker = Thread({ runChapter(bookId, from, to, maxCalls, backend, chapterOrdinal) }, "chapter-director").apply { start() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ChapterRunBus.running.value = false
        super.onDestroy()
    }

    private fun append(s: String) {
        ui.append(s)
        Log.i(TAG, s.trim())
        ChapterRunBus.log.value = ui.toString()
    }

    private fun runChapter(bookId: String, from: Int, to: Int, maxCalls: Int, backendReq: String, chapterOrdinal: Int) {
        val t0 = System.currentTimeMillis()
        try {
            val src = File(filesDir, "books/$bookId/source.txt")
            append("book=${src.length()}B\n")
            val bytes = src.readBytes()
            val enc = EncodingDetector.detect(bytes)
            val lines = PhysicalLineScanner().scan(bytes, enc.charset, enc.bomBytes, bookId).lines
            val packRules = assets.open("legacy/txtTocRule.json").use(LegacyChapterRuleAdapter::fromJson)
            // ★ 与 UI 用同一套规则选择（书包内的 chapter_rules.json），保证"导演本章"与章节列表一致
            val rules = ChapterRuleSelection.apply(
                packRules,
                ChapterRuleSelection.load(File(filesDir, "books/$bookId")),
            )
            val structure = ChapterStructureCompiler(rules).compile(lines, bookId)
            // ★ M0.3：产品口径 = 已确认章节视图（不消费 revision.chapters 的候选集合）
            val confirmed = ConfirmedChapterView.of(structure.revision, lines.size)
            // CANONICAL-JOIN: 这里只取 raw 候选**计数**做诊断日志（"16,060 个假章节"这类事故要看得见），
            // 章节序列本身一律用上面的 confirmed 视图（M0.3）。
            append("chapters=${confirmed.size}(confirmed) / ${structure.revision.chapters.size}(raw)\n")
            val all = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList(), chapters = confirmed)
                .paragraphs.map { it.paragraph }
            append("paragraphs=${all.size}\n")

            // ★ 按章导演：直接用 LogicalParagraph.chapterId 过滤（不需要改索引格式）
            val scope: List<LogicalParagraph> = if (chapterOrdinal >= 0) {
                val chapterId = confirmed.getOrNull(chapterOrdinal)?.chapterId
                if (chapterId == null) {
                    append("★ 章节序号 $chapterOrdinal 不存在，改为整本\n"); all
                } else {
                    append("chapter=${chapterOrdinal + 1} chapterId=$chapterId\n")
                    all.filter { it.chapterId == chapterId }
                }
            } else {
                all
            }
            if (scope.isEmpty()) append("★ 该章节范围内没有段落\n")

            // ★ 角色发现缓存：按书 + 源文件指纹，导入后预热一次，后续运行直接复用
            val cached = loadCachedNames(bookId, src)
            val names = cached ?: discoverNames(bookId, all).also { saveCachedNames(bookId, src, it) }
            append("names=${names.size}${if (cached != null) "（缓存命中）" else "（新计算并已缓存）"}\n")
            val store = BootstrapCharacterStore(names.toSet())

            val runDir = File(filesDir, "chapter_director").apply { mkdirs() }
            // ★ 每轮归档到独立文件：历史剧本永不被下一轮清空（此前 bug：只写同一个文件且开头清空）
            val archiveDir = File(runDir, "scripts").apply { mkdirs() }
            val scopeTag = if (chapterOrdinal >= 0) "ch${chapterOrdinal + 1}" else "p${from}-${to}"
            val runTag = "${bookId}_${scopeTag}_${System.currentTimeMillis()}"
            val archive = File(archiveDir, "$runTag.jsonl")
            val latest = File(runDir, "script_lines.jsonl")   // 兼容既有工具/校验脚本：始终是"最新一轮"
            latest.writeText("")

            // 真模型 decider（CPU 默认；Hexagon 需显式 NPU_EXPERIMENTAL）
            val modelDir = File(filesDir, "director-model").absolutePath + File.separator
            val selection = QwenBackendPolicy.resolve(
                if (backendReq.lowercase() in listOf("hexagon", "htp", "npu")) QwenBackendMode.NPU_EXPERIMENTAL
                else QwenBackendMode.CPU,
            )
            val backend = when (selection) {
                is QwenBackendSelection.Selected -> selection.backend.nativeName
                is QwenBackendSelection.Rejected -> "cpu"
            }
            append("backend=$backend modelDir=$modelDir\n")
            notify("整章导演运行中…", "加载模型")
            val director = ReaderDirectorEngine(QwenEngine(), modelDir, backend)
            if (!director.load()) append("★ 模型加载失败，全部走 fail-closed\n")

            // ★ 看门狗：native 调用跑在独立线程 + deadline + grace ⇒ 单次卡死不会拖死整章
            val watchdog = EngineWatchdog(director.engineForWatchdog(), timeoutMs = 30_000, graceMs = 15_000)
            val callCount = intArrayOf(0)

            // 进度：分母 = 范围内片段数（一次轻量切分即可，供进度条使用）
            val segmenter = SemanticSegmenter()
            ChapterRunBus.done.value = 0
            ChapterRunBus.lines.value = 0
            ChapterRunBus.total.value = scope.sumOf { segmenter.segment(it).size }

            // ★ P0-B：剧本持久化（JSONL 正文 + state.json 检查点）—— 走 ScriptRepository
            //   稳定键 = bookId + chapterId + sourceRevisionId；chapterId 用 M0.4 的内容派生 id
            //   ★ 只有「按章导演」才落库（产品路径）。整段范围运行是调试动作，落库会产生
            //     "混章剧本"污染按章视图（用户实测：125 段跨多章混成一份）。
            val scriptRepo = ScriptRepository(File(filesDir, "books"))
            val srcRev = "${src.length()}-${src.lastModified()}"
            val scriptChapterId =
                if (chapterOrdinal >= 0) (confirmed.getOrNull(chapterOrdinal)?.chapterId ?: 0L) else -1L
            val run: ScriptRun? =
                if (scriptChapterId > 0) {
                    scriptRepo.beginOrResume(bookId, scriptChapterId, srcRev, ChapterRunBus.total.value)
                } else {
                    null
                }
            if (run?.resumed == true) {
                append("resume from S${run.state.nextSegmentIndex}（已有 ${run.state.lineCount} 行）\n")
            }
            run?.let { append("script=${it.state.revisionId} chapterId=$scriptChapterId\n") }
                ?: append("（调试范围运行：不写入剧本仓库）\n")

            val stats = ChapterDirectorRunner(store, bookPk = 1L).run(
                scope.subList(from.coerceIn(0, scope.size), minOf(to, scope.size)),
                decider = { prompt ->
                    if (callCount[0] >= maxCalls) {
                        null                       // 本批上限：计入 noOutput（fail-closed），不假装有答案
                    } else if (watchdog.isStuck) {
                        null                       // ★ ENGINE_STUCK：暂停后续模型调用（不再堆请求）
                    } else {
                        callCount[0]++
                        notify(
                            "整章导演运行中…",
                            "模型调用 ${callCount[0]}  已处理 ${ChapterRunBus.done.value} 段",
                        )
                        watchdog.generate(prompt, maxTokens = 96)
                    }
                },
                cancel = { epoch > 0 || watchdog.isStuck },
                onLine = { line ->
                    run?.append(line)            // ★ 每个 ACCEPT 行即时落盘（仅按章运行）
                    latest.appendText(ScriptLineCodec.encode(line) + "\n")  // 兼容镜像
                    ChapterRunBus.lines.value = ChapterRunBus.lines.value + 1
                },
                onOutcome = { outcome ->
                    ChapterRunBus.done.value = ChapterRunBus.done.value + 1
                    if (outcome.outcome == ValidationOutcome.REJECT) run?.countRejected()
                    // ★ 提交点：每 12 段（= P1 的 batch 粒度）推进一次 checkpoint
                    if (ChapterRunBus.done.value % COMMIT_EVERY == 0) {
                        run?.commit(ChapterRunBus.done.value)
                        Log.i(TAG, "BATCH_COMMIT next=${run?.state?.nextSegmentIndex}")
                    }
                },
                resumeFrom = run?.state?.nextSegmentIndex ?: 0,
            )
            run?.commit(ChapterRunBus.done.value)
            run?.finish(if (stats.canceled) ScriptState.CANCELED else ScriptState.COMPLETED)
            run?.let {
                append(
                    "script state: status=${it.state.status} next=${it.state.nextSegmentIndex} " +
                        "accepted=${it.state.accepted} verify=${it.state.verify} rejected=${it.state.rejected}\n",
                )
            }
            append("watchdog: ${watchdog.summary()}\n")
            watchdog.shutdown()

            val report = JSONObject()
                .put("book", bookId).put("from", from).put("to", to)
                .put("segments", stats.segments).put("shortcut", stats.decidedByShortcut)
                .put("model_calls", stats.modelCalls).put("no_output", stats.noOutput)
                .put("accepted", stats.acceptedAll).put("downgrade", stats.downgrade)
                .put("verify", stats.verify).put("reject", stats.reject)
                .put("lines", stats.linesEmitted).put("coverage", stats.coverage)
                .put("fail_closed", stats.failClosedRate).put("canceled", stats.canceled).put("watchdog", watchdog.summary())
                .put("script_file", archive.name)
                .put("chapter", chapterOrdinal)
                .put("elapsed_ms", System.currentTimeMillis() - t0)
                .put("failure_codes", JSONObject(stats.failureCodes as Map<*, *>))
            File(runDir, "stats.txt").writeText(report.toString(2))

            ChapterRunBus.summary.value = buildString {
                append("lines=${stats.linesEmitted}  coverage=${"%.1f".format(stats.coverage * 100)}%\n")
                append("acc=${stats.acceptedAll} ver=${stats.verify} down=${stats.downgrade} rej=${stats.reject}\n")
                append("shortcut=${stats.decidedByShortcut} calls=${stats.modelCalls} noOutput=${stats.noOutput} canceled=${stats.canceled}\n")
                append("watchdog: ${watchdog.summary()}")
            }
            append("DONE\n" + stats.pretty())
            Log.i(TAG, "DONE lines=${stats.linesEmitted} coverage=${stats.coverage} canceled=${stats.canceled}")
            notify(
                if (stats.canceled) "整章已取消" else "整章完成",
                "lines=${stats.linesEmitted} coverage=${"%.1f".format(stats.coverage * 100)}%",
            )
        } catch (t: Throwable) {
            append("FAILED: ${t::class.java.simpleName}: ${t.message}")
            Log.e(TAG, "FAILED", t)
            notify("整章运行失败", t::class.java.simpleName)
        }
        running = false
        ChapterRunBus.running.value = false
        stopForegroundCompat()
        stopSelf()
    }

    // ── 角色发现缓存（按书 + 源文件指纹 + 闸门版本）─────────────
    //
    // 背景：全书两轮角色发现要扫全部段落，是每次运行里最贵的一步（内存紧张时尤甚）。
    // 缓存键 = bookId + 源文件长度 + 最后修改时间 + **gateVersion**；原文不可变（C1），
    // 所以指纹稳定即结果可复用。
    // ★ CH-0 ①：gateVersion 不可省 —— 旧缓存（未过合法性闸门）里混着"哈哈/招呼/李寄舟询"
    //   这类 cue 切片，一旦复用就会让模型继续在污染候选里作答。

    private fun discoveryCacheFile(bookId: String) = File(filesDir, "chapter_director/characters_$bookId.json")

    private fun nameGateAuditFile(bookId: String) = File(filesDir, "chapter_director/name_gate_$bookId.txt")

    private fun loadCachedNames(bookId: String, src: File): LinkedHashSet<String>? {
        val f = discoveryCacheFile(bookId)
        if (!f.isFile) return null
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        if (o.optInt("gateVersion", 0) != CharacterDiscovery.CACHE_VERSION) return null
        if (o.optLong("sourceLength") != src.length() || o.optLong("sourceModified") != src.lastModified()) return null
        val arr = o.optJSONArray("names") ?: return null
        return LinkedHashSet((0 until arr.length()).map { arr.getString(it) })
    }

    private fun saveCachedNames(bookId: String, src: File, names: Collection<String>) {
        runCatching {
            val f = discoveryCacheFile(bookId)
            f.parentFile?.mkdirs()
            f.writeText(
                JSONObject()
                    .put("bookId", bookId)
                    .put("gateVersion", CharacterDiscovery.CACHE_VERSION)
                    .put("sourceLength", src.length())
                    .put("sourceModified", src.lastModified())
                    .put("names", org.json.JSONArray(names.toList()))
                    .toString(),
            )
        }
    }

    /**
     * 角色发现（带候选人名合法性闸门）。
     *
     * ★ 逻辑已搬到 data-room 的 `CharacterDiscovery`（JVM 可测，产品路径与 Gate 同源）。
     *   本方法只负责：跑一次、落审计证据、返回名字集。
     */
    private fun discoverNames(bookId: String, all: List<LogicalParagraph>): LinkedHashSet<String> {
        val t0 = System.currentTimeMillis()
        val result = CharacterDiscovery.discover(all)
        runCatching { nameGateAuditFile(bookId).writeText(result.audit(bookId)) }
        append(
            "nameGate: harvested=${result.harvested} accepted=${result.names.size} " +
                "rejected=${result.rejected.size} (${System.currentTimeMillis() - t0}ms)\n",
        )
        val reasons = result.byReason()
        if (reasons.isNotEmpty()) {
            append("nameGate.reasons=" + reasons.entries.joinToString(" ") { "${it.key}=${it.value}" } + "\n")
        }
        // ★ 抽样打印被拦下的最高频污染（真机无法读 logcat 时的现场证据）
        result.rejected.sortedByDescending { it.evidence?.total ?: 0 }.take(12).forEach {
            append("  reject ${it.surface} (${it.reject.name}, total=${it.evidence?.total ?: 0})\n")
        }
        return LinkedHashSet(result.names)
    }

    // ── 通知（前台服务必需）──────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "整章导演", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "整章朗读剧本生成进度"
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChapterDirectorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, cancelIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "取消（epoch++）", cancel).build())
            .build()
    }

    private fun startForegroundCompat(title: String, text: String) {
        val n = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notify(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }
}





