package com.readervoice.app

/**
 * ReaderDirector 的输入模型与最终 Prompt 构建。
 * 关键：build() 返回的字符串就是真正送进 QwenEngine.generate() 的内容，
 * 必须原样展示、可复制，便于 PC/手机 A/B 对照。
 */
data class DirectorInput(
    val contextBefore: String = "",
    val current: String = "",
    val contextAfter: String = "",
    val knownRoles: List<String> = emptyList(),
) {
    fun hasContent(): Boolean = current.isNotBlank() || contextBefore.isNotBlank()
}

object DirectorPrompt {

    private val SYSTEM = listOf(
        "你是小说朗读系统的 ReaderDirector。",
        "分析当前文本，识别说话人、文本类型、情绪、表达方式和声音事件。",
        "只输出一个 JSON 对象，不要输出任何解释、前言或 Markdown 代码块标记。",
    )

    private val SCHEMA = listOf(
        "输出 JSON 字段（全部必填）：",
        "- speaker: 谁说的；叙述文本或无法判断时用 null",
        "- type: narration 或 dialogue",
        "- text: 真正需要朗读的文本（对话去掉引号，叙述保留原文）",
        "- emotion: 情绪类别（如 tired/sad/angry/calm/joyful/neutral 等，无则 neutral）",
        "- emotion_intensity: 情绪强度 0.0 到 1.0",
        "- delivery.pace: slow/normal/fast",
        "- delivery.volume: quiet/normal/loud",
        "- delivery.tone: 语气描述（如 weary/gentle/cold，无则 neutral）",
        "- voice_event: 特殊声音状态变化（如 whisper/cry/laugh/shout/normal，无则 normal）",
    )

    /** 组装最终送进模型的完整 Prompt 文本。 */
    fun build(input: DirectorInput): String {
        val sb = StringBuilder()
        sb.append(SYSTEM.joinToString("\n")).append("\n\n");
        sb.append("<context_before>\n").append(input.contextBefore.ifBlank { "（空）" }).append("\n</context_before>\n\n");
        sb.append("<current>\n").append(input.current).append("\n</current>\n\n");
        sb.append("<context_after>\n").append(input.contextAfter.ifBlank { "（空）" }).append("\n</context_after>\n\n");
        sb.append("<known_roles>\n");
        if (input.knownRoles.isEmpty()) {
            sb.append("（无）");
        } else {
            sb.append(input.knownRoles.joinToString("\n"));
        }
        sb.append("\n</known_roles>\n\n");
        sb.append(SCHEMA.joinToString("\n"));
        return sb.toString();
    }
}
