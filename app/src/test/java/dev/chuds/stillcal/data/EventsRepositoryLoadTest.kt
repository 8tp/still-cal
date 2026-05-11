package dev.chuds.stillcal.data

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EventsRepositoryLoadTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")

    @Test
    fun coldImportPreservesPersistedIndexBeforeExplicitLoad() = runBlocking {
        val filesRoot = Files.createTempDirectory("events-repository-test").toFile()
        try {
            EventsRepository(filesRoot).create(testEvent("existing", LocalDate.of(2026, 5, 1)))

            val coldRepository = EventsRepository(filesRoot)
            coldRepository.importEvent(testEvent("incoming", LocalDate.of(2026, 5, 2)))
            coldRepository.load()

            assertEquals(listOf("existing", "incoming"), coldRepository.events.value.map { it.id }.sorted())
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    private fun testEvent(id: String, date: LocalDate): Event {
        val start = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        return Event(
            id = id,
            title = id,
            notes = "",
            startEpochMs = start,
            endEpochMs = start + 60 * 60 * 1000,
            allDay = false,
            tzId = zone.id,
            rrule = null,
            reminder = null,
            createdAt = 0L,
            updatedAt = 0L,
        )
    }
}
