package com.voicedesign.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * VoiceDesign 前台服务：整链推理可达数分钟且占大内存，必须以前台服务运行，
 * 否则退到后台会被厂商内存管理直接杀掉（真机实测 reason=lmkd / iAwareF LowMem）。
 */
class VoiceDesignService : Service() {

    companion object {
        const val CH_ID = "vd_run"
        const val NOTI_ID = 4711
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_MAXFRAMES = "maxFrames"
        const val EXTRA_INSTRUCT = "instruct"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANG = "lang"
        private val logLock = Any()

        fun logFile(ctx: Context): File {
            val d = File(ctx.filesDir, "voicedesign")
            if (!d.exists()) d.mkdirs()
            return File(d, "vd_run.log")
        }

        fun append(ctx: Context, s: String) {
            synchronized(logLock) { try { logFile(ctx).appendText(s + "\n") } catch (_: Throwable) {} }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var player: android.media.MediaPlayer? = null

    /** 生成完立即播放给用户听；不依赖任何外部播放器。 */
    private fun playWav(path: String) {
        try {
            player?.release()
            player = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                setDataSource(path)
                setOnCompletionListener { append(this@VoiceDesignService, "播放结束"); it.release(); player = null }
                setOnErrorListener { mp, _, _ -> append(this@VoiceDesignService, "播放出错"); mp.release(); player = null; true }
                prepare(); start()
            }
        } catch (t: Throwable) {
            append(this, "播放失败: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "VoiceDesign", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun notify(text: String) {
        ensureChannel()
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CH_ID)
                else Notification.Builder(this)
        startForeground(NOTI_ID, b.setContentTitle("VoiceDesign").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val backend = intent?.getStringExtra(EXTRA_BACKEND) ?: "opencl"
        val maxFrames = intent?.getIntExtra(EXTRA_MAXFRAMES, 300) ?: 300
        val instruct = intent?.getStringExtra(EXTRA_INSTRUCT) ?: ""
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
        val lang = intent?.getIntExtra(EXTRA_LANG, 2055) ?: 2055
        notify("准备中…")
        Thread {
            val dir = File(filesDir, "voicedesign").apply { if (!exists()) mkdirs() }.absolutePath + "/"
            val stamp = System.currentTimeMillis()
            val out = dir + "vd_" + backend + "_" + stamp + ".wav"
            synchronized(logLock) { try { logFile(this).writeText("") } catch (_: Throwable) {} }
            append(this, "backend=" + backend)
            append(this, "instruct=" + instruct)
            append(this, "text=" + text)
            append(this, "lang=" + lang)
            append(this, "输出=" + out)
            VoiceDesignEngine.prepareAdsp(applicationInfo.nativeLibraryDir)
            val eng = VoiceDesignEngine()
            try {
                append(this, "[1/3] load ...")
                if (!eng.load(dir, backend, applicationInfo.nativeLibraryDir)) {
                    append(this, "FAIL load: " + eng.lastError()); notify("load 失败"); return@Thread
                }
                append(this, "[2/3] load OK")
                append(this, "[3/3] 运行整链 ...")
                notify("推理中…")
                val t1 = System.currentTimeMillis()
                val r = eng.run(dir, out, maxFrames, instruct, text, lang)
                val dt = System.currentTimeMillis() - t1
                val wf = File(out)
                append(this, "--------------------------------")
                append(this, "完成：结果 = " + r)
                append(this, "完成：耗时 = " + dt + " ms")
                if (wf.exists() && wf.length() > 44) {
                    val sec = (wf.length() - 44) / 2.0 / 24000.0
                    append(this, "完成：WAV = " + out)
                    append(this, String.format("完成：%.2f 秒 / %d 字节 —— 正在播放 🔊", sec, wf.length()))
                    notify(String.format("完成 %.1fs，播放中", sec))
                    playWav(out)
                } else {
                    append(this, "完成：WAV 未生成")
                    notify("WAV 未生成")
                }
                append(this, "--------------------------------")
            } catch (t: Throwable) {
                append(this, "=========== 异常退出 ===========")
                append(this, "EXCEPTION: " + t.javaClass.simpleName + ": " + t.message)
                notify("异常")
            } finally {
                eng.release()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
}
