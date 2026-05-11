package dev.chuds.stillcal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Compose theme for Still Cal. Material3 supplies text/accessibility plumbing only;
 * colors are pinned to the same monochrome tokens as still-launcher and still-notes.
 */
private val StillColorScheme = darkColorScheme(
    background = StillColors.OledBlack,
    surface = StillColors.OledBlack,
    surfaceVariant = StillColors.CodeSurface,
    primary = StillColors.SoftWhite,
    secondary = StillColors.Gray,
    onBackground = StillColors.SoftWhite,
    onSurface = StillColors.SoftWhite,
    onPrimary = StillColors.OledBlack,
    onSecondary = StillColors.OledBlack,
    outline = StillColors.Hairline,
)

@Composable
fun StillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StillColorScheme,
        content = content,
    )
}
