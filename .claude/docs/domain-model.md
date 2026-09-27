# Domain model (phase 3)

This doc defines what Flow's numbers MEAN: the entities, how they map to Jira, the three
measurement dimensions, the configuration they depend on, the analytical (`metrics`) model, its
invariants, and how known data imperfections are handled. It is the contract the metrics layer and
every dashboard are built against. **Status: agreed 2026-09-27, not yet implemented** — the
`metrics` schema, the PROCESS additions in "Gaps in `norm` today" and the configuration UI arrive
in a later implementation plan (`BACKLOG.md`).

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
              └────────────────────────────────────────┘  USER ──── member of (≤1 at a time) ──► TEAM
                                                           │
                                               WORKLOG ────┘ author; logged on a TASK
```

The primary entities are **DOMAIN**, **TASK** and **USER**; nearly every question is about one of
them or a combination.

| Entity | Meaning | Jira source | Over time |
|---|---|---|---|
| **DOMAIN** | how work is grouped around topics/systems | a Jira **space** (Atlassian's 2025 name for a project), `project.key`; a configurable project → domain map, 1:1 by default | a task *moving* between spaces is history (`project` changelog items) |
| **TEAM** | a group of users | Flow-owned (D1) | effective-dated membership |
| **USER** | a Jira account — not a Flow login | `accountId` (`norm.people`); optionally linked to a Flow login by email | ≤1 team at a time |
| **EPIC** | a longer body of work with start/due dates, planned in Jira Plans | an issue at hierarchy level 1 | its domain = its space; dates can change |
| **TASK** | every other issue, whatever its issue type | level-0 issues; sub-tasks roll up into their parent (D2) | epic, assignee, sprint(s), status, estimate — all intervals |
| **SPRINT** | the time-box work is planned into and delivered in | agile sprint (`originBoardId`, start/end/complete dates, state) | a task may pass through several (carry-over) |
| **WORKLOG** | time actually spent | `/worklog` (author, `started`, seconds) | edits and deletions are tracked |

Relationship rules:

- A task belongs to **at most one epic** at any instant; an epic belongs to **exactly one domain**
  (its space).
- A user belongs to **at most one team** at any instant.
- A task is **a user's task** while that user is its **assignee** (the assignee intervals).
- A sprint is a **team's sprint** through its board (board → team configuration).

## The three dimensions

Everything is measured in **man-days (MD)**. Story points convert **1 SP = 1 MD**; worklog seconds
convert by `hoursPerDay` (configuration, defaulting to Jira's own time-tracking setting, 8h).

**Plan — PV (planned value).**

- *Sprint:* the **committed scope** is the tasks in the sprint at its start (plus a configurable
  grace period); its PV is their estimates *as of commitment*. Scope added mid-sprint is **added
  scope**, scope taken out is **removed scope** — both tracked separately, never folded into the
  commitment.
- *Epic:* the **budget** is the sum of its child tasks' estimates, baselined when the epic's dates
  are set (D4); it is spread over the working days between the epic's start and due dates into a
  cumulative PV curve PV(t). Later budget or date changes create a new baseline, and the drift is
  reported rather than overwriting the original plan.
- *Capacity:* team × sprint capacity in MD is configured in Flow (Jira exposes no reliable capacity
  API). **Load** = committed PV ÷ capacity.

**Delivery — EV (earned value).** From the configured status stages:

- `started_at` = the task's **first** entry into an IN_PROGRESS status.
- `done_at` = its **last** entry into a DONE status that it never left afterwards. A reopened task
  is not delivered until it is finally done again; its reopen count is kept.
- EV(t) = the sum of baseline estimates of tasks with `done_at` ≤ t. Sprint EV = committed scope
  done by the sprint's end; added scope done is reported next to it.
- The same data gives cycle time (`done_at − started_at`), lead time (`done_at − created_at`),
  throughput, WIP, work-item age and flow efficiency (active ÷ total time).

**Cost — AC (actual cost).** AC(t) = the sum of worklog MD logged up to t, for any scope (task,
epic, domain, team, user). Worklogs on a sub-task roll up to its parent task, then to the epic.

**Derived:** SV = EV − PV, SPI = EV / PV, CV = EV − AC, CPI = EV / AC, say/do (committed done ÷
committed), estimate accuracy (actual MD ÷ estimate, per task).

## Configuration

Each configuration change records a new revision and triggers a full re-derive of the `metrics`
layer (the same idea as `PROCESSING_VERSION` for `norm`): history is always read through the
*current* configuration, and every derived number names the revision it came from.

| Setting | Shape | Default |
|---|---|---|
| Status → stage | each Jira status id → `NOT_STARTED` / `IN_PROGRESS` / `DONE`, with an optional per-domain override | seeded from Jira's status category (new / indeterminate / done); an unmapped status is flagged, never guessed |
| Estimate field | one field id | the field the data profile detects as `STORY_POINTS` |
| Epic start/due fields | two field ids | "Start date" + `duedate`, or Jira Plans' "Target start"/"Target end" |
| Project → domain | map | 1:1 |
| Board → team | map (makes a sprint a team's sprint) | none — must be set |
| Team × sprint capacity | MD | members × working days − absence, editable per sprint |
| `hoursPerDay`, working calendar | number; weekends + holidays | Jira time-tracking setting; Mon–Fri |
| Commitment grace | duration after sprint start | 0 |

## Analytical model (`metrics` schema)

A Kimball-style star whose storage is **intervals** (`tstzrange`, GiST-indexed). Daily snapshots
are materialized only as **aggregates** (per team/domain/epic per working day), never per task per
day — a real tenant's tens of thousands of issues × two years of days never exist as rows.

- **Configuration tables:** `status_stage_map`, `estimate_field`, `epic_date_fields`, `domain_map`,
  `board_team_map`, `team_sprint_capacity`, `calendar`, `config_revision`.
- **Dimensions:** `dim_domain`, `dim_team`, `dim_user`, `dim_epic` (SCD2 on dates and domain),
  `dim_task` (key, type, `is_subtask`, parent task), `dim_sprint` (board → team, planned
  start/end, completed at, state), `dim_date` (working-day flag).
- **Effective-dated bridges:**

| Bridge | Source | Rule |
|---|---|---|
| `team_membership(user, team, valid)` | Flow admin (D1) | `EXCLUDE USING gist (user WITH =, valid WITH &&)` — ≤1 team at a time |
| `task_epic(task, epic, valid)` | parent changelog items (a gap, see below) | ≤1 epic at a time |
| `task_domain(task, domain, valid)` | `project` changes in `norm.work_item_field_changes` | exactly 1 at a time |
| `task_assignee(task, user, valid)` | `norm` ASSIGNEE intervals | ≤1 at a time |
| `task_sprint(task, sprint, valid)` | `norm` SPRINT intervals | several over time (carry-over) |
| `task_estimate(task, md, valid)` | story-point changes (a gap, see below) | in MD |
| `task_stage(task, stage, valid)` | `norm` status intervals + `status_stage_map` | tiles the task's lifetime |

- **Facts:**

| Fact | Grain | Carries |
|---|---|---|
| `fact_task_delivery` (accumulating snapshot) | task | `created_at`/`started_at`/`done_at`, reopens, baseline + final estimate, cycle/lead/active/wait time, assignee + assignee's team at done, sprint + sprint's team at done, own domain, epic + epic's domain at done |
| `fact_sprint_scope` | task × sprint | added/removed at, committed flag, estimate at commitment and at end, done in sprint, carried over |
| `fact_worklog` | worklog | author + **author's team at `started`**, task, **task's domain + epic + epic's domain at `started`**, MD |
| `fact_epic_plan` | epic × baseline | start, due, budget MD — PV curves can be redrawn "as originally planned" |
| `agg_daily_{team,domain,epic}` | scope × working day | WIP, PV(t), EV(t), AC(t), throughput, age buckets |

## Invariants

The implementation asserts these as SQL sweeps over the persisted rows (the
`NormalizationPipelineTest` pattern, `.claude/docs/testing.md`):

1. A user belongs to ≤1 team at any instant (enforced by the exclusion constraint).
2. A task belongs to ≤1 epic at any instant; an epic to exactly 1 domain at any instant.
3. Stage intervals tile each task's lifetime (inherited from `norm`'s status tiling).
4. `started_at ≤ done_at` when both are set; `done_at` is set only while the current stage is DONE.
5. PV, EV and AC are in MD; unit conversion happens only in the `metrics` layer, under a recorded
   configuration revision.
6. Every worklog is attributed to exactly one (author team, task domain) pair; an author in no team
   lands in an explicit `UNASSIGNED` team — never dropped.
7. No double counting: each worklog and each estimate is counted once, at its own task, and rolls
   **up** the hierarchy (sub-task → task → epic), never sideways.
8. Every number is reproducible from `norm` + one configuration revision.

## Imperfections

Flagged and counted, never silently "fixed" (the same rule `norm` applies to its anomalies):

- **Cross-domain epics** — a task in domain A under an epic in domain B. Both domains are stored on
  every fact and a `cross_domain` flag is derived; the default attribution is D3.
- **Cross-team time** — a user in team X logs time on a task in another team's domain. Cost is
  charged to the **author's team** (who spent it) *and* the **task's domain/epic** (what it was
  spent on), giving a team × domain cost matrix plus a "foreign work" measure (time logged outside
  the team's own sprints and assignments).
- **Missing data** — a task with no epic (a per-domain `(no epic)` bucket), no estimate (counted in
  throughput, excluded from PV/EV, listed), an epic with no dates (no PV curve), an unassigned
  task, a worklog author in no team, an estimate changed after commitment (baseline and final both
  kept), a sprint whose board maps to no team, an unmapped status.

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
- **D4 — The epic budget is the sum of its child estimates**, baselined when its dates are set,
  with later drift reported (the epic's own estimate field is not used).
- **D5 — Delivery credit: sprint team + assignee.** Team velocity and say/do go to the team whose
  sprint the task was done in (board → team); "a user's task" is the assignee at `done_at`. Work
  done outside any sprint (Kanban) falls back to the assignee's team at `done_at`.

## Gaps in `norm` today

What the implementation must add to PROCESS (with a `PROCESSING_VERSION` bump) before the
`metrics` layer can be built:

- **Epic membership history.** `norm.work_items.parent_issue_id` is the current parent only, and
  `norm.work_item_field_changes` does not keep parent changes. Jira Cloud's `parent` field replaced
  Epic Link; the changelog spelling (`Parent`, `IssueParentAssociation`, `Epic Link`) must be
  confirmed on the real tenant, then tiled into `task_epic`.
- **Estimate history.** Story-point changes are kept verbatim but not tiled; `task_estimate` needs
  them as intervals.
- **Epic dates.** "Start date", `duedate` and "Target start/end" are neither extracted as current
  values nor tracked as changes.
- **Worklog time zone.** `started` carries its own offset; converting to working days uses Flow's
  calendar, not the author's local day.
- Sprints map to teams through their **board**, and a board may span several spaces — harmless
  here, because a sprint maps to a team, not to a domain.
