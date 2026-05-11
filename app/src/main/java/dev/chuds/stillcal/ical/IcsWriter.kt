package dev.chuds.stillcal.ical

import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.Recurrence
import dev.chuds.stillcal.data.ReminderOffset
import java.time.ZoneId

/**
 * Serialize [Event] back to iCalendar text. Output is the exact subset the parser accepts,
 * so a write → parse round-trip recovers every field.
 *
 * Lines longer than 75 octets are folded per RFC 5545 §3.1 by inserting CRLF + single space.
 */
internal object IcsWriter {

    private const val PRODID = "-//8tp//still-cal//EN"
    private const val LINE_ENDING = "\r\n"
    private const val MAX_OCTETS = 75

    /**
     * Wrap a single VEVENT body in a VCALENDAR envelope. This is what per-event files on disk
     * look like, and what single-event SAF export emits.
     */
    fun writeCalendar(event: Event): String = buildString {
        append("BEGIN:VCALENDAR").append(LINE_ENDING)
        append("VERSION:2.0").append(LINE_ENDING)
        append("PRODID:$PRODID").append(LINE_ENDING)
        appendEvent(this, event)
        append("END:VCALENDAR").append(LINE_ENDING)
    }

    /**
     * Bulk export — every event in one envelope.
     */
    fun writeCalendarBulk(events: List<Event>): String = buildString {
        append("BEGIN:VCALENDAR").append(LINE_ENDING)
        append("VERSION:2.0").append(LINE_ENDING)
        append("PRODID:$PRODID").append(LINE_ENDING)
        events.forEach { appendEvent(this, it) }
        append("END:VCALENDAR").append(LINE_ENDING)
    }

    private fun appendEvent(out: StringBuilder, event: Event) {
        val zone = runCatching { ZoneId.of(event.tzId) }.getOrNull() ?: ZoneId.systemDefault()
        val effectiveZoneId = zone.id

        out.writeIcsLine("BEGIN:VEVENT")
        out.writeIcsLine("UID:${event.id}")
        out.writeIcsLine("DTSTAMP:${IcsTypes.formatUtcStamp(event.updatedAt)}")

        if (event.allDay) {
            out.writeIcsLine("DTSTART;VALUE=DATE:${IcsTypes.formatDate(event.startEpochMs, zone)}")
            out.writeIcsLine("DTEND;VALUE=DATE:${IcsTypes.formatDate(event.endEpochMs, zone)}")
        } else {
            out.writeIcsLine("DTSTART;TZID=$effectiveZoneId:${IcsTypes.formatZoned(event.startEpochMs, zone)}")
            out.writeIcsLine("DTEND;TZID=$effectiveZoneId:${IcsTypes.formatZoned(event.endEpochMs, zone)}")
        }

        out.writeIcsLine("SUMMARY:${IcsTypes.escapeText(event.title)}")
        if (event.notes.isNotEmpty()) {
            out.writeIcsLine("DESCRIPTION:${IcsTypes.escapeText(event.notes)}")
        }
        event.rrule?.let { out.writeIcsLine("RRULE:${formatRrule(it, zone, event.allDay)}") }
        event.reminder?.let { appendAlarm(out, it) }
        out.writeIcsLine("END:VEVENT")
    }

    private fun appendAlarm(out: StringBuilder, reminder: ReminderOffset) {
        out.writeIcsLine("BEGIN:VALARM")
        out.writeIcsLine("ACTION:DISPLAY")
        out.writeIcsLine("TRIGGER:${formatTrigger(reminder)}")
        out.writeIcsLine("DESCRIPTION:reminder")
        out.writeIcsLine("END:VALARM")
    }

    private fun formatTrigger(reminder: ReminderOffset): String = when (reminder) {
        ReminderOffset.AtStart -> "PT0M"
        ReminderOffset.FiveMin -> "-PT5M"
        ReminderOffset.FifteenMin -> "-PT15M"
        ReminderOffset.OneHour -> "-PT1H"
        ReminderOffset.OneDay -> "-P1D"
    }

    private fun formatRrule(r: Recurrence, zone: ZoneId, allDay: Boolean): String {
        val freq = when (r) {
            is Recurrence.Daily -> "DAILY"
            is Recurrence.Weekly -> "WEEKLY"
            is Recurrence.Monthly -> "MONTHLY"
        }
        return "FREQ=$freq;UNTIL=${IcsTypes.formatUntil(r.until, zone, allDay)}"
    }

    private fun StringBuilder.writeIcsLine(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_OCTETS) {
            append(content).append(LINE_ENDING)
            return
        }
        // Fold to 75 octets per UTF-8 byte length, not character count.
        var i = 0
        var first = true
        while (i < bytes.size) {
            val chunkSize = minOf(if (first) MAX_OCTETS else MAX_OCTETS - 1, bytes.size - i)
            // Don't split inside a multibyte UTF-8 sequence — back up to a code-point boundary.
            var end = i + chunkSize
            while (end > i && end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
            val piece = String(bytes, i, end - i, Charsets.UTF_8)
            if (!first) append(' ')
            append(piece)
            append(LINE_ENDING)
            i = end
            first = false
        }
    }
}
