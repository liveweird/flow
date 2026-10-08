package ch.nokillswit

import ch.nokillswit.jira.JiraNormalizer
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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

    /** The first real tenant sent an unset Sprint custom field as JSON `null`, not `[]`, and every such issue failed PROCESS. */
    @Test
    fun `normalizeIssue treats a JSON null sprint, flagged, labels, components or fixVersions as empty`() {
        val payload = minimalIssuePayload(team = "null")
            .replace("\"labels\": []", "\"labels\": null")
            .replace("\"components\": []", "\"components\": null, \"fixVersions\": null, \"customfield_7\": null, \"customfield_8\": null")
        val input = JiraNormalizer.normalizeIssue(
            issuePayload = payload,
            changelogPayloads = emptyList(),
            worklogPayloads = emptyList(),
            fieldIds = noFieldIds.copy(sprintFieldId = "customfield_7", flaggedFieldId = "customfield_8", teamFieldId = "customfield_9999"),
            tombstone = TombstoneKind.NONE,
        )
        assertEquals(emptyList(), input.currentSprintIds)
        assertNull(input.currentSprintText)
        assertTrue(!input.currentFlagged)
        assertEquals(emptyList(), input.facts.labels)
        assertEquals(emptyList(), input.facts.components)
        assertEquals(emptyList(), input.facts.fixVersions)
        assertNull(input.facts.teamValueJson)
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
    fun `statusRefs accepts the statuses-search string enum and never throws on other shapes`() {
        val refs = JiraNormalizer.statusRefs(
            listOf(
                """{"id":"1","name":"To Do","scope":{"type":"GLOBAL"},"description":"","statusCategory":"TODO"}""",
                """{"id":"3","name":"In Progress","statusCategory":"IN_PROGRESS"}""",
                """{"id":"10135","name":"Abandoned","scope":{"type":"GLOBAL"},"description":"","statusCategory":"DONE"}""",
                """{"id":"90","name":"Odd","statusCategory":"SOMETHING_ELSE"}""",
                """{"id":"91","name":"Undefined","statusCategory":"UNDEFINED"}""",
                """{"id":"92","name":"Missing"}""",
                """{"id":"93","name":"Null","statusCategory":null}""",
                """{"id":"94","name":"Number","statusCategory":7}""",
                """{"id":"95","name":"Array","statusCategory":["DONE"]}""",
            ),
        )
        assertEquals(StatusCategory.TODO, refs.first { it.statusId == "1" }.category)
        assertEquals(StatusCategory.IN_PROGRESS, refs.first { it.statusId == "3" }.category)
        assertEquals(StatusCategory.DONE, refs.first { it.statusId == "10135" }.category)
        listOf("90", "91", "92", "93", "94", "95").forEach { id ->
            assertEquals(StatusCategory.UNKNOWN, refs.first { it.statusId == id }.category, "status $id")
        }
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

    @Test
    fun `normalizeIssue parses the system duedate field into due_at (epoch millis, start of day UTC)`() {
        val payload = """
            {
              "id": "50001",
              "key": "FOO-2",
              "fields": {
                "issuetype": {"name": "Task"},
                "project": {"key": "FOO"},
                "status": {"id": "1"},
                "created": "2026-01-01T00:00:00.000Z",
                "updated": "2026-01-02T00:00:00.000Z",
                "duedate": "2026-03-15"
              }
            }
        """.trimIndent()
        val input = JiraNormalizer.normalizeIssue(payload, emptyList(), emptyList(), noFieldIds, TombstoneKind.NONE)
        val expected = LocalDate.parse("2026-03-15").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, input.facts.dueAtMs)
    }

    @Test
    fun `customFieldsJson keeps only FILLED customfield_ values (drops null, empty array, empty object, empty string)`() {
        val payload = """
            {
              "id": "50002",
              "key": "FOO-3",
              "fields": {
                "issuetype": {"name": "Task"},
                "project": {"key": "FOO"},
                "status": {"id": "1"},
                "created": "2026-01-01T00:00:00.000Z",
                "updated": "2026-01-02T00:00:00.000Z",
                "customfield_10001": null,
                "customfield_10002": [],
                "customfield_10003": {},
                "customfield_10004": "",
                "customfield_10005": "filled",
                "customfield_10006": ["a"],
                "customfield_10007": {"id": "1"},
                "customfield_10008": 42
              }
            }
        """.trimIndent()
        val input = JiraNormalizer.normalizeIssue(payload, emptyList(), emptyList(), noFieldIds, TombstoneKind.NONE)
        val customFields = Json.parseToJsonElement(input.facts.customFieldsJson).jsonObject
        assertEquals(
            setOf("customfield_10005", "customfield_10006", "customfield_10007", "customfield_10008"),
            customFields.keys,
        )
    }

    @Test
    fun `hierarchyLevel prefers the issue's own fields_issuetype_hierarchyLevel over the ISSUE_TYPE reference fallback`() {
        val payloadWithOwnLevel = """
            {
              "id": "50003",
              "key": "FOO-4",
              "fields": {
                "issuetype": {"id": "10000", "name": "Epic", "hierarchyLevel": 1},
                "project": {"key": "FOO"},
                "status": {"id": "1"},
                "created": "2026-01-01T00:00:00.000Z",
                "updated": "2026-01-02T00:00:00.000Z"
              }
            }
        """.trimIndent()
        val fromOwn = JiraNormalizer.normalizeIssue(
            payloadWithOwnLevel, emptyList(), emptyList(), noFieldIds, TombstoneKind.NONE,
            // Deliberately wrong reference value — proves the issue's OWN hierarchyLevel wins.
            issueTypeHierarchy = mapOf("10000" to 99),
        )
        assertEquals(1, fromOwn.facts.hierarchyLevel)

        val payloadWithoutOwnLevel = """
            {
              "id": "50004",
              "key": "FOO-5",
              "fields": {
                "issuetype": {"id": "10000", "name": "Epic"},
                "project": {"key": "FOO"},
                "status": {"id": "1"},
                "created": "2026-01-01T00:00:00.000Z",
                "updated": "2026-01-02T00:00:00.000Z"
              }
            }
        """.trimIndent()
        val fromFallback = JiraNormalizer.normalizeIssue(
            payloadWithoutOwnLevel, emptyList(), emptyList(), noFieldIds, TombstoneKind.NONE,
            issueTypeHierarchy = mapOf("10000" to 1),
        )
        assertEquals(1, fromFallback.facts.hierarchyLevel)
    }

    @Test
    fun `worklog created and updated are null when Jira omits them, never falling back to started or created`() {
        val worklogs = listOf("""{"id":"900001","started":"2026-01-01T05:00:00.000Z","timeSpentSeconds":3600}""")
        val input = JiraNormalizer.normalizeIssue(minimalIssuePayload(), emptyList(), worklogs, noFieldIds, TombstoneKind.NONE)
        val worklog = input.worklogs.single()
        assertNull(worklog.createdAtMs)
        assertNull(worklog.updatedAtMs)
    }

    @Test
    fun `a parent move detected only via the discovered gh-epic-link field is tiled and stored with field_id=parent`() {
        val fieldIds = noFieldIds.copy(epicLinkFieldId = "customfield_10014")
        val changelogs = listOf(
            """{"id":"1","created":"2026-01-01T01:00:00.000Z","items":[
                {"field":"Epic Link","fieldId":"customfield_10014","from":"100","fromString":"FOO-1","to":"200","toString":"FOO-2"}
            ]}""",
        )
        val input = JiraNormalizer.normalizeIssue(minimalIssuePayload(), changelogs, emptyList(), fieldIds, TombstoneKind.NONE)

        assertEquals(1, input.parentEvents.size)
        assertEquals("200", input.parentEvents.single().toValueId)
        assertEquals("FOO-2", input.parentEvents.single().toValueText)

        val parentChange = input.fieldChanges.single { it.field == "Epic Link" }
        assertEquals("parent", parentChange.fieldId, "every matched parent item is normalized to field_id=parent")
    }

    @Test
    fun `a parent move detected only by the IssueParentAssociation display name (no fieldId) is tiled and stored with field_id=parent`() {
        val changelogs = listOf(
            """{"id":"1","created":"2026-01-01T01:00:00.000Z","items":[
                {"field":"IssueParentAssociation","from":"100","fromString":"FOO-1","to":"200","toString":"FOO-2"}
            ]}""",
        )
        val input = JiraNormalizer.normalizeIssue(minimalIssuePayload(), changelogs, emptyList(), noFieldIds, TombstoneKind.NONE)

        assertEquals(1, input.parentEvents.size)
        assertEquals("200", input.parentEvents.single().toValueId)

        val parentChange = input.fieldChanges.single { it.field == "IssueParentAssociation" }
        assertEquals("parent", parentChange.fieldId, "a name-only match must still be queryable via field_id=parent")
    }

    @Test
    fun `two parent spellings for the same move in one history collapse to a single event, preferring fieldId=parent`() {
        val fieldIds = noFieldIds.copy(epicLinkFieldId = "customfield_10014")
        val changelogs = listOf(
            """{"id":"1","created":"2026-01-01T01:00:00.000Z","items":[
                {"field":"Epic Link","fieldId":"customfield_10014","from":"100","fromString":"FOO-1","to":"200","toString":"FOO-2"},
                {"field":"Parent","fieldId":"parent","from":"100","fromString":"FOO-1","to":"200","toString":"FOO-2"}
            ]}""",
        )
        val input = JiraNormalizer.normalizeIssue(minimalIssuePayload(), changelogs, emptyList(), fieldIds, TombstoneKind.NONE)
        assertEquals(1, input.parentEvents.size, "one history must yield AT MOST one parent event, whatever spellings it carries")
        assertEquals("200", input.parentEvents.single().toValueId)

        // No fieldId=parent item present — the dedup-by-(from,to) fallback must still collapse to one.
        val changelogsNoParentId = listOf(
            """{"id":"2","created":"2026-01-01T02:00:00.000Z","items":[
                {"field":"Epic Link","fieldId":"customfield_10014","from":"200","fromString":"FOO-2","to":"300","toString":"FOO-3"},
                {"field":"IssueParentAssociation","from":"200","fromString":"FOO-2","to":"300","toString":"FOO-3"}
            ]}""",
        )
        val inputNoParentId =
            JiraNormalizer.normalizeIssue(minimalIssuePayload(), changelogsNoParentId, emptyList(), fieldIds, TombstoneKind.NONE)
        assertEquals(
            1, inputNoParentId.parentEvents.size,
            "two spellings with identical (from,to) and no fieldId=parent item must still collapse to one",
        )
    }

    @Test
    fun `a parent removal (to=null) is tiled as an event whose toValueId is null`() {
        val changelogs = listOf(
            """{"id":"1","created":"2026-01-01T01:00:00.000Z","items":[
                {"field":"Parent","fieldId":"parent","from":"100","fromString":"FOO-1","to":null,"toString":null}
            ]}""",
        )
        val input = JiraNormalizer.normalizeIssue(minimalIssuePayload(), changelogs, emptyList(), noFieldIds, TombstoneKind.NONE)
        assertEquals(1, input.parentEvents.size)
        assertNull(input.parentEvents.single().toValueId)
        assertEquals("100", input.parentEvents.single().fromValueId)
    }
}
