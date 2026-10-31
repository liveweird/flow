package ch.nokillswit.gitlab

/** The reviewer states GitLab documents (`/reviewers`); any other value is stored as [MR_REVIEWER_OTHER_STATE]. */
private val REVIEWER_STATES = setOf("UNREVIEWED", "REVIEWED", "REQUESTED_CHANGES", "APPROVED")

const val MR_REVIEWER_OTHER_STATE = "OTHER_STATE"
private const val ACTION_APPROVAL = "APPROVAL"

/**
 * One merge request's `raw.gitlab_mr_events` set (V43), built from the three reads of the MR DETAILS stream: a NOTE row per note (an
 * action code, never a body), an APPROVAL row per approver of the legacy `/approvals` object (`event_at` null: the endpoint has no
 * time; the matching system note has it) and a REVIEWER row per `/reviewers` entry (the state upper-cased, `event_at` = when they
 * were added). A pair listed twice (a page shift lists a note twice) is ONE row: the first wins.
 */
internal fun buildMrEvents(
    notes: List<GitLabNoteSummary>,
    approvers: List<Long>,
    reviewers: List<GitLabReviewerSummary>,
): List<RawGitLabMrEvent> {
    val events = ArrayList<RawGitLabMrEvent>(notes.size + approvers.size + reviewers.size)
    notes.mapTo(events) {
        RawGitLabMrEvent(MrEventSource.NOTE, it.id, it.authorId, it.createdAt, it.system, it.noteType, it.action, it.targets)
    }
    approvers.mapTo(events) {
        RawGitLabMrEvent(MrEventSource.APPROVAL, it, it, null, system = false, noteType = null, action = ACTION_APPROVAL)
    }
    reviewers.mapTo(events) {
        val state = it.state.uppercase().takeIf { state -> state in REVIEWER_STATES } ?: MR_REVIEWER_OTHER_STATE
        RawGitLabMrEvent(MrEventSource.REVIEWER, it.userId, it.userId, it.createdAt, system = false, noteType = null, action = state)
    }
    return events.distinctBy { it.source to it.sourceId }
}
