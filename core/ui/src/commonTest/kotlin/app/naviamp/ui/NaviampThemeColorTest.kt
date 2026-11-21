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
    fun lightPlayerMetadataIsReadableOnPaleAuroraBackgrounds() {
        val player = NaviampBuiltinTheme.light.toNaviampColors().withPlayerTextColors()
        // Pale rose, warm sand, and cool blue regions; arbitrary dark artwork is not covered.
        for (surface in listOf(Color(0xFFD0ADAC), Color(0xFFD6C2A0), Color(0xFFC3CBD5))) {
            assertTrue(contrast(player.secondaryText, surface) >= 4.5)
            assertTrue(contrast(player.mutedText, surface) >= 4.5)
            assertTrue(contrast(player.secondaryText.copy(alpha = 0.84f).compositeOver(surface), surface) >= 4.5)
        }
    }

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

    @Test
    fun playerSurfacePercentChangesOnlyTheBackgroundAlpha() {
        for (palette in listOf(NaviampForestTheme.light, NaviampForestTheme.dark)) {
            val opaque = palette.copy(playerSurfaceOpacityPercent = 100).toNaviampColors()
            for (percent in listOf(0, 1, 37, 50, 99, 100)) {
                val colors = palette.copy(playerSurfaceOpacityPercent = percent).toNaviampColors()
                assertEquals(percent / 100f, colors.playerSurface.alpha, 1f / 255f)
                assertEquals(opaque.playerSurface.red, colors.playerSurface.red)
                assertEquals(opaque.playerSurface.green, colors.playerSurface.green)
                assertEquals(opaque.playerSurface.blue, colors.playerSurface.blue)
                assertEquals(opaque, colors.copy(playerSurface = opaque.playerSurface))
                val material = colors.toMaterialColorScheme()
                assertEquals(opaque.controlSurface, material.surface)
                assertEquals(opaque.primaryText, material.onSurface)
                assertEquals(opaque.background, material.background)
                assertEquals(Color.Transparent, palette.copy(
                    playerSurface = null, playerSurfaceOpacityPercent = percent,
                ).toNaviampColors().playerSurface)
            }
        }
    }

    @Test
    fun actionTextIsReadableWithoutChangingFilledControlColors() {
        for (pack in listOf(NaviampBuiltinTheme, NaviampForestTheme)) {
            for (palette in listOf(pack.light, pack.dark)) {
                val colors = palette.toNaviampColors()
                assertTrue(contrast(colors.actionText, colors.controlSurface) >= 4.5, "${pack.id} dialog actions")
                assertTrue(contrast(colors.actionText, colors.background) >= 4.5, "${pack.id} page actions")
                val changed = palette.copy(actionText = "#123456").toNaviampColors()
                assertEquals(Color(0xFF123456), changed.actionText)
                assertEquals(colors.accent, changed.accent)
                assertEquals(colors.onAccent, changed.onAccent)
                assertEquals(colors.toMaterialColorScheme().primary, changed.toMaterialColorScheme().primary)
                assertEquals(colors.toMaterialColorScheme().onPrimary, changed.toMaterialColorScheme().onPrimary)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double =
        (max(a.luminance(), b.luminance()) + 0.05) / (min(a.luminance(), b.luminance()) + 0.05)
}
