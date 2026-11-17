package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val DefaultReadableSurfaceOpacity = 0.88f

internal fun readableSurfaceColor(
    colors: NaviampColors,
    opacity: Float = DefaultReadableSurfaceOpacity,
) = colors.background.copy(alpha = opacity.coerceIn(0f, 1f))

/** A bounded reading surface over the shared background. */
@Composable
internal fun NaviampReadableContent(
    colors: NaviampColors,
    keepDarkSurface: Boolean = false,
    surfaceOpacity: Float = DefaultReadableSurfaceOpacity,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val surface = if (keepDarkSurface || maxWidth >= 900.dp) {
            readableSurfaceColor(colors, surfaceOpacity)
        } else {
            Color.Transparent
        }
        Box(Modifier.widthIn(max = 1120.dp).fillMaxSize()
            .background(surface).padding(12.dp)) { content() }
    }
}
