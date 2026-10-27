package ch.nokillswit

import ch.nokillswit.reports.ComparePeopleReport
import ch.nokillswit.reports.CompareMixRow
import ch.nokillswit.reports.PersonTrendMixSeries
import ch.nokillswit.reports.PersonTrendReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals

/*
 * Shared by the Person trend (report 20) tests: the two GETs and THE one-truth check -- every point of every row (and every work-mix
 * point) equals the person's side-A cell of `GET /compare-people` over that point's own window.
 */

internal suspend fun HttpClient.personTrend(query: String): PersonTrendReport {
    val response = get("/api/v1/reports/person-trend?$query")
    assertEquals(HttpStatusCode.OK, response.status, "GET person-trend?$query: ${response.bodyAsText()}")
    return response.body()
}

internal suspend fun HttpClient.comparePeopleReport(query: String): ComparePeopleReport {
    val response = get("/api/v1/reports/compare-people?$query")
    assertEquals(HttpStatusCode.OK, response.status, "GET compare-people?$query: ${response.bodyAsText()}")
    return response.body()
}

/** What [assertPersonTrendIsComparePeople] compared: the grid, the figures and the work-mix points. */
internal class OneTruthCount(val windows: Int, val cells: Int, val mixPoints: Int)

/**
 * THE one-truth gate. For every window of [trend] it asks `GET /compare-people` for `[person, other]` over `from=window.from&to=window.end`
 * (plus [slice], the connection and slice parameters the trend was asked with) and requires, for EVERY row of the trend, that the point's
 * `value`, `n` and `hidden` equal side A's cell of the same row exactly, and that the row's metadata (group, unit, direction, target,
 * tolerance, drill) is Compare people's own; then every work-mix point equals Compare people's `workMix` side A for its key
 * (`md`/`share`; a key the window did not deliver is `md` 0). Nothing is compared with a tolerance: both sides run the same function.
 */
internal suspend fun HttpClient.assertPersonTrendIsComparePeople(
    trend: PersonTrendReport,
    person: String,
    other: String,
    slice: String,
): OneTruthCount {
    var cells = 0
    var mixPoints = 0
    trend.windows.forEachIndexed { i, window ->
        val compare = comparePeopleReport("$slice&from=${window.from}&to=${window.end}&accountId=$person&accountId=$other")
        assertEquals(person, compare.people.first().accountId)
        for (row in trend.rows) {
            val want = compare.rows.single { it.key == row.key }
            val label = "${row.key} @ ${window.end}"
            assertEquals(want.group, row.group, "$label group")
            assertEquals(want.unit, row.unit, "$label unit")
            assertEquals(want.direction, row.direction, "$label direction")
            assertEquals(want.target, row.target, "$label target")
            assertEquals(want.tolerance, row.tolerance, "$label tolerance")
            assertEquals(want.drill, row.drill, "$label drill")
            val point = row.points[i]
            assertEquals(window.end, point.end, "$label end")
            assertEquals(want.a.value, point.value, "$label value")
            assertEquals(want.a.n, point.n, "$label n")
            assertEquals(want.a.hidden, point.hidden, "$label hidden")
            cells++
        }
        mixPoints += assertMixIsCompareMix(trend.workMix.activityType, compare.workMix.activityType, i, window.end, "activityType")
        mixPoints += assertMixIsCompareMix(trend.workMix.workCategory, compare.workMix.workCategory, i, window.end, "workCategory")
    }
    return OneTruthCount(trend.windows.size, cells, mixPoints)
}

private fun assertMixIsCompareMix(
    series: List<PersonTrendMixSeries>,
    compare: List<CompareMixRow>,
    index: Int,
    end: String,
    what: String,
): Int {
    val delivered = compare.filter { it.a.items > 0 }.mapNotNull { it.key }.toSet()
    assertEquals(true, series.map { it.key }.toSet().containsAll(delivered), "$what @ $end: a series for every delivered key")
    val anyShare = compare.any { it.a.share != null }
    for (one in series) {
        val want = compare.firstOrNull { it.key == one.key }?.a
        val point = one.points[index]
        assertEquals(end, point.end, "$what ${one.key} end")
        assertEquals(want?.md ?: 0.0, point.md, "$what ${one.key} @ $end md")
        assertEquals(want?.share ?: if (anyShare) 0.0 else null, point.share, "$what ${one.key} @ $end share")
    }
    return series.size
}
