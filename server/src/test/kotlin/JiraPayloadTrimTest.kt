package ch.nokillswit

import ch.nokillswit.DirtyJira.ADF
import ch.nokillswit.DirtyJira.DESCRIPTION_EDIT
import ch.nokillswit.DirtyJira.REMOTE_LINK
import ch.nokillswit.DirtyJira.REMOTE_WORK_ITEM_LINK
import ch.nokillswit.DirtyJira.adf
import ch.nokillswit.DirtyJira.injectIntoHistory
import ch.nokillswit.DirtyJira.injectIntoIssue
import ch.nokillswit.DirtyJira.injectIntoWorklog
import ch.nokillswit.DirtyJira.item
import ch.nokillswit.DirtyJira.obj
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.jira.JiraReferenceRows
import ch.nokillswit.jira.DROPPED_HISTORY_ITEM_FIELDS
import ch.nokillswit.jira.DROPPED_HISTORY_ITEM_FIELD_IDS
import ch.nokillswit.jira.DROPPED_ISSUE_FIELDS
import ch.nokillswit.jira.DROPPED_WORKLOG_KEYS
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraNormalizer
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.SLIMMED_ISSUE_USER_FIELDS
import ch.nokillswit.jira.trimHistory
import ch.nokillswit.jira.trimIssue
import ch.nokillswit.jira.trimWorklog
import ch.nokillswit.jira.trimmedHistoryCanonicalJson
import ch.nokillswit.jira.trimmedIssueCanonicalJson
import ch.nokillswit.jira.trimmedWorklogCanonicalJson
import ch.nokillswit.norm.TombstoneKind
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What Jira really embeds for a user (`self`, `emailAddress`, avatar URLs, ...). */
private fun fullUser(accountId: String, name: String) =
    """{"self":"https://x.atlassian.net/rest/api/3/user?accountId=$accountId","accountId":"$accountId",""" +
        """"emailAddress":"$accountId@example.com","avatarUrls":{"48x48":"https://x/48.png"},"displayName":"$name",""" +
        """"active":true,"timeZone":"Europe/Warsaw","accountType":"atlassian"}"""

private fun slimUser(accountId: String, name: String) = """{"accountId":"$accountId","displayName":"$name","accountType":"atlassian"}"""

private val ADF_KEPT = adf("kept custom field text")

/** A rich issue document: every dropped key, full users, and every kind of kept key (custom fields with ADF, estimates, links). */
private val RICH_ISSUE = """
    {"id":"9001","key":"ABC-1","self":"https://x.atlassian.net/rest/api/3/issue/9001","expand":"renderedFields,names","fields":{
      "summary":"Rich issue","status":{"id":"10001","name":"To Do","statusCategory":{"id":2,"key":"new","name":"To Do"}},
      "issuetype":{"id":"10000","name":"Story","subtask":false},"project":{"id":"10010","key":"ABC","name":"Abc"},
      "created":"2025-09-02T12:40:17.309+0000","updated":"2025-09-11T12:40:17.309+0000","resolutiondate":null,"resolution":null,
      "priority":{"id":"3","name":"Medium"},"labels":["a","b"],"components":[{"id":"1","name":"Core"}],"fixVersions":[{"id":"2","name":"1.0"}],
      "duedate":"2026-01-05","parent":{"id":"7","key":"ABC-7"},"issuelinks":[{"id":"1","type":{"name":"Blocks"}}],"subtasks":[],
      "assignee":${fullUser("acc-1", "Ann")},"reporter":${fullUser("acc-2", "Bob")},"creator":${fullUser("acc-3", "Cy")},
      "timetracking":{"originalEstimate":"1h","originalEstimateSeconds":3600,"remainingEstimateSeconds":1800},
      "timespent":1800,"timeoriginalestimate":3600,"aggregatetimespent":1800,
      "customfield_10020":[{"id":5,"name":"Sprint 5","state":"active"}],"customfield_10016":3.0,"customfield_10019":"0|i1:",
      "customfield_10001":{"id":"team-1","name":"Platform"},"customfield_10021":[],"customfield_11111":$ADF_KEPT,
      "customfield_12222":{"accountId":"acc-9","emailAddress":"keep@example.com","displayName":"Picker"},
      "description":$ADF,"comment":{"comments":[{"id":"1","body":$ADF,"author":${fullUser("acc-1", "Ann")}}],"total":1},
      "worklog":{"worklogs":[{"id":"5","comment":$ADF,"author":${fullUser("acc-1", "Ann")}}],"total":1},
      "attachment":[{"id":"3","filename":"secret.pdf","author":${fullUser("acc-1", "Ann")}}]}}
""".trimIndent()

private const val GREENHOPPER = "com.pyxis.greenhopper.jira"
private const val FIELD_PAYLOADS_SPRINT =
    """{"id":"customfield_10020","name":"Sprint","custom":true,"schema":{"type":"array","custom":"$GREENHOPPER:gh-sprint"}}"""
private const val FIELD_PAYLOADS_RANK =
    """{"id":"customfield_10019","name":"Rank","custom":true,"schema":{"type":"any","custom":"$GREENHOPPER:gh-lexo-rank"}}"""
private const val FIELD_PAYLOADS_TEAM = """{"id":"customfield_10001","name":"Team","custom":true,""" +
    """"schema":{"type":"team","custom":"com.atlassian.jira.plugin.system.customfieldtypes:atlassian-team"}}"""
private const val FIELD_PAYLOADS_POINTS =
    """{"id":"customfield_10016","name":"Story point estimate","custom":true,"schema":{"type":"number"}}"""
private const val FIELD_PAYLOADS_FLAGGED = """{"id":"customfield_10021","name":"Flagged","custom":true,"schema":{"type":"array"}}"""

private fun history(id: Long, created: String, author: String, vararg items: String) =
    """{"id":"$id","author":$author,"created":"$created","items":[${items.joinToString(",")}],"historyMetadata":{"type":"x"}}"""

private val RICH_HISTORIES = listOf(
    history(
        1, "2025-09-03T10:00:00.000+0000", fullUser("acc-1", "Ann"),
        DESCRIPTION_EDIT, item("status", "status", "10001", "10002", "To Do", "In Progress"), REMOTE_LINK,
    ),
    history(2, "2025-09-04T10:00:00.000+0000", fullUser("acc-2", "Bob"), item("assignee", "assignee", "acc-1", "acc-2", "Ann", "Bob")),
    history(3, "2025-09-05T10:00:00.000+0000", fullUser("acc-1", "Ann"), item("Sprint", "customfield_10020", "4", "4, 5", "S4", "S4, S5")),
    history(
        4, "2025-09-06T10:00:00.000+0000", fullUser("acc-1", "Ann"),
        item("Flagged", "customfield_10021", null, "10019", null, "Impediment"),
    ),
    history(
        5, "2025-09-07T10:00:00.000+0000", fullUser("acc-1", "Ann"),
        item("Parent", "parent", "6", "7", "ABC-6", "ABC-7"), REMOTE_WORK_ITEM_LINK,
        item("priority", "priority", "2", "3", "High", "Medium"),
    ),
    // A custom field whose DISPLAY name is "description": a tracked custom field, its history must survive.
    history(
        6, "2025-09-08T10:00:00.000+0000", fullUser("acc-1", "Ann"),
        item("description", "customfield_555", null, "x", null, "kept as a custom field"),
        item("Epic Link", null, "ABC-5", "ABC-7", "ABC-5", "ABC-7"), item("duedate", "duedate", null, "2026-01-05", null, "2026-01-05"),
    ),
    // Only dropped items: the row is kept, with items: [].
    history(7, "2025-09-09T10:00:00.000+0000", fullUser("acc-1", "Ann"), DESCRIPTION_EDIT, REMOTE_LINK, REMOTE_WORK_ITEM_LINK),
)

private fun worklog(id: Long, started: String, seconds: Long) =
    """{"self":"https://x.atlassian.net/rest/api/3/issue/9001/worklog/$id","id":"$id","issueId":"9001",""" +
        """"author":${fullUser("acc-1", "Ann")},""" +
        """"updateAuthor":${fullUser("acc-2", "Bob")},"comment":$ADF,"started":"$started","created":"$started","updated":"$started",""" +
        """"timeSpent":"1h","timeSpentSeconds":$seconds,"visibility":{"type":"role","value":"Dev"},"properties":[{"key":"k"}]}"""

private val RICH_WORKLOGS = listOf(worklog(21, "2025-09-04T08:00:00.000+0000", 3600), worklog(22, "2025-09-05T08:00:00.000+0000", 1800))

private fun JsonElement.fields(): JsonObject = jsonObject.getValue("fields").jsonObject

/**
 * The payload trim (`jira/JiraPayloadTrim.kt`): the exact key sets, everything else kept, idempotence, the stable hash, and THE
 * EQUIVALENCE PROPERTY - normalizing a trimmed document gives exactly the rows the untrimmed one gives - over a rich synthetic
 * document and over every raw row of the shared stub fixture with the dropped keys injected.
 */
class JiraPayloadTrimTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }

    // --- the key sets -------------------------------------------------------------------------------------

    @Test
    fun `the dropped sets are exactly the decided ones`() {
        assertEquals(setOf("comment", "worklog", "description", "attachment"), DROPPED_ISSUE_FIELDS)
        assertEquals(setOf("assignee", "reporter", "creator"), SLIMMED_ISSUE_USER_FIELDS)
        assertEquals(setOf("self", "comment"), DROPPED_WORKLOG_KEYS)
        assertEquals(setOf("description"), DROPPED_HISTORY_ITEM_FIELD_IDS)
        assertEquals(setOf("description", "RemoteIssueLink", "RemoteWorkItemLink"), DROPPED_HISTORY_ITEM_FIELDS)
    }

    @Test
    fun `an issue loses exactly the four fields, slims the three users and keeps everything else untouched`() {
        val original = obj(RICH_ISSUE)
        val trimmed = trimIssue(original)

        val originalFields = original.fields()
        val trimmedFields = trimmed.fields()
        assertEquals(originalFields.keys - DROPPED_ISSUE_FIELDS, trimmedFields.keys)
        for (key in trimmedFields.keys - SLIMMED_ISSUE_USER_FIELDS) {
            assertEquals(originalFields.getValue(key), trimmedFields.getValue(key), "fields.$key is kept verbatim")
        }
        assertEquals(obj(slimUser("acc-1", "Ann")), trimmedFields.getValue("assignee"))
        assertEquals(obj(slimUser("acc-2", "Bob")), trimmedFields.getValue("reporter"))
        assertEquals(obj(slimUser("acc-3", "Cy")), trimmedFields.getValue("creator"))
        // The top level is untouched, and so is a user picker held in a CUSTOM field (customfield_* is kept verbatim).
        assertEquals(original.jsonObject - "fields", trimmed.jsonObject - "fields")
        assertTrue(trimmedFields.getValue("customfield_12222").jsonObject.containsKey("emailAddress"))
        assertEquals(originalFields.getValue("customfield_11111"), trimmedFields.getValue("customfield_11111"), "nested ADF is kept")
        assertEquals(originalFields.getValue("timetracking"), trimmedFields.getValue("timetracking"))
        assertEquals(originalFields.getValue("issuelinks"), trimmedFields.getValue("issuelinks"))
    }

    @Test
    fun `a history is slimmed and loses only the description and remote-link items, in the order the rest were`() {
        val original = obj(RICH_HISTORIES.first())
        val trimmed = trimHistory(original).jsonObject

        assertEquals(original.keys, trimmed.keys)
        assertEquals(obj(slimUser("acc-1", "Ann")), trimmed.getValue("author"))
        assertEquals(original.getValue("historyMetadata"), trimmed.getValue("historyMetadata"))
        assertEquals(original.getValue("id"), trimmed.getValue("id"))
        assertEquals(original.getValue("created"), trimmed.getValue("created"))
        assertEquals(listOf("status"), trimmed.getValue("items").jsonArray.map { it.jsonObject.getValue("field").toString().trim('"') })
        assertEquals(original.getValue("items").jsonArray[1], trimmed.getValue("items").jsonArray.single(), "the kept item is verbatim")
    }

    @Test
    fun `each dropped item shape is dropped, and a custom field named like a dropped one is kept`() {
        val items = listOf(
            DESCRIPTION_EDIT,
            item("description", null, null, null, "a", "b"),
            item("Description", "description", null, null, "a", "b"),
            REMOTE_LINK, REMOTE_WORK_ITEM_LINK,
            item("description", "customfield_555", null, "x", null, "y"),
            item("RemoteIssueLink", "customfield_556", null, "x", null, "y"),
            item("Attachment", "Attachment", null, "1", null, "f.pdf"),
            item("WorklogId", "WorklogId", null, "1", null, null),
            item("timespent", "timespent", null, "60", null, "60"),
            item("Parent", "parent", "1", "2", "A-1", "A-2"),
            item("Link", "Link", null, "A-9", null, "This issue relates to A-9"),
        )
        val kept = trimHistory(obj(history(1, "2025-09-03T10:00:00.000+0000", "{}", *items.toTypedArray()))).jsonObject
            .getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("customfield_555", "customfield_556", "Attachment", "WorklogId", "timespent", "parent", "Link"),
            kept.map { (it["fieldId"] as JsonPrimitive).content },
            "the three description spellings and the two remote-link spellings are gone; every other item is kept in order",
        )
        assertEquals(obj(items[5]), kept.first(), "a kept item is verbatim")
    }

    @Test
    fun `a history with only dropped items keeps its row with an empty items array and every other key`() {
        val onlyDropped = obj(RICH_HISTORIES.last())
        val trimmed = trimHistory(onlyDropped).jsonObject
        assertEquals(onlyDropped.keys, trimmed.keys)
        assertEquals(JsonArray(emptyList()), trimmed.getValue("items"))
        assertEquals(onlyDropped.getValue("id"), trimmed.getValue("id"))
        assertEquals(onlyDropped.getValue("created"), trimmed.getValue("created"))
        assertEquals(onlyDropped.getValue("historyMetadata"), trimmed.getValue("historyMetadata"))
        assertEquals(
            """{"author":{"accountId":"acc-1","accountType":"atlassian","displayName":"Ann"},""" +
                """"created":"2025-09-09T10:00:00.000+0000","historyMetadata":{"type":"x"},"id":"7","items":[]}""",
            trimmedHistoryCanonicalJson(RICH_HISTORIES.last()),
        )
    }

    @Test
    fun `a worklog loses self and comment, slims both authors and keeps every other key`() {
        val original = obj(RICH_WORKLOGS.first())
        val trimmed = trimWorklog(original).jsonObject
        assertEquals(original.keys - DROPPED_WORKLOG_KEYS, trimmed.keys)
        assertEquals(obj(slimUser("acc-1", "Ann")), trimmed.getValue("author"))
        assertEquals(obj(slimUser("acc-2", "Bob")), trimmed.getValue("updateAuthor"))
        for (key in trimmed.keys - setOf("author", "updateAuthor")) assertEquals(original.getValue(key), trimmed.getValue(key), key)
    }

    // --- totality, idempotence, the hash ------------------------------------------------------------------

    @Test
    fun `an unexpected shape is left exactly as it is and never throws`() {
        val notObject = JsonPrimitive("text")
        assertSame(notObject, trimIssue(notObject))
        assertSame(JsonNull, trimIssue(JsonNull))
        assertSame(notObject, trimHistory(notObject))
        assertSame(notObject, trimWorklog(notObject))
        val noFields = obj("""{"id":"1","key":"A-1"}""")
        assertSame(noFields, trimIssue(noFields))
        val fieldsNotObject = obj("""{"id":"1","fields":"oops"}""")
        assertSame(fieldsNotObject, trimIssue(fieldsNotObject))
        val nullFields = obj("""{"id":"1","fields":null}""")
        assertSame(nullFields, trimIssue(nullFields))
        val nullUsers = obj("""{"id":"1","fields":{"assignee":null,"reporter":"x","creator":[]}}""")
        assertEquals(nullUsers, trimIssue(nullUsers))

        val itemsNotArray = obj("""{"id":"1","author":null,"items":"oops"}""")
        assertEquals(itemsNotArray, trimHistory(itemsNotArray))
        val oddItems = obj("""{"id":"1","items":[1,null,"x",{"field":"description"},{"field":5,"fieldId":7}]}""")
        assertEquals(
            obj("""{"id":"1","items":[1,null,"x",{"field":5,"fieldId":7}]}"""),
            trimHistory(oddItems),
            "non-object items stay, an item with non-string names is not a dropped one",
        )
        assertEquals(obj("""{"id":"1"}"""), trimHistory(obj("""{"id":"1"}""")))
    }

    @Test
    fun `trimming twice is trimming once`() {
        val issue = trimIssue(obj(RICH_ISSUE))
        assertEquals(issue, trimIssue(issue))
        RICH_HISTORIES.forEach { assertEquals(trimHistory(obj(it)), trimHistory(trimHistory(obj(it)))) }
        RICH_WORKLOGS.forEach { assertEquals(trimWorklog(obj(it)), trimWorklog(trimWorklog(obj(it)))) }
        val text = trimmedIssueCanonicalJson(RICH_ISSUE)
        assertEquals(text, trimmedIssueCanonicalJson(text))
    }

    @Test
    fun `the canonical text and hash of a document and of its pre-trimmed form are identical, and key order does not matter`() {
        val trimmedText = trimmedIssueCanonicalJson(RICH_ISSUE)
        assertEquals(sha256Hex(trimmedText), sha256Hex(trimmedIssueCanonicalJson(canonicalJson(trimIssue(obj(RICH_ISSUE)).toString()))))
        // A change confined to a dropped key (a new comment) or a dropped user key (an e-mail) is not a content change.
        val comment2 = RICH_ISSUE.replace("free text", "another comment text").replace("acc-1@example.com", "new@example.com")
        assertNotEquals(RICH_ISSUE, comment2)
        assertEquals(trimmedText, trimmedIssueCanonicalJson(comment2))
        val reversedKeys = obj(RICH_ISSUE).entries.reversed().associate { it.key to it.value }
        val reordered = Json.encodeToString(JsonObject.serializer(), JsonObject(reversedKeys))
        assertEquals(trimmedText, trimmedIssueCanonicalJson(reordered))
        assertFalse(trimmedText.contains("emailAddress\":\"acc-"), "the slimmed users carry no e-mail")
        assertTrue(trimmedText.contains("keep@example.com"), "a custom-field user picker is kept verbatim")
        for (text in RICH_HISTORIES.map(::trimmedHistoryCanonicalJson) + RICH_WORKLOGS.map(::trimmedWorklogCanonicalJson)) {
            assertFalse(text.contains("emailAddress"), text)
            assertFalse(text.contains("RemoteIssueLink"), text)
        }
        assertTrue(trimmedHistoryCanonicalJson(RICH_HISTORIES[5]).contains("kept as a custom field"))
    }

    @Test
    fun `a NUL in a dropped key never reaches the stored text, and one in a kept key is replaced`() {
        val issue = """{"id":"1","key":"A-1","fields":{"description":"a\u0000b","summary":"x\u0000y","project":{"id":"1","key":"A"}}}"""
        val text = trimmedIssueCanonicalJson(issue)
        assertFalse(text.contains("description"))
        assertTrue(text.contains("x�y"), text)
        assertEquals(text, trimmedIssueCanonicalJson(issue.replace("a\\u0000b", "other")))
    }

    // --- THE EQUIVALENCE PROPERTY -------------------------------------------------------------------------

    private val syntheticFieldIds = JiraReferenceRows.discoverFieldIds(
        listOf(FIELD_PAYLOADS_SPRINT, FIELD_PAYLOADS_RANK, FIELD_PAYLOADS_TEAM, FIELD_PAYLOADS_POINTS, FIELD_PAYLOADS_FLAGGED),
    )

    @Test
    fun `normalizing the trimmed documents of a rich synthetic issue gives exactly the rows of the untrimmed ones`() {
        assertEquals("customfield_10020", syntheticFieldIds.sprintFieldId, "the synthetic field discovery found the sprint field")
        val hierarchy = mapOf("10000" to 0)
        val untrimmed = JiraNormalizer.normalizeIssue(
            RICH_ISSUE, RICH_HISTORIES, RICH_WORKLOGS, syntheticFieldIds, TombstoneKind.NONE, hierarchy,
        )
        val trimmed = JiraNormalizer.normalizeIssue(
            trimmedIssueCanonicalJson(RICH_ISSUE),
            RICH_HISTORIES.map(::trimmedHistoryCanonicalJson),
            RICH_WORKLOGS.map(::trimmedWorklogCanonicalJson),
            syntheticFieldIds, TombstoneKind.NONE, hierarchy,
        )
        assertEquals(untrimmed, trimmed)

        // The comparison is not vacuous: the document is non-trivial and the trim removed something from every kind of payload.
        assertEquals("acc-2", untrimmed.facts.reporterAccountId)
        assertEquals("acc-1", untrimmed.currentAssignee.id)
        assertEquals(listOf(5L), untrimmed.currentSprintIds)
        assertEquals(3600L, untrimmed.facts.originalEstimateSeconds)
        assertTrue(untrimmed.facts.customFieldsJson.contains("customfield_11111"))
        assertEquals(1, untrimmed.statusEvents.size)
        assertEquals(2, untrimmed.parentEvents.size, "the Parent history and the Epic Link history are one move each")
        assertEquals(2, untrimmed.worklogs.size)
        assertTrue(untrimmed.fieldChanges.any { it.fieldId == "customfield_555" }, "the custom field named description is tracked")
        assertTrue(RICH_ISSUE.length > trimmedIssueCanonicalJson(RICH_ISSUE).length)
        assertTrue(RICH_HISTORIES.sumOf { it.length } > RICH_HISTORIES.sumOf { trimmedHistoryCanonicalJson(it).length })
    }

    @Test
    fun `normalizing every raw row of the stub fixture with the dropped keys injected equals normalizing it as stored`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val db = sharedDatabaseForTests()
        val store = SyncedStubFixture.rawStore()
        val fieldIds = JiraReferenceRows.discoverFieldIds(store.entityPayloadsByKind(connId, JiraEntityKind.FIELD.name))
        val hierarchy = JiraReferenceRows.issueTypeHierarchy(store.entityPayloadsByKind(connId, JiraEntityKind.ISSUE_TYPE.name))

        val issues = suspendTransaction(db) {
            JiraRawStore.Issues.select(JiraRawStore.Issues.issueId, JiraRawStore.Issues.payload)
                .where { (JiraRawStore.Issues.connectionId eq connId) and JiraRawStore.Issues.deletedAt.isNull() }
                .map { it[JiraRawStore.Issues.issueId] to it[JiraRawStore.Issues.payload] }.toList()
        }
        val histories = suspendTransaction(db) {
            JiraRawStore.Changelogs.select(
                JiraRawStore.Changelogs.issueId, JiraRawStore.Changelogs.createdAt,
                JiraRawStore.Changelogs.historyId, JiraRawStore.Changelogs.payload,
            ).where { JiraRawStore.Changelogs.connectionId eq connId }
                .toList().sortedWith(compareBy({ it[JiraRawStore.Changelogs.createdAt] }, { it[JiraRawStore.Changelogs.historyId] }))
                .groupBy({ it[JiraRawStore.Changelogs.issueId] }, { it[JiraRawStore.Changelogs.payload] })
        }
        val worklogs = suspendTransaction(db) {
            JiraRawStore.Worklogs.select(JiraRawStore.Worklogs.issueId, JiraRawStore.Worklogs.payload)
                .where { (JiraRawStore.Worklogs.connectionId eq connId) and JiraRawStore.Worklogs.deletedAt.isNull() }
                .toList().groupBy({ it[JiraRawStore.Worklogs.issueId] }, { it[JiraRawStore.Worklogs.payload] })
        }
        assertTrue(issues.size > 1_000, "the whole in-scope fixture is compared: ${issues.size}")

        var historiesCompared = 0
        var worklogsCompared = 0
        for ((issueId, issuePayload) in issues) {
            val issueHistories = histories[issueId].orEmpty()
            val issueWorklogs = worklogs[issueId].orEmpty()
            val dirtyIssue = injectIntoIssue(issuePayload)
            val dirtyHistories = issueHistories.mapIndexed { index, text -> injectIntoHistory(text, index) }
            val dirtyWorklogs = issueWorklogs.map(::injectIntoWorklog)
            historiesCompared += dirtyHistories.size
            worklogsCompared += dirtyWorklogs.size

            val asStored = JiraNormalizer.normalizeIssue(
                issuePayload, issueHistories, issueWorklogs, fieldIds, TombstoneKind.NONE, hierarchy,
            )
            val untrimmedDirty = JiraNormalizer.normalizeIssue(
                dirtyIssue, dirtyHistories.map { it.toString() }, dirtyWorklogs.map { it.toString() },
                fieldIds, TombstoneKind.NONE, hierarchy,
            )
            val trimmedDirty = JiraNormalizer.normalizeIssue(
                trimmedIssueCanonicalJson(dirtyIssue),
                dirtyHistories.map { trimmedHistoryCanonicalJson(it.toString()) },
                dirtyWorklogs.map { trimmedWorklogCanonicalJson(it.toString()) },
                fieldIds, TombstoneKind.NONE, hierarchy,
            )
            assertEquals(asStored, trimmedDirty, "issue $issueId: the trimmed injected documents normalize as the stored ones")
            assertEquals(untrimmedDirty, trimmedDirty, "issue $issueId: trimmed and untrimmed injected documents normalize alike")

            // The injection is real and the trim exact: it brings every payload back to the stored text.
            assertTrue(dirtyIssue.length > issuePayload.length)
            assertEquals(canonicalJson(issuePayload), trimmedIssueCanonicalJson(dirtyIssue), "issue $issueId: trim(inject(x)) is x")
            dirtyHistories.zip(issueHistories).forEach { (dirty, stored) ->
                assertEquals(obj(stored).getValue("items"), trimHistory(dirty).jsonObject.getValue("items"))
                assertEquals(canonicalJson(stored), trimmedHistoryCanonicalJson(dirty.toString()))
            }
            dirtyWorklogs.zip(issueWorklogs).forEach { (dirty, stored) ->
                assertTrue(dirty.toString().length > stored.length)
                assertEquals(canonicalJson(stored), trimmedWorklogCanonicalJson(dirty.toString()))
            }
        }
        assertTrue(historiesCompared > 1_000, "histories were compared: $historiesCompared")
        assertTrue(worklogsCompared > 100, "worklogs were compared: $worklogsCompared")
    }
}
