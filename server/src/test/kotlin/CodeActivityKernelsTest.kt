package ch.nokillswit

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.metrics.TeamMembershipService.MembershipInterval
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.reports.CodeAttribution
import ch.nokillswit.reports.CodeCommitRow
import ch.nokillswit.reports.CodeEventRow
import ch.nokillswit.reports.CodeFlowFigures
import ch.nokillswit.reports.CodeIdentities
import ch.nokillswit.reports.CodeKernelContext
import ch.nokillswit.reports.CodeKeyActivity
import ch.nokillswit.reports.CodeMember
import ch.nokillswit.reports.CodeMemberFigures
import ch.nokillswit.reports.CodeMrRef
import ch.nokillswit.reports.CodeMrRow
import ch.nokillswit.reports.CodeQualityCounts
import ch.nokillswit.reports.CodeReviewSummary
import ch.nokillswit.reports.CodeSelection
import ch.nokillswit.reports.PickedAccounts
import ch.nokillswit.reports.codeQuality
import ch.nokillswit.reports.distribution
import ch.nokillswit.reports.flowFigures
import ch.nokillswit.reports.memberFigures
import ch.nokillswit.reports.openFigures
import ch.nokillswit.reports.reviewConcentration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of report 21 (`CodeActivityKernels.kt`, `CodeActivityMembers.kt`; domain-model A50, measures.md "Report 21"): one
 * hand-built world, every figure computed by hand. Days are counted from Monday 2026-03-02 (UTC, Saturday and Sunday off), the window
 * is three Monday weeks (days 0-20) and now is day 21.
 *
 * People: alice `u1`, bob `u2` and carol `u3` are LINKED (acc-a, acc-b, acc-c); `u4` is LINKED (acc-d, a member of team 7 from day 10
 * only); `u5` is an unlinked human; `u9` is decided BOT and `u6` IGNORED. Team 7 holds alice, carol and, until day 10, bob (then team 8).
 */
class CodeActivityKernelsTest {
    private val zone = ZoneId.of("UTC")
    private val calendar = WorkingCalendar(zone, setOf(6, 7), emptySet<LocalDate>())
    private val base = LocalDate.of(2026, 3, 2)
    private val conn = 1u

    private fun at(day: Int, hour: Int = 0): Long =
        base.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L

    private val identities = CodeIdentities.of(
        listOf(conn), "gl.test",
        decisions = mapOf(
            "u1" to CodeIdentityDecision.LINKED, "u2" to CodeIdentityDecision.LINKED, "u3" to CodeIdentityDecision.LINKED,
            "u4" to CodeIdentityDecision.LINKED, "u9" to CodeIdentityDecision.BOT, "u6" to CodeIdentityDecision.IGNORED,
        ),
        accounts = mapOf("u1" to "acc-a", "u2" to "acc-b", "u3" to "acc-c", "u4" to "acc-d"),
    )
    private val memberships = mapOf(
        "acc-a" to listOf(MembershipInterval(7u, 0, null)),
        "acc-b" to listOf(MembershipInterval(7u, 0, at(10)), MembershipInterval(8u, at(10), null)),
        "acc-c" to listOf(MembershipInterval(7u, 0, null)),
        "acc-d" to listOf(MembershipInterval(7u, at(10), null)),
    )

    private fun context(team: UInt, picks: Set<String>? = null, minSample: Int = 1) = CodeKernelContext(
        CodeSelection(team, picks), CodeAttribution(identities, memberships), at(0) to at(21), at(21), calendar, zone, minSample,
    )

    private fun commit(sha: String, key: String, day: Int, counted: Triple<Int, Int, Int>?) = CodeCommitRow(
        conn, 1, sha, key, at(day), counted?.second, counted?.third, counted?.first,
    )

    private fun mr(
        id: Long,
        author: String?,
        created: Int,
        merged: Int? = null,
        state: String = if (merged != null) "merged" else "opened",
        draft: Boolean = false,
        size: Pair<Int, Int>? = null,
        files: Int? = null,
        read: Boolean = true,
        closed: Int? = null,
    ) = CodeMrRow(
        conn, id, 1, id, state, draft, author, at(created), merged?.let { at(it) }, closed?.let { at(it) }, size?.first, size?.second,
        files, read,
    )

    private fun event(
        mrId: Long,
        kind: MrEventKind,
        actor: String?,
        author: String,
        at: Long?,
        targets: List<String> = emptyList(),
        seq: Int = 1,
    ) = CodeEventRow(CodeMrRef(conn, mrId), seq, kind, actor, at, targets, author)

    private fun review(mrId: Long, actor: String, firstDay: Int?, approved: Boolean = false) =
        CodeReviewSummary(CodeMrRef(conn, mrId), actor, firstDay?.let { at(it) }, approved)

    private val commits = listOf(
        commit("c1", "u1", 1, Triple(3, 10, 2)),
        commit("c2", "u1", 2, null),
        commit("c3", "u2", 5, Triple(1, 5, 5)),
        commit("c4", "u2", 12, Triple(2, 7, 0)),
        commit("c5", "u5", 3, Triple(1, 1, 1)),
        commit("c6", "u4", 3, Triple(1, 2, 2)),
        commit("c7", "u9", 3, Triple(1, 9, 9)),
    )

    private val mrs = listOf(
        mr(1, "u1", created = 0, merged = 2, size = 30 to 10, files = 4),
        mr(2, "u1", created = 3, merged = 15, files = 3),
        mr(3, "u2", created = 4, merged = 12, read = false),
        mr(4, "u3", created = 9, merged = 16, size = 1 to 1, files = 1),
        mr(5, "u1", created = 14),
        mr(6, "u3", created = 17, draft = true),
        mr(7, "u1", created = 1, state = "closed", closed = 6),
        mr(8, "u5", created = 2, merged = 5, size = 4 to 4, files = 2),
        mr(10, "u9", created = 1, merged = 5, size = 1 to 1, files = 1),
        mr(11, "u9", created = 15),
    )

    private val summaries = listOf(
        review(1, "u2", 1, approved = true),
        review(2, "u1", 4),
        review(2, "u9", 5, approved = true),
        review(2, "u6", 6),
        review(4, "u5", 10),
        review(8, "u2", null),
    )

    private val events = listOf(
        event(1, MrEventKind.APPROVAL, "u2", "u1", at(1)),
        event(1, MrEventKind.APPROVAL, "u2", "u1", at(1, 12), seq = 2),
        event(1, MrEventKind.COMMENT, "u2", "u1", at(1, 6), seq = 3),
        event(2, MrEventKind.COMMENT, "u1", "u1", at(4)),
        event(4, MrEventKind.DIFF_COMMENT, "u2", "u3", at(9, 12)),
        event(4, MrEventKind.CHANGES_REQUESTED, "u2", "u3", at(9), seq = 2),
        event(1, MrEventKind.COMMENT, "u3", "u1", null, seq = 4),
        event(2, MrEventKind.COMMENT, "u2", "u1", at(12), seq = 2),
        event(1, MrEventKind.COMMENT, "u9", "u1", at(2), seq = 5),
    )

    private val requests = listOf(
        event(2, MrEventKind.REVIEW_REQUESTED, "u1", "u1", at(3), targets = listOf("u2", "u3")),
        event(2, MrEventKind.REVIEW_REQUESTED, "u1", "u1", at(3, 6), targets = listOf("u2"), seq = 2),
        event(5, MrEventKind.REVIEW_REQUESTED, "u1", "u1", at(12), targets = listOf("u2")),
    )

    private fun members(team: UInt, roster: List<String> = emptyList(), picks: Set<String>? = null): Map<CodeMember, CodeMemberFigures> {
        val ctx = context(team, picks)
        val flow = ctx.flowFigures(mrs, summaries)
        return ctx.memberFigures(flow, commits, mrs, events, requests, roster).associateBy { it.member }
    }

    private fun flow(team: UInt, picks: Set<String>? = null): CodeFlowFigures = context(team, picks).flowFigures(mrs, summaries)

    private fun member(account: String) = CodeMember(account, null)

    private fun unlinked(key: String) = CodeMember(null, key, "gl.test")

    @Test
    fun `team 7 flow, attributed to the author's team at merged_at`() {
        val flow = flow(7u)
        // m1, m2 (alice) and m4 (carol) merged in team 7; m3 (bob) merged on day 12, after bob moved to team 8; m8 has no team.
        assertEquals(listOf(1L, 2L, 4L), flow.mrs.map { it.scoped.row.mrId })
        assertEquals(3, flow.merged)
        assertEquals(1, flow.closedUnmerged)
        // Working days: m1 Mon->Wed = 2, m2 Thu day 3 -> Tue day 15 = 8, m4 Wed day 9 -> Wed day 16 = 5; elapsed 2, 12, 7.
        assertEquals(listOf(2.0, 8.0, 5.0), flow.mrs.map { it.cycleWorkingDays })
        assertEquals(listOf(2.0, 12.0, 7.0), flow.mrs.map { it.cycleElapsedDays })
        // Size: m2's totals are unknown (null, never 0); its file count is known.
        assertEquals(listOf(40.0, null, 2.0), flow.mrs.map { it.sizeLines })
        assertEquals(listOf(4.0, 3.0, 1.0), flow.mrs.map { it.sizeFiles })
    }

    @Test
    fun `time to first review pairs the first human review that is not the author's, a BOT's or an IGNORED actor's`() {
        val flow = flow(7u)
        // m1: bob on day 1 (1 working day). m2: alice reviewed her own, a BOT approved and an IGNORED actor commented: nobody counts.
        // m4: an unlinked HUMAN (u5) on day 10 counts (1 working day).
        assertEquals(listOf(1.0, null, 1.0), flow.mrs.map { it.firstReviewWorkingDays })
        assertTrue(flow.mrs[1].review!!.firstReviewAt == null && !flow.mrs[1].review!!.approved && flow.mrs[1].review!!.reviewers.isEmpty())
        assertEquals(setOf("acc-b"), flow.mrs[0].review!!.reviewers)
        assertEquals(setOf("key:gl.test:u5"), flow.mrs[2].review!!.reviewers)
    }

    @Test
    fun `merged without review and without approval are counted among the merge requests whose details were read`() {
        val flow = flow(7u)
        // m2 has no review by another human; m2 and m4 have no approval by another human (the BOT's approval is not one).
        assertEquals(1, flow.mergedWithoutReview.count)
        assertEquals(1.0 / 3, flow.mergedWithoutReview.share!!, 1e-12)
        assertEquals(2, flow.mergedWithoutApproval.count)
        assertEquals(2.0 / 3, flow.mergedWithoutApproval.share!!, 1e-12)
    }

    @Test
    fun `a merge request whose details were not read is excluded from the review figures, never unreviewed`() {
        val flow = flow(8u)
        assertEquals(listOf(3L), flow.mrs.map { it.scoped.row.mrId })
        assertNull(flow.mrs.single().review)
        assertNull(flow.mrs.single().firstReviewWorkingDays)
        assertEquals(0, flow.mergedWithoutReview.count)
        assertNull(flow.mergedWithoutReview.share)
        assertNull(flow.mergedWithoutApproval.share)
        // Friday day 4 -> Saturday day 12: Fri, Mon, Tue, Wed, Thu, Fri.
        assertEquals(6.0, flow.mrs.single().cycleWorkingDays)
    }

    @Test
    fun `an actor with only undated events leaves no first review`() {
        val ctx = context(7u)
        val flow = ctx.flowFigures(listOf(mr(1, "u1", created = 0, merged = 2)), listOf(review(1, "u2", null)))
        assertNull(flow.mrs.single().review!!.firstReviewAt)
        assertEquals(1, flow.mergedWithoutReview.count)
    }

    @Test
    fun `the weekly bars cover every Monday-started week of the window, zero-filled, and add up to merged`() {
        val flow = flow(7u)
        assertEquals(listOf(base, base.plusDays(7), base.plusDays(14)), flow.weekly.map { it.bucketStart })
        assertEquals(listOf(1, 0, 2), flow.weekly.map { it.merged })
        assertEquals(flow.merged, flow.weekly.sumOf { it.merged })
    }

    @Test
    fun `the distributions are over the merge requests that have a value, and hidden under the minimum sample`() {
        val flow = flow(7u)
        val none = PickedAccounts(emptyList(), emptyMap())
        val cycle = flow.distribution(minSample = 1, tailPercent = 0, picked = none) { it.cycleWorkingDays }
        assertEquals(3, cycle.n)
        assertEquals(5.0, cycle.p50)
        val size = flow.distribution(minSample = 1, tailPercent = 0, picked = none) { it.sizeLines }
        assertEquals(2, size.n)
        val hidden = flow.distribution(minSample = 4, tailPercent = 0, picked = none) { it.cycleWorkingDays }
        assertTrue(hidden.hidden)
        assertNull(hidden.p50)
    }

    @Test
    fun `at USER level only the picks' merge requests count and the distribution splits per person`() {
        val picked = PickedAccounts(listOf("acc-a"), mapOf("acc-a" to "Alice"))
        val ctx = context(7u, picks = setOf("acc-a"))
        val flow = ctx.flowFigures(mrs, summaries)
        assertEquals(listOf(1L, 2L), flow.mrs.map { it.scoped.row.mrId })
        val cycle = flow.distribution(1, 0, picked) { it.cycleWorkingDays }
        assertEquals(2, cycle.n)
        assertEquals(listOf("acc-a"), cycle.byUser.map { it.accountId })
        assertEquals(cycle.histogram.map { it.count }, cycle.byUser.single().counts)
    }

    @Test
    fun `review concentration counts distinct reviewers per merge request and the busiest reviewer's share`() {
        val flow = flow(7u)
        // Reviewers per merged merge request: m1 {bob} = 1, m2 {} = 0, m4 {u5} = 1 -> p50 1; pairs = 2, the busiest holds one: 0.5.
        val review = reviewConcentration(flow, minSample = 1)
        assertEquals(3, review.n)
        assertEquals(1.0, review.reviewersPerMergedMrP50)
        assertEquals(0.5, review.topReviewerShare)
        val hidden = reviewConcentration(flow, minSample = 4)
        assertEquals(3, hidden.n)
        assertNull(hidden.reviewersPerMergedMrP50)
        assertNull(hidden.topReviewerShare)
    }

    @Test
    fun `open merge requests are the author's current team, drafts are counted apart and are not in the age`() {
        val open = context(7u).openFigures(mrs)
        // m5 (alice, created Monday day 14, five working days before now) is waiting; m6 (carol) is a draft.
        assertEquals(1, open.count)
        assertEquals(1, open.drafts)
        assertEquals(5.0, open.ageP50)
        assertEquals(5.0, open.ageP85)
        assertEquals(false, open.ageHidden)
        val hidden = context(7u, minSample = 2).openFigures(mrs)
        assertEquals(1, hidden.count)
        assertTrue(hidden.ageHidden)
        assertNull(hidden.ageP50)
        // Bob's merged m3 and the closed m7 are not open.
        assertEquals(0, context(8u).openFigures(mrs).count)
    }

    @Test
    fun `team 7 members, with the roster listed at zero`() {
        val m = members(7u, roster = listOf("acc-a", "acc-b", "acc-c", "acc-d"))
        val alice = m.getValue(member("acc-a"))
        // c1 counted (3 files, +10, -2); c2 has no counted figures: unknown, left out of the sums and counted.
        assertEquals(2, alice.commits)
        assertEquals(3L, alice.filesChanged)
        assertEquals(10L, alice.linesAdded)
        assertEquals(2L, alice.linesDeleted)
        assertEquals(1, alice.commitsLinesUnknown)
        assertEquals(2, alice.mrsMerged)
        assertEquals(4, alice.mrsOpened)
        assertEquals(1, alice.openMrs)
        assertEquals(5.0, alice.mrCycleWorkingDaysP50)
        assertEquals(40.0, alice.mrSizeLinesP50)
        // Her own comment counts as a comment and as no review.
        assertEquals(1, alice.comments)
        assertEquals(0, alice.reviewsGiven)

        val bob = m.getValue(member("acc-b"))
        assertEquals(1, bob.commits)
        assertEquals(5L, bob.linesAdded)
        assertEquals(0, bob.mrsMerged)
        assertNull(bob.mrCycleWorkingDaysP50)
        assertEquals(1, bob.mrsOpened)
        // Approve, comment, approve again on m1 and a diff comment and changes requested on m4: two merge requests reviewed, ONE approval.
        assertEquals(2, bob.reviewsGiven)
        assertEquals(1, bob.approvalsGiven)
        assertEquals(1, bob.changesRequestedGiven)
        assertEquals(2, bob.comments)
        // Asked twice on m2 (one merge request) while in team 7; the request on m5 came after he moved.
        assertEquals(1, bob.reviewRequestsReceived)

        val carol = m.getValue(member("acc-c"))
        assertEquals(0, carol.commits)
        assertEquals(1, carol.mrsMerged)
        assertEquals(2, carol.mrsOpened)
        assertEquals(1, carol.openMrs)
        // Her undated comment is in no period: no comment, no review.
        assertEquals(0, carol.comments)
        assertEquals(0, carol.reviewsGiven)
        assertEquals(1, carol.reviewRequestsReceived)

        // Dave joined on day 10: his day-3 commit is not team 7's, but the roster keeps him visible with zeros.
        assertEquals(0, m.getValue(member("acc-d")).commits)
        assertNull(m[unlinked("u5")])
    }

    @Test
    fun `a mover's work splits by anchor between his two teams`() {
        val t8 = members(8u)
        val bob = t8.getValue(member("acc-b"))
        // After day 10: his commit on day 12, his merge request merged on day 12, his comment on m2 and the request on m5.
        assertEquals(1, bob.commits)
        assertEquals(7L, bob.linesAdded)
        assertEquals(1, bob.mrsMerged)
        assertEquals(6.0, bob.mrCycleWorkingDaysP50)
        assertEquals(1, bob.comments)
        assertEquals(1, bob.reviewRequestsReceived)
        assertEquals(0, bob.mrsOpened)
        assertEquals(setOf(member("acc-b")), t8.keys)
    }

    @Test
    fun `team 0 is the unlinked residue and the linked with no membership at the anchor`() {
        val t0 = members(0u)
        // u5 is unlinked; dave (u4) is linked but had no team on day 3.
        // The BOT u9 has a commit, two merge requests and a comment: a decision, so in no group and no figure.
        assertEquals(setOf(unlinked("u5"), member("acc-d")), t0.keys)
        assertEquals(1, t0.getValue(unlinked("u5")).commits)
        assertEquals(1, t0.getValue(unlinked("u5")).mrsMerged)
        assertEquals(2L, t0.getValue(member("acc-d")).linesAdded)
        val flow = flow(0u)
        assertEquals(listOf(8L), flow.mrs.map { it.scoped.row.mrId })
        assertEquals(0, context(0u).openFigures(mrs).count, "the BOT's open merge request is nobody's")
    }

    @Test
    fun `the quality block counts the activity of undecided keys only, and the unknown counts`() {
        val activity = listOf(
            CodeKeyActivity(conn, "u1", commits = 2, mergeRequests = 3, events = 1),
            CodeKeyActivity(conn, "u5", commits = 1, mergeRequests = 1, events = 0),
            CodeKeyActivity(conn, "u9", commits = 0, mergeRequests = 0, events = 5),
        )
        val quality = codeQuality(activity, CodeQualityCounts(4, 3, 2, 1), identities)
        // Only u5 (undecided) needs an admin: u1 is LINKED and u9 is a BOT decision, which counts nowhere.
        assertEquals(1, quality.unlinkedIdentities)
        assertEquals(1, quality.unlinkedCommits)
        assertEquals(1, quality.unlinkedMergeRequests)
        assertEquals(0, quality.unlinkedEvents)
        assertEquals(4, quality.commitsLinesUnknown)
        assertEquals(3, quality.mrsSizeUnknown)
        assertEquals(2, quality.mrsDetailsNotRead)
        assertEquals(1, quality.eventsUndated)
    }

    @Test
    fun `the same key on two hosts is two people - groups, quality and the busiest reviewer agree`() {
        val twoHosts = CodeIdentities(
            mapOf(1u to "h1", 2u to "h2"),
            accountByHostKey = mapOf("h1" to mapOf("u1" to "acc-a"), "h2" to mapOf("u3" to "acc-c")),
            decisionByHostKey = mapOf(
                "h1" to mapOf("u1" to CodeIdentityDecision.LINKED), "h2" to mapOf("u3" to CodeIdentityDecision.LINKED),
            ),
        )
        fun context(team: UInt) = CodeKernelContext(
            CodeSelection(team, null), CodeAttribution(twoHosts, memberships), at(0) to at(21), at(21), calendar, zone, 1,
        )
        fun merged(connection: UInt, author: String) =
            CodeMrRow(connection, 1, 1, 1, "merged", false, author, at(0), at(2), null, 1, 1, 1, true)
        fun commit(connection: UInt, key: String) = CodeCommitRow(connection, 1, "s$connection", key, at(1), 1, 1, 1)
        // The undecided "u42" reviewed one merge request on each host: two reviewers, each with one pair.
        val mrs = listOf(merged(1u, "u1"), merged(2u, "u3"))
        val summaries = listOf(
            CodeReviewSummary(CodeMrRef(1u, 1), "u42", at(1), approved = false),
            CodeReviewSummary(CodeMrRef(2u, 1), "u42", at(1), approved = false),
        )
        val flow = context(7u).flowFigures(mrs, summaries)
        assertEquals(listOf(setOf("key:h1:u42"), setOf("key:h2:u42")), flow.mrs.map { it.review!!.reviewers })
        assertEquals(0.5, reviewConcentration(flow, 1).topReviewerShare)

        // In the UNASSIGNED bucket the two "u42" are two members, and two unlinked identities in the quality block.
        val ctx = context(0u)
        val commits = listOf(commit(1u, "u42"), commit(2u, "u42"))
        val noFlow = ctx.flowFigures(emptyList(), emptyList())
        val members = ctx.memberFigures(noFlow, commits, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf(CodeMember(null, "u42", "h1"), CodeMember(null, "u42", "h2")), members.map { it.member })
        assertEquals(listOf(1, 1), members.map { it.commits })
        val activity = listOf(CodeKeyActivity(1u, "u42", 1, 0, 0), CodeKeyActivity(2u, "u42", 1, 0, 0))
        assertEquals(2, codeQuality(activity, CodeQualityCounts(0, 0, 0, 0), twoHosts).unlinkedIdentities)
    }

    @Test
    fun `two keys linked to one account are one author, so a review under the other key is a self-review`() {
        val twoKeys = CodeIdentities.of(
            listOf(conn), "gl.test",
            decisions = mapOf(
                "u1" to CodeIdentityDecision.LINKED, "e1" to CodeIdentityDecision.LINKED, "u2" to CodeIdentityDecision.LINKED,
            ),
            accounts = mapOf("u1" to "acc-a", "e1" to "acc-a", "u2" to "acc-b"),
        )
        val ctx = CodeKernelContext(
            CodeSelection(7u, null), CodeAttribution(twoKeys, memberships), at(0) to at(21), at(21), calendar, zone, 1,
        )
        val flow = ctx.flowFigures(
            listOf(mr(1, "u1", created = 0, merged = 2)),
            listOf(review(1, "e1", 1, approved = true), review(1, "u2", 1)),
        )
        val review = assertNotNull(flow.mrs.single().review)
        assertEquals(setOf("acc-b"), review.reviewers)
        assertEquals(false, review.approved)
    }
}
