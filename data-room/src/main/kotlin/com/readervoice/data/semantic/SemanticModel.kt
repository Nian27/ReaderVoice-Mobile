package com.readervoice.data.semantic

import com.readervoice.data.character.EffectiveVoiceState
import com.readervoice.data.character.IdentityResolution

/**
 * TASK-070 语义层模型：SemanticSegment / NarrationIR / Context。
 * emotion/dialect 为 null（V1 不实现，§TASK-070-3）。
 */

enum class SegmentType { NARRATION, SPEECH, INNER_MONOLOGUE, GROUP_SPEECH, QUOTE, UNKNOWN }

/** SemanticSegment（§1）：段落内语义切片，可 round-trip 回 Paragraph/SourceSpan（G1）。 */
data class SemanticSegment(
    val segmentId: Long,
    val paragraphRevisionId: Long,
    val segmentIndex: Int,
    val type: SegmentType,
    val text: String,
    val sourceStart: Int,   // 段落 normalized text 内偏移（round-trip 基准）
    val sourceEnd: Int,
    val quoteDepth: Int,
)

/** NarrationIR v1（§3）：最小冻结结构；emotion/dialect 可 null。 */
data class NarrationIR(
    val segmentId: Long,
    val segmentType: String,
    val textRef: String,
    val speaker: NarrationSpeakerRef?,
    val actingIdentityId: String?,   // 附身：魔尊（G7）
    val surfaceEntityId: String?,    // 附身：林雪
    val voiceStateRef: String?,      // EffectiveVoiceState 摘要
    val language: String = "zh",
    val emotion: String? = null,     // V1 不实现
    val dialect: String? = null,     // V1 不实现
    val provenance: List<String>,
)

/**
 * NarrationIR 层的说话人记录（原名 `SpeakerRef`）。
 *
 * 2026-09-18 C18 落地时改名：`SpeakerRef` 这个名字**让给 C18 的持久持久引用契约**
 * （`Narrator | Unknown | Character(CharacterId)`，见 `SpeakerRef.kt`）。
 * 本记录携带 `identityId`（稳定身份，**不是**局部 C#，因此不违反 C18）与状态/置信度。
 * 与 C18 `SpeakerRef` 的统一排入 CH-1（届时 status 由角色生命周期接管）。
 */
data class NarrationSpeakerRef(
    val identityId: String,
    val status: String,        // CONFIRMED/PROVISIONAL/UNKNOWN
    val confidence: Double,
)

/** ContextBuilder 输出（§4）：ReaderDirector 的结构化输入。 */
data class DirectorContext(
    val target: ContextSegment,
    val recentSegments: List<ContextSegment>,
    val candidateSpeakers: List<CandidateSpeaker>,
    val identityConstraints: List<String>,   // "r01 != r07"
    val embodiment: List<EmbodimentHint>,
    val userLocks: List<String>,
    /**
     * MOBILE-005 / M3：**结构化证据**（模型只能引用 E# 编号，不能自造证据文字）。
     * 典型来源：delivery cue（表演提示）、显式 cue、窗口活跃、规则 provenance。
     */
    val evidenceItems: List<EvidenceItem> = emptyList(),
)

data class ContextSegment(
    val text: String,
    val segmentType: String,
    val position: Long,
    /** MOBILE-005 / M3：稳定 segmentId（= SemanticSegment.segmentId），模型必须回填它。 */
    val segmentId: Long = 0L,
    val segmentIndex: Int = 0,
)

/**
 * 证据项（MOBILE-005 / M3）。`kind` 取值：
 *   DELIVERY_CUE   表演提示（"饶有兴致"/"冷冷地"），带 emotion_hint ⇒ 由 cue 污染转为导演证据
 *   SPEAKER_CUE    显式说话人 cue
 *   SCENE_ACTIVE   窗口活跃说话人
 *   RECENT_MENTION 近期提及
 *   RULE           规则层判定（provenance）
 */
data class EvidenceItem(
    val id: String,          // E0/E1…（局部 ID，确定性分配）
    val kind: String,
    val text: String,
    val emotionHint: String? = null,
)

/**
 * 候选说话人（M3 契约）。
 *
 * 两条 ID 必须分开（ADR-055 + DECISIONS 冻结设计）：
 *   · `localId`    —— **模型唯一可见**的局部 ID（C0/C1…），在每个样本内按确定性 permutation 分配，
 *                     避免"正确候选恒在 C0"的位置偏差，也不向模型泄漏持久实体标识。
 *   · `identityId` —— 内部身份（cluster/entity uid），**不得进入 prompt**，只用于回填与审计。
 */
data class CandidateSpeaker(
    val localId: String,
    val identityId: String,
    val name: String,
    val aliases: List<String>,
    val recentTurnDistance: Int,
    /** 候选来源（CUE / RECENT_MENTION / SCENE_ACTIVE …）：可审计"正确角色是谁召回的"。 */
    val sources: List<String> = emptyList(),
)

data class EmbodimentHint(val surface: String, val actingIdentity: String, val stateType: String)

/** Dataset 样本（§5）：provenance 严格保留。 */
data class DirectorSample(
    val sampleId: String,
    val task: String,          // SPEAKER/IDENTITY/VOICE_STATE
    val bookHash: String,
    val input: Map<String, Any?>,
    val target: Map<String, Any?>,
    val provenance: String,    // EXPLICIT_RULE_GOLD/HUMAN_GOLD/LEGACY_V907/LEGACY_V907_HARNESS/TEACHER/UNKNOWN
    val legacyLabel: Boolean = false, // v90.7 输出恒 LEGACY_LABEL（G10）
)

