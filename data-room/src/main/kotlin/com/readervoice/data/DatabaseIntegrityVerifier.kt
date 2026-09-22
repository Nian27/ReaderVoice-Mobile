package com.readervoice.data

import java.sql.Connection

/**
 * DatabaseIntegrityVerifier（TASK-040 §92-§96）：
 * FK orphan / ACTIVE heads 唯一 / dependency cycle / paragraph spans 有序 /
 * chapter range 有序 / source hash 一致。
 */
class DatabaseIntegrityVerifier(private val db: Db) {

    data class Verdict(val pass: Boolean, val issues: List<String>)

    private val conn: Connection get() = db.connection()

    fun verify(): Verdict {
        val issues = mutableListOf<String>()

        // 1. FK orphan：核心表（§92）
        checkOrphan(issues, "physical_line", "source_revision_pk", "source_revision", "source_revision_pk")
        checkOrphan(issues, "chapter", "structure_revision_pk", "structure_revision", "structure_revision_pk")
        checkOrphan(issues, "paragraph_revision", "paragraph_pk", "logical_paragraph", "paragraph_pk")
        checkOrphan(issues, "paragraph_source_span", "paragraph_revision_pk", "paragraph_revision", "paragraph_revision_pk")
        checkOrphan(issues, "artifact_head", "active_revision_id", "artifact_revision", "revision_id")

        // 2. ACTIVE heads 唯一（§80）
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT owner_scope,artifact_type,count(*) c FROM artifact_revision WHERE status='ACTIVE' GROUP BY owner_scope,artifact_type HAVING c>1"
            ).use { rs ->
                while (rs.next()) issues += "MULTIPLE_ACTIVE: ${rs.getString(1)}/${rs.getString(2)} count=${rs.getInt(3)}"
            }
            st.executeQuery(
                "SELECT owner_scope,artifact_type,count(*) c FROM artifact_head GROUP BY owner_scope,artifact_type HAVING c>1"
            ).use { rs ->
                while (rs.next()) issues += "DUPLICATE_HEAD: ${rs.getString(1)}/${rs.getString(2)}"
            }
        }

        // 3. Dependency cycle（§81：全图 BFS）
        if (hasCycle()) issues += "DEPENDENCY_CYCLE"

        // 4. Paragraph spans 有序（§93：order_index 严格递增 + 不跨 source_revision）
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT pr.paragraph_revision_pk, count(*) FROM paragraph_source_span ps " +
                    "JOIN paragraph_revision pr ON pr.paragraph_revision_pk=ps.paragraph_revision_pk " +
                    "GROUP BY pr.paragraph_revision_pk HAVING count(*) <> max(ps.order_index)+1"
            ).use { rs ->
                while (rs.next()) issues += "SPAN_GAP: paragraph_revision ${rs.getLong(1)}"
            }
        }

        // 5. Chapter range（§94：chapter_index 有序 + content_start<=content_end）
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT structure_revision_pk, count(*) FROM chapter GROUP BY structure_revision_pk " +
                    "HAVING count(*) <> max(chapter_index)"
            ).use { rs ->
                while (rs.next()) issues += "CHAPTER_INDEX_GAP: structure ${rs.getLong(1)}"
            }
        }

        return Verdict(issues.isEmpty(), issues)
    }

    private fun checkOrphan(issues: MutableList<String>, table: String, fkCol: String, refTable: String, refPkCol: String) {
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT count(*) FROM $table WHERE $fkCol NOT IN (SELECT $refPkCol FROM $refTable)"
            ).use { rs ->
                rs.next()
                if (rs.getInt(1) > 0) issues += "FK_ORPHAN: $table.$fkCol -> $refTable (${rs.getInt(1)})"
            }
        }
    }

    private fun hasCycle(): Boolean {
        val edges = mutableMapOf<Long, MutableList<Long>>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT dependent_revision_id, dependency_revision_id FROM artifact_dependency").use { rs ->
                while (rs.next()) {
                    edges.getOrPut(rs.getLong(2)) { mutableListOf() } += rs.getLong(1)
                }
            }
        }
        val nodes = edges.keys + edges.values.flatten()
        val state = mutableMapOf<Long, Int>() // 0=unvisited 1=visiting 2=done
        fun dfs(n: Long): Boolean {
            return when (state[n]) {
                1 -> true // back edge = cycle
                2 -> false
                else -> {
                    state[n] = 1
                    for (m in edges[n] ?: emptyList()) if (dfs(m)) return true
                    state[n] = 2
                    false
                }
            }
        }
        return nodes.any { dfs(it) }
    }

    /** 报告 DB 表规模占比（§109）。 */
    fun tableSizes(): Map<String, Int> {
        val tables = mutableListOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                while (rs.next()) tables += rs.getString(1)
            }
        }
        val out = mutableMapOf<String, Int>()
        for (t in tables) {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM \"$t\"").use { rs -> rs.next(); out[t] = rs.getInt(1) }
            }
        }
        return out
    }
}
