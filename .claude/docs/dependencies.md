# Dependency maintenance

Dependency declarations, lockfiles and `gradle/verification-metadata.xml` are the source of truth.
Keep Gradle and the two npm workspaces (`web/`, `e2e/`) independent; do not upgrade to the newest
major merely because it is available. `.claude/docs/dependency-reproducibility.md` describes the
Gradle lock/checksum mechanism itself (what is covered, the hard empty-`GRADLE_USER_HOME` rule for
regenerating verification metadata, and how to review a regenerated file) — this doc stays focused
on the maintenance workflow: grouping, compatibility pins, and acceptance checks.

## Update automation

`.github/dependabot.yml` checks Gradle, each npm workspace, GitHub Actions, Dockerfiles,
Kubernetes image references, and Compose weekly. Ordinary minor/patch updates are grouped by
workspace. Vitest and its coverage plugins form a separate group so their exact-version peers move
together. The Gradle bulk group excludes Exposed and the Netty/Reactor/OpenTelemetry families:
their pins need individual compatibility review before integration. Exclusion from a group does
not ignore an update or exempt it from review.

Dependabot vulnerability alerts and security update PRs are enabled in the GitHub repository
settings. These are separate from the version-update configuration. Security updates still need
the normal checks; never auto-merge a breaking migration to clear an alert.

Dependabot does not cover every declaration here. At each monthly maintenance pass, inspect the
exact JDK pin in `mise.toml`, Ktor's imported catalog in `settings.gradle.kts`, and the runtime
versions actually used by cached images and local tools. Compare official release metadata, not
just open PRs. The CI toolchain pins (`.nvmrc`, the `java-version` in `ci.yml`, the wrapper checksum,
the kubeconform version/sha) are in that same manual list — see "CI toolchain pins and scans".

**The PostgreSQL image is pinned in THREE places that must move together:** `docker-compose.yaml`,
`k8s/postgres-deployment.yaml` and `PostgresTestSupport.IMAGE`
(`server/src/test/kotlin/PostgresTestSupport.kt`). Dependabot only sees the first two, and as
separate PRs (the `docker-compose` and `docker` ecosystems cannot share a group; the `postgres`
group in `dependabot.yml` merges the `/` and `/k8s` `docker` directories at most). So a Postgres
tag/digest bump lands in ONE hand-assembled PR touching all three literals — close or redo
Dependabot's split PRs into it, re-deriving the digest per the provenance rule below. The
Testcontainers literal is no longer a monthly manual inspection item: `PostgresImagePinTest`
asserts it equals the compose and k8s `image:` values, so a partial bump fails the build.

## Compatibility boundaries

- Keep TypeScript on the supported shared major across web and E2E. TypeScript 7 needs a
  coordinated migration: the current ESLint tooling requires TypeScript below 6.1, and
  `openapi-typescript` uses the compiler API and declares TypeScript 5. The existing web
  `--legacy-peer-deps` exception permits TypeScript 6; it is not permission to bypass arbitrary
  peer conflicts. In particular, all Mantine packages must use the same release.
- Match `@types/node` to the Node runtime major. Keep production on an LTS line; newer types can
  expose APIs missing at runtime even if the build passes.
- Upgrade React/React DOM and their type packages together. Upgrade Vitest and
  `@vitest/coverage-v8` together and validate coverage without lowering floors.
- Read the current rationale in `gradle/libs.versions.toml` before moving Exposed,
  Netty/Reactor Netty, or OpenTelemetry. Inspect published BOMs/POMs as well as release notes.
  `:server:checkDependencyAlignment` must pass, but alignment alone does not prove compatibility.

## Runtime and image verification

Keep PostgreSQL on the same tested major/minor across Compose, Kubernetes and Testcontainers.
External images use exact release tags and reviewed multi-platform digests where available;
refresh both together. The tag describes the intended version, while the digest selects bytes. A
local rebuild without an updated base digest does not pick up security fixes. Verify
`java -version`, `node --version`, and `postgres --version` inside the selected images when
changing runtime pins. A `mise` version identifier may differ from the Java version it installs;
record the actual binary version when verifying a JDK patch.

Use the build-specific local Kubernetes image workflow in `.claude/skills/run-stack/SKILL.md`. Do
not redeploy a mutable `latest` tag and assume every node has the same image. Never remove
development database volumes as part of an update.

## Acceptance checks

Refresh each changed workspace from its committed lockfile. For Gradle changes, regenerate the
project and buildscript locks with the command below and review their diff. Run each changed
workspace's documented lint, dead-code, type, test/coverage and build gates; regenerate frontend
API types and confirm there is no unexplained schema drift. Run `npm audit` in both workspaces and
distinguish runtime advisories from development-only exposure. For Gradle changes, run the
complete build and alignment gate on the pinned JDK; database changes need a fresh Testcontainers
database and the complete server suite, including concurrency cases.

Build the application image, verify health, and run the full Playwright journeys after
cross-stack updates. Existing Dependabot PR checks are evidence for their recorded commit only;
refresh against current master before merging. A version check or npm audit is not a comprehensive
JVM/container vulnerability scan.

Regenerate the Gradle project/buildscript/plugin lockfiles with:

```
./gradlew :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment \
  :dependencies :core:dependencies :server:dependencies --write-locks
```

then review the diff; do not hand-edit generated locks. `.github/workflows/ci.yml`'s
`gradle-vulnerability-scan` job scans all seven Gradle lockfiles (root/settings/core/server,
project + buildscript) with Trivy and fails on a HIGH or CRITICAL advisory; the Docker build
copies the locks before resolving server dependencies.

A dependency or plugin change also needs `gradle/verification-metadata.xml` regenerated in the
same commit — Gradle's separate SHA-256 checksum gate on every resolved artifact. This step has a
hard rule: it **must** run from an empty `GRADLE_USER_HOME` (a warm one silently omits checksums
for anything already cached), so it is a separate command from the plain `--write-locks` one above,
not an extra flag tacked onto it:

```
export JAVA_HOME=$(mise where java)
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 \
  :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment \
  :dependencies :core:dependencies :server:dependencies \
  build :server:installDist
```

Then validate from a second, also-empty `GRADLE_USER_HOME` with
`./gradlew --dependency-verification strict build` before committing. See
`.claude/docs/dependency-reproducibility.md` for what this covers, why the task list must resolve
every configuration CI and the Docker build resolve, and how to review the generated checksums.
`.github/workflows/ci.yml`'s `server` job checks `gradle/verification-metadata.xml` is present,
runs `--dependency-verification strict`, and diffs it (with the lockfiles) after the build; the
Docker build's `server` stage copies the whole `gradle/` directory, so it picks up the file
automatically once it exists.

## CI toolchain pins and scans (checkup A11, 2026-09-30)

`.github/workflows/ci.yml` and `e2e.yml` run on the toolchain the repo was verified on, and scan what
ships. Dependabot covers none of the pins in the first three rows — bump them by hand, together.

| Pin / gate | Where | Bump rule |
|---|---|---|
| JDK `21.0.12` (`actions/setup-java` `java-version`, both Gradle jobs) | `ci.yml` | the exact patch of `mise.toml`'s `java` and the Dockerfile's `eclipse-temurin:21.0.12_8` tags — one PR moves all three. `node scripts/check-toolchain-pins.mjs` (an `e2e-static` step) fails the build with one line per mismatch if the JDK or node pins diverge |
| Node `24.21.0` (`node-version-file: .nvmrc`, web/e2e-static/e2e jobs) | `.nvmrc` | mirrors `mise.toml`'s `node` and the Dockerfile's `node:24.21.0-alpine` (guarded by the same script); `.nvmrc` holds the bare version, no `v` |
| Gradle `distributionSha256Sum` | `gradle/wrapper/gradle-wrapper.properties` | the wrapper validates the downloaded distribution against it (CI, local, and the Docker build's `./gradlew --version`). On a Gradle bump take the value from `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256` (the `bin` type the URL uses), confirm it against a `shasum -a 256` of the downloaded zip, and change URL and checksum in the same commit |
| `npm audit --omit=dev --audit-level=high` | `ci.yml` `web` | runtime dependencies only (a dev-tool advisory never reaches the bundle). The LAST step of the job, so an overnight advisory cannot hide the real gate results |
| `npm audit --audit-level=high` | `ci.yml` `e2e-static` | every e2e dependency is a dev tool, so the whole tree. Also the last step |
| `trivy image` (HIGH/CRITICAL, `--ignore-unfixed`, exit 1) | `ci.yml` `images` (master only) | the same digest-pinned `trivy:0.74.0` as the Gradle lockfile scan; run against the compose-built `flow-app`. A red step is a fixable OS-package or JAR advisory: refresh the base-image digests (see "Runtime and image verification") or the dependency. It cannot be waived per-CVE without a `.trivyignore` with a dated justification — there is none |
| `kubeconform v0.8.0` (`-strict`, Kubernetes 1.33.0) | `ci.yml` `k8s-static` | the tarball is pinned by sha256 in the workflow (`CHECKSUMS` file of the release); bump version and sha together. Kubernetes version follows the local OrbStack cluster |
| `docker/setup-buildx-action`, `actions/cache` | `e2e.yml` (the `images` job dropped both on 2026-10-01 — WHY 9) | SHA-pinned like every action; Dependabot's `github-actions` ecosystem updates them |
| BuildKit `moby/buildkit:v0.33.0@sha256:6c2fa84a…` (`driver-opts: image=…` on the `setup-buildx-action` use) | `e2e.yml` | the builder image is otherwise pulled floating. Not Dependabot-covered: at a bump take the current stable tag and re-derive the index digest (`docker buildx imagetools inspect moby/buildkit:<tag> --raw \| sha256sum`) |
| `kubeconform -schema-location` (`yannh/kubernetes-json-schema@8df8a883…`) | `ci.yml` `k8s-static` (`KUBECONFORM_SCHEMAS`) | the schema repo is pinned to a commit so an upstream schema change cannot redden the job; move it with the kubeconform version |

The npm audits are a moving gate: a newly published advisory can turn an unrelated PR red. That is the
point — fix the dependency, or (if it is a false positive for how the package is used) record a
justified, dated exception here and adapt the audit step in the same PR (`npm audit` has no built-in
allow-list); never lower `--audit-level` to get green. Measured state on 2026-09-30: `npm audit` reports 0 vulnerabilities in
`web` (full and `--omit=dev`) and in `e2e`; the locally built image scans clean (no fixable
HIGH/CRITICAL) — those are the baselines the gates started from.

**Image build cache.** Only the nightly `e2e` job builds the app image through
`docker compose -f docker-compose.yaml -f .github/compose-buildx-cache.yaml build` on a `docker-container`
BuildKit builder (`docker/setup-buildx-action`), with a `type=local` layer cache persisted by
`actions/cache` (the `type=gha` backend needs runtime tokens a `run:` step does not receive). The cache key
hashes the dependency-shaped inputs (Dockerfile, all seven Gradle lockfiles, the Gradle build scripts,
`gradle.properties`, the version catalog, `verification-metadata.xml`, the wrapper properties,
`web/package.json` + `package-lock.json`), so it is rewritten only when one of them changes. Measured locally:
a cold build 2m08s, a rebuild with the warm cache and one changed server source file 1m32s; the cache is
~1 GB (`mode=max`) and the Gradle `installDist` layer is rebuilt whenever a source file changes (dependency
download and compile share one layer), so the gain is modest. **The `ci.yml` `images` job no longer uses it
(2026-10-01):** on the 4-vCPU runner the restore, import, export (discarded on every key hit), teardown and
image load cost ~60 s against ~10 s saved — a plain `docker compose build` measured 133 s against 170 s + 20 s
with the cache, `.claude/docs/build-times.md` WHY 9. The nightly keeps it until WHY 7's data says otherwise.

## Buildscript advisory follow-up (2026-09-26)

CI scans reported CVE-2026-84939 in the root plugin classpath's transitive
`org.freemarker:freemarker` 2.3.32 (caught in Covenant first). A root buildscript constraint selects
Apache FreeMarker 2.3.35, and the regenerated root buildscript lockfile records that version. This is a
build-time dependency; it is not packaged in the application runtime.

## Lessons ported from Toadie's Dependabot rounds (2026-09-26)

Sibling project Toadie hit these in its own weekly Dependabot rounds; they generalize to any
workspace here running the same tools:

- **A `knip` bump can add or drop findings with no change to this repo's code.** Toadie's 6.36
  started counting an export used only inside its own declaring file as unused, and separately
  raised a "Configuration hint … Remove from ignore" finding for an `ignore` entry that no longer
  suppressed anything. Read the whole `npm run knip` output on a Dependabot `knip` bump, not just
  a `grep knip` on the CI log — the failure can hide behind an unrelated-looking hint line. Fix by
  dropping the now-needless `export` keyword or `knip.json` ignore entry; do not silence the new
  rule. Flow's own `knip.json` carried exactly this stale entry (`ignore: ["src/api/schema.ts"]`)
  by the time this section was written — `schema.ts` is now fully consumed, so the entry was
  removed rather than kept as a no-op.
- **A required "Quality gate" style status check makes Dependabot PR merges serial, not
  parallel.** If branch protection requires every PR to contain the target branch's current HEAD,
  update-branch a Dependabot PR, wait for its checks, merge, then update-branch the next one —
  merging cheapest/most-likely-green first keeps a later red PR attributable to its own diff
  rather than a stale base. A config-only PR (`.github/**`) that doesn't change a Docker image can
  have its downstream image rebuild run in parallel with its own CI, since nothing about the image
  input changed.

## New dependencies (v0.2.0 plan commit 4 — the Jira HTTP client)

- `io.ktor:ktor-client-core` / `io.ktor:ktor-client-okhttp` (`ktorLibs.client.core`/`.okhttp`,
  main): the Jira Cloud client (`jira/JiraHttp.kt`, `jira/JiraClient.kt`) runs Ktor's `HttpClient`
  over the OkHttp engine specifically so `infra/outbound/OutboundGuard.kt`'s guarded
  `okhttp3.OkHttpClient` (the `Dns` pin, no redirects/proxy/connection-failure-retry) can
  preconfigure the transport (`engine { preconfigured = ... }`) — OkHttp's `Dns` hook is the clean
  way to port Toadie's resolve-check-connect pinning into a long-lived client (plan §3).
- `io.ktor:ktor-client-mock` (`ktorLibs.client.mock`, test-only): `JiraClientTest` scripts
  `JiraHttp`/`HttpJiraClient` against a `MockEngine` — no network, no WireMock needed for
  unit-level status/retry/paging/auth-header coverage.
- `org.wiremock:wiremock-standalone` (`libs.wiremock.standalone`, test-only, pinned to the SAME
  version as the compose `jira-stub` image tag): `JiraStubServer.kt` runs the real
  `sample-data/jira-stub` mappings in-JVM for `DataSourceTestConnectionTest`. The `-standalone`
  shaded artifact was chosen specifically because it bundles its own relocated Jackson/Jetty
  rather than exposing them as ordinary `com.fasterxml.jackson`/`org.eclipse.jetty` coordinates —
  confirmed empirically after adding it: `:server:checkDependencyAlignment` still reports the
  Jackson family aligned at 2.22 with no new members, and the lockfile gained only one small
  `org.eclipse.jetty.alpn:alpn-api` artifact (OkHttp's optional ALPN support, harmless). No Jackson
  constraint was needed.

## Dependency batch 2026-09-26

Weekly Dependabot round, applied on `chore/deps-2026-09-26` from `origin/master` (1eb7de7).

**Moved:**

- Gradle (`gradle-minor-patch` group, PR #11): `r2dbc-postgresql` 1.1.2.RELEASE → 1.1.3.RELEASE,
  `scram` (client/common) 3.3 → 3.4, `jackson` 2.22.2 → 2.22.3 (catalog pin, plus the mirrored
  `server/build.gradle.kts` buildscript constraints for `jackson-core`/`jackson-databind`/
  `jackson-datatype-jsr310`), and the same PR's buildscript-only bumps `log4j-api`/`log4j-core`
  2.25.5 → 2.26.1 and `plexus-utils` 4.0.3 → 4.1.0 (Ktor plugin's Shadow/Jib build-time classpath,
  not the application runtime).
- Gradle (PR #13): `netty` 4.2.17.Final → 4.2.18.Final. `reactor-bom` stayed at 2025.0.7 (its
  pinned release train already carries `reactor-netty-core` 1.3.7, built on Netty 4.2.18 — no
  reactor-side bump needed); `:server:checkDependencyAlignment` confirms `io.netty aligned at
  4.2.18.Final (31 modules)` and `io.projectreactor aligned with reactor-bom 2025.0.7`.
  Lockfiles/verification metadata regenerated from an empty `GRADLE_USER_HOME`; a clean-home
  `--dependency-verification strict build` passed.
- Docker (PR #10, #1): `postgres:18.6-alpine` digest `sha256:6c538e72...` →
  `sha256:77f58511...` in both `k8s/postgres-deployment.yaml` and `docker-compose.yaml` (same tag,
  refreshed multi-platform index digest — independently re-derived via
  `docker buildx imagetools inspect postgres:18.6-alpine --raw | sha256sum` before pinning, per
  this doc's provenance rule).
- npm `/web` (PR #5) and `/e2e` (PR #3): `knip` 6.37.0 → 6.38.0 in both workspaces. Read the full
  `npm run knip` output per this doc's own lesson above — no new or dropped findings in either
  workspace this round.

**Held back:**

- **OpenTelemetry SDK (PR #12, 1.65.0 → 1.66.0) — held.** The SDK/instrumentation pair rule in
  `gradle/libs.versions.toml` requires reading the paired SDK version off the instrumentation
  alpha BOM's POM before bumping either. The instrumentation BOM's latest published release is
  still `2.31.1-alpha` (Maven Central `maven-metadata.xml`, last updated 2026-08-23), and its POM
  still declares SDK `1.65.0` — there is no instrumentation release yet that pairs with SDK
  1.66.0. Bumping the SDK alone would break the pair `:server:checkDependencyAlignment` guards.
  Re-attempt once a `2.32.x-alpha` (or later) instrumentation BOM ships pairing with 1.66.0.
- **eclipse-temurin major (PR #2, 21 → 24)** — ignored going forward (JDK 21 LTS policy; matches
  `jvmToolchain(21)` and `mise.toml`'s pinned JDK).
- **node major (PR #8, 24.21.0 → 26.10.0)** — ignored going forward (Node 24 LTS line, pinned in
  `mise.toml`).
- **`@types/node` major, `/web` (PR #7) and `/e2e` (PR #6), 24.13.6 → 26.6.2** — ignored going
  forward in both workspaces (tracks the Node major actually running in production).
- **`typescript` major, `/e2e` (PR #4), 6.0.3 → 7.0.2** — ignored going forward in `/e2e`, matching
  the existing `/web` ignore (same `openapi-typescript`/`typescript-eslint` compatibility reason —
  `openapi-typescript` still declares TypeScript `^5` and drives compiler-factory APIs TypeScript 7
  removed).

`.github/dependabot.yml` gained one `ignore` entry per held-back major above (docker
`eclipse-temurin`/`node` semver-major; npm `@types/node` semver-major in `/web` and `/e2e`; npm
`typescript` semver-major in `/e2e`), each with a one-line rationale comment, so these stop
recurring until deliberately revisited.

## New dependencies (v0.3.0 M4 commit 11 — the reports SPA)

All four are `/web` runtime dependencies, installed with `npm install --legacy-peer-deps` (the
existing web exception: recharts' React peers are tolerated the same way `openapi-typescript`'s
TypeScript peer is).

- `@mantine/charts` (**`9.6.2`, exact**) — the reports' `BarChart` (later `LineChart`/`AreaChart`).
  Pinned to `@mantine/core`'s release, exactly like `@mantine/spotlight`: **every `@mantine/*` package
  must sit on one version** — a chart/date package a minor behind core renders unstyled or throws on a
  changed prop contract. The `web-minor-patch` Dependabot group already batches every `@mantine/*`
  bump into one PR, so they move together; check the resulting lockfile diff shows one Mantine version.
- `recharts` (`^3.10.1`) — `@mantine/charts`' rendering engine (a peer it does not bundle). ~400 kB:
  it must only ever enter **lazy chunks** (`components/VelocityChart.tsx` is dynamically imported by
  its page; `npm run build` shows it as its own `VelocityChart-*.js` chunk, and the main `index-*.js`
  stays free of it). Each chart component imports `@mantine/charts/styles.css` itself — without it
  the tooltip renders unstyled.
- `@mantine/dates` (**`9.6.2`, exact**) — the reports' custom-range `DatePickerInput` (phase-3 plan
  §2.4). Its `styles.css` is imported by `components/ReportFilterBar.tsx` (a lazy-page chunk), and it
  follows the same one-version rule as above.
- `dayjs` (`^1.11.23`) — `@mantine/dates`' peer, and the source of the Polish calendar locale
  (`dayjs/locale/pl`, loaded by the filter bar; a third UI language would need its dayjs locale added
  there too).
- **`react-is` override** (`web/package.json` `"overrides": { "react-is": "^19" }`, resolves to
  19.3.0): under React 19 the tree otherwise kept `react-is@17.0.2` (pulled by
  `@testing-library/dom`'s `pretty-format` and `prop-types`), and recharts imports `isFragment` from
  `react-is` — which must match React's major. `npm ls react-is` must show one version; keep the
  override matched to React's major when React moves.
