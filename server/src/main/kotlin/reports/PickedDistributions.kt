package ch.nokillswit.reports

/**
 * The one rule behind every report whose histograms split per picked person at USER level (Cycle time, Reported-time ratio, Task
 * accuracy, Task estimates): [drawn] builds the union's [Distribution] with a [Distribution.byUser] share per pick, [plain] never
 * splits (a group's own distribution). With no picks (below USER level) [drawn] is [plain]. [accountOf] is the row's assignee at
 * done; [fixedStep] is the report's fixed bar width (Task estimates), `null` for the data-fitted layout.
 */
internal class PickedDistributions<T>(
    private val picked: PickedAccounts,
    private val minSample: Int,
    private val tailPercent: Int,
    private val fixedStep: Double? = null,
    private val accountOf: (T) -> String?,
) {
    fun plain(values: List<Double>): Distribution = buildDistribution(values, minSample, tailPercent, fixedStep = fixedStep)

    /**
     * [valuesOf] reads a view's values off a row list; [union] is the same read over the whole [rows] when the caller already has it
     * (defaults to computing it).
     */
    fun drawn(rows: List<T>, valuesOf: (List<T>) -> List<Double>, union: List<Double> = valuesOf(rows)): Distribution {
        if (picked.ids.isEmpty()) return plain(union)
        val byAccount = rows.groupBy(accountOf)
        return buildDistributionByUser(
            picked.ids.map { UserValues(it, picked.label(it), valuesOf(byAccount[it].orEmpty())) },
            minSample,
            tailPercent,
            fixedStep = fixedStep,
        )
    }
}
