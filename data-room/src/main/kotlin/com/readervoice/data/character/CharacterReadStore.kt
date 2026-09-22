package com.readervoice.data.character

/**
 * MOBILE-005 / M2：字符层【只读】接口。
 *
 * 动机：语义层（data-room/semantic: Segmenter / RuleSpeakerBaseline / SpeakerCandidateCompiler /
 * ContextBuilder / NarrationIRBuilder）只需要读角色信息，却被绑在 `CharacterStore`（内含
 * `java.sql.Connection`，Android 无法直接使用）上，导致整层无法搬到设备。
 *
 * 抽取原则：
 *  - 方法签名与 `CharacterStore` **逐字一致**（只加 override，不改逻辑）⇒ 桌面行为不变
 *  - 只抽语义层【实际调用】的 9 个只读方法（已用调用面统计确认，非猜测）
 *  - 写侧（insert / merge / upsert / setAttribute）不在此接口：设备端本轮不需要写角色库
 *
 * 设备侧实现见 `SnapshotCharacterStore`（内存快照，索引 k 张表）。
 */
interface CharacterReadStore {

    fun entityByPk(entityPk: Long): NarrativeEntity?

    fun entityByCanonicalName(bookPk: Long, name: String): NarrativeEntity?

    /**
     * M3：本书全部规范名（候选编译器的 entitySet 输入）。
     * 供 `SpeakerCandidateCompiler` 的 RECENT_MENTION 源使用 —— 否则窗口里出现的人名
     * （"傅远坐下扫了一眼"）无法成为候选，规则层一旦给不出 speaker 就再无候选可用。
     */
    fun canonicalNamesOf(bookPk: Long): Set<String>

    /** 同一 identity cluster 内其它实体的规范名（按 entity_pk 升序）。 */
    fun aliasesOf(bookPk: Long, clusterId: String): List<String>

    fun resolveIdentity(bookPk: Long, entityPk: Long): IdentityResolution

    /** causal：只用 position 之前的证据（§22/§63）。 */
    fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long): EffectiveVoiceState

    /** 附身：谁通过 bodyEntityPk 说话（position 时点有效）。 */
    fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long?

    /** 附身 + 状态类型（用于 narration/embodiment 判定）。 */
    fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>?

    /** 硬负例（师父≠徒弟 / 宿主≠控制者 等）：合并与候选必须尊重。 */
    fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean

    /** 证据摘要（relation_type → 计数），用于身份判定的可审计性。 */
    fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int>
}
