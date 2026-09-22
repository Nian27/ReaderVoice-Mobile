package com.readervoice.data.snapshot

import com.readervoice.data.character.CharacterAttribute
import com.readervoice.data.character.CharacterReadStore
import com.readervoice.data.character.EffectiveVoiceState
import com.readervoice.data.character.IdentityResolution
import com.readervoice.data.character.NarrativeEntity

/**
 * MOBILE-005 / M3：**自举只读 store（设备侧）**。
 *
 * 设备上没有 sqlite-jdbc / 角色库表；而 `ChapterDirectorRunner` 需要的只读能力其实很窄：
 *   ① 候选召回用的 `canonicalNamesOf`（entitySet）
 *   ② 把候选名映射回身份的 `entityByCanonicalName` / `resolveIdentity`
 *   ③ 身份约束/声音状态（当前阶段可退化为默认值）
 *
 * 因此设备侧先用手上的「已知角色名集合」自举一个 store：
 * 身份 = 名字本身（尚无聚类）；别名/声音状态为空。
 * 这不是最终形态（最终应接角色层的聚类与声音绑定），但它让**整章真实运行**在今天就可行，
 * 且不引入任何"假装有数据"的行为：没有的信息一律走默认/空，由上层按 UNKNOWN 处理。
 */
class BootstrapCharacterStore(
    private val canonicalNames: Set<String>,
) : CharacterReadStore {

    private val byName: Map<String, NarrativeEntity> =
        canonicalNames.sorted().mapIndexed { i, n ->
            n to NarrativeEntity(
                entityPk = (i + 1).toLong(),
                entityUid = "boot-$n",
                bookPk = 0L,
                entityType = "PERSON",
                canonicalName = n,
                status = "PROVISIONAL",     // 自举阶段：未经身份聚类验证
            )
        }.toMap()

    override fun entityByPk(entityPk: Long): NarrativeEntity? = byName.values.firstOrNull { it.entityPk == entityPk }

    override fun entityByCanonicalName(bookPk: Long, name: String): NarrativeEntity? = byName[name]

    override fun canonicalNamesOf(bookPk: Long): Set<String> = canonicalNames

    override fun aliasesOf(bookPk: Long, clusterId: String): List<String> = emptyList()

    override fun resolveIdentity(bookPk: Long, entityPk: Long): IdentityResolution =
        IdentityResolution(entityPk, null, byName.values.firstOrNull { it.entityPk == entityPk }?.status ?: "UNKNOWN")

    override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long): EffectiveVoiceState =
        EffectiveVoiceState(entityPk, null, null, null, emptyMap<String, CharacterAttribute>())

    override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? = null

    override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? = null

    override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean = false

    override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int> = emptyMap()
}
