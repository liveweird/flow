package ch.nokillswit

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.identities.sourceHostOf
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * `identities/IdentityResolver.kt` on direct rows (no routes, no fixture): only LINKED decisions attribute, hosts never leak into each
 * other, and `sourceHostOf` is the one spelling of an instance. V45's CHECKs are pinned in `CodeIdentityLinkStoreTest`.
 */
class IdentityResolverTest {
    private val store = CodeIdentityLinkStore(sharedDatabaseForTests())
    private val resolver = IdentityResolver(store)

    private fun uniqueHost() = "test-${UUID.randomUUID().toString().take(8)}.example"

    private fun link(host: String, userId: Long, key: String, decision: CodeIdentityDecision, account: String? = null) =
        IdentityLink(host, key, decision, account, userId, 1L)

    /** Two unique hosts and a decider; both hosts are emptied and the decider soft-deleted afterwards. */
    private suspend fun withHosts(body: suspend (host: String, other: String, userId: Long) -> Unit) {
        val host = uniqueHost()
        val other = uniqueHost()
        val userId = TestUsers.seed(uniqueEmail("resolver"), "pw-resolver-1").toLong()
        try {
            body(host, other, userId)
        } finally {
            store.deleteHost(host)
            store.deleteHost(other)
            TestUsers.softDelete(userId.toUInt())
        }
    }

    @Test
    fun `accountsByKey answers LINKED decisions only, on this host only`() = runBlocking {
        withHosts { host, other, userId ->
            store.insertIfAbsent(link(host, userId, "u1", CodeIdentityDecision.LINKED, "acc-1"))
            store.insertIfAbsent(link(host, userId, "u2", CodeIdentityDecision.IGNORED))
            store.insertIfAbsent(link(host, userId, "u3", CodeIdentityDecision.BOT))
            store.insertIfAbsent(link(other, userId, "u4", CodeIdentityDecision.LINKED, "acc-4"))
            assertEquals(mapOf("u1" to "acc-1"), resolver.accountsByKey(host, listOf("u1", "u2", "u3", "u4", "u99")))
        }
    }

    @Test
    fun `an empty input is an empty answer`() = runBlocking {
        assertEquals(emptyMap(), resolver.accountsByKey(uniqueHost(), emptyList()))
        assertEquals(emptyMap(), resolver.keysByAccount(uniqueHost(), emptySet()))
    }

    @Test
    fun `keysByAccount groups the LINKED keys per account, sorted, and skips other hosts and other decisions`() = runBlocking {
        withHosts { host, other, userId ->
            store.insertIfAbsent(link(host, userId, "u9", CodeIdentityDecision.LINKED, "acc-1"))
            store.insertIfAbsent(link(host, userId, "u10", CodeIdentityDecision.LINKED, "acc-1"))
            store.insertIfAbsent(link(host, userId, "u2", CodeIdentityDecision.LINKED, "acc-2"))
            store.insertIfAbsent(link(host, userId, "u3", CodeIdentityDecision.IGNORED))
            store.insertIfAbsent(link(other, userId, "u5", CodeIdentityDecision.LINKED, "acc-1"))
            val answer = resolver.keysByAccount(host, listOf("acc-1", "acc-2", "acc-3"))
            assertEquals(mapOf("acc-1" to listOf("u10", "u9"), "acc-2" to listOf("u2")), answer)
            assertFalse("acc-3" in answer)
        }
    }

    @Test
    fun `sourceHostOf is the lower-cased authority with a non-default port`() {
        assertEquals("gitlab.example.com", sourceHostOf("https://gitlab.example.com"))
        assertEquals("gitlab.example.com:8443", sourceHostOf("https://GitLab.Example.com:8443/"))
        assertEquals("127.0.0.1:5432", sourceHostOf("http://127.0.0.1:5432"))
    }

    @Test
    fun `sourceHostOf refuses a URL without a host, without echoing it`() {
        val failure = assertFailsWith<IllegalArgumentException> { sourceHostOf("not-a-host-url") }
        assertFalse("not-a-host-url" in failure.message.orEmpty())
        val malformed = assertFailsWith<IllegalArgumentException> { sourceHostOf("https://bad host.example") }
        assertFalse("bad host" in malformed.message.orEmpty())
    }
}
