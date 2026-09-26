package ch.nokillswit.infra.crypto

/**
 * A service owning columns encrypted at rest ([FieldCipher]). The bootstrap iterates every
 * implementation (the list in infra/db/Bootstrap.kt) at each boot: legacy plaintext rows are
 * encrypted once, and while a rotation previousKey is configured every row is re-encrypted
 * under the current key. Adding a newly encrypted feature = implement this and register the
 * service in the bootstrap list — do NOT remove any registration (a key rotation would strand
 * that feature's rows under the previous key; see "Encryption at rest" in
 * `.claude/docs/security.md`). No feature stores an encrypted column yet — the machinery is
 * kept ready for the first one (e.g. a stored Jira API token).
 */
interface EncryptedAtRest {
    /** A short noun for the bootstrap log line, e.g. "environment credential". */
    val encryptedRowLabel: String

    /**
     * Encrypt plaintext legacy rows (or, with [reencryptAll], re-encrypt every row under the
     * current key). Returns the number of rows touched.
     */
    suspend fun encryptLegacyRows(reencryptAll: Boolean = false): Int
}
