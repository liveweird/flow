package ch.nokillswit

import ch.nokillswit.jira.JiraNormalizer
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `jira/JiraNormalizer.kt` (v0.2.0 plan §8) in isolation — no DB, no stub, hand-crafted minimal
 * JSON. `sample-data/jira-stub`'s own fixture always populates every optional field for AT LEAST
 * one issue, but never leaves them ALL absent on the SAME issue — this suite covers exactly the
 * "absent/null" branches `JiraSyncPipelineTest`/`NormalizationPipelineTest`'s full-dataset runs
 * can't reach (e.g. a `FIELD` catalog with no matching custom field, or an issue with no assignee,
 * no team, no parent, no worklogs and no changelog at all).
 */
class JiraNormalizerTest {

    @Test
    fun `discoverFieldIds returns null for every field when no FIELD entities match`() {
        val fieldIds = JiraNormalizer.discoverFieldIds(emptyList())
        assertNull(fieldIds.sprintFieldId)
        assertNull(fieldIds.rankFieldId)
        assertNull(fieldIds.teamFieldId)
        assertNull(fieldIds.storyPointsFieldId)
        assertNull(fieldIds.flaggedFieldId)
    }

    @Test
    fun `discoverFieldIds matches by schema-custom and by name`() {
        val fields = listOf(
            """{"id":"customfield_1","name":"Sprint","schema":{"custom":"com.pyxis.greenhopper.jira:gh-sprint"}}""",
            """{"id":"customfield_2","name":"Rank","schema":{"custom":"com.pyxis.greenhopper.jira:gh-lexo-rank"}}""",
            """{"id":"customfield_3","name":"Team",""" +
                """"schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:atlassian-team"}}""",
            """{"id":"customfield_4","name":"Story point estimate",""" +
                """"schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}}""",
            """{"id":"customfield_5","name":"Flagged",""" +
                """"schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:multicheckboxes"}}""",
            """{"id":"summary","name":"Summary","schema":{"system":"summary"}}""",
        )
        val fieldIds = JiraNormalizer.discoverFieldIds(fields)
        assertEquals("customfield_1", fieldIds.sprintFieldId)
        assertEquals("customfield_2", fieldIds.rankFieldId)
        assertEquals("customfield_3", fieldIds.teamFieldId)
        assertEquals("customfield_4", fieldIds.storyPointsFieldId)
        assertEquals("customfield_5", fieldIds.flaggedFieldId)
    }

    private val noFieldIds = JiraNormalizer.discoverFieldIds(emptyList())

    private fun minimalIssuePayload(
        assignee: String = "null",
        resolution: String = "null",
        parent: String? = null,
        team: String? = null,
        resolutiondate: String = "null",
    ): String = """
        {
          "id": "40000",
          "key": "FOO-1",
          "fields": {
            "summary": "A minimal issue",
            "issuetype": {"name": "Task"},
            "project": {"key": "FOO"},
            "status": {"id": "1"},
            "created": "2026-01-01T00:00:00.000Z",
            "updated": "2026-01-02T00:00:00.000Z",
            "resolutiondate": $resolutiondate,
            "assignee": $assignee,
            "reporter": {"accountId": "acc-reporter"},
            "priority": {"name": "Medium"},
            "resolution": $resolution,
            "labels": [],
            "components": []
            ${parent?.let { ""","parent":$it""" } ?: ""}
            ${team?.let { ""","customfield_9999":$it""" } ?: ""}
          }
        }
    """.trimIndent()

    @Test
    fun `normalizeIssue tolerates every optional field being absent (assignee, resolution, parent, team, worklogs, changelog)`() {
        val input = JiraNormalizer.normalizeIssue(
            issuePayload = minimalIssuePayload(),
            changelogPayloads = emptyList(),
            worklogPayloads = emptyList(),
            fieldIds = noFieldIds,
            tombstone = TombstoneKind.NONE,
        )
        assertEquals(40000L, input.issueId)
        assertNull(input.facts.assigneeAccountId)
        assertNull(input.facts.resolution)
        assertNull(input.facts.parentIssueId)
        assertNull(input.facts.teamValueJson)
        assertNull(input.facts.resolvedAtMs)
        assertNull(input.facts.storyPoints)
        assertNull(input.facts.originalEstimateSeconds)
        assertEquals(emptyList(), input.facts.labels)
        assertEquals(emptyList(), input.facts.components)
        assertEquals(emptyList(), input.facts.fixVersions)
        assertEquals(0L, input.facts.timeSpentSeconds)
        assertEquals(emptyList(), input.statusEvents)
        assertEquals(emptyList(), input.assigneeEvents)
        assertEquals(emptyList(), input.sprintEvents, "no sprintFieldId discovered means no sprint events at all")
        assertEquals(emptyList(), input.flaggedEvents, "no flaggedFieldId discovered means no flagged events at all")
        assertEquals(emptyList(), input.currentSprintIds)
        assertNull(input.currentSprintText)
        assertTrue(!input.currentFlagged)
        assertEquals(emptyList(), input.fieldChanges)
        assertEquals(emptyList(), input.worklogs)
    }

    @Test
    fun `normalizeIssue reads a present assignee, resolution, parent and team`() {
        val input = JiraNormalizer.normalizeIssue(
            issuePayload = minimalIssuePayload(
                assignee = """{"accountId":"acc-1","displayName":"Ann"}""",
                resolution = """{"name":"Done"}""",
                parent = """{"id":"39999","key":"FOO-0"}""",
                team = """{"id":"team-1","name":"Payments"}""",
                resolutiondate = "\"2026-01-03T00:00:00.000Z\"",
            ),
            changelogPayloads = emptyList(),
            worklogPayloads = emptyList(),
            fieldIds = noFieldIds.copy(teamFieldId = "customfield_9999"),
            tombstone = TombstoneKind.NONE,
        )
        assertEquals("acc-1", input.facts.assigneeAccountId)
        assertEquals("Done", input.facts.resolution)
        assertEquals(39999L, input.facts.parentIssueId)
        assertTrue(input.facts.teamValueJson.orEmpty().contains("Payments"))
        assertEquals("acc-1", input.currentAssignee.id)
        assertEquals("Ann", input.currentAssignee.text)
        assertTrue(input.facts.resolvedAtMs != null)
    }

    @Test
    fun `normalizeIssue tombstone kinds are carried through unchanged`() {
        val deleted = JiraNormalizer.normalizeIssue(minimalIssuePayload(), emptyList(), emptyList(), noFieldIds, TombstoneKind.DELETED)
        assertEquals(TombstoneKind.DELETED, deleted.facts.tombstone)
        val moved = JiraNormalizer.normalizeIssue(minimalIssuePayload(), emptyList(), emptyList(), noFieldIds, TombstoneKind.MOVED_OUT)
        assertEquals(TombstoneKind.MOVED_OUT, moved.facts.tombstone)
    }

    @Test
    fun `normalizeIssue parses status, assignee, sprint and flagged changelog events plus worklogs`() {
        val fieldIds = noFieldIds.copy(sprintFieldId = "customfield_sprint", flaggedFieldId = "customfield_flagged")
        val changelogs = listOf(
            """{"id":"1","created":"2026-01-01T01:00:00.000Z","items":[
                {"field":"status","fieldId":"status","from":"1","fromString":"To Do","to":"3","toString":"In Progress"}
            ]}""",
            """{"id":"2","created":"2026-01-01T02:00:00.000Z","items":[
                {"field":"assignee","fieldId":"assignee","from":null,"fromString":null,"to":"acc-1","toString":"Ann"}
            ]}""",
            """{"id":"3","created":"2026-01-01T03:00:00.000Z","items":[
                {"field":"Sprint","fieldId":"customfield_sprint","from":null,"fromString":null,"to":"5,6","toString":"Sprint 5, Sprint 6"}
            ]}""",
            """{"id":"4","created":"2026-01-01T04:00:00.000Z","items":[
                {"field":"Flagged","fieldId":"customfield_flagged","from":null,"fromString":null,"to":"10019","toString":"Impediment"}
            ]}""",
        )
        val worklogs = listOf(
            """{"id":"900000","issueId":"40000","author":{"accountId":"acc-1"},""" +
                """"started":"2026-01-01T05:00:00.000Z","timeSpentSeconds":3600}""",
        )
        val input = JiraNormalizer.normalizeIssue(minimalIssuePayload(), changelogs, worklogs, fieldIds, TombstoneKind.NONE)

        assertEquals(1, input.statusEvents.size)
        assertEquals("1", input.statusEvents.single().fromStatusId)
        assertEquals("3", input.statusEvents.single().toStatusId)

        assertEquals(1, input.assigneeEvents.size)
        assertNull(input.assigneeEvents.single().fromValueId)
        assertEquals("acc-1", input.assigneeEvents.single().toValueId)

        assertEquals(1, input.sprintEvents.size)
        assertEquals("6", input.sprintEvents.single().toValueId, "value_id is the LAST id of a multi-valued sprint change")
        assertEquals("Sprint 5, Sprint 6", input.sprintEvents.single().toValueText)

        assertEquals(1, input.flaggedEvents.size)
        assertEquals("true", input.flaggedEvents.single().toValueId)
        assertEquals("false", input.flaggedEvents.single().fromValueId)

        assertEquals(1, input.worklogs.size)
        assertEquals(900000L, input.worklogs.single().worklogId)
        assertEquals(3600L, input.worklogs.sumOf { it.timeSpentSeconds })

        assertEquals(4, input.fieldChanges.size, "status/assignee/Sprint/Flagged are all tracked field changes")
    }

    @Test
    fun `statusRefs maps every status category, defaulting an unknown key to UNKNOWN`() {
        val refs = JiraNormalizer.statusRefs(
            listOf(
                """{"id":"1","name":"To Do","statusCategory":{"key":"new"}}""",
                """{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}}""",
                """{"id":"10002","name":"Done","statusCategory":{"key":"done"}}""",
                """{"id":"99","name":"Weird","statusCategory":{"key":"something-else"}}""",
            ),
        )
        assertEquals(StatusCategory.TODO, refs.first { it.statusId == "1" }.category)
        assertEquals(StatusCategory.IN_PROGRESS, refs.first { it.statusId == "3" }.category)
        assertEquals(StatusCategory.DONE, refs.first { it.statusId == "10002" }.category)
        assertEquals(StatusCategory.UNKNOWN, refs.first { it.statusId == "99" }.category)
    }

    @Test
    fun `peopleRefs falls back to accountId when displayName is absent, and defaults active to true`() {
        val refs = JiraNormalizer.peopleRefs(
            listOf(
                """{"accountId":"acc-1","displayName":"Ann","emailAddress":"ann@example.com","active":true}""",
                """{"accountId":"acc-2"}""",
            ),
        )
        assertEquals("Ann", refs.first { it.accountId == "acc-1" }.displayName)
        assertEquals("acc-2", refs.first { it.accountId == "acc-2" }.displayName)
        assertNull(refs.first { it.accountId == "acc-2" }.email)
        assertTrue(refs.first { it.accountId == "acc-2" }.active)
    }

    @Test
    fun `boardRefs tolerates a board with no matching BOARD_CONFIGURATION entity`() {
        val refs = JiraNormalizer.boardRefs(
            boardPayloads = listOf("""{"id":"1","name":"Board 1","type":"scrum","location":{"projectKey":"FOO"}}"""),
            boardConfigurationPayloads = emptyList(),
        )
        assertEquals(1, refs.size)
        assertEquals(emptyList(), refs.single().columns)
    }

    @Test
    fun `boardRefs joins a board with its configuration columns`() {
        val refs = JiraNormalizer.boardRefs(
            boardPayloads = listOf("""{"id":"1","name":"Board 1","type":"scrum","location":{"projectKey":"FOO"}}"""),
            boardConfigurationPayloads = listOf(
                """{"id":"1","columnConfig":{"columns":[{"name":"To Do","statuses":[{"id":"1"}]}]}}""",
            ),
        )
        assertEquals(1, refs.single().columns.size)
        assertEquals(listOf("1"), refs.single().columns.single().statusIds)
    }

    @Test
    fun `sprintRefs handles an absent boardId, startDate, endDate and goal`() {
        val refs = JiraNormalizer.sprintRefs(listOf("""{"id":"5","name":"Sprint 5","state":"active"}"""))
        val sprint = refs.single()
        assertNull(sprint.boardId)
        assertNull(sprint.startAtMs)
        assertNull(sprint.endAtMs)
        assertNull(sprint.goal)
    }
}
