package ch.nokillswit

import ch.nokillswit.gitlab.CommitFilesOutcome
import ch.nokillswit.gitlab.CommitFilesResult
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.NON_MERGE_PARENTS
import ch.nokillswit.gitlab.RawGitLabCommit
import ch.nokillswit.gitlab.RawGitLabCommitFile
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `gitlab/GitLabCommit*` (V42): the commit upserts, the head and the tombstone sweep, the files flush, the backlog reads and PURGE. */
class GitLabCommitStoreTest {
    private val connections = mutableListOf<UInt>()
    private val store: GitLabRawStore = GitLabFixtures.rawStore()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private suspend fun connection(): UInt = GitLabFixtures.createConnection().also { connections += it }

    private fun sha(n: Int) = n.toString(16).padStart(40, '0')

    private fun commit(n: Int, committedAt: Long = n * 1_000L, parents: Int = 1, title: String = "Commit $n") = RawGitLabCommit(
        sha = sha(n),
        title = title,
        authorName = "Author $n",
        authorEmail = "a$n@x.test",
        authoredAt = committedAt - 10,
        committedAt = committedAt,
        parentCount = parents,
        additions = n,
        deletions = 1,
    )

    private fun commitWithSha(sha: String, committedAt: Long = 1_000) = RawGitLabCommit(
        sha, "Commit", "Author", "a@x.test", committedAt - 10, committedAt, parentCount = 1, additions = 1, deletions = 1,
    )

    private fun ok(sha: String, vararg files: RawGitLabCommitFile, capped: Boolean = false) =
        CommitFilesOutcome(1, sha, CommitFilesResult(files.toList(), capped), null)

    private fun file(path: String, additions: Int? = 3, deletions: Int? = 1, oldPath: String? = null, generated: Boolean = false) =
        RawGitLabCommitFile(path, oldPath, additions, deletions, newFile = false, deletedFile = false, renamedFile = oldPath != null,
            generatedFile = generated)

    /** The unconditional sweep (no confirmation): the stale candidates, all tombstoned; the stream confirms each against GitLab first. */
    private suspend fun sweep(connId: UInt, projectId: Long, sinceCommittedAt: Long, passStartedAt: Long, now: Long): Int =
        store.tombstoneCommits(connId, projectId, store.staleCommitShas(connId, projectId, sinceCommittedAt, passStartedAt, 1_000), now)

    private fun project(id: Long) = RawGitLabProject(
        id, "platform/p$id", "platform", 7, "main", false,
        Json.parseToJsonElement("""{"id":$id,"path":"p$id","path_with_namespace":"platform/p$id","name":"p$id"}"""),
    )

    @Test
    fun `a commit is inserted, then unchanged with only last_seen_at moved`() = runBlocking<Unit> {
        val connId = connection()
        assertEquals(
            listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.INSERTED),
            store.upsertCommits(connId, 1, listOf(commit(1), commit(2)), now = 10_000),
        )
        assertEquals(listOf(RawUpsertOutcome.UNCHANGED), store.upsertCommits(connId, 1, listOf(commit(1)), now = 20_000))
        val row = store.commitRows(connId, 1).single { it[GitLabRawStore.Commits.sha] == sha(1) }
        assertEquals(10_000L, row[GitLabRawStore.Commits.firstSeenAt])
        assertEquals(20_000L, row[GitLabRawStore.Commits.lastSeenAt])
        assertTrue(row[GitLabRawStore.Commits.needsProcessing])
        assertEquals(sha(1), row[GitLabRawStore.Commits.sha])
        assertEquals("Commit 1", row[GitLabRawStore.Commits.title])
        assertEquals(1, row[GitLabRawStore.Commits.additions])
        assertEquals(1.toShort(), row[GitLabRawStore.Commits.parentCount])
        assertNull(row[GitLabRawStore.Commits.filesFetchedAt])
    }

    @Test
    fun `the sweep tombstones only live unseen commits at or after since, and an upsert resurrects them`() = runBlocking<Unit> {
        val connId = connection()
        val three = listOf(commit(1, committedAt = 1_000), commit(2, committedAt = 5_000), commit(3, committedAt = 6_000))
        store.upsertCommits(connId, 1, three, now = 10_000)
        // The pass starting at 20_000 sees commit 3 only; commit 1 is older than the window start and stays live.
        store.upsertCommits(connId, 1, listOf(commit(3, committedAt = 6_000)), now = 20_000)
        assertEquals(1, sweep(connId, 1, sinceCommittedAt = 5_000, passStartedAt = 20_000, now = 21_000))
        val rows = store.commitRows(connId, 1).associateBy { it[GitLabRawStore.Commits.sha] }
        assertNull(rows.getValue(sha(1))[GitLabRawStore.Commits.deletedAt], "older than since: not touched")
        assertEquals(21_000L, rows.getValue(sha(2))[GitLabRawStore.Commits.deletedAt])
        assertNull(rows.getValue(sha(3))[GitLabRawStore.Commits.deletedAt], "seen in the pass")
        assertEquals(0, sweep(connId, 1, 5_000, 20_000, 22_000), "a tombstone is not set twice")
        assertEquals(0, sweep(connId, 2, 0, 20_000, 22_000), "another project is untouched")

        // PROCESS (PR 10) has handled the tombstone; seeing the commit again resurrects it and flags it again.
        markProcessed(connId)
        val seenAgain = store.upsertCommits(connId, 1, listOf(commit(2, committedAt = 5_000)), now = 30_000)
        assertEquals(listOf(RawUpsertOutcome.RESURRECTED), seenAgain)
        val back = store.commitRows(connId, 1).single { it[GitLabRawStore.Commits.sha] == sha(2) }
        assertNull(back[GitLabRawStore.Commits.deletedAt])
        assertEquals(30_000L, back[GitLabRawStore.Commits.lastSeenAt])
        assertTrue(back[GitLabRawStore.Commits.needsProcessing])
    }

    @Test
    fun `the stale candidates are the live unseen commits since the window, newest first and limited, and a touch removes them`() =
        runBlocking<Unit> {
            val connId = connection()
            store.upsertCommits(connId, 1, (1..5).map { commit(it, committedAt = it * 1_000L) }, now = 10_000)
            store.upsertCommits(connId, 1, listOf(commit(5, committedAt = 5_000)), now = 20_000)
            val stale = store.staleCommitShas(connId, 1, 2_000, 20_000, 10)
            assertEquals(listOf(sha(4), sha(3), sha(2)), stale, "since is inclusive, newest first")
            assertEquals(listOf(sha(4), sha(3)), store.staleCommitShas(connId, 1, 2_000, 20_000, 2))
            assertTrue(store.staleCommitShas(connId, 2, 0, 20_000, 10).isEmpty(), "another project")
            store.touchCommits(connId, 1, listOf(sha(4)), now = 21_000)
            assertEquals(listOf(sha(3), sha(2)), store.staleCommitShas(connId, 1, 2_000, 20_000, 10))
            assertEquals(1, store.tombstoneCommits(connId, 1, listOf(sha(3), sha(3)), now = 22_000), "a tombstone is set once")
            assertEquals(0, store.tombstoneCommits(connId, 1, listOf(sha(3)), now = 23_000))
            assertEquals(emptyList(), store.staleCommitShas(connId, 1, 4_000, 20_000, 10).filter { it == sha(3) })
            store.touchCommits(connId, 1, emptyList(), now = 24_000)
        }

    private suspend fun markProcessed(connId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            GitLabRawStore.Commits.update({ GitLabRawStore.Commits.connectionId eq connId }) { it[needsProcessing] = false }
        }
    }

    @Test
    fun `a page repeating a sha is judged by its first occurrence and long text is cut`() = runBlocking<Unit> {
        val connId = connection()
        val long = commit(2, title = "t".repeat(400) + "\u0000")
        val outcomes = store.upsertCommits(connId, 1, listOf(commit(1), commit(1), long), now = 1_000)
        assertEquals(listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.INSERTED), outcomes)
        val rows = store.commitRows(connId, 1)
        assertEquals(2, rows.size)
        assertEquals(255, rows.single { it[GitLabRawStore.Commits.sha] == sha(2) }[GitLabRawStore.Commits.title].length)
        assertEquals(emptyList(), store.upsertCommits(connId, 1, emptyList(), now = 2_000))
    }

    @Test
    fun `the same sha in two projects is two commits`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, listOf(commit(1)), now = 1_000)
        assertEquals(listOf(RawUpsertOutcome.INSERTED), store.upsertCommits(connId, 2, listOf(commit(1)), now = 1_000))
        assertEquals(2, store.commitRows(connId).size)
    }

    @Test
    fun `the project head accepts a SHA-1 or SHA-256 value and the re-list request is set and cleared with it`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        assertNull(store.liveProjectsForCommits(connId).single().headSha)

        store.requestRelist(connId, 1)
        assertTrue(store.liveProjectsForCommits(connId).single().relistRequested)

        val sha256 = "ab".repeat(32)
        store.recordCommitsHead(connId, 1, sha256)
        val row = store.liveProjectsForCommits(connId).single()
        assertEquals(sha256, row.headSha, "a 64-hex head is stored whole")
        assertFalse(row.relistRequested, "recording a head clears the request by default")
        assertEquals("main", row.defaultBranch)

        store.recordCommitsHead(connId, 1, sha(5), relistRequested = true)
        val again = store.liveProjectsForCommits(connId).single()
        assertEquals(sha(5), again.headSha)
        assertTrue(again.relistRequested)
    }

    @Test
    fun `only live projects are walked, in project order, and a moved-out project drops out`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(3), project(1), project(2)), now = 1_000)
        assertEquals(listOf(1L, 2L, 3L), store.liveProjectsForCommits(connId).map { it.projectId })
        store.upsertProjects(connId, listOf(project(1), project(3)), now = 5_000)
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 5_000, now = 6_000)
        assertEquals(listOf(1L, 3L), store.liveProjectsForCommits(connId).map { it.projectId })
    }

    @Test
    fun `the oldest live committed_at ignores tombstones and other projects`() = runBlocking<Unit> {
        val connId = connection()
        assertNull(store.oldestLiveCommittedAt(connId, 1))
        store.upsertCommits(connId, 1, listOf(commit(1, committedAt = 1_000), commit(2, committedAt = 5_000)), now = 10_000)
        store.upsertCommits(connId, 2, listOf(commit(3, committedAt = 500)), now = 10_000)
        assertEquals(1_000L, store.oldestLiveCommittedAt(connId, 1))
        store.upsertCommits(connId, 1, listOf(commit(2, committedAt = 5_000)), now = 20_000)
        sweep(connId, 1, sinceCommittedAt = 0, passStartedAt = 20_000, now = 21_000)
        assertEquals(5_000L, store.oldestLiveCommittedAt(connId, 1))
    }

    @Test
    fun `a flush stores the files once and stamps the commit, a re-flush adds no file row but moves the capped flag`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, listOf(commit(1)), now = 1_000)
        val files = listOf(
            file("a/b.go"), file("a/b.go", additions = 99), file("go.sum", generated = true), file("new.go", oldPath = "old.go"),
            file("big.bin", additions = null, deletions = null),
        )
        store.flushCommitFiles(connId, listOf(CommitFilesOutcome(1, sha(1), CommitFilesResult(files, capped = false), null)), now = 5_000)

        val rows = store.commitFileRows(connId, 1, sha(1))
        assertEquals(listOf("a/b.go", "big.bin", "go.sum", "new.go"), rows.map { it[GitLabRawStore.CommitFiles.path] })
        assertEquals(3, rows.first()[GitLabRawStore.CommitFiles.additions], "the first of a duplicate path wins")
        assertNull(rows[1][GitLabRawStore.CommitFiles.additions], "a too-large file keeps its path with null lines")
        assertTrue(rows[2][GitLabRawStore.CommitFiles.generatedFile])
        assertFalse(rows[0][GitLabRawStore.CommitFiles.generatedFile])
        assertEquals("old.go", rows[3][GitLabRawStore.CommitFiles.oldPath])
        assertTrue(rows[3][GitLabRawStore.CommitFiles.renamedFile])

        val stamped = store.commitRows(connId, 1).single()
        assertEquals(4, stamped[GitLabRawStore.Commits.filesChanged])
        assertFalse(stamped[GitLabRawStore.Commits.filesCapped])
        assertEquals(5_000L, stamped[GitLabRawStore.Commits.filesFetchedAt])
        assertNull(stamped[GitLabRawStore.Commits.filesError])
        assertTrue(stamped[GitLabRawStore.Commits.needsProcessing])

        store.flushCommitFiles(connId, listOf(CommitFilesOutcome(1, sha(1), CommitFilesResult(files, capped = true), null)), now = 6_000)
        assertEquals(4, store.commitFileRows(connId, 1, sha(1)).size, "a re-flush adds no file row")
        assertTrue(store.commitRows(connId, 1).single()[GitLabRawStore.Commits.filesCapped])
        store.flushCommitFiles(connId, emptyList(), now = 7_000)
    }

    @Test
    fun `an outcome with an error and no result leaves files_changed null and stores no file`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, listOf(commit(1), commit(2)), now = 1_000)
        store.flushCommitFiles(
            connId,
            listOf(
                CommitFilesOutcome(1, sha(1), null, "LIMIT_EXCEEDED"),
                CommitFilesOutcome(1, sha(2), CommitFilesResult(emptyList(), capped = false), null),
            ),
            now = 5_000,
        )
        val rows = store.commitRows(connId, 1).associateBy { it[GitLabRawStore.Commits.sha] }
        val failed = rows.getValue(sha(1))
        assertNull(failed[GitLabRawStore.Commits.filesChanged])
        assertEquals("LIMIT_EXCEEDED", failed[GitLabRawStore.Commits.filesError])
        assertEquals(5_000L, failed[GitLabRawStore.Commits.filesFetchedAt])
        assertEquals(0, rows.getValue(sha(2))[GitLabRawStore.Commits.filesChanged], "an empty diff is a complete, empty file list")
        assertTrue(store.commitFileRows(connId, 1, sha(1)).isEmpty())
        assertEquals(0L, store.countPendingCommitFiles(connId))
    }

    @Test
    fun `the backlog is newest first and excludes merges, tombstones and stamped commits`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(
            connId, 1,
            listOf(commit(1, committedAt = 1_000), commit(2, committedAt = 9_000), commit(3, committedAt = 5_000, parents = 2),
                commit(4, committedAt = 7_000), commit(5, committedAt = 3_000), commit(6, committedAt = 7_000)),
            now = 10_000,
        )
        store.upsertCommits(connId, 2, listOf(commit(7, committedAt = 8_000)), now = 10_000)
        // Commit 5 is tombstoned, commit 1 already read.
        val seen = listOf(
            commit(1, committedAt = 1_000), commit(2, committedAt = 9_000), commit(4, committedAt = 7_000), commit(6, committedAt = 7_000),
        )
        store.upsertCommits(connId, 1, seen, now = 20_000)
        sweep(connId, 1, sinceCommittedAt = 2_000, passStartedAt = 20_000, now = 21_000)
        store.flushCommitFiles(connId, listOf(CommitFilesOutcome(1, sha(1), CommitFilesResult(emptyList(), false), null)), now = 22_000)

        val pending = store.pendingCommitFiles(connId, limit = 10)
        assertEquals(listOf(1L to sha(2), 2L to sha(7), 1L to sha(4), 1L to sha(6)), pending.map { it.projectId to it.sha },
            "newest first, ties by sha")
        assertEquals(4L, store.countPendingCommitFiles(connId))
        assertEquals(listOf(sha(2), sha(7)), store.pendingCommitFiles(connId, limit = 2).map { it.sha })
    }

    @Test
    fun `PURGE drains the commits in batches, the files cascade and other connections are untouched`() = runBlocking<Unit> {
        val connId = connection()
        val other = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertCommits(connId, 1, (1..7).map { commit(it) }, now = 1_000)
        store.upsertCommits(connId, 2, listOf(commit(1)), now = 1_000)
        store.flushCommitFiles(
            connId,
            (1..7).map { CommitFilesOutcome(1, sha(it), CommitFilesResult(listOf(file("f$it.go")), false), null) },
            now = 2_000,
        )
        store.upsertCommits(other, 1, listOf(commit(1)), now = 1_000)
        val otherFiles = CommitFilesOutcome(1, sha(1), CommitFilesResult(listOf(file("f.go")), false), null)
        store.flushCommitFiles(other, listOf(otherFiles), now = 2_000)

        assertEquals(3, store.purgeCommitsBatch(connId, batchSize = 3))
        store.purgeAll(connId, batchSize = 3)

        assertTrue(store.commitRows(connId).isEmpty())
        assertTrue(store.projectRows(connId).isEmpty())
        for (n in 1..7) assertTrue(store.commitFileRows(connId, 1, sha(n)).isEmpty(), "files cascade with their commit")
        assertEquals(1, store.commitRows(other).size)
        assertEquals(1, store.commitFileRows(other, 1, sha(1)).size, "another connection's files are untouched")
    }

    @Test
    fun `a 64-hex SHA-256 commit goes through the commit row, its file row and the cascading purge`() = runBlocking<Unit> {
        val connId = connection()
        val sha256 = "ab".repeat(32)
        assertEquals(listOf(RawUpsertOutcome.INSERTED), store.upsertCommits(connId, 1, listOf(commitWithSha(sha256)), now = 1_000))
        store.flushCommitFiles(connId, listOf(ok(sha256, file("a.go"))), now = 2_000)
        assertEquals(sha256, store.commitRows(connId, 1).single()[GitLabRawStore.Commits.sha])
        assertEquals(1, store.commitFileRows(connId, 1, sha256).size)
        assertEquals(1, store.purgeCommitsBatch(connId))
        assertTrue(store.commitRows(connId).isEmpty())
        assertTrue(store.commitFileRows(connId, 1, sha256).isEmpty(), "the file cascaded with its commit")
    }

    @Test
    fun `a resurrected commit keeps its files and stamp, NOT_FOUND is reset and LIMIT_EXCEEDED stays`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, (1..3).map { commit(it, committedAt = it * 1_000L) }, now = 1_000)
        store.flushCommitFiles(
            connId,
            listOf(
                ok(sha(1), file("a.go")),
                CommitFilesOutcome(1, sha(2), null, "NOT_FOUND"),
                CommitFilesOutcome(1, sha(3), null, "LIMIT_EXCEEDED"),
            ),
            now = 2_000,
        )
        assertEquals(3, sweep(connId, 1, sinceCommittedAt = 0, passStartedAt = 20_000, now = 21_000))
        assertEquals(0L, store.countPendingCommitFiles(connId))

        val back = store.upsertCommits(connId, 1, (1..3).map { commit(it, committedAt = it * 1_000L) }, now = 30_000)
        assertEquals(List(3) { RawUpsertOutcome.RESURRECTED }, back)
        val rows = store.commitRows(connId, 1).associateBy { it[GitLabRawStore.Commits.sha] }
        val complete = rows.getValue(sha(1))
        assertEquals(2_000L, complete[GitLabRawStore.Commits.filesFetchedAt], "a complete file list is kept")
        assertEquals(1, complete[GitLabRawStore.Commits.filesChanged])
        assertEquals(1, store.commitFileRows(connId, 1, sha(1)).size)
        val gone = rows.getValue(sha(2))
        assertNull(gone[GitLabRawStore.Commits.filesFetchedAt], "NOT_FOUND was disproved: back in the backlog")
        assertNull(gone[GitLabRawStore.Commits.filesError])
        val oversized = rows.getValue(sha(3))
        assertEquals(2_000L, oversized[GitLabRawStore.Commits.filesFetchedAt])
        assertEquals("LIMIT_EXCEEDED", oversized[GitLabRawStore.Commits.filesError])
        assertEquals(listOf(sha(2)), store.pendingCommitFiles(connId, 10).map { it.sha })
    }

    @Test
    fun `a path over 2000 UTF-8 bytes is cut on a code point, stored once and counted once`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, listOf(commit(1)), now = 1_000)
        val cjk = "\u76ee".repeat(1_000)
        val emoji = "\uD83D\uDE00".repeat(600)
        val ascii = "d/" + "x".repeat(1_500)
        store.flushCommitFiles(
            connId,
            listOf(ok(sha(1), file(cjk, oldPath = cjk + "z"), file(emoji), file(ascii), file("nul\u0000.go"))),
            now = 2_000,
        )
        val paths = store.commitFileRows(connId, 1, sha(1)).associateBy { it[GitLabRawStore.CommitFiles.path].first() }
        val cjkRow = paths.getValue('\u76ee')
        assertEquals(666, cjkRow[GitLabRawStore.CommitFiles.path].length, "666 x 3 bytes = 1,998 <= 2,000")
        assertEquals(666, cjkRow[GitLabRawStore.CommitFiles.oldPath]!!.length)
        val emojiPath = paths.getValue('\uD83D')[GitLabRawStore.CommitFiles.path]
        assertEquals(500, emojiPath.codePointCount(0, emojiPath.length), "500 x 4 bytes = 2,000, never half a surrogate pair")
        assertEquals(1_024, paths.getValue('d')[GitLabRawStore.CommitFiles.path].length, "the column width still caps ASCII")
        assertFalse('\u0000' in paths.getValue('n')[GitLabRawStore.CommitFiles.path], "NUL is replaced before the cut")
        assertEquals(4, store.commitRows(connId, 1).single()[GitLabRawStore.Commits.filesChanged])
    }

    @Test
    fun `two long paths sharing the cut prefix are one file row and one in files_changed`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, listOf(commit(1)), now = 1_000)
        val prefix = "\u76ee".repeat(700)
        store.flushCommitFiles(
            connId,
            listOf(ok(sha(1), file(prefix + "a", additions = 5), file(prefix + "b", additions = 9), file("short.go"))),
            now = 2_000,
        )
        val rows = store.commitFileRows(connId, 1, sha(1))
        assertEquals(2, rows.size)
        val longRow = rows.single { it[GitLabRawStore.CommitFiles.path].startsWith("\u76ee") }
        assertEquals(5, longRow[GitLabRawStore.CommitFiles.additions], "the first wins")
        assertEquals(2, store.commitRows(connId, 1).single()[GitLabRawStore.Commits.filesChanged], "equals the rows stored")
    }

    @Test
    fun `an outcome carries exactly one of a result and an error`() {
        assertFailsWith<IllegalArgumentException> { CommitFilesOutcome(1, sha(1), null, null) }
        assertFailsWith<IllegalArgumentException> { CommitFilesOutcome(1, sha(1), CommitFilesResult(emptyList(), false), "NOT_FOUND") }
    }

    @Test
    fun `the backlog predicate renders the parent bound as a literal the partial index can match`() = runBlocking<Unit> {
        val sql = suspendTransaction(sharedDatabaseForTests()) { QueryBuilder(true).also { it.append(NON_MERGE_PARENTS) }.toString() }
        assertTrue(sql.endsWith("parent_count <= 1"), sql)
        assertFalse("?" in sql, "a bind parameter would defeat the partial index under a cached generic plan")
    }

    @Test
    fun `the default commits purge batch is 25 commits`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, 1, (1..30).map { commit(it) }, now = 1_000)
        assertEquals(25, store.purgeCommitsBatch(connId))
        assertEquals(5, store.commitRows(connId).size)
    }
}
