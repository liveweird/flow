package ch.nokillswit

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.norm.NormCodeCommit
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.norm.NormCodeMrEvent
import ch.nokillswit.reports.CodeSource
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Report 21's hand-written world (`CodeActivityReadsTest`, `ReportCodeActivityTest`): a DISABLED GitLab connection on a unique host with
 * `norm.code_*` rows written by hand, one team with one linked member, unique Jira accounts, the links and the decider; everything is
 * removed after [withCodeWorld]. Days count from Monday 2026-03-02 at midnight in [zone]; the window is days 0-13 and now is day 14.
 *
 * Keys: `kA` (alice, LINKED to [accA], the team's member since day -100), `kB` (LINKED to [accB], a reviewer with no team), `kBot`
 * (decided BOT) and `kU` (undecided human).
 */
internal class CodeWorld(val zone: ZoneId, val source: CodeSource, val teamId: UInt, val accA: String, val accB: String) {
    val connId: UInt get() = source.connectionId

    val kA = "u9001"
    val kB = "u9002"
    val kU = "u9003"
    val kBot = "u9004"
    val base: LocalDate = LocalDate.of(2026, 3, 2)

    fun at(day: Int, hour: Int = 0): Long =
        base.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L

    val window: Pair<Long, Long> = at(0) to at(14)
    val now: Long = at(14)

    private fun commit(
        sha: String,
        key: String,
        day: Int,
        counted: Triple<Int, Int, Int>?,
        merge: Boolean = false,
        deleted: Boolean = false,
    ) = NormCodeCommit(
        1, sha, key, "t", at(day), at(day), merge, counted?.second, counted?.third, counted?.first, counted?.second, counted?.third,
        counted?.first, false, if (deleted) 1L else null,
    )

    private fun event(seq: Int, kind: MrEventKind, actor: String?, at: Long?, targets: List<String> = emptyList()) =
        NormCodeMrEvent(seq, kind, actor, at, targets)

    private fun mr(
        id: Long,
        author: String?,
        created: Int,
        state: String,
        merged: Int? = null,
        closed: Int? = null,
        draft: Boolean = false,
        size: Pair<Int, Int>? = null,
        files: Int? = null,
        details: DetailsState = DetailsState.READ,
        deleted: Boolean = false,
        events: List<NormCodeMrEvent> = emptyList(),
    ) = NormCodeMergeRequest(
        id, 1, id, state, draft, author, null, at(created), at(created), merged?.let { at(it) }, closed?.let { at(it) }, "feature", "main",
        "title $id", null, null, null, emptyList(), emptyList(), size?.first, size?.second, files, false, details,
        if (deleted) 1L else null,
        events,
    )

    private fun identity(key: String, userId: Long, username: String?, displayName: String?, bot: Boolean = false) = NormCodeIdentity(
        key, IdentityKind.USER, userId, username, displayName, "active", bot, emptyList(), null,
    )

    val identities = listOf(
        identity(kA, 9001, "alice", "Alice A"),
        identity(kB, 9002, "bob", "Bob B"),
        identity(kU, 9003, "unlinked-user", null),
        identity(kBot, 9004, "ci-bot", "CI Bot", bot = true),
    )

    val commits = listOf(
        commit("a1", kA, 1, Triple(3, 10, 2)),
        commit("a2", kA, 2, null),
        commit("a3", kA, -3, Triple(1, 1, 1)),
        commit("a4", kA, 3, Triple(1, 1, 1), merge = true),
        commit("a5", kA, 4, Triple(1, 1, 1), deleted = true),
        commit("b1", kB, 5, Triple(1, 4, 4)),
        commit("u1", kU, 6, Triple(1, 1, 1)),
    )

    val mergeRequests = listOf(
        mr(
            1, kA, 1, "merged", merged = 4, size = 20 to 5, files = 3,
            events = listOf(
                event(1, MrEventKind.REVIEW_REQUESTED, kA, at(1), listOf(kB)),
                event(2, MrEventKind.REVIEWED, kB, at(2)),
                event(3, MrEventKind.COMMENT, kB, at(3)),
                event(4, MrEventKind.APPROVAL, kB, at(3, 1)),
                event(5, MrEventKind.APPROVAL, kB, at(3, 2)),
                event(6, MrEventKind.COMMENT, kBot, at(2)),
                event(7, MrEventKind.COMMENT, kA, at(2)),
                event(8, MrEventKind.COMMENT, null, at(2)),
                event(9, MrEventKind.COMMENT, kU, null),
            ),
        ),
        mr(
            2, kA, 6, "merged", merged = 8, size = 1 to 1, files = 1,
            events = listOf(event(1, MrEventKind.REVIEW_REQUESTED, kU, at(3), listOf(kA))),
        ),
        mr(3, kA, 7, "opened", draft = true),
        mr(4, kA, -20, "opened"),
        mr(5, kA, -30, "merged", merged = -25, size = 1 to 1, files = 1),
        mr(6, kA, 9, "opened", deleted = true, events = listOf(event(1, MrEventKind.COMMENT, kB, at(9)))),
        mr(7, kU, 3, "merged", merged = 6, details = DetailsState.PENDING),
        mr(8, kA, 2, "closed", closed = 5),
        mr(9, null, 4, "merged", merged = 9, details = DetailsState.PENDING),
    )
}

/**
 * Runs [body] in a fresh [CodeWorld] (rows written through the store, links through `CodeIdentityLinkStore`, the member's membership
 * inserted directly) and removes everything in `finally`. [zone] is the zone the days' midnights are taken in (UTC for the reads, the
 * configured report zone for an HTTP test, so the report's day bounds coincide with the world's days).
 */
internal suspend fun <T> withCodeWorld(zone: ZoneId, body: suspend CodeWorld.() -> T): T {
    val db = sharedDatabaseForTests()
    val dataSources = GitLabFixtures.dataSources()
    val links = CodeIdentityLinkStore(db)
    val host = "code-world-${UUID.randomUUID().toString().take(8)}.example.test"
    val connId = GitLabFixtures.createConnection(dataSources, baseUrl = "https://$host", enabled = false, namePrefix = "code-world")
    val teamId = TestTeams.seed("code-world-${UUID.randomUUID().toString().take(8)}")
    val userId = TestUsers.seed(uniqueEmail("code-world"), "pw-code-world-1")
    val source = CodeSource(connId, "https://$host", sourceHostOf("https://$host"))
    val world = CodeWorld(zone, source, teamId, "acc-a-${UUID.randomUUID()}", "acc-b-${UUID.randomUUID()}")
    try {
        val store = GitLabFixtures.codeStore()
        store.replaceIdentities(connId, world.identities)
        store.replaceCommits(connId, world.commits, world.now)
        store.replaceMergeRequests(connId, world.mergeRequests, world.now)
        val decider = userId.toLong()
        links.insertIfAbsent(IdentityLink(source.host, world.kA, CodeIdentityDecision.LINKED, world.accA, decider, 1L))
        links.insertIfAbsent(IdentityLink(source.host, world.kB, CodeIdentityDecision.LINKED, world.accB, decider, 1L))
        links.insertIfAbsent(IdentityLink(source.host, world.kBot, CodeIdentityDecision.BOT, null, decider, 1L))
        suspendTransaction(db) {
            TeamMembershipService.TeamMembership.insert {
                it[accountId] = world.accA
                it[TeamMembershipService.TeamMembership.teamId] = teamId
                it[validFrom] = world.at(-100)
                it[validTo] = null
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
        }
        return world.body()
    } finally {
        suspendTransaction(db) {
            TeamMembershipService.TeamMembership.deleteWhere { accountId inList listOf(world.accA, world.accB) }
        }
        TestTeams.service.delete(teamId)
        links.deleteHost(source.host)
        TestUsers.softDelete(userId)
        GitLabFixtures.purgeAll(connId)
        dataSources.delete(connId)
    }
}
