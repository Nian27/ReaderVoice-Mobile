package com.readervoice.parser.chapters

/**
 * VolumeResolver（TASK-020 §21/§22）：卷锚点 → Volume；卷边界后的序号重置合法。
 * 判据独立于 family：rawTitle 含"卷"词（"第一卷 风起"、"卷五 开源盛世"）。
 */
object VolumeResolver {

    private val VOLUME_RE = Regex("(?:第\\s{0,4}[\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}\\s{0,4}卷|[零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8}卷)")

    fun isVolumeTitle(title: String): Boolean = VOLUME_RE.containsMatchIn(title)

    /** 卷序号提取（"第一卷"→1、"卷五"→5）。 */
    fun volumeSerial(title: String): Int? {
        val m = Regex("(?:第\\s{0,4}([\\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8})\\s{0,4}卷|([零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,8})卷)")
            .find(title) ?: return null
        val raw = m.groupValues[1].ifBlank { m.groupValues[2] }
        return ChineseNumeralParser.parse(raw)
    }

    fun resolve(groups: List<SameLineCandidateGroup>, bookId: String): List<Volume> {
        val volumes = mutableListOf<Volume>()
        var idx = 0
        for (g in groups) {
            val c = g.winning
            if (!isVolumeTitle(c.rawTitle)) continue
            idx++
            volumes += Volume(
                volumeId = idx.toLong(),
                bookId = bookId,
                volumeIndex = idx,
                serialRaw = c.serialRaw,
                serialValue = volumeSerial(c.rawTitle),
                title = c.cleanTitle.ifBlank { c.rawTitle },
                startLine = c.lineNo,
                confidence = c.regexScore,
            )
        }
        return volumes
    }
}
