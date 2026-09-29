package ch.nokillswit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the suite-wide [SyncedStubFixture] against accidental mutation
 * (`.claude/docs/testing.md` "Shared synced fixture"): every READ-ONLY test in
 * `NormalizationPipelineTest`/`JiraSyncPipelineTest`/`DataProfileTest` reads the SAME synced
 * connection rather than syncing its own, so a test that silently started writing to it would
 * corrupt every OTHER read-only test sharing it. This re-snapshots the connection and compares it
 * against the baseline [SyncedStubFixture] captured the moment its own backfill first completed.
 */
class SyncedStubFixtureTest {
    @Test
    fun `the shared fixture's connection is never mutated by a read-only test`() = runBlocking {
        SyncedStubFixture.assertUnchanged()
    }

    /** [SyncedStubFixture.cloneProcessedData]'s own correctness pin — [DerivedStubFixture]'s first consumer. */
    @Test
    fun `cloneProcessedData reproduces the source connection's own status-interval digest`() = runBlocking {
        val sourceConnId = SyncedStubFixture.connectionId()
        val cloneConnId = SyncedStubFixture.createConnection(namePrefix = "jira-processed-clone-check")
        SyncedStubFixture.cloneProcessedData(sourceConnId, cloneConnId)

        val items = SyncedStubFixture.workItems()
        assertEquals(
            SyncedStubFixture.statusIntervalDigest(items, sourceConnId),
            SyncedStubFixture.statusIntervalDigest(items, cloneConnId),
            "a processed clone's status intervals must be byte-for-byte identical to the source connection's",
        )
    }
}
