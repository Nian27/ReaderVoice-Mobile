package com.readervoice.scheduler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** AGENTS ADR-025/G11：核心持久化表禁止 INSERT OR REPLACE */
class SchemaRedLineTest {

    @Test
    fun schemaHasNoInsertOrReplace() {
        val db = SchedulerDb(TestSupport.tempDbPath())
        try {
            val sql = db.schemaSql.uppercase()
            assertFalse(sql.contains("INSERT OR REPLACE"), "schema must not use INSERT OR REPLACE")
        } finally {
            db.close()
        }
    }

    private fun assertTrue(cond: Boolean, msg: String) {
        if (!cond) throw AssertionError(msg)
    }
}
