package app.naviamp.ui

import androidx.compose.ui.graphics.Color
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.DefaultSingleColorHex
import app.naviamp.domain.settings.InterfaceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.luminance

class NaviampAppBackgroundPolicyTest {
    private val colors = NaviampColors.Dark
    private val albumColors = NaviampPlayerColors.fromSingleColor(Color(0xFF186B91), colors)

    @Test
    fun auroraUsesResolvedAppearanceEvenWhenPaletteColorsAreIdentical() {
        for (steps in 2..5) {
            val settings = InterfaceSettings(appBackgroundStyle = AppBackgroundStyle.Aurora, auroraColorSteps = steps)
            val light = background(settings, isDark = false).targetPlayerColors.auroraColors(steps)
            val dark = background(settings, isDark = true).targetPlayerColors.auroraColors(steps)
            light.zip(dark).forEach { (lightStop, darkStop) ->
                assertTrue(lightStop.luminance() > darkStop.luminance())
            }
        }
    }

    @Test
    fun singleColorUsesSelectedColorAndInvalidValuesFallBack() {
        val selected = background(
            InterfaceSettings(
                appBackgroundStyle = AppBackgroundStyle.SingleColor,
                singleColorHex = "#32253F",
            ),
        )
        val fallback = background(
            InterfaceSettings(
                appBackgroundStyle = AppBackgroundStyle.SingleColor,
                singleColorHex = "invalid",
            ),
        )

        assertEquals(Color(0xFF32253F), selected.singleColor)
        assertEquals(naviampColorFromHex(DefaultSingleColorHex), fallback.singleColor)
        assertEquals(
            NaviampPlayerColors.fromSingleColor(selected.singleColor, colors),
            selected.targetPlayerColors,
        )
    }

    private fun background(settings: InterfaceSettings, isDark: Boolean = true): NaviampAppBackgroundUi =
        naviampAppBackgroundUi(
            interfaceSettings = settings,
            coverArtUrl = "cover-art",
            albumPlayerColors = albumColors,
            colors = colors,
            isDark = isDark,
        )
}
