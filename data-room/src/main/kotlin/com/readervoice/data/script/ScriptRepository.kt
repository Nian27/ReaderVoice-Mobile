package com.readervoice.data.script

import com.readervoice.data.semantic.ScriptLine
import com.readervoice.data.semantic.ScriptLineCodec
import java.io.File
import org.json.JSONObject

/**
 * CH-0 / P0-B：**剧本持久化（JSONL 为正文 + state.json 为检查点）**。
 *
 * 为什么不能只"写几个文件"：文件存在 ≠ 产品已保存。用户关掉页面再进来必须能看见，
 * 进程被杀必须能从最近检查点续跑 —— 所以需要**恢复路径**与**稳定键**。
 *
 * 目录结构（用户定案）：
 * ```
 * <booksRoot>/<bookId>/scripts/<chapterId>/
 *     current.jsonl            ← 每个 ACCEPT 行即时 append（崩溃也只丢当前段）
 *     revisions/<revisionId>.jsonl
 *     state.json               ← 检查点：nextSegmentIndex / 计数 / 状态
 * ```
 *
 * 稳定键：**bookId + chapterId + sourceRevisionId**
 *   · `chapterId` 用 CH-0/M0.4 的内容派生 id（**不是** ordinal —— 章节集合会变）
 *   · `sourceRevisionId` 变（原文/结构变化）⇒ 视为新一轮，不覆盖旧结果
 */
data class ScriptState(
    val revisionId: String,
    val bookId: String,
    val chapterId: Long,
    val sourceRevisionId: String,
    /** RUNNING | COMPLETED | CANCELED | FAILED（**段级 REJECT 不影响这里**） */
    val status: String,
    /** 已提交到的下一个段序号（恢复起点）。 */
    val nextSegmentIndex: Int,
    val totalSegments: Int,
    val accepted: Int,
    val verify: Int,
    val rejected: Int,
    val startedAt: Long,
    val updatedAt: Long,
    // ── R2：Book 持久数据的缓存键（旧 state.json 缺这些字段 ⇒ 视为未命中，自动重算）──────
    /** 章标题（目录/剧本页显示用；R1 起 canonical 标题）。 */
    val chapterTitle: String = "",
    /** 章序号（canonical ordinal；仅展示用，持久化主键是 chapterId）。 */
    val chapterOrdinal: Int = -1,
    /** 本章正文切片哈希（正文/章节区间变化 ⇒ 重算）。 */
    val sourceSpanHash: String = "",
    /** Director 协议版本（升级 ⇒ 重算）。 */
    val protocolVersion: String = "",
    /** 角色集版本（角色发现结果变化 ⇒ 重算）。 */
    val characterRevision: String = "",
    /**
     * **已备到的段序号（含）** —— 阅读驱动预取的核心字段。
     * `preparedUntil = 54` 表示 S0..S54 都有可用剧本；读到 S50 时后台补 S55 起。
     */
    val preparedUntil: Int = -1,
) {
    val lineCount: Int get() = accepted + verify

    fun toJson(): String = JSONObject()
        .put("revisionId", revisionId).put("bookId", bookId).put("chapterId", chapterId)
        .put("sourceRevisionId", sourceRevisionId).put("status", status)
        .put("nextSegmentIndex", nextSegmentIndex).put("totalSegments", totalSegments)
        .put("accepted", accepted).put("verify", verify).put("rejected", rejected)
        .put("startedAt", startedAt).put("updatedAt", updatedAt)
        .put("chapterTitle", chapterTitle).put("chapterOrdinal", chapterOrdinal)
        .put("sourceSpanHash", sourceSpanHash).put("protocolVersion", protocolVersion)
        .put("characterRevision", characterRevision).put("preparedUntil", preparedUntil)
        .toString()

    companion object {
        const val RUNNING = "RUNNING"
        const val COMPLETED = "COMPLETED"
        const val CANCELED = "CANCELED"
        const val FAILED = "FAILED"

        fun parse(json: String): ScriptState? = runCatching {
            val o = JSONObject(json)
            ScriptState(
                revisionId = o.getString("revisionId"),
                bookId = o.getString("bookId"),
                chapterId = o.getLong("chapterId"),
                sourceRevisionId = o.optString("sourceRevisionId", ""),
                status = o.optString("status", RUNNING),
                nextSegmentIndex = o.optInt("nextSegmentIndex", 0),
                totalSegments = o.optInt("totalSegments", 0),
                accepted = o.optInt("accepted", 0),
                verify = o.optInt("verify", 0),
                rejected = o.optInt("rejected", 0),
                startedAt = o.optLong("startedAt", 0),
                updatedAt = o.optLong("updatedAt", 0),
                chapterTitle = o.optString("chapterTitle", ""),
                chapterOrdinal = o.optInt("chapterOrdinal", -1),
                sourceSpanHash = o.optString("sourceSpanHash", ""),
                protocolVersion = o.optString("protocolVersion", ""),
                characterRevision = o.optString("characterRevision", ""),
                // 旧 state.json 没有这一项 ⇒ -1（= 没有任何已备段）⇒ ScriptCache 判 NO_SCRIPT
                preparedUntil = o.optInt("preparedUntil", -1),
            )
        }.getOrNull()
    }
}

class ScriptRepository(private val booksRoot: File) {

    private fun chapterDir(bookId: String, chapterId: Long): File =
        File(File(File(booksRoot, bookId), "scripts"), chapterId.toString())

    private fun stateFile(bookId: String, chapterId: Long) = File(chapterDir(bookId, chapterId), "state.json")
    private fun currentFile(bookId: String, chapterId: Long) = File(chapterDir(bookId, chapterId), "current.jsonl")
    private fun revisionFile(bookId: String, chapterId: Long, revisionId: String) =
        File(File(chapterDir(bookId, chapterId), "revisions"), "$revisionId.jsonl")

    fun loadState(bookId: String, chapterId: Long): ScriptState? {
        val f = stateFile(bookId, chapterId)
        if (!f.isFile) return null
        return ScriptState.parse(f.readText())
    }

    /** 恢复路径：读回已落盘的行（UI 打开章节时调用；不是"文件存在"，而是内容可用）。 */
    fun loadLines(bookId: String, chapterId: Long): List<String> {
        val f = currentFile(bookId, chapterId)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }
    }

    fun list(bookId: String): List<ScriptState> {
        val dir = File(File(booksRoot, bookId), "scripts")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }?.mapNotNull { sub ->
            sub.name.toLongOrNull()?.let { loadState(bookId, it) }
        }?.sortedBy { it.chapterId }.orEmpty()
    }

    /**
     * 开始或**续跑**：
     *   · 已有同 `sourceRevisionId` 且 `status == RUNNING` 的状态 ⇒ 复用（从 nextSegmentIndex 继续）
     *   · 否则新建 revision（**不覆盖**旧 revisions 文件）
     *
     * R2：新增 [req]（缓存判据）。传了 req 时按 `ScriptCache` 判定：
     *   · HIT ⇒ [ScriptRun.cacheHit] = true，调用方应**一次模型都不调**
     *   · EXTEND ⇒ 复用已备行，从 `preparedUntil + 1` 续算
     *   · MISS ⇒ 新建 revision（旧轮保留在 revisions/ 里）
     */
    fun beginOrResume(
        bookId: String,
        chapterId: Long,
        sourceRevisionId: String,
        totalSegments: Int,
        req: ScriptCache.ScriptRequirement? = null,
        chapterTitle: String = "",
        chapterOrdinal: Int = -1,
    ): ScriptRun {
        val existing = loadState(bookId, chapterId)
        val verdict = req?.let { ScriptCache.evaluate(existing, it) }

        if (existing != null && verdict is ScriptCache.Verdict.Hit) {
            return ScriptRun(this, existing.copy(updatedAt = System.currentTimeMillis()), resumed = true)
                .also { it.cacheHit = true }
        }
        if (existing != null && verdict is ScriptCache.Verdict.Extend) {
            return ScriptRun(this, existing.copy(updatedAt = System.currentTimeMillis()), resumed = true)
        }
        // 旧路径兼容：未传 req（如调试调用）时保持原有 RUNNING 续跑语义
        if (req == null && existing != null && existing.status == ScriptState.RUNNING &&
            existing.sourceRevisionId == sourceRevisionId && existing.nextSegmentIndex > 0
        ) {
            return ScriptRun(this, existing.copy(updatedAt = System.currentTimeMillis()), resumed = true)
        }

        val now = System.currentTimeMillis()
        val revisionId = "scr_${bookId}_${chapterId}_$now"
        val fresh = ScriptState(
            revisionId = revisionId,
            bookId = bookId,
            chapterId = chapterId,
            sourceRevisionId = sourceRevisionId,
            status = ScriptState.RUNNING,
            nextSegmentIndex = 0,
            totalSegments = totalSegments,
            accepted = 0, verify = 0, rejected = 0,
            startedAt = now, updatedAt = now,
            chapterTitle = chapterTitle,
            chapterOrdinal = chapterOrdinal,
            sourceSpanHash = req?.sourceSpanHash ?: "",
            protocolVersion = req?.protocolVersion ?: "",
            characterRevision = req?.characterRevision ?: "",
            preparedUntil = -1,
        )
        val dir = chapterDir(bookId, chapterId).apply { mkdirs() }
        File(dir, "revisions").mkdirs()
        currentFile(bookId, chapterId).writeText("")          // 新一轮正文（旧轮在 revisions/ 里）
        revisionFile(bookId, chapterId, revisionId).writeText("")
        writeState(fresh)
        return ScriptRun(this, fresh, resumed = false)
    }

    internal fun appendRaw(state: ScriptState, jsonLine: String) {
        currentFile(state.bookId, state.chapterId).appendText(jsonLine + "\n")
        revisionFile(state.bookId, state.chapterId, state.revisionId).appendText(jsonLine + "\n")
    }

    internal fun writeState(state: ScriptState) {
        val f = stateFile(state.bookId, state.chapterId)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "state.json.tmp")
        tmp.writeText(state.toJson())          // 原子替换：避免半写状态
        if (!tmp.renameTo(f)) {
            f.writeText(state.toJson())
            tmp.delete()
        }
    }
}

/** 一轮运行的句柄：**每个 ACCEPT 行即时 append，检查点显式 commit**。 */
class ScriptRun internal constructor(
    private val repo: ScriptRepository,
    var state: ScriptState,
    val resumed: Boolean,
) {
    /**
     * R2：本次是否为**缓存命中**（剧本齐备且判据全过）。
     * 命中时调用方应当**一次模型都不调**，直接使用已落盘的剧本。
     */
    var cacheHit: Boolean = false

    /** 追加一行（崩溃最多丢当前段，已 append 的不丢）。 */
    fun append(line: ScriptLine): ScriptState {
        repo.appendRaw(state, ScriptLineCodec.encode(line))
        state = state.copy(
            accepted = state.accepted + if (line.outcome.name == "ACCEPTED_ALL") 1 else 0,
            verify = state.verify + if (line.outcome.name == "VERIFY") 1 else 0,
            updatedAt = System.currentTimeMillis(),
        )
        return state
    }

    fun countRejected() {
        state = state.copy(rejected = state.rejected + 1, updatedAt = System.currentTimeMillis())
    }

    /** ★ 提交点：把 nextSegmentIndex 推进到 [nextSegmentIndex] 并落盘。 */
    fun commit(nextSegmentIndex: Int): ScriptState {
        state = state.copy(
            nextSegmentIndex = maxOf(state.nextSegmentIndex, nextSegmentIndex),
            updatedAt = System.currentTimeMillis(),
        )
        repo.writeState(state)
        return state
    }

    /**
     * R2：把「已备到哪个段」写进这本书的数据（阅读驱动预取的依据）。
     * 与 [commit] 分开：`nextSegmentIndex` 是**恢复起点**，`preparedUntil` 是**可用边界**。
     */
    fun markPreparedUntil(segmentOrdinal: Int): ScriptState {
        state = state.copy(
            preparedUntil = maxOf(state.preparedUntil, segmentOrdinal),
            updatedAt = System.currentTimeMillis(),
        )
        repo.writeState(state)
        return state
    }

    fun finish(status: String): ScriptState {
        state = state.copy(status = status, updatedAt = System.currentTimeMillis())
        repo.writeState(state)
        return state
    }

    /** 恢复时用于跳过已提交段（返回 true 表示"这一段已经有了，不要再算"）。 */
    fun shouldSkip(segmentOrdinal: Int): Boolean = segmentOrdinal < state.nextSegmentIndex

    /**
     * 段落 ordinal ↔ `preparedUntil` 的换算说明（避免两个概念混淆）：
     * ```
     * segmentOrdinal : 从章首算起的可朗读片段序号（0-based），与 runner 的统计口径一致
     * preparedUntil  : 已经**有可用剧本**的最大 ordinal（含）
     * ```
     * 阅读驱动预取据此判断"读到 S37、已备到 S54 ⇒ 还不用算"。
     */
    fun isPreparedThrough(segmentOrdinal: Int): Boolean = state.preparedUntil >= segmentOrdinal
}
