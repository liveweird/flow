package ch.nokillswit.ingest

/**
 * What a job that overflowed the JVM stack fails with (`IngestWorker.runJob`): a `StackOverflowError` is an `Error`, so no
 * `catch (Exception)` between the code and the worker sees it, and the job would otherwise die without `onFailed` - no FAILED row,
 * no audit event, no back-off, three lease expiries before it is closed. The text is constant: the cause is a response nested more
 * deeply than the parser or a serialiser can follow, which the Jira client refuses before the parse (`jira/JiraNodeCeiling.kt`), so
 * reaching this means a path that ceiling does not cover. Only this Error type is handled; an `OutOfMemoryError` is not.
 */
internal class JobStackOverflowException(overflow: StackOverflowError) :
    RuntimeException("the job overflowed the JVM stack (a document nested more deeply than the code can follow)", overflow)
