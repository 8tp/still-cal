package dev.chuds.stillcal.data

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun importEventKeepsPathLikeUidButSanitizesFilename() = runBlocking {
        val filesRoot = Files.createTempDirectory("events-repository-test").toFile()
        try {
            val repository = EventsRepository(filesRoot)

            val imported = repository.importEvent(testEvent("../outside", LocalDate.of(2026, 5, 3)))

            assertEquals("../outside", imported.id)
            assertFalse(filesRoot.resolve("outside.ics").exists())
            assertFalse(filesRoot.resolve("../outside.ics").exists())
            val eventsDir = filesRoot.resolve("events")
            val onDisk = eventsDir.listFiles()?.map { it.name } ?: emptyList()
            assertEquals(1, onDisk.size)
            assertTrue("filename should be sanitized, was ${onDisk.single()}",
                onDisk.single().matches(Regex("[A-Za-z0-9._-]+\\.ics")))
            assertEquals(listOf("../outside"), repository.events.value.map { it.id })
        } finally {
            filesRoot.deleteRecursively()
        }
    }

    @Test
    fun importEventDedupesGoogleStyleUidOnReimport() = runBlocking {
        val filesRoot = Files.createTempDirectory("events-repository-test").toFile()
        try {
            val repository = EventsRepository(filesRoot)
            val uid = "abc-123@google.com"

            val first = repository.importEvent(testEvent(uid, LocalDate.of(2026, 5, 4)))
            val second = repository.importEvent(testEvent(uid, LocalDate.of(2026, 5, 4)))

            assertEquals(uid, first.id)
            assertEquals(first.id, second.id)
            assertEquals(listOf(uid), repository.events.value.map { it.id })
            val eventsDir = filesRoot.resolve("events")
            val files = eventsDir.listFiles()?.toList() ?: emptyList()
            assertEquals(1, files.size)
            assertFalse("filename must not contain @", files.single().name.contains('@'))
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
