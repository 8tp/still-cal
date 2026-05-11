package dev.chuds.stillcal.data

import android.content.Context
import dev.chuds.stillcal.ical.IcsParser
import dev.chuds.stillcal.ical.IcsTypes
import dev.chuds.stillcal.ical.IcsWriter
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * File-backed events store. Mirrors NotesRepository in still-notes.
 *
 * Layout under filesDir:
 *   events/<safeName>.ics  one VEVENT per file, wrapped in a VCALENDAR envelope. The
 *                          filename is a sanitized derivative of the event id; the
 *                          authoritative UID lives in the file's UID property.
 *   index.json             metadata for fast list/grid rendering
 */
class EventsRepository private constructor(
    private val eventsDir: File,
    private val indexFile: File,
) {

    constructor(context: Context) : this(context.filesDir)

    internal constructor(filesRoot: File) : this(
        eventsDir = File(filesRoot, "events").apply { if (!exists()) mkdirs() },
        indexFile = File(filesRoot, "events_index.json"),
    )

    private val ioMutex = Mutex()
    private var loaded = false

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            loadLocked()
        }
    }

    suspend fun get(id: String): Event? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            ensureLoadedLocked()
            _events.value.firstOrNull { it.id == id }
        }
    }

    suspend fun create(template: Event): Event = withContext(Dispatchers.IO) {
        val id = template.id.ifBlank { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        val event = template.copy(id = id, createdAt = now, updatedAt = now)
        ioMutex.withLock {
            ensureLoadedLocked()
            eventFile(id).writeText(IcsWriter.writeCalendar(event))
            val next = _events.value + event
            writeIndex(next)
            _events.value = next.sortedBy { it.startEpochMs }
        }
        event
    }

    suspend fun save(event: Event): Event = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val refreshed = event.copy(updatedAt = now)
        ioMutex.withLock {
            ensureLoadedLocked()
            eventFile(refreshed.id).writeText(IcsWriter.writeCalendar(refreshed))
            val next = _events.value.filterNot { it.id == refreshed.id } + refreshed
            writeIndex(next)
            _events.value = next.sortedBy { it.startEpochMs }
        }
        refreshed
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            ensureLoadedLocked()
            eventFile(id).delete()
            val next = _events.value.filterNot { it.id == id }
            writeIndex(next)
            _events.value = next.sortedBy { it.startEpochMs }
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            eventsDir.listFiles()?.forEach { it.delete() }
            indexFile.delete()
            loaded = true
            _events.value = emptyList()
        }
    }

    /**
     * Import a parsed VEVENT. The verbatim external UID is preserved as the event id so a
     * re-import of the same .ics dedupes; on disk the filename is a sanitized derivative of
     * that id so path-like UIDs cannot escape eventsDir.
     */
    suspend fun importEvent(parsed: Event): Event = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        ioMutex.withLock {
            ensureLoadedLocked()
            // Re-check UID collision *inside* the mutex — two concurrent imports of the
            // same UID would otherwise both pass the outer check and produce duplicate ids.
            val externalUid = parsed.id.takeIf { it.isNotBlank() }
            val existing = externalUid?.let { uid -> _events.value.firstOrNull { it.id == uid } }
            if (existing != null) return@withLock existing
            val id = externalUid ?: UUID.randomUUID().toString()
            val event = parsed.copy(
                id = id,
                createdAt = if (parsed.createdAt == 0L) now else parsed.createdAt,
                updatedAt = if (parsed.updatedAt == 0L) now else parsed.updatedAt,
            )
            eventFile(event.id).writeText(IcsWriter.writeCalendar(event))
            val next = _events.value + event
            writeIndex(next)
            _events.value = next.sortedBy { it.startEpochMs }
            event
        }
    }

    /**
     * Read the on-disk .ics for an event. Useful for verification and single-event SAF export.
     */
    suspend fun readIcs(id: String): String = withContext(Dispatchers.IO) {
        val file = eventFile(id)
        if (file.exists()) file.readText() else ""
    }

    /**
     * Bulk export — single VCALENDAR envelope containing every event's VEVENT.
     */
    suspend fun bulkIcs(): String = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            ensureLoadedLocked()
            IcsWriter.writeCalendarBulk(_events.value.sortedBy { it.startEpochMs })
        }
    }

    private fun eventFile(id: String): File = File(eventsDir, "${safeFileNameForId(id)}.ics")

    private fun loadLocked() {
        val loadedEvents = readIndex() ?: rebuildIndexFromDisk()
        _events.value = loadedEvents.sortedBy { it.startEpochMs }
        loaded = true
    }

    private fun ensureLoadedLocked() {
        if (!loaded) loadLocked()
    }

    /**
     * Expand the event's occurrences that touch [dateRange]. For one-shot events this is at most
     * one occurrence; for recurring events it walks forward from the original start until UNTIL
     * or the range end, whichever comes first.
     */
    /**
     * Instance delegates to pure companion-object functions so the recurrence math is
     * trivially unit-testable without an Android Context.
     */
    fun occurrencesIntersecting(event: Event, dateRange: ClosedRange<LocalDate>): List<LocalDate> =
        Companion.occurrencesIntersecting(event, dateRange)

    fun nextOccurrenceMs(event: Event, fromMs: Long): Long? =
        Companion.nextOccurrenceMs(event, fromMs)

    private fun readIndex(): List<Event>? {
        if (!indexFile.exists()) return null
        return runCatching {
            val text = indexFile.readText()
            if (text.isBlank()) return null
            val array = JSONArray(text)
            (0 until array.length()).map { i -> indexEntryToEvent(array.getJSONObject(i)) }
        }.getOrNull()
    }

    private fun rebuildIndexFromDisk(): List<Event> {
        val deviceZone = ZoneId.systemDefault()
        val events = (eventsDir.listFiles { f -> f.extension == "ics" } ?: emptyArray()).mapNotNull { file ->
            runCatching {
                val raw = IcsParser.parseEvents(file.readText()).firstOrNull() ?: return@runCatching null
                IcsTypes.toEvent(raw, System.currentTimeMillis(), deviceZone)
            }.getOrNull()
        }
        writeIndex(events)
        return events
    }

    private fun writeIndex(events: List<Event>) {
        val array = JSONArray()
        events.forEach { event ->
            val obj = JSONObject()
                .put("id", event.id)
                .put("title", event.title)
                .put("notes", event.notes)
                .put("startEpochMs", event.startEpochMs)
                .put("endEpochMs", event.endEpochMs)
                .put("allDay", event.allDay)
                .put("tzId", event.tzId)
                .put("rrule", event.rrule?.let { rruleToJson(it) })
                .put("reminder", event.reminder?.name)
                .put("createdAt", event.createdAt)
                .put("updatedAt", event.updatedAt)
            array.put(obj)
        }
        indexFile.writeText(array.toString())
    }

    private fun rruleToJson(r: Recurrence): JSONObject {
        val kind = when (r) {
            is Recurrence.Daily -> "DAILY"
            is Recurrence.Weekly -> "WEEKLY"
            is Recurrence.Monthly -> "MONTHLY"
        }
        return JSONObject().put("kind", kind).put("until", r.until.toString())
    }

    private fun indexEntryToEvent(obj: JSONObject): Event {
        val rruleObj = obj.optJSONObject("rrule")
        val rrule: Recurrence? = rruleObj?.let {
            val until = LocalDate.parse(it.getString("until"))
            when (it.getString("kind")) {
                "DAILY" -> Recurrence.Daily(until)
                "WEEKLY" -> Recurrence.Weekly(until)
                "MONTHLY" -> Recurrence.Monthly(until)
                else -> null
            }
        }
        val reminder = obj.optString("reminder").takeIf { it.isNotEmpty() }
            ?.let { runCatching { ReminderOffset.valueOf(it) }.getOrNull() }
        return Event(
            id = obj.getString("id"),
            title = obj.optString("title"),
            notes = obj.optString("notes"),
            startEpochMs = obj.getLong("startEpochMs"),
            endEpochMs = obj.getLong("endEpochMs"),
            allDay = obj.optBoolean("allDay", false),
            tzId = obj.optString("tzId", ZoneId.systemDefault().id),
            rrule = rrule,
            reminder = reminder,
            createdAt = obj.optLong("createdAt"),
            updatedAt = obj.optLong("updatedAt"),
        )
    }

    /**
     * Pure recurrence math — no Android dependencies, trivially unit-testable.
     */
    companion object {

        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
        private const val MAX_FILENAME_LEN = 96

        /**
         * Derive a path-traversal-safe filename stem from an arbitrary event id. Real-world
         * iCalendar UIDs frequently contain `@`, `:`, `/`, etc. — we replace anything outside
         * `[A-Za-z0-9._-]` with `_`, strip leading dots, and append a short stable hash so
         * distinct ids whose sanitized prefixes collide still get distinct files.
         */
        internal fun safeFileNameForId(id: String): String {
            val sanitized = UNSAFE_FILENAME_CHARS.replace(id, "_").trimStart('.')
            val stem = sanitized.take(MAX_FILENAME_LEN).ifBlank { "event" }
            val suffix = Integer.toHexString(id.hashCode() and 0x7FFFFFFF)
            return "$stem-$suffix"
        }

        fun occurrencesIntersecting(
            event: Event,
            dateRange: ClosedRange<LocalDate>,
        ): List<LocalDate> {
            val zone = runCatching { ZoneId.of(event.tzId) }.getOrNull() ?: ZoneId.systemDefault()
            val startDate = java.time.Instant.ofEpochMilli(event.startEpochMs).atZone(zone).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(event.endEpochMs).atZone(zone).toLocalDate()
            val spanDays = when {
                event.allDay -> (endDate.toEpochDay() - startDate.toEpochDay()).toInt().coerceAtLeast(1)
                endDate.isAfter(startDate) -> (endDate.toEpochDay() - startDate.toEpochDay()).toInt() + 1
                else -> 1
            }

            fun spanFor(occurrenceStart: LocalDate): List<LocalDate> =
                (0 until spanDays).mapNotNull { offset ->
                    val d = occurrenceStart.plusDays(offset.toLong())
                    if (d in dateRange) d else null
                }

            val rrule = event.rrule ?: return spanFor(startDate)

            // Fast-forward: seek to the first occurrence whose span can possibly intersect
            // the range, then walk only within the range. The old day-by-day walk hit a hard
            // cap that silently truncated events older than ~3 years.
            val seekTarget = dateRange.start.minusDays((spanDays - 1).toLong())
            val firstOccurrence = firstOccurrenceOnOrAfter(startDate, seekTarget, rrule)
            val until = rrule.until
            val results = mutableListOf<LocalDate>()
            var occurrence = firstOccurrence
            while (occurrence <= until && occurrence <= dateRange.endInclusive) {
                results += spanFor(occurrence)
                occurrence = when (rrule) {
                    is Recurrence.Daily -> occurrence.plusDays(1)
                    is Recurrence.Weekly -> occurrence.plusWeeks(1)
                    is Recurrence.Monthly -> occurrence.plusMonths(1)
                }
            }
            return results
        }

        fun nextOccurrenceMs(event: Event, fromMs: Long): Long? {
            if (event.rrule == null) {
                return if (event.startEpochMs >= fromMs) event.startEpochMs else null
            }
            val zone = runCatching { ZoneId.of(event.tzId) }.getOrNull() ?: ZoneId.systemDefault()
            val original = java.time.Instant.ofEpochMilli(event.startEpochMs).atZone(zone)
            val time = original.toLocalTime()
            val anchorDate = original.toLocalDate()
            val until = event.rrule.until
            val targetDate = java.time.Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()

            // Seek to the first occurrence on or after the target date, then check whether
            // the exact instant (date+time at zone) is past fromMs; advance one cadence if not.
            var date = firstOccurrenceOnOrAfter(anchorDate, targetDate, event.rrule)
            var safety = 0
            while (date <= until && safety < 8) {
                val candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (candidate >= fromMs) return candidate
                date = when (event.rrule) {
                    is Recurrence.Daily -> date.plusDays(1)
                    is Recurrence.Weekly -> date.plusWeeks(1)
                    is Recurrence.Monthly -> date.plusMonths(1)
                }
                safety++
            }
            return null
        }

        /**
         * The first occurrence on or after [target], starting the cadence at [anchor]. If
         * [target] is before [anchor], returns [anchor]. O(1) cadence math — fixes the
         * "old daily events vanish from the grid" bug.
         */
        private fun firstOccurrenceOnOrAfter(
            anchor: LocalDate,
            target: LocalDate,
            rrule: Recurrence,
        ): LocalDate {
            if (target <= anchor) return anchor
            return when (rrule) {
                is Recurrence.Daily -> {
                    val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(anchor, target)
                    anchor.plusDays(daysAhead)
                }
                is Recurrence.Weekly -> {
                    val daysAhead = java.time.temporal.ChronoUnit.DAYS.between(anchor, target)
                    val weeksAhead = daysAhead / 7
                    val candidate = anchor.plusWeeks(weeksAhead)
                    if (candidate < target) candidate.plusWeeks(1) else candidate
                }
                is Recurrence.Monthly -> {
                    val monthsAhead = java.time.temporal.ChronoUnit.MONTHS.between(anchor, target)
                    val candidate = anchor.plusMonths(monthsAhead)
                    if (candidate < target) candidate.plusMonths(1) else candidate
                }
            }
        }
    }
}
