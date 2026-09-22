package com.readervoice.data.semantic

/**
 * MOBILE-005 / M3 步骤 2：**DIRECTOR_SELECTION_V1 协议渲染器**（两端共用）。
 *
 * 设计要点（ADR-055）：
 *   · 模型只看到 **局部 ID**：片段 S#、候选 C#、证据 E#；**内部 identityId 绝不进入 prompt**
 *   · 文本与决策分离：prompt 里给出原文，但**不要求模型复述文本**；生产文本永远取 canonical sourceSpan
 *   · UNKNOWN 是正常答案：`NARRATOR` / `C#` / `UNKNOWN` 三选一，并给出其语义定义
 *   · 空候选 + 对白 ⇒ **不调用模型**直接 UNKNOWN（fail-safe shortcut，由 runner 执行）
 *
 * 与 M0 探针的 prompt 同源（M0 已证：P1–P4 100%、3 次逐字一致），此处把它固化成契约渲染，
 * 避免各调用点各写一份而漂移。
 */
object DirectorSelectionPrompt {

    const val VERSION = "DIRECTOR_SELECTION_V1"

    /** target 在 prompt 中的片段编号（段内序号即可，模型易复述；系统按 segmentIndex 映射回）。 */
    fun segmentToken(seg: ContextSegment): String = "S${seg.segmentIndex}"

    /**
     * 片段类型的中文标签（**仅用于展示**，与输出枚举 DIALOGUE/NARRATION 字形不同，
     * 避免模型把展示标签当成要回填的字段值）。
     */
    private fun typeLabel(segmentType: String): String = when (segmentType.lowercase()) {
        "speech", "group_speech" -> "〔对白〕"
        "inner_monologue" -> "〔内心独白〕"
        "narration" -> "〔旁白〕"
        else -> "〔未知〕"
    }

    /**
     * **不调用模型**即可确定的片段（fail-safe shortcut，ADR-055 §3）：
     *   · 目标片段是〔旁白〕 ⇒ 说话人必然是 NARRATOR、type 必然是 NARRATION
     *     （旁白没有"谁在说话"这个问题；把候选摆给模型只会诱发"提到谁就选谁"的错误 —— 实测 15/24 硬失败）
     *   · 目标是对白但候选为空 ⇒ UNKNOWN（不猜名字）
     * @return 可直接采信的结论；null 表示必须调用模型
     */
    fun skipDecision(ctx: DirectorContext): String? {
        if (ctx.target.segmentType.lowercase() == "narration") return DirectorDecisionV2.NARRATOR
        if (ctx.candidateSpeakers.isEmpty()) return DirectorDecisionV2.UNKNOWN
        return null
    }

    /** 该片段是否走 shortcut（不调用模型）。 */
    fun isShortcut(ctx: DirectorContext): Boolean = skipDecision(ctx) != null

    fun render(ctx: DirectorContext): String {
        val sb = StringBuilder()
        sb.appendLine("你是小说朗读系统的 ReaderDirector。下面是一个已经切分好的段落，以及其中的语义片段。")
        sb.appendLine("你的任务：为【指定的那一个片段】选择说话人，并判断情绪与表达方式。")
        sb.appendLine()
        sb.appendLine("规则（必须遵守）：")
        sb.appendLine("1. 说话人只能从候选列表里选：C0、C1……，或者 UNKNOWN。禁止输出候选以外的名字。")
        sb.appendLine("2. evidence 只能填给定的证据编号（可以是空数组）。禁止自己编造证据文字。")
        sb.appendLine("3. 不要复述或改写原文；只输出决策字段。")
        sb.appendLine("4. 只输出一个 JSON 对象，不要解释、不要 Markdown 代码块。")
        sb.appendLine()
        sb.appendLine("说话人字段的三种取值：")
        sb.appendLine("- NARRATOR：确定是旁白（没有人物的说话行为）")
        sb.appendLine("- C0/C1/…：有足够证据判断为某个候选角色")
        sb.appendLine("- UNKNOWN：确定是角色对白，但现有证据不足以判断是谁")
        sb.appendLine()

        if (ctx.recentSegments.isNotEmpty()) {
            sb.appendLine("<上文>")
            ctx.recentSegments.forEach { sb.appendLine(it.text) }
            sb.appendLine("</上文>")
            sb.appendLine()
        }

        sb.appendLine("<本段片段>")
        // ★ 片段标签用【中文方括号】且与输出枚举值（DIALOGUE/NARRATION）在字形上完全不同：
        //   早期版本写成 "S0 [narration]"，模型会把标签直接回填进 type 字段 ⇒ 与 speaker 冲突（实测 96% 硬失败）。
        //   这是 prompt 表达问题，不是协议或模型能力问题。
        ctx.recentSegments.filter { it.position == ctx.target.position }.forEach { seg ->
            sb.appendLine("${segmentToken(seg)} ${typeLabel(seg.segmentType)} ${seg.text}")
        }
        sb.appendLine("${segmentToken(ctx.target)} ${typeLabel(ctx.target.segmentType)} ${ctx.target.text}")
        sb.appendLine("</本段片段>")
        sb.appendLine()

        sb.appendLine("<候选>")
        if (ctx.candidateSpeakers.isEmpty()) sb.appendLine("（无候选）")
        ctx.candidateSpeakers.forEach { c ->
            val alias = if (c.aliases.isNotEmpty()) "（别名 ${c.aliases.joinToString("、")}）" else ""
            val src = if (c.sources.isNotEmpty()) "，来源 ${c.sources.joinToString("/")}" else ""
            sb.appendLine("${c.localId} ${c.name}$alias（最近发言距离 ${c.recentTurnDistance}$src）")
        }
        sb.appendLine("</候选>")
        sb.appendLine()

        sb.appendLine("<证据>")
        if (ctx.evidenceItems.isEmpty()) sb.appendLine("（无证据）")
        ctx.evidenceItems.forEach { e ->
            val hint = if (e.emotionHint != null) "，情绪参考 ${e.emotionHint}" else ""
            sb.appendLine("${e.id} [${e.kind}] ${e.text}$hint")
        }
        sb.appendLine("</证据>")
        sb.appendLine()

        if (ctx.identityConstraints.isNotEmpty()) {
            sb.appendLine("<身份约束>")
            ctx.identityConstraints.forEach { sb.appendLine(it) }
            sb.appendLine("</身份约束>")
            sb.appendLine()
        }
        if (ctx.userLocks.isNotEmpty()) {
            sb.appendLine("<用户锁定（最高优先级，不得违背）>")
            ctx.userLocks.forEach { sb.appendLine(it) }
            sb.appendLine("</用户锁定>")
            sb.appendLine()
        }

        sb.appendLine("要分析的片段编号：${segmentToken(ctx.target)}")
        sb.appendLine("该片段的类型已由切分器确定：${typeLabel(ctx.target.segmentType)}")
        sb.appendLine("输出 JSON 字段（全部必填）：")
        sb.appendLine("- segment_id: 固定填 ${segmentToken(ctx.target)}")
        sb.appendLine("- speaker: C0/C1/… 或 NARRATOR 或 UNKNOWN")
        sb.appendLine(
            "- type: 必须与上面【已确定的片段类型】一致 —— " +
                "〔对白〕填 DIALOGUE，〔旁白〕填 NARRATION；只能填这两个大写英文值之一"
        )
        sb.appendLine("- emotion: 情绪类别（tired/sad/angry/calm/joyful/neutral 等，无则 neutral）")
        sb.appendLine("- emotion_intensity: 0.0 到 1.0")
        sb.appendLine("- delivery.pace: slow/normal/fast")
        sb.appendLine("- delivery.volume: quiet/normal/loud")
        sb.appendLine("- delivery.tone: 语气描述（如 weary/gentle/cold，无则 neutral）")
        sb.appendLine("- voice_event: whisper/cry/laugh/shout/sigh/normal 等（无则 normal）")
        sb.appendLine("- evidence: 证据编号数组（如 [\"E0\"]，可为 []）")
        return sb.toString()
    }
}

