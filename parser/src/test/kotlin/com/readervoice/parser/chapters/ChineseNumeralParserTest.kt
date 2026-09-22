package com.readervoice.parser.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChineseNumeralParserTest {

    @Test
    fun `basic numerals`() {
        assertEquals(1, ChineseNumeralParser.parse("一"))
        assertEquals(11, ChineseNumeralParser.parse("十一"))
        assertEquals(12, ChineseNumeralParser.parse("十二"))
        assertEquals(20, ChineseNumeralParser.parse("二十"))
        assertEquals(21, ChineseNumeralParser.parse("二十一"))
        assertEquals(108, ChineseNumeralParser.parse("一百零八"))
        assertEquals(2300, ChineseNumeralParser.parse("两千三百"))
        assertEquals(10003, ChineseNumeralParser.parse("一万零三"))
        assertEquals(120, ChineseNumeralParser.parse("壹佰贰拾"))
        assertEquals(110, ChineseNumeralParser.parse("一百一十"))
    }

    @Test
    fun `colloquial one-ten`() {
        assertEquals(10, ChineseNumeralParser.parse("一十"))
        assertEquals(12, ChineseNumeralParser.parse("一十二"))
        assertEquals(13, ChineseNumeralParser.parse("一十三"))
    }

    @Test
    fun `leading zeros and padded`() {
        assertEquals(12, ChineseNumeralParser.parse("〇一二"))
        assertEquals(12, ChineseNumeralParser.parse("零一二"))
        assertEquals(12, ChineseNumeralParser.parse("0012"))
        assertEquals(123, ChineseNumeralParser.parse("１２３")) // 全角
        assertEquals(12, ChineseNumeralParser.parse("第12章".substring(1, 3)))
    }

    @Test
    fun `digit sequence without units`() {
        assertEquals(12, ChineseNumeralParser.parse("一二"))
        assertEquals(2026, ChineseNumeralParser.parse("二零二六")) // 解析为 2026；是否章节由 Resolver 判
        assertEquals(520, ChineseNumeralParser.parse("五二零"))
    }

    @Test
    fun `dates and plain numbers parse but are not chapters`() {
        // Parser 只回答"若是章节序号是多少"——这些数字能解析，但 Resolver 必须用负例拒绝
        assertEquals(2026, ChineseNumeralParser.parse("2026"))
        assertEquals(1998, ChineseNumeralParser.parse("1998"))
        assertEquals(520, ChineseNumeralParser.parse("520"))
        assertEquals(1024, ChineseNumeralParser.parse("1024"))
        assertEquals(2026, ChineseNumeralParser.parse("二零二六"))
    }

    @Test
    fun `invalid inputs return null`() {
        assertNull(ChineseNumeralParser.parse(""))
        assertNull(ChineseNumeralParser.parse("abc"))
        assertNull(ChineseNumeralParser.parse("一百二十章abc"))
        assertNull(ChineseNumeralParser.parse("零a"))
    }

    @Test
    fun `whitespace tolerated`() {
        assertEquals(12, ChineseNumeralParser.parse(" 十二 "))
        assertEquals(12, ChineseNumeralParser.parse("　　十二"))
        assertEquals(123, ChineseNumeralParser.parse("１ ２ ３"))
    }
}
