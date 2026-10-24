package ch.nokillswit

import ch.nokillswit.auth.PasswordResetService.IssueOutcome
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.verifyPassword
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.flowusers.updatePasswordInTransaction
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The grant store (`auth/PasswordResetService.kt`, V35) over the shared container: digests, expiry, single use, staleness. */
class PasswordResetServiceTest {
    @Test
    fun `only digests are stored and exact expiry and delivery revocation reject grants`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val resets = newPasswordResetService(100) { now }
        val email = uniqueEmail("reset-store")
        val id = TestUsers.seed(email, "old-password")
        val token = assertNotNull(resets.issueToken(id, 0, email))
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val hashes = resetTokenHashes(id)
        assertEquals(1, hashes.size)
        assertTrue(hashes.single().matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(token, hashes.single())
        assertTrue(resets.isUsable(token))
        assertFalse(resets.isUsable(hashes.single()), "the stored digest is not a usable token")
        assertFalse(resets.isUsable("a".repeat(43)))
        assertNull(resets.complete("malformed", "hash"))
        assertNull(resets.complete("a".repeat(43), "hash"))
        now += 100
        assertFalse(resets.isUsable(token), "a grant dies at exactly its expiry instant")
        assertNull(resets.complete(token, "hash"))
        val fresh = assertNotNull(resets.issueToken(id, 0, email))
        assertEquals(1, resetTokenHashes(id).size, "issuance prunes expired grants")
        resets.revoke(fresh)
        assertFalse(resets.isUsable(fresh))
        assertTrue(resetTokenHashes(id).isEmpty())
        assertTrue(verifyPassword("old-password", TestUsers.service.read(id)!!.passwordHash), "a grant never touches the password")
        assertEquals(0L, TestUsers.service.read(id)!!.credentialRevision)
        assertFailsWith<IllegalArgumentException> { newPasswordResetService(0) }
        assertFailsWith<IllegalArgumentException> { newPasswordResetService(3_600_001) }
    }

    @Test
    fun `confirmation is single use across instances and invalidates sibling links`() = testApplication {
        usePostgresTestcontainer()
        val first = newPasswordResetService()
        val second = newPasswordResetService()
        for (sameToken in listOf(true, false)) {
            val email = uniqueEmail("reset-race")
            val id = TestUsers.seed(email, "old-password")
            val tokenA = assertNotNull(first.issueToken(id, 0, email))
            val tokenB = if (sameToken) tokenA else assertNotNull(second.issueToken(id, 0, email))
            val hash = hashPassword("new-password", cost = 4)
            val outcomes = coroutineScope {
                listOf(async { first.complete(tokenA, hash) }, async { second.complete(tokenB, hash) }).awaitAll()
            }
            assertEquals(listOf(id), outcomes.filterNotNull(), "exactly one confirmation wins")
            assertEquals(1, outcomes.count { it == null })
            assertFalse(second.isUsable(tokenA))
            assertFalse(first.isUsable(tokenB))
            assertNull(second.complete(tokenA, hash))
            assertTrue(resetTokenHashes(id).isEmpty(), "completing deletes every grant of the user")
            assertEquals(1L, TestUsers.service.read(id)!!.credentialRevision)
            assertTrue(verifyPassword("new-password", TestUsers.service.read(id)!!.passwordHash))
        }
    }

    @Test
    fun `a password change, an email change and a deletion invalidate issued and in-flight grants but a role change does not`() =
        testApplication {
            usePostgresTestcontainer()
            val resets = newPasswordResetService()
            val email = uniqueEmail("reset-stale")
            val id = TestUsers.seed(email, "old-password")

            val passwordLink = assertNotNull(resets.issueToken(id, 0, email))
            TestUsers.service.updatePassword(id, hashPassword("admin-password", cost = 4))
            assertFalse(resets.isUsable(passwordLink))
            assertNull(resets.complete(passwordLink, "hash"))
            assertEquals(IssueOutcome.Stale, resets.issue(id, 0, email), "an in-flight issue holding the old revision mints nothing")

            val emailLink = assertNotNull(resets.issueToken(id, 1, email))
            val newEmail = uniqueEmail("reset-renamed")
            TestUsers.service.updateGuarded(id, "Name", newEmail, FlowUserRole.ADMIN)
            assertFalse(resets.isUsable(emailLink), "a link mailed to an address the account no longer has is dead")
            assertNull(resets.complete(emailLink, "hash"))
            assertEquals(IssueOutcome.Stale, resets.issue(id, 1, email), "an in-flight issue holding the old email mints nothing")
            assertEquals(1L, TestUsers.service.read(id)!!.credentialRevision, "an email edit does not bump the credential revision")

            val roleLink = assertNotNull(resets.issueToken(id, 1, newEmail))
            TestUsers.service.updateGuarded(id, "Name", newEmail, FlowUserRole.USER)
            assertTrue(resets.isUsable(roleLink), "a role change leaves the mailbox trust, and the link, intact")

            TestUsers.softDelete(id)
            assertFalse(resets.isUsable(roleLink))
            assertNull(resets.complete(roleLink, "hash"))
            assertEquals(IssueOutcome.Stale, resets.issue(id, 1, newEmail))
            assertEquals(IssueOutcome.Stale, resets.issue(999999999u, 0, "nobody@test"))
        }

    @Test
    fun `an account holds at most five live grants, earlier links stay valid and expiry frees the slots`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val resets = newPasswordResetService(1000) { now }
        val email = uniqueEmail("reset-cap")
        val id = TestUsers.seed(email, "old-password")
        val otherEmail = uniqueEmail("reset-cap-other")
        val otherId = TestUsers.seed(otherEmail, "old-password")
        val tokens = (1..5).map { assertNotNull(resets.issueToken(id, 0, email)) }
        assertEquals(IssueOutcome.Capped, resets.issue(id, 0, email), "the sixth live grant is refused")
        assertEquals(5, resetTokenHashes(id).size, "a refusal stores nothing")
        assertTrue(tokens.all { resets.isUsable(it) }, "earlier links stay valid")
        assertIs<IssueOutcome.Issued>(resets.issue(otherId, 0, otherEmail), "the cap is per account")
        assertEquals(IssueOutcome.Stale, resets.issue(id, 7, email), "a moved account is stale, not capped")
        now += 1000
        assertIs<IssueOutcome.Issued>(resets.issue(id, 0, email), "expired grants no longer count")
        assertEquals(1, resetTokenHashes(id).size)
    }

    @Test
    fun `grants made dead by an email or password change hold no slot under the cap`() = testApplication {
        usePostgresTestcontainer()
        val resets = newPasswordResetService()
        val email = uniqueEmail("reset-cap-dead")
        val id = TestUsers.seed(email, "old-password")
        repeat(5) { assertNotNull(resets.issueToken(id, 0, email)) }
        val newEmail = uniqueEmail("reset-cap-moved")
        TestUsers.service.updateGuarded(id, "Name", newEmail, FlowUserRole.ADMIN)
        val atNewAddress = assertNotNull(resets.issueToken(id, 0, newEmail), "links to the old address do not cap the new one")
        assertEquals(1, resetTokenHashes(id).size, "the dead grants are dropped")
        repeat(4) { assertNotNull(resets.issueToken(id, 0, newEmail)) }
        TestUsers.service.updatePassword(id, hashPassword("admin-password", cost = 4))
        assertIs<IssueOutcome.Issued>(resets.issue(id, 1, newEmail), "links from before a password change do not cap")
        assertFalse(resets.isUsable(atNewAddress))
        assertEquals(1, resetTokenHashes(id).size)
    }

    @Test
    fun `concurrent issues across instances cannot exceed the cap`() = testApplication {
        usePostgresTestcontainer()
        val first = newPasswordResetService()
        val second = newPasswordResetService()
        val email = uniqueEmail("reset-cap-race")
        val id = TestUsers.seed(email, "old-password")
        val outcomes = coroutineScope {
            (0 until 10).map { index -> async { (if (index % 2 == 0) first else second).issue(id, 0, email) } }.awaitAll()
        }
        assertEquals(5, outcomes.count { it is IssueOutcome.Issued })
        assertEquals(5, outcomes.count { it == IssueOutcome.Capped })
        assertEquals(5, resetTokenHashes(id).size)
    }

    @Test
    fun `only one revision-conditional password writer wins when a reset races a conditional write`() = testApplication {
        usePostgresTestcontainer()
        val resets = newPasswordResetService()
        val email = uniqueEmail("reset-cas")
        val id = TestUsers.seed(email, "old-password")
        val token = assertNotNull(resets.issueToken(id, 0, email))
        val hash = hashPassword("new-password", cost = 4)
        val outcomes = coroutineScope {
            val reset = async { resets.complete(token, hash) }
            val change = async {
                suspendTransaction(sharedDatabaseForTests()) { updatePasswordInTransaction(id, hash, expectedRevision = 0) }
            }
            (reset.await() != null) to (change.await() == 1)
        }
        assertNotEquals(outcomes.first, outcomes.second, "one wins, the other sees a moved revision")
        assertEquals(1L, TestUsers.service.read(id)!!.credentialRevision)
    }

    @Test
    fun `a completed reset followed by an admin password write both succeed and the admin's password is final`() = testApplication {
        usePostgresTestcontainer()
        val resets = newPasswordResetService()
        val email = uniqueEmail("reset-then-admin")
        val id = TestUsers.seed(email, "old-password")
        val token = assertNotNull(resets.issueToken(id, 0, email))
        assertEquals(id, resets.complete(token, hashPassword("owner-password", cost = 4)))
        // The admin PUT is unconditional (no expected revision): it layers on the reset instead of failing against it.
        assertEquals(1, TestUsers.service.updatePassword(id, hashPassword("admin-password", cost = 4)))
        val account = assertNotNull(TestUsers.service.read(id))
        assertEquals(2L, account.credentialRevision, "each write bumped the revision once")
        assertTrue(verifyPassword("admin-password", account.passwordHash))
        assertFalse(verifyPassword("owner-password", account.passwordHash))
        assertFalse(resets.isUsable(token))
    }
}
