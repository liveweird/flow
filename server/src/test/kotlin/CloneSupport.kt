package ch.nokillswit

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.test.assertEquals

/**
 * The SQL clone mechanics every synced fixture shares (`SyncedStubFixture` for Jira, `SyncedGitLabFixture` for GitLab): ONE
 * `INSERT ... SELECT` per table, the column list taken from the Exposed object and proven equal to the database's, all in one
 * transaction on a plain JDBC connection (`.claude/docs/test-fixtures.md`). Lifted verbatim from `SyncedStubFixture`.
 */
internal object CloneSupport {
    const val CONNECTION_ID_COLUMN = "connection_id"

    /** Tables whose Exposed column set has already been proven equal to the database's (once per JVM fork). */
    private val verifiedColumnSets = ConcurrentHashMap.newKeySet<String>()

    private fun quoted(column: Column<*>) = "\"${column.name}\""

    /**
     * ONE `INSERT ... SELECT` for [table]: every column the Exposed object declares is copied
     * verbatim from [fromConnectionId]'s rows, except `connection_id` (rewritten to [toConnectionId])
     * and the surrogate SERIAL `id` (auto-increment columns get fresh values in the clone). The
     * column list comes from the table object, and [assertColumnSetMatchesDatabase] proves it equals
     * the database's own, so a column added by a migration but forgotten in the Exposed object (or
     * the reverse) fails the clone instead of being silently dropped.
     */
    private fun cloneTableSql(table: Table, fromConnectionId: UInt, toConnectionId: UInt): String {
        val copied = table.columns.filter { it.columnType !is AutoIncColumnType<*> && it.name != CONNECTION_ID_COLUMN }
        val targetList = (listOf("\"$CONNECTION_ID_COLUMN\"") + copied.map(::quoted)).joinToString(", ")
        val selectList = (listOf("$toConnectionId") + copied.map(::quoted)).joinToString(", ")
        return "INSERT INTO ${table.tableName} ($targetList) SELECT $selectList " +
            "FROM ${table.tableName} WHERE $CONNECTION_ID_COLUMN = $fromConnectionId"
    }

    private fun assertColumnSetMatchesDatabase(conn: java.sql.Connection, table: Table) {
        if (table.tableName in verifiedColumnSets) return
        val (schema, name) = table.tableName.split('.')
        val sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?"
        val database = conn.prepareStatement(sql).use { st ->
            st.setString(1, schema)
            st.setString(2, name)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        assertEquals(
            database,
            table.columns.map { it.name }.toSet(),
            "${table.tableName}: the Exposed columns must equal the database's, or the SQL clone would drop one",
        )
        verifiedColumnSets += table.tableName
    }

    /** Runs one `INSERT ... SELECT` per table (plus [extra]) in ONE database transaction on a plain JDBC connection. */
    fun cloneInTransaction(
        tables: List<Table>,
        fromConnectionId: UInt,
        toConnectionId: UInt,
        extra: (java.sql.Statement) -> Unit = {},
    ) {
        PostgresTestSupport.connect().use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { st ->
                    for (table in tables) {
                        assertColumnSetMatchesDatabase(conn, table)
                        st.executeUpdate(cloneTableSql(table, fromConnectionId, toConnectionId))
                    }
                    extra(st)
                }
                conn.commit()
            } catch (failure: Throwable) {
                // A failing rollback must never mask the failure that caused it.
                runCatching { conn.rollback() }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }
}
