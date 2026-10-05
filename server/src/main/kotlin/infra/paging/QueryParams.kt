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

/**
 * Every non-blank value of [name], trimmed and `distinct()` (first occurrence wins), for a repeated-key param whose contract bounds how
 * MANY values it takes (the deep dive's `epicId`/`issueId` keys): 400 when fewer than [minCount] or more than [maxCount] distinct
 * values arrive. Duplicates collapse rather than fail, as in [repeatedEnum]; the bound applies to what is left.
 */
fun Parameters.repeatedStrings(name: String, minCount: Int = 0, maxCount: Int): List<String> =
    boundedCount(name, repeatedValues(name).map { it.trim() }.distinct(), minCount, maxCount)

/**
 * Every non-blank value of [name] parsed as an id (`Long`, `distinct()`) of at least [minValue] (default 0; pass 1 to refuse a zero id),
 * bounded to [minCount]..[maxCount] distinct values like [repeatedStrings]; 400 on a value that is not an integer of at least
 * [minValue]. Empty (when [minCount] is 0) when absent.
 */
fun Parameters.repeatedLongs(name: String, minCount: Int = 0, maxCount: Int, minValue: Long = 0): List<Long> =
    boundedCount(
        name,
        repeatedValues(name).map { raw ->
            raw.trim().toLongOrNull()?.takeIf { it >= minValue } ?: throw BadRequestException("Invalid $name: $raw")
        }.distinct(),
        minCount,
        maxCount,
    )

private fun <T> boundedCount(name: String, values: List<T>, minCount: Int, maxCount: Int): List<T> {
    if (values.size > maxCount) throw BadRequestException("Parameter '$name' takes at most $maxCount values")
    if (values.size < minCount) {
        throw BadRequestException("Parameter '$name' takes at least $minCount value${if (minCount == 1) "" else "s"}")
    }
    return values
}

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
