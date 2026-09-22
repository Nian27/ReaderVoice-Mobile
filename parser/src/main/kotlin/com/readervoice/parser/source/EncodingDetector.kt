package com.readervoice.parser.source

import java.lang.Character.UnicodeScript

/**
 * 编码检测管线（TASK-010 §9/§12）：
 * BOM → strict UTF-8 → UTF-16 heuristics → GB18030 candidate → candidate scoring → result + confidence。
 *
 * 判据不是"decode 不报错"，而是特征评分：
 * hanRatio / 中文标点 / control 比例 / NUL 模式 / 解码错误（严格模式直接判死）。
 */
object EncodingDetector {

    private const val MIN_CONFIDENCE = 0.55

    data class CandidateScore(val kind: CharsetKind, val score: Double, val hanRatio: Double)

    fun detect(bytes: ByteArray): EncodingResult {
        // 1. BOM（属于源文件字节，不属于第一行正文字符）
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return EncodingResult(CharsetKind.UTF_8_BOM, 1.0, "BOM", bomBytes = 3)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return EncodingResult(CharsetKind.UTF_16LE, 1.0, "BOM", bomBytes = 2)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return EncodingResult(CharsetKind.UTF_16BE, 1.0, "BOM", bomBytes = 2)

        // 2. 空文件：无信息，默认 UTF-8 低置信
        if (bytes.isEmpty()) return EncodingResult(CharsetKind.UTF_8, 0.5, "empty")

        // 2.5 纯 ASCII：所有字节 < 0x80 → 编码交集，直接 UTF-8 高置信（GBK/UTF-16 声称无意义）
        if (bytes.all { it.toInt() in 0..0x7F }) {
            return EncodingResult(CharsetKind.UTF_8, 1.0, "ascii-subset")
        }

        val candidates = mutableListOf<CandidateScore>()

        // 3. strict UTF-8
        val utf8 = tryDecodeAll(bytes, Utf8Decoder)
        if (utf8 != null) {
            candidates += CandidateScore(
                CharsetKind.UTF_8,
                scoreUtf8(utf8), utf8.hanRatio
            )
        }

        // 4. UTF-16 heuristics（无 BOM）：NUL 分布偏斜
        val nul = nulDistribution(bytes)
        if (nul != null) {
            val dec = Utf16Decoder(nul.bigEndian)
            val d = tryDecodeAll(bytes, dec)
            if (d != null) {
                val ctrl = d.controlRatio
                val score = 0.5 + d.hanRatio * 0.4 + (1 - ctrl) * 0.1
                candidates += CandidateScore(
                    if (nul.bigEndian) CharsetKind.UTF_16BE else CharsetKind.UTF_16LE,
                    score.coerceIn(0.0, 1.0), d.hanRatio
                )
            }
        }

        // 5. GB18030 candidate（含 4 字节序列判定 GB18030 vs GBK）
        val gb = tryDecodeAll(bytes, Gb18030Decoder())
        if (gb != null) {
            val kind = if (gb.has4Byte) CharsetKind.GB18030 else CharsetKind.GBK
            val ctrl = gb.controlRatio
            val score = 0.4 + gb.hanRatio * 0.5 + (1 - ctrl) * 0.1
            candidates += CandidateScore(kind, score.coerceIn(0.0, 1.0), gb.hanRatio)
        }

        if (candidates.isEmpty()) {
            return EncodingResult(CharsetKind.UTF_8, 0.0, "none", alternatives = emptyList())
        }

        candidates.sortByDescending { it.score }
        val best = candidates[0]
        val second = candidates.getOrNull(1)
        val margin = if (second != null) best.score - second.score else 1.0
        val confidence = (best.score * 0.6 + margin.coerceIn(0.0, 1.0) * 0.4).coerceIn(0.0, 1.0)

        val alts = candidates.drop(1).map { it.kind }
        if (confidence < MIN_CONFIDENCE || margin < 0.12) {
            return EncodingResult(best.kind, confidence, "scoring", alternatives = alts)
        }
        return EncodingResult(best.kind, confidence, "scoring", alternatives = alts)
    }

    // ---- 特征统计 ----

    private data class DecodeStats(
        val hanRatio: Double,
        val controlRatio: Double,
        val has4Byte: Boolean,
        val hanCount: Int,
        val totalCount: Int,
    )

    /** 严格全量解码：成功返回统计；任何错误返回 null（该编码判死）。 */
    private fun tryDecodeAll(bytes: ByteArray, decoder: CharDecoder): DecodeStats? {
        var pos = 0
        var han = 0
        var control = 0
        var total = 0
        var has4 = false
        val end = bytes.size
        while (pos < end) {
            val (cp, next) = try {
                decoder.decodeNext(bytes, pos, end)
            } catch (e: DecodeException) {
                return null
            }
            if (Character.UnicodeScript.of(cp) == UnicodeScript.HAN) han++
            if (isControl(cp)) control++
            if (decoder is Gb18030Decoder && next - pos == 4) has4 = true
            total++
            pos = next
        }
        return DecodeStats(
            hanRatio = if (total == 0) 0.0 else han.toDouble() / total,
            controlRatio = if (total == 0) 0.0 else control.toDouble() / total,
            has4Byte = has4,
            hanCount = han,
            totalCount = total,
        )
    }

    private fun scoreUtf8(s: DecodeStats): Double {
        // UTF-8 合法 + 汉字占比主导 → 高置信；纯 ASCII 也合法（是 UTF-8 子集），但置信度中等
        if (s.hanRatio >= 0.05) return 0.5 + s.hanRatio.coerceAtMost(0.5) // 0.55..1.0
        return 0.55 // 无汉字（ASCII/其他），仍是合法 UTF-8
    }

    private fun isControl(cp: Int): Boolean = cp in 0x00..0x1F || cp in 0x7F..0x9F

    private data class NulInfo(val bigEndian: Boolean, val bias: Double)

    /** 无 BOM UTF-16 探测：NUL 在奇/偶位置的偏斜（ASCII 字符在 UTF-16 下必带一个 NUL）。 */
    private fun nulDistribution(bytes: ByteArray): NulInfo? {
        if (bytes.size < 16) return null
        var even = 0
        var odd = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i].toInt() == 0) {
                if (i % 2 == 0) even++ else odd++
            }
            i++
        }
        val total = bytes.size / 2
        val evenRatio = even.toDouble() / total
        val oddRatio = odd.toDouble() / total
        return when {
            evenRatio > 0.30 -> NulInfo(false, evenRatio)   // 偶数位置 NUL 多 → LE（字符在低字节）
            oddRatio > 0.30 -> NulInfo(true, oddRatio)      // 奇数位置 NUL 多 → BE
            else -> null
        }
    }
}
