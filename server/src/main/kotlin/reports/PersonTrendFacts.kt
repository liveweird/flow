package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Person trend's one scan of the delivered tasks (report 20, build-times.md WHY 46). Compare people reads four populations from
 * `fact_task_delivery` with four statements -- the done tasks ([fetchCompareDoneTasks]), the cycle tasks ([fetchDoneCycleTasks]), the
 * accuracy tasks ([fetchAccuracyTasks]) and the blocked items ([fetchBlockedItems]) -- over the SAME predicate (`taskFactSlice`, a done_at
 * in the window), so the same rows. Decoding a row costs ~0.75 microseconds per column through Exposed's R2DBC mappers and a team of 15
 * reads ~8,300 rows per statement; one statement over the union of the four column lists decodes each row once (20 columns instead of
 * 39) and builds the four objects from it with the very row mappers the four fetches use, so a population cannot differ from its own
 * report's fetch. `PersonTrendFactsTest` pins the equality with the four separate fetches.
 */

/** The four populations of the delivered tasks of the filter's accounts in a window, each exactly what its own fetch returns. */
internal class DeliveredFacts(
    val done: List<CompareDoneTask>,
    val cycle: List<DoneCycleTask>,
    val accuracy: List<AccuracyTask>,
    val blocked: List<DoneItem>,
)

/** [fetchCompareDoneTasks], [fetchDoneCycleTasks], [fetchAccuracyTasks] and [fetchBlockedItems] (`TASK`) of [window] from one statement. */
internal suspend fun fetchDeliveredFacts(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): DeliveredFacts {
    if (connectionIds.isEmpty()) return DeliveredFacts(emptyList(), emptyList(), emptyList(), emptyList())
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    val done = ArrayList<CompareDoneTask>()
    val cycle = ArrayList<DoneCycleTask>()
    val accuracy = ArrayList<AccuracyTask>()
    val blocked = ArrayList<DoneItem>()
    t.select((COMPARE_DONE_COLUMNS + DONE_CYCLE_COLUMNS + ACCURACY_COLUMNS + DONE_TASK_COLUMNS).distinct()).where { predicate }.collect {
        done += compareDoneTaskOf(it, filter.domainView)
        cycle += doneCycleTaskOf(it)
        accuracy += accuracyTaskOf(it)
        blocked += doneTaskItemOf(it)
    }
    return DeliveredFacts(done, cycle, accuracy, blocked)
}
