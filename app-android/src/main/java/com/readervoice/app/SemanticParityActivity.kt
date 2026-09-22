package com.readervoice.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.readervoice.data.snapshot.M2ParityRunner
import com.readervoice.data.snapshot.TraceCharacterStore
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import java.io.File

/**
 * MOBILE-005 / M2 Gate 工具：**设备端语义层 dump**（G2 对拍，debug-only）。
 *
 * 依赖同一输入（input.txt）+ 桌面产出的 trace.json（角色库只读调用的记录）。
 * 语义执行体与桌面共用 `M2ParityRunner`（不是两份实现），因此"一致"不是碰巧。
 *
 * 用法：
 *   adb shell am start -n com.readervoice.app/.SemanticParityActivity \
 *     --es infile m2/input.txt --es trace m2/trace.json --es tag device
 */
class SemanticParityActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 13f }
        setContentView(ScrollView(this).apply { addView(view) })

        val infile = intent?.getStringExtra("infile") ?: "m2/input.txt"
        val traceFile = intent?.getStringExtra("trace") ?: "m2/trace.json"
        val tag = intent?.getStringExtra("tag") ?: "device"
        val bookId = intent?.getStringExtra("book") ?: "book-4455b46ef2d2"

        Thread {
            val out = StringBuilder()
            try {
                val bytes = File(filesDir, infile).readBytes()
                val encoding = EncodingDetector.detect(bytes)
                val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
                val rules = assets.open("legacy/txtTocRule.json").use(LegacyChapterRuleAdapter::fromJson)
                ChapterStructureCompiler(rules).compile(lines, bookId)
                val para = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
                val paragraphs = para.paragraphs.map { it.paragraph }

                val store = TraceCharacterStore(File(filesDir, traceFile).readText())
                val dir = File(filesDir, "m2_parity").apply { mkdirs() }
                val jsonl = File(dir, "$tag.jsonl")
                val canon = File(dir, "${tag}_canonical.txt")
                val canonLines = mutableListOf<String>()
                val t0 = System.nanoTime()
                val n = M2ParityRunner.run(paragraphs, 1L, store) { line ->
                    canonLines += M2ParityRunner.canonicalLine(JSONObject(line))
                    jsonl.appendText(line + "\n")
                }
                canon.writeText(canonLines.joinToString("\n") + "\n")
                val ms = (System.nanoTime() - t0) / 1e6
                out.appendLine("paragraphs=${paragraphs.size} targets=$n semantic=${"%.0f".format(ms)}ms")
                out.appendLine("wrote=${jsonl.absolutePath} lines=${canonLines.size}")
                Log.i("SemanticParity", "DONE paragraphs=${paragraphs.size} targets=$n file=${jsonl.absolutePath}")
            } catch (t: Throwable) {
                out.appendLine("FAILED: ${t::class.java.simpleName}: ${t.message}")
                Log.e("SemanticParity", "FAILED", t)
            }
            runOnUiThread { view.text = out.toString() }
        }.start()
    }
}
