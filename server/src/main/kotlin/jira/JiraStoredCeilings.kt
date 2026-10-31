package ch.nokillswit.jira

import ch.nokillswit.ingest.StreamContext
import org.slf4j.Logger

// The stored-size ceilings (`.claude/docs/jira-integration.md` "Stored-size ceilings"): `jira.maxResponseBytes` bounds a RESPONSE, but
// the text the raw writers store is read back into trees later (PROCESS, the data profile, PROJECT_FIELDS, the inspector). A writer
// therefore REFUSES one item over its ceiling ([RawUpsertOutcome.REFUSED]) instead of storing a document nothing downstream can hold.
// A real tenant sits far below every figure here (the stub and real data never trip one; build-times WHY 48 re-measures).

private const val KIB = 1024L
private const val MIB = 1024L * KIB

/** `jira.maxStoredPayloadBytes` default: the most one issue or reference entity may store (canonical text). */
internal const val DEFAULT_MAX_STORED_PAYLOAD_BYTES = 2L * MIB
internal const val MIN_MAX_STORED_PAYLOAD_BYTES = 256L * KIB
internal const val MAX_MAX_STORED_PAYLOAD_BYTES = 8L * MIB

/** `jira.processPageBytes` default: the stored text one PROCESS page (and one inspected issue) may hold at once. */
internal const val DEFAULT_PROCESS_PAGE_BYTES = 8L * MIB
internal const val MIN_PROCESS_PAGE_BYTES = 2L * MIB
internal const val MAX_PROCESS_PAGE_BYTES = 64L * MIB

/** The most one changelog history may store (a constant, like the answer ceilings of `fetch/Ceilings.kt`; far above any real history). */
internal const val MAX_STORED_HISTORY_BYTES = 512L * KIB

/** The most one worklog may store (a trimmed worklog is a few hundred bytes). */
internal const val MAX_STORED_WORKLOG_BYTES = 16L * KIB

/**
 * The stored-size ceilings of a [JiraRawStore]: [maxPayloadBytes] an issue's or reference entity's canonical text, [maxHistoryBytes]
 * and [maxWorklogBytes] one history's or worklog's text as handed to the writer (checked before any parse), and [processPageBytes]
 * what PROCESS may claim for a page of issues (an issue's payload + histories + live worklogs) and what the raw issue inspector may
 * serve. The defaults are the shipped ones; `jira/Jira.kt` reads the two configurable figures.
 */
data class JiraStoredCeilings(
    val maxPayloadBytes: Long = DEFAULT_MAX_STORED_PAYLOAD_BYTES,
    val processPageBytes: Long = DEFAULT_PROCESS_PAGE_BYTES,
    val maxHistoryBytes: Long = MAX_STORED_HISTORY_BYTES,
    val maxWorklogBytes: Long = MAX_STORED_WORKLOG_BYTES,
)

/**
 * Fails startup when the two configurable figures contradict each other: a PROCESS page must hold the largest payload the writers accept
 * plus ONE history, or even a bare issue could be STORED yet never claimable (`issuesOversized`, stale for ever). It guarantees no more
 * than that: an issue with many histories or worklogs can still total more than a page and is then skipped (it keeps its old `norm` rows).
 */
internal fun requireProcessPageHoldsAnIssue(stored: JiraStoredCeilings) {
    val needed = stored.maxPayloadBytes + stored.maxHistoryBytes
    require(stored.processPageBytes >= needed) {
        "Config \"jira.processPageBytes\" (${stored.processPageBytes}) must be at least \"jira.maxStoredPayloadBytes\" " +
            "(${stored.maxPayloadBytes}) plus the largest stored history (${stored.maxHistoryBytes}) = $needed: otherwise an issue " +
            "could be stored that PROCESS can never claim"
    }
}

/** Whether a write changed stored data: the progress signal of `StreamContext.heartbeat`. A refusal changes nothing. */
internal val RawUpsertOutcome.isChange: Boolean get() = this != RawUpsertOutcome.UNCHANGED && this != RawUpsertOutcome.REFUSED

/** Ticks [counter] once per [RawUpsertOutcome.REFUSED] in [outcomes] and returns how many there were. */
internal fun StreamContext.countRefused(counter: String, outcomes: Iterable<RawUpsertOutcome>): Int =
    outcomes.count { it == RawUpsertOutcome.REFUSED }.also { if (it > 0) incrementProgress(counter, it.toLong()) }

/** One reference entity's progress: `entities` and `pages` for a stored one, `entitiesSkipped` for a refused one. */
internal fun StreamContext.countEntityOutcome(outcome: RawUpsertOutcome) {
    if (outcome == RawUpsertOutcome.REFUSED) {
        incrementProgress("entitiesSkipped")
    } else {
        incrementProgress("entities")
        incrementProgress("pages")
    }
}

/** The UTF-8 size of this text in bytes, counted without encoding it; an unpaired surrogate counts as 3 (an upper bound). */
internal fun String.utf8Length(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val c = this[index]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> 4.also { index++ }
            else -> 3
        }
        index++
    }
    return bytes
}

/**
 * The ONE WARN of a refusal: [what] names the item ("issue", "FIELD entity", "history", "worklog"), [id] is Jira text so it is cut
 * and stripped of control characters, and the payload is never logged.
 */
internal fun Logger.warnRefused(what: String, id: String, connectionId: UInt, bytes: Long, ceiling: Long) {
    warn(
        "Stored-size ceiling: refused {} {} of data source {}: {} bytes over the {}-byte ceiling; nothing written, " +
            "an existing row keeps its old payload",
        what, id.safeForLog(), connectionId, bytes, ceiling,
    )
}
