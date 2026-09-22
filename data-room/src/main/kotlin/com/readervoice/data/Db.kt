package com.readervoice.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement

/**
 * SQLite 连接管理（TASK-040 §74/§77/§122/§123）。
 * - foreign_keys 永远 ON（§74：不能开发环境关 FK 方便测试）
 * - WAL 评估（§77：后台 compiler 写入 + 前台 reader 查询）
 * - canonical schema 从 database/schema.sql 加载（§123：设计源）
 */
class Db(private val path: String) : AutoCloseable {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            // §77 WAL 评估：JVM reference 单连接场景下默认 rollback journal 更稳
            // （WAL checkpoint 与长事务交互触发 SQLITE_BUSY，3M stress 实测）；
            // Android Room 环境再按实际并发重新评估。
            // 长事务（3M 批量持久化）需要大 busy_timeout
            st.execute("PRAGMA busy_timeout = 30000")
        }
    }

    fun connection(): Connection = conn

    fun createSchema() {
        val ddl = javaClass.classLoader.getResourceAsStream("database/schema.sql")
            ?: throw IllegalStateException("schema.sql missing from resources")
        val sql = ddl.readBytes().toString(Charsets.UTF_8)
        // 逐条执行（按分号拆分，忽略 -- 注释行与 PRAGMA 头部）
        conn.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (statement in splitStatements(sql)) {
                st.execute(statement)
            }
        }
    }

    fun tableCount(): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
            .use { rs -> rs.next(); rs.getInt(1) }
    }

    fun tableNames(): List<String> = conn.createStatement().use { st ->
        st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { rs ->
                val out = mutableListOf<String>()
                while (rs.next()) out += rs.getString(1)
                out
            }
    }

    /** 执行无返回 SQL（Statement 内部关闭——泄漏会触发 sqlite-jdbc BUSY"statements in progress"）。 */
    fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    fun query(sql: String, mapper: (java.sql.ResultSet) -> Unit) {
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> mapper(rs) } }
    }

    private fun splitStatements(sql: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingleQuote = false
        for (line in sql.lines()) {
            if (line.trim().startsWith("--")) continue
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\'') inSingleQuote = !inSingleQuote
                sb.append(c)
                if (c == ';' && !inSingleQuote) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                i++
            }
            sb.append('\n')
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out
    }

    override fun close() {
        conn.close()
    }
}
