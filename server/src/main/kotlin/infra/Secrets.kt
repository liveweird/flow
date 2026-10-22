package ch.nokillswit.infra

/**
 * The masked rendering of a secret value for a `toString()` (checkup 3, D7-5). A data class that carries a
 * password, token, hash or key overrides `toString()` and prints each secret field through this, so a stray
 * `"$request"` in a log line, an exception message or a debugger watch never reveals the value. Presence is
 * kept (`***` vs `null`) because "was one supplied" is useful and harmless; the length is not revealed.
 */
fun maskedSecret(value: String?): String = if (value == null) "null" else "***"
