package dev.chuds.stillcal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography
import kotlinx.coroutines.delay

/**
 * Long-press action sheet — a bottom-anchored card listing lowercase verbs. Mirrors the
 * shape used by still-sms / still-dialer so the ecosystem looks of-a-piece.
 *
 * Setting [confirmTwice] on a destructive action makes the first tap re-label the row to
 * "tap again to confirm" for 4 seconds; the second tap commits. Auto-disarms on dismiss
 * or after the timeout.
 */
data class StillAction(
    val label: String,
    val destructive: Boolean = false,
    val confirmTwice: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun StillActionSheet(
    title: String?,
    actions: List<StillAction>,
    onDismiss: () -> Unit,
) {
    val dismissSource = remember { MutableInteractionSource() }
    var armedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armedLabel) {
        if (armedLabel != null) {
            delay(4000)
            armedLabel = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Translucent scrim so the underlying screen reads as "behind" the sheet
            // without violating the OLED-true-black palette anywhere visible.
            .background(StillColors.OledBlack.copy(alpha = 0.72f))
            .clickable(
                interactionSource = dismissSource,
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(StillColors.OledBlack)
                .border(1.dp, StillColors.Hairline, RoundedCornerShape(14.dp))
                // Block scrim taps from leaking through the sheet itself.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(verticalArrangement = Arrangement.Top) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = StillTypography.Caption,
                        color = StillColors.DimGray,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    StillDivider()
                }
                actions.forEachIndexed { i, action ->
                    val verbSource = remember(action.label) { MutableInteractionSource() }
                    val armed = armedLabel == action.label
                    val displayLabel = if (armed) "tap again to confirm" else action.label
                    val displayColor = when {
                        armed -> StillColors.SoftWhite
                        action.destructive -> StillColors.Gray
                        else -> StillColors.SoftWhite
                    }
                    Text(
                        text = displayLabel,
                        style = StillTypography.Menu,
                        color = displayColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = verbSource,
                                indication = null,
                                onClick = {
                                    if (action.confirmTwice && !armed) {
                                        armedLabel = action.label
                                    } else {
                                        armedLabel = null
                                        action.onClick()
                                        onDismiss()
                                    }
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    if (i < actions.lastIndex) StillDivider()
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
