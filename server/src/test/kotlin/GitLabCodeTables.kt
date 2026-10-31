package ch.nokillswit

/** The eleven GitLab tables (six raw, five `norm.code_*`) by name, with per-connection row counts over a plain JDBC connection. */
internal object GitLabCodeTables {
    val RAW = listOf(
        "raw.gitlab_projects", "raw.gitlab_users", "raw.gitlab_commits", "raw.gitlab_commit_files", "raw.gitlab_merge_requests",
        "raw.gitlab_mr_events",
    )
    val NORM = listOf(
        "norm.code_projects", "norm.code_identities", "norm.code_commits", "norm.code_merge_requests", "norm.code_mr_events",
    )
    val ALL = RAW + NORM

    fun counts(connId: UInt, tables: List<String> = ALL): Map<String, Long> = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            tables.associateWith { table ->
                st.executeQuery("SELECT COUNT(*) FROM $table WHERE connection_id = $connId").use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    /** Every row of [table] for [connId] as text, one string (all columns, JSON included): what a privacy scan searches. */
    fun text(connId: UInt, table: String): String = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT coalesce(string_agg(t::text, E'\\n'), '') FROM $table t WHERE connection_id = $connId").use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    fun columns(table: String): Set<String> = PostgresTestSupport.connect().use { conn ->
        val (schema, name) = table.split('.')
        conn.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?").use { st ->
            st.setString(1, schema)
            st.setString(2, name)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }
}
