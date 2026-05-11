// Resolution of spec §15.3 — the custom typographic number picker is deferred to v0.2.
// v0.1 ships with the documented fallback: BasicTextField rows constrained to digit input,
// `yyyy-mm-dd` for dates and `HH:mm` for times. The screen still owns its lowercase verbs,
// hairline dividers, and no-ripple discipline.
package dev.chuds.stillcal.ui.event

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.data.Event
import dev.chuds.stillcal.data.Recurrence
import dev.chuds.stillcal.data.RecurrenceKind
import dev.chuds.stillcal.data.ReminderOffset
import dev.chuds.stillcal.data.kind
import dev.chuds.stillcal.ui.components.NotificationsPermissionState
import dev.chuds.stillcal.ui.components.StillDivider
import dev.chuds.stillcal.ui.components.StillVerb
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Single scrollable column form. View == edit; tap any field to change it.
 * Reuses the existing event (when [existing] is non-null) or starts a draft for the
 * date the user navigated from.
 */
@Composable
fun EventEditScreen(
    existing: Event?,
    defaultDate: LocalDate,
    notificationsPermission: NotificationsPermissionState,
    onSave: (Event) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val initial = remember(existing, defaultDate) {
        existing ?: blankEvent(defaultDate, zone)
    }

    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var startDate by remember { mutableStateOf(initial.startDate(zone)) }
    var startTime by remember { mutableStateOf(initial.startTime(zone)) }
    var endDate by remember { mutableStateOf(initial.endDate(zone)) }
    var endTime by remember { mutableStateOf(initial.endTime(zone)) }
    var recurrence by remember { mutableStateOf(initial.rrule.kind()) }
    var until by remember { mutableStateOf(initial.rrule?.until ?: startDate.plusMonths(3)) }
    var reminder by remember { mutableStateOf(initial.reminder) }

    // Snap end past start whenever start moves past it.
    LaunchedEffect(startDate, startTime, allDay) {
        val startMs = combine(startDate, startTime, allDay).atZone(zone).toInstant().toEpochMilli()
        val endMs = combine(endDate, endTime, allDay).atZone(zone).toInstant().toEpochMilli()
        if (endMs <= startMs) {
            if (allDay) {
                endDate = startDate.plusDays(1)
                endTime = startTime
            } else {
                val nextHour = combine(startDate, startTime, false).plusHours(1)
                endDate = nextHour.toLocalDate()
                endTime = nextHour.toLocalTime()
            }
        }
    }

    val untilValid = recurrence == RecurrenceKind.None || !until.isBefore(startDate)
    val canSave = untilValid

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StillColors.OledBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 120.dp),
        ) {
            Text(
                text = if (existing == null) "new" else "edit",
                style = StillTypography.Kicker,
                color = StillColors.DimGray,
            )
            Spacer(Modifier.height(8.dp))

            TitleField(value = title, onChange = { title = it }, autoFocus = existing == null)
            StillDivider()

            ToggleRow(
                label = "all day",
                on = allDay,
                onToggle = { allDay = !allDay },
            )
            StillDivider()

            DateTimeRow(
                label = "start",
                date = startDate,
                time = startTime,
                allDay = allDay,
                onDateChange = { startDate = it },
                onTimeChange = { startTime = it },
            )
            StillDivider()

            DateTimeRow(
                label = "end",
                date = endDate,
                time = endTime,
                allDay = allDay,
                onDateChange = { endDate = it },
                onTimeChange = { endTime = it },
            )
            StillDivider()

            CycleRow(
                label = "repeat",
                value = recurrence.label(),
                onCycle = { recurrence = cycle(recurrence) },
            )
            if (recurrence != RecurrenceKind.None) {
                DateField(
                    label = "until",
                    date = until,
                    onChange = { until = it },
                )
                if (!untilValid) {
                    Text(
                        text = "until must be on or after start",
                        style = StillTypography.Caption,
                        color = StillColors.MutedWhite,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                }
            }
            StillDivider()

            CycleRow(
                label = "reminder",
                value = reminder?.label ?: "none",
                onCycle = {
                    val next = cycleReminder(reminder)
                    reminder = next
                    if (next != null && !notificationsPermission.granted) {
                        notificationsPermission.request()
                    }
                },
            )
            if (reminder != null && !notificationsPermission.granted) {
                Text(
                    text = "notifications disabled — reminder won't fire",
                    style = StillTypography.Caption,
                    color = StillColors.MutedWhite,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
            }
            StillDivider()

            NotesField(value = notes, onChange = { notes = it })
        }

        FooterBar(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
            showDelete = existing != null,
            canSave = canSave,
            onSave = {
                val rrule: Recurrence? = when (recurrence) {
                    RecurrenceKind.None -> null
                    RecurrenceKind.Daily -> Recurrence.Daily(until)
                    RecurrenceKind.Weekly -> Recurrence.Weekly(until)
                    RecurrenceKind.Monthly -> Recurrence.Monthly(until)
                }
                val nowMs = System.currentTimeMillis()
                val event = initial.copy(
                    title = title.ifBlank { "untitled" },
                    notes = notes,
                    allDay = allDay,
                    startEpochMs = combine(startDate, startTime, allDay).atZone(zone).toInstant().toEpochMilli(),
                    endEpochMs = combine(endDate, endTime, allDay).atZone(zone).toInstant().toEpochMilli(),
                    tzId = zone.id,
                    rrule = rrule,
                    reminder = reminder,
                    updatedAt = nowMs,
                )
                onSave(event)
            },
            onDelete = onDelete,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun TitleField(value: String, onChange: (String) -> Unit, autoFocus: Boolean) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = StillTypography.Title.copy(color = StillColors.SoftWhite),
        cursorBrush = SolidColor(StillColors.SoftWhite),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .focusRequester(focusRequester),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = "untitled",
                        style = StillTypography.Title,
                        color = StillColors.DimGray,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun NotesField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = StillTypography.Body.copy(color = StillColors.SoftWhite),
        cursorBrush = SolidColor(StillColors.SoftWhite),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = "notes",
                        style = StillTypography.Body,
                        color = StillColors.DimGray,
                    )
                }
                inner()
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = StillTypography.Menu, color = StillColors.SoftWhite)
        Spacer(Modifier.weight(1f))
        Text(
            text = if (on) "[ on ]" else "[ off ]",
            style = StillTypography.Caption,
            color = if (on) StillColors.SoftWhite else StillColors.MutedWhite,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CycleRow(label: String, value: String, onCycle: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onCycle,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = StillTypography.Menu, color = StillColors.SoftWhite)
        Spacer(Modifier.weight(1f))
        Text(text = value, style = StillTypography.Caption, color = StillColors.MutedWhite)
    }
}

@Composable
private fun DateTimeRow(
    label: String,
    date: LocalDate,
    time: java.time.LocalTime,
    allDay: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (java.time.LocalTime) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, style = StillTypography.Menu, color = StillColors.SoftWhite)
        Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            DateField(label = null, date = date, onChange = onDateChange)
            if (!allDay) {
                Spacer(Modifier.width(16.dp))
                TimeField(time = time, onChange = onTimeChange)
            }
        }
    }
}

@Composable
private fun DateField(label: String?, date: LocalDate, onChange: (LocalDate) -> Unit) {
    var text by remember(date) { mutableStateOf(date.toString()) }
    Column(modifier = Modifier.padding(top = if (label == null) 0.dp else 8.dp)) {
        if (label != null) {
            Text(label, style = StillTypography.Caption, color = StillColors.DimGray)
        }
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == '-' }.take(10)
                text = cleaned
                runCatching { LocalDate.parse(cleaned) }.getOrNull()?.let(onChange)
            },
            singleLine = true,
            textStyle = StillTypography.Caption.copy(color = StillColors.SoftWhite),
            cursorBrush = SolidColor(StillColors.SoftWhite),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp).padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun TimeField(time: java.time.LocalTime, onChange: (java.time.LocalTime) -> Unit) {
    var text by remember(time) {
        mutableStateOf(String.format("%02d:%02d", time.hour, time.minute))
    }
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == ':' }.take(5)
            text = cleaned
            runCatching { java.time.LocalTime.parse(cleaned) }.getOrNull()?.let(onChange)
        },
        singleLine = true,
        textStyle = StillTypography.Caption.copy(color = StillColors.SoftWhite),
        cursorBrush = SolidColor(StillColors.SoftWhite),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(70.dp).padding(vertical = 4.dp),
    )
}

@Composable
private fun FooterBar(
    modifier: Modifier = Modifier,
    showDelete: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    // Two-tap confirm — matches DayListScreen's action sheet pattern.
    var deleteArmed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(deleteArmed) {
        if (deleteArmed) {
            kotlinx.coroutines.delay(4000)
            deleteArmed = false
        }
    }
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StillVerb(label = "cancel", onClick = onCancel, bordered = true)
        if (showDelete) {
            StillVerb(
                label = if (deleteArmed) "confirm" else "delete",
                onClick = {
                    if (deleteArmed) {
                        deleteArmed = false
                        onDelete()
                    } else {
                        deleteArmed = true
                    }
                },
                bordered = true,
                color = if (deleteArmed) StillColors.SoftWhite else StillColors.MutedWhite,
            )
        }
        StillVerb(label = "save", onClick = onSave, bordered = true, enabled = canSave)
    }
}

private fun blankEvent(defaultDate: LocalDate, zone: ZoneId): Event {
    val start = defaultDate.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    val end = defaultDate.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    val now = System.currentTimeMillis()
    return Event(
        id = "",
        title = "",
        notes = "",
        startEpochMs = start,
        endEpochMs = end,
        allDay = false,
        tzId = zone.id,
        rrule = null,
        reminder = null,
        createdAt = now,
        updatedAt = now,
    )
}

private fun Event.startDate(zone: ZoneId): LocalDate =
    java.time.Instant.ofEpochMilli(startEpochMs).atZone(zone).toLocalDate()

private fun Event.startTime(zone: ZoneId): java.time.LocalTime =
    java.time.Instant.ofEpochMilli(startEpochMs).atZone(zone).toLocalTime()

private fun Event.endDate(zone: ZoneId): LocalDate =
    java.time.Instant.ofEpochMilli(endEpochMs).atZone(zone).toLocalDate()

private fun Event.endTime(zone: ZoneId): java.time.LocalTime =
    java.time.Instant.ofEpochMilli(endEpochMs).atZone(zone).toLocalTime()

private fun combine(date: LocalDate, time: java.time.LocalTime, allDay: Boolean): LocalDateTime =
    if (allDay) date.atStartOfDay() else date.atTime(time)

private fun cycle(kind: RecurrenceKind): RecurrenceKind = when (kind) {
    RecurrenceKind.None -> RecurrenceKind.Daily
    RecurrenceKind.Daily -> RecurrenceKind.Weekly
    RecurrenceKind.Weekly -> RecurrenceKind.Monthly
    RecurrenceKind.Monthly -> RecurrenceKind.None
}

private fun cycleReminder(current: ReminderOffset?): ReminderOffset? = when (current) {
    null -> ReminderOffset.AtStart
    ReminderOffset.AtStart -> ReminderOffset.FiveMin
    ReminderOffset.FiveMin -> ReminderOffset.FifteenMin
    ReminderOffset.FifteenMin -> ReminderOffset.OneHour
    ReminderOffset.OneHour -> ReminderOffset.OneDay
    ReminderOffset.OneDay -> null
}

private fun RecurrenceKind.label(): String = when (this) {
    RecurrenceKind.None -> "none"
    RecurrenceKind.Daily -> "daily"
    RecurrenceKind.Weekly -> "weekly"
    RecurrenceKind.Monthly -> "monthly"
}
