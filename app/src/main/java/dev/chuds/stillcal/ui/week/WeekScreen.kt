package dev.chuds.stillcal.ui.week

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.data.CalSettings
import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.EventsRepository
import dev.chuds.stillcal.ui.components.StillDivider
import dev.chuds.stillcal.ui.components.StillVerb
import dev.chuds.stillcal.ui.components.rememberToday
import dev.chuds.stillcal.ui.day.formatTime
import dev.chuds.stillcal.ui.month.resolveWeekStart
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun WeekScreen(
    settings: CalSettings,
    events: List<Event>,
    repository: EventsRepository,
    initialDate: LocalDate,
    onOpenDay: (LocalDate) -> Unit,
    onOpenEvent: (String) -> Unit,
    onSwitchToMonth: (LocalDate) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val weekStart = resolveWeekStart(settings.weekStart)
    val today by rememberToday()
    var anchor by remember(initialDate) {
        mutableStateOf(initialDate.with(TemporalAdjusters.previousOrSame(weekStart)))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StillColors.OledBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .pointerInput(anchor) {
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = 80f * density
                            if (dragTotal > threshold) anchor = anchor.minusWeeks(1)
                            else if (dragTotal < -threshold) anchor = anchor.plusWeeks(1)
                            dragTotal = 0f
                        },
                        onDragCancel = { dragTotal = 0f },
                    ) { _, delta -> dragTotal += delta }
                },
        ) {
            Header(start = anchor)
            StillDivider(modifier = Modifier.padding(horizontal = 24.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
            ) {
                val days = (0L until 7L).map { anchor.plusDays(it) }
                items(days, key = { it.toString() }) { day ->
                    DayRow(
                        date = day,
                        today = today,
                        events = events,
                        repository = repository,
                        settings = settings,
                        onOpenDay = onOpenDay,
                        onOpenEvent = onOpenEvent,
                    )
                    StillDivider()
                }
            }
        }

        FooterBar(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding(),
            // Hand the month view the week's start date so view-switching preserves context.
            onMonth = { onSwitchToMonth(anchor) },
            onToday = { anchor = today.with(TemporalAdjusters.previousOrSame(weekStart)) },
            onNew = onNew,
            onSettings = onOpenSettings,
        )
    }
}

@Composable
private fun Header(start: LocalDate) {
    val end = start.plusDays(6)
    val sameMonth = start.month == end.month
    val rangeText = if (sameMonth) {
        "${start.month.getDisplayName(JTextStyle.FULL, Locale.getDefault())} ${start.dayOfMonth}–${end.dayOfMonth}"
    } else {
        val s = start.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        val e = end.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        "$s – $e"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
    ) {
        Text(text = "WEEK", style = StillTypography.Kicker, color = StillColors.DimGray)
        Text(
            text = rangeText,
            style = StillTypography.Display,
            color = StillColors.SoftWhite,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRow(
    date: LocalDate,
    today: LocalDate,
    events: List<Event>,
    repository: EventsRepository,
    settings: CalSettings,
    onOpenDay: (LocalDate) -> Unit,
    onOpenEvent: (String) -> Unit,
) {
    val dayEvents = remember(events, date) {
        // occurrencesIntersecting for a single-day range yields at most one date per event,
        // so a plain filter is sufficient — no distinctBy needed.
        events
            .filter { event -> repository.occurrencesIntersecting(event, date..date).isNotEmpty() }
            .sortedWith(
                compareByDescending<Event> { it.allDay }
                    .thenBy { it.startEpochMs }
            )
    }
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = { onOpenDay(date) },
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(56.dp)) {
            Text(
                text = date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()).lowercase(),
                style = StillTypography.Caption,
                color = StillColors.DimGray,
            )
            Text(
                text = date.dayOfMonth.toString(),
                style = StillTypography.DayNum,
                color = if (date == today) StillColors.SoftWhite else StillColors.MutedWhite,
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (dayEvents.isEmpty()) {
                Text(
                    text = "·",
                    style = StillTypography.Caption,
                    color = StillColors.DimGray,
                )
            }
            dayEvents.forEach { event ->
                val perEventInteraction = remember(event.id) { MutableInteractionSource() }
                val timeText = if (event.allDay) "all day" else {
                    val zone = runCatching { ZoneId.of(event.tzId) }.getOrNull() ?: ZoneId.systemDefault()
                    val start = java.time.Instant.ofEpochMilli(event.startEpochMs).atZone(zone).toLocalTime()
                    formatTime(start, settings.timeFormat)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = perEventInteraction,
                            indication = null,
                            onClick = { onOpenEvent(event.id) },
                        )
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = timeText,
                        style = StillTypography.Caption,
                        color = StillColors.MutedWhite,
                        modifier = Modifier.width(70.dp),
                    )
                    Text(
                        text = event.title.ifBlank { "untitled" },
                        style = StillTypography.Title,
                        color = if (event.title.isBlank()) StillColors.DimGray else StillColors.SoftWhite,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FooterBar(
    modifier: Modifier = Modifier,
    onMonth: () -> Unit,
    onToday: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StillVerb(label = "month", onClick = onMonth, bordered = true)
        StillVerb(label = "today", onClick = onToday, bordered = true)
        StillVerb(label = "new", onClick = onNew, bordered = true)
        StillVerb(label = "settings", onClick = onSettings, bordered = true)
    }
}
