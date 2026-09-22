package com.readervoice.data.character

import com.readervoice.data.Db

/**
 * Schema v1 → v2 Migration（TASK-060 §3/§65）。
 * v2 = v1 全部表 + character 表族（schema.v2.sql 增量）。
 * 迁移测试：v1 seed（Book/Source/Chapter/Paragraph/Correction 数据）→ migrate → 数据 100% 保留。
 */
class SchemaMigrationV2(private val db: Db) {

    fun migrate() {
        val ddl = javaClass.classLoader.getResourceAsStream("database/schema.v2.sql")
            ?: throw IllegalStateException("schema.v2.sql missing")
        val sql = ddl.readBytes().toString(Charsets.UTF_8)
        val conn = db.connection()
        conn.autoCommit = false
        try {
            for (stmt in splitStatements(sql)) {
                conn.createStatement().use { it.execute(stmt) }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** v1 表完好性（迁移后校验）：核心表行数不变。 */
    fun verifyV1Preserved(before: Map<String, Int>): List<String> {
        val issues = mutableListOf<String>()
        for ((table, count) in before) {
            var now = -1
            db.query("SELECT count(*) FROM $table") { rs -> rs.next(); now = rs.getInt(1) }
            if (now != count) issues += "table $table: before=$count after=$now"
        }
        return issues
    }

    private fun splitStatements(sql: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (line in sql.lines()) {
            if (line.trim().startsWith("--")) continue
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\'') inQuote = !inQuote
                sb.append(c)
                if (c == ';' && !inQuote) { out += sb.toString(); sb.setLength(0) }
                i++
            }
            sb.append('\n')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }
}
