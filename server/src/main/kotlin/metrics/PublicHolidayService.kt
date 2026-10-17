package ch.nokillswit.metrics

import ch.nokillswit.infra.db.nowMillis
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val PublicHolidayServiceKey = AttributeKey<PublicHolidayService>("PublicHolidayService")

/**
 * The public-holiday dictionary (`metrics.public_holidays`, V25): flat ADMIN-curated dates, hard-deleting (the Lettuce registry
 * exception to the soft-delete convention, `.claude/docs/persistence.md`). The table object is also read by
 * [MetricsSettingsService.readWithCalendar], the one builder of the working calendar. Every create and delete moves the calendar
 * under every derived number, so each bumps the shared revision ([MetricsSettingsService.bumpRevision]: DERIVE for every enabled
 * connection, exactly like a settings PUT) inside its own transaction. A duplicate date raises 23505 -> the central 409 mapping,
 * rolling the insert back before any bump.
 */
class PublicHolidayService(
    private val database: R2dbcDatabase,
    private val metricsSettings: MetricsSettingsService,
) {
    object PublicHolidays : UIntIdTable("metrics.public_holidays") {
        val holidayDate = varchar("holiday_date", 10)
        val name = varchar("name", MAX_PUBLIC_HOLIDAY_NAME_LENGTH)
        val createdAt = long("created_at")
    }

    /** The whole dictionary, oldest date first (dates are unique, so the order is total). */
    suspend fun list(): List<PublicHolidayItem> = suspendTransaction(database) {
        PublicHolidays.selectAll().orderBy(PublicHolidays.holidayDate to SortOrder.ASC).toList().map { it.toItem() }
    }

    /** Inserts a holiday (shape re-checked here) and bumps the revision in the same transaction; returns the stored item. */
    suspend fun create(request: PublicHolidayCreateRequest): PublicHolidayItem = suspendTransaction(database) {
        validatePublicHoliday(request)
        val id = PublicHolidays.insert {
            it[holidayDate] = request.date
            it[name] = request.name
            it[createdAt] = nowMillis()
        }[PublicHolidays.id].value
        metricsSettings.bumpRevision()
        PublicHolidayItem(id, request.date, request.name)
    }

    /** Hard-deletes a holiday and bumps the revision in the same transaction; returns the removed item, null when it did not exist. */
    suspend fun delete(id: UInt): PublicHolidayItem? = suspendTransaction(database) {
        val existing = PublicHolidays.selectAll().where { PublicHolidays.id eq id }.toList().singleOrNull()?.toItem()
        if (existing == null || PublicHolidays.deleteWhere { PublicHolidays.id eq id } == 0) return@suspendTransaction null
        metricsSettings.bumpRevision()
        existing
    }

    private fun ResultRow.toItem() = PublicHolidayItem(
        id = this[PublicHolidays.id].value,
        date = this[PublicHolidays.holidayDate],
        name = this[PublicHolidays.name],
    )
}
