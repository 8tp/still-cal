package dev.chuds.stillcal.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.chuds.stillcal.ui.theme.StillColors
import dev.chuds.stillcal.ui.theme.StillTypography

/**
 * Lowercase verb — the still-family's button equivalent. Bordered variant gives
 * persistent footer rows a "this is tappable" cue without breaking the monochrome
 * lexicon (1dp Hairline rectangle, no fill, no ripple).
 *
 * Signature matches still-sms / still-clock; default style is intentionally [Caption]
 * (not [Menu]) per still-cal spec §6.1: "the four lowercase verbs, monospace small."
 * Pass `style = StillTypography.Menu` for the larger button look the other siblings use.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StillVerb(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bordered: Boolean = false,
    color: Color = StillColors.SoftWhite,
    style: TextStyle? = null,
) {
    val source = remember { MutableInteractionSource() }
    val resolvedStyle = style ?: StillTypography.Caption
    val resolvedColor = if (enabled) color else StillColors.DimGray
    Text(
        text = label,
        style = resolvedStyle,
        color = resolvedColor,
        modifier = modifier
            .then(
                if (bordered) Modifier.border(1.dp, StillColors.Hairline, RectangleShape)
                else Modifier,
            )
            .combinedClickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
