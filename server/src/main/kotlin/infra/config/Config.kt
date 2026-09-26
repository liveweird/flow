package ch.nokillswit.infra.config

import io.ktor.server.config.ApplicationConfig

/**
 * Boot-time range validation for a numeric config value (ported from Lettuce): a malformed or
 * out-of-range setting is a config error, not a runtime concern — `POSTGRES_POOL_MAX_SIZE=0`
 * would leave the app with no connections. Refuses to start in EVERY mode (the
 * `security.encryption.key` malformed-key precedent in `infra/crypto/Crypto.kt` — this is not
 * gated by `developmentMode`). Both bounds are inclusive; a non-numeric override is rejected the
 * same way as an out-of-range one, with the key in the message.
 */
fun requireConfigInt(config: ApplicationConfig, key: String, min: Int, max: Int = Int.MAX_VALUE): Int {
    val raw = config.property(key).getString()
    val value = raw.toIntOrNull()
    if (value == null || value < min || value > max) {
        error("Config \"$key\" must be an integer ${bounds(min.toLong(), max.toLong(), Int.MAX_VALUE.toLong())} (was \"$raw\")")
    }
    return value
}

/** The [requireConfigInt] sibling for the `Long`-typed duration/interval settings. */
fun requireConfigLong(config: ApplicationConfig, key: String, min: Long, max: Long = Long.MAX_VALUE): Long {
    val raw = config.property(key).getString()
    val value = raw.toLongOrNull()
    if (value == null || value < min || value > max) {
        error("Config \"$key\" must be an integer ${bounds(min, max, Long.MAX_VALUE)} (was \"$raw\")")
    }
    return value
}

private fun bounds(min: Long, max: Long, unbounded: Long): String =
    if (max == unbounded) ">= $min" else "between $min and $max"
