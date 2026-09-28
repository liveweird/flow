# Metrics layer (v0.3.0)

This doc grows commit by commit alongside the v0.3.0 metrics-layer work (the phase-3 plan,
`.claude/docs/domain-model.md`). It starts with the configuration model — the ADMIN-curated inputs
every later DERIVE run reads under ONE recorded revision. The derivation algorithm, the star schema
mechanics and the report API each add their own section as they land (see the plan's commit
sequence — commits 7-19).

## Configuration model

Two layers of configuration exist, both change-tracked through the SAME shared
`metrics.settings.config_revision` counter (`metrics/MetricsConfigService.kt`):

- **Global settings** (`GET/PUT /api/v1/metrics-settings`, v0.3.0 M1 commit 3) — the ONE
  `metrics.settings` singleton: the calendar (time zone, weekend days, holidays), `hoursPerDay`,
  commitment grace, minimum sample size, the aging-WIP window/percentiles, the backlog-in-sprints
  window and the epic-drift threshold. See `MetricsSettingsResponse`/`Request` in
  `metrics/MetricsSettings.kt`.
- **Per-connection configuration** (`GET/PUT /api/v1/data-sources/{id}/metrics-config`, v0.3.0 M1
  commit 4) — everything that is specific to one Jira connection's own data shape: status → stage,
  the estimate/epic-date/work-category field choices, project → domain, board → team, activity
  type and work-category maps, blocked statuses and per-sprint capacity overrides. This section
  covers that resource.

Both `metrics/TeamMembership*.kt`'s dated Jira-user team membership (D1, v0.3.0 M1 commit 3, its
own doc coverage in `.claude/docs/domain-model.md` "Configuration") and the per-connection resource
below bump the SAME shared revision — a DERIVE run (arriving with commit 7) always reads the
`norm` facts under ONE recorded `config_revision`, so every derived number names exactly which
configuration produced it (D-invariant 12, "every live number is reproducible from `norm` + one
configuration revision").

### Per-connection metrics configuration (`GET/PUT /api/v1/data-sources/{id}/metrics-config`)

**ONE composite resource, not eight paired endpoints** (API-RES-004, the features-PUT idiom
`metrics/MetricsSettings.kt` already established): a PUT is a full replace over all eight
per-connection `metrics.*` config tables (`.claude/docs/persistence.md` "V15") in ONE transaction —
`metrics/MetricsConfigService.kt`'s `replaceConfig`. The composite shape
(`metrics/DataSourceMetricsConfig.kt`):

```
DataSourceMetricsConfig {
  configured: Boolean
  statusStages: [{ statusId, stage }]              // metrics.status_stage_map, domain_key = ''
  fields: { estimateTask, estimateEpic, epicStart, epicDue, workCategory }  // metrics.field_config
  domains: [{ projectKey, domainKey, domainName }]  // metrics.domain_map
  boards: [{ boardId, teamId }]                     // metrics.board_team_map
  activityTypes: [{ issueType, activityType }]      // metrics.activity_type_map
  workCategories: [{ valueId, valueName, category }]// metrics.work_category_map
  blockedStatuses: [statusId]                       // metrics.blocked_statuses
  sprintCapacities: [{ sprintId, capacityMd }]       // metrics.team_sprint_capacity
}
```

**`GET` on an unconfigured connection returns the computed DEFAULTS**, `configured: false` — never
a `404` and never an empty shell an admin has to fill in from scratch before reports work at all
(team attribution excepted, since a board → team mapping can only ever be an admin's own choice):

| Field | Default |
|---|---|
| `statusStages` | seeded from each status's Jira category (`norm.statuses`) — `new → NOT_STARTED`, `indeterminate → IN_PROGRESS`, `done → DONE`; a status whose category is `UNKNOWN` is left OUT of this list (flagged, never guessed — `.claude/docs/domain-model.md`'s "an unmapped status is flagged, never guessed"). The `metrics-config/options` endpoint's own `statuses` list still names every status Jira reports, mapped or not, so an admin can see the gap. |
| `fields.estimateTask` / `fields.estimateEpic` | the connection's stored data profile's `STORY_POINTS`-detected custom field id (`source_connections.profile.customFields`, `jira/JiraProfile.kt`) — both roles share the one detected field |
| `fields.epicStart` | the profile's custom field whose name contains "start date" (case-insensitive); if none, the field whose name contains "target start" (Jira Plans' own start-date field, for a company-managed-project tenant using Plans dates instead of a team-managed "Start date" custom field); `null` if neither was detected |
| `fields.epicDue` | `"duedate"` — Jira's plain system field, always a valid choice, never subject to profile detection |
| `fields.workCategory` | `null` — must be chosen; there is no sensible guess for which field carries it |
| `domains` | 1:1 — one row per project key observed in `norm.work_items` (LIVE rows only), `domainKey`/`domainName` both set to the project key itself |
| `boards` | empty — no board → team mapping exists until an admin makes one |
| `activityTypes` | 1:1 — one row per issue type observed in `norm.work_items` (D6: activity types are standard issue types) |
| `workCategories` | empty |
| `blockedStatuses` | empty — only the Flagged field counts as "blocked" until an admin adds statuses here |
| `sprintCapacities` | empty — DERIVE (commit 8) falls back to a computed default (A3: members × working days) when no row exists for a sprint |

**Validation — the client-supplied-FK idiom, `400` never `404`** (there is no path id inside the
body): every id `DataSourceMetricsConfigRequest` carries is checked against exactly this
connection's own reference data (`metrics/DataSourceMetricsConfig.kt`'s `MetricsConfigReferenceData`,
computed by `MetricsConfigService.replaceConfig`/`.referenceData`):

- `statusStages[].statusId` and `blockedStatuses[]` → `norm.statuses` (`WorkItemStore.allStatusRefs`).
- Every non-null field id (`fields.*`) → the stored data profile's `customFields` ids, plus the one
  hardcoded system field `"duedate"`.
- `domains[].projectKey` → distinct project keys observed in `norm.work_items`
  (`WorkItemStore.distinctProjectKeys`) — NOT the connection's own configured `projectKeys`
  setting, since the reference is "what Jira actually reports", not "what scope was requested".
- `boards[].boardId` → `norm.boards` (`WorkItemStore.allBoardRefs`); `boards[].teamId` → the ACTIVE
  teams registry (`teams/TeamService.Teams`, cross-feature read, listed in `.claude/docs/persistence.md`).
  D10 ("one board per team") is enforced the OTHER direction at the database:
  `uq_metrics_board_team_map_team_id` (a `23505` → `409`, already named in
  `plugins/ErrorHandling.kt`'s `UNIQUE_CONSTRAINT_DETAILS`).
- `activityTypes[].issueType` → distinct issue types observed in `norm.work_items`
  (`WorkItemStore.distinctIssueTypes`).
- `workCategories[].valueId` → the distinct values `norm.work_items.custom_fields` carries for
  the REQUEST's OWN `fields.workCategory` field id (`WorkItemStore.distinctCustomFieldValues`) —
  never validated against a field the request doesn't itself choose; submitting `workCategories`
  while `fields.workCategory` is `null` is itself a `400`.
- `sprintCapacities[].sprintId` → `norm.sprints` (`WorkItemStore.allSprintRefs`); `capacityMd` must
  be `>= 0`.

**A repeated key WITHIN one array of the SAME request** (two `statusStages` entries naming the same
`statusId`, two `domains` entries naming the same `projectKey`, and so on for every list-shaped
field except `boards[].teamId`) is a `400`, checked BEFORE the reference-data lookups
(`requireNoDuplicateKeys`, `metrics/DataSourceMetricsConfig.kt`) — a malformed REQUEST, not a
conflict with something else already stored. `boards[].teamId` is the deliberate exception: two
boards claiming the same team, even within one request, stays D10's "one board per team" `409` at
the database (above), never pre-empted by this check.

**The no-op-PUT precedent** (the features-PUT idiom, `MetricsSettingsService.replace`'s own rule):
a PUT byte-for-byte identical to what is already stored writes nothing, bumps no revision, and the
route emits no `metrics_config.updated` audit line. `MetricsConfigService.readStoredConfig` returns
`null` when NOTHING is stored across all eight tables — that `null` is what flips `configured` to
`false` and is also the "nothing to compare against, this PUT is a real first write" signal. The
comparison is order-INSENSITIVE (`DataSourceMetricsConfigRequest.canonicalized()` sorts every list
by its own natural key before comparing): a plain `SELECT` with no `ORDER BY` gives no row-order
guarantee, and a re-PUT of the SAME set in a different array order must stay a no-op — `readStoredConfig`
itself still applies a deterministic `ORDER BY` per table so a `GET` response is stable across
repeated reads, but that ordering is a presentation nicety, not what the no-op check relies on.

**`GET /api/v1/data-sources/{id}/metrics-config/options`** — the reference data the metrics-config
editor picks from (`DataSourceMetricsConfigOptions`): every status (with its Jira category, mapped
or not), the profile-detected custom fields (with their `detectedRole` —
`SPRINT`/`RANK`/`TEAM`/`STORY_POINTS`/`FLAGGED`/`OTHER`, `jira/JiraProfile.kt`'s own detection,
reused rather than reimplemented), distinct project keys, boards, distinct issue types, and sprints
(all boards' sprints together — a future UI groups them per board client-side). `workCategoryValues`
is populated ONLY when the caller supplies `?workCategoryField=<fieldId>`, from that field's own
distinct observed values across the connection's LIVE work items
(`WorkItemStore.distinctCustomFieldValues`) — a Jira select-field value's shape (`{id, value}`, a
bare string, or an array of either) is parsed defensively, never cast blindly, since the caller may
name ANY custom field, not necessarily a `select`. The result is capped at 200 distinct values
(alphabetically by id) — `workCategoryValuesTruncated: true` means more exist, a backstop against a
poorly-chosen high-cardinality field (e.g. a free-text field picked as `WORK_CATEGORY` by mistake)
growing the response unbounded; `PUT`'s own id validation against this same reader is unaffected by
the cap (a legitimate value beyond the first 200 is still accepted, since the config table itself
has no such limit — only the OPTIONS listing is capped).

### PURGE and the metrics config

A soft-deleted connection's PURGE job (`.claude/docs/ingestion.md` "Job orders") drains its
per-connection `metrics.*` config rows through a GENERIC, connector-agnostic worker step —
`MetricsConfigService.purgeConnectionConfig`, called from `ingest/IngestWorker.kt`'s `runJob` right
after the connector's own `purgeSteps` finish. This is deliberately NOT wired into
`JiraConnector.purgeSteps`: the eight tables it drains hold no Jira-specific shape at all (a future
GitLab connector's connection would purge through the exact same call), so the cleanup lives beside
the OTHER connector-agnostic PURGE work the worker itself already owns, not duplicated per
connector kind.

## Not yet ported / not yet written

The derivation algorithm (`DeriveKernels`/`MetricsDeriver`, the `DERIVE` job kind, V16's star
schema), the working-calendar math, the report API and the SPA pages all arrive with their own
commits (7 onward) and their own sections here.

**Membership history is permanent (by design).** Deleting a team closes its members' open
memberships at that moment (so they can join another team from then on), but the history before
that stays — `metrics.team_membership`'s EXCLUDE is per Jira account across all teams, so a new
membership can never be backdated over a period the person already spent in another team, deleted
or not. To correct a mistaken membership, edit or delete that row; don't delete the team first.
