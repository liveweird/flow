package ch.nokillswit.identities

/**
 * An ADMIN's stored decision about one GitLab identity (`public.code_identity_links.decision`, V45, CHECK-constrained to exactly these
 * three). [LINKED] names a Jira person; [IGNORED] and [BOT] name nobody (the table's CHECK keeps `jira_account_id` null for them). The
 * same enum is the proposal's decision on the wire, where only [LINKED] and [BOT] occur.
 */
enum class CodeIdentityDecision { LINKED, IGNORED, BOT }

/**
 * Where an identity stands on the Identities page: a READ-TIME join of the stored decision and the computed proposal, never a column.
 * [LINKED] = decided and linked; [IGNORED_OR_BOT] = decided ignored or bot; [PROPOSED] = no decision, but an address matches one Jira
 * person (or the identity is a bot); [UNMAPPED] = no decision and no proposal.
 */
enum class CodeIdentityStatus { PROPOSED, UNMAPPED, LINKED, IGNORED_OR_BOT }
