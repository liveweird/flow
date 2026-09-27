package ch.nokillswit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

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
}
