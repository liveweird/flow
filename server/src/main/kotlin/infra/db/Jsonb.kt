package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.JsonColumnMarker
import org.jetbrains.exposed.v1.core.Table

/**
 * A repo-local `jsonb` column binding, because `exposed-r2dbc` 1.5.0 ships no JSON column type of
 * its own (verified against the jar on the classpath: no `exposed-json` module, no column type
 * targeting `io.r2dbc.postgresql.codec.Json`) — the first consumer is `source_connections.settings`
 * (V8, ingest/DataSourceService.kt).
 *
 * The binding needs no reflection into the raw `io.r2dbc.spi.Statement`: Exposed's R2DBC write
 * path runs a priority-ordered `TypeMapper` chain
 * (`org.jetbrains.exposed.v1.r2dbc.mappers.R2dbcRegistryTypeMappingImpl`), and the bundled
 * `PostgresSpecificTypeMapper` already special-cases any [IColumnType][org.jetbrains.exposed.v1.core.IColumnType]
 * marked [JsonColumnMarker] (verified by decompiling the class): a null value binds via
 * `Statement.bindNull(index, Json::class)`, a String value via
 * `Statement.bind(index, Json.of(value))` (`io.r2dbc.postgresql.codec.Json`) — so implementing the
 * marker interface IS the whole binding. The read path (`PostgresSpecificTypeMapper.getValue`)
 * already unwraps a returned `Json` via `.asString()` before [ColumnType.valueFromDB] ever runs
 * (same decompile), so [JsonbColumnType.valueFromDB] only needs to accept the resulting String —
 * the `Json`/`ByteArray` branches are defensive, for a value that reaches it by some other path
 * (a raw JDBC `PGobject`, say, if this type is ever exercised over that driver).
 *
 * Values are the caller's JSON TEXT verbatim — canonicalization (`infra/json/CanonicalJson.kt`) is
 * the caller's job, not this column type's. PostgreSQL's `jsonb` storage reformats on its own
 * (whitespace, key order, trailing zeros), so "exactly as received" means the same VALUES survive
 * the round trip, not the same bytes (`.claude/docs/persistence.md` "jsonb").
 */
class JsonbColumnType : ColumnType<String>(), JsonColumnMarker {
    override val usesBinaryFormat: Boolean = true
    override val needsBinaryFormatCast: Boolean = false

    override fun sqlType(): String = "JSONB"

    override fun valueFromDB(value: Any): String = when (value) {
        is String -> value
        is io.r2dbc.postgresql.codec.Json -> value.asString()
        is ByteArray -> String(value, Charsets.UTF_8)
        else -> error("Unexpected JSONB value type: ${value::class.qualifiedName}")
    }

    // The Postgres-specific R2DBC type mapper (see class doc) binds a plain String directly via
    // Json.of(value) — no wrapping needed here.
    override fun notNullValueToDB(value: String): Any = value

    override fun nonNullValueToString(value: String): String = "'${value.replace("'", "''")}'::jsonb"
}

/** Declares a `jsonb` column holding JSON text (see [JsonbColumnType]). */
fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
