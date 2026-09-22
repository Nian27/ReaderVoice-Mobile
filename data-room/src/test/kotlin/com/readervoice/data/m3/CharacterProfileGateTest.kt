package com.readervoice.data.m3

import com.readervoice.data.character.CharacterProfileExtractor
import com.readervoice.data.character.ProfileConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 角色档案抽取门（用户："人物档案，连年龄都没有，更别说其他的了"）。
 *
 * 两条必须同时成立：
 * 1. **抽得到**：书里真写了的年龄/性别/身份要抽出来，并带**原文证据**（可回溯到行号）。
 * 2. **不编造**：没写的必须是 `未知`（null），置信度 NONE —— 不许给一个看起来合理的假值。
 */
class CharacterProfileGateTest {

    private val lines = listOf(
        "李寄舟今年十八岁，正是意气风发的年纪。",                 // 1 年龄（名字后 N 岁）
        "十八岁的李寄舟站在山门前，仰头看着那块匾额。",           // 2 年龄（N 岁的X）+ 身份候选
        "李寄舟是魔教教主，这一点雷老虎心里很清楚。",             // 3 身份
        "她看着李寄舟，眼里满是担忧。",                           // 4 代词（她）
        "李寄舟摇了摇头，他知道自己不能退。",                     // 5 代词（他）
        "雷老虎低声道：“教主，我们该走了。”",                   // 6 台词相关
        "雷老虎是个粗豪的男子，做事却极细。",                     // 7 性别（男子）
        "空闻大师双手合十，念了声佛号。",                         // 8 别称（空闻 + 大师）
        "“教主还是不要抱有太多妄想比较好。”",                   // 9
    )
    private val names = listOf("李寄舟", "雷老虎", "空闻")

    @Test
    fun `age gender role are extracted with evidence`() {
        val profiles = CharacterProfileExtractor.extract(names, lines, spokenLines = mapOf("雷老虎" to 3))
        val byName = profiles.associateBy { it.name }

        val li = byName.getValue("李寄舟")
        assertEquals("18", li.age.value, "年龄应抽到 18（十八岁）")
        assertTrue(li.age.evidence.isNotEmpty(), "年龄必须带原文证据")
        assertTrue(
            li.age.evidence.first().quote.contains("岁"),
            "证据应是原文片段: ${li.age.evidence.first()}",
        )
        assertEquals(ProfileConfidence.HIGH, li.age.confidence, "两处独立证据 ⇒ 高置信")
        assertEquals("魔教教主", li.role.value, "身份应抽到 魔教教主")

        val lei = byName.getValue("雷老虎")
        assertEquals("男", lei.gender.value, "男子 ⇒ 男")
        assertEquals(3, lei.spokenLines, "台词数应来自剧本统计")

        val kong = byName.getValue("空闻")
        assertTrue(kong.aliases.contains("空闻大师"), "别称应为原文真实出现过的组合: ${kong.aliases}")
    }

    @Test
    fun `unknown fields stay unknown instead of being invented`() {
        // 这本书里没有"张三"的任何信息 ⇒ 全部字段必须是未知，不许猜
        val profiles = CharacterProfileExtractor.extract(listOf("张三"), listOf("山中无甲子，寒尽不知年。"))
        val p = profiles.single()
        assertNull(p.age.value, "没写年龄就必须是未知")
        assertNull(p.role.value, "没写身份就必须是未知")
        assertEquals(ProfileConfidence.NONE, p.age.confidence)
        assertEquals(0, p.mentions, "没出现过 ⇒ 提及数 0")
        assertTrue(p.aliases.isEmpty(), "别称不得凭空生成")
    }

    @Test
    fun `longer names win over shorter prefixes`() {
        // "张三丰"与"张三"同时在集合里时，不应把"张三丰"拆给"张三"
        val ls = listOf("张三丰捋了捋胡须，笑道：“无量寿佛。”")
        val profiles = CharacterProfileExtractor.extract(listOf("张三", "张三丰"), ls)
        val zhangSan = profiles.first { it.name == "张三" }
        val zhangSanFeng = profiles.first { it.name == "张三丰" }
        assertEquals(1, zhangSanFeng.mentions, "长名应被正确计数")
        assertEquals(0, zhangSan.mentions, "短名不应冒领长名的出现")
    }

    @Test
    fun `gender only when evidence leans one way`() {
        // 只有 1 次"他"、1 次"她" ⇒ 平票 ⇒ 未知（不硬猜）
        val ls = listOf("他看着林夜。", "她看着林夜。")
        val p = CharacterProfileExtractor.extract(listOf("林夜"), ls).single()
        assertNull(p.gender.value, "平票必须未知：${p.gender}")
    }
}
