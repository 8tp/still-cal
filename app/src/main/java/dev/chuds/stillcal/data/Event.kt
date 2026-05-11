package dev.chuds.stillcal.data

import java.time.LocalDate

/**
 * One event. Body lives at filesDir/events/<id>.ics; the JSON index holds the metadata
 * the list/grid screens render from without parsing every file.
 *
 * startEpochMs / endEpochMs are UTC instants. When [allDay] is true they snap to local
 * midnight in [tzId]; the end is exclusive, mirroring the .ics convention.
 */
data class Event(
    val id: String,
    val title: String,
    val notes: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
    val tzId: String,
    val rrule: Recurrence?,
    val reminder: ReminderOffset?,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface Recurrence {
    val until: LocalDate
    data class Daily(override val until: LocalDate) : Recurrence
    data class Weekly(override val until: LocalDate) : Recurrence
    data class Monthly(override val until: LocalDate) : Recurrence
}

enum class ReminderOffset(val minutesBefore: Int, val label: String) {
    AtStart(0, "at start"),
    FiveMin(5, "5 min before"),
    FifteenMin(15, "15 min before"),
    OneHour(60, "1 hour before"),
    OneDay(60 * 24, "1 day before"),
}

enum class RecurrenceKind { None, Daily, Weekly, Monthly }

fun Recurrence?.kind(): RecurrenceKind = when (this) {
    null -> RecurrenceKind.None
    is Recurrence.Daily -> RecurrenceKind.Daily
    is Recurrence.Weekly -> RecurrenceKind.Weekly
    is Recurrence.Monthly -> RecurrenceKind.Monthly
}
