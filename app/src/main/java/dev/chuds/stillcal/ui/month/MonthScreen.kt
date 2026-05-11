package dev.chuds.stillcal.ui.month

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.data.CalSettings
import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.EventsRepository
import dev.chuds.stillcal.data.WeekStart
import dev.chuds.stillcal.ui.components.StillDivider
import dev.chuds.stillcal.ui.components.StillVerb
import dev.chuds.stillcal.ui.components.rememberToday
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Month grid — home screen. 7 columns × 6 rows. Swipe ±80dp to advance month;
 * tap a cell to drill into the day list. Today is rendered in SoftWhite; other-month
 * days in DimGray; event days carry up to three MutedWhite dots beneath the number.
 */
@Composable
fun MonthScreen(
    settings: CalSettings,
    events: List<Event>,
    repository: EventsRepository,
    initialMonth: YearMonth,
    onOpenDay: (LocalDate) -> Unit,
    onSwitchToWeek: (LocalDate) -> Unit,
    onNew: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var displayed by remember(initialMonth) { mutableStateOf(initialMonth) }
    val today by rememberToday()
    val weekStart = resolveWeekStart(settings.weekStart)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StillColors.OledBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .pointerInput(displayed) {
                    var dragTotal = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val threshold = 80f * density
                            if (dragTotal > threshold) {
                                displayed = displayed.minusMonths(1)
                            } else if (dragTotal < -threshold) {
                                displayed = displayed.plusMonths(1)
                            }
                            dragTotal = 0f
                        },
                        onDragCancel = { dragTotal = 0f },
                    ) { _, delta ->
                        dragTotal += delta
                    }
                },
        ) {
            Header(
                month = displayed,
                onPrev = { displayed = displayed.minusMonths(1) },
                onNext = { displayed = displayed.plusMonths(1) },
            )

            WeekdayHeader(weekStart = weekStart)

            StillDivider(modifier = Modifier.padding(horizontal = 14.dp))

            MonthGrid(
                month = displayed,
                today = today,
                weekStart = weekStart,
                events = events,
                repository = repository,
                onOpenDay = onOpenDay,
            )
        }

        FooterBar(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding(),
            // Hand the week view a representative date in the currently browsed month —
            // today if we're on this month, the 1st otherwise — so a user paging through
            // March stays in March on view switch.
            onWeek = {
                val anchorDate = if (displayed == YearMonth.from(today)) today else displayed.atDay(1)
                onSwitchToWeek(anchorDate)
            },
            onToday = { displayed = YearMonth.from(today) },
            onNew = onNew,
            onSettings = onOpenSettings,
        )
    }
}

@Composable
private fun Header(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MONTH",
                style = StillTypography.Kicker,
                color = StillColors.DimGray,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = month.year.toString(),
                style = StillTypography.Caption,
                color = StillColors.MutedWhite,
            )
            Spacer(Modifier.weight(1f))
            StillVerb(label = "prev", onClick = onPrev)
            StillVerb(label = "next", onClick = onNext)
        }

        Text(
            text = month.month.getDisplayName(JTextStyle.FULL, locale).lowercase(locale),
            style = StillTypography.Display,
            color = StillColors.SoftWhite,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun WeekdayHeader(weekStart: DayOfWeek) {
    val letters = remember(weekStart) { weekdayLetters(weekStart) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        letters.forEach { letter ->
            Text(
                text = letter,
                style = StillTypography.Caption,
                color = StillColors.DimGray,
                modifier = Modifier
                    .weight(1f, fill = true)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    weekStart: DayOfWeek,
    events: List<Event>,
    repository: EventsRepository,
    onOpenDay: (LocalDate) -> Unit,
) {
    val firstCell = month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
    val cells = (0 until 42).map { firstCell.plusDays(it.toLong()) }
    val gridRange = cells.first()..cells.last()
    val occurrencesByDate = remember(events, gridRange) {
        val map = HashMap<LocalDate, Int>()
        events.forEach { event ->
            repository.occurrencesIntersecting(event, gridRange).forEach { date ->
                map[date] = (map[date] ?: 0) + 1
            }
        }
        map
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        for (row in 0 until 6) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val date = cells[row * 7 + col]
                    DayCell(
                        date = date,
                        today = today,
                        currentMonth = month,
                        eventCount = occurrencesByDate[date] ?: 0,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenDay(date) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    currentMonth: YearMonth,
    eventCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isToday = date == today
    val isCurrentMonth = YearMonth.from(date) == currentMonth
    val numberColor: Color = when {
        isToday -> StillColors.SoftWhite
        !isCurrentMonth -> StillColors.DimGray
        else -> StillColors.MutedWhite
    }
    Column(
        modifier = modifier
            .height(64.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = StillTypography.DayNum,
            color = numberColor,
        )
        Spacer(Modifier.height(6.dp))
        DotRow(count = eventCount, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DotRow(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val capped = count.coerceAtMost(3)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(capped) {
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(StillColors.MutedWhite),
            )
        }
    }
}

@Composable
private fun FooterBar(
    modifier: Modifier = Modifier,
    onWeek: () -> Unit,
    onToday: () -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StillVerb(label = "week", onClick = onWeek, bordered = true)
        StillVerb(label = "today", onClick = onToday, bordered = true)
        StillVerb(label = "new", onClick = onNew, bordered = true)
        StillVerb(label = "settings", onClick = onSettings, bordered = true)
    }
}

fun resolveWeekStart(setting: WeekStart): DayOfWeek = when (setting) {
    WeekStart.System -> {
        val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
        first
    }
    WeekStart.Sunday -> DayOfWeek.SUNDAY
    WeekStart.Monday -> DayOfWeek.MONDAY
}

private fun weekdayLetters(weekStart: DayOfWeek): List<String> = (0 until 7).map { offset ->
    val day = weekStart.plus(offset.toLong())
    day.getDisplayName(JTextStyle.NARROW, Locale.getDefault()).lowercase()
}
