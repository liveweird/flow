package ch.nokillswit.infra.db

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.auth.verifyPassword
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.users.validatePassword
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import ch.nokillswit.infra.crypto.EncryptedAtRest

/** The V3 seed account's well-known bcrypt hash (plaintext "changeme"). */
internal const val SEED_PASSWORD_HASH = "\$2y\$12\$VD60LjzPo00G5MtaWE3h9OrqYUid.MVxc5D7oHsM8oErnD9wuIvya"

internal const val SEED_ADMIN_EMAIL = "admin@flow.local"

private val BURNED_INITIAL_PASSWORDS = setOf("changeme", "CHANGE-ME", "change-me")

/**
 * Post-migration bootstrap that neutralizes the template seed credentials outside development.
 * Runs after [configureDatabase] (needs [UserServiceKey]); registered in application.yaml.
 *
 * - `ADMIN_INITIAL_PASSWORD` (config `bootstrap.adminInitialPassword`), when set, rotates the V3
 *   bootstrap admin's password — but only while it still carries the well-known seed hash, so a
 *   password the admin chose later is never overwritten. Production validates the candidate
 *   against the ordinary account limits and refuses known demo/template values first.
 * - Outside development mode startup **fails closed** (mirroring the JWT-secret check in
 *   plugins/Security.kt) if any active account still carries the well-known seed hash — a
 *   deployment cannot boot with the `changeme` backdoor present.
 */
suspend fun Application.configureBootstrap() {
    val userService = attributes[UserServiceKey]

    val adminInitialPassword = environment.config
        .propertyOrNull("bootstrap.adminInitialPassword")?.getString()?.takeIf { it.isNotBlank() }
    if (adminInitialPassword != null) {
        if (!developmentMode) {
            // Validate before changing the seed row. A new bcrypt hash of a burned plaintext
            // evades the seed-hash check below while leaving the same known password usable.
            check(BURNED_INITIAL_PASSWORDS.none { it.equals(adminInitialPassword.trim(), ignoreCase = true) }) {
                "ADMIN_INITIAL_PASSWORD is a publicly known value — choose a private password."
            }
            try {
                validatePassword(adminInitialPassword)
            } catch (_: BadRequestException) {
                error("ADMIN_INITIAL_PASSWORD must meet the account password length and byte limits.")
            }
        }
        val rotated = userService.rotatePasswordIfHashMatches(
            email = SEED_ADMIN_EMAIL,
            expectedHash = SEED_PASSWORD_HASH,
            newHash = hashPassword(adminInitialPassword),
        )
        if (rotated > 0) {
            log.info("Bootstrap: rotated the seed admin password from ADMIN_INITIAL_PASSWORD")
            // Ids and the fixed seed email only — never the password or its hash.
            audit(
                "bootstrap.admin_password_rotated",
                "userId" to userService.findWithIdByEmail(SEED_ADMIN_EMAIL)?.first?.toLong(),
                "email" to SEED_ADMIN_EMAIL,
            )
        }
    }

    if (!developmentMode) {
        val remaining = userService.countActiveWithPasswordHash(SEED_PASSWORD_HASH)
        if (remaining > 0) {
            error(
                "$remaining active account(s) still use the well-known seed password 'changeme' — " +
                    "set ADMIN_INITIAL_PASSWORD (or rotate them manually) before starting outside development."
            )
        }
        // Earlier builds could hash a burned ADMIN_INITIAL_PASSWORD with a fresh salt. Hash
        // equality misses that state, so verify the seed admin's current hash against each
        // known plaintext before accepting production traffic.
        val seedAdmin = userService.findWithIdByEmail(SEED_ADMIN_EMAIL)?.second
        if (seedAdmin != null) {
            for (burned in BURNED_INITIAL_PASSWORDS) {
                check(!verifyPassword(burned, seedAdmin.passwordHash)) {
                    "The seed admin still uses a publicly known password — rotate it before production startup."
                }
            }
        }
    }

    // Encryption-at-rest backfill (infra/crypto/): rows written before the field cipher existed
    // still hold plaintext — encrypt them once. While a rotation previousKey is configured, every
    // row is re-encrypted under the current key instead. THE list of encrypted-at-rest services —
    // a newly encrypted feature registers here; removing an entry would strand its rows under a
    // rotated-away key.
    val rotating = environment.config
        .propertyOrNull("security.encryption.previousKey")?.getString()?.isNotBlank() == true
    encryptedAtRestServices().forEach { service ->
        val encrypted = service.encryptLegacyRows(reencryptAll = rotating)
        if (encrypted > 0) {
            log.info("Bootstrap: ${if (rotating) "re-" else ""}encrypted $encrypted ${service.encryptedRowLabel} row(s) at rest")
            audit("crypto.reencrypted", "rows" to encrypted, "label" to service.encryptedRowLabel, "rotating" to rotating)
        }
    }
}

/**
 * Every service owning encrypted-at-rest columns (see EncryptedAtRest). The Jira API token
 * (`source_connections.secret`, V8) is the first entry — never remove one once it lands.
 */
private fun Application.encryptedAtRestServices(): List<EncryptedAtRest> = listOf(attributes[DataSourceServiceKey])
