package ch.nokillswit.norm

/**
 * The connector-agnostic code-layer vocabulary and kernel (`gitlab-integration.md` "PROCESS / PROFILE"; the V44 `norm.code_*` tables,
 * `norm/CodeActivityStore.kt`). `gitlab/GitLabNormalizer.kt` is the one producer today; nothing here knows a GitLab code string.
 * This file holds the codes the store compiles against; the pure kernel (identity keys, the counted-lines rule, the event order)
 * joins them.
 */

/**
 * Bump on ANY change to the code layer's PROCESS rules (the identity keys, the counted-lines rule, the event kinds and order, the merge
 * request row) - `raw.gitlab_commits` / `raw.gitlab_merge_requests` `processing_version IS DISTINCT FROM` this reprocesses every row on
 * the next PROCESS pass, with no migration. Deliberately a separate constant from Jira's [PROCESSING_VERSION]: neither side reprocesses
 * the other.
 *
 * `1` (PR 10): the first code layer.
 */
const val CODE_PROCESSING_VERSION = 1

/** What an identity key stands for (`norm.code_identities.kind`, Kotlin-enum-whitelisted, no CHECK). */
enum class IdentityKind {
    /** `u<id>`: a GitLab user (listed, or a ghost named by a fact but absent from the users listing). */
    USER,

    /** `e<hash>`: a commit author's address that matches no GitLab user. */
    EMAIL,
}

/** The review-event kinds a merge request carries (`norm.code_mr_events.kind`, Kotlin-enum-whitelisted, no CHECK). */
enum class MrEventKind { COMMENT, DIFF_COMMENT, APPROVAL, REVIEW_REQUESTED, REVIEWED, CHANGES_REQUESTED }

/**
 * Whether a merge request's events are current (`norm.code_merge_requests.details_state`): [READ] a complete details read, [PENDING] the
 * read is stale (the event set is empty or old), [UNREADABLE] a terminal details error. A report reads the state, never "no events".
 */
enum class DetailsState { READ, PENDING, UNREADABLE }
