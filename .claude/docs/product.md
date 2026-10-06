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
  `metrics` star from `norm` (`metrics/`), and the reports (`reports/`); Home is the unit overview.
- **v0.4.0 (this codebase) — the Deep dive.** Report 17 (A29): plan, execution and cost per task
  and day on a drillable epic/task × time matrix; seventeen reports on sixteen pages. Next: the
  real-Jira first sync, then `BACKLOG.md`.

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
