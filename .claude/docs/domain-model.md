# Domain model (phase 3)

This doc defines what Flow's numbers MEAN: the entities, how they map to Jira, the three
measurement dimensions, the configuration they depend on, the analytical (`metrics`) model, the
reports it serves, its invariants, and how known data imperfections are handled. It is the
contract the metrics layer and every dashboard are built against. **Status: agreed 2026-09-27
(D1–D16), validated against the target reports; amended 2026-09-28 (A17–A21, see "Amendments"
below); being implemented in v0.3.0** (`.claude/docs/metrics.md`). The per-measure operational
contract — each report number's grain, time anchor, attribution, estimate snapshot, missing-data
rule, frozen/live source and the test that pins it — is `.claude/docs/measures.md`.

**Stance.** Facts stay in `norm.*` (`.claude/docs/ingestion.md` "Normalized layer" — what
happened, never what it means); meaning lives in the `metrics` layer above it, which is rebuilt
entirely from `norm` + configuration, never from a Jira call. Everything that can change over time
is **effective-dated**, and reports default to **as-was**: a user who changes team keeps their past
work with the old team.

## Entities

```
            DOMAIN ◄──────────────── EPIC ◄──────────── TASK ──────────► SPRINT ◄── TEAM (via board)
   (Jira space/project)    belongs to   (0..1 at a time)   (0..n over time)
              ▲                                        │   │ assigned to (0..1 at a time)
              │ task's own space                       │   ▼
              └────────────────────────────────────────┘  USER ──── member of (≤1 at a time) ──► TEAM ──► UNIT
                                                           │
                                               WORKLOG ────┘ author; logged on a TASK
```

The primary entities are **DOMAIN**, **TASK** and **USER**; nearly every question is about one of
them or a combination.

| Entity | Meaning | Jira source | Over time |
|---|---|---|---|
| **DOMAIN** | how work is grouped around topics/systems | a Jira **space** (Atlassian's 2025 name for a project), `project.key`; a configurable project → domain map, 1:1 by default | a task *moving* between spaces is history (`project` changelog items) |
| **UNIT** | the whole organization Flow reports on | all teams (+ `UNASSIGNED`) | — |
| **TEAM** | a group of users | Flow-owned (D1) | effective-dated membership |
| **USER** | a Jira account — not a Flow login | `accountId` (`norm.people`); optionally linked to a Flow login by email | ≤1 team at a time |
| **EPIC** | a longer body of work with its own estimate and start/due dates, planned in Jira Plans | an issue at hierarchy level 1 | its domain = its space; dates and estimate can change |
| **TASK** | every other issue, whatever its issue type | level-0 issues; sub-tasks roll up into their parent (D2) | epic, assignee, sprint(s), status, estimate — all intervals |
| **SPRINT** | the time-box work is planned into and delivered in | agile sprint (`originBoardId`, start/end/complete dates, state) | a task may pass through several (carry-over) |
| **WORKLOG** | time actually spent | `/worklog` (author, `started`, seconds) | edits and deletions are tracked |

Relationship rules:

- A task belongs to **at most one epic** at any instant; an epic belongs to **exactly one domain**
  (its space).
- A user belongs to **at most one team** at any instant; the unit is every team.
- A task is **a user's task** while that user is its **assignee** (the assignee intervals).
- A sprint is a **team's sprint** through its board (board → team configuration).

Two slicing attributes apply to every task-level fact:

- **Activity type** (Development, Manual Testing, Test Automation, Refactoring, …) — the task's
  **issue type** through a configurable issue type → activity type map (1:1 by default); the
  *current* issue type (issue-type changes are rare, and `norm` already keeps them if that ever
  matters). Activity types are standard issue types, one per task (D6).
- **Work category** (Product Development, Maintenance, Cost of Poor Quality, …) — the value of a
  configurable custom field through a value → category map (1:1 by default): the **task's own
  value, else its epic's** (D8), classified as-is (the current value, under the current
  map — see "Reading the numbers"); otherwise `(uncategorized)`.

## The three dimensions

Everything is measured in **man-days (MD)**. Story points convert **1 SP = 1 MD**; worklog seconds
convert by `hoursPerDay` (a manual setting in v0.3.0, default 8h — reading Jira's own
time-tracking setting is a backlog item).

**Estimate snapshots.** Every task and epic keeps its estimate at four moments — at
**commitment** (per sprint), at **start** (`started_at`), at **done** (`done_at`) and **current** —
plus the number of estimate changes after start. An item with no estimate at start that gains one
later is **estimated late**: counted separately, never as a +∞% change. An estimate of **0 SP
counts as unestimated**. A task with no estimate of its own but estimated sub-tasks takes the sum
of its sub-tasks' estimates, marked `estimate_source = SUBTASKS` (the mirror of the epic fallback
below).

**Plan — PV (planned value).**

- *Sprint:* the **committed scope** is the tasks in the sprint at its start (plus a configurable
  grace period), at their estimate as of commitment. Scope added mid-sprint is **added scope**,
  scope taken out is **removed scope**; the **final scope** is what is in the sprint when it is
  completed.
- *Epic:* the **budget** is the epic's **own estimate** (D4, revised), baselined when its dates
  are set; when an epic has no estimate of its own, the budget falls back to the sum of its child
  estimates, marked `budget_source = CHILDREN`. It is spread over the working days between the
  epic's start and due dates into a cumulative PV curve PV(t); later budget or date changes create
  a new baseline, and the drift is reported rather than overwriting the original plan.
- *Capacity:* team × sprint capacity in MD is configured in Flow (Jira exposes no reliable capacity
  API). **Load** = committed scope ÷ capacity.

**Delivery — EV (earned value).** From the configured status stages, for tasks and epics alike:

- `started_at` = the item's **first** entry into an IN_PROGRESS status.
- `done_at` = its **last** entry into a DONE status that it never left afterwards. A reopened item
  is not delivered until it is finally done again; its reopen count is kept.
- EV(t) = the sum of estimates of items with `done_at` ≤ t.
- **Cycle time** = `done_at − started_at`, **lead time** = `done_at − created_at`, both stored as
  elapsed time **and in working days** (the configured calendar and time zone).
- An epic's lifecycle comes from **its own status** (D11); where it disagrees with its children,
  the epic is flagged, never re-dated.
- **Blocked time** = time an item spent Flagged or in a configured **blocked status** (e.g.
  Blocked, Waiting) between its `started_at` and its `done_at` (or now), in working days — per
  item, and as a share of its cycle time.
- **Flow efficiency** = active time ÷ cycle time, where active time is the time in IN_PROGRESS
  stages inside the cycle minus blocked time, and wait time is the rest of the cycle (A18).
- **Age** of an IN_PROGRESS item = working days since `started_at`, compared with its team's
  cycle-time percentiles (aging WIP).

**Cost — AC (actual cost).** AC(t) = the sum of worklog MD logged up to t, for any scope. A task's
`actual_md` is its own worklogs plus its sub-tasks'; an epic's is every child's plus any logged on
the epic itself. Logging time is expected for all work (D14), so actual cost is treated as
trustworthy, and a DONE task with no worklogs is a data-quality finding rather than a zero-cost
task.

**Derived:** SV = EV − PV, SPI = EV / PV, CV = EV − AC, CPI = EV / AC, and the per-report measures
below.

## Glossary

Flow uses the user's vocabulary, which differs slightly from common Scrum usage:

- **Velocity** — how much a team *plans* into a sprint: **initial velocity** = committed scope at
  sprint start; **final velocity** = the final scope at sprint completion. (Scrum usually means
  "delivered" by velocity — in Flow that is throughput.)
- **Throughput** — how much a team *delivers* in a sprint: items done inside the sprint window
  while in the sprint.
- **Commitment** — the sprint's scope at start + grace that is still in the sprint at completion;
  scope taken out is reported as **removed**, beside it and in no other bucket (A17).
- **Carry-over** — scope in the sprint at completion (committed or added) that is not done and
  appears in the team's next sprint; **dropped** scope is not done and in no later sprint. So a
  sprint's final scope = delivered + carried over + dropped, always, and = committed + added in items
  (in SP too, unless an item was re-estimated between its commitment and the sprint's close) (A17). A
  task removed after being done counts as removed, not delivered. A carried-over task counts in the velocity
  of every sprint it was committed to, but in throughput only once — in the sprint where it was
  done (Jira's own convention).
- **Backlog in sprints** — estimated backlog SP ÷ the team's mean delivered SP over its last N
  sprints: how far ahead the backlog reaches.
- **Estimated backlog** — tasks ready to be picked up for sprint planning (D9, see Reports).

See `.claude/docs/metrics.md` "Sprint scope, facts and snapshots (D13)" for how committed/added/
removed/final/delivered/carried-over/dropped are actually computed and stored (`fact_sprint_scope`/
`fact_sprint`), the default sprint capacity (A3), and the `fact_sprint_snapshot`/`reconstructed`
rule this section's D13 describes.
- **Activity type / work category** — see Entities.

## Configuration

Each configuration change records a new revision and triggers a full re-derive of the `metrics`
layer (the same idea as `PROCESSING_VERSION` for `norm`): history is always read through the
*current* configuration, and every derived number names the revision it came from.

| Setting | Shape | Default |
|---|---|---|
| Status → stage | each Jira status id → `NOT_STARTED` / `IN_PROGRESS` / `DONE`, with an optional per-domain override | seeded from Jira's status category (new / indeterminate / done); an unmapped status is flagged, never guessed |
| Estimate field | one field id (tasks) + an optional override for epics | the field the data profile detects as `STORY_POINTS` |
| Epic start/due fields | two field ids | "Start date" + `duedate`, or Jira Plans' "Target start"/"Target end" |
| Project → domain (+ owner team, A19) | map | 1:1; owner = the team of the one mapped board on that project, else none |
| Board → team | 1:1 map (makes a sprint a team's sprint, and owns its backlog — D10) | none — must be set |
| Team × sprint capacity | MD | members × working days − absence, editable per sprint |
| `hoursPerDay`, working calendar, time zone | number; weekends + holidays; zone | Jira time-tracking setting; Mon–Fri; the unit's zone |
| Commitment grace | duration after sprint start | 0 |
| Issue type → activity type | map | 1:1 |
| Work-category field + value → category | one field id + map | none (field must be chosen); 1:1 |
| Minimum sample size | number | 5 — below it, percentiles and distributions are hidden with a note |
| Blocked statuses | set of status ids | none (only Flagged counts until set) |
| Aging-WIP window | N most recent done items + percentiles | 50; p50/p85/p95 |
| Backlog-in-sprints window | N most recent closed sprints | 3 |
| Epic drift threshold | days an epic may stay open after its last child is done | 14 |

## Analytical model (`metrics` schema)

A Kimball-style star whose storage is **intervals** (`tstzrange`, GiST-indexed). Facts stay at
**atomic grain** (task, epic, worklog, task × sprint, sprint); the daily aggregates hold **additive**
counts and sums only.

- **Configuration tables:** `status_stage_map`, `estimate_field`, `epic_date_fields`, `domain_map`,
  `board_team_map`, `team_sprint_capacity`, `calendar`, `activity_type_map`,
  `work_category_field`, `work_category_map`, `blocked_statuses`, `report_windows`,
  `config_revision`.
- **Dimensions:** `dim_org` (unit → team → user, effective-dated through `team_membership`),
  `dim_domain`, `dim_team`, `dim_user`, `dim_epic` (SCD2 on dates and domain), `dim_task` (key,
  type, activity type, work category, `is_subtask`, parent task), `dim_sprint` (board → team,
  planned start/end, completed at, state), `dim_date` (working-day flag).
- **Effective-dated bridges:**

| Bridge | Source | Rule |
|---|---|---|
| `team_membership(user, team, valid)` | Flow admin (D1) | `EXCLUDE USING gist (user WITH =, valid WITH &&)` — ≤1 team at a time |
| `task_epic(task, epic, valid)` | parent changelog items (a gap) | ≤1 epic at a time |
| `task_domain(task, domain, valid)` | `project` changes in `norm.work_item_field_changes` | exactly 1 at a time |
| `task_assignee(task, user, valid)` | `norm` ASSIGNEE intervals | ≤1 at a time |
| `task_sprint(task, sprint, valid)` | `norm` SPRINT intervals | several over time (carry-over) |
| `item_estimate(item, md, valid)` | estimate-field changes, tasks and epics (a gap) | in MD |
| `item_status(item, status, valid)` | `norm` status intervals, tasks and epics | tiles the item's lifetime |
| `item_stage(item, stage, valid)` | `item_status` + `status_stage_map` | tiles the item's lifetime |

- **Facts:**

| Fact | Grain | Carries |
|---|---|---|
| `fact_task_delivery` (accumulating snapshot) | task | `created_at`/`started_at`/`done_at`, reopens, estimate at start/done/current + `estimate_source` + changes after start, `actual_md`, `has_worklogs`, `blocked_time`, cycle/lead time (elapsed + working days), status-based active/wait time, assignee + assignee's team at done, sprint + sprint's team at done, own domain, epic + epic's domain, activity type, work category |
| `fact_epic_delivery` (accumulating snapshot) | epic | `started_at`/`done_at`, own estimate at start/done/current + changes after start, child-sum estimate, `actual_md`, cycle time, blocked time, domain, work category, drift flags (D11) |
| `fact_sprint_scope` | task × sprint | added/removed at, committed flag, `in_scope_at_close`, estimate at commitment and at close, assignee at commitment, done in sprint, carried over / dropped |
| `fact_sprint` | sprint (→ team) | committed, added, removed, final, delivered, carried-over and dropped SP (+ item counts), capacity, load — always the live recomputation |
| `fact_sprint_snapshot` | sprint (→ team) | the same figures (and the sprint's scope rows) frozen when the sprint completes, with the configuration revision and a `reconstructed` flag for sprints completed before Flow first processed them (D13) — never updated afterwards |
| `fact_worklog` | worklog | author + **author's team at `started`**, task + its activity type/work category, **task's domain + epic + epic's domain at `started`**, MD |
| `fact_epic_plan` | epic × baseline | start, due, budget MD, `budget_source` — PV curves can be redrawn "as originally planned" |
| `agg_daily_{team,domain,epic}` | scope × working day | WIP per status and per stage (tasks and epics), estimated-backlog count and SP, PV(t), EV(t), AC(t), throughput — item counts beside every SP figure |

**Organization and periods.** Sprint facts attach to a team through the sprint's board; task and
worklog facts attach to a user and, through `team_membership` at the relevant instant, to a team
(D5 and the cost rule); the unit is the sum of all teams plus `UNASSIGNED`, so every report drills
**unit → team → user**. Every fact carries its own timestamps, so any calendar period works (last
month, January, a custom range): a task belongs to a period by the timestamp the measure is about
(`done_at` for throughput and accuracy, `started_at` for starts), a sprint by its **completion
date**. Sprint-relative periods ("last sprint", "last 6 sprints") are **per team** — each team's own
closed sprints; at unit level, "last sprint" means each team's latest closed sprint.

**Distributions.** Averages, medians, **p50/p90/p95** and **full histograms** are computed **at
query time** over the atomic facts (`percentile_cont`, `width_bucket`) — never pre-aggregated,
because percentiles are not additive (a team's p90 is not derivable from its users' p90s). Groups
smaller than the minimum sample size show counts only, with a note. Outliers are never dropped —
the distribution shows them.

**Access.** Every signed-in user sees every report at every level, individuals included (D12);
configuration and data-source pages stay ADMIN-only. A Flow login may optionally be linked to its
Jira user (by email) for "my numbers" shortcuts.

**Reading the numbers.**

- The two domain views of D3 **disagree by design** for cross-domain tasks: "delivered in domain
  X" (task's own domain) and "earned in domain X" (epic's domain) count them differently. Every
  domain-sliced chart labels which view it shows.
- The two throughputs **disagree by design** too: the sprint view counts what was done inside the
  sprint while in it, at its estimate at completion; the period view counts every task by its
  `done_at`, at its estimate at done. A task finished after its sprint closed is in the second
  only.
- Attribution (team, domain, epic) is **as-was**; classification (activity type, work category,
  status → stage) is **as-is**, under the current configuration — so live numbers for a past
  period can move when configuration changes or late data arrives. Closed sprints are also frozen
  at completion (D13); the live figure is shown beside the frozen one, and the difference is
  flagged.

## Reports

Each report names the facts it reads (the SP figures always carry item counts beside them); every
one of them filters by period and drills unit → team →
user, and slices by domain, activity type and work category.

| # | Report | Source | Definition |
|---|---|---|---|
| 1 | **Velocity** | `fact_sprint_snapshot` (+ live `fact_sprint`) | initial = committed SP; final = final-scope SP at completion; per user via `assignee_at_commitment` in `fact_sprint_scope` |
| 2 | **Throughput** | `fact_sprint` (sprint view), `fact_task_delivery` (period view) | delivered SP; for a calendar period: SP of tasks with `done_at` in it |
| 3 | **Task estimation accuracy** | `fact_task_delivery`, DONE tasks with an estimate and worklogs | `actual_md ÷ estimate at start` (D15; vs estimate at done as a second view); full distribution; unestimated or worklog-less DONE tasks counted beside it, never in it |
| 4 | **Epic estimation accuracy** | `fact_epic_delivery`, DONE epics | `actual_md ÷ own estimate at start` (D15; at done as a second view); the child-sum estimate shown alongside |
| 5 | **Estimate adjustments** | `fact_task_delivery`, `fact_epic_delivery` | share of started items whose estimate changed after start, and the % change start → done (distribution); estimated-late items counted separately |
| 6.1 | **Velocity vs throughput** | `fact_sprint` | committed and final vs delivered SP, sprint over sprint |
| 6.2 | **Carry-over** | `fact_sprint` | carried-over SP (and dropped SP) per sprint |
| 6.3 | **Added scope** | `fact_sprint` | SP added after sprint start (and removed) |
| 7 | **Cycle time** | `fact_task_delivery` | per task; elapsed and working days; distribution |
| 8 | **Reported time ÷ cycle time** | `fact_task_delivery` | `actual_md ÷ cycle time in working days` — how much of the elapsed working time was logged; distinct from the status-based flow efficiency (active ÷ total time in stages) |
| 9 | **WIP** | `agg_daily_*` over `item_status`/`item_stage` | items in parallel per status (or stage, or board column) over time — tasks and epics |
| 10 | **Estimated backlog depth** | `agg_daily_*` | count and SP of estimated tasks ready for planning (D9), now and as a trend; owned by the owner team of the task's domain (A19), `(unowned)` otherwise |
| 11 | **Aging WIP** | `item_stage` + `fact_task_delivery` | the current age of every IN_PROGRESS task and epic against the team's cycle-time p50/p85/p95 over its last N done items; items past p85 highlighted |
| 12 | **Blocked time** | `fact_task_delivery`, `fact_epic_delivery` | blocked time per item, as a share of cycle time, and as a distribution |
| 13 | **Throughput in items; backlog in sprints** | `fact_sprint`, `agg_daily_*` | item counts beside SP in reports 1, 2, 6 and 10; backlog in sprints = estimated backlog SP ÷ mean delivered SP over the team's last N sprints |
| 14 | **Data quality** | all facts | per team and domain: worklog coverage (DONE tasks with worklogs; logged hours per member per working day vs `hoursPerDay`), late logging (worklog created vs `started`), tasks without an estimate, epic or work category, epic drift (D11), unmapped statuses, work done outside any sprint, sprint-snapshot drift (D13) |

## Invariants

The implementation asserts these as SQL sweeps over the persisted rows (the
`NormalizationPipelineTest` pattern, `.claude/docs/testing.md`):

1. A user belongs to ≤1 team at any instant (enforced by the exclusion constraint).
2. A task belongs to ≤1 epic at any instant; an epic to exactly 1 domain at any instant.
3. Status and stage intervals tile each item's lifetime (inherited from `norm`'s status tiling).
4. `started_at ≤ done_at` when both are set; `done_at` is set only while the current stage is DONE.
5. PV, EV and AC are in MD; unit conversion happens only in the `metrics` layer, under a recorded
   configuration revision.
6. Every worklog is attributed to exactly one (author team, task domain) pair; an author in no team
   lands in an explicit `UNASSIGNED` team — never dropped.
7. No double counting: each worklog and each estimate is counted once, at its own item, and rolls
   **up** the hierarchy (sub-task → task → epic), never sideways — the sum of task `actual_md`
   equals the `fact_worklog` total for the same scope (worklogs on epics aside).
8. `fact_sprint`'s totals equal the sums of its `fact_sprint_scope` rows.
9. Estimated backlog, WIP and done are mutually exclusive for an item at any instant.
10. A board maps to at most one team (D10).
11. A `fact_sprint_snapshot` row never changes once written.
12. Every live number is reproducible from `norm` + one configuration revision.

## Imperfections

Flagged and counted, never silently "fixed" (the same rule `norm` applies to its anomalies):

- **Cross-domain epics** — a task in domain A under an epic in domain B. Both domains are stored on
  every fact and a `cross_domain` flag is derived; the default attribution is D3.
- **Cross-team time** — a user in team X logs time on a task in another team's domain. Cost is
  charged to the **author's team** (who spent it) *and* the **task's domain/epic** (what it was
  spent on), giving a team × domain cost matrix plus a "foreign work" measure (time logged outside
  the team's own sprints and assignments).
- **Missing data** — a task with no epic (a per-domain `(no epic)` bucket), no estimate (counted in
  throughput, excluded from PV/EV and accuracy, listed), an epic with no estimate (budget from its
  children, flagged) or no dates (no PV curve), an unassigned task, a worklog author in no team, an
  estimate added only after start (estimated late), a sprint whose board maps to no team, a backlog
  item no mapped board covers, an unmapped status, a task with no work category, a DONE task with
  no worklogs (D14), work done outside any sprint (D10), an epic whose status disagrees with its
  children (D11). All of them surface in the data-quality report (14).

## Decisions

Agreed with the user on 2026-09-27.

- **D1 — Team membership is Flow-owned and effective-dated.** Admins maintain which Jira user is in
  which team, with valid-from/valid-to dates; Jira keeps no membership history, and the ≤1-team rule
  needs one. The existing `teams` registry stays; its `team_members` join links *Flow logins*, so a
  new dated membership of *Jira users* is added beside it. It may be seeded once from Atlassian
  Teams or the issues' Team field.
- **D2 — Epics are only EPICs; sub-tasks roll up.** An epic is never a TASK. A sub-task's
  estimate, delivery and worklogs roll up into its parent task (no double counting), and it stays
  visible for drill-down.
- **D3 — Cross-domain work is split by purpose.** Plan and EVM (PV/EV/AC) are attributed to the
  **epic's domain** (who owns the initiative); delivery and flow metrics (cycle time, WIP,
  throughput) stay with the **task's own domain** (whose workflow it moved through). Both views
  remain available.
- **D4 — The epic budget is the epic's own estimate** (revised the same day, when the reports
  showed epics carry their own SP estimate): baselined when its dates are set, with later drift
  reported; the sum of child estimates is shown alongside and is the fallback when an epic has no
  estimate of its own. *(First agreed as "the sum of child estimates".)*
- **D5 — Delivery credit: sprint team + assignee.** Team velocity and say/do go to the team whose
  sprint the task was done in (board → team); "a user's task" is the assignee at `done_at`. Work
  done outside any sprint falls back to the assignee's team at `done_at` — an anomaly path only,
  since every team works in sprints (D10).
- **D6 — Activity types are standard issue types**, one per task — so D2's sub-task roll-up loses
  no activity information.
- **D7 — Epics carry their own story-point estimate**, compared to actual cost and tracked for
  changes after the epic starts (reports 4 and 5); see D4.
- **D8 — Work category: the task's own value, else its epic's.**
- **D9 — The estimated backlog** is every task (not an epic or sub-task) in a NOT_STARTED status
  with an estimate that is not in an active or closed sprint — future sprints count as backlog —
  owned by the team whose board shows it. A narrower "ready statuses only" refinement is a possible
  later configuration option.
- **D10 — One board per team; every team works in sprints.** Board → team is 1:1; there are no
  Kanban teams, so sprint-based reports cover everyone.
- **D11 — Epics follow their own status; drift is flagged.** An epic's started/done, WIP, cycle
  time and accuracy come from its own status intervals. Three flags compare it with its children —
  `EPIC_NOT_STARTED_WITH_ACTIVE_CHILDREN`, `EPIC_OPEN_AFTER_CHILDREN_DONE` (past the configured
  threshold) and `EPIC_DONE_WITH_OPEN_CHILDREN` — shown in the data-quality report, never
  corrected.
- **D12 — Everyone sees every level.** Every signed-in user sees all reports down to individuals;
  configuration and data sources stay ADMIN-only.
- **D13 — Closed sprints are frozen at completion, with the live view beside them**; the difference
  (late worklogs, re-estimates, re-mapped statuses) is flagged.
- **D14 — Logging time is expected for all work**; a DONE task without worklogs is a data-quality
  finding and is left out of estimation accuracy.
- **D15 — Estimation accuracy is judged against the estimate at start**; the estimate at done is a
  second view (report 5 shows the adjustments in between).
- **D16 — Added reports:** aging WIP, blocked time, item counts beside SP with the backlog in
  sprints, and a data-quality view (reports 11–14).

## Amendments

Agreed with the user on 2026-09-28, when writing `.claude/docs/measures.md` showed where the model
above was silent or contradicted itself (the implementation plan's §0 A17–A21; `measures.md`
carries the per-measure detail).

- **A17 — Sprint buckets form a partition**: committed (in at start + grace and still in at
  completion), added, removed (beside, removal wins over delivery), and carry-over/dropped for every
  not-done item in the final scope — see the Glossary.
- **A18 — Flow efficiency** (active ÷ cycle time) is a delivered measure beside report 8.
- **A19 — A domain has an owning team** (configured per domain; the default is the team of the one
  mapped board on that project). Epics and the estimated backlog are attributed to their domain's
  owner team — this replaces D9's "owned by the team whose board shows it"; with no owner they are
  `(unowned)`, and report 14 lists the domain. Tasks keep D5.
- **A20 — Team-level EVM**: a team's PV is its sprints' committed scope, EV the scope it delivered
  in them, AC its members' worklogs; team CPI is always shown with the team's foreign-work share
  beside it, since that share is exactly where the author's-team cost and the sprint's-team value
  diverge. Report 15 has epic, domain and team levels.
- **A21 — The remaining rules** are settled from D1–D16 without a new decision: level-0 reads by
  default (D2); an open item's team is D5 evaluated now; attribution of domain and epic is as-was at
  `done_at` (or now); a task with no epic falls back to its own domain in the epic's-domain view;
  WIP counts items at the end of each calendar day in the configured zone; foreign work also
  compares the author's team with the assignee's team when the task has no sprint team. The full
  list is in `measures.md`.
- **A22 — Settled from the 9d Opus review** (main session, 2026-09-28; `.claude/docs/metrics.md`
  "Derivation corrections from the measure contract" has the implementation detail):
  - **Current sprint excludes closed sprints.** A sprint whose `complete_at ≤ now` (or whose Jira
    `state` is `closed`) is never an open item's current sprint. A not-done task left in a closed
    sprint is effectively backlog, so D5-at-now falls back to its assignee's team.
  - **Now-evaluated team columns ignore soft-deleted teams.** This covers `current_team_id` and
    `owner_team_id`: a board mapping or configured owner pointing at a soft-deleted team resolves to
    none. As-was columns (`credit_team_id`, `author_team_id`, sprint team at done/started) keep the
    historical team regardless.
  - **The owner team belongs to the DOMAIN, not the project.** Resolution:
    - use the configured owner if every project row of the domain agrees (a disagreeing PUT is a
      400 once the API lands);
    - else the team of the single mapped board across ALL the domain's projects;
    - else none.

    The resolved owner is persisted per domain on `dim_domain.owner_team_id` (V17), which reports
    and data quality read.
  - **Epic-logged worklogs.** For a worklog logged directly on an epic, foreign work compares the
    author's team with the epic's OWNER team (A19), not the epic's assignee's team — epics carry no
    sprint at all, so the sprint-team branch never applies to them either.

## Gaps in `norm` today

What the implementation must add to PROCESS (with a `PROCESSING_VERSION` bump) before the
`metrics` layer can be built:

- **Epic membership history.** `norm.work_items.parent_issue_id` is the current parent only, and
  `norm.work_item_field_changes` does not keep parent changes. Jira Cloud's `parent` field replaced
  Epic Link; the changelog spelling (`Parent`, `IssueParentAssociation`, `Epic Link`) must be
  confirmed on the real tenant, then tiled into `task_epic`.
- **Estimate history, tasks and epics.** Story-point changes are kept verbatim for the detected
  story-points field but not tiled, and a different epic estimate field (if configured) is not kept
  at all; `item_estimate` needs both as intervals.
- **Epic dates.** "Start date", `duedate` and "Target start/end" are neither extracted as current
  values nor tracked as changes.
- **Arbitrary custom fields.** `norm.work_items` keeps only the fields it knows (Sprint, Rank,
  Team, story points, Flagged); the work-category field (and any future configurable field) needs
  its current value and its changes kept — e.g. a `custom_fields` JSONB of current values plus
  change tracking for configured field ids.
- **Worklog `created`/`updated` timestamps.** `norm.work_item_worklogs` keeps only `started`;
  late logging (report 14) needs when the worklog was actually entered. `raw.jira_worklogs` already
  has the full payload.
- **Worklog time zone.** `started` carries its own offset; converting to working days uses Flow's
  calendar and zone, not the author's local day.
- Sprints map to teams through their **board**, and a board may span several spaces — harmless
  here, because a sprint maps to a team, not to a domain; backlog ownership uses the board's own
  project (`norm.boards.project_key`), so a multi-project board's other projects fall to
  `(unowned)` until that is revisited (see `BACKLOG.md`'s multi-project boards item).
