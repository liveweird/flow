package ch.nokillswit.gitlab

/** The code layer's PROCESS stream: rebuilds `norm.code_*` from the raw GitLab rows. Its ONE cursor row holds fingerprints. */
internal const val CODE_PROCESS_STREAM_NAME = "code_process"

/** The code layer's PROFILE stream: stores the GitLab data profile section. It keeps no cursor. */
internal const val CODE_PROFILE_STREAM_NAME = "code_profile"

/**
 * The streams that never call GitLab (the `jira/JiraLocalStreams.kt` twin): they do not enforce the job's wall clock, so a run that
 * finished every GitLab-calling stream cannot end `TIME_EXCEEDED`, and the hard stop is disarmed for good when the run reaches them. No
 * GitLab-calling stream may therefore follow either of them in a job.
 */
internal val GITLAB_LOCAL_STREAMS = setOf(CODE_PROCESS_STREAM_NAME, CODE_PROFILE_STREAM_NAME)
