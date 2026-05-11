package dev.chuds.stillcal.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the recurrence-expansion fast-forward (Tier A #14).
 *
 * The old implementation walked day-by-day from the event's original start, hitting an
 * arbitrary hard cap that made recurring events older than ~3 years silently vanish from
 * the grid. These tests call the pure companion-object math directly — no Android
 * Context needed.
 */
class RecurrenceExpansionTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    @Test
    fun veryOldDailyEventRendersInTodaysMonth() {
        // A daily event starting 2010-01-01 with UNTIL=2040-01-01 — ~11,000 occurrences.
        // The previous walk hit hardCap=1000 well before reaching 2026.
        val event = dailyEvent(
            start = LocalDate.of(2010, 1, 1),
            until = LocalDate.of(2040, 1, 1),
        )
        val may2026 = LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31)
        val dates = EventsRepository.occurrencesIntersecting(event, may2026)
        assertEquals(31, dates.size)
        assertTrue(LocalDate.of(2026, 5, 1) in dates)
        assertTrue(LocalDate.of(2026, 5, 31) in dates)
    }

    @Test
    fun veryOldDailyEventReminderResolves() {
        val event = dailyEvent(
            start = LocalDate.of(2010, 1, 1),
            until = LocalDate.of(2040, 1, 1),
        )
        val now = LocalDateTime.of(2026, 5, 10, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val next = EventsRepository.nextOccurrenceMs(event, fromMs = now)
        // 09:00 anchor + noon target on 5/10 → next is 5/11 at 09:00.
        assertNotNull("expected a next-occurrence within UNTIL", next)
        val nextDate = java.time.Instant.ofEpochMilli(next!!).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2026, 5, 11), nextDate)
    }

    @Test
    fun weeklyAnchorPreservesDayOfWeekAfterSeek() {
        // 2010-01-01 was a Friday. Weekly Fridays seeking to May 2026.
        val event = weeklyEvent(
            start = LocalDate.of(2010, 1, 1),
            until = LocalDate.of(2040, 1, 1),
        )
        val may2026 = LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31)
        val dates = EventsRepository.occurrencesIntersecting(event, may2026)
        assertEquals(listOf(1, 8, 15, 22, 29), dates.map { it.dayOfMonth })
    }

    @Test
    fun monthlyAnchorPreservesDayOfMonthAfterSeek() {
        val event = monthlyEvent(
            start = LocalDate.of(2010, 6, 15),
            until = LocalDate.of(2040, 1, 1),
        )
        val may2026 = LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31)
        val dates = EventsRepository.occurrencesIntersecting(event, may2026)
        assertEquals(listOf(LocalDate.of(2026, 5, 15)), dates)
    }

    @Test
    fun nonRecurringEventBeforeRangeReturnsNothing() {
        val event = oneShotEvent(LocalDate.of(2020, 1, 1))
        val may2026 = LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31)
        assertEquals(emptyList<LocalDate>(), EventsRepository.occurrencesIntersecting(event, may2026))
    }

    @Test
    fun nonRecurringEventStartingInsideRange() {
        val event = oneShotEvent(LocalDate.of(2026, 5, 15))
        val may2026 = LocalDate.of(2026, 5, 1)..LocalDate.of(2026, 5, 31)
        assertEquals(listOf(LocalDate.of(2026, 5, 15)), EventsRepository.occurrencesIntersecting(event, may2026))
    }

    @Test
    fun nextOccurrenceMsForOneShotEventInTheFuture() {
        val event = oneShotEvent(LocalDate.of(2026, 5, 20))
        val now = LocalDateTime.of(2026, 5, 10, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val next = EventsRepository.nextOccurrenceMs(event, fromMs = now)
        assertNotNull(next)
        assertEquals(event.startEpochMs, next)
    }

    @Test
    fun nextOccurrenceMsForOneShotEventInThePastReturnsNull() {
        val event = oneShotEvent(LocalDate.of(2020, 1, 1))
        val now = LocalDateTime.of(2026, 5, 10, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(null, EventsRepository.nextOccurrenceMs(event, fromMs = now))
    }

    private fun dailyEvent(start: LocalDate, until: LocalDate): Event = baseEvent(start).copy(
        rrule = Recurrence.Daily(until),
    )

    private fun weeklyEvent(start: LocalDate, until: LocalDate): Event = baseEvent(start).copy(
        rrule = Recurrence.Weekly(until),
    )

    private fun monthlyEvent(start: LocalDate, until: LocalDate): Event = baseEvent(start).copy(
        rrule = Recurrence.Monthly(until),
    )

    private fun oneShotEvent(start: LocalDate): Event = baseEvent(start)

    private fun baseEvent(start: LocalDate): Event {
        val s = start.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val e = start.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        return Event(
            id = java.util.UUID.randomUUID().toString(),
            title = "test",
            notes = "",
            startEpochMs = s,
            endEpochMs = e,
            allDay = false,
            tzId = zone.id,
            rrule = null,
            reminder = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }
}
