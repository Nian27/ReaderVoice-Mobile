package com.voicedesign.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * VoiceDesign：输入「音色描述」+「要说的内容」-> 端侧生成音色 WAV。
 * 推理交给前台服务；本页负责输入与日志展示。
 */
class VoiceDesignActivity : Activity() {

    private lateinit var instructBox: EditText
    private lateinit var textBox: EditText
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
        title = "VoiceDesign 文字设计音色"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16) }

        root.addView(TextView(this).apply {
            text = "音色描述（如：苍老男性，低沉略沙哑，语气沉稳）"
            textSize = 12f; setTextColor(Color.rgb(71, 85, 105))
        })
        instructBox = EditText(this).apply {
            setText("苍老男性，低沉略沙哑，声音有岁月感，语气沉稳、有威严，但并不凶狠。")
            textSize = 13f; maxLines = 3
        }
        root.addView(instructBox)

        root.addView(TextView(this).apply {
            text = "要说的内容"
            textSize = 12f; setTextColor(Color.rgb(71, 85, 105))
        })
        textBox = EditText(this).apply {
            setText("小家伙，修炼之事急不得，根基若是不稳，日后吃亏的还是你自己。")
            textSize = 13f; maxLines = 3
        }
        root.addView(textBox)

        fun mk(label: String, backend: String) = Button(this).apply {
            text = label
            setOnClickListener { start(backend) }
        }
        root.addView(mk("生成音色 · OpenCL(GPU)", "opencl"))
        root.addView(mk("生成音色 · HTP(NPU)", "htp"))
        root.addView(mk("生成音色 · CPU", "cpu"))

        log = TextView(this).apply { textSize = 11f; setTextColor(Color.rgb(30, 41, 59)); setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        startTicking()
        if (intent?.getBooleanExtra("autorun", false) == true) {
            start(intent?.getStringExtra("backend") ?: "opencl")
        }
    }

    private fun start(backend: String) {
        var instruct = instructBox.text.toString()
        var text = textBox.text.toString()
        if (intent?.getBooleanExtra("autorun", false) == true) {
            instruct = intent?.getStringExtra("instruct") ?: instruct
            text = intent?.getStringExtra("text") ?: text
        }
        val i = Intent(this, VoiceDesignService::class.java)
            .putExtra(VoiceDesignService.EXTRA_BACKEND, backend)
            .putExtra(VoiceDesignService.EXTRA_INSTRUCT, instruct)
            .putExtra(VoiceDesignService.EXTRA_TEXT, text)
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
