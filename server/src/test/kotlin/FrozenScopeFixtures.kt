package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.assertEquals

/**
 * Shared by the three sprint reports' USER-level frozen-figure tests (Velocity, Throughput, Sprint consistency): a
 * private derived clone whose `fact_sprint_snapshot.scope` a test rewrites, and the writer-shaped JSON item to rewrite
 * it with. Built independently of the production parser (`reports/FrozenScope.kt`).
 */
object FrozenScopeFixtures {

    /** A private, DISABLED, FLO-mapped clone derived once under the pinned clock; returns (connection id, its team id). */
    suspend fun derivedDisabledClone(namePrefix: String, teamPrefix: String): Pair<UInt, UInt> {
        val connId = SyncedStubFixture.createConnection(namePrefix = namePrefix, enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        val teamId = DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), teamPrefix)
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 1u) }
        return connId to teamId
    }

    /** One snapshot's stored `scope`, as JSON. */
    suspend fun storedScope(connectionId: UInt, sprintId: Long): JsonArray {
        val stored = suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.FactSprintSnapshot.selectAll()
                .where {
                    (MetricsTables.FactSprintSnapshot.connectionId eq connectionId) and
                        (MetricsTables.FactSprintSnapshot.sprintId eq sprintId)
                }
                .toList().single()[MetricsTables.FactSprintSnapshot.scope]
        }
        return Json.parseToJsonElement(stored) as JsonArray
    }

    /**
     * Rewrites one snapshot row's `scope` so it DIFFERS from the live `fact_sprint_scope` rows. The immutability trigger forbids
     * UPDATE/DELETE unless `metrics.allow_snapshot_delete` is SET LOCAL 'on' — the PURGE step's own sanctioned bypass (pinned by
     * `IngestWorkerTest`); the trigger itself stays enabled, and only the test's private clone is touched.
     */
    suspend fun overwriteSnapshotScope(connectionId: UInt, sprintId: Long, scope: JsonArray) {
        suspendTransaction(sharedDatabaseForTests()) {
            exec("SET LOCAL metrics.allow_snapshot_delete = 'on'")
            exec(
                "UPDATE metrics.fact_sprint_snapshot SET scope = '$scope'::jsonb " +
                    "WHERE connection_id = $connectionId AND sprint_id = $sprintId",
            )
        }
        assertEquals(scope, storedScope(connectionId, sprintId), "the synthetic scope must actually have been stored")
    }

    /** One stored-scope item with EVERY key the writer (`MetricsStore.sprintScopeItemsJson`) emits. */
    fun scopeItem(
        issueId: Long,
        assignee: String?,
        committed: Boolean = true,
        inScopeAtClose: Boolean = true,
        addedAtMs: Long? = null,
        removedAtMs: Long? = null,
        commitMd: Double? = null,
        closeMd: Double? = null,
        doneMd: Double? = null,
        done: Boolean = false,
        carried: Boolean = false,
        dropped: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("issueId", issueId)
        put("addedAtMs", addedAtMs)
        put("removedAtMs", removedAtMs)
        put("committed", committed)
        put("inScopeAtClose", inScopeAtClose)
        put("estimateAtCommitmentMd", commitMd)
        put("estimateAtCloseMd", closeMd)
        put("estimateAtDoneMd", doneMd)
        put("assigneeAtCommitment", assignee)
        put("doneInSprint", done)
        put("carriedOver", carried)
        put("dropped", dropped)
    }
}
