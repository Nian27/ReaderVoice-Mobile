package com.readervoice.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ChapterListActivity : Activity() {

    companion object {
        /**
         * 首次导演的默认批。
         * 说明：`ChapterIndexEntry` 目前只带字节锚点（anchorByte），没有「章 → 段落下标」的映射，
         * 所以 v1 用「从开头的有界批次」作为产品入口；按章导演要等索引补上段落区间。
         */
        private const val DEFAULT_TO = 160
        private const val DEFAULT_MAX_CALLS = 60

        /**
         * 「导演本章」的上界：章节段落数未知，用一个足够大的上界；
         * 真实范围由服务按 `chapterId` 过滤后决定（过滤后 subList 会被 coerce）。
         */
        private const val CHAPTER_TO = 10_000
    }

    private var audioPlayer: CachedAudioPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(MainActivity.EXTRA_BOOK_ID)
        val packageDir = bookId?.let { BookRepository(this).packageFor(it) }
        if (packageDir == null) {
            setContentView(TextView(this).apply { text = "Book Package 不存在" })
            return
        }
        val chapters = BookDatabase.chapters(packageDir)
        val reading = ReadingStateStore.load(packageDir)
        val cachedAudio = BookDatabase.syncCachedAudioAssets(packageDir)
        title = "章节索引"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            applySafeDrawingPadding()
        }
        root.addView(TextView(this).apply {
            text = "章节"
            setTextSp(26f)
            setTextColor(Color.rgb(15, 23, 42))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = if (chapters.isEmpty()) {
                "未识别到章节。\n\n可能原因：这本书的标题写法不在**默认启用**的规则里" +
                    "（默认只开标准规则：`第N章` / `Chapter N` / `1、` 等；" +
                    "`《…》` 成对标题、`====` 分隔线、纯数字标题、顶格标题等**激进规则默认关闭**，" +
                    "它们容易把书名号正文和分隔线当成章节）。\n\n" +
                    "下一步：点下面的【章节规则（重新分章）】，勾选匹配这本书的规则后重新分章。"
            } else {
                "已生成 ${chapters.size} 个章节（canonical：只含已确认章节，候选/待定不进入阅读与导演）。"
            }
            setTextSp(14f)
            setTextColor(Color.rgb(100, 116, 139))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8); bottomMargin = dp(24) })
        // ★ R1 空状态引导：没识别到章节时必须给出**可执行的下一步**，不能只留一句"未识别"
        if (chapters.isEmpty()) {
            root.addView(Button(this).apply {
                text = "去选择章节规则"
                setTextSp(16f)
                setTextColor(Color.WHITE)
                background = roundedSurface(Color.rgb(29, 78, 216), radiusDp = 14, strokeColor = Color.rgb(29, 78, 216))
                setOnClickListener {
                    startActivity(
                        Intent(this@ChapterListActivity, ChapterRulesActivity::class.java)
                            .putExtra(MainActivity.EXTRA_BOOK_ID, bookId),
                    )
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            ).apply { bottomMargin = dp(20) })
        }
        val playbackStatus = TextView(this).apply {
            text = if (cachedAudio.isEmpty()) "没有已缓存的 WAV；TTS 生成尚未接入。" else "发现 ${cachedAudio.size} 个已缓存 WAV。"
            setTextSp(14f)
            setTextColor(Color.rgb(100, 116, 139))
        }
        root.addView(playbackStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) })
        root.addView(Button(this).apply {
            text = "播放已缓存音频"
            isEnabled = cachedAudio.isNotEmpty()
            setTextSp(16f)
            setOnClickListener { audioPlayer?.play(cachedAudio) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(18) })
        audioPlayer = CachedAudioPlayer(this, packageDir) { message -> playbackStatus.text = message }

        // ★ 产品入口：开始导演（生成分角色剧本）
        //   由前台服务承载 ⇒ 可以离开 App、锁屏，进度不中断（cached-app freezer 免疫）
        root.addView(TextView(this).apply {
            text = "开始导演：本机 0.8B 模型逐段判定「谁在说、怎么说」，产出分角色剧本。\n" +
                "★ 逐章导演请看每章下面的【导演本章】——每章剧本独立保存。下面是跨章调试入口（不落库）。"
            setTextSp(13f)
            setTextColor(Color.rgb(100, 116, 139))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) })
        root.addView(Button(this).apply {
            text = "调试：范围导演（跨章，$DEFAULT_TO 段，不落剧本库）"
            setTextSp(16f)
            setTextColor(Color.WHITE)
            background = roundedSurface(Color.rgb(29, 78, 216), radiusDp = 14, strokeColor = Color.rgb(29, 78, 216))
            setOnClickListener {
                startForegroundService(
                    ChapterDirectorService.startIntent(
                        this@ChapterListActivity, bookId, 0, DEFAULT_TO, DEFAULT_MAX_CALLS, "hexagon",
                    ),
                )
                startActivity(Intent(this@ChapterListActivity, ChapterDirectorActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52),
        ).apply { bottomMargin = dp(20) })

        // ★ 章节规则：自动规则不可能命中所有网文目录，给用户显式的"按规则重新分章"入口
        root.addView(Button(this).apply {
            text = "章节规则（重新分章）"
            setTextSp(15f)
            setOnClickListener {
                startActivity(Intent(this@ChapterListActivity, ChapterRulesActivity::class.java).putExtra(MainActivity.EXTRA_BOOK_ID, bookId))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46),
        ).apply { bottomMargin = dp(12) })

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chapters.forEach { chapter ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedSurface()
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(TextView(this@ChapterListActivity).apply {
                    text = chapterTitle(chapter) + if (reading?.chapterId == chapter.anchorByte) "　←当前" else ""
                    setTextSp(18f)
                    // 当前阅读章高亮（位置是书的数据，目录据此标记）
                    setTextColor(if (reading?.chapterId == chapter.anchorByte) Color.rgb(29, 78, 216) else Color.rgb(15, 23, 42))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@ChapterListActivity).apply {
                    text = chapterSubtitle(chapter)
                    setTextSp(14f)
                    setTextColor(Color.rgb(100, 116, 139))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) })
                // ★ 看原文：判断导演准确度必须对着真实正文看（行号区间取文，不受编码影响）
                addView(LinearLayout(this@ChapterListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@ChapterListActivity).apply {
                        text = "阅读"
                        setTextSp(14f)
                        setOnClickListener {
                            startActivity(
                                Intent(this@ChapterListActivity, ReaderActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_BOOK_ID, bookId)
                                    // 用**章节稳定 id**（anchor byteStart）而不是下标/ordinal：
                                    // 章节集合变化时 ordinal 会变，chapterId 不会（R1 canonical 口径）
                                    .putExtra(ReaderActivity.EXTRA_CHAPTER_ID, chapter.anchorByte),
                            )
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(40),
                    ))
                    addView(Button(this@ChapterListActivity).apply {
                        text = "剧本"
                        setTextSp(14f)
                        setOnClickListener {
                            startActivity(
                                Intent(this@ChapterListActivity, ScriptViewActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_BOOK_ID, bookId),
                            )
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(40),
                    ).apply { leftMargin = dp(10) })
                    addView(Button(this@ChapterListActivity).apply {
                        text = "导演本章"
                        setTextSp(14f)
                        isEnabled = chapter.state != "PENDING_STRUCTURE"
                        setOnClickListener {
                            startForegroundService(
                                ChapterDirectorService.startIntent(
                                    this@ChapterListActivity, bookId,
                                    from = 0, to = CHAPTER_TO, maxCalls = DEFAULT_MAX_CALLS,
                                    backend = "hexagon", chapterOrdinal = chapter.ordinal,
                                ),
                            )
                            startActivity(Intent(this@ChapterListActivity, ChapterDirectorActivity::class.java))
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(40),
                    ).apply { leftMargin = dp(10) })
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) })
        }
        root.addView(ScrollView(this).apply {
            addView(content)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        setContentView(root)
        root.requestApplyInsets()
    }

    override fun onDestroy() {
        audioPlayer?.release()
        super.onDestroy()
    }
}



