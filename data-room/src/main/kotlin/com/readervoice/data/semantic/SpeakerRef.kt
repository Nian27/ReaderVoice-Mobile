package com.readervoice.data.semantic

/**
 * MOBILE-005 / C18：**持久说话人引用**。
 *
 * `C0/C1/...` 是**一次模型请求内部的临时地址**（每样本按确定性 permutation 分配），
 * 它绝不能进入长期 `ScriptLine` / `NarrationIR` / `CharacterStore` —— 否则下一段重排后
 * 同一个 `C1` 可能已经是另一个人。
 *
 * 生命周期（C18）：
 * ```
 * DirectorContext → Prompt → DirectorDecisionV2 → Validator
 *   → LocalCandidateResolver（本轮 CandidateMap 查表）
 *   → SpeakerRef                    ← 只有它允许被持久化
 * ```
 *
 * `CharacterId` 是**稳定 opaque ID**，不是 SQLite rowid / 自增整数：数据库迁移、角色 merge、
 * 角色库导入导出、按书迁移、PROVISIONAL→PERSISTENT、旧版角色库迁移都必须让这个 id 保持稳定。
 */
@JvmInline
value class CharacterId(val value: String) {
    init {
        require(value.isNotBlank()) { "CharacterId 不能为空" }
    }
}

sealed interface SpeakerRef {

    /** 旁白：路由 Audio8，永不绑定角色音色。 */
    data object Narrator : SpeakerRef

    /** 未确认说话人：合法结果（C8），路由到临时对白兜底，**不得**为它强造角色。 */
    data object Unknown : SpeakerRef

    /** 已确认角色：持有稳定 opaque id。 */
    data class Character(val id: CharacterId) : SpeakerRef

    companion object {
        const val TOKEN_NARRATOR = "NARRATOR"
        const val TOKEN_UNKNOWN = "UNKNOWN"
        const val PREFIX_CHARACTER = "CHARACTER:"

        /** 线上表示（JSONL）：`NARRATOR` | `UNKNOWN` | `CHARACTER:<stable-id>`。 */
        fun encodeToken(ref: SpeakerRef): String = when (ref) {
            Narrator -> TOKEN_NARRATOR
            Unknown -> TOKEN_UNKNOWN
            is Character -> PREFIX_CHARACTER + ref.id.value
        }

        /**
         * 解析线上表示。
         * @return null 表示**不是合法持久引用**（例如旧格式的 `C0`）—— 调用方必须 fail-closed，
         *         绝不能把它猜成某个角色（ADR-055 / 不猜历史 C0 是谁）。
         */
        fun decodeToken(token: String): SpeakerRef? = when {
            token == TOKEN_NARRATOR -> Narrator
            token == TOKEN_UNKNOWN -> Unknown
            token.startsWith(PREFIX_CHARACTER) ->
                token.removePrefix(PREFIX_CHARACTER).takeIf { it.isNotBlank() }?.let { Character(CharacterId(it)) }
            else -> null
        }
    }
}
