package ch.nokillswit

import ch.nokillswit.gitlab.CommitFilesOutcome
import ch.nokillswit.gitlab.CommitFilesResult
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.RawGitLabCommit
import ch.nokillswit.gitlab.RawGitLabCommitFile
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun file(path: String, additions: Int? = 3, deletions: Int? = 1, oldPath: String? = null, generated: Boolean = false) =
        RawGitLabCommitFile(path, oldPath, additions, deletions, newFile = false, deletedFile = false, renamedFile = oldPath != null,
            generatedFile = generated)

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
        assertEquals(1, store.tombstoneCommitsNotSeenSince(connId, 1, sinceCommittedAt = 5_000, passStartedAt = 20_000, now = 21_000))
        val rows = store.commitRows(connId, 1).associateBy { it[GitLabRawStore.Commits.sha] }
        assertNull(rows.getValue(sha(1))[GitLabRawStore.Commits.deletedAt], "older than since: not touched")
        assertEquals(21_000L, rows.getValue(sha(2))[GitLabRawStore.Commits.deletedAt])
        assertNull(rows.getValue(sha(3))[GitLabRawStore.Commits.deletedAt], "seen in the pass")
        assertEquals(0, store.tombstoneCommitsNotSeenSince(connId, 1, 5_000, 20_000, 22_000), "a tombstone is not set twice")
        assertEquals(0, store.tombstoneCommitsNotSeenSince(connId, 2, 0, 20_000, 22_000), "another project is untouched")

        // PROCESS (PR 10) has handled the tombstone; seeing the commit again resurrects it and flags it again.
        markProcessed(connId)
        val seenAgain = store.upsertCommits(connId, 1, listOf(commit(2, committedAt = 5_000)), now = 30_000)
        assertEquals(listOf(RawUpsertOutcome.RESURRECTED), seenAgain)
        val back = store.commitRows(connId, 1).single { it[GitLabRawStore.Commits.sha] == sha(2) }
        assertNull(back[GitLabRawStore.Commits.deletedAt])
        assertEquals(30_000L, back[GitLabRawStore.Commits.lastSeenAt])
        assertTrue(back[GitLabRawStore.Commits.needsProcessing])
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
        store.tombstoneCommitsNotSeenSince(connId, 1, sinceCommittedAt = 0, passStartedAt = 20_000, now = 21_000)
        assertEquals(5_000L, store.oldestLiveCommittedAt(connId, 1))
    }

    @Test
    fun `a flush stores the files once, stamps the commit and a duplicate path or a re-flush changes nothing`() = runBlocking<Unit> {
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
        store.tombstoneCommitsNotSeenSince(connId, 1, sinceCommittedAt = 2_000, passStartedAt = 20_000, now = 21_000)
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
}
