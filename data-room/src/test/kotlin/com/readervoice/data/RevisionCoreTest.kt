package com.readervoice.data

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §129：Revision promotion / 回滚 / head 唯一 / DAG / cycle / idempotent / snapshot。 */
class RevisionCoreTest {

    private fun newStore(): Pair<Db, RevisionStore> {
        val db = Db(createTempDirectory("rv-rev-test-").resolve("t.db").toString())
        db.createSchema()
        return db to RevisionStore(db)
    }

    @Test
    fun `promotion switches head atomically`() {
        // §79：R1 ACTIVE → R2 BUILDING → 事务后 R1 SUPERSEDED / R2 ACTIVE / HEAD=R2
        val (db, rev) = newStore()
        db.use {
            val r1 = rev.create("STRUCTURE", "book:1", null, null, null, "h1", "v1")
            rev.promote("book:1", "STRUCTURE", r1.revisionId)
            val r2 = rev.create("STRUCTURE", "book:1", null, null, r1.revisionId, "h2", "v1")
            assertEquals(RevisionStore.Status.BUILDING, rev.get(r2.revisionId)!!.status)
            rev.promote("book:1", "STRUCTURE", r2.revisionId)
            assertEquals(RevisionStore.Status.SUPERSEDED, rev.get(r1.revisionId)!!.status)
            assertEquals(RevisionStore.Status.ACTIVE, rev.get(r2.revisionId)!!.status)
            assertEquals(r2.revisionId, rev.head("book:1", "STRUCTURE")!!.activeRevisionId)
        }
    }

    @Test
    fun `failed promotion keeps old active`() {
        // G5/§78：R2 失败（模拟中途异常）→ R1 仍 ACTIVE，R2=FAILED
        val (db, rev) = newStore()
        db.use {
            val r1 = rev.create("PARAGRAPH_RECOVERY", "book:1", null, null, null, "h1", "v1")
            rev.promote("book:1", "PARAGRAPH_RECOVERY", r1.revisionId)
            val r2 = rev.create("PARAGRAPH_RECOVERY", "book:1", null, null, r1.revisionId, "h2", "v1")
            // 模拟失败：直接标记 FAILED（不 promote）
            rev.markFailed(r2.revisionId, "simulated failure at 50%")
            assertEquals(RevisionStore.Status.ACTIVE, rev.get(r1.revisionId)!!.status)
            assertEquals(RevisionStore.Status.FAILED, rev.get(r2.revisionId)!!.status)
            assertEquals(r1.revisionId, rev.head("book:1", "PARAGRAPH_RECOVERY")!!.activeRevisionId)
        }
    }

    @Test
    fun `promote requires building status`() {
        val (db, rev) = newStore()
        db.use {
            val r1 = rev.create("STRUCTURE", "book:1", null, null, null, "h1", "v1")
            rev.promote("book:1", "STRUCTURE", r1.revisionId)
            // 再次 promote 已 ACTIVE 的 → 拒绝
            assertFailsWithIllegal { rev.promote("book:1", "STRUCTURE", r1.revisionId) }
        }
    }

    @Test
    fun `single active head per owner and type`() {
        // G4/§80
        val (db, rev) = newStore()
        db.use {
            val r1 = rev.create("STRUCTURE", "book:1", null, null, null, "h1", "v1")
            val r2 = rev.create("STRUCTURE", "book:1", null, null, null, "h2", "v1")
            rev.promote("book:1", "STRUCTURE", r1.revisionId)
            rev.promote("book:1", "STRUCTURE", r2.revisionId)
            val active = rev.activeHeads().filter { it.artifactType == "STRUCTURE" }
            assertEquals(1, active.size)
            // DB 层验证唯一 ACTIVE
            db.query("SELECT count(*) FROM artifact_revision WHERE owner_scope='book:1' AND artifact_type='STRUCTURE' AND status='ACTIVE'") { rs ->
                rs.next(); assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `dependency dag and cycle rejection`() {
        // G6/§81
        val (db, rev) = newStore()
        db.use {
            val s = rev.create("SOURCE", "book:1", null, null, null, "s", "v1")
            val st = rev.create("STRUCTURE", "book:1", null, null, s.revisionId, "st", "v1")
            val p = rev.create("PARAGRAPH_RECOVERY", "book:1", null, null, st.revisionId, "p", "v1")
            assertTrue(rev.addDependency("book:1", st.revisionId, s.revisionId))
            assertTrue(rev.addDependency("book:1", p.revisionId, st.revisionId))
            // cycle：s → p 会成环（p 依赖 st 依赖 s）
            assertFalse(rev.addDependency("book:1", s.revisionId, p.revisionId), "cycle must be rejected")
            // 直接成环：p → p
            assertFalse(rev.addDependency("book:1", p.revisionId, p.revisionId))
        }
    }

    @Test
    fun `idempotent build reuses revision`() {
        // §86：同 input_hash+algorithm → 不创建重复 revision
        val (db, rev) = newStore()
        db.use {
            val r1 = rev.create("STRUCTURE", "book:1", null, null, null, "hash-X", "v1")
            rev.promote("book:1", "STRUCTURE", r1.revisionId)
            val r2 = rev.create("STRUCTURE", "book:1", null, null, null, "hash-X", "v1")
            assertEquals(r1.revisionId, r2.revisionId, "identical build must reuse")
        }
    }

    @Test
    fun `snapshot reads consistent heads`() {
        val (db, rev) = newStore()
        db.use {
            val s = rev.create("SOURCE", "book:9", null, "src-9", null, "s", "v1")
            rev.promote("book:9", "SOURCE", s.revisionId)
            val st = rev.create("STRUCTURE", "book:9", null, "src-9", s.revisionId, "st", "v1")
            rev.promote("book:9", "STRUCTURE", st.revisionId)
            val snap = rev.snapshot("book:9")
            assertEquals("src-9", snap.sourceRevisionId)
            assertEquals(st.revisionId, snap.structureRevisionId)
        }
    }

    @Test
    fun `crash recovery marks interrupted builds failed`() {
        // G13/§114：BUILDING 无 RUNNING session → FAILED_INTERRUPTED
        val (db, rev) = newStore()
        db.use {
            val r1 = rev.create("STRUCTURE", "book:1", null, null, null, "h1", "v1")
            rev.promote("book:1", "STRUCTURE", r1.revisionId)
            val r2 = rev.create("STRUCTURE", "book:1", null, null, null, "h2", "v1") // BUILDING，无 session
            val recovered = rev.recoverInterruptedBuilds()
            assertTrue(recovered.any { it.first == r2.revisionId })
            assertEquals(RevisionStore.Status.FAILED, rev.get(r2.revisionId)!!.status)
            assertEquals(RevisionStore.Status.ACTIVE, rev.get(r1.revisionId)!!.status) // 旧 ACTIVE 不变
            // 有 RUNNING session 的 BUILDING 不恢复
            val r3 = rev.create("STRUCTURE", "book:1", null, null, null, "h3", "v1")
            rev.startSession("STRUCTURE", r3.revisionId)
            val recovered2 = rev.recoverInterruptedBuilds()
            assertTrue(recovered2.none { it.first == r3.revisionId })
        }
    }

    @Test
    fun `downstream stale propagation`() {
        // §16/§82：SOURCE stale → STRUCTURE STALE → PARAGRAPH STALE
        val (db, rev) = newStore()
        db.use {
            val s = rev.create("SOURCE", "book:1", null, null, null, "s", "v1")
            val st = rev.create("STRUCTURE", "book:1", null, null, s.revisionId, "st", "v1")
            val p = rev.create("PARAGRAPH_RECOVERY", "book:1", null, null, st.revisionId, "p", "v1")
            rev.addDependency("book:1", st.revisionId, s.revisionId)
            rev.addDependency("book:1", p.revisionId, st.revisionId)
            rev.markDownstreamStale("book:1", s.revisionId)
            assertEquals(RevisionStore.Status.STALE, rev.get(st.revisionId)!!.status)
            assertEquals(RevisionStore.Status.STALE, rev.get(p.revisionId)!!.status)
        }
    }

    private fun assertFailsWithIllegal(block: () -> Unit) {
        try { block(); throw AssertionError("expected exception") } catch (e: IllegalArgumentException) { /* ok */ }
    }
}
