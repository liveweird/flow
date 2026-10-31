package ch.nokillswit

import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.RawGitLabUser
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `gitlab/GitLabRawStore.kt` (V41): the shared sha256 diff rule over `raw.gitlab_projects`/`raw.gitlab_users`, the sweeps and PURGE. */
class GitLabRawStoreTest {
    private val connections = mutableListOf<UInt>()
    private val store: GitLabRawStore = GitLabFixtures.rawStore()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private suspend fun connection(): UInt = GitLabFixtures.createConnection().also { connections += it }

    private fun project(id: Long, branch: String = "main", description: String = "x", archived: Boolean = false) = RawGitLabProject(
        projectId = id,
        pathWithNamespace = "platform/p$id",
        groupPath = "platform",
        groupId = 7,
        defaultBranch = branch,
        archived = archived,
        payload = Json.parseToJsonElement(
            """{"id":$id,"path":"p$id","path_with_namespace":"platform/p$id","name":"p$id","default_branch":"$branch","archived":$archived,
               "description":"$description","avatar_url":"https://x/a.png","statistics":{"commit_count":9},
               "namespace":{"id":7,"full_path":"platform","kind":"group","parent_id":null,"avatar_url":"u"}}""",
        ),
    )

    private fun user(id: Long, emails: List<String> = emptyList(), name: String = "User $id", state: String = "active") = RawGitLabUser(
        userId = id,
        username = "user$id",
        name = name,
        state = state,
        bot = false,
        emails = emails,
        payload = Json.parseToJsonElement(
            """{"id":$id,"username":"user$id","name":"$name","state":"$state","bio":"private","email":"u$id@x.test"}""",
        ),
    )

    @Test
    fun `a project is inserted, then unchanged, then changed - and only a change moves changed_at`() = runBlocking<Unit> {
        val connId = connection()
        assertEquals(listOf(RawUpsertOutcome.INSERTED), store.upsertProjects(connId, listOf(project(1)), now = 1_000))
        assertEquals(listOf(RawUpsertOutcome.UNCHANGED), store.upsertProjects(connId, listOf(project(1)), now = 2_000))
        var row = store.projectRows(connId).single()
        assertEquals(1_000L, row[GitLabRawStore.Projects.firstSeenAt])
        assertEquals(2_000L, row[GitLabRawStore.Projects.lastSeenAt], "an unchanged row is only touched")
        assertEquals(1_000L, row[GitLabRawStore.Projects.changedAt])

        assertEquals(listOf(RawUpsertOutcome.CHANGED), store.upsertProjects(connId, listOf(project(1, branch = "trunk")), now = 3_000))
        row = store.projectRows(connId).single()
        assertEquals("trunk", row[GitLabRawStore.Projects.defaultBranch])
        assertEquals(3_000L, row[GitLabRawStore.Projects.changedAt])
        assertEquals("platform", row[GitLabRawStore.Projects.groupPath])
        assertEquals(7L, row[GitLabRawStore.Projects.groupId])
        assertNull(row[GitLabRawStore.Projects.commitsHeadSha])
        assertFalse(row[GitLabRawStore.Projects.relistRequested])
    }

    @Test
    fun `the stored payload is the slim projection - no description, avatar or statistics - and a change in them is no change`() =
        runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1, description = "first")), now = 1_000)
        val payload = Json.parseToJsonElement(store.projectRows(connId).single()[GitLabRawStore.Projects.payload]).jsonObject
        assertEquals(setOf("id", "path", "path_with_namespace", "name", "default_branch", "archived", "namespace"), payload.keys)
        assertEquals(setOf("id", "full_path", "kind"), payload.getValue("namespace").jsonObject.keys,
            "a null member (parent_id) is dropped")
        // A description is not in the projection, so editing it changes no hash.
        assertEquals(listOf(RawUpsertOutcome.UNCHANGED), store.upsertProjects(connId, listOf(project(1, description = "second")),
            now = 2_000))
    }

    @Test
    fun `a project not seen in a completed pass is marked moved out, and seen again it is resurrected`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2)), now = 1_000)
        // A second pass starting at 5_000 sees only project 1.
        store.upsertProjects(connId, listOf(project(1)), now = 5_000)
        assertEquals(1, store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 5_000, now = 6_000))
        var rows = store.projectRows(connId).associateBy { it[GitLabRawStore.Projects.projectId] }
        assertNull(rows.getValue(1)[GitLabRawStore.Projects.movedOutAt])
        assertEquals(6_000L, rows.getValue(2)[GitLabRawStore.Projects.movedOutAt])
        assertEquals(1L, store.counts(connId).projectsMovedOut)
        assertEquals(1L, store.counts(connId).projects)
        assertEquals(0, store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 5_000, now = 7_000),
            "a marked row is not marked twice")

        assertEquals(listOf(RawUpsertOutcome.RESURRECTED), store.upsertProjects(connId, listOf(project(2)), now = 9_000))
        rows = store.projectRows(connId).associateBy { it[GitLabRawStore.Projects.projectId] }
        assertNull(rows.getValue(2)[GitLabRawStore.Projects.movedOutAt], "seen again clears the marker")
        assertEquals(9_000L, rows.getValue(2)[GitLabRawStore.Projects.changedAt])
    }

    @Test
    fun `a moved-out project that RECONCILE finds deleted is marked once, and stays resurrectable`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2), project(3)), now = 1_000)
        store.upsertProjects(connId, listOf(project(3)), now = 5_000)
        assertEquals(2, store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 5_000, now = 6_000))
        assertEquals(listOf(1L, 2L), store.movedOutProjects(connId, limit = 10), "moved-out live rows only, oldest move then id")
        assertEquals(listOf(1L), store.movedOutProjects(connId, limit = 1))
        val hash = store.projectRows(connId).first { it[GitLabRawStore.Projects.projectId] == 1L }[GitLabRawStore.Projects.sha256]

        assertTrue(store.markProjectDeleted(connId, 1, now = 7_000))
        assertFalse(store.markProjectDeleted(connId, 1, now = 8_000), "guarded on deleted_at IS NULL: a repeated pass is a no-op")
        assertFalse(store.markProjectDeleted(connId, 99, now = 8_000))
        val deleted = store.projectRows(connId).first { it[GitLabRawStore.Projects.projectId] == 1L }
        assertEquals(7_000L, deleted[GitLabRawStore.Projects.deletedAt])
        assertEquals(6_000L, deleted[GitLabRawStore.Projects.movedOutAt], "the moved-out marker stays")
        assertEquals(hash, deleted[GitLabRawStore.Projects.sha256], "no payload or hash is written")
        assertEquals(listOf(2L), store.movedOutProjects(connId, limit = 10), "a deleted project is not probed again")
        val counts = store.counts(connId)
        assertEquals(1L, counts.projectsDeleted)
        assertEquals(1L, counts.projectsMovedOut)
        assertEquals(1L, counts.projects)

        assertEquals(listOf(RawUpsertOutcome.RESURRECTED), store.upsertProjects(connId, listOf(project(1)), now = 9_000))
        val back = store.projectRows(connId).first { it[GitLabRawStore.Projects.projectId] == 1L }
        assertNull(back[GitLabRawStore.Projects.deletedAt])
        assertNull(back[GitLabRawStore.Projects.movedOutAt])
    }

    @Test
    fun `an archived mark touches nothing but the archived flag`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2)), now = 1_000)
        store.upsertProjects(connId, listOf(project(2)), now = 5_000)
        assertEquals(1, store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 5_000, now = 6_000))
        val before = store.projectRows(connId).first { it[GitLabRawStore.Projects.projectId] == 1L }

        assertTrue(store.markProjectArchived(connId, 1))
        assertFalse(store.markProjectArchived(connId, 1), "guarded on archived = false: a repeated pass is a no-op")
        assertFalse(store.markProjectArchived(connId, 99))
        val after = store.projectRows(connId).first { it[GitLabRawStore.Projects.projectId] == 1L }
        assertTrue(after[GitLabRawStore.Projects.archived])
        assertEquals(6_000L, after[GitLabRawStore.Projects.movedOutAt], "still moved out: the mark must not resurrect or delete")
        assertNull(after[GitLabRawStore.Projects.deletedAt])
        assertEquals(before[GitLabRawStore.Projects.sha256], after[GitLabRawStore.Projects.sha256])
        assertEquals(before[GitLabRawStore.Projects.payload], after[GitLabRawStore.Projects.payload])
        assertEquals(before[GitLabRawStore.Projects.changedAt], after[GitLabRawStore.Projects.changedAt])
        assertEquals(before[GitLabRawStore.Projects.lastSeenAt], after[GitLabRawStore.Projects.lastSeenAt])
        assertFalse(store.projectRows(connId).first { it[GitLabRawStore.Projects.projectId] == 2L }[GitLabRawStore.Projects.archived])
    }

    @Test
    fun `a project relisted under another configured group is a change`() = runBlocking<Unit> {
        val connId = connection()
        val original = project(1)
        store.upsertProjects(connId, listOf(original), now = 1_000)
        val relisted = RawGitLabProject(1, original.pathWithNamespace, "other", 9, original.defaultBranch, false, original.payload)

        assertEquals(listOf(RawUpsertOutcome.CHANGED), store.upsertProjects(connId, listOf(relisted), now = 2_000))
        val row = store.projectRows(connId).single()
        assertEquals("other", row[GitLabRawStore.Projects.groupPath])
        assertEquals(9L, row[GitLabRawStore.Projects.groupId])
        assertEquals(2_000L, row[GitLabRawStore.Projects.changedAt])
        assertEquals(listOf(RawUpsertOutcome.UNCHANGED), store.upsertProjects(connId, listOf(relisted), now = 3_000),
            "and then it is stable")
    }

    @Test
    fun `a page repeating a key is judged by its first occurrence`() = runBlocking<Unit> {
        val connId = connection()
        val outcomes = store.upsertProjects(connId, listOf(project(1), project(1), project(2)), now = 1_000)
        assertEquals(listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.INSERTED), outcomes)
        assertEquals(2, store.projectRows(connId).size)
    }

    @Test
    fun `users diff the same way, the sweep tombstones the unseen and a reappearing user is resurrected`() = runBlocking<Unit> {
        val connId = connection()
        assertEquals(
            listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.INSERTED),
            store.upsertUsers(connId, listOf(user(1), user(2)), now = 1_000),
        )
        assertEquals(1_000L, store.oldestLiveUserSeenAt(connId))
        assertEquals(
            listOf(RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.CHANGED),
            store.upsertUsers(connId, listOf(user(1), user(2, name = "Renamed")), now = 5_000),
        )
        store.upsertUsers(connId, listOf(user(1)), now = 8_000)
        assertEquals(1, store.markUsersDeletedNotSeenSince(connId, passStartedAt = 8_000, now = 9_000))
        assertEquals(8_000L, store.oldestLiveUserSeenAt(connId), "a deleted user no longer counts for freshness")
        assertEquals(1L, store.counts(connId).users)
        assertEquals(1L, store.counts(connId).usersDeleted)

        assertEquals(listOf(RawUpsertOutcome.RESURRECTED), store.upsertUsers(connId, listOf(user(2, name = "Renamed")), now = 12_000))
        assertNull(store.userRows(connId).single { it[GitLabRawStore.Users.userId] == 2L }[GitLabRawStore.Users.deletedAt])
    }

    @Test
    fun `emails are stored sorted, distinct and lowercase, inside the payload too, and a new email is a change`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertUsers(connId, listOf(user(1, emails = listOf("B@x.test", "a@x.test", "b@x.test"))), now = 1_000)
        val row = store.userRows(connId).single()
        val stored = Json.parseToJsonElement(row[GitLabRawStore.Users.emails]) as JsonArray
        assertEquals(listOf("a@x.test", "b@x.test"), stored.map { it.jsonPrimitive.content })
        val payload = Json.parseToJsonElement(row[GitLabRawStore.Users.payload]).jsonObject
        assertEquals(stored, payload.getValue("emails").jsonArray, "one hash covers both")
        assertNull(payload["bio"], "an unlisted member is not stored")
        assertEquals(setOf("id", "username", "name", "state", "email", "emails"), payload.keys)

        assertEquals(
            listOf(RawUpsertOutcome.UNCHANGED),
            store.upsertUsers(connId, listOf(user(1, emails = listOf("a@x.test", "B@X.test"))), now = 2_000),
        )
        assertEquals(
            listOf(RawUpsertOutcome.CHANGED),
            store.upsertUsers(connId, listOf(user(1, emails = listOf("a@x.test", "b@x.test", "c@x.test"))), now = 3_000),
        )
    }

    @Test
    fun `PURGE drains both tables in batches and leaves other connections alone`() = runBlocking<Unit> {
        val connId = connection()
        val other = connection()
        store.upsertProjects(connId, (1L..7L).map { project(it) }, now = 1_000)
        store.upsertUsers(connId, (1L..7L).map { user(it) }, now = 1_000)
        store.upsertProjects(other, listOf(project(1)), now = 1_000)

        assertEquals(3, store.purgeProjectsBatch(connId, batchSize = 3))
        assertEquals(3, store.purgeUsersBatch(connId, batchSize = 3))
        store.purgeAll(connId, batchSize = 3)

        assertTrue(store.projectRows(connId).isEmpty())
        assertTrue(store.userRows(connId).isEmpty())
        assertNotNull(store.projectRows(other).singleOrNull(), "another connection's rows are untouched")
    }
}
