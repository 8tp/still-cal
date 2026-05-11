package dev.chuds.stillcal

import android.app.AlarmManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chuds.stillcal.data.CalSettings
import dev.chuds.stillcal.data.DefaultView
import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.EventsRepository
import dev.chuds.stillcal.data.FontPreset
import dev.chuds.stillcal.data.PreferencesRepository
import dev.chuds.stillcal.data.TimeFormat
import dev.chuds.stillcal.data.WeekStart
import dev.chuds.stillcal.data.ImportResult
import dev.chuds.stillcal.data.importIcsFromSingleUri
import dev.chuds.stillcal.data.importIcsFromUris
import dev.chuds.stillcal.data.writeIcsToUri
import dev.chuds.stillcal.reminders.RemindersScheduler
import dev.chuds.stillcal.ui.components.rememberNotificationsPermissionState
import dev.chuds.stillcal.ui.day.DayListScreen
import dev.chuds.stillcal.ui.event.EventEditScreen
import dev.chuds.stillcal.ui.month.MonthScreen
import dev.chuds.stillcal.ui.settings.SettingsScreen
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.LocalStillTypography
import dev.chuds.stillcal.ui.theme.stillTypographyFor
import dev.chuds.stillcal.ui.week.WeekScreen
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/**
 * Top-level composable. Hand-rolled router — Month / Week / DayList / EventEdit / Settings.
 * Owns the EventsRepository, PreferencesRepository, and SAF launchers, in still-notes' shape.
 */
@Composable
fun StillCalApp(
    incomingDateOpen: LocalDate? = null,
    incomingViewUri: android.net.Uri? = null,
    onIncomingDateOpenHandled: () -> Unit = {},
    onIncomingViewUriHandled: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val activityContext = LocalContext.current
    val eventsRepository = remember(context) { EventsRepository(context) }
    val preferencesRepository = remember(context) { PreferencesRepository(context) }
    val scope = rememberCoroutineScope()

    val notificationsPermission = rememberNotificationsPermissionState()

    val loadedSettings by preferencesRepository.settings.collectAsState(initial = null)
    val settings = loadedSettings ?: CalSettings()
    val events by eventsRepository.events.collectAsStateWithLifecycle()

    LaunchedEffect(eventsRepository) {
        eventsRepository.load()
        RemindersScheduler.ensureChannel(activityContext)
    }

    // Route reflects the navigation stack as a simple list. System back pops.
    val routeStack = remember { mutableStateListOf<Route>(Route.Month(YearMonth.now())) }
    var initialApplied by remember { mutableStateOf(false) }

    // Apply the cold-start default-view preference exactly once.
    LaunchedEffect(loadedSettings?.defaultView, initialApplied) {
        val loaded = loadedSettings ?: return@LaunchedEffect
        if (!initialApplied && incomingDateOpen == null && incomingViewUri == null) {
            initialApplied = true
            if (loaded.defaultView == DefaultView.Week) {
                routeStack.clear()
                routeStack += Route.Week(LocalDate.now())
            }
        }
    }

    // Honor an incoming reminder-tap opening the day list for the event's date.
    LaunchedEffect(incomingDateOpen) {
        val date = incomingDateOpen ?: return@LaunchedEffect
        initialApplied = true
        routeStack.clear()
        routeStack += Route.Month(YearMonth.from(date))
        routeStack += Route.DayList(date)
        onIncomingDateOpenHandled()
    }

    // Honor an ACTION_VIEW text/calendar payload — import then jump to first imported event.
    LaunchedEffect(incomingViewUri) {
        val uri = incomingViewUri ?: return@LaunchedEffect
        initialApplied = true
        scope.launch {
            val result = importIcsFromSingleUri(activityContext, uri, eventsRepository)
            Toast.makeText(
                activityContext,
                importToastMessage(result),
                Toast.LENGTH_SHORT,
            ).show()
            result.imported.forEach { ev ->
                RemindersScheduler.scheduleNext(activityContext, ev, eventsRepository)
            }
            result.imported.firstOrNull()?.let { ev ->
                val zone = java.time.ZoneId.systemDefault()
                val date = java.time.Instant.ofEpochMilli(ev.startEpochMs).atZone(zone).toLocalDate()
                routeStack.clear()
                routeStack += Route.Month(YearMonth.from(date))
            }
            onIncomingViewUriHandled()
        }
    }

    BackHandler(enabled = routeStack.size > 1) {
        routeStack.removeAt(routeStack.lastIndex)
    }

    // Wire SAF launchers.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val singleExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        val id = pendingExport
        pendingExport = null
        if (uri != null && id != null) {
            scope.launch {
                val body = eventsRepository.readIcs(id)
                if (writeIcsToUri(activityContext, uri, body)) {
                    Toast.makeText(activityContext, "exported", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val bulkExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val body = eventsRepository.bulkIcs()
                if (writeIcsToUri(activityContext, uri, body)) {
                    Toast.makeText(activityContext, "exported all events", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val result = importIcsFromUris(activityContext, uris, eventsRepository)
                Toast.makeText(
                    activityContext,
                    importToastMessage(result),
                    Toast.LENGTH_SHORT,
                ).show()
                result.imported.forEach { ev ->
                    RemindersScheduler.scheduleNext(activityContext, ev, eventsRepository)
                }
            }
        }
    }

    fun startExport(id: String) {
        pendingExport = id
        val event = eventsRepository.events.value.firstOrNull { it.id == id }
        val safe = (event?.title ?: "event")
            .replace(Regex("[^A-Za-z0-9._\\- ]"), "").trim().replace(' ', '-').ifBlank { "event" }
        singleExportLauncher.launch("$safe.ics")
    }

    fun startBulkExport() {
        bulkExportLauncher.launch("still-cal-${System.currentTimeMillis()}.ics")
    }

    fun startImport() {
        importLauncher.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream"))
    }

    fun saveEvent(event: Event) {
        scope.launch {
            val saved = if (eventsRepository.events.value.any { it.id == event.id } &&
                event.id.isNotBlank()) {
                eventsRepository.save(event)
            } else {
                eventsRepository.create(event)
            }
            warnIfNoExactAlarms(activityContext, saved)
            RemindersScheduler.scheduleNext(activityContext, saved, eventsRepository)
            routeStack.removeAt(routeStack.lastIndex)
        }
    }

    fun deleteEvent(id: String) {
        scope.launch {
            RemindersScheduler.cancel(activityContext, id)
            eventsRepository.delete(id)
            // If we were editing the deleted event, pop back to wherever we came from.
            val top = routeStack.lastOrNull()
            if (top is Route.EventEdit && top.id == id) {
                routeStack.removeAt(routeStack.lastIndex)
            }
        }
    }

    val typography = remember(settings.fontPreset) { stillTypographyFor(settings.fontPreset) }

    CompositionLocalProvider(LocalStillTypography provides typography) {
        when (val route = routeStack.last()) {
            is Route.Month -> MonthScreen(
                settings = settings,
                events = events,
                repository = eventsRepository,
                initialMonth = route.month,
                onOpenDay = { d -> routeStack += Route.DayList(d) },
                onSwitchToWeek = { anchorDate ->
                    routeStack.clear()
                    routeStack += Route.Week(anchorDate)
                },
                onNew = { routeStack += Route.EventEdit(null, LocalDate.now()) },
                onOpenSettings = { routeStack += Route.Settings },
            )
            is Route.Week -> WeekScreen(
                settings = settings,
                events = events,
                repository = eventsRepository,
                initialDate = route.date,
                onOpenDay = { d -> routeStack += Route.DayList(d) },
                onOpenEvent = { id -> routeStack += Route.EventEdit(id, null) },
                onSwitchToMonth = { anchorDate ->
                    routeStack.clear()
                    routeStack += Route.Month(YearMonth.from(anchorDate))
                },
                onNew = { routeStack += Route.EventEdit(null, LocalDate.now()) },
                onOpenSettings = { routeStack += Route.Settings },
            )
            is Route.DayList -> DayListScreen(
                date = route.date,
                settings = settings,
                events = events,
                repository = eventsRepository,
                onOpenEvent = { id -> routeStack += Route.EventEdit(id, route.date) },
                onDeleteEvent = ::deleteEvent,
                onExportEvent = ::startExport,
                onNew = { routeStack += Route.EventEdit(null, route.date) },
                onBack = { routeStack.removeAt(routeStack.lastIndex) },
            )
            is Route.EventEdit -> {
                val existing = route.id?.let { id -> events.firstOrNull { it.id == id } }
                if (route.id != null && existing == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(StillColors.OledBlack),
                    )
                } else {
                    EventEditScreen(
                        existing = existing,
                        defaultDate = route.defaultDate ?: LocalDate.now(),
                        notificationsPermission = notificationsPermission,
                        onSave = ::saveEvent,
                        onDelete = { existing?.let { deleteEvent(it.id) } },
                        onCancel = { routeStack.removeAt(routeStack.lastIndex) },
                    )
                }
            }
            Route.Settings -> SettingsScreen(
                settings = settings,
                onCycleFontPreset = {
                    scope.launch {
                        preferencesRepository.setFontPreset(cycleFontPreset(settings.fontPreset))
                    }
                },
                onCycleDefaultView = {
                    scope.launch {
                        preferencesRepository.setDefaultView(cycleDefaultView(settings.defaultView))
                    }
                },
                onCycleWeekStart = {
                    scope.launch {
                        preferencesRepository.setWeekStart(cycleWeekStart(settings.weekStart))
                    }
                },
                onCycleTimeFormat = {
                    scope.launch {
                        preferencesRepository.setTimeFormat(cycleTimeFormat(settings.timeFormat))
                    }
                },
                onImport = ::startImport,
                onExportAll = ::startBulkExport,
                onDeleteAll = {
                    scope.launch {
                        eventsRepository.events.value.forEach {
                            RemindersScheduler.cancel(activityContext, it.id)
                        }
                        eventsRepository.deleteAll()
                    }
                },
                onBack = { routeStack.removeAt(routeStack.lastIndex) },
            )
        }
    }
}

private fun importToastMessage(result: ImportResult): String {
    val n = result.imported.size
    val base = "imported $n " + if (n == 1) "event" else "events"
    return if (result.skipped > 0) "$base · skipped ${result.skipped}" else base
}

private fun warnIfNoExactAlarms(context: Context, event: Event) {
    if (event.reminder == null) return
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (!am.canScheduleExactAlarms()) {
        Toast.makeText(context, "enable exact alarms in settings", Toast.LENGTH_LONG).show()
    }
}

private fun cycleFontPreset(current: FontPreset): FontPreset = when (current) {
    FontPreset.System -> FontPreset.Editorial
    FontPreset.Editorial -> FontPreset.Terminal
    FontPreset.Terminal -> FontPreset.Grotesk
    FontPreset.Grotesk -> FontPreset.System
}

private fun cycleDefaultView(current: DefaultView): DefaultView = when (current) {
    DefaultView.Month -> DefaultView.Week
    DefaultView.Week -> DefaultView.Month
}

private fun cycleWeekStart(current: WeekStart): WeekStart = when (current) {
    WeekStart.System -> WeekStart.Sunday
    WeekStart.Sunday -> WeekStart.Monday
    WeekStart.Monday -> WeekStart.System
}

private fun cycleTimeFormat(current: TimeFormat): TimeFormat = when (current) {
    TimeFormat.System -> TimeFormat.Hour12
    TimeFormat.Hour12 -> TimeFormat.Hour24
    TimeFormat.Hour24 -> TimeFormat.System
}

private sealed interface Route {
    data class Month(val month: YearMonth) : Route
    data class Week(val date: LocalDate) : Route
    data class DayList(val date: LocalDate) : Route
    data class EventEdit(val id: String?, val defaultDate: LocalDate?) : Route
    data object Settings : Route
}
