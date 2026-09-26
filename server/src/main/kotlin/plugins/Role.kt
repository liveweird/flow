package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.util.AttributeKey

/**
 * The `FLOW_ROLE` switch (`app.role`, default `all`): one codebase and one image serve three
 * process shapes (v0.2.0 Jira ingestion — see `.claude/docs/ingestion.md` "Roles"):
 * - **WEB** serves the HTTP API (feature routes, SPA/static assets) but runs no ingestion worker.
 * - **WORKER** serves only the health/ready probes — its one HTTP surface — and runs the
 *   ingestion worker (arriving in commit 5 of the phase-2 plan).
 * - **ALL** does both in one process — dev, `docker compose` and the test suite's default.
 *
 * Flyway and Bootstrap always run, in every role: schema and seed state must be current
 * regardless of which surface a given instance serves.
 */
enum class AppRole { WEB, WORKER, ALL }

/** Published by [configureRole] into `Application.attributes`; read via [servesApi]/[runsWorker]. */
val AppRoleKey = AttributeKey<AppRole>("AppRole")

/**
 * Boot-validated the same way as [ch.nokillswit.infra.config.requireConfigInt] validates a
 * numeric setting, but for an enum: an unset value defaults to `all`; any other value that isn't
 * one of [AppRole]'s names fails startup in EVERY mode — the same fail-closed shape as a
 * malformed encryption key (`infra/crypto/Crypto.kt`) — a bad `FLOW_ROLE` is a deploy-time config
 * error, not something to limp along with. Registered right after the base plugins (before the
 * infra and feature modules) in `application.yaml`, so every later module can read
 * [Application.servesApi]/[Application.runsWorker] off `attributes`.
 */
fun Application.configureRole() {
    val raw = environment.config.property("app.role").getString()
    val role = AppRole.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: error(
            "Config \"app.role\" must be one of ${AppRole.entries.joinToString { it.name.lowercase() }} (was \"$raw\")",
        )
    attributes.put(AppRoleKey, role)
}

/**
 * True when this process serves the HTTP API — feature route modules and the SPA/static catch-all
 * early-return when this is false (`WORKER` alone), leaving only the health/ready probes.
 */
fun Application.servesApi(): Boolean = attributes[AppRoleKey] != AppRole.WORKER

/**
 * True when this process runs the ingestion worker (`S/ingest/IngestWorker.kt`, arriving in commit
 * 5 of the phase-2 plan — no consumer reads this yet).
 */
fun Application.runsWorker(): Boolean = attributes[AppRoleKey] != AppRole.WEB
