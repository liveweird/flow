package ch.nokillswit.auth

/**
 * The login audit events emitted from more than one place in `AuthRoutes.kt` (`.claude/docs/observability.md` lists every event). The
 * strings are the operator-facing contract: tests assert the literals, so a rename here fails them.
 */
internal const val EVENT_LOGIN_REJECTED_LOCKED = "login.rejected_locked"
internal const val EVENT_LOGIN_CAPACITY_REJECTED = "login.capacity_rejected"
internal const val EVENT_LOGIN_MFA_FAILURE = "login.mfa_failure"
