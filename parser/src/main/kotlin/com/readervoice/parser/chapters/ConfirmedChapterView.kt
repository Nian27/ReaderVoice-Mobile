package com.readervoice.parser.chapters

/**
 * CH-0 / M0.1 + M0.2：**已确认章节视图（ConfirmedChapterView）**。
 *
 * ## 为什么需要它
 *
 * `StructureRevision.chapters` 是**候选集合**，里面同时装着
 * `CONFIRMED / PROVISIONAL / REJECTED / TOC_ENTRY`。实测在真实书上：
 *
 * ```
 * 20 MB 网文：识别 16,060 个"章节"（正常应为 600~2,000）
 * 抽样直接看到：第 50/100/249 章 state=REJECTED
 *              title = "《智慧树》播出之后…"  ← 书名号正文被当成标题
 *              title = "========================"  ← 分隔线被当成标题
 * ```
 *
 * 下游一旦按 `chapters` 的下标当章节序号，得到的就是**假章节 + 错范围**，
 * 进而连锁放大：取文过大 → 段落恢复扫大量文本 → 片段数暴涨 → 连标构造反复 →
 * Director 任务"看起来特别慢"。所以：
 *
 * > **`rawCandidates`（`revision.chapters`）只用于诊断；
 * >  产品（UI / Reader / ChapterDirectorService / 剧本任务）一律只消费本视图。**
 *
 * ## 本视图保证
 *
 * 1. 只含 `state == CONFIRMED`（`PROVISIONAL` 需显式 opt-in）
 * 2. `ordinal` **连续**（0..n-1），与"第几章"一一对应
 * 3. 按 `anchorByteStart` 升序（**不信任 `chapterIndex`** —— 实测出现 `8 → 0` 回退）
 * 4. `contentEndLine` 被**重算**：非末章 = 下一确认章的 `contentStartLine - 1`；末章 = EOF
 *    （实测原值对非末章为 `null`，下游默认成"到文件末尾"，造成跨章取文）
 * 5. 区间单调不重叠、且 `contentStartLine <= contentEndLine`
 */
data class ConfirmedChapter(
    /** 产品唯一序号（连续，从 0 开始）。**目录/进度/导航用它**；持久化主键用 [chapterId]。 */
    val ordinal: Int,
    /** 章节稳定标识 —— **持久化主键**（章节列表变化不影响它）。内容派生：= 锚点行 byteStart。 */
    val chapterId: Long,
    /** 原始解析器的序号（**不可信**，实测有 8→0 回退；仅诊断用）。 */
    val chapterIndex: Int,
    /** 展示标题（清洗后）。 */
    val title: String,
    /** 原文标题行（保留源样，导航用；不复制原文到别处，只是内存串）。 */
    val titleRaw: String,
    val anchorByteStart: Int,
    val contentStartLine: Int,
    val contentEndLine: Int,
    val state: ResolveState,
) {
    val lineCount: Int get() = (contentEndLine - contentStartLine + 1).coerceAtLeast(0)
}

object ConfirmedChapterView {

    /**
     * @param eofLine 全书总行数（末章 `contentEndLine` 取它）
     * @param includeProvisional 是否把 `PROVISIONAL` 也纳入（默认 false：产品口径只认 CONFIRMED）
     */
    fun of(
        revision: StructureRevision,
        eofLine: Int,
        includeProvisional: Boolean = false,
    ): List<ConfirmedChapter> {
        val accepted = revision.chapters
            .filter { it.state == ResolveState.CONFIRMED || (includeProvisional && it.state == ResolveState.PROVISIONAL) }
            .sortedBy { it.anchorByteStart }        // 不信任 chapterIndex（实测有回退）

        return accepted.mapIndexed { i, ch ->
            val start = ch.contentStartLine.coerceIn(1, maxOf(1, eofLine))
            // M0.2：正文区间**按下一个确认章重算**，末章到 EOF
            val endFromNext = accepted.getOrNull(i + 1)?.contentStartLine?.minus(1)
            val end = (endFromNext ?: eofLine).coerceIn(start, maxOf(start, eofLine))
            ConfirmedChapter(
                ordinal = i,
                chapterId = ch.chapterId,
                chapterIndex = ch.chapterIndex,
                title = ch.titleDisplay,
                titleRaw = ch.titleRaw,
                anchorByteStart = ch.anchorByteStart,
                contentStartLine = start,
                contentEndLine = end,
                state = ch.state,
            )
        }
    }

    /** 便捷入口：`revision.confirmedChapters(eofLine)`。 */
    fun StructureRevision.confirmedChapters(eofLine: Int, includeProvisional: Boolean = false): List<ConfirmedChapter> =
        of(this, eofLine, includeProvisional)

    /** 自检：返回不满足不变式的描述（应为空）。 */
    fun violations(view: List<ConfirmedChapter>, eofLine: Int): List<String> {
        val out = mutableListOf<String>()
        // ★ M0.4：chapterId 必须唯一且非 0 —— 它是持久化主键（ordinal 不行，章节集合会变）
        view.groupBy { it.chapterId }.filter { it.value.size > 1 }.forEach { (id, dup) ->
            out += "chapterId=$id 重复 ${dup.size} 次（ordinal=${dup.map { it.ordinal }}）"
        }
        view.filter { it.chapterId == 0L }.forEach { out += "ordinal=${it.ordinal} chapterId=0（未分配）" }
        view.forEachIndexed { i, c ->
            if (c.ordinal != i) out += "ordinal 不连续：第 $i 项 ordinal=${c.ordinal}"
            if (c.state != ResolveState.CONFIRMED && c.state != ResolveState.PROVISIONAL) {
                out += "ordinal=${c.ordinal} 含非确认状态 ${c.state}"
            }
            if (c.contentStartLine > c.contentEndLine) {
                out += "ordinal=${c.ordinal} 区间倒置 ${c.contentStartLine}..${c.contentEndLine}"
            }
            if (i > 0) {
                val p = view[i - 1]
                if (c.contentStartLine <= p.contentEndLine) {
                    out += "ordinal=$i 与前章重叠：prev.end=${p.contentEndLine} ≥ start=${c.contentStartLine}"
                }
                if (c.anchorByteStart <= p.anchorByteStart) {
                    out += "ordinal=$i anchorByte 非单调：${p.anchorByteStart} ≥ ${c.anchorByteStart}"
                }
            }
            if (c.contentEndLine > eofLine) out += "ordinal=${c.ordinal} end 超 EOF"
        }
        return out
    }
}
