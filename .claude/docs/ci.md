### CI and static-analysis gate details

Read on demand before changing a CI job, a Dependabot rule or a static-analysis config; the commands a session runs stay in `CLAUDE.md` "Commands" and the binding one-line rules in `.claude/docs/testing.md`.

**CI** (moved from `CLAUDE.md` "Commands").

- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (a PR changing only Markdown, `measures.md` aside, skips the server job; server — incl. the OpenAPI
  coverage gate, strict Gradle dependency verification (`--dependency-verification strict`) plus a
  lock/verification-metadata drift check, and a HIGH/CRITICAL Gradle lockfile vulnerability scan,
  web — incl. the API-contract gate (`lint:api` + `check:api`), e2e statics —
  lint/knip/typecheck/scenario parity/setup, `npm audit` (high+) on both npm workspaces, `k8s-static` (kubeconform over
  `k8s/`), and on `master` an image build plus a Trivy scan of it); the blackbox Playwright
  suite (`e2e.yml`) runs nightly and on demand. Dependabot (`.github/dependabot.yml`) checks every
  workspace, Actions and container manifests weekly; `.claude/docs/dependencies.md` describes
  grouping, compatibility pins and runtime verification, and
  `.claude/docs/dependency-reproducibility.md` describes the Gradle lock/checksum mechanism itself.

**Static analysis and dead-code gates** (moved from `testing.md`).

**Static analysis (detekt).** `./gradlew detekt` runs detekt over `core` + `server` (plain rule
sets, no type resolution) and rides `check`, so `build` fails on any finding — the gate is zero
findings with **no baseline file**. Repo tuning lives in `config/detekt/detekt.yml`, layered on
the bundled defaults; every override there carries a one-line comment naming the deliberate idiom
it protects (wildcard Ktor imports, the flat feature-package layout, declarative `*Routes.kt`
registrars, the validation-throw convention, guard-clause returns). Fix new findings in code
first; extend the config only for a genuinely deliberate idiom, and prefer a config override over
`@Suppress` (a per-site `@Suppress` needs a one-line justifying comment). Runs in seconds, no
Docker — safe to run anytime, unlike the test suite.

**Frontend static analysis (sonarjs + knip).** The SPA's counterpart, same
zero-findings/no-baseline policy: `cd web && npm run lint` carries `eslint-plugin-sonarjs`
(recommended set) plus core size/complexity backstops tuned generously for React's
one-function-per-page architecture (`cognitive-complexity` 40, `complexity` 50 — backstops
against future monsters, not targets); every override in `web/eslint.config.js` carries the idiom
comment. `cd web && npm run knip` is the dead-code gate (unused files/exports/dependencies; test
files count as entries, so a flagged export is unused even by tests) — the generated
`src/api/schema.ts` is excluded (type-checked by `tsc`, not style-linted).

**Running the full stack** (moved from `CLAUDE.md`; the ports and the one-liners stay there).

`docker compose up --build` serves everything at `http://localhost:8084` (sign in as
`admin@flow.local` — see the README for where the initial password comes from); local dev is
`docker compose up postgres mailpit` (Postgres on host port **5435**) + `./gradlew :server:run`
(API on **8084**) + `cd web && npm run dev` (Vite on **5176**, proxying `/api` to :8084), each in
its own terminal. The compose stack bundles **Mailpit** (`http://localhost:8028`) and wires the
app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately avoid
Lettuce's (8080/5432/5173/8025), Toadie's (8081/5433/5174/8026) and Covenant's (8082/5434/5175/8027)
so every sibling stack can run side by side; host ports bind to 127.0.0.1 only. Kubernetes
(OrbStack) deployment targets the dedicated `flow` namespace — see `k8s/secret.yaml`'s header for
the secret-creation command.
