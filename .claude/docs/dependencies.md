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
exact JDK pin in `mise.toml`, Ktor's imported catalog in `settings.gradle.kts`, the pinned
PostgreSQL image literal in `server/src/test/kotlin/PostgresTestSupport.kt`, and the runtime
versions actually used by cached images and local tools. Compare official release metadata, not
just open PRs.

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
