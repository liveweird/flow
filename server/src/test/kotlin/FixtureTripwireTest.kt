package ch.nokillswit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A NON-test helper (its name does not end in `Test`): what a fixture's caller looks like after a suspension inside a helper. */
internal object TripwireProbe {
    suspend fun callerAfterResume(): String? {
        yield()
        return callerTestClass()
    }
}

/**
 * Pins [FixtureTripwire] itself (`.claude/docs/test-fixtures.md` "Shared synced fixture"): the fixtures' mutation check is only as
 * good as this class, and nothing else exercises its failure and gating paths (the real fixtures never fail).
 */
class FixtureTripwireTest {
    /** A fake fixture: [value] is the "connection state", [snapshots] counts the checks that actually ran. */
    private class Fake(perCallerClass: Boolean, caller: () -> String? = { "ATest" }) {
        var value = 1
        var snapshots = 0
        val tripwire = FixtureTripwire<Int>("fake", perCallerClass, caller).also { it.arm(1) }

        suspend fun verify(force: Boolean = false) = tripwire.verify(7u, force) { snapshots++; value }
    }

    @Test
    fun `a changed snapshot fails the verify, naming the previous caller, and the baseline is re-captured`() = runBlocking<Unit> {
        var caller = "ReaderTest"
        val fake = Fake(perCallerClass = false) { caller }
        fake.verify() // consumed by the arming call: no check
        assertEquals(0, fake.snapshots)
        fake.verify()
        assertEquals(1, fake.snapshots)

        fake.value = 2
        caller = "MutatorTest"
        val failure = assertFailsWith<AssertionError> { fake.verify() }
        assertTrue("previous caller: ReaderTest" in failure.message.orEmpty(), failure.message)
        assertTrue("expected: 1" in failure.message.orEmpty() && "actual:   2" in failure.message.orEmpty(), failure.message)

        fake.verify() // the baseline is now 2: one mutation is one red test
        assertEquals(3, fake.snapshots)
    }

    @Test
    fun `the explicit tripwire call is forced past the per-class gate`() = runBlocking<Unit> {
        val fake = Fake(perCallerClass = true)
        fake.verify() // arming call (ATest)
        fake.value = 2
        fake.verify() // same class: gated, so the mutation goes unseen here ...
        assertFailsWith<AssertionError> { fake.verify(force = true) } // ... but the explicit tripwire sees it
    }

    @Test
    fun `the per-class gate skips a repeat call from the same class and re-checks when the class changes`() = runBlocking<Unit> {
        var caller: String? = "ATest"
        val fake = Fake(perCallerClass = true) { caller }
        fake.verify() // arming call (ATest): the baseline is fresh, nothing to check
        fake.verify()
        fake.verify()
        assertEquals(0, fake.snapshots, "repeat calls from ATest are skipped")

        caller = "BTest"
        fake.verify()
        assertEquals(1, fake.snapshots, "a new class re-checks")
        fake.verify()
        assertEquals(1, fake.snapshots)

        fake.value = 2
        caller = "CTest"
        assertFailsWith<AssertionError> { fake.verify() }
    }

    @Test
    fun `an unidentifiable caller re-checks every time`() = runBlocking<Unit> {
        val fake = Fake(perCallerClass = true) { null }
        fake.verify() // arming call
        repeat(3) { fake.verify() }
        assertEquals(3, fake.snapshots)
    }

    @Test
    fun `without the gate every call re-checks`() = runBlocking<Unit> {
        val fake = Fake(perCallerClass = false)
        fake.verify() // arming call
        repeat(3) { fake.verify() }
        assertEquals(3, fake.snapshots)
    }

    @Test
    fun `callerTestClass names the test class from the test body, its lambdas and a Default-dispatcher resumption`() = runBlocking<Unit> {
        assertEquals("FixtureTripwireTest", callerTestClass())
        withContext(Dispatchers.Default) {
            yield()
            // After a suspension the stack holds only this lambda's continuation, which still belongs to the test class.
            assertEquals("FixtureTripwireTest", callerTestClass())
        }
    }

    @Test
    fun `callerTestClass is null after a resumption inside a non-test helper`() = runBlocking<Unit> {
        assertNull(withContext(Dispatchers.Default) { TripwireProbe.callerAfterResume() })
    }
}
