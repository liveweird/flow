package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps `server/cds/classlist.txt`, the class list the Dockerfile dumps its AppCDS archive from (build-times.md WHY 27),
 * honest. A stale list never breaks the image (a class missing from it is loaded from the jars at run time), it only
 * costs startup and the metaspace the archive would have saved, so this is a tripwire for gross drift: the file keeps
 * the canonical shape `scripts/cds-classlist.mjs` writes, and most of the application classes it names still exist.
 * Falling below [MIN_APP_CLASSES_STILL_PRESENT] means a rename/refactor wave: rerun the script (its header says how).
 */
class CdsClassListTest {
    private val lines: List<String> = locate("cds/classlist.txt", "server/cds/classlist.txt").readLines()
    private val body = lines.filter { it.isNotBlank() && !it.startsWith("#") }
    private val classes = body.filter { !it.startsWith("@") }
    private val proxies = body.filter { it.startsWith("@lambda-proxy ") }
    private val invokers = body.filter { it.startsWith("@lambda-form-invoker ") }

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    @Test
    fun `the class list has the canonical shape of the generator`() {
        assertTrue(lines.takeWhile { it.startsWith("#") }.isNotEmpty(), "the generated header comment is missing")
        assertEquals(
            body.size,
            classes.size + proxies.size + invokers.size,
            "only classes, @lambda-proxy and @lambda-form-invoker lines are allowed",
        )
        assertEquals(body, classes + proxies + invokers, "sections must be classes, then lambda proxies, then lambda-form invokers")
        for (section in listOf(classes, proxies, invokers)) {
            assertEquals(section.distinct().sorted(), section, "each section is sorted and free of duplicates")
        }
        assertTrue(classes.all { it.matches(CLASS_NAME) }, "class lines are bare names with no `id:` suffix")
        assertTrue(
            classes.size >= MIN_CLASSES,
            "the list names ${classes.size} classes, expected at least $MIN_CLASSES: was it generated from a real run?",
        )
        assertTrue(invokers.isNotEmpty() && proxies.isNotEmpty(), "a real run records lambda proxies and invokers")
    }

    @Test
    fun `most application classes in the class list still exist`() {
        val ours = classes.filter { it.startsWith("ch/nokillswit/") }
        assertTrue(ours.size >= MIN_APP_CLASSES, "the list holds only ${ours.size} application classes, expected at least $MIN_APP_CLASSES")
        val loader = CdsClassListTest::class.java.classLoader
        val missing = ours.filter { name ->
            runCatching { Class.forName(name.replace('/', '.'), false, loader) }.isFailure
        }
        val present = (ours.size - missing.size).toDouble() / ours.size
        assertTrue(
            present >= MIN_APP_CLASSES_STILL_PRESENT,
            "only ${"%.0f".format(present * 100)} % of the ${ours.size} application classes in server/cds/classlist.txt exist any more " +
                "(first missing: ${missing.take(5)}): regenerate it with `node scripts/cds-classlist.mjs`",
        )
    }

    private companion object {
        val CLASS_NAME = Regex("\\S+")
        const val MIN_CLASSES = 5_000
        const val MIN_APP_CLASSES = 1_000
        const val MIN_APP_CLASSES_STILL_PRESENT = 0.85
    }
}
