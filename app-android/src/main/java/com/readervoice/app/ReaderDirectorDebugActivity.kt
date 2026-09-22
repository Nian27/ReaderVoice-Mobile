package com.readervoice.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * ReaderDirector 可视化调试台（第一版冻结）：
 * 输入小说片段 → 展示最终 Prompt → Generate → 展示 Raw Output → 展示 Parsed 结果。
 * 优先透明可见，不做漂亮。
 */
class ReaderDirectorDebugActivity : Activity() {

    private lateinit var engine: ReaderDirectorEngine
    private lateinit var statusBar: TextView
    private lateinit var inputBefore: EditText
    private lateinit var inputCurrent: EditText
    private lateinit var inputAfter: EditText
    private lateinit var inputRoles: EditText
    private lateinit var rawOutput: TextView
    private lateinit var parsedOutput: TextView
    private lateinit var debugOutput: TextView
    private var lastPrompt: String = ""
    private var loaded = false

    companion object {
        // ── 显式实验开关（不改变发布默认）─────────────────────────────────────
        // 用法（ADB）: adb shell am start -n com.readervoice.app/.ReaderDirectorDebugActivity \
        //                --es readerdirector.backend hexagon
        // 默认（不传 / cpu / 未知值）= CPU correctness 路径，fail-closed。
        //
        // ★ 这里【不再】持有 HEXAGON_CORRECTNESS_PASSED：该常量唯一权威在
        //   QwenBackendPolicy（当前 = false）。实验 backend 只经 NPU_EXPERIMENTAL 进入，
        //   结果带 experimental 标记，禁止写入 COMMITTED 状态。
        //   HTP correctness Gate 通过后才允许把 policy 常量翻真（需 runs/ 证据 + ADR）。
        const val EXTRA_BACKEND = "readerdirector.backend"
        /** Gate 用：files/ 下的 prompt 文件名；给了就原样送模型（不套 Director 模板）。 */
        const val EXTRA_RAW_PROMPT = "readerdirector.rawprompt"
        /** Gate 用：生成 token 上限，0 = 只 prefill（配合 logits dump 做数值对拍）。 */
        const val EXTRA_MAX_TOKENS = "readerdirector.maxtokens"

        /** Gate 用：一路 --ez readerdirector.autorun true 时，onCreate 自动 load + generate。 */
        const val EXTRA_AUTORUN = "readerdirector.autorun"
        /** Gate 用：--ez readerdirector.profile true → 打开 host 逐 op 计时 + DSP/perf 落盘。 */
        const val EXTRA_PROFILE = "readerdirector.profile"

        fun modeOf(requested: String?): QwenBackendMode = when (requested?.trim()?.lowercase()) {
            null, "", "cpu" -> QwenBackendMode.CPU
            "hexagon", "htp", "npu" -> QwenBackendMode.NPU_EXPERIMENTAL
            else -> QwenBackendMode.CPU
        }
    }

    private lateinit var selection: QwenBackendSelection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelDir = File(filesDir, "director-model").absolutePath + File.separator
        selection = QwenBackendPolicy.resolve(modeOf(intent?.getStringExtra(EXTRA_BACKEND)))
        val nativeName = when (val s = selection) {
            is QwenBackendSelection.Selected -> s.backend.nativeName
            is QwenBackendSelection.Rejected -> "cpu"
        }
        val qwen = QwenEngine()
        // logits/input_embeds 转储到 App 可写目录（App 的 CWD 是 "/"，默认会静默失败）
        qwen.setDumpDir(filesDir.absolutePath)
        if (intent?.getBooleanExtra(EXTRA_PROFILE, false) == true) qwen.setProfiling(true)
        engine = ReaderDirectorEngine(qwen, modelDir, nativeName)
        buildUi()
        // Gate 自动化：--ez readerdirector.autorun true → 自动 load 并立即 generate
        // （免去手工点按，gate 脚本可全自动跑；不影响手工使用路径）。
        if (intent?.getBooleanExtra(EXTRA_AUTORUN, false) == true) {
            statusBar.post {
                loadModel()
                generate()
            }
        }
    }

    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(Color.rgb(248, 250, 252)) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }

        col.addView(TextView(this).apply {
            text = "Qwen3.5 ReaderDirector";
            setTextSp(22f);
            setTextColor(Color.rgb(15, 23, 42));
            setTypeface(typeface, Typeface.BOLD);
        })
        statusBar = TextView(this).apply { setTextSp(13f); setTextColor(Color.rgb(100, 116, 139)) }
        col.addView(statusBar, lp(top = dp(6)))

        // 控制按钮行
        val ctrl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctrl.addView(btn("Load Model") { loadModel() }, lp(w = 0, h = dp(44), weight = 1f, right = dp(6)))
        ctrl.addView(btn("Reset") { resetEngine() }, lp(w = 0, h = dp(44), weight = 1f, right = dp(6)))
        ctrl.addView(btn("Release") { releaseEngine() }, lp(w = 0, h = dp(44), weight = 1f))
        col.addView(ctrl, lp(top = dp(12)))

        // 输入区
        col.addView(section("INPUT"));
        inputBefore = field("上文", "萧炎推开房门，看见药老正坐在桌边。")
        inputCurrent = field("当前文本", "“你终于回来了。”药老抬起头，声音有些疲惫。")
        inputAfter = field("下文", "萧炎怔了一下，没有立即回答。")
        inputRoles = field("Known Roles（逗号分隔）", "萧炎, 药老")
        col.addView(inputBefore);
        col.addView(inputCurrent);
        col.addView(inputAfter);
        col.addView(inputRoles);

        // 查看最终 prompt
        col.addView(btn("查看最终 Prompt（可复制）") { showPrompt() }, lp(h = dp(44), top = dp(8)))

        // Generate / Cancel
        val gen = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        gen.addView(btn("GENERATE") { generate() }, lp(w = 0, h = dp(52), weight = 2f, right = dp(8)));
        gen.addView(btn("CANCEL") { engine.cancel() }, lp(w = 0, h = dp(52), weight = 1f));
        col.addView(gen, lp(top = dp(12)))

        // Raw output
        col.addView(section("RAW OUTPUT"));
        rawOutput = TextView(this).apply { setTextSp(12f); setTextColor(Color.rgb(30, 41, 59)); typeface = Typeface.MONOSPACE }
        col.addView(rawOutput, lp(top = dp(4)))

        // Parsed output
        col.addView(section("PARSED RESULT"));
        parsedOutput = TextView(this).apply { setTextSp(14f); setTextColor(Color.rgb(15, 23, 42)) }
        col.addView(parsedOutput, lp(top = dp(4)))

        // Debug
        col.addView(section("DEBUG"));
        debugOutput = TextView(this).apply { setTextSp(12f); setTextColor(Color.rgb(71, 85, 105)); typeface = Typeface.MONOSPACE }
        col.addView(debugOutput, lp(top = dp(4), bottom = dp(40)))

        root.addView(col)
        setContentView(root)
        refreshStatus()
    }

    private fun loadModel() {
        Thread {
            val ok = engine.load()
            loaded = ok
            runOnUiThread { refreshStatus(); toast(if (ok) "模型加载成功" else "模型加载失败") }
        }.start()
    }

    private fun resetEngine() {
        engine.reset();
        toast("已 reset");
    }

    private fun releaseEngine() {
        engine.release();
        loaded = false;
        refreshStatus();
        toast("已 release");
    }

    private fun generate() {
        // Gate 模式：--es readerdirector.rawprompt <files/ 下的文件名> 时，原样送 prompt。
        val rawName = intent?.getStringExtra(EXTRA_RAW_PROMPT)
        val maxTokens = if (intent?.hasExtra(EXTRA_MAX_TOKENS) == true)
            intent.getIntExtra(EXTRA_MAX_TOKENS, 0) else 128
        if (!rawName.isNullOrBlank()) {
            val f = File(filesDir, rawName)
            if (!f.exists()) { toast("raw prompt 不存在: $rawName"); return }
            rawOutput.text = "生成中…（raw prompt $rawName, maxTokens=$maxTokens）"
            parsedOutput.text = ""
            Thread {
                val t0 = System.currentTimeMillis()
                val analysis = engine.analyzeRawPrompt(f.readText(), maxTokens)
                val genMs = System.currentTimeMillis() - t0
                lastPrompt = analysis.prompt
                runOnUiThread { renderAnalysis(analysis, genMs) }
            }.start()
            return
        }
        val input = DirectorInput(
            contextBefore = inputBefore.text.toString().trim(),
            current = inputCurrent.text.toString().trim(),
            contextAfter = inputAfter.text.toString().trim(),
            knownRoles = inputRoles.text.toString().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
        );
        if (!input.hasContent()) { toast("请先输入当前文本"); return }
        rawOutput.text = "生成中…";
        parsedOutput.text = "";
        Thread {
            val t0 = System.currentTimeMillis()
            // P8 之后: fused GDR 把 32 token 从 165 s 打到 ~10 s（16.5×），
            // 128 token 预算从 ~7.5 分钟降到 ~1 分钟 ⇒ 恢复完整预算，
            // 否则 ReaderDirector JSON 生成不完整（parse FAIL）。
            val analysis = engine.analyze(input, 128)
            val genMs = System.currentTimeMillis() - t0
            lastPrompt = analysis.prompt
            runOnUiThread { renderAnalysis(analysis, genMs) }
        }.start()
    }

    private fun renderAnalysis(a: ReaderDirectorEngine.Analysis, genMs: Long) {
        rawOutput.text = if (a.raw.isBlank()) "（空输出）" else a.raw;
        val metrics = engine.getMetrics();
        when (val p = a.parsed) {
            is ReaderDirectorSchema.ParseOutcome.Success -> {
                val r = p.result;
                parsedOutput.text = listOf(
                    "Speaker       ${r.speaker ?: "（叙述/null）"}",
                    "Type          ${r.type}",
                    "Text          ${r.text}",
                    "Emotion       ${r.emotion}",
                    "Intensity     ${r.emotionIntensity}",
                    "Delivery      ${r.delivery.pace} / ${r.delivery.volume} / ${r.delivery.tone}",
                    "Voice Event   ${r.voiceEvent}",
                ).joinToString("\n");
                debugOutput.text = "Backend   ${engine.getBackend()}\nGenerate  ${genMs} ms\nParse     PASS\n$metrics";
            }
            is ReaderDirectorSchema.ParseOutcome.Failure -> {
                parsedOutput.text = "Parse: FAIL\nReason: ${p.reason}";
                debugOutput.text = "Backend   ${engine.getBackend()}\nGenerate  ${genMs} ms\nParse     FAIL\nReason    ${p.reason}";
            }
        }
        refreshStatus();
    }

    private fun showPrompt() {
        val input = DirectorInput(
            contextBefore = inputBefore.text.toString().trim(),
            current = inputCurrent.text.toString().trim(),
            contextAfter = inputAfter.text.toString().trim(),
            knownRoles = inputRoles.text.toString().split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
        );
        lastPrompt = DirectorPrompt.build(input);
        copyToClipboard(lastPrompt);
        toast("最终 Prompt 已复制到剪贴板");
    }

    private fun refreshStatus() {
        val desc = when (val s = selection) {
            is QwenBackendSelection.Selected ->
                "backend=${s.backend.nativeName} mode=${s.mode} experimental=${s.experimental} (${s.reason})"
            is QwenBackendSelection.Rejected -> "REJECTED (${s.reason}) -> cpu"
        }
        statusBar.text = "Model: ${if (loaded) "Loaded ✓" else "Not Loaded"}   " +
                "Backend(requested): ${engine.getBackend()}   " +
                "HTP Correctness: ${if (QwenBackendPolicy.HEXAGON_CORRECTNESS_PASSED) "PASSED" else "NOT PASSED"}\n" +
                "Switch: $desc";
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;
        cm.setPrimaryClip(ClipData.newPlainText("prompt", text));
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

    private fun section(title: String): TextView = TextView(this).apply {
        text = title; setTextSp(14f); setTextColor(Color.rgb(29, 78, 216)); setTypeface(typeface, Typeface.BOLD);
        setPadding(0, dp(16), 0, dp(4));
    }

    private fun field(label: String, initial: String): EditText = EditText(this).apply {
        hint = label; setText(initial); setTextSp(14f); setSingleLine(false);
        minLines = 1; maxLines = 4;
        setBackgroundColor(Color.WHITE); setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    private fun btn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        setText(text); setTextSp(14f); setTextColor(Color.WHITE);
        background = roundedSurface(Color.rgb(29, 78, 216));
        setOnClickListener { onClick() };
    }

    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
                   weight: Float = 0f, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            setMargins(left, top, right, bottom);
        }

    override fun onDestroy() {
        super.onDestroy();
        engine.release();
    }
}
