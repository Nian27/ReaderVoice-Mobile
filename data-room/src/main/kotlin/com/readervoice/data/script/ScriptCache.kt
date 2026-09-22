package com.readervoice.data.script

import java.security.MessageDigest

/**
 * R2（ADR-056 / PLAN-20260918-062）：**剧本缓存的命中判据**。
 *
 * ## 产品语义
 *
 * 剧本是**这本书的数据**，不是一次运行的产物。用户读到第 126 章第 37 段时，系统只问一件事：
 * > "这一章的剧本还算不算数？" —— 算就**一次模型都不调**，不算才补算。
 *
 * ## 判据（全部满足才算命中）
 *
 * ```
 * ① 剧本存在          —— current.jsonl 有行，且 state.json 可解析
 * ② sourceSpanHash 相同 —— 本章正文切片（原文 + 章节区间）没变
 * ③ protocolVersion 相同 —— DIRECTOR_SELECTION_V1 这类协议没升级
 * ④ characterRevision 兼容 —— 角色集（实体集 + 门版本）没变
 * ⑤ 已备到足够靠后     —— preparedUntil >= 本次需要的段（阅读驱动预取用）
 * ```
 *
 * ## 什么时候必须重算（失效语义，逐条可测）
 *
 * ```
 * SOURCE_CHANGED        正文变化 / 换章节规则导致区间变化
 * PROTOCOL_CHANGED      Director 协议升级（旧决策不再可解释）
 * CHARACTERS_CHANGED    角色发现结果变化（候选集变了 ⇒ 旧说话人可能不再成立）
 * USER_CHARACTER_FIX    用户手工修正角色（USER_LOCKED，最高优先级）
 * IDENTITY_MERGE        身份合并（两个角色并成一个）
 * MODEL_UPGRADE         Director 模型升级且要求重算
 * ```
 * 前四类由判据**自动**推出；后三类由调用方显式传 [ScriptRequirement.forceRecompute] 的 [InvalidationReason]。
 */
object ScriptCache {

    /** 缓存判据输入（由调用方按当前状态算出）。 */
    data class ScriptRequirement(
        /** 本章正文切片（章节区间内的原文）的稳定哈希。 */
        val sourceSpanHash: String,
        /** Director 协议版本（如 `DIRECTOR_SELECTION_V1`）。 */
        val protocolVersion: String,
        /** 角色集版本（实体集 + 门版本的哈希）。 */
        val characterRevision: String,
        /** 本次**需要**准备到的段序号（含）。默认 0 = 只要"有剧本"即可。 */
        val needUntil: Int = 0,
        /** 显式失效原因（用户修正/身份合并/模型升级）；非空即强制重算。 */
        val forceRecompute: List<InvalidationReason> = emptyList(),
    )

    enum class InvalidationReason {
        NO_SCRIPT,
        SOURCE_CHANGED,
        PROTOCOL_CHANGED,
        CHARACTERS_CHANGED,
        USER_CHARACTER_FIX,
        IDENTITY_MERGE,
        MODEL_UPGRADE,
        NOT_PREPARED_YET,
    }

    sealed interface Verdict {
        /** 可以完全跳过模型调用；[preparedUntil] = 已可用到的段序号（含）。 */
        data class Hit(val preparedUntil: Int) : Verdict

        /** 需要（重新）分析；[reasons] 可审计。 */
        data class Miss(val reasons: List<InvalidationReason>) : Verdict

        /** 已有剧本，但还没备到本次需要的位置 ⇒ **续算**（不丢弃已有行）。 */
        data class Extend(val preparedUntil: Int) : Verdict
    }

    /**
     * @param state 已有的章级状态（null = 从没分析过）
     */
    fun evaluate(state: ScriptState?, req: ScriptRequirement): Verdict {
        if (req.forceRecompute.isNotEmpty()) {
            return Verdict.Miss(req.forceRecompute.distinct())
        }
        if (state == null || state.preparedUntil <= 0) return Verdict.Miss(listOf(InvalidationReason.NO_SCRIPT))

        val reasons = ArrayList<InvalidationReason>(3)
        if (state.sourceSpanHash != req.sourceSpanHash) reasons += InvalidationReason.SOURCE_CHANGED
        if (state.protocolVersion != req.protocolVersion) reasons += InvalidationReason.PROTOCOL_CHANGED
        if (state.characterRevision != req.characterRevision) reasons += InvalidationReason.CHARACTERS_CHANGED
        if (reasons.isNotEmpty()) return Verdict.Miss(reasons)

        return if (state.preparedUntil >= req.needUntil) {
            Verdict.Hit(state.preparedUntil)
        } else {
            Verdict.Extend(state.preparedUntil)
        }
    }

    // ── 哈希（确定性，JVM/Android 一致）────────────────────────────────

    /** SHA-256 前 16 个 hex 字符（足够区分，短到能进 JSON）。 */
    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (i in 0 until 8) append("%02x".format(d[i]))
        }
    }

    /**
     * 角色集版本：**排序后**的实体集 + 发现门版本。
     * 排序保证结果与发现顺序无关（只有集合变化才改版本）。
     */
    fun characterRevision(names: Collection<String>, gateVersion: Int): String =
        sha256Hex("v$gateVersion|" + names.sorted().joinToString("\u0001"))

    /** 章节正文切片哈希（区间 + 文本；区间变化也要重算）。 */
    fun sourceSpanHash(chapterId: Long, startLine: Int, endLine: Int, chapterText: String): String =
        sha256Hex("$chapterId|$startLine|$endLine|$chapterText")
}
