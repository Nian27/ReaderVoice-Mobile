package com.readervoice.data

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §129：CorrectionEvent / Override / Undo / Lineage / Rebind。 */
class CorrectionTest {

    private fun newDb(): Db {
        val db = Db(createTempDirectory("rv-cor-test-").resolve("t.db").toString())
        db.createSchema()
        return db
    }

    @Test
    fun `correction event is immutable log`() {
        // §34/§35：事件只增不改；用户再改 → 新事件
        val db = newDb()
        db.use {
            val cs = CorrectionStore(db)
            val e1 = cs.recordCorrection("book:1", "src-1", "PARAGRAPH_JOIN", "PARAGRAPH", "P100", oldPayload = "P100", newPayload = "P100+P101")
            val e2 = cs.recordCorrection("book:1", "src-1", "PARAGRAPH_JOIN", "PARAGRAPH", "P100", oldPayload = "P100", newPayload = "P100+P101+P102")
            assertEquals(2, cs.listCorrections("book:1").size)
            assertTrue(e1.correctionId != e2.correctionId)
            assertEquals("P100+P101", e1.newPayload) // 旧事件保留
        }
    }

    @Test
    fun `override priority and lock`() {
        // §39：USER_LOCKED > USER_OVERRIDE；isLocked 查询
        val db = newDb()
        db.use {
            val cs = CorrectionStore(db)
            val locked = cs.compileOverride("book:1", "PARAGRAPH_JOIN", "P100|P101", "join", CorrectionStore.OverridePriority.USER_LOCKED)
            cs.compileOverride("book:1", "PARAGRAPH_JOIN", "P100|P101", "join", CorrectionStore.OverridePriority.USER_OVERRIDE)
            assertTrue(cs.isLocked("book:1", "PARAGRAPH_JOIN", "P100|P101"))
            val active = cs.activeOverrides("book:1")
            // 同 match 旧 ACTIVE → SUPERSEDED；当前 ACTIVE = USER_LOCKED
            assertEquals(1, active.size)
            assertEquals(CorrectionStore.OverridePriority.USER_LOCKED, active[0].priority)
            assertTrue(locked.ruleId != 0L)
        }
    }

    @Test
    fun `undo creates new event and switches override`() {
        // §40：UNDO = 新事件 + head 切换，不删除历史
        val db = newDb()
        db.use {
            val cs = CorrectionStore(db)
            val e = cs.recordCorrection("book:1", null, "PARAGRAPH_JOIN", "PARAGRAPH", "P100", oldPayload = "P100", newPayload = "P100+P101")
            val undo = cs.undo(e.correctionId)
            assertEquals("UNDO:PARAGRAPH_JOIN", undo.type)
            assertEquals("P100+P101", undo.oldPayload) // old/new 对调
            assertEquals(2, cs.listCorrections("book:1").size) // 历史保留
        }
    }

    @Test
    fun `paragraph lineage records merge and split`() {
        // §31/§32：先插 book + source + 3 个 logical_paragraph 再建 lineage（FK RESTRICT）
        val db = newDb()
        db.use {
            db.exec("INSERT INTO book(book_uid,created_at) VALUES('b1','2026-08-12')")
            db.exec("INSERT INTO source_revision(source_revision_uid,book_pk,local_path,sha256,byte_size,imported_at) VALUES('s1',1,'/a','x',1,'2026-08-12')")
            for (i in 1..3) {
                db.exec("INSERT INTO logical_paragraph(paragraph_uid,book_pk,source_revision_pk,created_at) VALUES('p$i',1,1,'2026-08-12')")
            }
            val cs = CorrectionStore(db)
            cs.recordLineage(1, 3, CorrectionStore.LineageType.MERGED_INTO)
            cs.recordLineage(2, 3, CorrectionStore.LineageType.MERGED_INTO)
            val lineage = cs.lineageOf(3)
            assertEquals(2, lineage.size)
            assertTrue(lineage.all { it.type == CorrectionStore.LineageType.MERGED_INTO })
        }
    }

    @Test
    fun `source revision rebind marks override needs rebind`() {
        // §33/§38：跨 SourceRevision 的 override → NEEDS_REBIND
        val db = newDb()
        db.use {
            val cs = CorrectionStore(db)
            val rule = cs.compileOverride("book:1", "PARAGRAPH_JOIN", "P100|P101", "join", CorrectionStore.OverridePriority.USER_LOCKED)
            cs.markNeedsRebind(rule.ruleId)
            assertEquals(CorrectionStore.OverrideStatus.NEEDS_REBIND, cs.getOverride(rule.ruleId)!!.status)
        }
    }
}
