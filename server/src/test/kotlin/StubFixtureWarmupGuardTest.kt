package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Warm-up guard for the shared stub fixtures (`.claude/docs/testing.md` "Shared synced fixture"). [SyncedStubFixture] and
 * [DerivedStubFixture] are lazy per JVM fork: whichever test touches them FIRST pays Postgres + migrate + the full stub SYNC
 * + the pinned DERIVE (~45 s on a 4-vCPU CI runner). Ktor's `testApplication` runs its body under `runTestWithRealTime`
 * with a hard-coded 60 s wall-clock limit (`UncompletedCoroutinesError`), so that first test would die on a slow runner —
 * and with the weight-balanced fork order every fork opens with a heavy fixture class (CI run 39034284450). A class that
 * reads a fixture therefore warms it in a `@BeforeClass` ([warmStubFixtures]), which JUnit 4 does not time. The rules:
 *
 * - every `*Test.kt` that calls `SyncedStubFixture.connectionId()`/`assertUnchanged()`, the `DerivedStubFixture` pair, or
 *   `FrozenScopeFixtures.derivedDisabledClone` (which clones the shared connection) declares `warmStubFixtures()` inside a
 *   `@BeforeClass` method;
 * - a helper file (not `*Test.kt`) may not hide such a call, except the fixtures themselves, `TestEnvironment.kt` (which holds
 *   [warmStubFixtures]) and `FrozenScopeFixtures` (whose entry point is itself a trigger above) — otherwise its callers
 *   would escape the first rule.
 */
class StubFixtureWarmupGuardTest {
    private fun sources(): Map<String, List<String>> {
        val root = listOf("src/test/kotlin", "server/src/test/kotlin").map(::File).firstOrNull { it.exists() }
            ?: error("server test sources not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readLines() }
    }

    private fun triggers(lines: List<String>) = lines.any { !COMMENT.matches(it) && TRIGGER.containsMatchIn(it) }

    private fun warmsInBeforeClass(lines: List<String>): Boolean = lines.indices.any { i ->
        !COMMENT.matches(lines[i]) && BEFORE_CLASS.containsMatchIn(lines[i]) &&
            lines.subList(i, minOf(i + WINDOW, lines.size)).any { WARM_CALL.containsMatchIn(it) && !COMMENT.matches(it) }
    }

    @Test
    fun `a class that reads a shared stub fixture warms it in a BeforeClass`() {
        val sources = sources()
        assertTrue(sources.size > MIN_FILES, "expected the server test sources, found ${sources.size} files")
        val unwarmed = sources.filter { (path, lines) ->
            path.endsWith("Test.kt") && path != THIS_FILE && triggers(lines) && !warmsInBeforeClass(lines)
        }.keys.sorted()
        assertTrue(
            unwarmed.isEmpty(),
            "these classes read SyncedStubFixture/DerivedStubFixture but do not warm them in a @BeforeClass, so their first test in a " +
                "fork pays the one-time Postgres + SYNC + DERIVE cost INSIDE testApplication's hard 60 s limit " +
                "(UncompletedCoroutinesError on a slow CI runner) — add to the class: " +
                "companion object { @JvmStatic @BeforeClass fun warmFixtures() = warmStubFixtures() } " +
                "(import org.junit.BeforeClass): $unwarmed",
        )
    }

    @Test
    fun `a helper file does not hide a fixture trigger from the classes that use it`() {
        val hidden = sources().filter { (path, lines) ->
            !path.endsWith("Test.kt") && path !in HELPERS_ALLOWED_TO_TRIGGER && triggers(lines)
        }.keys.sorted()
        assertTrue(
            hidden.isEmpty(),
            "these helper files call SyncedStubFixture/DerivedStubFixture.connectionId()/assertUnchanged(), so every test class using " +
                "them would trigger the one-time SYNC unwarmed and escape the BeforeClass rule: expose the helper through a " +
                "pattern in TRIGGER (as derivedDisabledClone is) and list the file in HELPERS_ALLOWED_TO_TRIGGER: $hidden",
        )
    }

    private companion object {
        const val MIN_FILES = 100
        const val WINDOW = 4
        const val THIS_FILE = "StubFixtureWarmupGuardTest.kt"
        val HELPERS_ALLOWED_TO_TRIGGER =
            setOf("SyncedStubFixture.kt", "DerivedStubFixture.kt", "FrozenScopeFixtures.kt", "TestEnvironment.kt")
        val COMMENT = Regex("""^\s*(?:\*|//|/\*).*""")
        val TRIGGER = Regex("""\b(?:SyncedStubFixture|DerivedStubFixture)\.(?:connectionId|assertUnchanged)\b|\bderivedDisabledClone\(""")
        val BEFORE_CLASS = Regex("""@(?:[\w.]+\.)?BeforeClass\b""")
        val WARM_CALL = Regex("""\bwarmStubFixtures\(\)""")
    }
}
