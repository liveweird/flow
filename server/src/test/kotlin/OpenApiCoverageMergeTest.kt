package ch.nokillswit

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fork-safe merge behind the OpenAPI coverage gate (`server/build.gradle.kts`'s `tasks.test`). */
class OpenApiCoverageMergeTest {
    private val declared = listOf(
        DeclaredOperation("GET", "/api/v1/things", "listThings", listOf("200", "401", "403")),
        DeclaredOperation("GET", "/api/v1/things/{id}", "getThing", listOf("200", "404", "default")),
        DeclaredOperation("DELETE", "/api/v1/things/{id}", "deleteThing", listOf("204", "409")),
    )

    private fun hit(method: String, path: String, status: Int) = OpenApiCoverageMerge.encode(ExercisedHit(method, path, status))

    private val a = hit("GET", "/api/v1/things", 200)
    private val b = hit("GET", "/api/v1/things/{id}", 200)
    private val c = hit("GET", "/api/v1/things/{id}", 404)

    @Test
    fun `the union of two forks keeps every hit of both`() {
        val union = OpenApiCoverageMerge.union(listOf(listOf(a, b), listOf(b, c)))
        assertEquals(setOf(a, b, c).map(OpenApiCoverageMerge::decode).toSet(), union)
    }

    @Test
    fun `a pair exercised by any fork is covered - the union's gaps equal the intersection of the per-fork gaps`() {
        val forkA = listOf(a, b).map(OpenApiCoverageMerge::decode).toSet()
        val forkB = listOf(b, c).map(OpenApiCoverageMerge::decode).toSet()
        val perForkGaps = listOf(forkA, forkB).map { OpenApiCoverageMerge.gaps(declared, it).toSet() }
        val merged = OpenApiCoverageMerge.gaps(declared, OpenApiCoverageMerge.union(listOf(listOf(a, b), listOf(b, c))))
        assertEquals(perForkGaps.reduce { x, y -> x intersect y }, merged.toSet())
        // 200 (fork A) and 404 (fork B) both count; only the never-produced DELETE pairs remain, and the
        // cross-cutting 401 / default are never gaps.
        assertEquals(
            setOf(
                "GET /api/v1/things 403 (listThings)",
                "DELETE /api/v1/things/{id} 204 (deleteThing)",
                "DELETE /api/v1/things/{id} 409 (deleteThing)",
            ),
            merged.toSet(),
        )
    }

    @Test
    fun `a fork that recorded nothing contributes nothing`() {
        val without = OpenApiCoverageMerge.union(listOf(listOf(a, b)))
        assertEquals(without, OpenApiCoverageMerge.union(listOf(listOf(a, b), emptyList(), listOf("", "  "))))
    }

    @Test
    fun `a path with spaces round-trips through the per-fork file format`() {
        val odd = ExercisedHit("POST", "(no template: /api/v1/x y)", 404)
        assertEquals(odd, OpenApiCoverageMerge.decode(OpenApiCoverageMerge.encode(odd)))
    }

    @Test
    fun `publishing from two forks leaves ONE coverage report and ONE gaps file over the union`() {
        val dir = Files.createTempDirectory("coverage-merge")
        try {
            OpenApiCoverageMerge.publish(dir, "1", setOf(ExercisedHit("GET", "/api/v1/things", 200)), declared)
            // The first fork to exit leaves a superset of the final gaps.
            assertTrue("GET /api/v1/things/{id} 404 (getThing)" in Files.readAllLines(dir.resolve("gaps.txt")))
            OpenApiCoverageMerge.publish(
                dir,
                "2",
                setOf(ExercisedHit("GET", "/api/v1/things/{id}", 200), ExercisedHit("GET", "/api/v1/things/{id}", 404)),
                declared,
            )
            val gaps = Files.readAllLines(dir.resolve("gaps.txt"))
            assertTrue(gaps.none { it.startsWith("GET ") && "403" !in it }, "GETs covered by either fork are no gap: $gaps")
            assertTrue("GET /api/v1/things 403 (listThings)" in gaps)
            val report = Files.readAllLines(dir.resolve("coverage.md"))
            assertTrue(report.any { it.startsWith("2 of 3 operations exercised; 3 of 8 declared") }, report.toString())
            assertEquals(2, Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("exercised-") }.count() }.toInt())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
