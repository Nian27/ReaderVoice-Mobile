package com.readervoice.app

import com.readervoice.data.character.CharacterProfile
import com.readervoice.data.character.ProfileConfidence
import com.readervoice.data.character.ProfileField
import java.io.File
import org.json.JSONObject

/**
 * 角色档案的**用户修正层**（`files/books/<bookId>/character_overrides.json`）。
 *
 * 规则抽取会错、会缺（用户原话："连年龄都没有"）。产品必须允许**人工修正**，
 * 而且修正一旦落下就是 **USER_LOCKED**：后续规则重算/模型升级都不得覆盖（根 AGENTS 不变量 8）。
 *
 * ```
 * { "林夜": { "age": "19", "gender": "男", "role": "剑修", "locked": true } }
 * ```
 */
object CharacterProfileStore {

    private fun file(packageDir: File) = File(packageDir, "character_overrides.json")

    data class Override(
        val age: String? = null,
        val gender: String? = null,
        val role: String? = null,
        val locked: Boolean = false,
    )

    fun loadAll(packageDir: File): Map<String, Override> {
        val f = file(packageDir)
        if (!f.isFile) return emptyMap()
        return runCatching {
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { k ->
                val e = o.getJSONObject(k)
                Override(
                    age = e.optString("age").ifBlank { null },
                    gender = e.optString("gender").ifBlank { null },
                    role = e.optString("role").ifBlank { null },
                    locked = e.optBoolean("locked", false),
                )
            }
        }.getOrDefault(emptyMap())
    }

    fun save(packageDir: File, name: String, o: Override) {
        runCatching {
            val all = loadAll(packageDir).toMutableMap()
            all[name] = o
            val root = JSONObject()
            all.forEach { (k, v) ->
                root.put(
                    k,
                    JSONObject()
                        .put("age", v.age ?: "")
                        .put("gender", v.gender ?: "")
                        .put("role", v.role ?: "")
                        .put("locked", v.locked),
                )
            }
            val f = file(packageDir)
            val tmp = File(packageDir, "character_overrides.json.tmp")
            tmp.writeText(root.toString(1))
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString(1))
                tmp.delete()
            }
        }
    }

    /**
     * 把用户修正合并进抽取结果：**用户值优先**，并标记 locked。
     * 未修正的字段保持规则抽取结果（含其证据与置信度）。
     */
    fun apply(profiles: List<CharacterProfile>, overrides: Map<String, Override>): List<CharacterProfile> =
        profiles.map { p ->
            val o = overrides[p.name] ?: return@map p
            p.copy(
                age = o.age?.let { ProfileField(it, ProfileConfidence.HIGH, p.age.evidence) } ?: p.age,
                gender = o.gender?.let { ProfileField(it, ProfileConfidence.HIGH, p.gender.evidence) } ?: p.gender,
                role = o.role?.let { ProfileField(it, ProfileConfidence.HIGH, p.role.evidence) } ?: p.role,
                locked = o.locked,
            )
        }
}
