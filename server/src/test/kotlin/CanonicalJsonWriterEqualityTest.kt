package ch.nokillswit

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.jira.trimHistory
import ch.nokillswit.jira.trimIssue
import ch.nokillswit.jira.trimWorklog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The canonical writer (`infra/json/CanonicalJson.kt`) renders a parsed tree straight to text, sorting only each object's key list,
 * where it used to build a recursively sorted COPY of the tree and print that (`.claude/docs/build-times.md` WHY 48). The stored
 * text and its sha256 are compared against stored rows to decide UNCHANGED vs CHANGED, so one byte of difference would rewrite
 * every stored row: this class pins the new writer against [oracleCanonicalJson], a verbatim copy of the old implementation, on
 * every stub fixture body the raw writers store (whole documents and every item, raw and through the three payload trims), a
 * set of hand-picked edge cases and a few thousand random trees.
 */
class CanonicalJsonWriterEqualityTest {

    /** The implementation this class replaced, kept here only as the oracle. */
    private fun oracleCanonicalJson(element: JsonElement): String = oracleCanonicalize(element).toString()

    private fun oracleCanonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key.oracleReplaceNul() }
                .associate { (key, value) -> key.oracleReplaceNul() to oracleCanonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map(::oracleCanonicalize))
        is JsonPrimitive ->
            if (element.isString && element.content.indexOf('\u0000') >= 0) JsonPrimitive(element.content.oracleReplaceNul()) else element
    }

    private fun String.oracleReplaceNul(): String = replace('\u0000', '�')

    private var comparisons = 0

    /** The new writer against the oracle on [element] and on its text, on both the tree path and the text path, hash included. */
    private fun assertSameBytes(element: JsonElement, label: String) {
        val old = oracleCanonicalJson(element)
        val oldViaText = oracleCanonicalJson(Json.parseToJsonElement(element.toString()))
        val new = canonicalJson(element)
        assertEquals(old, new, "tree path: $label")
        assertEquals(old, oldViaText, "the old tree and text paths agreed: $label")
        assertEquals(old, canonicalJson(element.toString()), "text path: $label")
        assertEquals(sha256Hex(old), sha256Hex(new), "sha256: $label")
        comparisons++
    }

    /** [element] and, for an object/array, every node down to [depth] levels of nesting. */
    private fun forEachNode(element: JsonElement, depth: Int, visit: (JsonElement) -> Unit) {
        visit(element)
        if (depth == 0) return
        when (element) {
            is JsonObject -> element.values.forEach { forEachNode(it, depth - 1, visit) }
            is JsonArray -> element.forEach { forEachNode(it, depth - 1, visit) }
            is JsonPrimitive -> Unit
        }
    }

    private fun stubBodies(): List<File> {
        val dir = listOf(File("sample-data/jira-stub/__files"), File("../sample-data/jira-stub/__files")).first { it.isDirectory }
        return dir.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
    }

    @Test
    fun `every stub fixture body, every item of it and every item through the three payload trims canonicalize byte for byte`() {
        val files = stubBodies()
        assertTrue(files.size > 500, "the corpus is the whole stub (${files.size} bodies)")
        var parsed = 0
        for (file in files) {
            val document = Json.parseToJsonElement(file.readText())
            parsed++
            forEachNode(document, depth = 3) { node ->
                assertSameBytes(node, file.name)
                if (node is JsonObject) {
                    // The writers' payloads: an issue, a changelog history, a worklog. The trims are total, so each runs on every object.
                    assertSameBytes(trimIssue(node), "${file.name} trimIssue")
                    assertSameBytes(trimHistory(node), "${file.name} trimHistory")
                    assertSameBytes(trimWorklog(node), "${file.name} trimWorklog")
                }
            }
        }
        assertEquals(files.size, parsed)
        assertTrue(comparisons > 10_000, "the stub corpus compared $comparisons trees")
    }

    private val edgeCases = listOf(
        // unicode and escapes
        """{"emoji":"🚀","name":"Zoë Żółć","cjk":"漢字","combining":"é","astral":"🚀"}""",
        """{"q":"a\"b","bs":"a\\b","sl":"a\/b","n":"a\nb","r":"a\rb","t":"a\tb","b":"\b","f":"\f","c":"\u001f\u007f\u0080  "}""",
        """{"k\"ey":1,"k\\ey":2,"k\ney":3,"k\u001fey":4,"kéy":5}""",
        // numbers: big, negative, float, exponent, zero forms
        """[123456789012345678901234567890,-123456789012345678901234567890,0,-0,0.0,-0.0,1.50,1e10,1E10,1e+10,1E-5,1.5e+300,0.1]""",
        """{"big":18446744073709551616,"neg":-9223372036854775809,"exp":2.5E-10,"int":42,"frac":3.14159265358979323846}""",
        // empty and nested containers, nulls, booleans
        """{}""", """[]""", """{"a":{},"b":[],"c":[[],{}],"d":{"e":{"f":{}}},"g":[[[]]]}""",
        """{"n":null,"t":true,"f":false,"s":"null","u":"true","z":[null,null],"o":{"x":null}}""",
        """null""", """true""", """"top level string"""", """42""", """-1.5e3""",
        // key order: case, digits, prefixes, empty key, non-BMP vs BMP-above-surrogates (UTF-16 code unit order)
        """{"b":1,"B":2,"a":3,"A":4,"10":5,"9":6,"":7,"aa":8,"a":9}""",
        """{"￿":1,"🚀":2,"":3,"z":4,"é":5,"e":6,"f":7}""",
        // a duplicate key in the source text: the parser keeps one entry (last value, first position)
        """{"z":1,"a":2,"z":3,"a":{"y":1,"x":2,"y":3}}""",
        // NUL in values, keys, arrays, and two keys that collide only after the replacement
        """{"a\u0000k":{"d":"x\u0000y"},"list":["p\u0000","q"],"top":"\u0000"}""",
        """{"a\u0000":1,"a�":2,"b":3}""",
        """{"a�":1,"a\u0000":2,"a\u0000\u0000":3,"a��":4,"c":5}""",
        """{"x\u0000":{"b":1,"a":2},"x�":{"c":3},"x\u0000\u0000":9}""",
        // an issue-shaped and a history-shaped and a worklog-shaped document, keys deliberately out of order
        """{"key":"FLO-1","id":"1","fields":{"summary":"s","description":"d","comment":{"comments":[]},"assignee":{"displayName":"A","accountId":"x","self":"u"},
            |"customfield_10001":{"value":"v","id":"1"},"project":{"key":"FLO","id":"1"}}}""".trimMargin(),
        """{"items":[{"fieldId":"description","field":"description"},{"field":"RemoteIssueLink"},{"fieldId":"status","to":"3","from":"1"}],
            |"created":"2024-01-01T00:00:00.000+0000","author":{"accountId":"a","displayName":"n","emailAddress":"e"},
            |"id":"9"}""".trimMargin(),
        """{"id":"1","self":"u","comment":"text","started":"2024-01-01T00:00:00.000+0000","timeSpentSeconds":60,
            |"author":{"accountId":"a"}}""".trimMargin(),
        """[9007199254740993,-9007199254740993]""",
    )

    @Test
    fun `hand-picked edge cases canonicalize byte for byte, raw and through the three payload trims`() {
        val before = comparisons
        for (text in edgeCases) {
            val element = Json.parseToJsonElement(text)
            assertSameBytes(element, text)
            if (element is JsonObject) {
                assertSameBytes(trimIssue(element), "trimIssue $text")
                assertSameBytes(trimHistory(element), "trimHistory $text")
                assertSameBytes(trimWorklog(element), "trimWorklog $text")
            }
        }
        assertTrue(comparisons - before >= edgeCases.size * 4 / 2)
    }

    @Test
    fun `the documented NUL rule holds - replaced in values and keys, colliding keys merge to the later value at the first position`() {
        assertEquals("""{"a�k":{"d":"x�y"}}""", canonicalJson("""{"a\u0000k":{"d":"x\u0000y"}}"""))
        assertEquals("""{"a�":2,"b":3}""", canonicalJson("""{"a\u0000":1,"b":3,"a�":2}"""))
        assertEquals("""{"a�":2,"c":5}""", canonicalJson("""{"a�":1,"a\u0000":2,"c":5}"""))
        assertEquals("""{"a��":4,"c":5}""", canonicalJson("""{"a\u0000\u0000":1,"a�\u0000":2,"c":5,"a��":4}"""))
    }

    @Test
    fun `a deeply nested document and a wide one canonicalize byte for byte`() {
        val depth = 400
        val nested = "{\"b\":1,\"a\":".repeat(depth) + "[3,1,2]" + "}".repeat(depth)
        assertSameBytes(Json.parseToJsonElement(nested), "depth $depth")
        val wide = (0 until 5_000).reversed().joinToString(prefix = "{", postfix = "}") { "\"k$it\":{\"y\":$it,\"x\":[$it]}" }
        assertSameBytes(Json.parseToJsonElement(wide), "5,000 keys")
        val dense = "[" + (0 until 100_000).joinToString(",") { """{"a":$it}""" } + "]"
        assertSameBytes(Json.parseToJsonElement(dense), "the hostile shape, 100,000 objects")
    }

    private val keyAlphabet = listOf(
        "a", "b", "A", "B", "z", "0", "9", "_", "-", " ", "é", "Ż", "漢", "🚀", "\u0000", "�", "\"", "\\", "\n", "\u007f", "￿", "",
    )

    private fun randomKey(random: Random): String = (0 until random.nextInt(0, 4)).joinToString("") { keyAlphabet.random(random) }

    private fun randomPrimitive(random: Random): JsonElement = when (random.nextInt(8)) {
        0 -> JsonNull
        1 -> JsonPrimitive(random.nextBoolean())
        2 -> JsonPrimitive(random.nextLong())
        3 -> JsonPrimitive(random.nextDouble() * 1e12 - 5e11)
        4 -> JsonPrimitive("null")
        5 -> JsonPrimitive(randomKey(random))
        6 -> JsonPrimitive((0 until random.nextInt(0, 12)).joinToString("") { keyAlphabet.random(random) + "x" })
        else -> Json.parseToJsonElement(listOf("1e10", "-0", "1.50", "123456789012345678901234567890", "2.5E-10").random(random))
    }

    private fun randomTree(random: Random, depth: Int): JsonElement = when {
        depth == 0 || random.nextInt(4) == 0 -> randomPrimitive(random)
        random.nextBoolean() -> JsonObject(
            (0 until random.nextInt(0, 6)).associate { randomKey(random) to randomTree(random, depth - 1) },
        )
        else -> JsonArray((0 until random.nextInt(0, 6)).map { randomTree(random, depth - 1) })
    }

    @Test
    fun `random trees canonicalize byte for byte`() {
        val random = Random(48)
        val before = comparisons
        repeat(3_000) { index -> assertSameBytes(randomTree(random, depth = 6), "random tree #$index") }
        assertEquals(3_000, comparisons - before)
    }
}
