package ch.nokillswit.infra.paging

import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException

// Small helpers for the repeated list-endpoint query-param parsing idioms, so every route stops
// hand-writing `params["x"]?.takeIf { it.isNotBlank() }` and the numeric variants. Ported from
// Lettuce minus its view-scoped helpers (optionalIncludeIndirect, uintOnlyForView) and the
// remaining typed scalar parser (optionalLong) — each returns with its first consumer
// (optionalBoolean arrived with the users-list featureEnabled filter, optionalUInt with the
// teams list's memberId filter).

/**
 * The single value of [name], or null when absent. A repeated key is a 400: repetition is
 * reserved for per-endpoint documented `IN` semantics (API-LIST-004) — a param with those
 * semantics reads through [repeatedValues] instead; anywhere else, silently using the first
 * value would hide the caller's conflicting input.
 */
fun Parameters.singleValue(name: String): String? {
    val all = getAll(name) ?: return null
    if (all.size > 1) throw BadRequestException("Parameter '$name' must not be repeated")
    return all.first()
}

/** The param's value, or null when absent or blank. */
fun Parameters.optionalString(name: String): String? = singleValue(name)?.takeIf { it.isNotBlank() }

/** An unsigned-id param (the `?memberId=` shape): null when absent, 400 when not a non-negative integer. */
fun Parameters.optionalUInt(name: String): UInt? =
    optionalString(name)?.let { it.toUIntOrNull() ?: throw BadRequestException("Invalid $name: $it") }

/**
 * Every non-blank value of [name] — for the params whose documented contract makes repetition
 * mean any-of/`IN` (API-LIST-004). Empty when absent or all values are blank.
 */
fun Parameters.repeatedValues(name: String): List<String> =
    getAll(name).orEmpty().filter { it.isNotBlank() }

/** Parses a non-blank param as a strict boolean; null when absent/blank, 400 unless exactly true/false. */
fun Parameters.optionalBoolean(name: String): Boolean? =
    optionalString(name)?.let {
        it.toBooleanStrictOrNull() ?: throw BadRequestException("$name must be true or false")
    }

/**
 * Parses a non-blank param as an enum constant (exact name match); null when absent/blank,
 * 400 (listing the allowed values) when present but not a constant of [E].
 */
inline fun <reified E : Enum<E>> Parameters.optionalEnum(name: String): E? =
    optionalString(name)?.let { raw ->
        enumValues<E>().firstOrNull { it.name == raw } ?: throw BadRequestException(
            "Unknown $name: $raw (allowed: ${enumValues<E>().joinToString { it.name }})",
        )
    }

/**
 * Every non-blank value of [name] parsed as an enum constant (case-insensitive, `distinct()`) —
 * the repeated-key any-of idiom (API-LIST-004) for an enum-typed filter; 400 (listing the allowed
 * values) on an unknown one. Empty when absent.
 */
inline fun <reified E : Enum<E>> Parameters.repeatedEnum(name: String): List<E> =
    repeatedValues(name).map { raw ->
        enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: throw BadRequestException(
            "Unknown $name: $raw (allowed: ${enumValues<E>().joinToString { it.name }})",
        )
    }.distinct()
