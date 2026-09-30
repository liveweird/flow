package ch.nokillswit

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.parseStringArray
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Canonical JSON (`infra/json/CanonicalJson.kt`, the first stored-`jsonb` consumer): recursive
 * key sorting, order-preserving arrays, and a stable sha256 digest over the result — plus the
 * shared string-array codec (`infra/json/JsonArrays.kt`) and the shared day constant
 * (`infra/time/Millis.kt`) that the stores and steps used to each re-declare.
 */
class CanonicalJsonTest {

    @Test
    fun `object key order does not affect the canonical form`() {
        assertEquals(canonicalJson("""{"b":2,"a":1}"""), canonicalJson("""{"a":1,"b":2}"""))
    }

    @Test
    fun `key sorting is recursive, into nested objects`() {
        val shuffled = """{"outer":{"z":1,"a":2},"m":3}"""
        val sorted = """{"m":3,"outer":{"a":2,"z":1}}"""
        assertEquals(canonicalJson(sorted), canonicalJson(shuffled))
    }

    @Test
    fun `arrays keep their own order - position is meaning, not sorted like object keys`() {
        val canonical = canonicalJson("""{"list":[3,1,2]}""")
        assertEquals("""{"list":[3,1,2]}""", canonical)
        assertNotEquals(canonicalJson("""{"list":[1,2,3]}"""), canonical)
    }

    @Test
    fun `numbers and strings survive canonicalization unchanged`() {
        assertEquals(canonicalJson("""{"a":1.50,"b":"text"}"""), canonicalJson("""{"b":"text","a":1.50}"""))
    }

    @Test
    fun `unicode content is preserved and stable`() {
        val raw = """{"emoji":"🚀","name":"Zoë Żółć"}"""
        val canonical = canonicalJson(raw)
        assertEquals(canonical, canonicalJson(canonical), "canonicalization is idempotent")
        assertEquals(canonicalJson("""{"name":"Zoë Żółć","emoji":"🚀"}"""), canonical)
    }

    @Test
    fun `canonicalization is idempotent for a deeply nested payload`() {
        val raw = """{"c":[{"y":1,"x":2},{"b":true,"a":null}],"a":{"nested":{"z":1,"y":2}}}"""
        val once = canonicalJson(raw)
        assertEquals(once, canonicalJson(once))
    }

    @Test
    fun `sha256Hex is stable for equivalent payloads and differs for different ones`() {
        val hashA = sha256Hex(canonicalJson("""{"b":2,"a":1}"""))
        val hashB = sha256Hex(canonicalJson("""{"a":1,"b":2}"""))
        assertEquals(hashA, hashB, "key-order-equivalent payloads hash the same once canonicalized")
        assertEquals(64, hashA.length)
        assertTrue(hashA.all { it.isDigit() || it in 'a'..'f' })
        assertNotEquals(hashA, sha256Hex(canonicalJson("""{"a":1,"b":3}""")))
    }

    @Test
    fun `stringArrayJson renders a compact array in list order and parseStringArray inverts it`() {
        assertEquals("""["b","a"]""", stringArrayJson(listOf("b", "a")))
        assertEquals("[]", stringArrayJson(emptyList()))
        val awkward = listOf("quote\"", "back\\slash", "zażółć", "")
        assertEquals(awkward, parseStringArray(stringArrayJson(awkward)))
        assertEquals(emptyList(), parseStringArray("[]"))
    }

    @Test
    fun `MILLIS_PER_DAY is one fixed 24-hour day`() {
        assertEquals(86_400_000L, MILLIS_PER_DAY)
    }
}
