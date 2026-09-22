package com.readervoice.data

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §129：Schema / Book / SourceRevision / FK / DeletePolicy。 */
class SchemaAndBookTest {

    private fun newDb(): Db {
        val dir = createTempDirectory("rv-db-test-")
        val db = Db(dir.resolve("test.db").toString())
        db.createSchema()
        return db
    }

    @Test
    fun `schema creates tables and reopens`() {
        // G2：create → close → reopen 数据一致
        val dir = createTempDirectory("rv-db-reopen-")
        val path = dir.resolve("test.db").toString()
        Db(path).use { db ->
            db.createSchema()
            assertTrue(db.tableCount() >= 26, "expected 26+ tables, got ${db.tableCount()}")
        }
        Db(path).use { db ->
            assertEquals(27, db.tableCount())
            assertTrue(db.tableNames().contains("artifact_revision"))
        }
    }

    @Test
    fun `book and source revision persist`() {
        val db = newDb()
        db.use {
            val rev = RevisionStore(db)
            val persist = Persister(db, rev)
            val bookPk = persist.persistBook("book-test-1", "测试书")
            val (srcPk, srcRevId) = persist.persistSourceRevision(
                bookPk, "src-1", "/tmp/book.txt", "abc123", 1024, "UTF-8", 1.0,
            )
            assertEquals("SOURCE", rev.get(srcRevId)!!.artifactType)
            assertEquals(RevisionStore.Status.ACTIVE, rev.get(srcRevId)!!.status)
            val head = rev.head("book:$bookPk", "SOURCE")
            assertEquals(srcRevId, head!!.activeRevisionId)
        }
    }

    @Test
    fun `exact duplicate source revision is rejected by unique constraint`() {
        val db = newDb()
        db.use {
            val rev = RevisionStore(db)
            val persist = Persister(db, rev)
            val bookPk = persist.persistBook("book-dup", "书")
            persist.persistSourceRevision(bookPk, "src-1", "/a.txt", "sha-dup", 10, "UTF-8", 1.0)
            assertFailsWith<Exception> {
                persist.persistSourceRevision(bookPk, "src-2", "/b.txt", "sha-dup", 10, "UTF-8", 1.0)
            }
        }
    }

    @Test
    fun `fk rejects orphan insert`() {
        // G3：故意插 orphan 必须失败（FK ON）
        val db = newDb()
        db.use {
            assertFailsWith<Exception> {
                db.exec("INSERT INTO physical_line(source_revision_pk,line_no) VALUES(999,1)")
            }
            assertFailsWith<Exception> {
                db.exec("INSERT INTO artifact_head(owner_scope,artifact_type,active_revision_id) VALUES('x','Y',999)")
            }
        }
    }

    @Test
    fun `core user data delete is restricted`() {
        val db = newDb()
        db.use {
            val rev = RevisionStore(db)
            val persist = Persister(db, rev)
            val bookPk = persist.persistBook("book-del", "书")
            persist.persistSourceRevision(bookPk, "src-1", "/a.txt", "sha1", 10, "UTF-8", 1.0)
            // 删除有 source_revision 引用的 book → RESTRICT 失败
            assertFailsWith<Exception> {
                db.exec("DELETE FROM book WHERE book_pk=$bookPk")
            }
            // 软删除可用（§41）
            db.exec("UPDATE book SET deleted_at=datetime('now') WHERE book_pk=$bookPk")
        }
    }
}
