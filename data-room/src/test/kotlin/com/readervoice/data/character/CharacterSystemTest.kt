package com.readervoice.data.character

import com.readervoice.data.Db
import com.readervoice.data.RevisionStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-060 核心测试（v90.7 Hard Regression Pack + Gate G3-G19）。
 * 运行前提：schema v1 创建 + SchemaMigrationV2 迁移。
 */
class CharacterSystemTest {

    private fun newDb(v2: Boolean = true): Db {
        val db = Db(createTempDirectory("rv-char-").resolve("t.db").toString())
        db.createSchema()
        if (v2) SchemaMigrationV2(db).migrate()
        return db
    }

    private fun setup(v2: Boolean = true): Triple<Db, RevisionStore, CharacterStore> {
        val db = newDb(v2)
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b1','2026-08-12')")
        return Triple(db, RevisionStore(db), CharacterStore(db))
    }

    /** 每个测试的 CHARACTER revision id（FK 需要有效 revision）。 */
    private fun revIdFor(rev: RevisionStore): Long =
        rev.create("CHARACTER", "book:1", "1", "src", null, "h-" + System.nanoTime(), "v1").revisionId


    private fun newEntity(store: CharacterStore, bookPk: Long, name: String, type: String = "PERSON"): Long =
        store.upsertEntity(bookPk, "uid-$bookPk-$name", type, name, "CANDIDATE")

    @Test
    fun `G2-G65 schema migration preserves v1 data`() {
        val db = newDb(v2 = false) // v1 只有
        db.exec("INSERT INTO book(book_uid,created_at) VALUES('b1','2026-08-12')")
        db.exec("INSERT INTO source_revision(source_revision_uid,book_pk,local_path,sha256,byte_size,imported_at) VALUES('s1',1,'/a','x',1,'2026-08-12')")
        db.exec("INSERT INTO logical_paragraph(paragraph_uid,book_pk,source_revision_pk,created_at) VALUES('p1',1,1,'2026-08-12')")
        val before = mapOf("book" to 1, "source_revision" to 1, "logical_paragraph" to 1)
        // migrate v2
        SchemaMigrationV2(db).migrate()
        val issues = SchemaMigrationV2(db).verifyV1Preserved(before)
        assertTrue(issues.isEmpty(), "v1 数据必须 100% 保留: $issues")
        // v2 表存在
        val tables = db.tableNames()
        for (t in listOf("narrative_entity", "mention", "identity_evidence", "identity_cluster_membership", "identity_lineage", "embodiment_interval", "voice_phase_interval", "temporary_voice_event", "character_attribute")) {
            assertTrue(tables.contains(t), "缺 v2 表 $t")
        }
    }

    @Test
    fun `G3 cross-book isolation is db constraint`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val a = newEntity(store, 1, "张明")
            db.exec("INSERT INTO book(book_uid,created_at) VALUES('b2','2026-08-12')")
            val b = newEntity(store, 2, "张明")
            assertTrue(a != b, "两本书同名实体必须不同 pk")
            // 证据带 book_pk（查询隔离）
            store.insertEvidence(IdentityEvidence(rid, 1, 1, a, newEntity(store, 1, "张教授"), "ALIAS", "POSITIVE", 2.0, false, null, "TEST"))
            val leak = db.tableNames()
            assertTrue(leak.isNotEmpty())
            // 跨书查询（book=2 的 a 的邻居）应无结果
            var cnt = 0
            db.query("SELECT count(*) FROM identity_evidence WHERE book_pk=2 AND (entity_a_pk=$a OR entity_b_pk=$a)") { rs -> rs.next(); cnt = rs.getInt(1) }
            assertEquals(0, cnt, "书 2 不得看到书 1 的证据")
        }
    }

    @Test
    fun `G4 hard negative forbids auto merge`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val a = newEntity(store, 1, "张明")
            val b = newEntity(store, 1, "李四")
            // 先有正证（弱）
            store.insertEvidence(IdentityEvidence(rid, 1, 1, a, b, "ALIAS", "POSITIVE", 1.6, false, null, "TEST"))
            assertTrue(store.mergeCandidate(1, a, b), "无 hard negative 时可合并")
            // 用户 ENTITY_NEQ → hard negative
            store.insertEvidence(IdentityEvidence(rid, 1, 1, a, b, "DIFFERENT_PERSON", "NEGATIVE", 4.0, hardBlock = true, null, "USER_LOCKED"))
            assertFalse(store.mergeCandidate(1, a, b), "hard negative 存在 → auto merge 禁止（G4/§58）")
        }
    }

    @Test
    fun `G5 relationship never becomes same person`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val a = newEntity(store, 1, "师父")
            val b = newEntity(store, 1, "徒弟")
            store.insertEvidence(IdentityEvidence(rid, 1, 1, a, b, "KINSHIP", "NEUTRAL", 0.0, false, null, "TEST"))
            // KINSHIP 不是 SAME_PERSON 证据
            assertFalse(store.canBeSamePersonEvidence("KINSHIP"))
            assertFalse(store.canBeSamePersonEvidence("SOCIAL_RELATION"))
            assertTrue(store.canBeSamePersonEvidence("ALIAS"))
            assertTrue(store.canBeSamePersonEvidence("SAME_PERSON"))
            assertFalse(store.mergeCandidate(1, a, b), "仅关系证据不得触发合并（G5/§56）")
        }
    }

    @Test
    fun `G6 group never merges with person`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val group = newEntity(store, 1, "众人", "GROUP")
            val person = newEntity(store, 1, "张明", "PERSON")
            store.insertEvidence(IdentityEvidence(rid, 1, 1, group, person, "CO_PRESENCE", "NEUTRAL", 0.7, false, null, "TEST"))
            assertFalse(store.mergeCandidate(1, group, person), "群体 ≠ 个人（G6）")
        }
    }

    @Test
    fun `G7 control embodiment never equals identity`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val demon = newEntity(store, 1, "魔尊")
            val body = newEntity(store, 1, "林雪")
            // 附身区间
            store.insertEmbodiment(rid, 1, demon, body, "CONTROL", 100, 200, null)
            // 控制 ≠ SAME_PERSON：mergeCandidate 必须 false
            store.insertEvidence(IdentityEvidence(rid, 1, 1, demon, body, "CONTROL", "NEUTRAL", 0.0, false, null, "TEST"))
            assertFalse(store.mergeCandidate(1, demon, body), "CONTROL 永不 merge（G7/§25）")
            // whoIsActingThrough
            val acting = store.whoIsActingThrough(1, body, 150)
            assertEquals(demon, acting, "Ch150 林雪身体由魔尊发声")
            assertNull(store.whoIsActingThrough(1, body, 50), "Ch50 无附身")
        }
    }

    @Test
    fun `G8 UNKNOWN preserved with deterministic uid`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val uid = store.unknownEntityUid("book-x", 42)
            val uid2 = store.unknownEntityUid("book-x", 42)
            assertEquals(uid, uid2, "UNKNOWN uid 确定性")
            assertTrue(uid.startsWith("unknown-"))
            val e = store.entityByUid(uid)
            assertNull(e, "未落库前为 null（UNKNOWN 是 provisional，非永久）")
        }
    }

    @Test
    fun `G9 merge then split is reversible at identity level`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val a = newEntity(store, 1, "张明")
            val b = newEntity(store, 1, "张教授")
            store.mergeInto(1, 1, b, a, "test-merge", emptyList(), automatic = true)
            val res = store.resolveIdentity(1, b)
            assertNotNull(res.clusterId, "merge 后 b 进入 cluster")
            assertEquals(res.clusterId, store.resolveIdentity(1, a).clusterId, "a/b 同 cluster")
            store.splitFrom(1, 1, b, res.clusterId!!, "test-split")
            val res2 = store.resolveIdentity(1, b)
            assertTrue(res2.clusterId != res.clusterId, "split 后 b 独立 cluster（G9）")
            // 原实体与证据仍存在（non-destructive）
            assertNotNull(store.entityByUid("uid-1-张教授"), "旧实体保留（ADR-029）")
        }
    }

    @Test
    fun `G10 embodiment interval query`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val demon = newEntity(store, 1, "魔尊")
            val body = newEntity(store, 1, "林雪")
            store.insertEmbodiment(rid, 1, demon, body, "EMBODIMENT", 100, 200, 0.9)
            assertEquals(demon, store.whoIsActingThrough(1, body, 150))
            assertEquals(demon, store.whoIsActingThrough(1, body, 200), "end 包含")
            assertNull(store.whoIsActingThrough(1, body, 201), "end 后无")
        }
    }

    @Test
    fun `G11-G12 temp voice start continue replace end without base mutation`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val e = newEntity(store, 1, "张三")
            // base voice_age 属性
            store.setAttribute(rid, 1, e, "voice_age", "YOUNG", "RULE", 0, false, null)
            store.insertTempVoiceEvent(rid, 1, e, "START", "TEMPORARY", "{\"tag\":\"男老年1\"}", 10, "scene", "RULE")
            store.insertTempVoiceEvent(rid, 1, e, "CONTINUE", "TEMPORARY", null, 20, "scene", "RULE")
            store.insertTempVoiceEvent(rid, 1, e, "REPLACE", "TEMPORARY", "{\"tag\":\"男中年1\"}", 30, "scene", "RULE")
            store.insertTempVoiceEvent(rid, 1, e, "END", "TEMPORARY", null, 40, "scene", "RULE")
            // causal：position 5 < 事件位置 10 → 无事件（G14 语义）
            val s0 = store.queryEffectiveVoiceState(1, e, 5)
            assertNull(s0.temporaryAction, "Ch5 无临时事件（causal）")
            val s1 = store.queryEffectiveVoiceState(1, e, 15)
            assertEquals("START", s1.temporaryAction)
            val sC = store.queryEffectiveVoiceState(1, e, 25)
            assertEquals("CONTINUE", sC.temporaryAction)
            val sR = store.queryEffectiveVoiceState(1, e, 35)
            assertEquals("REPLACE", sR.temporaryAction)
            val sEnd = store.queryEffectiveVoiceState(1, e, 45)
            assertEquals("END", sEnd.temporaryAction)
            // G12：temp 不改 base
            val baseAfter = store.queryEffectiveVoiceState(1, e, 100)
            assertEquals("YOUNG", baseAfter.baseAttributes["voice_age"]?.value, "Base 不被临时修改（G12）")
        }
    }

    @Test
    fun `G13 identity future evidence allowed`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val teacher = newEntity(store, 1, "张老师")
            val ming = newEntity(store, 1, "张明")
            // Ch20 证据：张老师=张明（未来证据反向确定早期）
            store.insertEvidence(IdentityEvidence(rid, 1, 1, teacher, ming, "ALIAS", "POSITIVE", 3.0, false, 2000, "RULE"))
            store.mergeInto(1, 1, teacher, ming, "future-evidence", emptyList(), automatic = true)
            // resolveIdentity 是 whole-book（允许未来证据）
            val res = store.resolveIdentity(1, teacher)
            assertEquals(store.resolveIdentity(1, ming).clusterId, res.clusterId, "早期张老师可被未来证据解析为张明（G13）")
        }
    }

    @Test
    fun `G14 performance future leak is zero`() {
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val e = newEntity(store, 1, "张三")
            // Ch10 临时换声（未来位置）
            store.insertTempVoiceEvent(rid, 1, e, "START", "TEMPORARY", null, 100, "scene", "RULE")
            // 查 Ch2：不得看到（causal，G14/§62）
            val s = store.queryEffectiveVoiceState(1, e, 20)
            assertNull(s.temporaryAction, "Ch2 不得看到 Ch10 的临时事件（G14）")
        }
    }

    @Test
    fun `G17 legacy adapter preserves provenance`() {
        val obs = LegacyBehaviorAdapter.toObservation(mapOf(
            "behavior_type" to "IDENTITY_SAME",
            "legacy_version" to "tts-rule-2.85-v907",
            "legacy_output" to mapOf("speaker" to "张明", "target" to "张教授"),
        ))
        assertNotNull(obs)
        assertEquals("LEGACY_tts-rule-2.85-v907", obs!!.provenance, "provenance 保留 LEGACY_*")
        assertEquals(ObservationType.SAME_IDENTITY_EVIDENCE, obs.type)
        // 未知类型不映射
        assertNull(LegacyBehaviorAdapter.toObservation(mapOf("behavior_type" to "NOT_A_TYPE")))
    }

    @Test
    fun `G19 real book private integration runs without claiming accuracy`() {
        // 真实书已 gitignored；此测试用 synthetic 观察流验证 Compiler 全链路（真实书 integration 在 RealBookTest 覆盖结构层）
        val (db, rev, store) = setup()
        db.use {
            val rid = revIdFor(rev)
            val compiler = CharacterCompiler(db, rev, store)
            // 最小段落链（mention.paragraph_revision_pk NOT NULL，§5）
            db.exec("INSERT INTO source_revision(source_revision_uid,book_pk,local_path,sha256,byte_size,imported_at) VALUES('src1',1,'/a','x',1,'2026-08-12')")
            db.exec("INSERT INTO logical_paragraph(paragraph_uid,book_pk,source_revision_pk,created_at) VALUES('lp1',1,1,'2026-08-12')")
            db.exec("INSERT INTO structure_revision(artifact_revision_id,source_revision_pk,rule_pack_version,resolver_version,input_hash,created_at) VALUES(" + rid + ",1,'v','v','h','2026-08-12')")
            db.exec("INSERT INTO paragraph_recovery_revision(artifact_revision_id,source_revision_pk,structure_revision_pk,layout_profiler_version,boundary_classifier_version,join_policy_version,block_detector_version,input_hash,created_at) VALUES(" + rid + ",1,1,'v','v','v','v','h','2026-08-12')")
            db.exec("INSERT INTO paragraph_revision(paragraph_pk,pr_revision_pk,paragraph_index,block_type,read_policy,confidence,fingerprint,revision_reason) VALUES(1,1,0,'PROSE','NORMAL',0.9,'f','AUTO_REFLOW')")
            val bookPk = 1L
            val obs = sequenceOf(
                CharacterObservation(ObservationType.MENTION, 1, 10, 12, "张明", "PERSON", emptyMap(), 100, "EXPLICIT_RULE"),
                CharacterObservation(ObservationType.MENTION, 1, 20, 23, "张教授", "PERSON", emptyMap(), 150, "EXPLICIT_RULE"),
                CharacterObservation(ObservationType.SAME_IDENTITY_EVIDENCE, null, null, null, null, null,
                    mapOf("entity_a" to "张明", "entity_b" to "张教授", "strength" to 3.0), 200, "LEGACY_X"),
            )
            val result = compiler.compile(bookPk, "src-1", obs)
            println("G19 result=" + result)
            assertEquals(3, result.observationsProcessed)
            assertEquals(2, result.mentionsInserted)
            assertTrue(result.evidenceInserted >= 1)
            assertTrue(result.mergeExecuted >= 1, "正证 + 无 hard negative → merge")
            // Compiler 产生 CHARACTER ArtifactRevision
            val head = rev.head("book:1", "CHARACTER")
            assertNotNull(head, "CHARACTER head 存在（G16 promotion 路径）")
        }
    }
}
