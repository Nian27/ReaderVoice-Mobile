package com.readervoice.app

/**
 * ReaderDirector 语义适配层：DirectorInput → 最终 Prompt → QwenEngine → 结构化结果。
 * 返回三层输出（input prompt / raw output / parsed result），全部透明可审计。
 */
class ReaderDirectorEngine(
    private val engine: QwenEngine,
    private val modelDir: String,
    private val backend: String = "cpu",
) {

    @Volatile
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        loaded = engine.load(modelDir, backend)
        return loaded
    }

    fun cancel() = engine.cancel()

    /** 看门狗需要直接驱动底层引擎（deadline 生成 / 协作式取消）。 */
    fun engineForWatchdog(): QwenEngine = engine
    fun reset() = engine.reset()
    fun getBackend(): String = engine.getBackend()
    fun getMetrics(): String = engine.getMetrics()
    fun release() = engine.release()

    /**
     * Gate 用：把给定文本【原样】作为 prompt 送进引擎（不再套 Director 模板）。
     * 用途：App↔shell 同输入逐字节对拍 —— 两边喂同一个 prompt 文件内容，
     * 排除 template/tokenizer 包装差异导致的误判。
     */
    fun analyzeRawPrompt(prompt: String, maxTokens: Int = 0): Analysis {
        if (!load()) {
            return Analysis(prompt, "", ReaderDirectorSchema.ParseOutcome.Failure("模型未加载", ""))
        }
        val raw = engine.generate(prompt, maxTokens)
        if (raw == "CANCELED") {
            return Analysis(prompt, "", ReaderDirectorSchema.ParseOutcome.Failure("生成被取消", raw))
        }
        return Analysis(prompt, raw, ReaderDirectorSchema.parse(raw))
    }

    /** 一次完整分析，返回三层输出。 */
    fun analyze(input: DirectorInput, maxTokens: Int = 128): Analysis {
        if (!load()) {
            return Analysis(DirectorPrompt.build(input), "", ReaderDirectorSchema.ParseOutcome.Failure("模型未加载", ""))
        }
        val prompt = DirectorPrompt.build(input)
        val raw = engine.generate(prompt, maxTokens)
        if (raw == "CANCELED") {
            return Analysis(prompt, "", ReaderDirectorSchema.ParseOutcome.Failure("生成被取消", raw))
        }
        val parsed = ReaderDirectorSchema.parse(raw)
        return Analysis(prompt, raw, parsed)
    }

    /**
     * 一次完整分析并直接产出 RenderUnit v1 候选。
     * fail-closed：解析失败或校验出现违规时，候选一律不作为可提交结果返回。
     */
    fun analyzeToRenderUnit(
        input: DirectorInput,
        unitId: String,
        sourceText: String,
        profile: VoiceProfileRef,
        sampleRate: Int = RenderUnitBuilder.DEFAULT_SAMPLE_RATE,
        speed: Float = 1.0f,
        maxTokens: Int = 128,
    ): RenderUnitOutcome {
        val a = analyze(input, maxTokens)
        when (val p = a.parsed) {
            is ReaderDirectorSchema.ParseOutcome.Failure ->
                return RenderUnitOutcome.Failed(p.reason, a.prompt, a.raw, emptyList())
            is ReaderDirectorSchema.ParseOutcome.Success -> {
                val candidate = RenderUnitBuilder.build(
                    RenderUnitBuilder.BuildRequest(
                        unitId = unitId,
                        sourceText = sourceText,
                        director = p.result,
                        profile = profile,
                        sampleRate = sampleRate,
                        speed = speed,
                    )
                )
                val violations = RenderUnitValidator.validate(candidate)
                if (violations.isNotEmpty()) {
                    return RenderUnitOutcome.Failed(
                        "RenderUnit 校验未通过: " + violations.joinToString { it.rule + ":" + it.detail },
                        a.prompt, a.raw, violations,
                    )
                }
                return RenderUnitOutcome.Built(candidate, a.prompt, a.raw)
            }
        }
    }

    /** 三层输出：最终送进模型的 prompt / 模型原始输出 / 解析结果。 */
    data class Analysis(
        val prompt: String,
        val raw: String,
        val parsed: ReaderDirectorSchema.ParseOutcome,
    )

    /** analyzeToRenderUnit 的结果；Failed 携带 prompt/raw 供审计，绝不静默。 */
    sealed interface RenderUnitOutcome {
        data class Built(
            val candidate: RenderUnitBuilder.Candidate,
            val prompt: String,
            val raw: String,
        ) : RenderUnitOutcome

        data class Failed(
            val reason: String,
            val prompt: String,
            val raw: String,
            val violations: List<RenderUnitValidator.Violation>,
        ) : RenderUnitOutcome
    }
}

