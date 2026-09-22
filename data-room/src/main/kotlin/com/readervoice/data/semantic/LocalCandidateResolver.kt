package com.readervoice.data.semantic

/**
 * MOBILE-005 / C18：**本轮候选映射表**。
 *
 * `C0..Cn → CharacterId`，在模型调用**前**由身份解析结果构建，模型调用后只用于查表，
 * 随该请求一起丢弃 —— 绝不跨段复用（C18）。
 */
class CandidateMap(private val byLocalId: Map<String, CharacterId>) {

    fun characterOf(localId: String): CharacterId? = byLocalId[localId]

    fun localIds(): Set<String> = byLocalId.keys

    val size: Int get() = byLocalId.size

    companion object {
        /**
         * 由 DirectorContext 的候选构建。
         *
         * `CandidateSpeaker.localId` 是模型可见的局部编号；`identityId` 是内部稳定身份
         * （设备自举阶段它是名字，接角色层后是 cluster/entity 的稳定 id）。
         */
        fun from(ctx: DirectorContext): CandidateMap =
            CandidateMap(
                ctx.candidateSpeakers
                    .filter { it.identityId.isNotBlank() }
                    .associate { it.localId to CharacterId(it.identityId) },
            )
    }
}

/**
 * MOBILE-005 / C18 的强制点：模型返回后把局部 `C#` 解析为**持久** `SpeakerRef`。
 *
 * **这里不做任何身份推断** —— "药老/老师/老者/药尘 是不是同一个人" 属于
 * Understanding → Character 阶段（CH-1），这里只是纯查表。
 */
object LocalCandidateResolver {

    /**
     * @return 持久引用；`null` 表示局部 ID 无法解析（调用方必须 fail-closed：不产出持久行）
     */
    fun toSpeakerRef(decision: DirectorDecisionV2, map: CandidateMap): SpeakerRef? =
        when (val s = decision.speaker) {
            DirectorDecisionV2.NARRATOR -> SpeakerRef.Narrator
            DirectorDecisionV2.UNKNOWN -> SpeakerRef.Unknown
            else -> map.characterOf(s)?.let { SpeakerRef.Character(it) }
        }

    /** 便捷：只有解析成功才返回引用（否则 null）。与 [toSpeakerRef] 同义，语义更显式。 */
    fun resolveOrNull(decision: DirectorDecisionV2, map: CandidateMap): SpeakerRef? =
        toSpeakerRef(decision, map)
}
