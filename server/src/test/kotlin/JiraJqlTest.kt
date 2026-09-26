package ch.nokillswit

import ch.nokillswit.jira.JiraJql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** JQL construction (v0.2.0 plan §6/§7): project keys arrive pre-validated (`PROJECT_KEY_PATTERN`), so no quoting is ever needed. */
class JiraJqlTest {

    @Test
    fun `scope renders a quoted project-in list`() {
        assertEquals("project in (\"ENG\")", JiraJql.scope(listOf("ENG")))
        assertEquals("project in (\"ENG\",\"OPS\")", JiraJql.scope(listOf("ENG", "OPS")))
    }

    @Test
    fun `scope rejects an empty list or a key outside the validated shape`() {
        assertFailsWith<IllegalArgumentException> { JiraJql.scope(emptyList()) }
        assertFailsWith<IllegalArgumentException> { JiraJql.scope(listOf("lowercase")) }
        assertFailsWith<IllegalArgumentException> { JiraJql.scope(listOf("HAS SPACE")) }
        assertFailsWith<IllegalArgumentException> { JiraJql.scope(listOf("HAS\"QUOTE")) }
        assertFailsWith<IllegalArgumentException> { JiraJql.scope(listOf("1STARTSWITHDIGIT")) }
    }

    @Test
    fun `incremental adds a relative updated bound ordered ascending`() {
        assertEquals(
            "project in (\"ENG\") AND updated >= \"-15m\" ORDER BY updated ASC",
            JiraJql.incremental(listOf("ENG"), 15),
        )
    }

    @Test
    fun `incremental rejects a negative minute window`() {
        assertFailsWith<IllegalArgumentException> { JiraJql.incremental(listOf("ENG"), -1) }
    }

    @Test
    fun `reconcile orders by id ascending with no date bound`() {
        assertEquals("project in (\"ENG\") ORDER BY id ASC", JiraJql.reconcile(listOf("ENG")))
    }
}
