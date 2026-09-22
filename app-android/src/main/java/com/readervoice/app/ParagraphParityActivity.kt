package com.readervoice.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.readervoice.parser.chapters.ChapterStructureCompiler
import com.readervoice.parser.chapters.LegacyChapterRuleAdapter
import com.readervoice.parser.paragraph.ParagraphRecoveryPipeline
import com.readervoice.parser.source.EncodingDetector
import com.readervoice.parser.source.PhysicalLineScanner
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * MOBILE-005 / M1 Gate 工具：**设备端段落化 dump**（G1 对拍用，debug-only）。
 *
 * 用法：
 *   adb shell am start -n com.readervoice.app/.ParagraphParityActivity \
 *     --es infile book-4455b46ef2d2/source.txt --es book <bookId> --es tag device --ez autorun true
 *
 * 记录格式与桌面端 `M1ParagraphParityTest` **逐字段一致**（谁生成谁负责）：
 *   {"i","pid","rid","idx","ch","block","policy","conf","len","sha"}
 * 其中 sha = sha256(normalizedText) —— 只有它相同才说明段落化真的等价。
 * 调用路径刻意与 BookRepository.compileStructure 一致（同 scanner 参数、同 rules 资源、同 bookId）。
 */
class ParagraphParityActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 13f }
        setContentView(ScrollView(this).apply { addView(view) })

        val infile = intent?.getStringExtra("infile") ?: "book-4455b46ef2d2/source.txt"
        val bookId = intent?.getStringExtra("book") ?: "book-4455b46ef2d2"
        val tag = intent?.getStringExtra("tag") ?: "device"

        Thread {
            val out = StringBuilder()
            try {
                val src = File(filesDir, infile)
                val bytes = src.readBytes()
                val encoding = EncodingDetector.detect(bytes)
                val lines = PhysicalLineScanner().scan(bytes, encoding.charset, encoding.bomBytes, bookId).lines
                val rules = assets.open("legacy/txtTocRule.json").use(LegacyChapterRuleAdapter::fromJson)
                val chapters = ChapterStructureCompiler(rules).compile(lines, bookId).revision.chapters
                val t0 = System.nanoTime()
                val para = ParagraphRecoveryPipeline().run(lines, bookId, emptySet(), emptySet(), emptySet(), emptyList())
                val ms = (System.nanoTime() - t0) / 1e6

                val dir = File(filesDir, "m1_parity").apply { mkdirs() }
                val file = File(dir, "$tag.jsonl")
                val md = MessageDigest.getInstance("SHA-256")
                file.bufferedWriter(Charsets.UTF_8).use { w ->
                    para.paragraphs.forEachIndexed { i, bp ->
                        val p = bp.paragraph
                        val sha = md.digest(p.normalizedText.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        md.reset()
                        w.write(
                            JSONObject()
                                .put("i", i).put("pid", p.paragraphId).put("rid", p.revisionId)
                                .put("idx", p.paragraphIndex).put("ch", p.chapterId ?: JSONObject.NULL)
                                .put("block", p.blockType.name).put("policy", p.readPolicy.name)
                                .put("conf", p.boundaryConfidence).put("len", p.normalizedText.length)
                                .put("sha", sha)
                                .toString()
                        )
                        w.newLine()
                    }
                }
                out.appendLine("charset=${encoding.charset} lines=${lines.size} chapters=${chapters.size}")
                out.appendLine("paragraphs=${para.paragraphs.size} pipeline=${"%.0f".format(ms)}ms")
                out.appendLine("bookId=$bookId infile=$infile")
                out.appendLine("wrote=${file.absolutePath} bytes=${file.length()}")
                Log.i("ParagraphParity", "DONE lines=${lines.size} paragraphs=${para.paragraphs.size} file=${file.absolutePath}")
            } catch (t: Throwable) {
                out.appendLine("FAILED: ${t::class.java.simpleName}: ${t.message}")
                Log.e("ParagraphParity", "FAILED", t)
            }
            runOnUiThread { view.text = out.toString() }
        }.start()
    }
}
