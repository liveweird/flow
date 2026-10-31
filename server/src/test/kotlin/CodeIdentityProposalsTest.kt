package ch.nokillswit

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityStatus
import ch.nokillswit.identities.IdentityProposal
import ch.nokillswit.identities.SearchableIdentity
import ch.nokillswit.identities.proposalFor
import ch.nokillswit.identities.selectPage
import ch.nokillswit.identities.statusOf
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.validation.foldForSearch
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.NormCodeIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure identity-mapping kernel (`identities/CodeIdentityProposals.kt`): proposals, status, filter/sort/page. No database. */
class CodeIdentityProposalsTest {

    private fun user(bot: Boolean = false, username: String? = "sample.dev1", emails: List<String> = listOf("user1@flow-sample.example")) =
        NormCodeIdentity(
            key = "u1",
            kind = IdentityKind.USER,
            userId = 1,
            username = username,
            displayName = "Sample User 1",
            state = "active",
            bot = bot,
            emails = emails,
            deletedAt = null,
        )

    @Test
    fun `a bot with a matching address is proposed as BOT, the flag wins`() {
        assertEquals(IdentityProposal(CodeIdentityDecision.BOT, null), proposalFor(user(bot = true), setOf("acc-1")))
        assertEquals(IdentityProposal(CodeIdentityDecision.BOT, null), proposalFor(user(bot = true), emptySet()))
    }

    @Test
    fun `exactly one matched account is proposed as LINKED to it`() {
        assertEquals(IdentityProposal(CodeIdentityDecision.LINKED, "acc-1"), proposalFor(user(), setOf("acc-1")))
    }

    @Test
    fun `two distinct accounts are ambiguous and no match proposes nothing`() {
        assertNull(proposalFor(user(), setOf("acc-1", "acc-2")))
        assertNull(proposalFor(user(), emptySet()))
    }

    @Test
    fun `a ghost proposes nothing, a deleted user with a matching address is still proposed`() {
        val ghost = user(username = null, emails = emptyList())
        assertNull(proposalFor(ghost, emptySet()))
        val deleted = user().copy(deletedAt = 1L)
        assertEquals(IdentityProposal(CodeIdentityDecision.LINKED, "acc-1"), proposalFor(deleted, setOf("acc-1")))
    }

    @Test
    fun `an email-only identity is never a bot`() {
        val emailOnly = user().copy(kind = IdentityKind.EMAIL, userId = null, username = null, bot = true)
        assertNull(proposalFor(emailOnly, emptySet()))
    }

    @Test
    fun `statusOf covers every cell, a stored decision beating any proposal`() {
        val proposal = IdentityProposal(CodeIdentityDecision.LINKED, "acc-1")
        assertEquals(CodeIdentityStatus.LINKED, statusOf(CodeIdentityDecision.LINKED, proposal))
        assertEquals(CodeIdentityStatus.LINKED, statusOf(CodeIdentityDecision.LINKED, null))
        assertEquals(CodeIdentityStatus.IGNORED_OR_BOT, statusOf(CodeIdentityDecision.IGNORED, proposal))
        assertEquals(CodeIdentityStatus.IGNORED_OR_BOT, statusOf(CodeIdentityDecision.BOT, null))
        assertEquals(CodeIdentityStatus.PROPOSED, statusOf(null, proposal))
        assertEquals(CodeIdentityStatus.UNMAPPED, statusOf(null, null))
    }

    @Test
    fun `foldForSearch drops accents and case`() {
        assertEquals("zoe", foldForSearch("Zo\u00EB"))
        assertEquals("zoe", foldForSearch("Zoe\u0308"))
        assertEquals("lukasz", foldForSearch("\u0141ukasz"))
        assertEquals("strasse", foldForSearch("Stra\u00DFe"))
    }

    private fun row(
        key: String,
        name: String? = null,
        username: String? = null,
        emails: List<String> = emptyList(),
        status: CodeIdentityStatus = CodeIdentityStatus.UNMAPPED,
    ) = SearchableIdentity(key, name, username, emails, status)

    private fun page(
        rows: List<SearchableIdentity>,
        status: CodeIdentityStatus? = null,
        q: String? = null,
        sort: List<SortField> = listOf(SortField("displayName", false), SortField("id", false)),
        page: Int = 1,
        pageSize: Int = 20,
    ) = selectPage(rows, status, q, PageRequest(page, pageSize, sort)) { it }

    private val roster = listOf(
        row("u1", "Sample User 1", "sample.dev1", listOf("user1@flow-sample.example"), CodeIdentityStatus.PROPOSED),
        row("u2", "Zoë Sample", "sample.zoe", listOf("zoe@mail.example"), CodeIdentityStatus.LINKED),
        row("u3", null, null, emptyList(), CodeIdentityStatus.UNMAPPED),
        row("u4", "Ada Sample", "sample.ada", listOf("ada.secondary@mail.example"), CodeIdentityStatus.PROPOSED),
    )

    @Test
    fun `status filter keeps only the rows in that status and totals them`() {
        val (items, total) = page(roster, status = CodeIdentityStatus.PROPOSED)
        assertEquals(listOf("u4", "u1"), items.map { it.key })
        assertEquals(2L, total)
    }

    @Test
    fun `q matches a name, a username or an address, case and accent insensitively`() {
        assertEquals(listOf("u2"), page(roster, q = "zoe").first.map { it.key })
        assertEquals(listOf("u2"), page(roster, q = "ZOË").first.map { it.key })
        assertEquals(listOf("u4"), page(roster, q = "sample.ada").first.map { it.key })
        assertEquals(listOf("u4"), page(roster, q = "Secondary@MAIL").first.map { it.key })
        assertEquals(4, page(roster, q = "   ").first.size)
        assertEquals(0L, page(roster, q = "no such text").second)
    }

    @Test
    fun `displayName sort puts absent names last, in both directions`() {
        assertEquals(listOf("u4", "u1", "u2", "u3"), page(roster).first.map { it.key })
        val descending = page(roster, sort = listOf(SortField("displayName", true), SortField("id", false)))
        assertEquals(listOf("u2", "u1", "u4", "u3"), descending.first.map { it.key })
    }

    @Test
    fun `username sort descending and the id tiebreaker`() {
        val byUsername = page(roster, sort = listOf(SortField("username", true), SortField("id", false)))
        assertEquals(listOf("u2", "u1", "u4", "u3"), byUsername.first.map { it.key })
        val twins = listOf(row("u9", "Same"), row("u5", "Same"), row("u7", "Same"))
        assertEquals(listOf("u5", "u7", "u9"), page(twins).first.map { it.key })
        val byIdDescending = page(twins, sort = listOf(SortField("id", true)))
        assertEquals(listOf("u9", "u7", "u5"), byIdDescending.first.map { it.key })
    }

    @Test
    fun `paging returns the requested slice, the filtered total and an empty page past the end`() {
        val many = (1..8).map { row("u$it", "Name $it") }
        val second = page(many, page = 2, pageSize = 3)
        assertEquals(listOf("u4", "u5", "u6"), second.first.map { it.key })
        assertEquals(8L, second.second)
        val last = page(many, page = 3, pageSize = 3)
        assertEquals(listOf("u7", "u8"), last.first.map { it.key })
        val beyond = page(many, page = 4, pageSize = 3)
        assertEquals(emptyList(), beyond.first)
        assertEquals(8L, beyond.second)
        assertEquals(emptyList(), page(many, page = Int.MAX_VALUE, pageSize = 100).first)
    }
}
