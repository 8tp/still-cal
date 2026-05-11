package dev.chuds.stillcal.ical

import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.Recurrence
import dev.chuds.stillcal.data.ReminderOffset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Mapping between iCalendar text and our [Event]. Tolerant on the way in (missing DTEND,
 * unknown timezone, no RRULE), strict-but-narrow on the way out.
 *
 * Open-question resolution (spec §15.1): we always write zone-anchored TZID times so a
 * round-trip produces byte-identical output. On import we still accept floating-time;
 * we anchor those to the device zone at read time and re-emit with TZID on next save.
 */
internal object IcsTypes {

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    val DATE_TIME_UTC_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /**
     * Convert a [RawVEvent] into an [Event]. [now] supplies fallbacks for createdAt/updatedAt
     * when the .ics doesn't carry DTSTAMP. [deviceZone] anchors floating-time values.
     */
    fun toEvent(
        raw: RawVEvent,
        now: Long,
        deviceZone: ZoneId,
    ): Event {
        val byName: Map<String, RawProperty> = raw.properties.associateBy { it.name }

        val uid = byName["UID"]?.value?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val title = unescapeText(byName["SUMMARY"]?.value ?: "")
        val notes = unescapeText(byName["DESCRIPTION"]?.value ?: "")

        val dtStartProp = byName["DTSTART"]
            ?: return Event(
                id = uid,
                title = title,
                notes = notes,
                startEpochMs = now,
                endEpochMs = now + 60 * 60 * 1000L,
                allDay = false,
                tzId = deviceZone.id,
                rrule = null,
                reminder = parseFirstAlarm(raw.alarms),
                createdAt = now,
                updatedAt = now,
            )

        val parsedStart = parseDateProperty(dtStartProp, deviceZone)
        val dtEndProp = byName["DTEND"]
        val parsedEnd = dtEndProp?.let { parseDateProperty(it, deviceZone) }
            ?: defaultEndFor(parsedStart)

        val eventZone = parsedStart.zoneId ?: deviceZone
        val rrule = byName["RRULE"]?.let { parseRrule(it.value, parsedStart.localDate(), eventZone) }
        val reminder = parseFirstAlarm(raw.alarms)
        val createdMs = byName["CREATED"]?.value?.let { parseInstantMs(it, deviceZone) } ?: now
        val updatedMs = byName["LAST-MODIFIED"]?.value?.let { parseInstantMs(it, deviceZone) }
            ?: byName["DTSTAMP"]?.value?.let { parseInstantMs(it, deviceZone) }
            ?: now

        // Preserve the zone the event was authored in. For floating time, anchor to device zone.
        val authoredZone = parsedStart.zoneId ?: deviceZone

        return Event(
            id = uid,
            title = title,
            notes = notes,
            startEpochMs = parsedStart.epochMs,
            endEpochMs = parsedEnd.epochMs,
            allDay = parsedStart.allDay,
            tzId = authoredZone.id,
            rrule = rrule,
            reminder = reminder,
            createdAt = createdMs,
            updatedAt = updatedMs,
        )
    }

    /**
     * Parsed shape of a DTSTART/DTEND property, ready to be turned into Event fields.
     */
    internal data class ParsedDateTime(
        val epochMs: Long,
        val allDay: Boolean,
        val zoneId: ZoneId?,
    ) {
        fun localDate(): LocalDate {
            val zone = zoneId ?: ZoneOffset.UTC
            return java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        }
    }

    internal fun parseDateProperty(prop: RawProperty, deviceZone: ZoneId): ParsedDateTime {
        val raw = prop.value.trim()
        val valueType = prop.params["VALUE"]
        val isDateValue = valueType.equals("DATE", ignoreCase = true) ||
            (raw.length == 8 && !raw.contains('T'))

        if (isDateValue) {
            val date = LocalDate.parse(raw, DATE_FORMATTER)
            val zone = prop.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone
            val epoch = date.atStartOfDay(zone).toInstant().toEpochMilli()
            return ParsedDateTime(epoch, allDay = true, zoneId = zone)
        }

        val isUtc = raw.endsWith("Z")
        val core = if (isUtc) raw.dropLast(1) else raw
        val ldt = LocalDateTime.parse(core, DATE_TIME_FORMATTER)
        val zone = when {
            isUtc -> ZoneOffset.UTC
            else -> prop.params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: deviceZone
        }
        val epoch = ldt.atZone(zone).toInstant().toEpochMilli()
        return ParsedDateTime(epoch, allDay = false, zoneId = zone)
    }

    private fun defaultEndFor(start: ParsedDateTime): ParsedDateTime {
        // Spec acceptance: missing DTEND → DTSTART + 1h, or +1 day for all-day events.
        val delta = if (start.allDay) 24L * 60 * 60 * 1000 else 60L * 60 * 1000
        return start.copy(epochMs = start.epochMs + delta)
    }

    private fun parseRrule(value: String, anchor: LocalDate, eventZone: ZoneId): Recurrence? {
        val parts = value.split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1)
        }.toMap()

        val freq = parts["FREQ"]?.uppercase() ?: return null
        val until: LocalDate = parts["UNTIL"]?.let { parseUntil(it, eventZone) }
            ?: parts["COUNT"]?.toIntOrNull()?.let { count ->
                projectCountToUntil(freq, anchor, count.coerceAtLeast(1))
            }
            ?: anchor.plusYears(2)

        return when (freq) {
            "DAILY" -> Recurrence.Daily(until)
            "WEEKLY" -> Recurrence.Weekly(until)
            "MONTHLY" -> Recurrence.Monthly(until)
            else -> null
        }
    }

    private fun projectCountToUntil(freq: String, anchor: LocalDate, count: Int): LocalDate {
        val occurrences = count - 1
        return when (freq.uppercase()) {
            "DAILY" -> anchor.plusDays(occurrences.toLong())
            "WEEKLY" -> anchor.plusWeeks(occurrences.toLong())
            "MONTHLY" -> anchor.plusMonths(occurrences.toLong())
            else -> anchor.plusYears(1)
        }
    }

    private fun parseUntil(value: String, eventZone: ZoneId): LocalDate {
        val raw = value.trim()
        return when {
            // VALUE=DATE form — written for all-day events. No zone conversion.
            raw.length == 8 -> LocalDate.parse(raw, DATE_FORMATTER)
            // UTC datetime form — anchor at the *event's* zone (not the device's) so a
            // calendar created in Paris and imported in Auckland keeps its UNTIL date.
            raw.endsWith("Z") -> LocalDateTime.parse(raw.dropLast(1), DATE_TIME_FORMATTER)
                .atZone(ZoneOffset.UTC).withZoneSameInstant(eventZone).toLocalDate()
            // Floating datetime — treat as event-local.
            else -> LocalDateTime.parse(raw, DATE_TIME_FORMATTER).toLocalDate()
        }
    }

    private fun parseFirstAlarm(alarms: List<RawVAlarm>): ReminderOffset? {
        for (alarm in alarms) {
            val trigger = alarm.properties.firstOrNull { it.name == "TRIGGER" }?.value ?: continue
            val minutes = parseTriggerMinutes(trigger) ?: continue
            return matchReminderOffset(minutes)
        }
        return null
    }

    internal fun parseTriggerMinutes(trigger: String): Int? {
        // We handle the durations our own writer emits plus the ones Google Calendar /
        // Fastmail / Fossify commonly emit: -PT15M, -PT1H, -P1D, -P2W, -PT0M / PT0S.
        val t = trigger.trim()
        if (!t.startsWith("-P") && !t.startsWith("P")) return null
        val negative = t.startsWith("-P")
        val body = if (negative) t.drop(2) else t.drop(1)
        var weeks = 0L
        var days = 0L
        var hours = 0L
        var minutes = 0L
        var seconds = 0L
        var afterT = false
        var num = StringBuilder()
        for (ch in body) {
            when {
                ch == 'T' -> afterT = true
                ch.isDigit() -> num.append(ch)
                ch == 'W' && !afterT -> { weeks = num.toString().toLongOrNull() ?: 0; num.setLength(0) }
                ch == 'D' -> { days = num.toString().toLongOrNull() ?: 0; num.setLength(0) }
                ch == 'H' && afterT -> { hours = num.toString().toLongOrNull() ?: 0; num.setLength(0) }
                ch == 'M' && afterT -> { minutes = num.toString().toLongOrNull() ?: 0; num.setLength(0) }
                ch == 'S' && afterT -> { seconds = num.toString().toLongOrNull() ?: 0; num.setLength(0) }
            }
        }
        val totalMinutes = weeks * 7 * 24 * 60 + days * 24 * 60 + hours * 60 + minutes +
            if (seconds > 0) 1 else 0
        val signed = if (negative) totalMinutes.toInt() else -totalMinutes.toInt()
        // We model "minutes before"; negative trigger = before = positive value.
        return if (signed >= 0) signed else null
    }

    private fun matchReminderOffset(minutesBefore: Int): ReminderOffset? = when (minutesBefore) {
        0 -> ReminderOffset.AtStart
        5 -> ReminderOffset.FiveMin
        15 -> ReminderOffset.FifteenMin
        60 -> ReminderOffset.OneHour
        60 * 24 -> ReminderOffset.OneDay
        else -> closestReminderOffset(minutesBefore)
    }

    private fun closestReminderOffset(minutesBefore: Int): ReminderOffset {
        return ReminderOffset.values().minByOrNull { kotlin.math.abs(it.minutesBefore - minutesBefore) }
            ?: ReminderOffset.AtStart
    }

    private fun parseInstantMs(value: String, deviceZone: ZoneId): Long? = runCatching {
        val raw = value.trim()
        when {
            raw.endsWith("Z") -> LocalDateTime.parse(raw.dropLast(1), DATE_TIME_FORMATTER)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
            raw.length == 8 -> LocalDate.parse(raw, DATE_FORMATTER)
                .atStartOfDay(deviceZone).toInstant().toEpochMilli()
            else -> LocalDateTime.parse(raw, DATE_TIME_FORMATTER)
                .atZone(deviceZone).toInstant().toEpochMilli()
        }
    }.getOrNull()

    /**
     * The reverse of [unescapeText] used for SUMMARY/DESCRIPTION values.
     * RFC 5545 §3.3.11 — escape backslash, semicolon, comma, and newline; do NOT escape colon.
     */
    fun escapeText(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                ';' -> out.append("\\;")
                ',' -> out.append("\\,")
                '\n' -> out.append("\\n")
                '\r' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun unescapeText(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val next = s[i + 1]
                when (next) {
                    'n', 'N' -> out.append('\n')
                    '\\' -> out.append('\\')
                    ';' -> out.append(';')
                    ',' -> out.append(',')
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Format a UTC instant as DTSTAMP value: YYYYMMDDTHHMMSSZ.
     */
    fun formatUtcStamp(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC)
            .format(DATE_TIME_UTC_FORMATTER)

    /**
     * Format a local instant for a zone: YYYYMMDDTHHMMSS, suitable for `DTSTART;TZID=...`.
     */
    fun formatZoned(epochMs: Long, zone: ZoneId): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
            .format(DATE_TIME_FORMATTER)

    /**
     * Format a date as YYYYMMDD for VALUE=DATE properties.
     */
    fun formatDate(epochMs: Long, zone: ZoneId): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            .format(DATE_FORMATTER)

    /**
     * Format UNTIL per RFC 5545 §3.3.10: a DATE when the event itself is VALUE=DATE,
     * otherwise the end-of-day-in-event-zone as a UTC instant. Both forms round-trip
     * to the original [LocalDate] regardless of the device's zone at import time.
     */
    fun formatUntil(date: LocalDate, zone: ZoneId, allDay: Boolean): String =
        if (allDay) {
            date.format(DATE_FORMATTER)
        } else {
            date.atTime(LocalTime.of(23, 59, 59)).atZone(zone)
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DATE_TIME_UTC_FORMATTER)
        }
}
