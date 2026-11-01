### Product history and donors

Read on demand when you need the version history or the sibling repositories Flow ports from; the binding one-liners ("Port, don't reinvent", the current version) stay in `CLAUDE.md`.

**Roadmap** (moved from `CLAUDE.md` "Product").

- **v0.1.0 — foundation.** Sign-in with email MFA, users, teams, feature flags, EN/PL,
  light/dark theme.
- **v0.2.0 — Jira ingestion.** An ADMIN-managed Jira Cloud connection (an Atlassian service
  account + a scoped read-only API token, encrypted at rest with `infra/crypto/FieldCipher`), a raw
  store with incremental cursors (`raw.*`), a neutral normalized layer above it (`norm.*` — facts
  only, no interpretation), the data profile and the admin pages over all of it
  (`.claude/docs/ingestion.md`).
- **v0.3.0 — the domain model, metrics, reports.** The model in
  `.claude/docs/domain-model.md`, implemented: metrics configuration, a `DERIVE` job building the
  `metrics` star from `norm` (`metrics/`), and the reports (`reports/`); Home first showed four unit-level tiles.
- **v0.4.0 — the Deep dive** (past stage; Home's overview below shipped with it as 1.0). Report 17 (A29, A40): plan, approximate EV and cost per task
  and day on a drillable epic/task × time matrix; nineteen reports on eighteen pages. **Home** became the overview of up to three picked teams (default: the ones you manage, then the rest by name; the pick is remembered per browser):
  one request (`GET /reports/home?teamId=…`, with its drill `home/items`), the freshness line and seven sections — last sprint, how big the work
  is, how well estimates hold (PRED), epics and tasks in progress, how much is delivered, what is missing from the data — as small
  multiples over the trailing 90 days with a trend against the 90 days before, every bar drillable, section titles linking to the
  full report for the same window, and the epic estimate gap tolerance as a shared metrics setting.
- **1.x (current: 1.44.0, `web/src/changelog/entries.ts` is the per-release record).** 1.0 the new Home; 1.1 Compare people (report 18, two people head
  to head across teams) and 1.4 Task estimates (report 19), 1.19 Person trend (report 20, one person's figures over rolling 90-day windows) - twenty reports in all; 1.3 Home's team pick and 1.2 its data-quality counts; 1.5 the
  Cost matrix by epic and 1.11 its revamp as **Costs** (sprint AC with capacity and the by-epic split in 1.13, per-team work-category targets in 1.12
  and the target-vs-actual share with its weekly cumulative trend in 1.14); 1.9 Commitments and Throughput split by epic per sprint and 1.10
  Throughput's Actual cost beside Delivered; 1.4.3 a capacity day belongs to one sprint of a team; 1.6 one Jira site for every data source, no project
  in two data sources; 1.7 reports are read for a team or a domain, never the whole unit (team-required filters; Deep dive asks for a domain first);
  1.8 "Users" became "Flow users"; 1.15 sign-in changes take effect on the next request and 1.16 the password reset is a confirmed link; 1.17 the ADMIN **telemetry panel** (Flow's own memory, storage, sync, DERIVE, request and JVM figures with 90-day trends; `web-features.md` "Telemetry panel"); plus the container memory / AppCDS work (`build-times.md`); 1.34-1.38 the self-managed GitLab connector (data sources; commits with per-file line counts and path exclusions; merge requests, notes as action codes, approvals and reviewer states; RECONCILE; the normalized code layer `norm.code_*` and the data profile's `gitlab` section; admin-confirmed identity links; `gitlab-integration.md`); 1.39 the Code activity endpoint and 1.40 its page (report 21, a team's merge-request flow over `norm.code_*` with the members' volumes as NEUTRAL context, A50; `reports.md` "Report 21"), 1.41 its drill, 1.42 the `CODE` rows of Compare people and Person trend, 1.43 the page's Unassigned choice, 1.44 Home's Code section (Report 21's own figures per picked team, `reports.md` "Home overview") - twenty-one reports on twenty pages. Next: the real-Jira first sync, then `BACKLOG.md`.

**Donors** (moved from `CLAUDE.md` "Donors").

Flow's repository was copied from Covenant and trimmed to its generic foundation — auth, users,
teams, feature flags, i18n, theming, quality gates — with the contract-catalog domain removed.
**Port, don't reinvent**: when Flow needs a capability one of these siblings already has, port its
implementation rather than designing a new one.

- **Covenant** (`~/Sources/covenant`, the primary donor) — this repo's entire foundation IS
  Covenant's scaffold. Its `toadie/` server package (an ADMIN-curated external-API connector:
  encrypted connection config, scoped read-only credentials, bounded paginated reads, a
  raw/derived cache split) is the template for Flow's own Jira connector in v0.2.0 — port its
  *shape*, not its GraphQL specifics.
- **Lettuce** (`~/Sources/lettuce`) — `@mantine/charts` + `recharts` for the flow-metrics
  dashboards; its WireMock teams-stub pattern for integration-testing an external API
  client without hitting the real service.
- **Toadie** (`~/Sources/toadie`) — the `UrlFetch` SSRF guard (public-host validation before any
  server-initiated outbound call) — forward guidance for the Jira client from v0.2.0; see
  `.claude/docs/security.md`.
