package com.readervoice.data.snapshot

import com.readervoice.data.character.CharacterAttribute
import com.readervoice.data.character.CharacterReadStore
import com.readervoice.data.character.EffectiveVoiceState
import com.readervoice.data.character.IdentityResolution
import com.readervoice.data.character.NarrativeEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * MOBILE-005 / M2：**只读调用的记录 / 回放**（G2 对拍机制）。
 *
 * 为什么要这种机制（而不是"设备端也连数据库"）：
 *  - 设备端没有 sqlite-jdbc，也不该连桌面库；G2 要测的是【语义层在 Android 上行为是否一致】，
 *    不是"数据库连接是否一致"。
 *  - 用记录代理包住真实 JDBC store 跑一遍语义管线，把 (方法, 参数) → 结果 全量落盘；
 *    设备端用回放 store 提供**同样的答案**。覆盖面由构造决定（凡是管线调用过的都记下了），
 *    不靠人工枚举，不会漏。
 *  - 回放 store 对**缺键 fail-closed**（抛异常），否则会把"依赖没喂到"伪装成"结果不同"。
 */

private fun entToJson(e: NarrativeEntity): JSONObject = JSONObject()
    .put("entityPk", e.entityPk).put("entityUid", e.entityUid).put("bookPk", e.bookPk)
    .put("entityType", e.entityType).put("canonicalName", e.canonicalName ?: JSONObject.NULL)
    .put("status", e.status)

private fun entFromJson(o: JSONObject): NarrativeEntity = NarrativeEntity(
    entityPk = o.getLong("entityPk"), entityUid = o.getString("entityUid"), bookPk = o.getLong("bookPk"),
    entityType = o.getString("entityType"),
    canonicalName = if (o.isNull("canonicalName")) null else o.getString("canonicalName"),
    status = o.getString("status"),
)

private fun attrToJson(a: CharacterAttribute): JSONObject = JSONObject()
    .put("attributeId", a.attributeId).put("characterRevisionId", a.characterRevisionId)
    .put("bookPk", a.bookPk).put("entityPk", a.entityPk).put("attribute", a.attribute)
    .put("value", a.value).put("confidence", a.confidence ?: JSONObject.NULL)
    .put("source", a.source)
    .put("narrativePosition", a.narrativePosition ?: JSONObject.NULL)
    .put("userOverride", a.userOverride)

private fun attrFromJson(o: JSONObject): CharacterAttribute = CharacterAttribute(
    attributeId = o.getLong("attributeId"), characterRevisionId = o.getLong("characterRevisionId"),
    bookPk = o.getLong("bookPk"), entityPk = o.getLong("entityPk"), attribute = o.getString("attribute"),
    value = o.getString("value"),
    confidence = if (o.isNull("confidence")) null else o.getDouble("confidence"),
    source = o.getString("source"),
    narrativePosition = if (o.isNull("narrativePosition")) null else o.getLong("narrativePosition"),
    userOverride = o.getBoolean("userOverride"),
)

private fun vsToJson(v: EffectiveVoiceState): JSONObject {
    val attrs = JSONObject()
    v.baseAttributes.forEach { (k, a) -> attrs.put(k, attrToJson(a)) }
    return JSONObject().put("entityPk", v.entityPk)
        .put("phase", v.phase ?: JSONObject.NULL)
        .put("temporaryAction", v.temporaryAction ?: JSONObject.NULL)
        .put("temporaryPayload", v.temporaryPayload ?: JSONObject.NULL)
        .put("baseAttributes", attrs)
}

private fun vsFromJson(o: JSONObject): EffectiveVoiceState {
    val attrs = LinkedHashMap<String, CharacterAttribute>()
    val ja = o.getJSONObject("baseAttributes")
    for (k in ja.keys()) attrs[k] = attrFromJson(ja.getJSONObject(k))
    return EffectiveVoiceState(
        entityPk = o.getLong("entityPk"),
        phase = if (o.isNull("phase")) null else o.getString("phase"),
        temporaryAction = if (o.isNull("temporaryAction")) null else o.getString("temporaryAction"),
        temporaryPayload = if (o.isNull("temporaryPayload")) null else o.getString("temporaryPayload"),
        baseAttributes = attrs,
    )
}

/** 记录代理：包住真实 store，把语义管线的每一次只读调用落进 trace。 */
class RecordingCharacterStore(private val delegate: CharacterReadStore) : CharacterReadStore {
    val trace = JSONObject()
    var calls = 0
        private set

    private fun rec(key: String, value: Any?) {
        calls++
        trace.put(key, value ?: JSONObject.NULL)
    }

    override fun entityByPk(entityPk: Long): NarrativeEntity? =
        delegate.entityByPk(entityPk).also { rec("entityByPk|$entityPk", it?.let(::entToJson)) }

    override fun canonicalNamesOf(bookPk: Long): Set<String> =
        delegate.canonicalNamesOf(bookPk).also { rec("canonicalNamesOf|$bookPk", JSONArray(it.toList())) }


    override fun entityByCanonicalName(bookPk: Long, name: String): NarrativeEntity? =
        delegate.entityByCanonicalName(bookPk, name).also { rec("entityByCanonicalName|$bookPk|$name", it?.let(::entToJson)) }

    override fun aliasesOf(bookPk: Long, clusterId: String): List<String> =
        delegate.aliasesOf(bookPk, clusterId).also { list ->
            rec("aliasesOf|$bookPk|$clusterId", JSONArray(list))
        }

    override fun resolveIdentity(bookPk: Long, entityPk: Long): IdentityResolution =
        delegate.resolveIdentity(bookPk, entityPk).also {
            rec("resolveIdentity|$bookPk|$entityPk",
                JSONObject().put("entityPk", it.entityPk).put("clusterId", it.clusterId ?: JSONObject.NULL).put("status", it.status))
        }

    override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long): EffectiveVoiceState =
        delegate.queryEffectiveVoiceState(bookPk, entityPk, position).also {
            rec("queryEffectiveVoiceState|$bookPk|$entityPk|$position", vsToJson(it))
        }

    override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? =
        delegate.whoIsActingThrough(bookPk, bodyEntityPk, position).also {
            rec("whoIsActingThrough|$bookPk|$bodyEntityPk|$position", it)
        }

    override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? =
        delegate.actingThroughWithState(bookPk, bodyEntityPk, position).also {
            rec("actingThroughWithState|$bookPk|$bodyEntityPk|$position", it?.let { p -> JSONArray().put(p.first).put(p.second) })
        }

    override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean =
        delegate.hasHardNegative(bookPk, entityAPk, entityBPk).also {
            rec("hasHardNegative|$bookPk|$entityAPk|$entityBPk", it)
        }

    override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int> =
        delegate.evidenceSummary(bookPk, entityAPk, entityBPk).also { m ->
            val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
            rec("evidenceSummary|$bookPk|$entityAPk|$entityBPk", o)
        }

    fun traceJson(): String = trace.toString()
}

/** 回放 store：按 key 返回记录的结果；缺键 fail-closed。 */
class TraceCharacterStore(traceJson: String) : CharacterReadStore {
    private val trace = JSONObject(traceJson)
    private fun raw(key: String): Any {
        check(trace.has(key)) { "M2 trace missing key: $key" }
        return trace.get(key)
    }

    private fun ent(key: String): NarrativeEntity? {
        val v = raw(key)
        return if (v == JSONObject.NULL) null else entFromJson(v as JSONObject)
    }

    override fun entityByPk(entityPk: Long) = ent("entityByPk|$entityPk")
    override fun canonicalNamesOf(bookPk: Long): Set<String> {
        val a = raw("canonicalNamesOf|$bookPk") as JSONArray
        return (0 until a.length()).map { a.getString(it) }.toCollection(LinkedHashSet())
    }

    override fun entityByCanonicalName(bookPk: Long, name: String) = ent("entityByCanonicalName|$bookPk|$name")

    override fun aliasesOf(bookPk: Long, clusterId: String): List<String> {
        val a = raw("aliasesOf|$bookPk|$clusterId") as JSONArray
        return (0 until a.length()).map { a.getString(it) }
    }

    override fun resolveIdentity(bookPk: Long, entityPk: Long): IdentityResolution {
        val o = raw("resolveIdentity|$bookPk|$entityPk") as JSONObject
        return IdentityResolution(o.getLong("entityPk"), if (o.isNull("clusterId")) null else o.getString("clusterId"), o.getString("status"))
    }

    override fun queryEffectiveVoiceState(bookPk: Long, entityPk: Long, position: Long): EffectiveVoiceState =
        vsFromJson(raw("queryEffectiveVoiceState|$bookPk|$entityPk|$position") as JSONObject)

    override fun whoIsActingThrough(bookPk: Long, bodyEntityPk: Long, position: Long): Long? {
        val v = raw("whoIsActingThrough|$bookPk|$bodyEntityPk|$position")
        return if (v == JSONObject.NULL) null else (v as Number).toLong()
    }

    override fun actingThroughWithState(bookPk: Long, bodyEntityPk: Long, position: Long): Pair<Long, String>? {
        val v = raw("actingThroughWithState|$bookPk|$bodyEntityPk|$position")
        if (v == JSONObject.NULL) return null
        val a = v as JSONArray
        return a.getLong(0) to a.getString(1)
    }

    override fun hasHardNegative(bookPk: Long, entityAPk: Long, entityBPk: Long): Boolean =
        raw("hasHardNegative|$bookPk|$entityAPk|$entityBPk") as Boolean

    override fun evidenceSummary(bookPk: Long, entityAPk: Long, entityBPk: Long): Map<String, Int> {
        val o = raw("evidenceSummary|$bookPk|$entityAPk|$entityBPk") as JSONObject
        val out = LinkedHashMap<String, Int>()
        for (k in o.keys()) out[k] = o.getInt(k)
        return out
    }
}

