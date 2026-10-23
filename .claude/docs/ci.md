### CI and static-analysis gate details

Read on demand before changing a CI job, a Dependabot rule or a static-analysis config; the commands a session runs stay in `CLAUDE.md` "Commands" and the binding one-line rules in `.claude/docs/testing.md`.

**CI** (moved from `CLAUDE.md` "Commands").

- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (job ids `changes`, `server`, `gradle-vulnerability-scan`, `web`, `e2e-static`, `k8s-static`, `images`, and the failure-only `images-alert`; the `changes` job lets a PR changing only Markdown, `measures.md` aside, skip `server`; `server` — incl. the OpenAPI
  coverage gate, strict Gradle dependency verification (`--dependency-verification strict`) plus a
  lock/verification-metadata drift check, `gradle-vulnerability-scan` (a HIGH/CRITICAL Gradle lockfile vulnerability scan),
  `web` — incl. the API-contract gate (`lint:api` + `check:api`), `npm run lint` over a restored ESLint result cache (`web/.eslintcache` via `actions/cache`, keyed on the lockfile + `eslint.config.js` + the commit with a prefix restore, so a PR re-lints only the files it changed; `build-times.md` WHY 20) and the coverage run in shuffled order
  (`npm run test:coverage -- --sequence.shuffle --sequence.seed=<seed>`, which is also the `isolate: false` independence proof; `scripts/gates.sh web` mirrors it with a
  random seed it prints; CI picks the seed itself and writes it, with the replay command, to the job summary before the run, so a shuffle-order failure can be reproduced), `e2e-static` —
  lint/knip/typecheck/scenario parity/setup, the toolchain-pins check, `npm audit` (high+) on both npm workspaces, `k8s-static` (kubeconform over
  `k8s/`, and the web deployment pinned to one replica), and on `master` `images`, an image build plus a Trivy scan of it); the blackbox Playwright
  suite (`e2e.yml`) runs nightly and on demand, and re-scans the image it built with the same Trivy gate (its "Build images" step is a plain `docker compose build`, like `images`: the layer cache it used to restore cost more than it saved, `build-times.md` WHY 22). Dependabot (`.github/dependabot.yml`) checks every
  workspace, Actions and container manifests weekly; `.claude/docs/dependencies.md` describes
  grouping, compatibility pins and runtime verification, and
  `.claude/docs/dependency-reproducibility.md` describes the Gradle lock/checksum mechanism itself.

**Concurrency.** `ci.yml` cancels a superseded run only for `pull_request` events (`cancel-in-progress: ${{ github.event_name == 'pull_request' }}`); a push to
`master` runs in a group of its own (the run id), because inside one shared group GitHub keeps a single pending run and drops the older pending one even without
`cancel-in-progress` — every master commit (so every release commit) is fully verified. `e2e.yml` (nightly/on demand) declares no `concurrency`.

**Failure alerting** (checkup 3, D5-1/D8-5; nightly + alerting was chosen over a PR-time e2e subset, to keep PR CI fast).

- **Where and when.** `e2e.yml` job `alert` runs `if: failure() && github.event_name == 'schedule'` (the nightly only; an on-demand `workflow_dispatch` run stays quiet because its author is watching) and `ci.yml` job `images-alert` runs
  `if: failure() && github.event_name == 'push' && github.ref == 'refs/heads/master'`; each `needs` the job it watches, so it is skipped (not run) on every green or cancelled run and adds nothing to a gate's time. No other job is watched.
- **One reusable piece.** Both call `.github/workflows/ci-alert.yml` (`workflow_call`, input `title`; no third-party action, only the runner's `gh`). The calling job grants `permissions: issues: write` with the built-in `GITHUB_TOKEN`; the workflows' default stays `contents: read` and no secret is involved.
- **Dedupe.** The label `ci-failure` (created with `gh label create --force`) plus an exact title, one issue per workflow: "Nightly e2e is failing" and "master images job is failing". An OPEN issue with that label and title gets a comment (run URL, date, head SHA, trigger); otherwise the issue is opened with the same body. Every context value reaches the script through `env:` (script-injection rule); no commit message, branch name or PR title is used.
- **Closing is manual.** Nothing closes the issue automatically, not even a green run: close it once the cause is understood, and the next red run opens a fresh one. A red `alert` job (e.g. a token problem) is itself a failed run.

**Local gates vs CI (`scripts/gates.sh`).** The script runs `server-build`, `web-gates` and `e2e-static` (lint, knip, typecheck, scenario parity, `npm run test:setup` and
`node scripts/check-toolchain-pins.mjs`, the same cheap checks as the CI `e2e-static` job). **CI-only scans**, deliberately not in the script (the header comment of
`gates.sh` lists them too): `npm audit` on both workspaces and the HIGH/CRITICAL Gradle lockfile vulnerability scan (they read a live advisory database and the network, and
their result changes overnight without a commit), kubeconform in `k8s-static` (a downloaded checksum-pinned binary), and the Trivy / AppCDS image checks (need a built image).

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
`src/api/schema.ts` is excluded (type-checked by `tsc`, not style-linted). `web/scripts/*.mjs` (the build-time Node scripts, e.g. `check-api.mjs`) are linted too —
`js.configs.recommended` + sonarjs with Node globals, a separate block in `web/eslint.config.js`.

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

**AppCDS (class-data sharing; `.claude/docs/build-times.md` WHY 27).**

- The Docker build dumps `/app/cds/flow.jsa` from the committed `server/cds/classlist.txt` (+~5 s per image build, +107 MB / ~27 MB gzipped per image); Trivy scans the same image as before.
- The `images` job then runs `scripts/check-cds-archive.sh flow-app` (the image's launcher with `-XX:+PrintSharedArchiveAndExit`, under a second, no database): it fails when the archive does not fit the runtime
  (a changed jar, `-XX:-UseCompressedOops`/`ObjectAlignmentInBytes` added to `applicationDefaultJvmArgs`, another JDK) or is not wired, because such an image starts silently WITHOUT the archive and slower than before.
- `scripts/cds-classlist.mjs` regenerates the class list from a real run in its own throwaway compose project `flow-cds-train` (ports 8588/5939/8532/8598); it builds its own image and refuses to overwrite the list from an unhealthy run.
