# Measures (v0.3.0) — the per-measure contract

`.claude/docs/domain-model.md` stays the source of MEANING (entities, the three dimensions,
D1–D16, invariants 1–12). This doc is the operational contract, one row per number a report shows:
which fact/column it reads, which timestamp places it in a period, whom it is attributed to, which
estimate snapshot and unit it uses, how missing data is treated, whether it is frozen or live, and
which test pins it. It was drafted as a coherence check of the model against the M3 code; the
decisions it forced are the plan's §0 amendments **A17–A21** (2026-09-28).

Status marker: `planned` — the writer or reader does not exist yet (`agg_daily_*`, the reports
API); a marked row becomes binding when its commit fills "Pinned by".

## Conventions (every row, unless the cell says otherwise)

- **Timestamps** are epoch millis UTC in every fact. Day boundaries — periods, WIP days, working
  days — come from the configured zone through `WorkingCalendar`/`dim_date`
  (`metrics.settings.time_zone`, default `Europe/Warsaw`, A4). Period filters are ISO dates in
  that zone, inclusive.
- **Period membership:** a task by `done_at` (delivery), a worklog by `started_at` (cost), a
  sprint by `complete_at` — whole sprint or nothing. The ACTIVE sprint (no `complete_at`) is
  reachable only by an explicit `sprintId`.
- **Level-0 by default (D2):** task-level reads use `is_subtask = false`; sub-task rows exist for
  drill-down only — their worklogs and estimates are already rolled into the parent (`actual_md`,
  `estimate_source = SUBTASKS`). An unfiltered sum double counts.
- **Live rows only:** DERIVE reads `norm.work_items` with `deleted_at IS NULL AND moved_out_at IS
  NULL`.
- **Team codes.**
  - `credit` = `fact_task_delivery.credit_team_id` — D5: the sprint's team at `done_at`, else the
    assignee's team at `done_at` (the fallback also covers a sprint on an unmapped board); null
    while not done.
  - `current` = `current_team_id` — the same D5 rule evaluated at now, for open items, with TWO
    A22 corrections over the as-was `credit` rule: a sprint whose `complete_at ≤ now` (or whose
    Jira `state` is `closed`) is never an open item's current sprint — a not-done task left in a
    closed sprint falls straight to the assignee fallback, effectively backlog; and a currently
    soft-deleted team is skipped (a soft-deleted sprint team falls back to the assignee's team, a
    soft-deleted assignee team gives no team) — never a retired team a report would have to
    explain.
  - `sprint` = `dim_sprint.team_id` (board → team, as-is).
  - `author` = `fact_worklog.author_team_id` (membership at `started_at`, as-was).
  - `owner` = the domain's owner team (A19, persisted per domain on `dim_domain.owner_team_id`,
    resolved by `MetricsDeriver.ownerTeamByDomain`: every one of the domain's project rows that
    carries a CONFIGURED owner (`metrics.domain_map.owner_team_id`) must agree — a genuine
    disagreement resolves to no owner outright, never a fallback; no configured owner at all falls
    back to the ONE `board_team_map` board mapped across all the domain's projects) — for epics and
    the estimated backlog. A22: both the configured owner and the board-mapped team are filtered to
    currently ACTIVE teams first — a soft-deleted team resolves as if unconfigured/unmapped.
  - A null team is the UNASSIGNED bucket at query time — never a stored sentinel; a null owner is
    `UNOWNED`.
- **User codes.** `assignee@done` = `assignee_account_id_at_done`; `assignee@now` =
  `current_assignee_account_id`; `assignee@commit` =
  `fact_sprint_scope.assignee_at_commitment`; `author` = `fact_worklog.author_account_id`.
- **Domain codes (D3).** `TASK` = the task's own domain; `EPIC` = its epic's domain, falling back
  to the task's own domain when it has no epic (A21); `own` = an epic's own space. Task domain and
  epic are **as-was** at `done_at ?: now` (A21) — a covering `task_domain`/`task_epic` history row
  whose OWN value is genuinely `null` (no domain/epic resolved at that instant) is trusted as-is,
  never flattened into the task's CURRENT value; only the ABSENCE of any covering row (a task that
  has never moved) falls back to current. `epic_domain_key` itself is always the epic's CURRENT
  domain — a known, deliberate limitation: no epic-domain HISTORY table exists (unlike
  `task_domain`), so a worklog or task attributed to an epic's domain always sees that epic's
  domain as of NOW, never as it was at the read's own anchor instant.
- **As-of interval semantics.** Every "value active at instant `t`" read (a bridge row containing
  `t`, an estimate/date timeline point, a team-membership interval) is **half-open, `[from, to)`**:
  a change landing at EXACTLY `t` counts as the NEW value, never the old one still in effect one
  millisecond before it.
- **Estimate snapshots:** `@commit`, `@entry`, `@start`, `@done`, `@close` (sprint close),
  `current`; source `OWN|SUBTASKS|NONE` (tasks), `OWN|CHILDREN` (epic budget). `0` = unestimated
  (stored `null`).
- **Units:** MD (1 SP = 1 MD; worklog seconds ÷ 3600 ÷ `hoursPerDay`, manual, default 8 — A5);
  items; elapsed ms (shown as days); wd = fractional working days
  (`WorkingCalendar.workingDaysBetween`).
- **Frozen/live:** `live` = rebuilt wholesale by every DERIVE under one `config_revision`;
  `frozen` = `fact_sprint_snapshot` (D13), written ONCE when a closed, team-mapped sprint is first
  seen. `reconstructed` means only "the connection had no successful DERIVE before the sprint
  closed".
- **Sentinels:** UNASSIGNED (null team), `UNOWNED` (null owner), `UNCATEGORIZED` (null work
  category), `UNMAPPED` (the stage of a status with no map row — never started, never done),
  `(no epic)` (null `epic_id`).

Columns: **Grain · Anchor · Team · User · Domain · Estimate · Unit · Missing data · Frozen/live ·
Source · Pinned by**. `—` = not applicable, or (in "Pinned by") a gap.

## Report 1 — Velocity

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Initial velocity (committed) | sprint | `complete_at` | sprint | assignee@commit — same rows as the team figure, so Σ users = team | — | @commit | MD + items | unestimated → 0 MD, counted in items; items removed later are excluded (in *removed*); a sprint on an unmapped board has no team and shows only in report 14 | frozen + live, drift flagged | `fact_sprint(_snapshot).committed_md/_items`; per user `fact_sprint_scope` | `MetricsDerivationTest` "golden FLO sprint's buckets"; `MetricsGoldenTest`; `DeriveKernelsTest` "sprintScope marks a task committed", "respects the grace period"; `ReportVelocityTest` "sprintId period returns the golden FLO sprint matching expected json", "TEAM level groups sum to the team total" |
| Final velocity | sprint | `complete_at` | sprint | assignee@commit | — | @close | MD + items | = committed + added (A17) | frozen + live | `final_md/_items` | golden FLO; `DeriveKernelsTest` "sprintTotals sums exactly the rows it is given"; `ReportVelocityTest` "UNIT level lists the FLO team's sprints with finalMd matching fact_sprint", "sprintId period returns the golden FLO sprint matching expected json"; `ReportVelocityTest` "TEAM level groups sum to the team total" |

## Report 2 — Throughput

The two views differ by design: a task done after its sprint closed counts in the period view but
in no sprint's delivered scope, and the sprint view reads the estimate at close, the period view
at `done_at`.

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Throughput, sprint view (delivered) | sprint | `complete_at` | sprint | assignee@commit | — | @close | MD + items | unestimated → 0 MD, counted; done then removed before close → *removed*, not delivered (A17, Jira's convention) | frozen + live | `fact_sprint.delivered_md/_items` = Σ `fact_sprint_scope.done_in_sprint` | golden FLO; `DeriveKernelsTest` "marks delivered scope"; `MetricsDerivationTest` "invariant 8" |
| Throughput, period view | task | `done_at` | credit | assignee@done | TASK | @done | MD + items | unestimated → 0 MD, counted; no team → UNASSIGNED; created straight into DONE still counts | live | `fact_task_delivery.done_at`, `estimate_at_done_md`, `credit_team_id` | "invariants 3 and 4"; "D5 - the OPS Kanban…" (credit fallback); no value pin — |

## Report 6 — Sprint consistency, capacity, load

A17: final = delivered + carried + dropped, always; final = committed + added in items (in MD only when no
item was re-estimated between its commitment/entry and the close — committed/added are priced at entry,
the rest at close); removed is beside them.

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Committed | sprint | `complete_at` | sprint | assignee@commit | — | @commit | MD + items | in scope at start + grace AND at close | frozen + live | `committed_md/_items` | golden FLO; `MetricsGoldenTest` |
| Added | sprint | `complete_at` | sprint | assignee@entry | — | @entry | MD + items | entered after start + grace, in scope at close | frozen + live | `added_md/_items` | golden FLO; "marks added scope" |
| Removed | sprint | `complete_at` | sprint | assignee@commit | — | @commit | MD + items | in no other bucket; wins over delivered | frozen + live | `removed_md/_items` | golden FLO; "marks removed scope … excluded from every other bucket" |
| Carried over | sprint | `complete_at` | sprint | — | — | @close | MD + items | in scope at close ∧ not done ∧ in a LATER sprint of the same board — committed OR added (A17) | frozen + live | `carried_over_md/_items` | golden FLO; "carries over an ADDED task present in a later sprint of the team"; "every fact_sprint row partitions as final = delivered + carried + dropped" |
| Dropped | sprint | `complete_at` | sprint | — | — | @close | MD + items | in scope at close ∧ not done ∧ no later sprint — committed OR added (A17) | frozen + live | `dropped_md/_items` | golden FLO; "marks dropped scope"; "and drops it otherwise (A17)"; "every fact_sprint row partitions as final = delivered + carried + dropped" |
| Capacity | sprint | `complete_at` | sprint | — | — | — | MD | `CONFIGURED` wins; `DEFAULT` = every member whose membership overlaps the window, in full, × wd of the window (A3); no team or no `start_at` → null | live (snapshot copies it) | `dim_sprint.capacity_md/_source`, `fact_sprint.capacity_md` | "sprint capacity defaults to Sigma members x working days (A3)…" |
| Load | sprint | `complete_at` | sprint | — | — | @commit | ratio | null when capacity is null or 0 | frozen + live | `committed_md / capacity_md` | same test |

## Reports 3, 4, 5 — Estimation

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Task accuracy @start (D15) | task, DONE | `done_at` | credit | assignee@done | TASK | @start, OWN or SUBTASKS | ratio, distribution | excluded, each counted in its own bucket: `unestimatedAtStart` (incl. estimated-late), `neverStarted` (no `started_at` — has no @start), `noWorklogs` (D14); n < `minSampleSize` → hidden | live | `actual_md / estimate_at_start_md` | kernel only: `DeriveKernelsTest` "estimateSnapshots …"; report value — |
| Task accuracy @done | task, DONE | `done_at` | credit | assignee@done | TASK | @done | ratio | same buckets on `estimate_at_done_md` | live | `actual_md / estimate_at_done_md` | — |
| Epic accuracy @start / @done | epic, DONE by its own status (D11) | `done_at` | owner (A19) | — | own | own @start / @done; child sum beside | ratio | no own estimate (`budget_source = CHILDREN`) or `actual_md = 0` → excluded, counted | live | `fact_epic_delivery.actual_md / own_estimate_at_*_md`, `child_sum_estimate_md` | "golden epic's own budget, start-due dates and child sum" (values, not the ratio); `MetricsDerivationTest` "owner_team_id equals the FLO board's team for FLO epics (A19)" |
| Share changed after start | task/epic, started | `started_at` | credit (tasks), owner (epics) | assignee@done | TASK | timeline points after `started_at` | % | never started → excluded | live | `estimate_changes_after_start > 0` over `started_at IS NOT NULL` | `DeriveKernelsTest` "estimateSnapshots …" |
| % change start → done | task/epic, DONE | `done_at` | credit / owner | assignee@done | TASK | (@done − @start) ÷ @start | %, distribution | either snapshot null → excluded; estimated-late counted separately, never +∞ | live | `estimate_at_done_md`, `estimate_at_start_md` | — |
| Estimated late | task/epic, started | `started_at` | credit / owner | assignee@done | TASK | @start null ∧ current non-null | items | — | live | `estimated_late` | `DeriveKernelsTest` "reports estimated-late…", "is not estimated-late…" |

## Reports 7, 8 — Cycle time, lead time, reported ÷ cycle, flow efficiency

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Cycle time | task (epics by own status) DONE | `done_at` | credit / owner | assignee@done | TASK | — | elapsed + wd | no `started_at` → excluded (`neverStarted`); reopened: first IN_PROGRESS → start of the trailing DONE run | live | `cycle_ms`, `cycle_working_days` | "invariants 3 and 4"; `DeriveKernelsTest` "startedDoneAt …"; `WorkingCalendarTest` |
| Lead time | same | `done_at` | credit / owner | assignee@done | TASK | — | elapsed + wd | never null when done | live | `lead_ms`, `lead_working_days` | — |
| Reported ÷ cycle | task DONE with worklogs | `done_at` | credit | assignee@done | TASK | — | ratio | no worklogs / `neverStarted` / `zeroCycle` (`cycle_working_days = 0`) → excluded, counted | live | `actual_md / cycle_working_days` | — |
| Flow efficiency (A18) | task DONE | `done_at` | credit | assignee@done | TASK | — | ratio | `neverStarted` / `zeroCycle` → excluded, counted | live | `active_ms / cycle_ms`; active = IN_PROGRESS-stage time in `[started_at, done_at)` minus blocked time that occurred WHILE IN_PROGRESS (blocked time outside any IN_PROGRESS stretch — e.g. blocked-while-UNMAPPED — is already WAIT, never subtracted a second time); wait = cycle − active | `DeriveKernelsTest` "sums IN_PROGRESS time inside the cycle window, minus blocked time, wait is the rest", "ignores blocked time outside any IN_PROGRESS stage, never double-subtracting it (review round 2c fix)", "subtracts a reopen-spanning blocked interval only where it overlaps each IN_PROGRESS stretch, not the DONE gap", "floors active at 0 as a defensive backstop, never negative"; `MetricsDerivationTest` "active plus wait equals cycle, both within 0 and cycle, for every DONE task (A18)" |

## Report 9 — WIP (`planned`)

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| WIP per status / stage / board column | scope × day × item kind × status | end of day d in the configured zone (`valid_from < day_end ≤ valid_to ∣ open`), one row for EVERY calendar day; `is_working_day` lets a chart hide weekends | tasks: current D5 team as of that day; epics: owner | — | TASK | — | items | UNMAPPED is its own key; board columns via `norm.board_columns` as-is | live | `agg_daily_wip.item_count` over `item_stage` | — |

## Reports 10, 13 — Estimated backlog, backlog in sprints (`planned`)

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Estimated backlog (D9) | scope × day | end of day d | owner of the task's domain (`dim_domain.owner_team_id`, A19); none → `UNOWNED` | — | TASK | estimate at d | items + MD | level-0, NOT_STARTED, estimate > 0, in no sprint with `start_at ≤ end of d` (future sprints count as backlog) | live | `agg_daily_flow.backlog_items/_md` | — |
| Backlog in sprints | team | as of the period end | owner = sprint team | — | — | — | sprints | ÷ mean `delivered_md` of the team's last N closed sprints (`backlog_window_sprints`); fewer than N → what exists; mean 0 → null | live | backlog MD ÷ avg(`fact_sprint.delivered_md`) | — |

## Report 11 — Aging WIP (`planned`)

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Age | open task/epic, current stage IN_PROGRESS | request time − `started_at` | current (tasks); owner (epics) | assignee@now | TASK | — | wd | UNMAPPED is not in progress → excluded | live | `fact_task_delivery`/`fact_epic_delivery` `started_at WHERE done_at IS NULL` | `MetricsDerivationTest` "current_team_id matches D5 evaluated now …", "current_team_id ignores a CLOSED current sprint, falling back to the assignee's team (A22)" |
| Thresholds p50/p85/p95 | team | the team's last N DONE items by `done_at` (`aging_window_items`) | credit | — | TASK | — | wd | n < `minSampleSize` → hidden | live | `cycle_working_days` of those items | — |

## Report 12 — Blocked time

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Blocked time | task/epic | `done_at` (open items: now) | credit / current (tasks); owner (epics) | assignee@done / @now | TASK | — | wd (+ ms) | FLAGGED ∪ configured blocked statuses, merged, clipped to `[started_at, done_at ∣ now)`; never started → 0 | live | `blocked_working_days`, `blocked_ms`; `item_blocked` (`reason` FLAGGED/STATUS) | `DeriveKernelsTest` "blockedIntervals unions … clips", "is empty when the item never started", "reports STATUS …" |
| Blocked share of cycle | task/epic DONE | `done_at` | credit / owner | assignee@done | TASK | — | ratio | open or `zeroCycle` → excluded | live | `blocked_working_days / cycle_working_days` | — |

## Report 15 — EVM (A7 + A20, `planned`; epic, domain and team levels)

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| PV(t), epic/domain | epic × baseline | budget spread evenly over the working days of `[start, due]` | owner | — | own (EPIC view) | budget = own estimate, else CHILDREN (D4) | MD | no dates → no curve; CHILDREN budget flagged | live; superseded baselines kept | `fact_epic_plan` (9b) | 9b tests; "golden epic's own budget, start-due dates and child sum" |
| PV(t), team (A20) | team | sprints with `start_at ≤ t` | sprint | — | — | @commit | MD | Σ `committed_md` of those sprints | live | `fact_sprint` / `agg_daily_flow.pv_md` | — |
| EV(t) | scope | `done_at ≤ t` | TEAM scope: MD delivered in the team's sprints (A20); DOMAIN/EPIC scope: `estimate_at_done_md` of level-0 tasks | — | EPIC | @done (team: @close) | MD | unestimated → 0; an epic's own estimate is never EV (it is the budget) | live | `fact_sprint_scope.done_in_sprint` / `fact_task_delivery`; `agg_daily_flow.ev_md` | — |
| AC(t) | scope | worklog `started_at ≤ t` | author | author | EPIC | — | MD | author in no team → UNASSIGNED, never dropped (invariant 6) | live | Σ `fact_worklog.md`; `agg_daily_flow.ac_md` | "fact_worklog - invariant 6", "invariant 7" |
| SV, SPI, CV, CPI | epic / domain / team | as of t | as the curves | — | EPIC | — | MD / ratio | PV = 0 → SPI null; AC = 0 → CPI null; team CPI always shown with the team's foreign-work share beside it (A20) | live | query time over the three curves | — |

## Report 16 — Cost matrix and foreign work (A8)

| Measure | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Team × domain cost | author team × domain × period | `started_at` | author (rows) | author | toggle TASK / EPIC, both as-was at `started_at`; a worklog logged on an epic takes the epic's own domain in both views (A21) | — | MD | UNASSIGNED row; EPIC view: a task with no epic uses its own domain | live | `fact_worklog.md`, `author_team_id`, `task_domain_key`, `epic_domain_key` | "fact_worklog - invariant 6"; "task_domain_key is never null (invariant 6 strengthened, commit 9d)" |
| Foreign-work share | team | `started_at` | author | author | — | — | % of the team's MD | **task-logged**: foreign = author team ≠ the task's sprint team at `started_at`; with no sprint team, ≠ the assignee's team at `started_at` (A21). **Epic-logged** (A22): foreign = author team ≠ the epic's own DOMAIN OWNER team (`dim_domain.owner_team_id`) — never the epic's assignee's team, since epics carry no sprint at all. Either side unknown → not foreign | live | `fact_worklog.foreign_work` (+ `assignee_team_id_at_started`) | `MetricsDerivationTest` "assignee_team_id_at_started, and foreign_work's task-vs-epic rule, match A21-A22"; "foreign_work is true when author and assignee teams differ, with no sprint team known at started_at (A21)" |

## Report 14 — Data quality (one row per finding; per team and per domain)

Populations: task findings count tasks with `done_at` in the period, plus — separately — currently
open started tasks; epic findings count epics open or done in the period.

| Finding | Grain | Anchor | Team | User | Domain | Estimate | Unit | Missing data | Frozen/live | Source | Pinned by |
|---|---|---|---|---|---|---|---|---|---|---|---|
| DONE tasks without worklogs (D14) | task | `done_at` | credit | assignee@done | TASK | — | items, % | sub-task worklogs roll up | live | `has_worklogs = false AND done_at IS NOT NULL` | — |
| Logged hours per member per working day | author × working day | `started_at` | author | author | — | — | h/wd vs `hoursPerDay` | member-days = `team_membership` × `dim_date.is_working_day`, at query time | live | Σ `fact_worklog.md` × `hoursPerDay` ÷ member-days | — |
| Late logging | worklog | `started_at` | author | author | — | — | days | `late_ms` null only when `created_at` is unknown; clamped ≥ 0 | live | `fact_worklog.late_ms` | "fact_worklog - invariant 6 (… late_ms …)" |
| Tasks without an estimate | task | see populations | credit / current | assignee | TASK | current, source NONE | items | — | live | `estimate_source = 'NONE'` | — |
| Tasks without an epic | task | see populations | credit / current | assignee | TASK | — | items | the per-domain `(no epic)` bucket | live | `epic_id IS NULL` | — |
| Tasks without a work category | task | see populations | credit / current | assignee | TASK | — | items | only when a work-category field is configured; own value, else the epic's (D8), as-is | live | `work_category IS NULL` | — |
| Unassigned DONE tasks | task | `done_at` | credit | — | TASK | — | items | — | live | `assignee_account_id_at_done IS NULL` | — |
| Epics without an own estimate | epic | see populations | owner | — | own | current | items | budget from children | live | `budget_source = 'CHILDREN'` | golden epic test |
| Epics without dates | epic | see populations | owner | — | own | — | items | no PV curve | live | `dim_epic.start_at IS NULL OR due_at IS NULL` | golden epic test |
| Epic drift (D11) | epic | now | owner | — | own | — | items + flags | an epic with no children never flags | live | `fact_epic_delivery.drift_flags` | `DeriveKernelsTest` "epicDriftFlags …" |
| Domains without an owner team (A19, A22) | domain | — | — | — | own | — | domains | their epics and backlog land in `UNOWNED` — including a domain whose ONLY configured owner is now soft-deleted, or whose several projects disagree on a configured owner | live | `dim_domain.owner_team_id IS NULL` | `MetricsDerivationTest` "owner team resolution (A19, A22) - a configured override, a disagreeing board pair, and a soft-deleted configured team" |
| Unmapped statuses | status; item | now | — | — | — | — | statuses, items | UNMAPPED time is never started/done | live | `norm.statuses` − `status_stage_map`; `item_stage.stage = 'UNMAPPED'` | "DERIVE flags a deliberately UNMAPPED status …" |
| Unmapped boards | board | — | — | — | — | — | boards, sprints | their sprints have no team and no snapshot; their tasks fall back to the assignee's team (D5) | live | `norm.boards` − `board_team_map` | — |
| Authors without a team | account | `started_at` | UNASSIGNED | author | — | — | accounts, MD | — | live | `fact_worklog.author_team_id IS NULL` | "fact_worklog - invariant 6" |
| Work done outside any sprint (D10) | task DONE | `done_at` | credit (assignee fallback) | assignee@done | TASK | @done | items + MD | tasks done in an unmapped-board sprint are listed under "unmapped boards", not here | live | `sprint_id_at_done IS NULL` | "D5 - the OPS Kanban project's DONE tasks …" |
| Sprint-snapshot drift (D13) | sprint × figure | `complete_at` | sprint | — | — | per figure | MD / items delta | live − frozen per figure (the seven MD figures, their item twins, capacity, load); non-zero → flagged | frozen vs live | `fact_sprint` − `fact_sprint_snapshot` | immutability only: "fact_sprint_snapshot … never updated afterwards", "reconstructed - …" |
| Cross-domain tasks | task | `done_at` | credit | assignee@done | both | — | items | as-was | live | `cross_domain` | `MetricsDerivationTest` "domain is AS-WAS at done_at, not the current project (A21)" |
| Derive warnings (A13) | connection × run | latest successful run | — | — | — | — | flag | the sprint step was skipped, nothing fabricated | live | `derive_runs.row_counts.sprintFieldUnresolved` → `deriveWarnings` | "… flagged sprintFieldUnresolved" |

## How this doc is kept true

- `domain-model.md` owns the meaning. When a row here and a sentence there disagree, change one of
  them deliberately in the same commit — never let the code silently decide.
- Every report commit (plan commits 10–18) fills the "Pinned by" cells of the rows it reads with the
  test that asserts the PERSISTED number or the endpoint's value, and drops the row's `planned`
  marker. A `—` after its report has landed is a gap, not a style choice.
- A kernel or derivation change that moves a cell (a new anchor, a changed exclusion) edits the row
  in the same commit.
- `MeasureContractTest` (server tests) parses these tables and fails when a "Pinned by" cell names a
  test class that does not exist.
