package ch.nokillswit

import ch.nokillswit.norm.CODE_PROCESSING_VERSION
import ch.nokillswit.norm.CountedFile
import ch.nokillswit.norm.LineStats
import ch.nokillswit.norm.MrEventInput
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.norm.countLines
import ch.nokillswit.norm.identityKeyForEmail
import ch.nokillswit.norm.identityKeyForUser
import ch.nokillswit.norm.noreplyUserId
import ch.nokillswit.norm.orderEvents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The pure code-layer kernel (`norm/CodeNormalization.kt`): identity keys, the counted-lines rule, the event order. No database. */
class CodeNormalizationTest {
    private val unmatched by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).firstOrNull { it.isFile }
            ?: error("sample-data/gitlab/expected.json not found from ${File(".").absolutePath}")
        val users = Json.parseToJsonElement(file.readText()).jsonObject.getValue("users").jsonObject
        users.getValue("unmatchedAuthors").jsonArray[0].jsonObject
    }

    @Test
    fun `identity keys are u-id and a hash of the canonical email, never the address`() {
        assertEquals("u7", identityKeyForUser(7))
        val email = unmatched.getValue("email").jsonPrimitive.content
        val expected = unmatched.getValue("identityKey").jsonPrimitive.content
        assertEquals(expected, identityKeyForEmail(email))
        assertEquals(expected, identityKeyForEmail(" ${email.replaceFirstChar { it.uppercase() }.uppercase()} "))
        assertEquals(33, expected.length)
        assertNotEquals(email, expected)
    }

    @Test
    fun `the noreply pattern names the user id and a bot address names nobody`() {
        assertEquals(2L, noreplyUserId("2-sample.dev1@users.noreply.gitlab.flow-sample.example"))
        assertEquals(123L, noreplyUserId("123-x@users.noreply.example"))
        assertNull(noreplyUserId("project_3_bot_4f1a9c2e7b@noreply.gitlab.flow-sample.example"))
        assertNull(noreplyUserId("user1@flow-sample.example"))
        assertNull(noreplyUserId("x-2-y@users.noreply.example"))
    }

    private fun lines(files: List<CountedFile>, stats: LineStats = LineStats(null, null), excluded: Set<String> = emptySet()) =
        countLines(files, stats) { it in excluded }

    @Test
    fun `no exclusions sums the files and an excluded file drops out of files and lines`() {
        val files = listOf(CountedFile("a.kt", 3, 1), CountedFile("package-lock.json", 50, 40))
        val all = lines(files, LineStats(53, 41))
        assertEquals(Triple(2, 53, 41), Triple(all.files, all.additions, all.deletions))
        val kept = lines(files, LineStats(53, 41), excluded = setOf("package-lock.json"))
        assertEquals(Triple(1, 3, 1), Triple(kept.files, kept.additions, kept.deletions))
    }

    private val tooLarge = listOf(
        CountedFile("package-lock.json", 59, 66),
        CountedFile("src/data/README.md", 10, 9),
        CountedFile("src/data/catalog.json", null, null),
    )

    @Test
    fun `the too_large fallback credits the remainder of the stats to the unknown file`() {
        val counted = lines(tooLarge, LineStats(5269, 215), excluded = setOf("package-lock.json"))
        assertEquals(Triple(2, 5210, 149), Triple(counted.files, counted.additions, counted.deletions))
    }

    @Test
    fun `an excluded unknown file drops the remainder`() {
        val counted = lines(tooLarge, LineStats(5269, 215), excluded = setOf("package-lock.json", "src/data/catalog.json"))
        assertEquals(Triple(1, 10, 9), Triple(counted.files, counted.additions, counted.deletions))
    }

    @Test
    fun `two unknown files with one excluded credit the remainder once`() {
        val files = listOf(CountedFile("a", 10, 1), CountedFile("big1", null, null), CountedFile("big2", null, null))
        val counted = lines(files, LineStats(110, 21), excluded = setOf("big2"))
        assertEquals(Triple(2, 110, 21), Triple(counted.files, counted.additions, counted.deletions))
    }

    @Test
    fun `an included unknown file with missing stats leaves the lines unknown but still counts the file`() {
        val counted = lines(listOf(CountedFile("a", 5, 5), CountedFile("big", null, null)), LineStats(null, null))
        assertEquals(2, counted.files)
        assertNull(counted.additions)
        assertNull(counted.deletions)
    }

    @Test
    fun `a capped commit gets no remainder - an included unknown file leaves the lines unknown, otherwise the known lines are summed`() {
        val files = listOf(CountedFile("a", 10, 4), CountedFile("big", null, null))
        val capped = countLines(files, LineStats(5000, 900), capped = true) { false }
        val unknown = Triple(capped.files, capped.additions, capped.deletions)
        assertEquals(Triple(2, null, null), unknown, "the stats cover files that were never stored")
        val uncapped = countLines(files, LineStats(5000, 900)) { false }
        assertEquals(Triple(2, 5000, 900), Triple(uncapped.files, uncapped.additions, uncapped.deletions))
        val unknownExcluded = countLines(files, LineStats(5000, 900), capped = true) { it == "big" }
        assertEquals(Triple(1, 10, 4), Triple(unknownExcluded.files, unknownExcluded.additions, unknownExcluded.deletions))
        val allKnown = countLines(listOf(CountedFile("a", 10, 4), CountedFile("b", 1, 1)), LineStats(5000, 900), capped = true) { false }
        val bound = Triple(allKnown.files, allKnown.additions, allKnown.deletions)
        assertEquals(Triple(2, 11, 5), bound, "a lower bound over the stored files")
    }

    @Test
    fun `a binary file is a known zero and a negative remainder is clamped`() {
        val binary = lines(listOf(CountedFile("logo.png", 0, 0)), LineStats(0, 0))
        assertEquals(Triple(1, 0, 0), Triple(binary.files, binary.additions, binary.deletions))
        val clamped = lines(listOf(CountedFile("a", 10, 10), CountedFile("big", null, null)), LineStats(4, 4))
        assertEquals(Triple(2, 10, 10), Triple(clamped.files, clamped.additions, clamped.deletions))
    }

    @Test
    fun `additions and deletions are computed independently`() {
        val files = listOf(CountedFile("a", 7, null), CountedFile("b", null, 3))
        val counted = lines(files, LineStats(20, 10))
        // additions: known 7 + remainder (20 - 7); deletions: known 3 + remainder (10 - 3).
        assertEquals(Triple(2, 20, 10), Triple(counted.files, counted.additions, counted.deletions))
    }

    private fun input(kind: MrEventKind, actor: String?, at: Long?, source: String = "NOTE", id: Long = 1) =
        MrEventInput(kind, actor, at, emptyList(), source, id)

    @Test
    fun `events are ordered deterministically with nulls last and a contiguous seq`() {
        val events = listOf(
            input(MrEventKind.APPROVAL, "u2", null, "APPROVAL", 2),
            input(MrEventKind.COMMENT, "u1", 20),
            input(MrEventKind.COMMENT, null, 10, id = 5),
            input(MrEventKind.APPROVAL, "u1", 20),
            input(MrEventKind.COMMENT, "u1", 20, id = 9),
            input(MrEventKind.COMMENT, "u1", 20, id = 3),
        )
        val ordered = orderEvents(events)
        assertEquals((1..events.size).toList(), ordered.map { it.seq })
        assertEquals(
            listOf(MrEventKind.COMMENT to null, MrEventKind.APPROVAL to "u1", MrEventKind.COMMENT to "u1", MrEventKind.COMMENT to "u1"),
            ordered.take(4).map { it.kind to it.actorKey },
        )
        assertEquals(MrEventKind.APPROVAL to "u2", ordered.last().kind to ordered.last().actorKey)
        repeat(20) {
            assertEquals(ordered, orderEvents(events.shuffled(java.util.Random(it.toLong()))))
        }
    }

    @Test
    fun `the code processing version is positive`() {
        assertEquals(true, CODE_PROCESSING_VERSION > 0)
    }
}
