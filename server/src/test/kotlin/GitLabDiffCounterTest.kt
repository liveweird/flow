package ch.nokillswit

import ch.nokillswit.gitlab.countHunkLines
import ch.nokillswit.gitlab.summarizeDiffFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `countHunkLines` (`gitlab/GitLabDiffCounter.kt`): GitLab's per-file `diff` text, hunks only. The last test grades it against the stub's
 * real diff bodies and `sample-data/gitlab/expected.json`, the generator's ground truth.
 */
class GitLabDiffCounterTest {
    private fun count(diff: String) = countHunkLines(diff).let { it.additions to it.deletions }

    @Test
    fun `context lines count nothing, additions and deletions count one each`() {
        assertEquals(2 to 1, count("@@ -1,4 +1,5 @@ func x()\n ctx\n-old\n+new\n+more\n ctx2\n"))
    }

    @Test
    fun `the no-newline marker counts nothing wherever it follows`() {
        assertEquals(1 to 0, count("@@ -1 +1,2 @@\n ctx\n+added\n\\ No newline at end of file\n"))
        assertEquals(0 to 1, count("@@ -1,2 +1 @@\n ctx\n-removed\n\\ No newline at end of file\n"))
        assertEquals(1 to 1, count("@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b\n\\ No newline at end of file\n"))
        assertEquals(0 to 0, count("@@ -1 +1 @@\n ctx\n\\ No newline at end of file\n"))
    }

    @Test
    fun `two hunks add up and a second header counts nothing`() {
        assertEquals(3 to 2, count("@@ -1,2 +1,3 @@\n-a\n+b\n+c\n ctx\n@@ -10,2 +11,2 @@\n-d\n+e\n"))
    }

    @Test
    fun `a binary file, an empty diff and a diff without a hunk count nothing`() {
        assertEquals(0 to 0, count("Binary files a/x.png and b/x.png differ\n"))
        assertEquals(0 to 0, count(""))
        assertEquals(0 to 0, count("\n\n"))
        assertEquals(0 to 0, count("+looks added\n-looks removed\n"), "nothing before the first @@ counts")
    }

    @Test
    fun `lines that look like headers INSIDE a hunk are content lines`() {
        // 4 added lines starting '++++', '+---', '+@@' and a plain one; 1 removed line starting '---'; 1 removed plain.
        val diff = "@@ -1,3 +1,4 @@\n++++ four\n+--- dashes\n+@@ at\n+plain\n---- removed dashes\n-plain removed\n ctx\n"
        assertEquals(4 to 2, count(diff))
        assertEquals(0 to 1, count("@@ -1 +0,0 @@\n--- a/x\n"), "a removed line whose content starts with '--' counts")
        assertEquals(1 to 0, count("@@ -0,0 +1 @@\n+++ b/x\n"))
    }

    @Test
    fun `a file header before the first hunk is not counted`() {
        assertEquals(1 to 1, count("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n"))
    }

    @Test
    fun `CRLF line endings count the same and the last line needs no newline`() {
        assertEquals(2 to 1, count("@@ -1,2 +1,3 @@\r\n ctx\r\n-a\r\n+b\r\n+c"))
        assertEquals(1 to 0, count("@@ -0,0 +1 @@\n+only"))
    }

    @Test
    fun `summarizeDiffFile never carries the text and nulls the lines of a too_large file`() {
        val too = Json.parseToJsonElement("""{"diff":"@@ -1 +1 @@\n-a\n+b\n","new_path":"p","too_large":true}""")
        val file = summarizeDiffFile(too)
        assertNull(file.additions)
        assertNull(file.deletions)
        val declared = ch.nokillswit.gitlab.RawGitLabCommitFile::class.java.declaredFields.map { it.name }
        assertTrue(declared.none { it.contains("diff", ignoreCase = true) }, "no field can hold the diff text: $declared")
    }

    @Test
    fun `a diff entry without a diff string has unknown lines, an empty string is a real 0 and 0`() {
        val missing = summarizeDiffFile(Json.parseToJsonElement("""{"new_path":"p"}"""))
        assertNull(missing.additions)
        assertNull(missing.deletions)
        val nonString = summarizeDiffFile(Json.parseToJsonElement("""{"new_path":"p","diff":null}"""))
        assertNull(nonString.additions)
        val empty = summarizeDiffFile(Json.parseToJsonElement("""{"new_path":"p","diff":"","renamed_file":true,"old_path":"q"}"""))
        assertEquals(0, empty.additions)
        assertEquals(0, empty.deletions)
    }

    // --- the stub's real bodies against the generator's ground truth ----------------------------------------------------------

    private fun repoFile(relative: String) = listOf(File(relative), File("../$relative")).firstOrNull { it.exists() }
        ?: error("$relative not found from ${File(".").absolutePath}")

    private val expected: JsonObject by lazy { Json.parseToJsonElement(repoFile("sample-data/gitlab/expected.json").readText()).jsonObject }

    /** Every diff entry of a commit across the stub's pages (the file name carries the first 12 characters of the sha). */
    private fun stubDiff(projectId: Long, sha: String): List<JsonObject> {
        val dir = repoFile("sample-data/gitlab-stub/__files")
        val pages = dir.listFiles { f -> f.name.startsWith("p$projectId-diff-${sha.take(12)}") }.orEmpty().sortedBy { it.name }
        check(pages.isNotEmpty()) { "no stub diff body for $projectId/$sha" }
        return pages.flatMap { Json.parseToJsonElement(it.readText()).jsonArray.map(kotlinx.serialization.json.JsonElement::jsonObject) }
    }

    private fun assertCommitMatches(projectId: Long, sha: String) {
        val truth = expected.getValue("commitFiles").jsonObject.getValue(projectId.toString()).jsonObject.getValue(sha).jsonObject
            .getValue("files").jsonArray
        val stub = stubDiff(projectId, sha).associateBy { it.getValue("new_path").jsonPrimitive.content }
        assertEquals(truth.size, stub.size, "$projectId/$sha: the same files")
        for (file in truth.map { it.jsonObject }) {
            val path = file.getValue("path").jsonPrimitive.content
            val counted = summarizeDiffFile(stub.getValue(path))
            assertEquals(file.getValue("additions").jsonPrimitive.int, counted.additions, "$projectId/$sha $path additions")
            assertEquals(file.getValue("deletions").jsonPrimitive.int, counted.deletions, "$projectId/$sha $path deletions")
        }
    }

    @Test
    fun `the counter equals the ground truth for the special example commits and the hostile-lines commit`() {
        val special = expected.getValue("special").jsonObject
        val commits = mutableSetOf<Pair<Long, String>>()
        for (example in special.getValue("examples").jsonObject.values) {
            val e = example.jsonObject
            commits += e.getValue("projectId").jsonPrimitive.content.toLong() to e.getValue("sha").jsonPrimitive.content
        }
        val hostile = special.getValue("hostileLines").jsonObject
        commits += hostile.getValue("projectId").jsonPrimitive.content.toLong() to hostile.getValue("sha").jsonPrimitive.content
        assertTrue(commits.size >= 3)
        commits.forEach { (projectId, sha) -> assertCommitMatches(projectId, sha) }

        val files = hostile.getValue("files") as JsonArray
        assertEquals(7, files.sumOf { it.jsonObject.getValue("additions").jsonPrimitive.int }, "the hostile commit's ground truth is 7/3")
        assertEquals(3, files.sumOf { it.jsonObject.getValue("deletions").jsonPrimitive.int })
    }
}
