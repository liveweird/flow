package ch.nokillswit.jira

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder

/**
 * The SQL twin of the trim functions (`JiraPayloadTrim.kt`) for the TRIM stream: a predicate over a raw table's `payload` column that
 * holds for every row the trim WOULD change, so a row that is already trimmed never leaves PostgreSQL. It is a candidate filter, never
 * the decision: the Kotlin trim still compares the parsed document with its trimmed form and writes only a real difference, so a
 * predicate that is too wide costs a read, and one that is too narrow would leave a row untrimmed - `JiraTrimStreamTest` injects every
 * dropped shape into a clone and requires it to come back clean.
 *
 * It is written without the `?`/`?|` jsonb operators (the statement text goes through a `?` placeholder scan) and guards every
 * set-returning call with `jsonb_typeof`, because `jsonb_object_keys`/`jsonb_array_elements` raise on the wrong type and a trim of
 * an unexpected shape must stay a no-op. The key lists are the Kotlin constants, so the two cannot drift.
 */
internal object JiraTrimCandidates {
    private const val PAYLOAD = "{p}"

    private fun quoted(values: Collection<String>): String = values.sorted().joinToString(", ") { "'$it'" }

    private val keptUserKeys = quoted(KEPT_USER_KEYS)

    /** [value] (a jsonb expression) is an object with a key outside [KEPT_USER_KEYS]. */
    private fun fatUser(value: String): String =
        "CASE WHEN jsonb_typeof($value) = 'object' THEN " +
            "EXISTS (SELECT 1 FROM jsonb_object_keys($value) AS k WHERE k NOT IN ($keptUserKeys)) ELSE false END"

    /** [value] is an object that has one of [keys]. */
    private fun hasAnyKey(value: String, keys: Collection<String>): String =
        "CASE WHEN jsonb_typeof($value) = 'object' THEN " +
            "EXISTS (SELECT 1 FROM jsonb_object_keys($value) AS k WHERE k IN (${quoted(keys)})) ELSE false END"

    private val issue: String = run {
        val fields = "($PAYLOAD -> 'fields')"
        val users = SLIMMED_ISSUE_USER_FIELDS.sorted().joinToString(" OR ") { fatUser("($fields -> '$it')") }
        "(${hasAnyKey(fields, DROPPED_ISSUE_FIELDS)} OR $users)"
    }

    private val history: String = run {
        val dropped = "(jsonb_typeof(it -> 'fieldId') = 'string' AND " +
            "(it ->> 'fieldId') IN (${quoted(DROPPED_HISTORY_ITEM_FIELD_IDS)})) OR " +
            "(jsonb_typeof(it -> 'fieldId') IS DISTINCT FROM 'string' AND jsonb_typeof(it -> 'field') = 'string' AND " +
            "(it ->> 'field') IN (${quoted(DROPPED_HISTORY_ITEM_FIELDS)}))"
        val items = "CASE WHEN jsonb_typeof($PAYLOAD -> 'items') = 'array' THEN " +
            "EXISTS (SELECT 1 FROM jsonb_array_elements($PAYLOAD -> 'items') AS it " +
            "WHERE jsonb_typeof(it) = 'object' AND ($dropped)) ELSE false END"
        val users = USER_REFERENCE_FIELDS.joinToString(" OR ") { fatUser("($PAYLOAD -> '$it')") }
        "CASE WHEN jsonb_typeof($PAYLOAD) = 'object' THEN ($users OR $items) ELSE false END"
    }

    private val worklog: String = run {
        val users = USER_REFERENCE_FIELDS.joinToString(" OR ") { fatUser("($PAYLOAD -> '$it')") }
        "(${hasAnyKey(PAYLOAD, DROPPED_WORKLOG_KEYS)} OR $users)"
    }

    /** The rows of [table] whose [payload] the trim would change (a superset; see the class doc). */
    fun forTable(table: TrimTable, payload: Column<String>): Op<Boolean> =
        TemplateOp(
            when (table) {
                TrimTable.ISSUES -> issue
                TrimTable.CHANGELOGS -> history
                TrimTable.WORKLOGS -> worklog
            },
            payload,
        )

    /** A boolean SQL fragment with every [PAYLOAD] replaced by the column. */
    private class TemplateOp(private val template: String, private val payload: Column<String>) : Op<Boolean>() {
        override fun toQueryBuilder(queryBuilder: QueryBuilder) {
            template.split(PAYLOAD).forEachIndexed { index, segment ->
                if (index > 0) queryBuilder.append(payload)
                queryBuilder.append(segment)
            }
        }
    }
}
