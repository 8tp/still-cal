package dev.chuds.stillcal.ui.day

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.data.CalSettings
import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.EventsRepository
import dev.chuds.stillcal.data.TimeFormat
import dev.chuds.stillcal.ui.components.StillDivider
import dev.chuds.stillcal.ui.components.StillVerb
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun DayListScreen(
    date: LocalDate,
    settings: CalSettings,
    events: List<Event>,
    repository: EventsRepository,
    onOpenEvent: (String) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onExportEvent: (String) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    val occurrences = remember(events, date) {
        // occurrencesIntersecting for a single-day range yields at most one date per event,
        // so a plain filter is sufficient — no distinctBy needed.
        events
            .filter { event -> repository.occurrencesIntersecting(event, date..date).isNotEmpty() }
            .sortedWith(
                compareByDescending<Event> { it.allDay }
                    .thenBy { it.startEpochMs }
            )
    }
    var actionTarget by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StillColors.OledBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Header(date = date)
            StillDivider(modifier = Modifier.padding(horizontal = 24.dp))

            if (occurrences.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "nothing scheduled",
                        style = StillTypography.Caption,
                        color = StillColors.DimGray,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                ) {
                    items(occurrences, key = { it.id }) { event ->
                        EventRow(
                            event = event,
                            settings = settings,
                            onClick = { onOpenEvent(event.id) },
                            onLongClick = { actionTarget = event.id },
                        )
                        StillDivider()
                    }
                }
            }
        }

        FooterBar(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding(),
            onBack = onBack,
            onNew = onNew,
        )

        actionTarget?.let { id ->
            val event = occurrences.firstOrNull { it.id == id }
            if (event != null) {
                dev.chuds.stillcal.ui.components.StillActionSheet(
                    title = event.title.ifBlank { "untitled" },
                    actions = listOf(
                        dev.chuds.stillcal.ui.components.StillAction(label = "edit") {
                            onOpenEvent(id)
                        },
                        dev.chuds.stillcal.ui.components.StillAction(label = "export") {
                            onExportEvent(id)
                        },
                        dev.chuds.stillcal.ui.components.StillAction(
                            label = "delete",
                            destructive = true,
                            confirmTwice = true,
                        ) { onDeleteEvent(id) },
                    ),
                    onDismiss = { actionTarget = null },
                )
            } else {
                actionTarget = null
            }
        }
    }
}

@Composable
private fun Header(date: LocalDate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
    ) {
        val locale = LocalConfiguration.current.locales[0]
        val weekday = date.dayOfWeek.getDisplayName(JTextStyle.FULL, locale)
        val monthName = date.month.getDisplayName(JTextStyle.FULL, locale)
        Text(
            text = "$weekday, $monthName ${date.dayOfMonth}",
            style = StillTypography.Display,
            color = StillColors.SoftWhite,
        )
        Text(
            text = "${monthName.uppercase(locale)} ${date.year}",
            style = StillTypography.Kicker,
            color = StillColors.DimGray,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(
    event: Event,
    settings: CalSettings,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val timeText = if (event.allDay) {
        "all day"
    } else {
        val zone = runCatching { ZoneId.of(event.tzId) }.getOrNull() ?: ZoneId.systemDefault()
        val startInstant = java.time.Instant.ofEpochMilli(event.startEpochMs).atZone(zone)
        val endInstant = java.time.Instant.ofEpochMilli(event.endEpochMs).atZone(zone)
        val start = startInstant.toLocalTime()
        val end = endInstant.toLocalTime()
        // Cross-midnight events: append a "+1d" / "+Nd" hint so a 26-hour meeting doesn't
        // visually look like a 2-hour one.
        val crossDays = (endInstant.toLocalDate().toEpochDay() - startInstant.toLocalDate().toEpochDay()).toInt()
        val suffix = if (crossDays > 0) " (+${crossDays}d)" else ""
        "${formatTime(start, settings.timeFormat)} – ${formatTime(end, settings.timeFormat)}$suffix"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = timeText,
                style = StillTypography.Caption,
                color = StillColors.MutedWhite,
                modifier = Modifier.width(90.dp),
            )
            Text(
                text = event.title.ifBlank { "untitled" },
                style = StillTypography.Title,
                color = if (event.title.isBlank()) StillColors.DimGray else StillColors.SoftWhite,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (event.notes.isNotBlank()) {
            Text(
                text = event.notes.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "",
                style = StillTypography.Small,
                color = StillColors.MutedWhite,
                modifier = Modifier.padding(start = 90.dp, top = 4.dp),
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun FooterBar(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onNew: () -> Unit,
) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StillVerb(label = "back", onClick = onBack, bordered = true)
        StillVerb(label = "new", onClick = onNew, bordered = true)
    }
}

internal fun formatTime(time: LocalTime, format: TimeFormat): String {
    val pattern = when (format) {
        TimeFormat.Hour24 -> "HH:mm"
        TimeFormat.Hour12 -> "h:mm a"
        TimeFormat.System -> if (Locale.getDefault().country == "US") "h:mm a" else "HH:mm"
    }
    return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}
