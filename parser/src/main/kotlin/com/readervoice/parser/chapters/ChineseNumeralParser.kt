package com.readervoice.parser.chapters

/**
 * 中文数字解析（TASK-020 §10/§11）——独立模块，禁止各 Regex 自行维护数字字符集。
 *
 * 只回答"如果这是章节序号，它是多少"；不回答"它是不是章节"（后者归 Resolver）。
 * 解析失败返回 null（不强猜）。纯函数，无状态。
 *
 * 支持：一/十一/十二/二十/一百零八/两千三百/一万零三/壹佰贰拾/〇一二/零一二/0012/１２３（全角）
 * 语义：十前无数字默认 1（"十二"=12）；"一十二" 自然解析为 12（"一"被十消费为基数）；
 * 纯中文数字无单位序列按位拼接（"一二"=12、"二零二六"=2026——是否章节由 Resolver 判定）。
 */
object ChineseNumeralParser {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        '壹' to 1, '贰' to 2, '叁' to 3, '肆' to 4, '伍' to 5,
        '陆' to 6, '柒' to 7, '捌' to 8, '玖' to 9,
    )
    private val UNITS = mapOf(
        '十' to 10, '拾' to 10,
        '百' to 100, '佰' to 100,
        '千' to 1000, '仟' to 1000,
        '万' to 10000,
    )

    fun parse(raw: String): Int? {
        val s = normalize(raw)
        if (s.isEmpty()) return null

        // 纯阿拉伯数字（含全角转换后）："0012" → 12
        if (s.all { it.isDigit() }) return s.toIntOrNull()

        // 纯中文数字序列无单位：按位拼接（"一二"→12、"〇一二"→12、"二零二六"→2026）
        if (s.all { it in DIGITS }) {
            var v = 0
            for (c in s) v = v * 10 + DIGITS.getValue(c)
            return v
        }

        // 中文数字 + 单位
        var total = 0
        var section = 0
        var number = 0
        for (c in s) {
            when {
                c in DIGITS -> number = DIGITS.getValue(c)
                c in UNITS -> {
                    val u = UNITS.getValue(c)
                    if (u == 10000) {
                        total += (section + number) * 10000
                        section = 0
                        number = 0
                    } else {
                        val base = when {
                            number != 0 -> number
                            u == 10 && section == 0 && total == 0 -> 1 // "十二"：十无前数默认 1
                            else -> 0
                        }
                        section += base * u
                        number = 0
                    }
                }
                else -> return null
            }
        }
        return total + section + number
    }

    private fun normalize(s: String): String = buildString {
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\u3000') continue
            if (c in '０'..'９') append((c.code - '０'.code + '0'.code).toChar()) // 全角数字 → 半角（append Char 而非码点）
            else append(c)
        }
    }
}
