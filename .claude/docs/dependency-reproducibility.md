# Gradle dependency reproducibility

The Gradle build uses two complementary, fail-closed controls:

- dependency locking pins the selected module versions in `gradle.lockfile`, `core/gradle.lockfile`
  and `server/gradle.lockfile`; each project's buildscript classpath is locked separately in
  `buildscript-gradle.lockfile`, `core/buildscript-gradle.lockfile` and
  `server/buildscript-gradle.lockfile`; the imported Ktor version catalog (and the settings-plugin
  coordinate resolved through the root project's `settingsPluginAudit` configuration) is pinned in
  `settings-gradle.lockfile`; and
- dependency verification checks the SHA-256 digest of every resolved external artifact and its
  metadata against `gradle/verification-metadata.xml`.

Gradle enables dependency verification automatically when the verification metadata file exists.
Do not run builds with `--dependency-verification lenient` or `off`, and do not add trusted-artifact
patterns or configuration exclusions. A missing checksum or a checksum mismatch is a build failure
that must be investigated.

This process deliberately does not cover GitHub Actions dependencies — those are SHA-pinned in the
workflow files and bumped via `.github/dependabot.yml`; see "Dependabot's Gradle bump" in
`.claude/docs/dependencies.md`.

## What is covered

Strict locking and verification apply to every resolvable project configuration in the root,
`core` and `server` projects: the JVM compile, runtime, test, Detekt, Kover, Kotlin compiler, and
application distribution (`:server:installDist`) inputs. `core` currently has only the JVM target;
adding another target requires regenerating locks and verification metadata on every supported host
and reviewing whether host-specific state is needed.

Project dependency locking does not apply to the plugins DSL or to buildscript classpaths on its
own, so root, `core` and `server` each activate locking explicitly for their `buildscript {
configurations.classpath { resolutionStrategy.activateDependencyLocking() } }` block — that is why
there are three separate `buildscript-gradle.lockfile`s alongside the three project ones. The root
buildscript currently carries one constraint (`org.freemarker:freemarker` above CVE-2026-84939 —
see `.claude/docs/dependencies.md`'s "Buildscript advisory follow-up"); if another buildscript
dependency is introduced elsewhere, activate locking for its `classpath` configuration too.

Gradle's settings-plugins DSL (the `foojay-resolver-convention` plugin applied in
`settings.gradle.kts`) has no project-scoped strict-lock API of its own. Flow resolves that
coordinate through a dedicated `settingsPluginAudit` configuration on the root project
(`:verifySettingsPluginAudit` in `build.gradle.kts`), which rides the same `allprojects {
dependencyLocking { lockAllConfigurations() } }` block as everything else the root project
resolves, so its lock state lands in the root project's own lockfile. Global dependency
verification covers this configuration, the Ktor version catalog, project plugins, and any future
buildscript artifacts the same way it covers ordinary dependencies.

Verification also covers POM and Gradle module metadata because `verify-metadata` is enabled in
`gradle/verification-metadata.xml`'s `<configuration>` block. It does not cover locally produced
project artifacts (the `:core`/`:server` jars), changing modules such as snapshots, the Gradle
distribution, or downloaded Java toolchains. The wrapper distribution has its own SHA-256 pin in
`gradle/wrapper/gradle-wrapper.properties`; CI and the Docker build both supply JDK 21 separately
(`mise.toml` locally, `eclipse-temurin` base images in CI/Docker).

## Routine builds

Normal commands enforce both controls without extra flags:

```bash
./gradlew build
./gradlew :server:installDist
```

Strict lock mode fails when a resolvable configuration has no lock state, has unexpected modules,
or resolves a version that differs from its lock. Dependency verification fails before an
unapproved or modified external artifact can be used. `.github/workflows/ci.yml`'s `server` job
additionally passes `--dependency-verification strict` explicitly (belt-and-braces on top of the
automatic enforcement) and diffs the lock/verification files after the build to catch drift a
contributor forgot to commit.

## Regenerating verification metadata

**`gradle/verification-metadata.xml` must be generated from an empty `GRADLE_USER_HOME`.** A warm
or reused Gradle home already has some artifacts sitting in its module/metadata cache from an
earlier run, and Gradle only records a checksum for what it actually has to fetch and hash while
`--write-verification-metadata` is active — entries for anything already cached silently never make
it into the file. The result looks complete, passes locally against that same warm cache, and then
fails the first clean checkout or Docker build with a missing-checksum error for exactly the
artifacts the warm cache had. Regenerating from a fresh `mktemp -d` every time is the only way to
guarantee every resolved artifact gets hashed.

The task list must resolve every configuration CI and the Docker build resolve, matching the
lockfile-regeneration command in `.claude/docs/dependencies.md`:

```bash
export JAVA_HOME=$(mise where java)
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 \
  :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment \
  :dependencies :core:dependencies :server:dependencies \
  build :server:installDist
```

`:verifySettingsPluginAudit` and the three `buildEnvironment` tasks resolve the settings-plugin
coordinate and each project's buildscript classpath; the three `dependencies` tasks resolve every
resolvable configuration of each project (compile, runtime, test, Detekt, Kover, and distribution
inputs alike — the dependency report task walks all of them, not just one); `build` and
`:server:installDist` then actually execute the same tasks CI's `server` job and the Dockerfile's
`server` stage run, as a second, execution-driven pass over the same graph. Adding a new
resolvable configuration (a new subproject, a new Gradle or Kotlin plugin, a new task with its own
classpath) means adding its resolving task to this list too — otherwise its artifacts silently
never get a recorded checksum and the very first clean build fails.

Validate the result from a second, also-empty `GRADLE_USER_HOME` before committing:

```bash
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --dependency-verification strict build
```

A clean pass here, not the generation run above, is the actual proof the metadata is complete: the
generation run can reuse warm project build-output state (`core/build/`, `server/build/`) left over
from earlier local builds and skip re-resolving a configuration nothing changed, even against a
fresh `GRADLE_USER_HOME`.

## Intentional dependency changes

Regenerate locks and verification metadata together, review every generated change before
committing it:

1. Confirm lockfile additions, removals, and version changes match the requested update.
2. Confirm checksum entries are limited to the expected new artifacts and metadata.
3. Establish new checksums from an independently authenticated publisher checksum, signature, or
   release source. Metadata generation records the artifacts Gradle resolved; it does not prove
   that the repository or publisher was honest. A fresh cache can detect local-cache corruption,
   but a second download from the same repository is not independent provenance.
4. Keep old checksum entries only while their coordinates remain intentionally supported. Remove
   obsolete component entries in a focused review rather than recreating the entire verification
   file from an unreviewed cache.

Never accept a changed checksum for an unchanged coordinate as routine maintenance. Treat it as a
possible repository or cache integrity incident and verify the publisher's artifact independently.

The initial checksum baseline was generated from the configured Maven Central and Gradle Plugin
Portal repositories, checked for trust exceptions, and resolved again through a fresh local cache.
This establishes a consistent integrity baseline and rules out reliance on one pre-existing local
cache; it does not authenticate publisher identity. Signature verification is not enabled.

The control behavior and commands follow Gradle's official documentation for
[dependency locking](https://docs.gradle.org/9.7.1/userguide/dependency_locking.html) and
[dependency verification](https://docs.gradle.org/9.7.1/userguide/dependency_verification.html).
