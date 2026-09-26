# syntax=docker/dockerfile:1

# ── Stage 1: build the React SPA ──────────────────────────────────────────────
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS web
RUN apk add --no-cache git
WORKDIR /web
# Install deps first for layer caching. --legacy-peer-deps per web/ README
# (openapi-typescript declares TS ^5 while the scaffold uses TS 6).
COPY web/package.json web/package-lock.json ./
RUN npm ci --legacy-peer-deps
COPY web/ ./
# .git is copied last so a new commit only busts the build layer, and the version
# stamp is computed explicitly here: the vite config's `git status` dirty check
# would always be a false positive in this stage (the worktree is just web/).
COPY .git .git
# schema.ts is committed, so `vite build` needs no running server / gen:api.
RUN GIT_SHA=$(git rev-parse --short HEAD) \
    GIT_COMMIT_TIME=$(git log -1 --format=%cI) \
    npm run build

# ── Stage 2: build the server distribution ────────────────────────────────────
FROM eclipse-temurin:24.0.2_12-jdk-noble@sha256:dacac8e9a0df0d2bd24e702b4431132875c249930b70555ebd7ca285b5bee684 AS server
WORKDIR /src
# Copy build scripts + wrapper first so the Gradle distribution download caches.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile settings-gradle.lockfile buildscript-gradle.lockfile ./
COPY gradle/ gradle/
RUN ./gradlew --version --no-daemon
# Module build files, then sources.
COPY core/build.gradle.kts core/gradle.lockfile core/buildscript-gradle.lockfile core/
COPY server/build.gradle.kts server/gradle.lockfile server/buildscript-gradle.lockfile server/
COPY core/src/ core/src/
COPY server/src/ server/src/
# installDist keeps every dependency as its own JAR, so Flyway's ServiceLoader
# plugin discovery works exactly as under `:server:run` (a fat JAR collapses the
# duplicate META-INF/services descriptors and breaks Flyway at startup).
RUN ./gradlew :server:installDist --no-daemon

# ── Stage 3: runtime ──────────────────────────────────────────────────────────
# Same major as the toolchain (jvmToolchain(21)) and the test JVM — what is tested is what runs.
FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7 AS runtime
# The reviewed Temurin index still ships older Noble revisions of these packages.
# Upgrade them from Ubuntu's signed repositories and enforce the security floors; exact
# revision pins would stop clean rebuilds when Ubuntu supersedes them in the live index.
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade \
      libexpat1 libsqlite3-0 perl-base \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libexpat1)" ge 2.6.1-2ubuntu0.5 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libsqlite3-0)" ge 3.45.1-1ubuntu2.8 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' perl-base)" ge 5.38.2-3.2ubuntu0.6 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=server /src/server/build/install/server/ ./
COPY --from=web /web/dist web
ENV WEB_STATIC_DIR=/app/web
# The shipped image runs in production mode: the JWT-secret and seed-password fail-closed
# checks are active, and HSTS + HTTPS redirect are on. Local demos (docker-compose.yaml)
# explicitly override this back to true.
ENV KTOR_DEVELOPMENT=false
# No outbound email unless the deployment opts in: a real deployment sets
# MAIL_TRANSPORT=smtp with real SMTP_* settings (production mode refuses `log`).
ENV MAIL_TRANSPORT=disabled
# Run as an unprivileged fixed uid; the k8s pod pins runAsUser to this id, and nothing under
# /app is written at runtime (logs go to stdout).
RUN groupadd --system --gid 10001 flow \
    && useradd --system --uid 10001 --gid flow --home-dir /app --shell /usr/sbin/nologin flow \
    && chown -R flow:flow /app
USER 10001
EXPOSE 8084
# /api/v1/health is the liveness probe (process up); /api/v1/ready adds the database round trip.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8084/api/v1/health >/dev/null || exit 1
ENTRYPOINT ["/app/bin/server"]
