---
name: run-stack
description: How to run, package, and deploy the Flow stack — docker compose, local dev (gradle + vite), local Kubernetes (OrbStack), installDist packaging, and JVM memory tuning.
---

# Running, packaging, and deploying the Flow stack

## Packaging

Package the server for deployment with `./gradlew :server:installDist` (output under
`server/build/install/server/`, launcher `bin/server`). **Do not use `:server:buildFatJar`** —
the shadow plugin collapses the duplicate
`META-INF/services/org.flywaydb.core.extensibility.Plugin` descriptors and the fat JAR NPEs at
startup inside Flyway's plugin registry. `installDist` keeps each dependency JAR separate, so
Flyway's `ServiceLoader` discovery works exactly as under `:server:run`.

## JVM footprint tuning

JVM footprint tuning is baked into the `application {}` block in `server/build.gradle.kts` via
`applicationDefaultJvmArgs` = `-XX:+UseSerialGC -Xmx256m -XX:TieredStopAtLevel=1`, so it flows
into both `bin/server` (→ Docker image) and `:server:run` (the Gradle `test` task is unaffected).
Measured (in Lettuce, the same stack) on a 512 MiB Linux container: baseline G1 drifts
**~345→410 MiB RSS** as it grows its heap, vs a steady, deterministic **~270 MiB** with these
flags (**~25% lower and predictable**); startup is ~1.6 s either way, so the win is memory, not
startup. SerialGC removes G1's per-heap overhead (~75 MiB); `-Xmx256m` caps a heap that holds no
large caches (drop to `192m` to trim ~25 MiB more); C1-only (`TieredStopAtLevel=1`) trims
code-cache + C2-compiler memory (~50 MiB) at the cost of peak CPU-bound throughput (irrelevant
here — **remove that flag if the service ever runs hot**). Override per-deploy with
`JAVA_OPTS`/`SERVER_OPTS` — the Gradle-generated launcher script (`bin/server`) assembles the JVM
command line as `$DEFAULT_JVM_OPTS $JAVA_OPTS $SERVER_OPTS`, so a `JAVA_OPTS`-supplied flag comes
AFTER the baked `applicationDefaultJvmArgs` on the same `java` invocation and the JVM takes the
LAST `-Xmx` it sees — `k8s/worker-deployment.yaml`'s `JAVA_OPTS=-Xmx512m` (below) relies on exactly
this ordering to override the baked `-Xmx256m` without a separate image. Container ceilings match:
`mem_limit: 512m` in `docker-compose.yaml`, `resources.limits.memory: 512Mi` (request `320Mi`) in
`k8s/web-deployment.yaml` (`k8s/worker-deployment.yaml` runs a larger heap, 768Mi/384Mi — see
"Roles" below). This was evaluated (in Lettuce) instead of a GraalVM native-image migration, which
the reflection/ServiceLoader-heavy stack (Ktor config modules, Flyway, Exposed, OTel, Logback,
java-jwt) makes costly for little benefit on a long-running internal service.

## Roles

`FLOW_ROLE` (`web`|`worker`|`all`, default `all` — `plugins/Role.kt`, `.claude/docs/ingestion.md`
"Roles") switches which HTTP surface a process serves: `worker` answers only the health/ready
probes and runs the ingestion worker (`ingest/IngestWorker.kt` — the sync-job scheduler, its
lease/heartbeat claim loop, and the Jira sync/reconcile/reprocess/purge jobs it runs); `web` serves
the API and SPA with no worker; `all` does both. Dev, `docker compose` and the test suite default
to `all` — a single process. **Kubernetes runs the real split**: `k8s/web-deployment.yaml`
(`FLOW_ROLE=web`) sits behind `k8s/app-service.yaml`; `k8s/worker-deployment.yaml`
(`FLOW_ROLE=worker`) does not — its pod labels (`io.kompose.service: worker`) are deliberately
different from the Service's selector (`io.kompose.service: app`), so it never receives API
traffic, and it runs a single replica (the sync-job lease makes a second one safe but pointless).
The `ingest.*` config block (`schedulerTickSeconds`, `workerSlots`, `leaseSeconds`, `maxAttempts`,
`jobRetentionDays`, `purgeGraceDays`, `workerId` — see the README's environment-variable table) is
validated on every boot regardless of role; only the scan loop itself is worker-only.

## Running the full stack

Two ways to run, sharing the same `docker-compose.yaml`. All ports deliberately avoid Lettuce's
(8080/5432/5173/8025), Toadie's (8081/5433/5174/8026) and Covenant's (8082/5434/5175/8027), so
every sibling stack can run side by side on one machine; host ports bind to 127.0.0.1:

- **One command (clone & run / demo):** `docker compose up --build` builds the SPA and the
  server, starts PostgreSQL, runs Flyway on boot, and serves everything at
  `http://localhost:8084` (Swagger at `/openapi`; sign in as `admin@flow.local` / `changeme`;
  Mailpit at `http://localhost:8028`). Tear down with `docker compose down` (add `-v` to drop the
  DB volume).
- **Local development (hot reload):** in separate terminals, run
  `docker compose up postgres mailpit` (Postgres host port **5435** → in-container 5432),
  `./gradlew :server:run`, and `cd web && npm run dev` (Vite on **5176**, proxying `/api` →
  **8084**).

- **Local Kubernetes (OrbStack):** give each local build a unique tag, for example
  `FLOW_IMAGE_TAG="dev-$(git rev-parse --short=12 HEAD)-$(date +%s)"`, then run
  `docker build -t "flow-app:${FLOW_IMAGE_TAG}" .`. Create the `flow` namespace and
  `flow-secrets` Secret (command in `k8s/secret.yaml`), then run
  `./k8s/apply-local.sh "$FLOW_IMAGE_TAG"`. The script renders **both** Deployments
  (`k8s/web-deployment.yaml` and `k8s/worker-deployment.yaml`) with that tag *before* applying any
  resource and deliberately excludes the Secret template. The checked-in `:local-build` name is a
  render placeholder; never apply the directory directly. A unique tag makes
  `imagePullPolicy: IfNotPresent` deterministic while OrbStack's cluster shares Docker's image
  store, so no push is needed. Everything lives in the dedicated namespace so it coexists with
  Lettuce, Toadie and Covenant. PostgreSQL uses an exact version and multi-platform manifest
  digest, its argv-list exec probe also serves as the readiness gate. Both Deployments run the
  same image and production mode (`KTOR_DEVELOPMENT=false`, `HTTP_BEHIND_PROXY=true` — each
  expects a TLS-terminating ingress/LB that sets `X-Forwarded-For`/`-Proto`; only
  `k8s/web-deployment.yaml` sits behind `k8s/app-service.yaml`) and differ only in `FLOW_ROLE`
  (`web` vs `worker`), pod labels (so the Service selects the web pod only), and resource sizing —
  the worker's `JAVA_OPTS=-Xmx512m` overrides the image's baked `-Xmx256m` (the Gradle-generated
  launcher script appends `$JAVA_OPTS` after the baked `$DEFAULT_JVM_OPTS` on one `java` command
  line, checked in `server/build/install/server/bin/server`, and the JVM honours the last `-Xmx`
  flag), with requests/limits bumped to 384Mi/768Mi (from the web Deployment's 320Mi/512Mi) to
  match. For throwaway plain-HTTP local-cluster testing, flip `KTOR_DEVELOPMENT` to `"true"` in
  both files. **Teardown:** retain database data by scaling the deployments to zero, or delete the
  deployments, services and PVC-holding resources explicitly. **Secrets:** both deployments
  consume the `flow-secrets` Secret via `secretKeyRef` (`JWT_SECRET`,
  `POSTGRES_USER`/`POSTGRES_PASSWORD`, `DATA_ENCRYPTION_KEY`, `ADMIN_INITIAL_PASSWORD`, optional
  `SMTP_USER`/`SMTP_PASSWORD`); `k8s/secret.yaml` is a placeholder **template** — applying it
  verbatim fail-closes at startup (the placeholder JWT value is on the burned list in
  `plugins/Security.kt`).

The root `Dockerfile` is a 3-stage build: (1) Node 24.21.0 builds `web/dist` (`npm ci
--legacy-peer-deps`), (2) Temurin 21.0.12+8 JDK on Ubuntu 24.04 Noble runs `:server:installDist`
with the committed Gradle project and plugin locks, (3) the matching Temurin JRE bundles the
install image **and** the built SPA, sets `WEB_STATIC_DIR=/app/web` and `KTOR_DEVELOPMENT=false`,
and runs `bin/server` as an unprivileged uid. The runtime stage upgrades libexpat1, libsqlite3-0
and perl-base from Noble's signed repositories and enforces fixed-version floors until the
Temurin image includes them. Every external base uses an exact tag and reviewed multi-platform
manifest digest. The `app` Compose service points the `POSTGRES_*` env vars at the `postgres`
service host (`postgres:5432` in-network) and waits on its healthcheck. Compose and
Testcontainers share PostgreSQL 18.6's reviewed digest; Mailpit is pinned to the security-fixed
1.31.2 image. `.dockerignore` keeps build outputs / `node_modules` (web, e2e) out of the build
context; `.git` is **included** on purpose — the SPA build stage reads the commit sha/timestamp
from it for the version stamp (see "Build version stamp" in `web/CLAUDE.md`). Note the volume
mount: postgres 18+ images keep data in a versioned subdir of `/var/lib/postgresql`, so the
compose volume mounts that path (the pre-18 `/var/lib/postgresql/data` path makes the container
unhealthy).
