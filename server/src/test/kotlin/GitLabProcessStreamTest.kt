package ch.nokillswit

import ch.nokillswit.gitlab.CommitKey
import ch.nokillswit.gitlab.GitLabDiffStats
import ch.nokillswit.gitlab.GitLabProcessStream
import ch.nokillswit.gitlab.PROCESS_FAILED_PAGES_MAX
import ch.nokillswit.gitlab.ProcessFailure
import ch.nokillswit.gitlab.ProcessNormalizers
import ch.nokillswit.gitlab.codeCommit
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.gitlab.GitLabRawStore.Users
import ch.nokillswit.gitlab.DETAILS_ERROR_NOT_FOUND
import ch.nokillswit.gitlab.MrDetailsOutcome
import ch.nokillswit.infra.json.parseStringArray
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.infra.validation.PathGlob
import ch.nokillswit.ingest.DEFAULT_PATH_EXCLUSIONS
import ch.nokillswit.norm.CODE_PROCESSING_VERSION
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.norm.NormCodeCommit
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.failureDetail
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.IThrowableProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.BeforeClass
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitLab PROCESS stream (`gitlab/GitLabProcessStream.kt`, V44's `norm.code_*`) against the stub's independent oracle
 * (`sample-data/gitlab/expected.json`). The shared fixture is only READ; every test that changes anything clones its own connection
 * (`SyncedGitLabFixture.cloneRawData` / `cloneProcessedData`, `GitLabFixtures.purgeAll` in the cleanup).
 */
class GitLabProcessStreamTest {
    private val code = GitLabFixtures.codeStore()
    private val processStore = GitLabFixtures.processStore()
    private val raw = GitLabFixtures.rawStore()
    private val connections = mutableListOf<UInt>()
    private val fixtureId: UInt by lazy { runBlocking { SyncedGitLabFixture.connectionId() } }

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { GitLabFixtures.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private val expected: JsonObject by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    /** A clone of the fixture's RAW tables, every row flagged for PROCESS (a fresh sync's state, the norm layer empty). */
    private suspend fun rawClone(): UInt = GitLabFixtures.createConnection().also {
        connections += it
        SyncedGitLabFixture.cloneRawData(fixtureId, it)
        processStore.markAllNeedsProcessing(it, commitsOnly = false)
    }

    private fun globs(patterns: List<String> = DEFAULT_PATH_EXCLUSIONS) = patterns.map(PathGlob::compile)

    private suspend fun process(
        connId: UInt,
        patterns: List<String> = DEFAULT_PATH_EXCLUSIONS,
        normalizers: ProcessNormalizers = ProcessNormalizers(),
    ): Map<String, Long> = GitLabFixtures.runStream(GitLabProcessStream(processStore, code, globs(patterns), normalizers), connId)

    private fun oracleCommit(projectId: Long, sha: String): JsonObject =
        expected.getValue("commitFiles").jsonObject.getValue(projectId.toString()).jsonObject.getValue(sha).jsonObject

    /**
     * The files the store counts for a commit under [patterns]: the oracle lists every `/diff` ENTRY, the store keeps the first 1,000
     * entries and ONE row per path (a delete plus a re-add of one path is one stored file), so the oracle's `counted.files` can exceed
     * it by those pairs while the lines agree exactly.
     */
    private fun storedFilesCounted(projectId: Long, sha: String, patterns: List<String>): Int {
        val paths = oracleCommit(projectId, sha).getValue("files").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }
        return paths.take(1000).distinct().count { !PathGlob.matchesAny(globs(patterns), it) }
    }

    private fun List<NormCodeCommit>.storedFilesTotal(patterns: List<String>) = sumOf { storedFilesCounted(it.projectId, it.sha, patterns) }

    private fun List<NormCodeCommit>.countedTotals() =
        Triple(sumOf { it.filesCounted ?: 0 }, sumOf { it.additionsCounted ?: 0 }, sumOf { it.deletionsCounted ?: 0 })

    private fun totals(obj: JsonObject) =
        Triple(
            obj.getValue("files").jsonPrimitive.int,
            obj.getValue("additions").jsonPrimitive.int,
            obj.getValue("deletions").jsonPrimitive.int,
        )

    // ---------------------------------------------------------------------------------------------- the fixture, read-only

    @Test
    fun `the fixture's commits match the oracle - counted lines, files, identities, projects`() = runBlocking<Unit> {
        val commits = code.commitRows(fixtureId)
        assertEquals(300, commits.size)
        assertEquals(19, commits.count { it.isMerge })
        val rawBySha = raw.commitRows(fixtureId).associateBy { it[Commits.projectId] to it[Commits.sha] }
        val nonMerge = commits.filterNot { it.isMerge }
        assertEquals(281, nonMerge.size)
        nonMerge.forEach { commit ->
            val oracle = oracleCommit(commit.projectId, commit.sha).getValue("counted").jsonObject
            val label = "${commit.projectId}/${commit.sha}"
            assertEquals(totals(oracle).second to totals(oracle).third, commit.additionsCounted to commit.deletionsCounted, label)
            assertEquals(storedFilesCounted(commit.projectId, commit.sha, DEFAULT_PATH_EXCLUSIONS), commit.filesCounted, label)
            assertEquals(rawBySha.getValue(commit.projectId to commit.sha)[Commits.filesChanged], commit.files)
        }
        commits.filter { it.isMerge }.forEach { merge ->
            assertEquals(Triple(null, null, null), Triple(merge.filesCounted, merge.additionsCounted, merge.deletionsCounted))
            assertNull(merge.files)
        }
        val inScope = expected.getValue("pathExclusions").jsonObject.getValue("inScopeTotals").jsonObject
        val oracleCounted = totals(inScope.getValue("counted").jsonObject)
        assertEquals(oracleCounted.second to oracleCounted.third, nonMerge.countedTotals().let { it.second to it.third })
        assertEquals(nonMerge.storedFilesTotal(DEFAULT_PATH_EXCLUSIONS), nonMerge.countedTotals().first)
        val rawTotals = inScope.getValue("raw").jsonObject
        assertEquals(rawTotals.getValue("additions").jsonPrimitive.int, nonMerge.sumOf { it.additions ?: 0 })
        assertEquals(1, nonMerge.count { it.filesCapped }, "the one commit over the file cap")

        // The independent identity oracle: noreply addresses, a secondary email, the bot and the unmatched author.
        val byKey = nonMerge.groupBy { it.identityKey }
        for (identity in expected.getValue("identities").jsonArray.map { it.jsonObject }) {
            val key = identity.getValue("key").jsonPrimitive.content
            val rows = byKey.getValue(key)
            assertEquals(identity.getValue("commitsNonMerge").jsonPrimitive.int, rows.size, key)
            val perProject = identity.getValue("perProject").jsonObject.mapValues { it.value.jsonPrimitive.int }
            assertEquals(perProject, rows.groupingBy { it.projectId.toString() }.eachCount(), key)
        }
        assertEquals(expected.getValue("identities").jsonArray.size, byKey.size, "no other identity key appears")

        val identities = code.identityRows(fixtureId)
        assertEquals(9, identities.size)
        assertEquals(8, identities.count { it.kind == IdentityKind.USER })
        val unmatched = expected.getValue("users").jsonObject.getValue("unmatchedAuthors").jsonArray[0].jsonObject
        val emailRow = identities.single { it.kind == IdentityKind.EMAIL }
        assertEquals(unmatched.getValue("identityKey").jsonPrimitive.content, emailRow.key)
        assertEquals(unmatched.getValue("name").jsonPrimitive.content, emailRow.displayName)
        assertEquals(listOf(unmatched.getValue("email").jsonPrimitive.content), emailRow.emails)
        val rawUsers = raw.userRows(fixtureId).associate { it[Users.userId] to parseStringArray(it[Users.emails]) }
        identities.filter { it.kind == IdentityKind.USER }.forEach { assertEquals(rawUsers.getValue(it.userId!!), it.emails, it.key) }
        assertEquals(4, code.projectRows(fixtureId).count { it.deletedAt == null && it.movedOutAt == null })
        assertEquals(0L, processStore.countNeedsProcessing(fixtureId))
    }

    @Test
    fun `the fixture's merge requests and events match the oracle`() = runBlocking<Unit> {
        val mrs = code.mergeRequestRows(fixtureId)
        assertEquals(60, mrs.size)
        assertEquals(mapOf("merged" to 34L, "opened" to 16L, "closed" to 8L, "locked" to 2L), code.mergeRequestFigures(fixtureId).byState)
        assertEquals(59, mrs.count { it.additions != null })
        val gap = expected.getValue("mergeRequests").jsonObject.getValue("nullDiffStatsGap").jsonObject
        val gapped = mrs.single { it.additions == null }
        assertEquals(gap.getValue("projectId").jsonPrimitive.long to gap.getValue("iid").jsonPrimitive.long, gapped.projectId to gapped.iid)
        assertTrue(mrs.all { it.detailsState == DetailsState.READ })
        for (listed in expected.getValue("mergeRequests").jsonObject.getValue("list").jsonArray.map { it.jsonObject }) {
            val project = listed.getValue("projectId").jsonPrimitive.long
            val row = mrs.single { it.projectId == project && it.iid == listed.getValue("iid").jsonPrimitive.long }
            assertEquals("u${listed.getValue("authorId").jsonPrimitive.long}", row.authorKey)
            assertEquals(listed.getValue("reviewerIds").jsonArray.map { "u${it.jsonPrimitive.long}" }.sorted(), row.reviewerKeys)
        }
        val counts = code.eventCounts(fixtureId)
        assertEquals(269L, counts.getValue(MrEventKind.COMMENT))
        assertEquals(90L, counts.getValue(MrEventKind.DIFF_COMMENT))
        assertEquals(76L, counts.getValue(MrEventKind.REVIEW_REQUESTED))
        assertEquals(14L, counts.getValue(MrEventKind.REVIEWED))
        assertTrue(counts.getValue(MrEventKind.APPROVAL) in 40L..80L, "approvals: ${counts.getValue(MrEventKind.APPROVAL)}")
        val changes = counts.getValue(MrEventKind.CHANGES_REQUESTED)
        assertTrue(changes in 10L..14L, "changes requested: $changes")
        val events = code.mrEventRows(fixtureId)
        events.forEach { (mrId, list) ->
            assertEquals((1..list.size).toList(), list.map { it.seq }, "seq is contiguous from 1 for $mrId")
            for (kind in listOf(MrEventKind.APPROVAL, MrEventKind.CHANGES_REQUESTED)) {
                val actors = list.filter { it.kind == kind && it.actorKey != null }.map { it.actorKey }
                assertEquals(actors.distinct(), actors, "no merge request has two $kind events by one actor ($mrId)")
            }
            list.filter { it.kind == MrEventKind.REVIEW_REQUESTED }.forEach {
                assertTrue(it.targetKeys.isNotEmpty(), "a resolved target ($mrId)")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- REPROCESS and flags

    @Test
    fun `REPROCESS rebuilds exactly the incremental state, and a version downgrade claims exactly that row`() = runBlocking<Unit> {
        val connId = rawClone()
        val first = process(connId)
        assertEquals(300L, first["commitsProcessed"])
        assertEquals(60L, first["mergeRequestsProcessed"])
        assertEquals(9L, first["identities"])
        assertNull(first["exclusionsChanged"], "the first run only stores the fingerprints: nothing CHANGED")
        assertNull(first["identityMapChanged"])
        assertNull(first["usernameMapChanged"])
        assertEquals(0L, processStore.countNeedsProcessing(connId))
        val digest = GitLabFixtures.codeDigest(connId)
        assertEquals(digest, GitLabFixtures.codeDigest(fixtureId), "the clone's PROCESS equals the fixture's own")

        assertEquals(360, processStore.markAllNeedsProcessing(connId, commitsOnly = false))
        val again = process(connId)
        assertEquals(300L, again["commitsProcessed"])
        assertEquals(digest, GitLabFixtures.codeDigest(connId), "REPROCESS digest equals the incremental state")
        assertEquals(300, code.commitRows(connId).size)

        val commit = raw.commitRows(connId).first()
        val mr = raw.mergeRequestRows(connId).first()
        suspendTransaction(sharedDatabaseForTests()) {
            val ofCommit = (Commits.connectionId eq connId) and (Commits.projectId eq commit[Commits.projectId]) and
                (Commits.sha eq commit[Commits.sha])
            Commits.update({ ofCommit }) {
                it[processingVersion] = CODE_PROCESSING_VERSION - 1
            }
            MergeRequests.update({ (MergeRequests.connectionId eq connId) and (MergeRequests.mrId eq mr[MergeRequests.mrId]) }) {
                it[processingVersion] = CODE_PROCESSING_VERSION + 1
            }
        }
        val commitKey = CommitKey(commit[Commits.projectId], commit[Commits.sha])
        assertEquals(listOf(commitKey), processStore.commitsToProcess(connId, CODE_PROCESSING_VERSION, 50))
        assertEquals(listOf(mr[MergeRequests.mrId]), processStore.mergeRequestsToProcess(connId, CODE_PROCESSING_VERSION, 50))
        val third = process(connId)
        assertEquals(listOf(1L, 1L), listOf(third["commitsProcessed"], third["mergeRequestsProcessed"]))
        assertEquals(digest, GitLabFixtures.codeDigest(connId))
    }

    @Test
    fun `tombstones, refilled stats and a terminal details error are mirrored and nothing else moves`() = runBlocking<Unit> {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        SyncedGitLabFixture.cloneProcessedData(fixtureId, connId)
        val commit = code.commitRows(connId).first { !it.isMerge }
        val mr = code.mergeRequestRows(connId).first { it.additions != null }
        val gap = code.mergeRequestRows(connId).single { it.additions == null }
        val settled = code.mergeRequestRows(connId).first { it.mrId != mr.mrId && it.mrId != gap.mrId && it.additions != null }
        val others = code.commitRows(connId).filter { it.sha != commit.sha }

        assertEquals(1, raw.tombstoneCommits(connId, commit.projectId, listOf(commit.sha), 5_000L))
        assertEquals(1, raw.tombstoneMergeRequests(connId, listOf(mr.mrId), 5_000L))
        assertEquals(1, raw.writeMrDiffStats(connId, gap.projectId, mapOf(gap.iid to GitLabDiffStats(7, 3, 2))))
        assertTrue(raw.settleMrDetails(connId, settled.mrId, emptyList(), 6_000L, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND)))
        val progress = process(connId)

        assertEquals(1L, progress["commitsProcessed"])
        assertEquals(3L, progress["mergeRequestsProcessed"])
        assertEquals(5_000L, code.commitRows(connId).single { it.sha == commit.sha }.deletedAt)
        assertEquals(others, code.commitRows(connId).filter { it.sha != commit.sha }, "no other commit row moved")
        val rows = code.mergeRequestRows(connId).associateBy { it.mrId }
        assertEquals(5_000L, rows.getValue(mr.mrId).deletedAt)
        assertEquals(Triple(7, 3, 2), rows.getValue(gap.mrId).let { Triple(it.additions, it.deletions, it.fileCount) })
        assertEquals(DetailsState.UNREADABLE, rows.getValue(settled.mrId).detailsState)
        assertNull(code.mrEventRows(connId, settled.mrId)[settled.mrId], "an unreadable merge request has no norm events")
        assertTrue(raw.mrEventRows(connId, settled.mrId).isNotEmpty(), "while its raw events stand")
        assertEquals(0L, processStore.countNeedsProcessing(connId))
    }

    // ---------------------------------------------------------------------------------------------- exclusions (U1, U2)

    @Test
    fun `a changed exclusion list re-flags every commit at the next run and the figures follow`() = runBlocking<Unit> {
        val connId = rawClone()
        val tooLarge = expected.getValue("special").jsonObject.getValue("tooLarge").jsonObject
        val tooLargeSha = tooLarge.getValue("sha").jsonPrimitive.content
        val tooLargeProject = tooLarge.getValue("projectId").jsonPrimitive.long
        val stats = tooLarge.getValue("commitStats").jsonObject

        val noneRun = process(connId, emptyList())
        assertEquals(300L, noneRun["commitsProcessed"])
        val unfiltered = code.commitRows(connId).single { it.projectId == tooLargeProject && it.sha == tooLargeSha }
        assertEquals(
            Triple(3, stats.getValue("additions").jsonPrimitive.int, stats.getValue("deletions").jsonPrimitive.int),
            Triple(unfiltered.filesCounted, unfiltered.additionsCounted, unfiltered.deletionsCounted),
            "nothing excluded: the unknown file's remainder completes the stats",
        )
        val many = expected.getValue("special").jsonObject.getValue("manyFiles").jsonObject
        val inScope = expected.getValue("pathExclusions").jsonObject.getValue("inScopeTotals").jsonObject
        val rawTotals = totals(inScope.getValue("raw").jsonObject)
        val capped = totals(many.getValue("firstCappedFilesTotals").jsonObject)
        val all = totals(many.getValue("allFilesTotals").jsonObject)
        val nonMerge = code.commitRows(connId).filterNot { it.isMerge }
        val unfilteredTotals = nonMerge.countedTotals()
        assertEquals(
            (rawTotals.second - (all.second - capped.second)) to (rawTotals.third - (all.third - capped.third)),
            unfilteredTotals.second to unfilteredTotals.third,
            "every stored file counted: the raw lines minus the capped commit's unstored files",
        )
        assertEquals(nonMerge.storedFilesTotal(emptyList()), unfilteredTotals.first)

        val changed = process(connId)
        assertEquals(1L, changed["exclusionsChanged"])
        assertEquals(300L, changed["commitsProcessed"], "every commit, merges included, is re-processed")
        assertNull(changed["mergeRequestsProcessed"], "merge request line counts are not exclusion-aware")
        val counted = expected.getValue("pathExclusions").jsonObject.getValue("inScopeTotals").jsonObject.getValue("counted").jsonObject
        val filteredTotals = code.commitRows(connId).filterNot { it.isMerge }.countedTotals()
        assertEquals(totals(counted).second to totals(counted).third, filteredTotals.second to filteredTotals.third)
        assertEquals(code.commitRows(connId).filterNot { it.isMerge }.storedFilesTotal(DEFAULT_PATH_EXCLUSIONS), filteredTotals.first)
        val filtered = code.commitRows(connId).single { it.projectId == tooLargeProject && it.sha == tooLargeSha }
        assertEquals(Triple(2, 5210, 149), Triple(filtered.filesCounted, filtered.additionsCounted, filtered.deletionsCounted))

        val third = process(connId)
        assertNull(third["exclusionsChanged"])
        assertNull(third["commitsProcessed"], "the same list: nothing is flagged")
        val codeCursors = SyncCursorsService(sharedDatabaseForTests()).getAll(connId).map { it.stream }.filter { it.startsWith("code_") }
        assertEquals(listOf("code_process"), codeCursors)
    }

    // ---------------------------------------------------------------------------------------------- the identity map (item 6)

    private suspend fun setUserEmails(connId: UInt, userId: Long, emails: List<String>) {
        suspendTransaction(sharedDatabaseForTests()) {
            Users.update({ (Users.connectionId eq connId) and (Users.userId eq userId) }) { it[Users.emails] = stringArrayJson(emails) }
        }
    }

    private suspend fun emailsOf(connId: UInt, userId: Long): List<String> =
        parseStringArray(raw.userRows(connId).single { it[Users.userId] == userId }[Users.emails])

    private suspend fun assertIncrementalEqualsReprocess(connId: UInt) {
        val incremental = GitLabFixtures.codeDigest(connId)
        processStore.markAllNeedsProcessing(connId, commitsOnly = false)
        process(connId)
        assertEquals(incremental, GitLabFixtures.codeDigest(connId), "REPROCESS digest equals the incremental state")
    }

    @Test
    fun `an address added to a user moves that author's commits from the e key to the u key at the next run`() = runBlocking<Unit> {
        val connId = rawClone()
        process(connId)
        val contractor = expected.getValue("users").jsonObject.getValue("unmatchedAuthors").jsonArray[0].jsonObject
        val before = code.identityFigures(connId)
        assertEquals(3L, before.commitsUnmatched)
        assertEquals(1L, before.emailOnly)

        setUserEmails(connId, 3, emailsOf(connId, 3) + contractor.getValue("email").jsonPrimitive.content)
        val run = process(connId)
        assertEquals(1L, run["identityMapChanged"])
        assertNull(run["exclusionsChanged"])
        assertEquals(300L, run["commitsProcessed"], "every commit is re-flagged and re-processed")
        val after = code.identityFigures(connId)
        assertEquals(0L, after.commitsUnmatched)
        assertEquals(0L, after.emailOnly)
        assertEquals(53 + 3, code.commitRows(connId).count { !it.isMerge && it.identityKey == "u3" })
        assertIncrementalEqualsReprocess(connId)
    }

    @Test
    fun `an address removed from a user moves that author's commits to an e key`() = runBlocking<Unit> {
        val connId = rawClone()
        process(connId)
        assertEquals(84, code.commitRows(connId).count { !it.isMerge && it.identityKey == "u2" })
        setUserEmails(connId, 2, emailsOf(connId, 2).filterNot { it.startsWith("user1@") })
        val run = process(connId)
        assertEquals(1L, run["identityMapChanged"])
        val after = code.identityFigures(connId)
        assertTrue(after.commitsUnmatched > 3L, "the commits under the removed address are now unmatched: ${after.commitsUnmatched}")
        assertEquals(2L, after.emailOnly)
        assertTrue(code.commitRows(connId).count { !it.isMerge && it.identityKey == "u2" } < 84)
        assertIncrementalEqualsReprocess(connId)
    }

    @Test
    fun `a user added or renamed after the first run resolves a review request's target at the next run`() = runBlocking<Unit> {
        val connId = rawClone()
        val targetUsername = raw.mrEventRows(connId).filter { it[MrEvents.action] == "REVIEW_REQUESTED" }
            .flatMap { parseStringArray(it[MrEvents.targets] ?: "[]") }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        val owner = raw.userRows(connId).single { it[Users.username].equals(targetUsername, ignoreCase = true) }
        val ownerId = owner[Users.userId]
        suspend fun rename(to: String) {
            suspendTransaction(sharedDatabaseForTests()) {
                Users.update({ (Users.connectionId eq connId) and (Users.userId eq ownerId) }) { it[username] = to }
            }
        }
        suspend fun resolvedTargets() =
            code.mrEventRows(connId).values.flatten().sumOf { event -> event.targetKeys.count { key -> key == "u$ownerId" } }

        rename("renamed-before-first-run")
        process(connId)
        assertEquals(0, resolvedTargets(), "the target is unresolved while nobody owns the username")
        rename(owner[Users.username])
        val run = process(connId)
        assertEquals(1L, run["usernameMapChanged"])
        assertEquals(60L, run["mergeRequestsProcessed"], "a username change re-flags the merge requests too")
        assertTrue(resolvedTargets() > 0, "the owner's review requests now name u$ownerId")
        assertIncrementalEqualsReprocess(connId)
    }

    // ---------------------------------------------------------------------------------------------- failures

    @Test
    fun `a failure leaves as stage, keys and the cause's class - never a bound value`() = runBlocking<Unit> {
        val connId = rawClone()
        val email = "contractor.sample@mail.example"
        val failing = ProcessNormalizers(identities = { _, _, _, _ -> error("duplicate key value (identity_key) = ($email)") })
        val thrown = assertFailsWith<ProcessFailure> { process(connId, normalizers = failing) }
        assertFalse(email in thrown.message.orEmpty(), thrown.message)
        assertFalse(email in failureDetail(thrown), "the job's error detail carries no bound value")
        assertTrue(email in thrown.cause?.message.orEmpty(), "while the original stays as the cause for the stack")
        assertTrue("reference rows" in thrown.message.orEmpty() && "IllegalStateException" in thrown.message.orEmpty())
        assertEquals(360L, processStore.countNeedsProcessing(connId), "nothing was processed, everything stays flagged")
    }

    private fun poisoned(keys: Set<CommitKey>) = ProcessNormalizers(commit = { raw, files, excluded, map ->
        if (CommitKey(raw.projectId, raw.sha) in keys) {
            error("cannot normalize ${raw.authorEmail}")
        } else {
            codeCommit(raw, files, excluded, map)
        }
    })

    /** The first key of each of the first [pages] claim pages of a fully flagged clone (pages of 50 by `(project_id, sha)`). */
    private suspend fun firstKeysOfPages(connId: UInt, pages: Int): Set<CommitKey> {
        val firsts = mutableSetOf<CommitKey>()
        var after: CommitKey? = null
        repeat(pages) {
            val page = processStore.commitsToProcess(connId, CODE_PROCESSING_VERSION, 50, after)
            firsts += page.first()
            after = page.last()
        }
        return firsts
    }

    @Test
    fun `one poisoned page is counted and skipped, the rest is processed and its rows stay flagged`() = runBlocking<Unit> {
        val connId = rawClone()
        val poison = firstKeysOfPages(connId, 1)
        val logs = LogCapture(GitLabProcessStream::class.java.name)
        val progress = try {
            process(connId, normalizers = poisoned(poison))
        } finally {
            logs.detach()
        }
        val warns = logs.events.filter { it.level == Level.WARN }
        assertEquals(1, warns.size, "one WARN for the one tolerated page")
        assertTrue("commit page" in warns.single().formattedMessage)
        fun IThrowableProxy.messages(): List<String?> = listOf(message) + (cause?.messages() ?: emptyList())
        val logged = listOf(warns.single().formattedMessage) + warns.single().throwableProxy.messages().filterNotNull()
        assertTrue(logged.none { "@" in it }, "the tolerated-page WARN carries no address: $logged")
        assertEquals(1L, progress["commitPagesFailed"])
        assertEquals(250L, progress["commitsProcessed"], "the other five pages of 50 landed")
        assertEquals(60L, progress["mergeRequestsProcessed"], "the merge request pass ran past the failed commit page")
        assertEquals(50L, processStore.countNeedsProcessing(connId), "the poisoned page's rows stay flagged for the next run")
        assertEquals(250, code.commitRows(connId).size)
        val healed = process(connId)
        assertEquals(50L, healed["commitsProcessed"], "the next run retries exactly that page")
        assertEquals(0L, processStore.countNeedsProcessing(connId))
    }

    @Test
    fun `a raw value its decoder cannot read faults only its own page`() = runBlocking<Unit> {
        val connId = rawClone()
        val first = raw.mergeRequestRows(connId).minOf { it[MergeRequests.mrId] }
        suspendTransaction(sharedDatabaseForTests()) {
            MergeRequests.update({ (MergeRequests.connectionId eq connId) and (MergeRequests.mrId eq first) }) {
                // PostgreSQL casts " 5" to bigint (the reference rebuild reads the ids that way), the reader's toLong() does not.
                it[reviewerIds] = """[" 5"]"""
            }
        }
        val progress = process(connId)
        assertEquals(1L, progress["mergeRequestPagesFailed"], "the NumberFormatException of the reader is a row fault of that page")
        assertEquals(10L, progress["mergeRequestsProcessed"], "the second page of 10 landed")
        assertEquals(300L, progress["commitsProcessed"])
        assertEquals(50L, processStore.countNeedsProcessing(connId), "the poisoned page's merge requests stay flagged")
    }

    @Test
    fun `more than the bound of poisoned pages in one run is an outage and the job fails`() = runBlocking<Unit> {
        val tolerant = rawClone()
        val outage = rawClone()
        val tolerated = process(tolerant, normalizers = poisoned(firstKeysOfPages(tolerant, PROCESS_FAILED_PAGES_MAX)))
        assertEquals(PROCESS_FAILED_PAGES_MAX.toLong(), tolerated["commitPagesFailed"], "up to the bound is tolerated")
        val failure = assertFailsWith<ProcessFailure> {
            process(outage, normalizers = poisoned(firstKeysOfPages(outage, PROCESS_FAILED_PAGES_MAX + 1)))
        }
        assertTrue("commit page" in failure.message.orEmpty() && "@" !in failure.message.orEmpty(), failure.message)
    }

    // ---------------------------------------------------------------------------------------------- flags, cursor, progress

    @Test
    fun `refreshed details are flagged for PROCESS and the next run writes them as pending`() = runBlocking<Unit> {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        SyncedGitLabFixture.cloneProcessedData(fixtureId, connId)
        val mr = code.mergeRequestRows(connId).first { it.additions != null }
        assertTrue(raw.settleMrDetails(connId, mr.mrId, emptyList(), 6_000L, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND)))
        process(connId)
        assertEquals(DetailsState.UNREADABLE, code.mergeRequestRows(connId).single { it.mrId == mr.mrId }.detailsState)
        assertEquals(1, raw.retryMrDetails(connId, listOf(DETAILS_ERROR_NOT_FOUND), 10))
        assertEquals(1L, processStore.countNeedsProcessing(connId), "the un-stamp flags PROCESS")
        process(connId)
        assertEquals(DetailsState.PENDING, code.mergeRequestRows(connId).single { it.mrId == mr.mrId }.detailsState)
    }

    @Test
    fun `an absent or undecodable cursor re-flags everything, a matching one nothing`() = runBlocking<Unit> {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        SyncedGitLabFixture.cloneProcessedData(fixtureId, connId)
        val cursors = SyncCursorsService(sharedDatabaseForTests())
        assertNull(process(connId)["commitsProcessed"], "the cloned cursor matches: nothing to do")
        cursors.clear(connId, "code_process")
        val absent = process(connId)
        assertEquals(300L, absent["commitsProcessed"], "no cursor: the configuration the rows were computed under is unknown")
        assertEquals(60L, absent["mergeRequestsProcessed"])
        assertNull(absent["exclusionsChanged"], "an unknown past is not a change")
        cursors.put(connId, "code_process", """{"exclusionsSha256":7}""")
        assertEquals(300L, process(connId)["commitsProcessed"], "an undecodable cursor counts as changed")
        assertEquals(0L, processStore.countNeedsProcessing(connId))
    }

    @Test
    fun `progress keys and heartbeats - one beat per stage and page, a no-op run only the reference rows`() = runBlocking<Unit> {
        val connId = rawClone()
        val beats = mutableListOf<String?>()
        suspend fun run(): Map<String, Long> {
            beats.clear()
            val context = StreamContext(
                connId, 1u, sharedDatabaseForTests(), SyncCursorsService(sharedDatabaseForTests()),
                jobHeartbeat = { progress, _ ->
                    beats += progress
                    true
                },
            )
            val stream = GitLabProcessStream(processStore, code, globs())
            context.currentStreamName = stream.name
            stream.run(context)
            return context.progressSnapshot()
        }
        val first = run()
        assertEquals(mapOf("commitsProcessed" to 300L, "mergeRequestsProcessed" to 60L, "identities" to 9L), first)
        // fingerprints + reference rows + 6 commit pages + 2 merge request pages
        assertEquals(10, beats.size)
        val noop = run()
        assertEquals(mapOf("identities" to 9L), noop, "nothing flagged: no page counters")
        assertEquals(1, beats.size, "only the reference rebuild beats (no fingerprint change, no page)")
    }

    // ---------------------------------------------------------------------------------------------- the page lock

    @Test
    fun `a write that re-flags a row waits for the page that holds it and lands after the mark`() = runBlocking<Unit> {
        val connId = rawClone()
        val target = raw.commitRows(connId).first { it[Commits.parentCount] <= 1 }
        val key = CommitKey(target[Commits.projectId], target[Commits.sha])
        val held = CompletableDeferred<Unit>()
        val pageDone = CompletableDeferred<Unit>()
        val page = launch(Dispatchers.IO) {
            suspendTransaction(sharedDatabaseForTests()) {
                processStore.commitsForProcessing(connId, listOf(key))
                held.complete(Unit)
                pageDone.await()
                processStore.markCommitsProcessed(connId, listOf(key), 1L, CODE_PROCESSING_VERSION)
            }
        }
        held.await()
        val tombstone = async(Dispatchers.IO) { raw.tombstoneCommits(connId, key.projectId, listOf(key.sha), 2L) }
        assertNull(withTimeoutOrNull(700) { tombstone.await() }, "the re-flagging write waits for the page's lock (FOR NO KEY UPDATE)")
        pageDone.complete(Unit)
        page.join()
        assertEquals(1, tombstone.await())
        val row = raw.commitRows(connId).single { it[Commits.projectId] == key.projectId && it[Commits.sha] == key.sha }
        assertTrue(row[Commits.needsProcessing], "the flag landed after the mark and survives it")
        assertNotNull(row[Commits.deletedAt])
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }
}
