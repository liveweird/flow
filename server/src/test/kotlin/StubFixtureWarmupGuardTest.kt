package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Warm-up guard for the shared stub fixtures (`.claude/docs/testing.md` "Shared synced fixture"). [SyncedStubFixture] and
 * [DerivedStubFixture] are lazy per JVM fork: whichever test touches them FIRST pays Postgres + migrate + the full stub SYNC
 * + the pinned DERIVE (~30-50 s on a 4-vCPU CI runner). Ktor's `testApplication` runs its body under `runTestWithRealTime`
 * with a hard-coded 60 s wall-clock limit (`UncompletedCoroutinesError`), so that first test would die on a slow runner —
 * and with the weight-balanced fork order every fork opens with a heavy fixture class (CI run 39034284450). A class that
 * reads a fixture therefore warms it in a `@BeforeClass` ([warmStubFixtures]), which JUnit 4 does not time (the Postgres + first-boot
 * share of the cold start is paid by each fork's pinned `ForkWarmup<k>Test`, which must NOT touch the fixtures). The rules:
 *
 * - every `*Test.kt` that calls `SyncedStubFixture.connectionId()`/`assertUnchanged()`, the `DerivedStubFixture` pair, or
 *   `FrozenScopeFixtures.derivedDisabledClone` (which clones the shared connection) declares `warmStubFixtures()` inside a
 *   `@BeforeClass` method;
 * - the GitLab twin: every `*Test.kt` that calls `SyncedGitLabFixture.connectionId()`/`assertUnchanged()` declares
 *   `warmGitLabFixture()` in a `@BeforeClass` (its own call, so the Jira-fixture classes do not pay for the GitLab SYNC); a class
 *   must warm EVERY fixture it triggers;
 * - a helper file (not `*Test.kt`) may not hide such a call, except the fixtures themselves, `TestEnvironment.kt` (which holds
 *   [warmStubFixtures] and [warmGitLabFixture]) and `FrozenScopeFixtures` (whose entry point is itself a trigger above) — otherwise
 *   its callers
 *   would escape the first rule.
 */
class StubFixtureWarmupGuardTest {
    private fun sources(): Map<String, List<String>> {
        val root = listOf("src/test/kotlin", "server/src/test/kotlin").map(::File).firstOrNull { it.exists() }
            ?: error("server test sources not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readLines() }
    }

    private fun triggers(lines: List<String>, trigger: Regex) = lines.any { !COMMENT.matches(it) && trigger.containsMatchIn(it) }

    private fun warmsInBeforeClass(lines: List<String>, warmCall: Regex): Boolean = lines.indices.any { i ->
        !COMMENT.matches(lines[i]) && BEFORE_CLASS.containsMatchIn(lines[i]) &&
            lines.subList(i, minOf(i + WINDOW, lines.size)).any { warmCall.containsMatchIn(it) && !COMMENT.matches(it) }
    }

    /** The fixtures a file triggers whose own warm-up call it does not make in a `@BeforeClass`: the missing calls, by name. */
    private fun missingWarmUps(lines: List<String>): List<String> =
        FIXTURES.filter { (_, trigger, warm) -> triggers(lines, trigger) && !warmsInBeforeClass(lines, warm) }.map { it.first }

    @Test
    fun `a class that reads a shared stub fixture warms it in a BeforeClass`() {
        val sources = sources()
        assertTrue(sources.size > MIN_FILES, "expected the server test sources, found ${sources.size} files")
        val unwarmed = sources.filter { (path, _) -> path.endsWith("Test.kt") && path != THIS_FILE }
            .mapValues { (_, lines) -> missingWarmUps(lines) }.filterValues { it.isNotEmpty() }
        assertTrue(
            unwarmed.isEmpty(),
            "these classes read a shared stub fixture but do not warm it in a @BeforeClass, so their first test in a fork pays the " +
                "one-time Postgres + SYNC (+ DERIVE) cost INSIDE testApplication's hard 60 s limit (UncompletedCoroutinesError on a " +
                "slow CI runner) — add to the class: companion object { @JvmStatic @BeforeClass fun warmFixtures() = <call> } " +
                "(import org.junit.BeforeClass), with the call named here per fixture (SyncedStubFixture/DerivedStubFixture: " +
                "warmStubFixtures(); SyncedGitLabFixture: warmGitLabFixture()): ${unwarmed.toSortedMap()}",
        )
    }

    @Test
    fun `a helper file does not hide a fixture trigger from the classes that use it`() {
        val hidden = sources().filter { (path, lines) ->
            !path.endsWith("Test.kt") && path !in HELPERS_ALLOWED_TO_TRIGGER && FIXTURES.any { triggers(lines, it.second) }
        }.keys.sorted()
        assertTrue(
            hidden.isEmpty(),
            "these helper files call SyncedStubFixture/DerivedStubFixture/SyncedGitLabFixture.connectionId()/assertUnchanged(), " +
                "so every test class using " +
                "them would trigger the one-time SYNC unwarmed and escape the BeforeClass rule: expose the helper through a " +
                "pattern in FIXTURES (as derivedDisabledClone is) and list the file in HELPERS_ALLOWED_TO_TRIGGER: $hidden",
        )
    }

    private companion object {
        const val MIN_FILES = 100
        const val WINDOW = 4
        const val THIS_FILE = "StubFixtureWarmupGuardTest.kt"
        val HELPERS_ALLOWED_TO_TRIGGER =
            setOf(
                "SyncedStubFixture.kt", "DerivedStubFixture.kt", "SyncedGitLabFixture.kt", "FrozenScopeFixtures.kt", "TestEnvironment.kt",
            )
        val COMMENT = Regex("""^\s*(?:\*|//|/\*).*""")
        /** (name, trigger, the warm-up call that satisfies it): a class must warm EVERY fixture it triggers. */
        val FIXTURES = listOf(
            Triple(
                "Jira (SyncedStubFixture/DerivedStubFixture)",
                Regex("""\b(?:SyncedStubFixture|DerivedStubFixture)\.(?:connectionId|assertUnchanged)\b|\bderivedDisabledClone\("""),
                Regex("""\bwarmStubFixtures\(\)"""),
            ),
            Triple(
                "GitLab (SyncedGitLabFixture)",
                Regex("""\bSyncedGitLabFixture\.(?:connectionId|assertUnchanged)\b"""),
                Regex("""\bwarmGitLabFixture\(\)"""),
            ),
        )
        val BEFORE_CLASS = Regex("""@(?:[\w.]+\.)?BeforeClass\b""")
    }
}
