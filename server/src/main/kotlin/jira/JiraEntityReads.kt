package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Entities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** How many `(entity_id, stored bytes)` pairs one planning query returns: ids and numbers only, never payload text. */
private const val PLAN_WINDOW = 2_000

/**
 * The only way production code reads a whole kind of `raw.jira_entities` (`.claude/docs/jira-integration.md` "Stored-size ceilings"):
 * in keyset CHUNKS of at most [chunkBytes] stored bytes (`jira.processPageBytes`; a single entity bigger than that is a chunk of its
 * own, and the writer's [JiraStoredCeilings.maxPayloadBytes] bounds it), ascending `entity_id`. A caller parses each payload, keeps
 * the small value it needs and drops the text and the tree before the next chunk, so no kind size - however many entities a host
 * made the REFERENCE stream store - can hold the heap or stall PROCESS. One kind at a time: a caller that needs two kinds reduces
 * the first to its extracted values before reading the second.
 *
 * Each chunk is its own transaction (a kind written while it is read may be seen in two snapshots; every caller is a rebuild or a
 * read-only view that tolerates it, as it did the single statement's snapshot). Two statements per chunk window: the plan (ids and
 * `octet_length(payload::text)`, parameters bound, never interpolated) and the payload read of one chunk's id range.
 */
internal class JiraEntityReads(private val database: R2dbcDatabase, private val chunkBytes: Long = DEFAULT_PROCESS_PAGE_BYTES) {

    /** Calls [consume] once per live entity of [kind] in ascending id order, one chunk of text in memory at a time. */
    suspend fun forEachEntity(connectionId: UInt, kind: String, consume: (entityId: String, payload: String) -> Unit) {
        var after: String? = null
        while (true) {
            val window = planWindow(connectionId, kind, after)
            if (window.isEmpty()) return
            for (chunk in chunksOf(window, chunkBytes)) {
                readChunk(connectionId, kind, chunk.first(), chunk.last()).forEach { (id, payload) -> consume(id, payload) }
            }
            if (window.size < PLAN_WINDOW) return
            after = window.last().first
        }
    }

    /** [forEachEntity] mapped to the small values the caller extracts (`null` = none for this entity), in id order. */
    suspend fun <T : Any> mapEntities(connectionId: UInt, kind: String, transform: (entityId: String, payload: String) -> T?): List<T> {
        val out = mutableListOf<T>()
        forEachEntity(connectionId, kind) { id, payload -> transform(id, payload)?.let(out::add) }
        return out
    }

    private suspend fun planWindow(connectionId: UInt, kind: String, after: String?): List<Pair<String, Long>> =
        suspendTransaction(database) {
            val rows: Flow<Pair<String, Long>?>? = exec(
                "SELECT entity_id, octet_length(payload::text)::bigint FROM raw.jira_entities " +
                    "WHERE connection_id = ? AND kind = ? AND deleted_at IS NULL AND (?::text IS NULL OR entity_id > ?) " +
                    "ORDER BY entity_id LIMIT $PLAN_WINDOW",
                args = listOf(
                    IntegerColumnType() to connectionId.toInt(),
                    TextColumnType() to kind,
                    TextColumnType() to after,
                    TextColumnType() to after,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, String::class.java)!! to row.get(1, Long::class.javaObjectType)!! }
            rows?.toList()?.filterNotNull().orEmpty()
        }

    private suspend fun readChunk(connectionId: UInt, kind: String, firstId: String, lastId: String): List<Pair<String, String>> =
        suspendTransaction(database) {
            Entities.select(Entities.entityId, Entities.payload)
                .where {
                    (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and Entities.deletedAt.isNull() and
                        (Entities.entityId greaterEq firstId) and (Entities.entityId lessEq lastId)
                }
                .orderBy(Entities.entityId)
                .map { it[Entities.entityId] to it[Entities.payload] }.toList()
        }
}

/** Splits the planned `(id, bytes)` window into runs of at most [chunkBytes], every run at least one entity; each run is its ids. */
internal fun chunksOf(window: List<Pair<String, Long>>, chunkBytes: Long): List<List<String>> {
    val chunks = mutableListOf<MutableList<String>>()
    var running = 0L
    for ((id, bytes) in window) {
        val current = chunks.lastOrNull()
        if (current == null || running + bytes > chunkBytes) {
            chunks += mutableListOf(id)
            running = bytes
        } else {
            current += id
            running += bytes
        }
    }
    return chunks
}
