package ch.nokillswit

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.json.canonicalJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Roundtrips the repo-local `jsonb` column binding (`infra/db/Jsonb.kt`) through Exposed R2DBC
 * against a real PostgreSQL (Testcontainers) — insert, select, update, a null value and nested
 * objects/arrays. A scratch table created with raw DDL, never `SchemaUtils.create`
 * (`.claude/docs/persistence.md`) — this is not one of the app's own migrated tables. Comparisons
 * go through [canonicalJson]: PostgreSQL's own `jsonb` storage reformats (whitespace, key order),
 * so "round-trips" means the same VALUES, not the same bytes.
 */
class JsonbColumnTest {

    private object Scratch : IntIdTable("jsonb_column_test") {
        val payload = jsonb("payload").nullable()
    }

    private suspend fun freshScratchTable() {
        suspendTransaction(sharedDatabaseForTests()) {
            exec("CREATE TABLE IF NOT EXISTS jsonb_column_test (id SERIAL PRIMARY KEY, payload JSONB)")
            exec("TRUNCATE TABLE jsonb_column_test")
        }
    }

    @Test
    fun `inserts and selects a flat object regardless of source key order`() = runBlocking {
        freshScratchTable()
        val db = sharedDatabaseForTests()
        val id = suspendTransaction(db) {
            Scratch.insert { it[payload] = """{"b":2,"a":1,"name":"café"}""" }[Scratch.id].value
        }
        val stored = suspendTransaction(db) {
            Scratch.selectAll().where { Scratch.id eq id }.toList().single()[Scratch.payload]
        }
        assertEquals(canonicalJson("""{"a":1,"b":2,"name":"café"}"""), canonicalJson(stored!!))
    }

    @Test
    fun `updates a stored payload`() = runBlocking {
        freshScratchTable()
        val db = sharedDatabaseForTests()
        val id = suspendTransaction(db) { Scratch.insert { it[payload] = """{"v":1}""" }[Scratch.id].value }
        suspendTransaction(db) { Scratch.update({ Scratch.id eq id }) { it[payload] = """{"v":2}""" } }
        val stored = suspendTransaction(db) {
            Scratch.selectAll().where { Scratch.id eq id }.toList().single()[Scratch.payload]
        }
        assertEquals(canonicalJson("""{"v":2}"""), canonicalJson(stored!!))
    }

    @Test
    fun `a null value round-trips as null`() = runBlocking {
        freshScratchTable()
        val db = sharedDatabaseForTests()
        val id = suspendTransaction(db) { Scratch.insert { it[payload] = null }[Scratch.id].value }
        val stored = suspendTransaction(db) {
            Scratch.selectAll().where { Scratch.id eq id }.toList().single()[Scratch.payload]
        }
        assertNull(stored)
    }

    @Test
    fun `nested objects and arrays round-trip, arrays keeping their order`() = runBlocking {
        freshScratchTable()
        val db = sharedDatabaseForTests()
        val nested = """{"outer":{"list":[3,1,2],"inner":{"z":true,"a":false}},"tags":["b","a"]}"""
        val id = suspendTransaction(db) { Scratch.insert { it[payload] = nested }[Scratch.id].value }
        val stored = suspendTransaction(db) {
            Scratch.selectAll().where { Scratch.id eq id }.toList().single()[Scratch.payload]
        }
        assertEquals(canonicalJson(nested), canonicalJson(stored!!))
    }
}
