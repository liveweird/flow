package ch.nokillswit.jira

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.norm.CurrentFieldValue
import ch.nokillswit.norm.FieldChangeEvent
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.IssueNormalizationInput
import ch.nokillswit.norm.StatusChangeEvent
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorklogFact
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val NORMALIZER_JSON = Json { ignoreUnknownKeys = true }

private const val STATUS_FIELD_ID = "status"
private const val ASSIGNEE_FIELD_ID = "assignee"

/**
 * The `parent` field's changelog spelling (v0.3.0 M1 commit 2, `.claude/docs/domain-model.md`
 * "Gaps in `norm`"): the sample stub uses the system field id `parent` (Jira Cloud's current
 * shape, replacing Epic Link) — [PARENT_FIELD_NAMES] and [JiraFieldIds.epicLinkFieldId] are
 * defensive fallbacks for a real tenant still on the older Epic Link field or a differently-cased
 * changelog `field` display name; confirm all three against the real tenant
 * (`.claude/docs/jira-integration.md`).
 */
private const val PARENT_FIELD_ID = "parent"
private val PARENT_FIELD_NAMES = setOf("Parent", "IssueParentAssociation", "Epic Link")
private const val CUSTOM_FIELD_PREFIX = "customfield_"

/**
 * The issue-key change's `field_id` (a move to another project renames the key). Real Jira sends
 * the item NAME-ONLY — `{"field":"Key","fieldtype":"jira","from":null,"fromString":"OLD-1","to":null,"toString":"NEW-2"}`,
 * no `fieldId`, the old/new key in `fromString`/`toString` only — while the stub spells it
 * `fieldId: "issuekey"`; both normalize to [ISSUE_KEY_CHANGE_FIELD_ID]. The `field` match is the
 * English display name only (like the name-only parent match): a site localized to another language
 * would name it differently and its key moves would go untracked.
 */
private const val ISSUE_KEY_CHANGE_FIELD_ID = "issuekey"
private const val ISSUE_KEY_CHANGE_FIELD_NAME = "Key"
private const val ISSUE_KEY_CHANGE_FIELD_TYPE = "jira"

/**
 * The Jira-specific field ids the PROCESS step needs, discovered ONCE per run from the REFERENCE
 * stream's `FIELD` entities (v0.2.0 plan §8) — never hardcoded, since a real tenant assigns its own
 * `customfield_NNNNN` ids. Sprint/Rank/Team are matched by their UNIQUE `schema.custom` plugin key;
 * Story points and Flagged have no unique `schema.custom` of their own on a real tenant (Story
 * points is plain `schema.custom = ...:float`, shared with any other numeric custom field), so
 * those two are matched by NAME instead (case-insensitive).
 */
data class JiraFieldIds(
    val sprintFieldId: String?,
    val rankFieldId: String?,
    val teamFieldId: String?,
    val storyPointsFieldId: String?,
    val flaggedFieldId: String?,
    // The legacy Epic Link custom field (`gh-epic-link`), if the tenant still carries it
    // (v0.3.0 M1 commit 2) — see [PARENT_FIELD_NAMES].
    val epicLinkFieldId: String? = null,
)

/**
 * Jira-specific parsing for the PROCESS step (v0.2.0 plan §8) — turns raw Jira JSON
 * (`raw.jira_issues.payload`, `raw.jira_changelogs.payload`, `raw.jira_worklogs.payload`,
 * `raw.jira_entities.payload`) into the connector-agnostic shapes `norm/Normalization.kt`/
 * `norm/Tiling.kt` consume. Nothing here touches the database — `jira/JiraProcessStream.kt` is the
 * one caller, feeding it rows already read from `jira/JiraRawStore.kt`.
 */
object JiraNormalizer {

    /**
     * The full per-issue tiling input (v0.2.0 plan §8) — [issuePayload] is `raw.jira_issues.payload`
     * (`{id, key, fields}`), [changelogPayloads] is that issue's histories oldest-first (already
     * ordered by the caller, `jira/JiraRawStore.kt`'s `changelogPayloadsForIssue`), [worklogPayloads]
     * its non-tombstoned worklogs.
     */
    fun normalizeIssue(
        issuePayload: String,
        changelogPayloads: List<String>,
        worklogPayloads: List<String>,
        fieldIds: JiraFieldIds,
        tombstone: TombstoneKind,
        /** Resolved ONCE per PROCESS run by [issueTypeHierarchy] (v0.3.0 M1 commit 2) — empty means "not yet resolved". */
        issueTypeHierarchy: Map<String, Int> = emptyMap(),
        /** `raw.jira_issues.deleted_at`/`moved_out_at` VERBATIM — meaningless when [tombstone] is `NONE` (review fix). */
        tombstoneAtMs: Long? = null,
    ): IssueNormalizationInput {
        val issue = NORMALIZER_JSON.parseToJsonElement(issuePayload).jsonObject
        val fields = issue.getValue("fields").jsonObject
        val issueId = issue.getValue("id").jsonPrimitive.content.toLong()

        val issueType = fields.getValue("issuetype").jsonObject
        val project = fields.getValue("project").jsonObject
        // `assignee`/`resolution`/`parent` can all be present-but-JSON-null when unset (never
        // simply absent, on SOME tenants) — `.jsonObject` would throw on a literal JSON null, so
        // every object-shaped field goes through the null-filtering helper, never a plain
        // `?.jsonObject` cast.
        val assignee = fields["assignee"].orNullObject()
        val reporter = fields["reporter"].orNullObject()
        val priority = fields["priority"].orNullObject()
        val resolution = fields["resolution"].orNullObject()
        val team = fieldIds.teamFieldId?.let { fields[it] }.orNullObject()
        val parent = fields["parent"].orNullObject()
        val parentIssueId = parent?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val parentIssueKey = parent?.get("key")?.jsonPrimitive?.contentOrNull

        val currentSprints = fieldIds.sprintFieldId?.let { fields[it] }.orNullArray() ?: JsonArray(emptyList())
        val currentSprintIds = currentSprints.map { it.jsonObject.getValue("id").jsonPrimitive.content.toLong() }
        val currentSprintText = currentSprints.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.jsonObject.getValue("name").jsonPrimitive.content }
        val currentFlagged = fieldIds.flaggedFieldId?.let { fields[it] }.orNullArray()?.isNotEmpty() ?: false

        val worklogs = worklogPayloads.map { payload ->
            val worklog = NORMALIZER_JSON.parseToJsonElement(payload).jsonObject
            val startedAtMs = parseJiraInstantEpochMillis(worklog.getValue("started").jsonPrimitive.content)
            // Missing means missing — NEVER falls back to `startedAtMs`/`createdAtMs` (a review
            // fix): a genuinely absent `created`/`updated` is unknown data, not "no skew".
            val createdAtMs = worklog["created"]?.jsonPrimitive?.contentOrNull?.let { parseJiraInstantEpochMillis(it) }
            val updatedAtMs = worklog["updated"]?.jsonPrimitive?.contentOrNull?.let { parseJiraInstantEpochMillis(it) }
            WorklogFact(
                worklogId = worklog.getValue("id").jsonPrimitive.content.toLong(),
                authorAccountId = worklog["author"].orNullObject()?.get("accountId")?.jsonPrimitive?.contentOrNull,
                startedAtMs = startedAtMs,
                timeSpentSeconds = worklog.getValue("timeSpentSeconds").jsonPrimitive.content.toLong(),
                createdAtMs = createdAtMs,
                updatedAtMs = updatedAtMs,
            )
        }

        val facts = WorkItemFacts(
            issueKey = issue.getValue("key").jsonPrimitive.content,
            projectKey = project.getValue("key").jsonPrimitive.content,
            issueType = issueType.getValue("name").jsonPrimitive.content,
            isSubtask = issueType["subtask"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            parentIssueId = parentIssueId,
            summary = fields["summary"]?.jsonPrimitive?.contentOrNull,
            currentStatusId = fields.getValue("status").jsonObject.getValue("id").jsonPrimitive.content,
            resolution = resolution?.get("name")?.jsonPrimitive?.contentOrNull,
            priority = priority?.get("name")?.jsonPrimitive?.contentOrNull,
            assigneeAccountId = assignee?.get("accountId")?.jsonPrimitive?.contentOrNull,
            reporterAccountId = reporter?.get("accountId")?.jsonPrimitive?.contentOrNull,
            createdAtMs = parseJiraInstantEpochMillis(fields.getValue("created").jsonPrimitive.content),
            updatedAtMs = parseJiraInstantEpochMillis(fields.getValue("updated").jsonPrimitive.content),
            resolvedAtMs = fields["resolutiondate"]?.jsonPrimitive?.contentOrNull?.let { parseJiraInstantEpochMillis(it) },
            storyPoints = fieldIds.storyPointsFieldId?.let { fields[it]?.jsonPrimitive?.doubleOrNull },
            originalEstimateSeconds = fields["timetracking"].orNullObject()?.get("originalEstimateSeconds")?.jsonPrimitive?.longOrNull,
            // Computed from the issue's OWN worklogs, never `timetracking.timeSpentSeconds` (which a
            // real tenant may leave stale relative to the worklog feed this stream already trusts).
            timeSpentSeconds = worklogs.sumOf { it.timeSpentSeconds },
            labels = fields["labels"].orNullArray()?.map { it.jsonPrimitive.content } ?: emptyList(),
            components = fields["components"].orNullArray()?.map { it.jsonObject.getValue("name").jsonPrimitive.content } ?: emptyList(),
            fixVersions = fields["fixVersions"].orNullArray()?.map { it.jsonObject.getValue("name").jsonPrimitive.content } ?: emptyList(),
            teamValueJson = team?.toString(),
            rank = fieldIds.rankFieldId?.let { fields[it]?.jsonPrimitive?.contentOrNull },
            // Prefer the issue's OWN `fields.issuetype.hierarchyLevel` when a tenant does return it
            // (the stub does not; a real tenant sometimes does) — fall back to the REFERENCE
            // stream's ISSUE_TYPE entities only when the issue document itself is silent.
            hierarchyLevel = issueType["hierarchyLevel"]?.jsonPrimitive?.intOrNull
                ?: issueType["id"]?.jsonPrimitive?.contentOrNull?.let { issueTypeHierarchy[it] },
            dueAtMs = fields["duedate"]?.jsonPrimitive?.contentOrNull?.let { parseDateMs(it) },
            customFieldsJson = customFieldsJson(fields),
            tombstone = tombstone,
            tombstoneAtMs = tombstoneAtMs,
        )

        return IssueNormalizationInput(
            issueId = issueId,
            facts = facts,
            statusEvents = changelogPayloads.flatMap { statusEventsFromHistory(it) },
            assigneeEvents = changelogPayloads.flatMap { fieldEventsFromHistory(it, ASSIGNEE_FIELD_ID) },
            sprintEvents = fieldIds.sprintFieldId
                ?.let { id -> changelogPayloads.flatMap { sprintEventsFromHistory(it, id) } } ?: emptyList(),
            parentEvents = changelogPayloads.flatMap { parentEventsFromHistory(it, fieldIds) },
            currentParent = CurrentFieldValue(parentIssueId?.toString(), parentIssueKey),
            flaggedEvents = fieldIds.flaggedFieldId
                ?.let { id -> changelogPayloads.flatMap { flaggedEventsFromHistory(it, id) } } ?: emptyList(),
            currentAssignee = CurrentFieldValue(
                assignee?.get("accountId")?.jsonPrimitive?.contentOrNull,
                assignee?.get("displayName")?.jsonPrimitive?.contentOrNull,
            ),
            currentSprintIds = currentSprintIds,
            currentSprintText = currentSprintText,
            currentFlagged = currentFlagged,
            fieldChanges = changelogPayloads.flatMap { fieldChangesFromHistory(it, fieldIds) },
            worklogs = worklogs,
        )
    }

    private fun historyItems(historyPayload: String): Pair<Long, List<JsonObject>> {
        val history = NORMALIZER_JSON.parseToJsonElement(historyPayload).jsonObject
        val atMs = parseJiraInstantEpochMillis(history.getValue("created").jsonPrimitive.content)
        return atMs to history.getValue("items").jsonArray.map { it.jsonObject }
    }

    private fun statusEventsFromHistory(historyPayload: String): List<StatusChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == STATUS_FIELD_ID }
            .map { StatusChangeEvent(atMs, it.statusSideId("from", "fromString"), it.statusSideId("to", "toString")) }
    }

    private fun fieldEventsFromHistory(historyPayload: String, fieldId: String): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == fieldId }
            .map {
                FieldChangeEvent(
                    atMs,
                    it["from"]?.jsonPrimitive?.contentOrNull,
                    it["fromString"]?.jsonPrimitive?.contentOrNull,
                    it["to"]?.jsonPrimitive?.contentOrNull,
                    it["toString"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }

    /**
     * SPRINT's `from`/`to` are comma-joined id lists (plan §8's "multi-valued sprint field" rule) —
     * `value_id` is the LAST id, `value_text` is Jira's own comma-joined `toString`/`fromString`.
     */
    private fun sprintEventsFromHistory(historyPayload: String, sprintFieldId: String): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == sprintFieldId }
            .map { item ->
                val fromIds = item["from"]?.jsonPrimitive?.contentOrNull?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val toIds = item["to"]?.jsonPrimitive?.contentOrNull?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                FieldChangeEvent(
                    atMs,
                    fromIds.lastOrNull(),
                    item["fromString"]?.jsonPrimitive?.contentOrNull,
                    toIds.lastOrNull(),
                    item["toString"]?.jsonPrimitive?.contentOrNull,
                )
            }
    }

    private fun flaggedEventsFromHistory(historyPayload: String, flaggedFieldId: String): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        return items.filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == flaggedFieldId }
            .map { item ->
                val isFlagged = item["to"]?.jsonPrimitive?.contentOrNull != null
                val wasFlagged = item["from"]?.jsonPrimitive?.contentOrNull != null
                FieldChangeEvent(atMs, wasFlagged.toString(), null, isFlagged.toString(), null)
            }
    }

    /**
     * A parent-move changelog item (v0.3.0 M1 commit 2): [PARENT_FIELD_ID] (the sample stub's own
     * spelling), [fieldIds]' discovered legacy Epic Link custom field id, or one of
     * [PARENT_FIELD_NAMES]'s display-name fallbacks — confirm all three against a real tenant
     * (`.claude/docs/jira-integration.md`).
     */
    private fun isParentChangeItem(item: JsonObject, fieldIds: JiraFieldIds): Boolean {
        val fieldId = item["fieldId"]?.jsonPrimitive?.contentOrNull
        val fieldName = item["field"]?.jsonPrimitive?.contentOrNull
        return fieldId == PARENT_FIELD_ID ||
            (fieldIds.epicLinkFieldId != null && fieldId == fieldIds.epicLinkFieldId) ||
            fieldName in PARENT_FIELD_NAMES
    }

    /**
     * PARENT tiling input (v0.3.0 M1 commit 2) — `value_id` is the target epic's issue id,
     * `value_text` its key; a removal (`to` absent) tiles an open interval with `value_id = null`.
     * `value_id`/`value_text` name the parent issue whatever its hierarchy level — a sub-task's
     * parent is its story/task (level 0), not necessarily an epic; "epic membership" specifically
     * is a metrics-layer filter over PARENT rows whose target has `hierarchy_level = 1`, not
     * something PROCESS decides here.
     *
     * **AT MOST ONE event per history.** A single changelog history can carry MORE than one
     * matching item for the SAME move — Jira's Epic Link → Parent field migration period can emit
     * both an `IssueParentAssociation`/`Epic Link` item and a `parent` item in ONE history — so
     * this collapses to one event: prefer the `fieldId == "parent"` item when present, else the
     * first item among the matches after deduping identical (from, to) pairs.
     */
    private fun parentEventsFromHistory(historyPayload: String, fieldIds: JiraFieldIds): List<FieldChangeEvent> {
        val (atMs, items) = historyItems(historyPayload)
        val matches = items.filter { isParentChangeItem(it, fieldIds) }
        val chosen = matches.firstOrNull { it["fieldId"]?.jsonPrimitive?.contentOrNull == PARENT_FIELD_ID }
            ?: matches.distinctBy { it["from"]?.jsonPrimitive?.contentOrNull to it["to"]?.jsonPrimitive?.contentOrNull }.firstOrNull()
            ?: return emptyList()
        return listOf(
            FieldChangeEvent(
                atMs,
                chosen["from"]?.jsonPrimitive?.contentOrNull,
                chosen["fromString"]?.jsonPrimitive?.contentOrNull,
                chosen["to"]?.jsonPrimitive?.contentOrNull,
                chosen["toString"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }

    /**
     * An issue-key change item: `fieldId == "issuekey"`, or the real, name-only `field == "Key"` with no `fieldId` whose `fieldtype` is
     * `"jira"` (a system field) or absent. A present `fieldtype` other than `jira` (a custom field that happens to be NAMED "Key")
     * is not a key change. An absent `fieldtype` is accepted: Jira always sends it, so an item without one is a trimmed payload of the
     * system item (and the cost of a missed move, silent mis-attribution, outweighs the odds of a fieldtype-less custom "Key" field).
     */
    private fun isIssueKeyChangeItem(item: JsonObject): Boolean {
        val fieldId = item["fieldId"]?.jsonPrimitive?.contentOrNull
        if (fieldId == ISSUE_KEY_CHANGE_FIELD_ID) return true
        val fieldType = item["fieldtype"]?.jsonPrimitive?.contentOrNull
        return fieldId == null && item["field"]?.jsonPrimitive?.contentOrNull == ISSUE_KEY_CHANGE_FIELD_NAME &&
            (fieldType == null || fieldType == ISSUE_KEY_CHANGE_FIELD_TYPE)
    }

    /**
     * Every TRACKED changelog item — status, assignee, Sprint, Flagged, Rank, priority, resolution,
     * issuetype, project, Key, story points, due date, EVERY `customfield_*` item and every parent
     * move (v0.3.0 M1 commit 2 widens plan §4's original list — never filter which custom fields
     * are captured) — kept verbatim, in order, each row carrying the changelog item's own
     * [FieldChangeFact.fieldId] (the metrics layer's per-field replay key; `field` is a display name
     * only, unreliable across a field rename).
     */
    private fun fieldChangesFromHistory(historyPayload: String, fieldIds: JiraFieldIds): List<FieldChangeFact> {
        val (atMs, items) = historyItems(historyPayload)
        val explicitTrackedFieldIds = setOfNotNull(
            STATUS_FIELD_ID, ASSIGNEE_FIELD_ID, "priority", "resolution", "issuetype", "project", ISSUE_KEY_CHANGE_FIELD_ID, "duedate",
            fieldIds.sprintFieldId, fieldIds.flaggedFieldId, fieldIds.rankFieldId, fieldIds.storyPointsFieldId,
        )
        // One key move spelled both ways in one history (fieldId-bearing and name-only) is ONE change: keep the first of each
        // (fromString, toString) key item. Only the texts are compared: the real name-only item carries the keys there with null
        // `from`/`to`, while a fieldId-bearing twin may fill `from`/`to` as well, so comparing those would keep both.
        val seenKeyChanges = mutableSetOf<Pair<String?, String?>>()
        return items.filter { item ->
            val fieldId = item["fieldId"]?.jsonPrimitive?.contentOrNull
            if (isIssueKeyChangeItem(item)) {
                seenKeyChanges.add(item["fromString"]?.jsonPrimitive?.contentOrNull to item["toString"]?.jsonPrimitive?.contentOrNull)
            } else {
                fieldId in explicitTrackedFieldIds || fieldId?.startsWith(CUSTOM_FIELD_PREFIX) == true || isParentChangeItem(item, fieldIds)
            }
        }.map { item ->
            val isParentItem = isParentChangeItem(item, fieldIds)
            FieldChangeFact(
                field = item.getValue("field").jsonPrimitive.content,
                atMs = atMs,
                fromValue = item["from"]?.jsonPrimitive?.contentOrNull,
                fromText = item["fromString"]?.jsonPrimitive?.contentOrNull,
                toValue = item["to"]?.jsonPrimitive?.contentOrNull,
                toText = item["toString"]?.jsonPrimitive?.contentOrNull,
                // Normalized to "parent" for EVERY matched parent item, regardless of which of the
                // three spellings actually matched (`fieldId` is null on a real, name-only
                // `IssueParentAssociation` item) — otherwise a name-only match is invisible to
                // `WorkItemStore.fieldChangesByFieldIds(connectionId, listOf("parent"))`.
                // The name-only issue-key item is normalized the same way, to "issuekey".
                fieldId = when {
                    isParentItem -> PARENT_FIELD_ID
                    isIssueKeyChangeItem(item) -> ISSUE_KEY_CHANGE_FIELD_ID
                    else -> item["fieldId"]?.jsonPrimitive?.contentOrNull
                },
            )
        }
    }

    /** `YYYY-MM-DD` (Jira's plain date fields, e.g. `duedate`) at start of day UTC — epoch millis (v0.3.0 M1 commit 2). */
    private fun parseDateMs(dateStr: String): Long = LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * Every FILLED `customfield_*` current value on [fields], canonicalized and keyed by field id
     * (v0.3.0 M1 commit 2, `.claude/docs/domain-model.md` "Gaps in `norm`") — never filtered
     * by WHICH field, only by whether it carries anything: a literal JSON `null` and an empty
     * array/object/string are dropped (Jira represents "no value" as any of the three, depending on
     * the field's own schema type) so that "the key is present" reliably means "the value is
     * filled" for every reader, never a false positive on an empty shell. The metrics layer picks
     * the configured fields at DERIVE time, so PROCESS must keep every FILLED one, not filter which.
     */
    private fun customFieldsJson(fields: JsonObject): String {
        val customFields = fields.entries
            .filter { (key, value) -> key.startsWith(CUSTOM_FIELD_PREFIX) && value != JsonNull && !isBlankCustomFieldValue(value) }
            .associate { (key, value) -> key to value }
        return canonicalJson(JsonObject(customFields))
    }

    /** "Present" means "filled" for [customFieldsJson] — an empty array/object/string carries no information. */
    private fun isBlankCustomFieldValue(value: JsonElement): Boolean = when (value) {
        is JsonArray -> value.isEmpty()
        is JsonObject -> value.isEmpty()
        is JsonPrimitive -> value.isString && value.content.isEmpty()
    }
}
