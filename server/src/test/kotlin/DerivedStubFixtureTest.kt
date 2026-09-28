package ch.nokillswit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Guards the suite-wide [DerivedStubFixture] against accidental mutation — the same tripwire role
 * `SyncedStubFixtureTest` plays for [SyncedStubFixture] (`.claude/docs/testing.md` "Shared synced
 * fixture" — "The derived fixture"): every READ-ONLY `MetricsDerivationTest` assertion reads the
 * SAME derived connection rather than deriving its own, so a test that silently started writing to
 * it would corrupt every OTHER read-only test sharing it.
 */
class DerivedStubFixtureTest {
    @Test
    fun `the shared derived fixture's connection is never mutated by a read-only test`() = runBlocking {
        DerivedStubFixture.assertUnchanged()
    }
}
