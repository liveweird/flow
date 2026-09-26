package ch.nokillswit

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.json.canonicalJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V10 is the FIRST migration to create a table outside `public` (`raw`, plan §0 A3) — this proves,
 * through Exposed R2DBC against Testcontainers PostgreSQL, that a schema-qualified `Table("raw.…")`
 * behaves exactly like an unqualified one for every operation the Jira raw store needs: insert,
 * select, update, upsert/`ON CONFLICT`, jsonb, a `FOR UPDATE SKIP LOCKED` select, and delete. This
 * is the load-bearing check plan §0 A3 calls for BEFORE `jira/JiraRawStore.kt` relies on the same
 * shape — a scratch table (`raw.roundtrip_test`, never one of the app's migrated tables), created
 * with raw DDL (`.claude/docs/persistence.md`: never `SchemaUtils.create`).
 *
 * Outcome: schema-qualified Exposed `Table` names work natively over `exposed-r2dbc` 1.5.0 — no
 * `search_path`/raw-SQL fallback (plan §0 A3's fallback branch) was needed.
 */
class RawSchemaRoundtripTest {

    private object Scratch : Table("raw.roundtrip_test") {
        val id = integer("id")
        val label = varchar("label", 50)
        val payload = jsonb("payload").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object ScratchCounter : IntIdTable("raw.roundtrip_counter") {
        val name = varchar("name", 50).uniqueIndex()
        val hits = integer("hits")
    }

    private suspend fun freshScratchTables() {
        suspendTransaction(sharedDatabaseForTests()) {
            exec("CREATE SCHEMA IF NOT EXISTS raw")
            exec("CREATE TABLE IF NOT EXISTS raw.roundtrip_test (id INTEGER PRIMARY KEY, label VARCHAR(50) NOT NULL, payload JSONB)")
            exec(
                "CREATE TABLE IF NOT EXISTS raw.roundtrip_counter " +
                    "(id SERIAL PRIMARY KEY, name VARCHAR(50) NOT NULL UNIQUE, hits INTEGER NOT NULL)",
            )
            exec("TRUNCATE TABLE raw.roundtrip_test")
            exec("TRUNCATE TABLE raw.roundtrip_counter")
        }
    }

    @Test
    fun `insert, select and update a schema-qualified table`() = runBlocking {
        freshScratchTables()
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            Scratch.insert {
                it[id] = 1
                it[label] = "first"
                it[payload] = """{"b":2,"a":1}"""
            }
        }
        val row = suspendTransaction(db) { Scratch.selectAll().where { Scratch.id eq 1 }.toList().single() }
        assertEquals("first", row[Scratch.label])
        assertEquals(canonicalJson("""{"a":1,"b":2}"""), canonicalJson(row[Scratch.payload]!!))

        suspendTransaction(db) { Scratch.update({ Scratch.id eq 1 }) { it[label] = "updated" } }
        val updated = suspendTransaction(db) { Scratch.selectAll().where { Scratch.id eq 1 }.toList().single() }
        assertEquals("updated", updated[Scratch.label])
    }

    @Test
    fun `upsert via ON CONFLICT DO UPDATE inserts then merges on a schema-qualified table`() = runBlocking {
        freshScratchTables()
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            ScratchCounter.upsert(ScratchCounter.name) {
                it[name] = "widgets"
                it[hits] = 1
            }
        }
        var row = suspendTransaction(db) { ScratchCounter.selectAll().where { ScratchCounter.name eq "widgets" }.toList().single() }
        assertEquals(1, row[ScratchCounter.hits])

        // A second upsert on the SAME unique key merges (ON CONFLICT DO UPDATE), never duplicates.
        suspendTransaction(db) {
            ScratchCounter.upsert(ScratchCounter.name) {
                it[name] = "widgets"
                it[hits] = 2
            }
        }
        val rows = suspendTransaction(db) { ScratchCounter.selectAll().where { ScratchCounter.name eq "widgets" }.toList() }
        assertEquals(1, rows.size, "ON CONFLICT must merge, not duplicate")
        row = rows.single()
        assertEquals(2, row[ScratchCounter.hits])
    }

    @Test
    fun `a FOR UPDATE SKIP LOCKED select works on a schema-qualified table`() = runBlocking {
        freshScratchTables()
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            Scratch.insert {
                it[id] = 10
                it[label] = "lockable"
                it[payload] = null
            }
        }
        val skipLocked = ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)
        val locked = suspendTransaction(db) {
            Scratch.selectAll().where { Scratch.id eq 10 }.forUpdate(skipLocked).toList().single()
        }
        assertEquals("lockable", locked[Scratch.label])
    }

    @Test
    fun `a null jsonb value round-trips as null on a schema-qualified table`() = runBlocking {
        freshScratchTables()
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            Scratch.insert {
                it[id] = 20
                it[label] = "no-payload"
                it[payload] = null
            }
        }
        val row = suspendTransaction(db) { Scratch.selectAll().where { Scratch.id eq 20 }.toList().single() }
        assertTrue(row[Scratch.payload] == null)
    }

    @Test
    fun `delete removes a row from a schema-qualified table`() = runBlocking {
        freshScratchTables()
        val db = sharedDatabaseForTests()
        suspendTransaction(db) {
            Scratch.insert {
                it[id] = 30
                it[label] = "to-delete"
                it[payload] = null
            }
        }
        val deleted = suspendTransaction(db) { Scratch.deleteWhere { Scratch.id eq 30 } }
        assertEquals(1, deleted)
        val remaining = suspendTransaction(db) { Scratch.selectAll().where { Scratch.id eq 30 }.toList() }
        assertTrue(remaining.isEmpty())
    }
}
