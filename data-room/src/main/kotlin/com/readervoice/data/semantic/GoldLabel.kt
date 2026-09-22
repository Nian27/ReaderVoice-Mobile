package com.readervoice.data.semantic

/**
 * MOBILE-005 / M3 步骤 1：**gold 口径分型**（评价尺子必须先正）。
 *
 * 事故背景：`饶有兴致道：` → gold=饶有兴致 且候选也是它 ⇒ 模型答对、准确率 100%、产品结果荒谬。
 * 根因不是模型，而是**把规则层自己的输出当成了 gold**。
 *
 * 规矩（M3 起强制）：
 *   MANUAL_GOLD    人工确认的真实说话人 —— **唯一可进硬准确率 Gate**
 *   WEAK_GOLD      规则/数据自动产生（显式 cue、legacy v90.7、teacher）—— 只作弱标签
 *   RULE_BASELINE  RuleSpeaker 的预测 —— 只能算 agreement，**不得当 gold**
 *   NONE           无标注
 *
 * 报告必须分开写（禁止合并成一个"准确率"）：
 *   Candidate Recall @ MANUAL_GOLD / Director Accuracy @ MANUAL_GOLD /
 *   Agreement with Rule Baseline / UNKNOWN rate
 */
enum class GoldKind {
    MANUAL_GOLD,
    WEAK_GOLD,
    RULE_BASELINE,
    NONE,
}

data class GoldLabel(val kind: GoldKind, val speaker: String?, val source: String) {
    /** 是否可用于硬准确率 Gate。 */
    val hardGateUsable: Boolean get() = kind == GoldKind.MANUAL_GOLD

    override fun toString(): String = "${kind.name}(${speaker ?: "-"})[$source]"
}

object GoldPolicy {

    /** 规则层结果 → gold 型别（**规则永远是规则，不是 gold**）。 */
    fun fromRuleResult(r: RuleSpeakerBaseline.SpeakerResult): GoldLabel = when {
        r.speaker == null -> GoldLabel(GoldKind.NONE, null, r.provenance.firstOrNull() ?: "UNKNOWN")
        r.provenance.any { it in EXPLICIT_CUE_PROVENANCE } ->
            GoldLabel(GoldKind.WEAK_GOLD, r.speaker, r.provenance.first())
        r.provenance.any { it == "TURN_TRACKING" } ->
            GoldLabel(GoldKind.RULE_BASELINE, r.speaker, "TURN_TRACKING")
        else -> GoldLabel(GoldKind.WEAK_GOLD, r.speaker, r.provenance.firstOrNull() ?: "UNKNOWN")
    }

    /**
     * Dataset provenance（TASK-080 冻结产物）→ gold 型别。
     * 注意：**不改冻结数据集**，只在其上叠加型别解释；历史 `EXPLICIT_RULE_GOLD` 名字里有 GOLD，
     * 但按本口径它是 WEAK_GOLD。
     */
    fun fromDatasetProvenance(provenance: String, speaker: String?): GoldLabel = when (provenance) {
        "HUMAN_GOLD" -> GoldLabel(GoldKind.MANUAL_GOLD, speaker, provenance)
        "EXPLICIT_RULE_GOLD" -> GoldLabel(GoldKind.WEAK_GOLD, speaker, provenance)
        "LEGACY_V907", "LEGACY_V907_HARNESS", "TEACHER" -> GoldLabel(GoldKind.WEAK_GOLD, speaker, provenance)
        "UNKNOWN" -> GoldLabel(GoldKind.NONE, speaker, provenance)
        else -> GoldLabel(GoldKind.NONE, speaker, provenance)
    }

    /** 硬 Gate 守卫：非 MANUAL_GOLD 不允许进入准确率 Gate（调用方必须显式降级为"弱指标"）。 */
    fun requireHardGate(label: GoldLabel, metric: String) {
        check(label.hardGateUsable) {
            "GOLD_GATE_VIOLATION: $metric 需要 MANUAL_GOLD，实际 ${label.kind}（source=${label.source}）。" +
                "规则/弱标签只能报 agreement 或 weak metric。"
        }
    }

    val EXPLICIT_CUE_PROVENANCE = setOf(
        "EXPLICIT_SPEECH_CUE", "POSTPOSED_SPEECH_CUE", "CROSS_PARAGRAPH_CUE",
    )
}

