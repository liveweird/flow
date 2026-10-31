package ch.nokillswit.gitlab

import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.classifyUpsert

/**
 * The ONE page rule for a hashed GitLab table (projects, users, merge requests): a page of prepared rows is classified against the
 * stored hashes and markers ([classifyUpsert]), the per-row changes are applied by the caller's `change`, and the inserts and the
 * unchanged-touch come back for the caller to batch. Pure bookkeeping, no table of its own.
 */

/** One input row of a hashed table, ready to be judged: the caller's [input], its numeric [key], and the canonical payload + sha256. */
internal class Prepared<I>(val input: I, val key: Long, val canonical: String, val hash: String)

/** What [diffPage] decided: the outcomes in input order, the rows to insert and the keys of the unchanged live rows to touch. */
internal class PageDiff<I>(val outcomes: List<RawUpsertOutcome>, val inserts: List<Prepared<I>>, val unchanged: List<Long>)

/** The stored side of a row: its hash, whether it is tombstoned (or moved out), and the columns outside the payload a caller compares. */
internal class StoredRow(val hash: String, val tombstoned: Boolean, val groupPath: String? = null, val groupId: Long? = null)

/** The unchanged keys of [diff], sorted and cut into `IN (...)`-sized chunks for the last-seen touch. */
internal fun touched(diff: PageDiff<*>): List<List<Long>> = diff.unchanged.sorted().chunked(KEY_CHUNK)

/**
 * Classifies a page against the stored rows ([classifyUpsert]) and applies the per-row changes ([change]); inserts and the
 * unchanged-touch are returned for the caller to batch. A key repeated in one page is judged by its first occurrence alone.
 */
internal suspend fun <I> diffPage(
    rows: List<Prepared<I>>,
    stored: Map<Long, StoredRow>,
    drifted: (Prepared<I>, StoredRow) -> Boolean = { _, _ -> false },
    change: suspend (Prepared<I>) -> Unit,
): PageDiff<I> {
    val inserts = mutableListOf<Prepared<I>>()
    val unchanged = mutableListOf<Long>()
    val seen = HashSet<Long>()
    val outcomes = rows.map { row ->
        if (!seen.add(row.key)) return@map RawUpsertOutcome.UNCHANGED
        val current = stored[row.key]
        val classified = classifyUpsert(current?.hash, row.hash, current?.tombstoned == true)
        val outcome = if (classified == RawUpsertOutcome.UNCHANGED && current != null && drifted(row, current)) {
            RawUpsertOutcome.CHANGED
        } else {
            classified
        }
        when (outcome) {
            RawUpsertOutcome.INSERTED -> inserts += row
            RawUpsertOutcome.UNCHANGED -> unchanged += row.key
            RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED -> change(row)
            RawUpsertOutcome.REFUSED -> error("classifyUpsert never refuses")
        }
        outcome
    }
    return PageDiff(outcomes, inserts, unchanged)
}
