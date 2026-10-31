package ch.nokillswit.jira

/**
 * The streams that never call Jira ([PROCESS_STREAM_NAME], [PROFILE_STREAM_NAME]): they do not enforce the job's wall clock
 * ([ch.nokillswit.ingest.StreamContext]), so a run that finished every Jira-calling stream cannot end `TIME_EXCEEDED`.
 */
internal val LOCAL_STREAMS = setOf(PROCESS_STREAM_NAME, PROFILE_STREAM_NAME)

internal const val PROCESS_STREAM_NAME = "process"
internal const val PROFILE_STREAM_NAME = "profile"
