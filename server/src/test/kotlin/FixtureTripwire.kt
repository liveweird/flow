package ch.nokillswit

import kotlin.test.fail

/**
 * The re-verification machinery behind [SyncedStubFixture]/[DerivedStubFixture]'s shared connections
 * (`.claude/docs/test-fixtures.md` "Fixture tripwires"): the fixture arms it with the baseline it captures when
 * its own init completes, and every later `connectionId()` call re-snapshots the connection and compares it, so a
 * consumer that mutates the shared connection fails the NEXT test that asks for it — whatever class order the fork
 * runs in. A tripwire test that merely compares once proves nothing when it happens to run first (or before the
 * mutator); this check rides every consumer instead.
 *
 * [perCallerClass] trades precision for cost: a snapshot that is expensive (the derived digest, ~180 ms) re-checks
 * only when the calling TEST CLASS changes (an unidentifiable caller always re-checks), a cheap one (~25 ms) on
 * every call. The failing test is the one AFTER the mutator, so the message names the previous caller. After a
 * failure the baseline is re-captured, so one mutation fails ONE test, never every later consumer in the fork.
 */
internal class FixtureTripwire<S : Any>(
    private val name: String,
    private val perCallerClass: Boolean,
    private val callerOf: () -> String? = ::callerTestClass,
) {
    private var baseline: S? = null
    private var lastCaller: String? = null
    private var freshlyArmed = false

    fun arm(snapshot: S) {
        baseline = snapshot
        freshlyArmed = true
    }

    /** [force] skips the per-class gate (the explicit tripwire tests); [snapshotOf] is called only when a check runs. */
    suspend fun verify(connectionId: UInt, force: Boolean = false, snapshotOf: suspend () -> S) {
        val caller = callerOf()
        val previous = lastCaller
        if (freshlyArmed) { // the baseline was captured moments ago, by the call that initialised the fixture
            freshlyArmed = false
            lastCaller = caller
            return
        }
        if (!force && perCallerClass && caller != null && caller == previous) return
        lastCaller = caller
        val expected = checkNotNull(baseline) { "$name: verify() before arm()" }
        val current = snapshotOf()
        if (current != expected) {
            baseline = current // one mutation fails one test, not every later consumer
            fail(
                "the shared $name connection $connectionId was mutated since it was last verified" +
                    (previous?.let { " (previous caller: $it)" } ?: "") +
                    " — a read-only test must never write to it; clone it instead.\nexpected: $expected\nactual:   $current",
            )
        }
    }
}

/**
 * The simple name of the top-level `*Test` class the current call runs under, or null when no frame belongs to one (a helper
 * class's own coroutine continuation after a suspension: only that helper is on the stack). Nested and lambda classes
 * (`FooTest$bar$1`) count as `FooTest`.
 */
internal fun callerTestClass(): String? = StackWalker.getInstance().walk { frames ->
    frames.map { it.className.substringBefore('$').substringAfterLast('.') }
        .filter { it.endsWith("Test") }
        .findFirst()
        .orElse(null)
}
