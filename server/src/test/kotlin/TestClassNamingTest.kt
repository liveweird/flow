package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Naming guard for every Kotlin file under `server/src/test/kotlin`. The `test` task selects its candidates by FILE NAME
 * (`scanForTestClasses = false` plus an include of every `*Test.class` in `server/build.gradle.kts`), so a class that holds
 * `@Test` methods but is not named `*Test` would silently never run, and a helper `object`, an abstract base or a
 * nested class whose name ends in `Test` would be handed to a fork as a candidate. The rules:
 *
 * - a file containing `@Test` (also `@kotlin.test.Test`/`@org.junit.Test`, or after another annotation on the line) is
 *   named `*Test.kt` and declares `class <FileName>`;
 * - every `@Test` sits in that class: the nearest top-level declaration above it is `class <FileName>` (a second
 *   top-level class holding tests is never a candidate);
 * - a `class <FileName>` named `*Test` holds at least one `@Test` (JUnit 4 fails a candidate with no runnable methods);
 * - every declaration named `*Test` in test sources is a concrete `class` named exactly like its file (so no `object`,
 *   `interface`, `abstract class`, nested or second `*Test` class — each would add a candidate beside the file's own).
 */
class TestClassNamingTest {
    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    private data class Declaration(val modifiers: List<String>, val kind: String, val name: String)

    private fun sources(): Map<String, List<String>> {
        val root = locate("src/test/kotlin", "server/src/test/kotlin")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readLines() }
    }

    private fun declarations(lines: List<String>): List<Declaration> = lines.mapNotNull { line ->
        DECLARATION.matchEntire(line)?.let { match ->
            Declaration(match.groupValues[1].trim().split(' ').filter(String::isNotEmpty), match.groupValues[2], match.groupValues[3])
        }
    }

    @Test
    fun `a test class must be named Test and match its file`() {
        val sources = sources()
        assertTrue(sources.size > MIN_FILES, "expected the server test sources, found ${sources.size} files")
        val problems = sources.flatMap { (path, lines) ->
            val fileName = path.substringAfterLast('/').removeSuffix(".kt")
            val declared = declarations(lines)
            val testLines = lines.indices.filter { i -> !COMMENT.matches(lines[i]) && TEST_ANNOTATION.containsMatchIn(lines[i]) }
            val hasTests = testLines.isNotEmpty()
            val own = declared.filter { it.name == fileName && it.kind == "class" && "abstract" !in it.modifiers }
            buildList {
                if (hasTests && !fileName.endsWith(SUFFIX)) add("$path holds @Test methods but its file name does not end in $SUFFIX")
                if (hasTests && fileName.endsWith(SUFFIX) && own.isEmpty()) {
                    add("$path holds @Test methods but declares no concrete class $fileName")
                }
                testLines.mapNotNull { i -> (i downTo 0).firstNotNullOfOrNull { TOP_LEVEL.matchEntire(lines[it])?.groupValues?.get(1) } }
                    .filter { it != fileName }.distinct()
                    .forEach { add("$path holds @Test methods in $it, which is not the file's class $fileName") }
                if (!hasTests && own.isNotEmpty() && fileName.endsWith(SUFFIX)) {
                    add("$path declares class $fileName without any @Test (JUnit 4 fails it with 'No runnable methods')")
                }
                declared.filter { it.name.endsWith(SUFFIX) && it !in own }.forEach {
                    add("$path declares ${(it.modifiers + it.kind + it.name).joinToString(" ")}, which is not the concrete class $fileName")
                }
            }
        }
        assertTrue(
            problems.isEmpty(),
            "a test class must be named *Test and match its file — the test task selects candidates by file name " +
                "(scanForTestClasses=false): rename the class/file or drop the Test suffix from the helper: $problems",
        )
    }

    private companion object {
        const val MIN_FILES = 100
        const val SUFFIX = "Test"
        // `@Test`, `@kotlin.test.Test`, `@org.junit.Test`, at a line start or after another annotation/whitespace.
        val TEST_ANNOTATION = Regex("""(?:^|\s)@(?:[\w.]+\.)?Test\b""")
        val COMMENT = Regex("""^\s*(?:\*|//|/\*).*""")

        // A top-level (column 0) class/object/interface declaration; nested helpers are indented and ignored.
        val TOP_LEVEL = Regex(
            """^(?:(?:public|internal|private|open|abstract|final|data|sealed|value|enum|annotation)\s+)*""" +
                """(?:class|object|interface)\s+(\w+)\b.*""",
        )
        val DECLARATION = Regex(
            """^\s*((?:(?:public|internal|private|protected|open|abstract|final|data|sealed|inner|value|enum|annotation)\s+)*)""" +
                """(class|object|interface)\s+(\w*Test)\b.*""",
        )
    }
}
