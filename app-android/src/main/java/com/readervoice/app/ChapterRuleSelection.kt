package com.readervoice.app

import com.readervoice.parser.chapters.ChapterRule
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 章节规则选择（用户覆盖）。
 *
 * 设计要点：
 *   · **不改原文、不改规则包本体**（C1 原文不可变；规则包是构建资产）
 *   · 选择持久化在**书包内**（`chapter_rules.json`），因此随书走、切书隔离
 *   · 一旦存在用户选择，它就是**权威**：`enabled = ruleId ∈ 选择集`
 *     （即用户也能打开规则包里默认关闭的规则）
 *   · 重新分章 = 用选中规则重跑 `ChapterStructureCompiler` 并原子替换章节目录
 */
object ChapterRuleSelection {

    private const val FILE = "chapter_rules.json"

    fun load(packageDir: File): Set<String>? {
        val f = File(packageDir, FILE)
        if (!f.isFile) return null
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val arr = o.optJSONArray("enabledRuleIds") ?: return null
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun save(packageDir: File, enabledRuleIds: Collection<String>) {
        File(packageDir, FILE).writeText(
            JSONObject()
                .put("note", "用户章节规则选择（覆盖规则包的 enabled）")
                .put("enabledRuleIds", JSONArray(enabledRuleIds.toList()))
                .toString(),
        )
    }

    /** 应用选择：selection == null 表示沿用规则包自带的 enabled。 */
    fun apply(rules: List<ChapterRule>, selection: Set<String>?): List<ChapterRule> =
        if (selection == null) rules else rules.map { it.copy(enabled = it.ruleId in selection) }
}
