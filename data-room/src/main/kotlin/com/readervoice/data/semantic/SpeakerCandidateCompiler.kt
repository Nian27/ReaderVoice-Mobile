package com.readervoice.data.semantic

/**
 * TASK-095 Speaker Candidate Compiler v1（095A）：
 * 候选 = CUE ∪ RECENT_MENTION ∪ SCENE_ACTIVE（union + dedup + gate filter），带 provenance。
 *
 * 架构原则（PLAN-20260816-095）：
 * - Candidate Discovery 独立于 Speaker Cue（095B 才完全脱钩；095A 先落地 RECENT_MENTION source）
 * - 不要求模型弥补 Candidate Compiler 对真实角色的系统性漏检
 * - 候选 provenance 可审计：正确角色究竟是谁召回的
 *
 * 095A 范围：CUE（本段 assign 确认说话人，宽松 gate 过滤，不依赖 entitySet——缓解
 * rule_speaker 自举缺陷）、SCENE_ACTIVE（窗口活跃说话人）、RECENT_MENTION（recent_context
 * 中出现的已知实体）。NEW_MENTION / USER_LOCKED 在 095C/后续加入。
 */
enum class CandidateSourceType { CUE, CURRENT_MENTION, RECENT_MENTION, SCENE_ACTIVE, NEW_MENTION, USER_LOCKED }

data class SpeakerCandidate(
    val surface: String,
    val sources: Set<CandidateSourceType>,
    val score: Float,
)

class SpeakerCandidateCompiler(
    private val entitySet: Set<String>,
    private val maxCandidates: Int = 12,
    private val sceneWindow: Int = 6,
) {

    companion object {
        /** 宽松形态 gate：几乎不可能出现在角色名中的字符（095B 起由 Mention Discovery 取代）。 */
        const val GATE_CHARS =
            "的地得道说问答喊叫想看听忍住禁释追恭该当即续板街反没还就都也又再才刚不对从在了着过被把只"
        private val CJK = Regex("^[\\u4e00-\\u9fa5]{2,4}$")
    }

    private fun gateOk(name: String): Boolean =
        CJK.matches(name) && name.none { it in GATE_CHARS }

    /**
     * recent_context 中出现的已知实体（RECENT_MENTION）：
     * 段落文本按标点切块，2-4 字 CJK 块直接候选；长块滑动取 2/3/4 字片段；片段 ∩ entitySet。
     */
    fun recentMentions(recentContext: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (text in recentContext) {
            for (blk in text.split(Regex("[，。！？；：、\\s“”\"《》]"))) {
                val b = blk.trim()
                if (CJK.matches(b)) {
                    if (b in entitySet) out.add(b)
                } else if (b.length > 4) {
                    for (i in 0 until b.length - 1) {
                        for (len in intArrayOf(2, 3, 4)) {
                            if (i + len <= b.length) {
                                val frag = b.substring(i, i + len)
                                if (frag in entitySet) out.add(frag)
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * compile：CUE ∪ SCENE_ACTIVE ∪ RECENT_MENTION，gate 过滤 + dedup，按 source 优先级排序。
     * @param cueSpeakers 本段 assign 确认的说话人（含可能的新名字，gate 通过即收——不依赖 entitySet）
     * @param recentSpeakers 窗口活跃说话人（近→远）
     * @param recentContext 前文段落文本（RECENT_MENTION 提取源）
     */
    fun compile(
        cueSpeakers: List<String>,
        recentSpeakers: List<String>,
        recentContext: List<String>,
    ): List<SpeakerCandidate> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<SpeakerCandidate>()
        fun add(surface: String, type: CandidateSourceType, score: Float) {
            val s = surface.trim()
            if (s.isEmpty() || s == "UNKNOWN" || !gateOk(s)) return
            // ★ CH-0 ①：封闭类否决（叹词/虚词/动作短语/介词与指示词起首）——
            //   与语料无关，因此即使污染已进实体集（旧缓存）也不得进候选。
            //   真机实测：discovery 曾把"哈哈""招呼"当角色名 ⇒ 20 段里 9 段说话人不是人。
            if (NameLegitimacy.closedClassReject(s) != null) return
            // ★ M3：delivery cue（"饶有兴致"/"冷冷地"/"沉声"）不是人物候选 —— 它们走 evidence
            if (DeliveryCueLexicon.isDeliveryCue(s, entitySet)) return
            // ★ M3：群体称呼不是单人人名候选（"众人/二人/几个修士"）
            if (NameEvidence.isGroupName(s)) return
            if (!seen.add(s)) return
            out.add(SpeakerCandidate(s, setOf(type), score))
        }
        // CUE：本段说话人最高优先（score 1.0）
        for (c in cueSpeakers) add(c, CandidateSourceType.CUE, 1.0f)
        // RECENT_MENTION：上下文提及的已知实体（score 0.9——recall 优先，先于 SCENE 靠后位）
        for (m in recentMentions(recentContext)) add(m, CandidateSourceType.RECENT_MENTION, 0.9f)
        // SCENE_ACTIVE：窗口活跃说话人（score 0.8，近→远，限 sceneWindow）
        for (sp in recentSpeakers.take(sceneWindow)) add(sp, CandidateSourceType.SCENE_ACTIVE, 0.8f)
        return out.take(maxCandidates)
    }

    /**
     * M3：从 cue 说话人列表里挑出**表演提示**（不进候选，转 evidence）。
     * 顺序稳定（首次出现），供确定性 E# 编号。
     */
    fun deliveryCuesOf(cueSpeakers: List<String>): List<DeliveryCue> =
        cueSpeakers.mapNotNull { DeliveryCueLexicon.classify(it.trim(), entitySet) }.distinctBy { it.text }
}
