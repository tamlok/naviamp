package app.naviamp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampForestTheme
import app.naviamp.domain.settings.NaviampThemeSettings
import app.naviamp.domain.settings.resolveNaviampTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NaviampThemeColorTest {
    @Test
    fun customArtworkUsesItsDarkPaletteWhileAppRemainsLight() {
        val resolved = resolveNaviampTheme(
            NaviampThemeSettings(mode = NaviampThemeMode.Light, selectedPackId = NaviampForestTheme.id),
            systemDark = true,
        )
        val app = resolved.palette.toNaviampColors()
        val overlay = naviampArtworkOverlayColors(resolved.pack)
        assertEquals(Color(0xFF142018), app.primaryText)
        assertEquals(Color(0xFFF0FFF3), overlay.primaryText)
        assertNotEquals(app.primaryText, overlay.primaryText)
        // Metadata and controls sit in the bottom portion of the fixed black artwork fade.
        for (artwork in listOf(Color.Black, Color.White, Color.Red, Color.Green, Color.Blue)) {
            val surface = Color.Black.copy(alpha = 0.90f).compositeOver(artwork)
            assertTrue(contrast(overlay.primaryText, surface) >= 4.5)
            assertTrue(contrast(overlay.secondaryText, surface) >= 4.5)
        }
    }

    @Test
    fun readingSurfacesPreserveCustomForegroundAndProtectBothVariants() {
        for (pack in listOf(NaviampBuiltinTheme, NaviampForestTheme)) {
            for (palette in listOf(pack.light, pack.dark)) {
                val original = palette.toNaviampColors()
                val reading = televisionReadingColors(original)
                assertEquals(original.primaryText, reading.primaryText)
                assertEquals(original.primaryText, reading.secondaryText)
                assertEquals(original.primaryText, reading.mutedText)
                assertEquals(1f, televisionReadingSurfaceColor(reading).alpha)
                assertTrue(contrast(reading.primaryText, televisionReadingSurfaceColor(reading)) >= 4.5)
                assertTrue(contrast(original.onAccent, original.accent) >= 4.5)
                assertTrue(contrast(original.onError, original.error) >= 4.5)
                assertTrue(contrast(original.onSuccess, original.success) >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double =
        (max(a.luminance(), b.luminance()) + 0.05) / (min(a.luminance(), b.luminance()) + 0.05)
}
