package com.readervoice.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * VoiceDesign 控制台：整链交给前台服务执行（后台也能跑完，见 VoiceDesignService），
 * 本页只负责触发与展示日志。
 */
class VoiceDesignActivity : Activity() {

    private lateinit var log: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var ticking = false

    private fun modelDir(): String {
        val d = File(filesDir, "voicedesign")
        if (!d.exists()) d.mkdirs()
        return d.absolutePath + "/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "VoiceDesign HTP 全链"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply {
            text = "文字 -> VoiceDesign -> 16-code -> WAV"
            setTextColor(Color.rgb(15, 23, 42)); textSize = 18f
        })
        root.addView(TextView(this).apply {
            text = "模型目录: " + modelDir()
            textSize = 11f; setTextColor(Color.rgb(100, 116, 139))
        })
        log = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(30, 41, 59)); setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fun mk(label: String, backend: String) = Button(this).apply {
            text = label
            setOnClickListener { start(backend) }
        }
        root.addView(mk("跑 HTP（NPU）", "htp"))
        root.addView(mk("跑 CPU（对照）", "cpu"))
        setContentView(root)
        startTicking()
        if (intent?.getBooleanExtra("autorun", false) == true) {
            start(intent?.getStringExtra("backend") ?: "htp")
        }
    }

    private fun start(backend: String) {
        val i = Intent(this, VoiceDesignService::class.java)
            .putExtra(VoiceDesignService.EXTRA_BACKEND, backend)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun startTicking() {
        ticking = true
        val f = VoiceDesignService.logFile(this)
        Thread {
            while (ticking) {
                val txt = try { if (f.exists()) f.readText() else "" } catch (_: Throwable) { "" }
                ui.post { log.text = if (txt.isBlank()) "（等待服务输出…）" else txt }
                try { Thread.sleep(1500) } catch (_: Throwable) { return@Thread }
            }
        }.start()
    }

    override fun onDestroy() { ticking = false; super.onDestroy() }
}
