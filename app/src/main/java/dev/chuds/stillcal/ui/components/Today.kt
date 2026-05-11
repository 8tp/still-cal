package dev.chuds.stillcal.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Reactive "what day is today" state. Replaces ad-hoc `remember { LocalDate.now() }`
 * calls across the calendar screens — those captured today's date once and never updated,
 * so leaving the app open across midnight left the highlight stranded on yesterday.
 *
 * The producer sleeps until the next local midnight (+1s safety margin), then re-emits.
 * Suspended while the app is backgrounded; Compose resumes the produceState block on
 * recomposition, so a backgrounded-overnight app catches up the next time it draws.
 */
@Composable
fun rememberToday(): State<LocalDate> = produceState(initialValue = LocalDate.now()) {
    while (true) {
        val now = LocalDateTime.now()
        val nextMidnight = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT)
        val sleepMs = Duration.between(now, nextMidnight).toMillis() + 1_000L
        delay(sleepMs.coerceAtLeast(1_000L))
        value = LocalDate.now()
    }
}
