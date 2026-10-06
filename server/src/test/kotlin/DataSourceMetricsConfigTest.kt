package ch.nokillswit

import ch.nokillswit.metrics.MetricsBoardTeamMapping
import ch.nokillswit.metrics.MetricsConfigReferenceData
import ch.nokillswit.metrics.MetricsDomainMapping
import ch.nokillswit.metrics.MetricsFieldConfig
import ch.nokillswit.metrics.MetricsActivityTypeMapping
import ch.nokillswit.metrics.MetricsSprintCapacity
import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsStatusStage
import ch.nokillswit.metrics.MetricsWorkCategoryMapping
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.validateDataSourceMetricsConfig
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `metrics/DataSourceMetricsConfig.kt`'s `validateDataSourceMetricsConfig` (v0.3.0 M1 commit 4) —
 * a PURE function, deliberately unit-testable without a database
 * ([MetricsConfigReferenceData]'s own doc): every rejection branch, one test each, plus a fully
 * valid request that must NOT throw.
 */
class DataSourceMetricsConfigTest {

    private fun ref(
        statusIds: Set<String> = setOf("10001", "10002"),
        fieldIds: Set<String> = setOf("duedate", "customfield_10001"),
        projectKeys: Set<String> = setOf("ENG"),
        boardIds: Set<Long> = setOf(500L),
        issueTypes: Set<String> = setOf("Story"),
        sprintIds: Set<Long> = setOf(900L),
        activeTeamIds: Set<UInt> = setOf(1u),
        workCategoryValueIds: Set<String>? = setOf("opt-1"),
    ) = MetricsConfigReferenceData(statusIds, fieldIds, projectKeys, boardIds, issueTypes, sprintIds, activeTeamIds, workCategoryValueIds)

    @Test
    fun `a fully valid request does not throw`() {
        val request = DataSourceMetricsConfigRequest(
            statusStages = listOf(MetricsStatusStage("10001", MetricsStage.NOT_STARTED)),
            fields = MetricsFieldConfig(estimateTask = "duedate", workCategory = "customfield_10001"),
            domains = listOf(MetricsDomainMapping("ENG", "eng", "Engineering")),
            boards = listOf(MetricsBoardTeamMapping(500L, 1u)),
            activityTypes = listOf(MetricsActivityTypeMapping("Story", "Story")),
            workCategories = listOf(MetricsWorkCategoryMapping("opt-1", "Option 1", "Product Development")),
            blockedStatuses = listOf("10002"),
            sprintCapacities = listOf(MetricsSprintCapacity(900L, 5.0)),
        )
        validateDataSourceMetricsConfig(request, ref())
    }

    @Test
    fun `an unknown status id in statusStages is 400`() {
        val request = DataSourceMetricsConfigRequest(statusStages = listOf(MetricsStatusStage("nope", MetricsStage.DONE)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `an unknown field id in any role is 400`() {
        listOf(
            MetricsFieldConfig(estimateTask = "nope"),
            MetricsFieldConfig(estimateEpic = "nope"),
            MetricsFieldConfig(epicStart = "nope"),
            MetricsFieldConfig(epicDue = "nope"),
            MetricsFieldConfig(workCategory = "nope"),
        ).forEach { fields ->
            assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(DataSourceMetricsConfigRequest(fields = fields), ref()) }
        }
    }

    @Test
    fun `an unknown project key in domains is 400`() {
        val request = DataSourceMetricsConfigRequest(domains = listOf(MetricsDomainMapping("NOPE", "d", "D")))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `an unknown or inactive owner team id in domains is 400`() {
        val unknown = DataSourceMetricsConfigRequest(domains = listOf(MetricsDomainMapping("ENG", "eng", "Engineering", 999u)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(unknown, ref()) }
    }

    @Test
    fun `an active owner team id in domains does not throw`() {
        val request = DataSourceMetricsConfigRequest(domains = listOf(MetricsDomainMapping("ENG", "eng", "Engineering", 1u)))
        validateDataSourceMetricsConfig(request, ref())
    }

    @Test
    fun `a same-domain owner disagreement is 400`() {
        val request = DataSourceMetricsConfigRequest(
            domains = listOf(
                MetricsDomainMapping("ENG", "shared", "Shared", 1u),
                MetricsDomainMapping("OPS", "shared", "Shared", 2u),
            ),
        )
        assertFailsWith<BadRequestException> {
            validateDataSourceMetricsConfig(request, ref(projectKeys = setOf("ENG", "OPS"), activeTeamIds = setOf(1u, 2u)))
        }
    }

    @Test
    fun `a same-domain owner disagreement between a set and an unset row is 400`() {
        val request = DataSourceMetricsConfigRequest(
            domains = listOf(MetricsDomainMapping("ENG", "shared", "Shared", 1u), MetricsDomainMapping("OPS", "shared", "Shared")),
        )
        assertFailsWith<BadRequestException> {
            validateDataSourceMetricsConfig(request, ref(projectKeys = setOf("ENG", "OPS")))
        }
    }

    @Test
    fun `same-domain rows agreeing on one owner (or all unset) do not throw`() {
        val agreeing = DataSourceMetricsConfigRequest(
            domains = listOf(
                MetricsDomainMapping("ENG", "shared", "Shared", 1u),
                MetricsDomainMapping("OPS", "shared", "Shared", 1u),
            ),
        )
        validateDataSourceMetricsConfig(agreeing, ref(projectKeys = setOf("ENG", "OPS")))

        val allUnset = DataSourceMetricsConfigRequest(
            domains = listOf(MetricsDomainMapping("ENG", "shared", "Shared"), MetricsDomainMapping("OPS", "shared", "Shared")),
        )
        validateDataSourceMetricsConfig(allUnset, ref(projectKeys = setOf("ENG", "OPS")))
    }

    @Test
    fun `an unknown board id in boards is 400`() {
        val request = DataSourceMetricsConfigRequest(boards = listOf(MetricsBoardTeamMapping(999_999L, 1u)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `an unknown or inactive team id in boards is 400`() {
        val request = DataSourceMetricsConfigRequest(boards = listOf(MetricsBoardTeamMapping(500L, 999u)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `an unknown issue type in activityTypes is 400`() {
        val request = DataSourceMetricsConfigRequest(activityTypes = listOf(MetricsActivityTypeMapping("Nope", "Nope")))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `workCategories present while fields-workCategory is unset is 400`() {
        val request = DataSourceMetricsConfigRequest(
            fields = MetricsFieldConfig(workCategory = null),
            workCategories = listOf(MetricsWorkCategoryMapping("opt-1", "Option 1", "Product Development")),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `an unknown work category value id is 400`() {
        val request = DataSourceMetricsConfigRequest(
            fields = MetricsFieldConfig(workCategory = "customfield_10001"),
            workCategories = listOf(MetricsWorkCategoryMapping("nope", "Nope", "Cost of Poor Quality")),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `a null workCategoryValueIds (no field chosen yet) never rejects a value id on its own`() {
        // workCategoryValueIds == null means "field not configured" — the earlier "requires fields.workCategory" check
        // fires first for a non-empty workCategories list, so this exercises the ref.workCategoryValueIds == null branch
        // via an EMPTY workCategories list (nothing to check against the null set).
        val request = DataSourceMetricsConfigRequest(fields = MetricsFieldConfig(workCategory = null), workCategories = emptyList())
        validateDataSourceMetricsConfig(request, ref(workCategoryValueIds = null))
    }

    @Test
    fun `an unknown status id in blockedStatuses is 400`() {
        val request = DataSourceMetricsConfigRequest(blockedStatuses = listOf("nope"))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `an unknown sprint id in sprintCapacities is 400`() {
        val request = DataSourceMetricsConfigRequest(sprintCapacities = listOf(MetricsSprintCapacity(999_999L, 5.0)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `a negative capacityMd is 400`() {
        val request = DataSourceMetricsConfigRequest(sprintCapacities = listOf(MetricsSprintCapacity(900L, -1.0)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `capacityMd is bounded by NUMERIC(8,2) - overflow, non-finite and more than two decimals are 400, the limits pass`() {
        fun capacity(md: Double) = DataSourceMetricsConfigRequest(sprintCapacities = listOf(MetricsSprintCapacity(900L, md)))
        listOf(1e7, 1_000_000.0, 999_999.995, Double.NaN, Double.POSITIVE_INFINITY, 1.005, 0.001).forEach { md ->
            assertFailsWith<BadRequestException>("capacityMd $md must be rejected") { validateDataSourceMetricsConfig(capacity(md), ref()) }
        }
        listOf(0.0, 0.01, 10.10, 42.0, 999_999.99).forEach { md -> validateDataSourceMetricsConfig(capacity(md), ref()) }
    }

    @Test
    fun `domainKey, domainName and category are bounded by their column widths`() {
        fun domain(key: String, name: String) = DataSourceMetricsConfigRequest(domains = listOf(MetricsDomainMapping("ENG", key, name)))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(domain("k".repeat(51), "n"), ref()) }
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(domain("k", "n".repeat(101)), ref()) }
        validateDataSourceMetricsConfig(domain("k".repeat(50), "n".repeat(100)), ref())

        fun category(category: String) = DataSourceMetricsConfigRequest(
            fields = MetricsFieldConfig(workCategory = "customfield_10001"),
            workCategories = listOf(MetricsWorkCategoryMapping("opt-1", null, category)),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(category("c".repeat(101)), ref()) }
        validateDataSourceMetricsConfig(category("c".repeat(100)), ref())
    }

    @Test
    fun `a duplicate statusId within statusStages is 400`() {
        val request = DataSourceMetricsConfigRequest(
            statusStages = listOf(MetricsStatusStage("10001", MetricsStage.NOT_STARTED), MetricsStatusStage("10001", MetricsStage.DONE)),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `a duplicate projectKey within domains is 400`() {
        val request = DataSourceMetricsConfigRequest(
            domains = listOf(MetricsDomainMapping("ENG", "a", "A"), MetricsDomainMapping("ENG", "b", "B")),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `a duplicate boardId within boards is 400 - but the SAME team on two DIFFERENT boards is not pre-empted here`() {
        val duplicateBoard = DataSourceMetricsConfigRequest(
            boards = listOf(MetricsBoardTeamMapping(500L, 1u), MetricsBoardTeamMapping(500L, 1u)),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(duplicateBoard, ref(boardIds = setOf(500L))) }

        // Two DIFFERENT boards claiming the SAME team must NOT be rejected here — that clash is
        // D10's "one board per team", enforced as a 409 at the database (uq_metrics_board_team_map_team_id),
        // never pre-empted by this pure validator.
        val sameTeamDifferentBoards = DataSourceMetricsConfigRequest(
            boards = listOf(MetricsBoardTeamMapping(500L, 1u), MetricsBoardTeamMapping(501L, 1u)),
        )
        validateDataSourceMetricsConfig(sameTeamDifferentBoards, ref(boardIds = setOf(500L, 501L)))
    }

    @Test
    fun `a duplicate issueType within activityTypes is 400`() {
        val request = DataSourceMetricsConfigRequest(
            activityTypes = listOf(MetricsActivityTypeMapping("Story", "A"), MetricsActivityTypeMapping("Story", "B")),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `a duplicate valueId within workCategories is 400`() {
        val request = DataSourceMetricsConfigRequest(
            fields = MetricsFieldConfig(workCategory = "customfield_10001"),
            workCategories = listOf(
                MetricsWorkCategoryMapping("opt-1", "A", "Product Development"),
                MetricsWorkCategoryMapping("opt-1", "B", "Maintenance"),
            ),
        )
        assertFailsWith<BadRequestException> {
            validateDataSourceMetricsConfig(request, ref(workCategoryValueIds = setOf("opt-1")))
        }
    }

    @Test
    fun `a duplicate statusId within blockedStatuses is 400`() {
        val request = DataSourceMetricsConfigRequest(blockedStatuses = listOf("10001", "10001"))
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }

    @Test
    fun `a duplicate sprintId within sprintCapacities is 400`() {
        val request = DataSourceMetricsConfigRequest(
            sprintCapacities = listOf(MetricsSprintCapacity(900L, 1.0), MetricsSprintCapacity(900L, 2.0)),
        )
        assertFailsWith<BadRequestException> { validateDataSourceMetricsConfig(request, ref()) }
    }
}
