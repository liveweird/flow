# Dependency maintenance

Dependency declarations and lockfiles are the source of truth. Keep Gradle and the two npm
workspaces (`web/`, `e2e/`) independent; do not upgrade to the newest major merely because it is
available.

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
