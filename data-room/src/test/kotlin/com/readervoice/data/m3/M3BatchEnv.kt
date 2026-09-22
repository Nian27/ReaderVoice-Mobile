package com.readervoice.data.m3

import com.readervoice.data.semantic.CandidateSpeaker
import com.readervoice.data.semantic.ContextSegment
import com.readervoice.data.semantic.DirectorContext
import com.readervoice.data.semantic.EvidenceItem
import com.readervoice.data.semantic.SegmentType
import com.readervoice.data.semantic.SemanticSegment
import org.json.JSONArray
import org.json.JSONObject

/**
 * M3 批次评估的**共享环境构造**：从 fixture 记录重建链路所需的上下文。
 * 两个批次（M0 25 段 / G_APP5 前若干段）都用它，避免各写一份。
 */
internal object M3BatchEnv {

    data class Env(
        val ctx: DirectorContext,
        val segment: SemanticSegment,
        val paragraphText: String,
        val candidateIds: Set<String>,
        val evidenceIds: Set<String>,
    )

    fun of(rec: JSONObject): Env {
        val segText = rec.getString("text")
        val start = rec.getInt("source_start")
        val end = rec.getInt("source_end")
        val paragraphId = rec.getLong("paragraph_id")
        val segIdx = rec.getInt("segment_index")
        val segType = when (rec.getString("segment_type").lowercase()) {
            "speech" -> SegmentType.SPEECH
            "inner_monologue" -> SegmentType.INNER_MONOLOGUE
            "group_speech" -> SegmentType.GROUP_SPEECH
            "quote" -> SegmentType.QUOTE
            "narration" -> SegmentType.NARRATION
            else -> SegmentType.UNKNOWN
        }

        val cands = rec.optJSONArray("candidates") ?: JSONArray()
        val candidateSpeakers = (0 until cands.length()).map { k ->
            val o = cands.getJSONObject(k)
            CandidateSpeaker(
                localId = o.getString("id"),
                identityId = "audit-" + o.getString("name"),
                name = o.getString("name"),
                aliases = emptyList(),
                recentTurnDistance = o.optInt("recent_turn_distance", 0),
            )
        }
        val evArr = rec.optJSONArray("evidence") ?: JSONArray()
        val evidence = (0 until evArr.length()).map { k ->
            val o = evArr.getJSONObject(k)
            EvidenceItem(
                o.getString("id"), o.getString("kind"), o.getString("text"),
                if (o.isNull("emotion_hint")) null else o.getString("emotion_hint"),
            )
        }
        val ctx = DirectorContext(
            target = ContextSegment(
                segText, rec.getString("segment_type"), paragraphId,
                segmentId = paragraphId * 1000 + segIdx, segmentIndex = segIdx,
            ),
            recentSegments = (rec.optJSONArray("recent_context") ?: JSONArray()).let { rc ->
                (0 until rc.length()).map { ContextSegment(rc.getString(it), "narration", paragraphId) }
            },
            candidateSpeakers = candidateSpeakers,
            identityConstraints = emptyList(), embodiment = emptyList(), userLocks = emptyList(),
            evidenceItems = evidence,
        )
        val seg = SemanticSegment(
            segmentId = paragraphId * 1000 + segIdx, paragraphRevisionId = paragraphId,
            segmentIndex = segIdx, type = segType, text = segText,
            sourceStart = start, sourceEnd = end, quoteDepth = 0,
        )
        // 重建段落文本以保持 canonical 切片关系（ScriptLineBuilder 会断言）
        val paragraphText = " ".repeat(start) + segText + " ".repeat(8)
        return Env(ctx, seg, paragraphText, candidateSpeakers.map { it.localId }.toSet(), evidence.map { it.id }.toSet())
    }
}
