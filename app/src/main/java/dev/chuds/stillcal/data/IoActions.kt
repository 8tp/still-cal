package dev.chuds.stillcal.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.widget.Toast
import dev.chuds.stillcal.ical.IcsParser
import dev.chuds.stillcal.ical.IcsTypes
import java.io.InputStream
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF helpers for the import / export flows wired up in StillCalApp.
 * Pattern mirrors still-notes' IoActions: keep ContentResolver mechanics out of Compose.
 */

suspend fun writeIcsToUri(
    context: Context,
    uri: Uri,
    body: String,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(body.toByteArray(Charsets.UTF_8))
        } ?: return@runCatching false
        true
    }.getOrElse {
        toastOnMain(context, "export failed")
        false
    }
}

/**
 * Result of a SAF import. [imported] are events that successfully round-tripped through
 * the parser; [skipped] counts malformed VEVENT blocks that threw during mapping (spec §13
 * promises no crashes for unknown TZID or missing DTEND — we extend that to "no crashes
 * for anything the parser can't make sense of," and just report a skip count).
 */
data class ImportResult(val imported: List<Event>, val skipped: Int)

/**
 * Import one or more .ics files from SAF. Each VEVENT inside becomes one event on disk.
 * Malformed VEVENTs (unparseable DTSTART, etc.) are counted in [ImportResult.skipped]
 * rather than crashing the coroutine mid-batch.
 */
suspend fun importIcsFromUris(
    context: Context,
    uris: List<Uri>,
    repository: EventsRepository,
): ImportResult = withContext(Dispatchers.IO) {
    val zone = ZoneId.systemDefault()
    val imported = mutableListOf<Event>()
    var skipped = 0
    uris.forEach { uri ->
        val text = readTextFromUri(context, uri)
        if (text == null) {
            skipped++
            return@forEach
        }
        val rawEvents = runCatching { IcsParser.parseEvents(text) }.getOrElse {
            skipped++
            return@forEach
        }
        rawEvents.forEach { raw ->
            val event = runCatching {
                IcsTypes.toEvent(raw, System.currentTimeMillis(), zone)
            }.getOrNull()
            if (event == null) {
                skipped++
                return@forEach
            }
            runCatching { imported += repository.importEvent(event) }
                .onFailure { skipped++ }
        }
    }
    ImportResult(imported = imported, skipped = skipped)
}

/**
 * Read a single .ics from a content URI (used by MainActivity's ACTION_VIEW handler).
 * Same tolerance as [importIcsFromUris]: malformed events are skipped, not crashed on.
 */
suspend fun importIcsFromSingleUri(
    context: Context,
    uri: Uri,
    repository: EventsRepository,
): ImportResult = withContext(Dispatchers.IO) {
    val zone = ZoneId.systemDefault()
    val text = readTextFromUri(context, uri)
        ?: return@withContext ImportResult(emptyList(), 1)
    val rawEvents = runCatching { IcsParser.parseEvents(text) }.getOrElse {
        return@withContext ImportResult(emptyList(), 1)
    }
    val imported = mutableListOf<Event>()
    var skipped = 0
    rawEvents.forEach { raw ->
        val event = runCatching {
            IcsTypes.toEvent(raw, System.currentTimeMillis(), zone)
        }.getOrNull()
        if (event == null) {
            skipped++
            return@forEach
        }
        runCatching { imported += repository.importEvent(event) }
            .onFailure { skipped++ }
    }
    ImportResult(imported = imported, skipped = skipped)
}

/**
 * Hard ceiling on SAF text reads. Anything past this is almost certainly hostile or
 * mis-targeted (real calendars are kilobytes, the largest published Google Calendar
 * export the author has seen is ~6 MiB). Streaming the byte count lets us abort early
 * instead of buffering a multi-GB blob into memory.
 */
internal const val MAX_IMPORT_BYTES: Long = 32L * 1024 * 1024

internal sealed class ReadResult {
    data class Ok(val text: String) : ReadResult()
    data object TooLarge : ReadResult()
    data object Failed : ReadResult()
}

internal fun readBoundedText(stream: InputStream, maxBytes: Long): ReadResult = runCatching {
    val buffer = ByteArray(8 * 1024)
    val out = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) return@runCatching ReadResult.TooLarge
        out.write(buffer, 0, n)
    }
    ReadResult.Ok(out.toString(Charsets.UTF_8.name()))
}.getOrElse { ReadResult.Failed }

private fun readTextFromUri(context: Context, uri: Uri): String? {
    val resolver: ContentResolver = context.contentResolver
    val result = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            readBoundedText(stream, MAX_IMPORT_BYTES)
        } ?: ReadResult.Failed
    }.getOrElse { ReadResult.Failed }
    return when (result) {
        is ReadResult.Ok -> result.text
        ReadResult.TooLarge -> {
            toastOnMain(context, "file too large to import")
            null
        }
        ReadResult.Failed -> null
    }
}

private fun toastOnMain(context: Context, message: String) {
    android.os.Handler(context.mainLooper).post {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
