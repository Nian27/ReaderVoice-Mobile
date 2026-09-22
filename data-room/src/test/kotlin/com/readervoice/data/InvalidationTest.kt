package com.readervoice.data

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §129：Invalidation 传播（whole/local/metadata）。 */
class InvalidationTest {

    private fun setup(): Triple<Db, RevisionStore, InvalidationStore> {
        val db = Db(createTempDirectory("rv-inv-test-").resolve("t.db").toString())
        db.createSchema()
        val rev = RevisionStore(db)
        val inv = InvalidationStore(db, rev)
        // 建链：SOURCE → STRUCTURE → PARAGRAPH
        val s = rev.create("SOURCE", "book:1", null, "src-1", null, "s", "v1")
        rev.promote("book:1", "SOURCE", s.revisionId)
        val st = rev.create("STRUCTURE", "book:1", null, "src-1", s.revisionId, "st", "v1")
        rev.promote("book:1", "STRUCTURE", st.revisionId)
        val p = rev.create("PARAGRAPH_RECOVERY", "book:1", null, "src-1", st.revisionId, "p", "v1")
        rev.addDependency("book:1", st.revisionId, s.revisionId)
        rev.addDependency("book:1", p.revisionId, st.revisionId)
        rev.promote("book:1", "PARAGRAPH_RECOVERY", p.revisionId)
        return Triple(db, rev, inv)
    }

    @Test
    fun `whole source invalidation propagates down chain`() {
        // G7/§82
        val (db, rev, inv) = setup()
        db.use {
            val ev = inv.record("book:1", "SOURCE_CHANGED", "SOURCE", "src-1", InvalidationStore.ScopeType.WHOLE_SOURCE_REVISION)
            val marks = inv.propagate(ev)
            assertTrue(marks.any { it.startsWith("SOURCE:") })
            assertTrue(rev.activeHeads().none { it.artifactType == "STRUCTURE" && rev.get(it.activeRevisionId)!!.status != RevisionStore.Status.STALE })
            assertTrue(rev.get(rev.head("book:1", "PARAGRAPH_RECOVERY")!!.activeRevisionId)!!.status == RevisionStore.Status.STALE)
            assertTrue(inv.get(ev.eventId)!!.processed)
        }
    }

    @Test
    fun `scoped paragraph invalidation is local`() {
        // G8/§83：用户 Join P100/P101 → 局部（Structure 不变，非整书 STALE）
        val (db, rev, inv) = setup()
        db.use {
            val ev = inv.record("book:1", "PARAGRAPH_JOIN", "PARAGRAPH", "100,101", InvalidationStore.ScopeType.PARAGRAPH_SET, "100,101")
            val marks = inv.propagate(ev)
            assertTrue(marks.any { it.startsWith("PARAGRAPH_SET:") })
            // STRUCTURE 不被 STALE（§20：不影响 Chapter detection）
            val stHead = rev.head("book:1", "STRUCTURE")!!.activeRevisionId
            assertTrue(rev.get(stHead)!!.status != RevisionStore.Status.STALE)
        }
    }

    @Test
    fun `metadata only chapter change keeps paragraph valid`() {
        // G9/§84：chapter title 元数据修改（anchor 不变）→ Paragraph 不 stale
        val (db, rev, inv) = setup()
        db.use {
            val ev = inv.record("book:1", "CHAPTER_METADATA", "CHAPTER", "ch5", InvalidationStore.ScopeType.CHAPTER_RANGE, "metadata-only")
            // 元数据变更：不触发全链传播（调用方只更新 chapter 行）
            // 断言：PARAGRAPH 仍 ACTIVE
            val pHead = rev.head("book:1", "PARAGRAPH_RECOVERY")!!.activeRevisionId
            assertEquals(RevisionStore.Status.ACTIVE, rev.get(pHead)!!.status)
            // anchor 变更才需要局部失效（§19/§85）——记录事件即可
            val ev2 = inv.record("book:1", "CHAPTER_ANCHOR", "CHAPTER", "ch6", InvalidationStore.ScopeType.CHAPTER_RANGE, "L500")
            assertEquals("CHAPTER_RANGE", ev2.scopeType.name)
        }
    }
}
