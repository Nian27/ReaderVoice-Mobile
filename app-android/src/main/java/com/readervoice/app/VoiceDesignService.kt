package com.readervoice.app

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
 * 否则退到后台会被 lmkd 直接杀掉（真机实测 reason=lmkd code 91）。
 */
class VoiceDesignService : Service() {

    companion object {
        const val CH_ID = "vd_run"
        const val NOTI_ID = 4711
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_MAXFRAMES = "maxFrames"
        private val logLock = Any()

        fun logFile(ctx: Context): File {
            val d = File(ctx.filesDir, "voicedesign")
            if (!d.exists()) d.mkdirs()
            return File(d, "vd_run.log")
        }

        fun append(ctx: Context, s: String) {
            synchronized(logLock) {
                try { logFile(ctx).appendText(s + "\n") } catch (_: Throwable) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID)
        else Notification.Builder(this)
        val b = n.setContentTitle("VoiceDesign").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        startForeground(NOTI_ID, b)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val backend = intent?.getStringExtra(EXTRA_BACKEND) ?: "htp"
        val maxFrames = intent?.getIntExtra(EXTRA_MAXFRAMES, 300) ?: 300
        notify("准备中…")
        Thread {
            val dir = File(filesDir, "voicedesign").apply { if (!exists()) mkdirs() }.absolutePath + "/"
            val out = dir + "reference_" + backend + "_" + System.currentTimeMillis() + ".wav"
            append(this, "backend=" + backend)
            append(this, "模型目录=" + dir)
            append(this, "输出=" + out)
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
                val r = eng.run(dir, out, maxFrames)
                append(this, "结果: " + r)
                append(this, "耗时: " + (System.currentTimeMillis() - t1) + " ms")
                append(this, if (File(out).exists()) "WAV: " + out + " (" + File(out).length() + " bytes)" else "WAV 未生成")
                notify("完成")
            } catch (t: Throwable) {
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
