package ch.nokillswit

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Flyway checksum of every migration file. An APPLIED migration's bytes may never
 * change — comments included: Flyway validates stored checksums against the files at startup
 * (`validateOnMigrate`, the default), so any edit to an already-applied migration makes every
 * existing database refuse to boot with a checksum mismatch. This gate turns that silent
 * production-only failure into a red test: editing a listed file fails here, and the fix is
 * to revert the edit (a comment clarification belongs in `.claude/docs/persistence.md`), never
 * to update the pinned value. Adding a NEW migration adds one line — compute it with the same
 * algorithm below, or read it from `flyway_schema_history` after the first local run.
 */
class MigrationChecksumTest {
    private val pinned = mapOf(
        "V1__init.sql" to -1123925117,
        "V2__create_revoked_tokens.sql" to 794001362,
        "V3__seed_admin.sql" to 1462554926,
        "V4__enable_unaccent_extension.sql" to 242547752,
        "V5__user_disabled_features.sql" to -466290471,
        "V6__create_teams.sql" to -1993255374,
        "V7__user_credential_revision.sql" to 289120118,
        "V8__create_source_connections.sql" to -1370826313,
        "V9__create_sync_jobs.sql" to -1398573629,
        "V10__create_jira_raw_store.sql" to -496936825,
        "V11__create_jira_changelogs_worklogs.sql" to -1236733849,
        "V12__create_jira_reconcile_seen.sql" to -21356434,
        "V13__create_norm_layer.sql" to -228001900,
        "V14__norm_phase3_gaps.sql" to -881475963,
        "V15__create_metrics_config.sql" to -414170277,
        "V16__create_metrics_star.sql" to -1128653097,
        "V17__metrics_contract_columns.sql" to 1803803927,
        "V18__widen_field_interval_value_text.sql" to -269263430,
        "V19__widen_reference_name_columns.sql" to 431478997,
        "V20__widen_jira_entity_id.sql" to 1345749268,
        "V21__reconcile_backoff.sql" to -1330966837,
    )

    @Test
    fun `every migration file matches its applied Flyway checksum`() {
        val dir = Path.of("src/main/resources/db/migration")
        val files = dir.listDirectoryEntries("*.sql").associate { it.name to flywayChecksum(it) }
        assertEquals(
            pinned.keys.sorted(),
            files.keys.sorted(),
            "Migration files and the pinned manifest must list the same set — add the new file's checksum here",
        )
        for ((name, expected) in pinned) {
            assertEquals(
                expected,
                files.getValue(name),
                "$name changed after being applied — revert the edit; existing databases would fail Flyway validation",
            )
        }
    }

    // Flyway's algorithm: CRC32 over each line's UTF-8 bytes (terminators excluded), BOM stripped.
    private fun flywayChecksum(file: Path): Int {
        val crc = CRC32()
        Files.readString(file).removePrefix("﻿").lineSequence().forEach { crc.update(it.toByteArray()) }
        return crc.value.toInt()
    }
}
