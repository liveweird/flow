package ch.nokillswit

import ch.nokillswit.norm.fieldValueOptions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `norm/NormPickerReads.kt`'s `fieldValueOptions` (v0.3.0 M1 commit 4 review fix) — the defensive
 * JSON-shape parser `distinctCustomFieldValues` feeds every `custom_fields[fieldId]` value
 * through, since the field could be ANY custom field an admin picks, not necessarily a `select`.
 * `internal` (the `ingest/DataSourceService.kt` `backoffMillis` precedent) makes it directly
 * unit-testable without a database.
 */
class WorkItemStoreFieldValueOptionsTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `null element yields nothing`() {
        assertEquals(emptyList(), fieldValueOptions(null))
    }

    @Test
    fun `JSON null yields nothing`() {
        assertEquals(emptyList(), fieldValueOptions(parse("null")))
    }

    @Test
    fun `an object with id and value uses id as the key and value as the name`() {
        assertEquals(listOf("10001" to "Product Development"), fieldValueOptions(parse("""{"id":"10001","value":"Product Development"}""")))
    }

    @Test
    fun `an object with only value (no id) uses value as both key and name`() {
        assertEquals(listOf("Maintenance" to "Maintenance"), fieldValueOptions(parse("""{"value":"Maintenance"}""")))
    }

    @Test
    fun `an object with id and name (no value) uses id as the key and name as the name`() {
        val values = fieldValueOptions(parse("""{"id":"10002","name":"Cost of Poor Quality"}"""))
        assertEquals(listOf("10002" to "Cost of Poor Quality"), values)
    }

    @Test
    fun `an object with neither id nor value nor name yields nothing`() {
        assertEquals(emptyList(), fieldValueOptions(parse("""{"self":"https://example.atlassian.net/x"}""")))
    }

    @Test
    fun `a bare string primitive uses itself as both key and name`() {
        assertEquals(listOf("Ad-hoc" to "Ad-hoc"), fieldValueOptions(parse("\"Ad-hoc\"")))
    }

    @Test
    fun `a blank string primitive yields nothing`() {
        assertEquals(emptyList(), fieldValueOptions(parse("\"\"")))
    }

    @Test
    fun `a bare number primitive uses its text form as both key and name`() {
        assertEquals(listOf("42" to "42"), fieldValueOptions(parse("42")))
    }

    @Test
    fun `an array flattens every element - a multi-select field`() {
        val values = fieldValueOptions(parse("""[{"id":"1","value":"A"},{"id":"2","value":"B"}]"""))
        assertEquals(listOf("1" to "A", "2" to "B"), values)
    }

    @Test
    fun `an empty array yields nothing`() {
        assertEquals(emptyList(), fieldValueOptions(parse("[]")))
    }
}
