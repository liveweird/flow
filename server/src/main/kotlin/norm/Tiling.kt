package ch.nokillswit.norm

/**
 * The normalized layer's tiling algorithm (v0.2.0 plan §8) — PURE, no DB, no Jira-specific shapes:
 * every input is already resolved to plain ids/strings by the caller (`jira/JiraNormalizer.kt`),
 * and every output is a flat, ordered list of non-overlapping intervals plus whatever anomalies the
 * input itself exposed. Facts are never "fixed", only flagged (plan §8) — a chain that doesn't add
 * up, an event dated before the issue existed, or a last status disagreeing with the issue's
 * current one are all recorded as [TilingAnomaly] entries, never silently corrected away.
 *
 * Both [statusIntervals] and [fieldIntervals] share one construction rule: the FIRST interval
 * starts at `createdAtMs` (the issue's creation), using the first event's `from` value if there is
 * one, else the CURRENT value (there was never a change); every later interval spans one event to
 * the next; the LAST interval is always open (`toAtMs = null`) — exactly one interval is ever open,
 * by construction. Zero-length intervals are allowed (two events at the same millisecond, or an
 * event exactly at `createdAtMs`).
 */
enum class StatusCategory { TODO, IN_PROGRESS, DONE, UNKNOWN }

enum class IntervalSource { CREATED, CHANGE }

/** The three anomaly codes plan §8 names — flagged on `norm.work_items.anomalies`, never used to alter a stored fact. */
enum class TilingAnomaly { STATUS_CHANGE_BEFORE_CREATED, STATUS_CHAIN_BROKEN, STATUS_MISMATCH_WITH_CURRENT }

/** The four field kinds `norm.work_item_field_intervals`/`_field_changes` track by their own interval table (plan §4). */
enum class TrackedField { ASSIGNEE, SPRINT, FLAGGED }

/** One status-changelog transition, ids only — [atMs] need not be sorted or monotonic; [statusIntervals] clamps defensively. */
data class StatusChangeEvent(val atMs: Long, val fromStatusId: String, val toStatusId: String)

data class StatusInterval(
    val seq: Int,
    val statusId: String,
    val fromAtMs: Long,
    val toAtMs: Long?,
    val source: IntervalSource,
)

data class StatusTilingResult(val intervals: List<StatusInterval>, val anomalies: List<TilingAnomaly>)

/** One field-changelog transition — [fromValueId]/[toValueId] null means "no value" (e.g. unassigned). */
data class FieldChangeEvent(
    val atMs: Long,
    val fromValueId: String?,
    val fromValueText: String?,
    val toValueId: String?,
    val toValueText: String?,
)

data class FieldInterval(val seq: Int, val valueId: String?, val valueText: String?, val fromAtMs: Long, val toAtMs: Long?)

object Tiling {

    /**
     * Builds one issue's status-interval timeline (plan §8) from its creation time, its CURRENT
     * status id and its changelog status events (already ordered by `(created, historyId)` by the
     * caller — this function does not re-sort, only defensively clamps non-monotonic timestamps).
     *
     * - No events: a single open interval at the current status, `source = CREATED`.
     * - Events present: the first interval's status is `events[0].fromStatusId` (`source = CREATED`);
     *   interval `k` (`k >= 1`) is the status `events[k-1].toStatusId` set by the PREVIOUS
     *   transition (`source = CHANGE`); the last interval is open.
     * - [TilingAnomaly.STATUS_CHANGE_BEFORE_CREATED]: the very first event predates [createdAtMs] —
     *   clamped to [createdAtMs] (a zero-length first interval is allowed and expected here).
     * - [TilingAnomaly.STATUS_CHAIN_BROKEN]: consecutive events don't chain (`events[i].fromStatusId
     *   != events[i-1].toStatusId`) — the computed interval status still trusts the `to` chain,
     *   never the disagreeing `from`; the anomaly is the flag, not a correction.
     * - [TilingAnomaly.STATUS_MISMATCH_WITH_CURRENT]: the last computed status disagrees with
     *   [currentStatusId] — the interval keeps the value the changelog chain computed.
     */
    fun statusIntervals(createdAtMs: Long, currentStatusId: String, events: List<StatusChangeEvent>): StatusTilingResult {
        if (events.isEmpty()) {
            val single = StatusInterval(1, currentStatusId, createdAtMs, null, IntervalSource.CREATED)
            return StatusTilingResult(listOf(single), emptyList())
        }

        val anomalies = mutableListOf<TilingAnomaly>()
        val boundaries = mutableListOf(createdAtMs)
        var cursor = createdAtMs
        events.forEachIndexed { i, event ->
            var at = event.atMs
            if (i == 0 && at < createdAtMs) {
                anomalies += TilingAnomaly.STATUS_CHANGE_BEFORE_CREATED
                at = createdAtMs
            }
            if (at < cursor) at = cursor // defensive monotonic clamp — no dedicated anomaly code for this in plan §8.
            if (i > 0 && event.fromStatusId != events[i - 1].toStatusId) anomalies += TilingAnomaly.STATUS_CHAIN_BROKEN
            boundaries += at
            cursor = at
        }

        val statusIds = listOf(events.first().fromStatusId) + events.map { it.toStatusId }
        if (statusIds.last() != currentStatusId) anomalies += TilingAnomaly.STATUS_MISMATCH_WITH_CURRENT

        val intervals = statusIds.indices.map { k ->
            StatusInterval(
                seq = k + 1,
                statusId = statusIds[k],
                fromAtMs = boundaries[k],
                toAtMs = if (k == statusIds.lastIndex) null else boundaries[k + 1],
                source = if (k == 0) IntervalSource.CREATED else IntervalSource.CHANGE,
            )
        }
        return StatusTilingResult(intervals, anomalies)
    }

    /**
     * Builds one issue's field-interval timeline for ASSIGNEE/SPRINT/FLAGGED (plan §8) — the same
     * construction rule as [statusIntervals], minus its status-specific anomaly checks (plan §8
     * lists anomalies only under status tiling). SPRINT's caller passes an already-resolved
     * `valueId`/`valueText` pair per event (the LAST sprint id of a carry-over set, and Jira's own
     * comma-joined `toString` — `jira/JiraNormalizer.kt`, plan §8's "multi-valued sprint field"
     * rule); this function itself is field-shape-agnostic.
     */
    fun fieldIntervals(
        createdAtMs: Long,
        currentValueId: String?,
        currentValueText: String?,
        events: List<FieldChangeEvent>,
    ): List<FieldInterval> {
        if (events.isEmpty()) {
            return listOf(FieldInterval(1, currentValueId, currentValueText, createdAtMs, null))
        }

        val boundaries = mutableListOf(createdAtMs)
        var cursor = createdAtMs
        events.forEach { event ->
            val at = if (event.atMs < cursor) cursor else event.atMs
            boundaries += at
            cursor = at
        }

        val valueIds = listOf(events.first().fromValueId) + events.map { it.toValueId }
        val valueTexts = listOf(events.first().fromValueText) + events.map { it.toValueText }
        return valueIds.indices.map { k ->
            FieldInterval(
                seq = k + 1,
                valueId = valueIds[k],
                valueText = valueTexts[k],
                fromAtMs = boundaries[k],
                toAtMs = if (k == valueIds.lastIndex) null else boundaries[k + 1],
            )
        }
    }
}
