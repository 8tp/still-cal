package dev.chuds.stillcal.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.data.CalSettings
import dev.chuds.stillcal.data.DefaultView
import dev.chuds.stillcal.data.FontPreset
import dev.chuds.stillcal.data.TimeFormat
import dev.chuds.stillcal.data.WeekStart
import dev.chuds.stillcal.ui.components.StillDivider
import dev.chuds.stillcal.ui.components.StillMenuItem
import dev.chuds.stillcal.ui.components.StillVerb
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    settings: CalSettings,
    onCycleFontPreset: () -> Unit,
    onCycleDefaultView: () -> Unit,
    onCycleWeekStart: () -> Unit,
    onCycleTimeFormat: () -> Unit,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit,
) {
    var deleteArmed by remember { mutableStateOf(false) }
    LaunchedEffect(deleteArmed) {
        if (deleteArmed) {
            delay(4000)
            deleteArmed = false
        }
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
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 96.dp),
        ) {
            Text(text = "settings", style = StillTypography.Display, color = StillColors.SoftWhite)
            Text(
                text = "still cal · v0.1.0",
                style = StillTypography.Caption,
                color = StillColors.DimGray,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )

            StillMenuItem(
                title = "font",
                subtitle = settings.fontPreset.label(),
                onClick = onCycleFontPreset,
            )
            StillDivider()
            StillMenuItem(
                title = "default view",
                subtitle = settings.defaultView.label(),
                onClick = onCycleDefaultView,
            )
            StillDivider()
            StillMenuItem(
                title = "week starts on",
                subtitle = settings.weekStart.label(),
                onClick = onCycleWeekStart,
            )
            StillDivider()
            StillMenuItem(
                title = "time format",
                subtitle = settings.timeFormat.label(),
                onClick = onCycleTimeFormat,
            )

            Spacer(Modifier.height(20.dp))

            StillMenuItem(
                title = "import .ics",
                subtitle = "pick one or more files via the system picker",
                onClick = onImport,
            )
            StillDivider()
            StillMenuItem(
                title = "export all",
                subtitle = "save every event as a single .ics",
                onClick = onExportAll,
            )
            StillDivider()
            StillMenuItem(
                title = "delete all events",
                subtitle = if (deleteArmed) "tap again to confirm" else "removes every event from disk",
                titleColor = if (deleteArmed) StillColors.SoftWhite else StillColors.MutedWhite,
                onClick = {
                    if (deleteArmed) {
                        deleteArmed = false
                        onDeleteAll()
                    } else {
                        deleteArmed = true
                    }
                },
            )
        }

        FooterBar(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding(),
            onBack = onBack,
        )
    }
}

@Composable
private fun FooterBar(modifier: Modifier = Modifier, onBack: () -> Unit) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StillVerb(label = "back", onClick = onBack, bordered = true)
    }
}

private fun FontPreset.label(): String = when (this) {
    FontPreset.System -> "system — serif + sans + mono"
    FontPreset.Editorial -> "editorial — cormorant + inter + plex"
    FontPreset.Terminal -> "terminal — plex mono throughout"
    FontPreset.Grotesk -> "grotesk — instrument serif + space"
}

private fun DefaultView.label(): String = when (this) {
    DefaultView.Month -> "month"
    DefaultView.Week -> "week"
}

private fun WeekStart.label(): String = when (this) {
    WeekStart.System -> "system"
    WeekStart.Sunday -> "sunday"
    WeekStart.Monday -> "monday"
}

private fun TimeFormat.label(): String = when (this) {
    TimeFormat.System -> "system"
    TimeFormat.Hour12 -> "12 hour"
    TimeFormat.Hour24 -> "24 hour"
}
