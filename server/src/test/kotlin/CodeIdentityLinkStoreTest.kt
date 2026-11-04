package ch.nokillswit

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.IdentityLink
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.postgresql.util.PSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `identities/CodeIdentityLinkStore.kt` and V45's constraints on direct rows (no routes, no fixture): every write reports what it wrote,
 * and the SQL refuses the shapes the migration promises are impossible. Each test owns a unique host and removes it in a `finally`.
 */
class CodeIdentityLinkStoreTest {
    private val store = CodeIdentityLinkStore(sharedDatabaseForTests())

    private fun uniqueHost() = "test-${UUID.randomUUID().toString().take(8)}.example:443"

    private suspend fun withHostAndUser(body: suspend (host: String, userId: Long) -> Unit) {
        val host = uniqueHost()
        val userId = TestUsers.seed(uniqueEmail("links"), "pw-links-1").toLong()
        try {
            body(host, userId)
        } finally {
            store.deleteHost(host)
            TestUsers.softDelete(userId.toUInt())
        }
    }

    private fun link(
        host: String,
        userId: Long,
        key: String = "u1",
        decision: CodeIdentityDecision = CodeIdentityDecision.LINKED,
        at: Long = 1L,
    ) = IdentityLink(host, key, decision, if (decision == CodeIdentityDecision.LINKED) "acc-1" else null, userId, at)

    @Test
    fun `insertIfAbsent says true once and keeps the first decision`() = runBlocking {
        withHostAndUser { host, userId ->
            val first = link(host, userId)
            assertTrue(store.insertIfAbsent(first))
            val second = first.copy(decision = CodeIdentityDecision.IGNORED, jiraAccountId = null, decidedAt = 2L)
            assertFalse(store.insertIfAbsent(second), "a stored decision wins and the call says nothing was written")
            assertEquals(first, store.find(host, "u1"))
        }
    }

    @Test
    fun `update reports whether a row was overwritten`() = runBlocking {
        withHostAndUser { host, userId ->
            assertFalse(store.update(link(host, userId)), "no row yet")
            assertTrue(store.insertIfAbsent(link(host, userId)))
            val changed = link(host, userId, decision = CodeIdentityDecision.BOT, at = 9L)
            assertTrue(store.update(changed))
            assertEquals(changed, store.find(host, "u1"))
        }
    }

    @Test
    fun `delete returns the row it removed, then null`() = runBlocking {
        withHostAndUser { host, userId ->
            val stored = link(host, userId)
            store.insertIfAbsent(stored)
            store.insertIfAbsent(link(host, userId, key = "u2"))
            assertEquals(stored, store.delete(host, "u1"))
            assertNull(store.delete(host, "u1"))
            assertNull(store.find(host, "u1"))
            assertNotNull(store.find(host, "u2"), "only the named decision goes")
        }
    }

    @Test
    fun `insertIgnoringConflicts answers only the keys it inserted and keeps the stored decision`() = runBlocking {
        withHostAndUser { host, userId ->
            val existing = link(host, userId, key = "u2", decision = CodeIdentityDecision.IGNORED)
            store.insertIfAbsent(existing)
            val batch = listOf("u1", "u2", "u3").map { link(host, userId, key = it, decision = CodeIdentityDecision.BOT) }
            assertEquals(setOf("u1", "u3"), store.insertIgnoringConflicts(batch))
            assertEquals(existing, store.find(host, "u2"))
            assertEquals(emptySet(), store.insertIgnoringConflicts(batch), "a second pass writes nothing")
            assertEquals(emptySet(), store.insertIgnoringConflicts(emptyList()))
        }
    }

    @Test
    fun `reads are scoped to the host and forAccounts returns LINKED rows only`() = runBlocking {
        withHostAndUser { host, userId ->
            val otherHost = uniqueHost()
            try {
                store.insertIfAbsent(link(host, userId, key = "u1"))
                store.insertIfAbsent(link(host, userId, key = "u2", decision = CodeIdentityDecision.IGNORED))
                store.insertIfAbsent(link(otherHost, userId, key = "u1"))
                assertEquals(setOf("u1", "u2"), store.forHost(host).map { it.key }.toSet())
                assertEquals(listOf("u1"), store.forKeys(host, listOf("u1", "u9")).map { it.key })
                assertEquals(listOf("u1"), store.forAccounts(host, listOf("acc-1")).map { it.key })
                assertEquals(emptyList(), store.forAccounts(host, emptyList()))
            } finally {
                store.deleteHost(otherHost)
            }
        }
    }

    @Test
    fun `find with forUpdate returns the row, and null (locking nothing) when there is none`() = runBlocking {
        withHostAndUser { host, userId ->
            assertNull(store.find(host, "u1", forUpdate = true), "a missing row locks nothing")
            val stored = link(host, userId)
            store.insertIfAbsent(stored)
            assertEquals(stored, store.find(host, "u1", forUpdate = true))
        }
    }

    /** Inserts one raw row (no store, so no enum in the way); returns the violated constraint's name, or null when the row went in. */
    private fun violatedConstraint(host: String, key: String, decision: String, account: String?, userId: Long): String? =
        PostgresTestSupport.connect().use { conn ->
            conn.prepareStatement(
                "INSERT INTO code_identity_links (source_host, identity_key, decision, jira_account_id, decided_by_user_id, decided_at) " +
                    "VALUES (?, ?, ?, ?, ?, 1)",
            ).use { st ->
                st.setString(1, host)
                st.setString(2, key)
                st.setString(3, decision)
                st.setString(4, account)
                st.setLong(5, userId)
                try {
                    st.executeUpdate()
                    null
                } catch (e: SQLException) {
                    (e as? PSQLException)?.serverErrorMessage?.constraint ?: e.message ?: fail("a violation without a message")
                }
            }
        }

    @Test
    fun `the SQL refuses a malformed key, host, decision and account`() = runBlocking {
        withHostAndUser { host, userId ->
            val hex = "a".repeat(32)
            fun check(key: String = "u1", h: String = host, decision: String = "IGNORED", account: String? = null) =
                violatedConstraint(h, key, decision, account, userId)

            // The accepted shapes first: a digits key, an e-hash key, a LINKED row with an account.
            assertNull(check(key = "u${"9".repeat(19)}"))
            assertNull(check(key = "e$hex"))
            assertNull(check(key = "u2", decision = "LINKED", account = "acc-1"))

            assertEquals("ck_code_identity_links_key", check(key = "u"))
            assertEquals("ck_code_identity_links_key", check(key = "x1"))
            assertEquals("ck_code_identity_links_key", check(key = "u${"1".repeat(20)}"))
            assertEquals("ck_code_identity_links_key", check(key = "e${"A".repeat(32)}"), "the hash is lowercase hex")
            assertEquals("ck_code_identity_links_key", check(key = "e${"a".repeat(31)}"))
            assertEquals("ck_code_identity_links_key", check(key = "u1\n"), "a trailing newline is not a digit")
            assertEquals("ck_code_identity_links_host", check(h = ""))
            assertEquals("ck_code_identity_links_host", check(h = host.uppercase()))
            assertEquals("ck_code_identity_links_decision", check(decision = "MAYBE"))
            assertEquals("ck_code_identity_links_account", check(key = "u3", decision = "LINKED", account = null))
            assertEquals("ck_code_identity_links_account", check(key = "u4", decision = "LINKED", account = ""))
            assertEquals("ck_code_identity_links_account", check(key = "u5", decision = "IGNORED", account = "acc-1"))
            assertEquals("ck_code_identity_links_account", check(key = "u6", decision = "BOT", account = ""))
            assertTrue(check(h = "h".repeat(301))!!.contains("too long"), "source_host is VARCHAR(300)")
            assertNull(check(key = "u7", h = "h".repeat(300)))
            store.deleteHost("h".repeat(300))
        }
    }

    @Test
    fun `the account index is partial on LINKED and leads with the host`() {
        val definition = PostgresTestSupport.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_code_identity_links_account'").use { rs ->
                    assertTrue(rs.next(), "the index exists")
                    rs.getString(1)
                }
            }
        }
        assertTrue(definition.contains("(source_host, jira_account_id)"), definition)
        assertTrue(definition.contains("decision"), definition)
        assertTrue(definition.contains("LINKED"), definition)
    }
}
