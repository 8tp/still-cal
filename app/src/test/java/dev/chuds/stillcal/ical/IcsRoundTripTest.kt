package dev.chuds.stillcal.ical

import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.Recurrence
import dev.chuds.stillcal.data.ReminderOffset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Spec §13 round-trip guarantee: writer → parser → mapper recovers every Event field.
 * Three synthetic events stress the orthogonal axes: zoned single, all-day single, and
 * a recurring event with VALARM + DESCRIPTION escaping.
 */
class IcsRoundTripTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val zoneId: String = zone.id

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun epochDateOnly(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun roundTrip_singleZoned() {
        val event = Event(
            id = "11111111-1111-1111-1111-111111111111",
            title = "Coffee with K",
            notes = "bring the book; remember stops",
            startEpochMs = epoch(2026, 5, 12, 9, 0),
            endEpochMs = epoch(2026, 5, 12, 10, 0),
            allDay = false,
            tzId = zoneId,
            rrule = null,
            reminder = ReminderOffset.FifteenMin,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_300_000L,
        )
        assertRoundTrip(event)
    }

    @Test
    fun roundTrip_allDay() {
        val event = Event(
            id = "22222222-2222-2222-2222-222222222222",
            title = "vacation start",
            notes = "",
            startEpochMs = epochDateOnly(2026, 7, 4),
            endEpochMs = epochDateOnly(2026, 7, 5),
            allDay = true,
            tzId = zoneId,
            rrule = null,
            reminder = null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_300_000L,
        )
        assertRoundTrip(event)
    }

    @Test
    fun roundTrip_recurringWithReminder() {
        val event = Event(
            id = "33333333-3333-3333-3333-333333333333",
            title = "weekly sync, escape, this; please",
            notes = "first line\nsecond line\nthird",
            startEpochMs = epoch(2026, 5, 11, 15, 30),
            endEpochMs = epoch(2026, 5, 11, 16, 30),
            allDay = false,
            tzId = zoneId,
            rrule = Recurrence.Weekly(LocalDate.of(2026, 8, 1)),
            reminder = ReminderOffset.OneHour,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_300_000L,
        )
        assertRoundTrip(event)
    }

    @Test
    fun malformedDtstartDoesNotCrashMapping() {
        // A VEVENT with a garbage DTSTART value. The parser produces a RawVEvent, but
        // IcsTypes.toEvent throws DateTimeParseException — callers must runCatching this.
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//test//EN
            BEGIN:VEVENT
            UID:bad
            DTSTART:not-a-date
            SUMMARY:bogus
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val raw = IcsParser.parseEvents(ics).single()
        // The mapping throws — confirm it does, so the import path's runCatching is justified.
        val thrown = runCatching { IcsTypes.toEvent(raw, now = 0L, deviceZone = zone) }
            .exceptionOrNull()
        assertNotNull("expected mapping of malformed DTSTART to throw", thrown)
    }

    @Test
    fun unknownTzidFallsBackToDeviceZone() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//test//EN
            BEGIN:VEVENT
            UID:tz
            DTSTART;TZID=Mars/Olympus_Mons:20260512T090000
            DTEND;TZID=Mars/Olympus_Mons:20260512T100000
            SUMMARY:on mars
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val raw = IcsParser.parseEvents(ics).single()
        val event = IcsTypes.toEvent(raw, now = 0L, deviceZone = zone)
        // Falls back to deviceZone, doesn't throw — spec §13 promise.
        assertEquals(zone.id, event.tzId)
    }

    @Test
    fun missingDtendFallsBackToOneHour() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//test//EN
            BEGIN:VEVENT
            UID:nodtend
            DTSTART;TZID=America/New_York:20260512T090000
            SUMMARY:end missing
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val raw = IcsParser.parseEvents(ics).single()
        val event = IcsTypes.toEvent(raw, now = 0L, deviceZone = zone)
        // Spec acceptance: "No crashes when an event's DTEND is missing (treat as DTSTART + 1h)."
        assertEquals(event.startEpochMs + 60 * 60 * 1000L, event.endEpochMs)
    }

    @Test
    fun untilDateSurvivesRoundTripInParisZone() {
        val paris = ZoneId.of("Europe/Paris")
        val event = Event(
            id = "55555555-5555-5555-5555-555555555555",
            title = "weekly in Paris",
            notes = "",
            startEpochMs = LocalDateTime.of(2026, 5, 11, 9, 0).atZone(paris).toInstant().toEpochMilli(),
            endEpochMs = LocalDateTime.of(2026, 5, 11, 10, 0).atZone(paris).toInstant().toEpochMilli(),
            allDay = false,
            tzId = paris.id,
            rrule = Recurrence.Weekly(LocalDate.of(2026, 8, 1)),
            reminder = null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_300_000L,
        )
        val ics = IcsWriter.writeCalendar(event)
        val raw = IcsParser.parseEvents(ics).single()
        // Round-trip in the *event's* zone — the regression we just closed.
        val parsedInParis = IcsTypes.toEvent(raw, now = event.updatedAt, deviceZone = paris)
        assertEquals(LocalDate.of(2026, 8, 1), (parsedInParis.rrule as Recurrence.Weekly).until)
        // And in a totally different device zone — Auckland UTC+12 — UNTIL still resolves
        // back to the original 2026-08-01 because parseUntil now anchors to the event zone.
        val auckland = ZoneId.of("Pacific/Auckland")
        val parsedInAuckland = IcsTypes.toEvent(raw, now = event.updatedAt, deviceZone = auckland)
        assertEquals(LocalDate.of(2026, 8, 1), (parsedInAuckland.rrule as Recurrence.Weekly).until)
    }

    @Test
    fun untilDateForAllDayEventEmitsValueDateForm() {
        val event = Event(
            id = "66666666-6666-6666-6666-666666666666",
            title = "all-day weekly",
            notes = "",
            startEpochMs = epochDateOnly(2026, 5, 11),
            endEpochMs = epochDateOnly(2026, 5, 12),
            allDay = true,
            tzId = zoneId,
            rrule = Recurrence.Weekly(LocalDate.of(2026, 8, 1)),
            reminder = null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_300_000L,
        )
        val ics = IcsWriter.writeCalendar(event)
        // For an all-day event, UNTIL is a DATE per RFC 5545 §3.3.10 — no T, no Z.
        assert(ics.contains("UNTIL=20260801\r\n") || ics.contains("UNTIL=20260801;")) {
            "expected DATE-form UNTIL for all-day event; got:\n$ics"
        }
        val raw = IcsParser.parseEvents(ics).single()
        val parsed = IcsTypes.toEvent(raw, now = event.updatedAt, deviceZone = zone)
        assertEquals(LocalDate.of(2026, 8, 1), (parsed.rrule as Recurrence.Weekly).until)
    }

    @Test
    fun writerOutputParsesBackByteByByteCleanly() {
        val event = Event(
            id = "44444444-4444-4444-4444-444444444444",
            title = "round trip me",
            notes = "",
            startEpochMs = epoch(2026, 5, 12, 9, 0),
            endEpochMs = epoch(2026, 5, 12, 10, 0),
            allDay = false,
            tzId = zoneId,
            rrule = null,
            reminder = ReminderOffset.AtStart,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_300_000L,
        )
        val once = IcsWriter.writeCalendar(event)
        val raw = IcsParser.parseEvents(once).single()
        val parsed = IcsTypes.toEvent(raw, now = event.updatedAt, deviceZone = zone)
        val twice = IcsWriter.writeCalendar(parsed)
        assertEquals(once, twice)
    }

    private fun assertRoundTrip(event: Event) {
        val text = IcsWriter.writeCalendar(event)
        val raw = IcsParser.parseEvents(text).single()
        val parsed = IcsTypes.toEvent(raw, now = event.updatedAt, deviceZone = zone)

        assertEquals("id", event.id, parsed.id)
        assertEquals("title", event.title, parsed.title)
        assertEquals("notes", event.notes, parsed.notes)
        assertEquals("startEpochMs", event.startEpochMs, parsed.startEpochMs)
        assertEquals("endEpochMs", event.endEpochMs, parsed.endEpochMs)
        assertEquals("allDay", event.allDay, parsed.allDay)
        assertEquals("tzId", event.tzId, parsed.tzId)
        assertEquals("rrule", event.rrule, parsed.rrule)
        assertEquals("reminder", event.reminder, parsed.reminder)
        // createdAt and updatedAt aren't carried in the .ics text (DTSTAMP records only
        // updatedAt at second precision and we re-stamp on write). The repository keeps
        // these in the JSON index — round-trip equality is across the .ics fields above.
    }
}
