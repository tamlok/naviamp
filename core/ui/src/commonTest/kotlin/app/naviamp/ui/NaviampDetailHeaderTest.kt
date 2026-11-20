package app.naviamp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampForestTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaviampDetailHeaderTest {
    private val playlist = SharedMediaItemUi("playlist", "Playlist", "Tracks", coverArtUrls = listOf("preview"))

    @Test
    fun explicitPlaylistArtworkWinsOverTrackAndPreviewCovers() {
        val detail = SharedPlaylistDetailUi(playlist.copy(coverArtUrl = "playlist-cover"), tracks("track-cover"))
        assertEquals(listOf("playlist-cover"), detail.headerArtworkUrls())
    }

    @Test
    fun collageUsesOnlyFourDistinctDisplayedCoversAndPreviewWhenTracksHaveNone() {
        val detail = SharedPlaylistDetailUi(playlist, tracks(null, "a", "a", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c", "d"), detail.headerArtworkUrls())
        assertEquals(listOf("preview"), detail.copy(tracks = tracks(null)).headerArtworkUrls())
        assertEquals(emptyList(), detail.copy(playlist = playlist.copy(coverArtUrls = emptyList()), tracks = emptyList()).headerArtworkUrls())
    }

    @Test
    fun artworkTintsPreserveTextAndActionContrastAcrossThemes() {
        val samples = listOf(Color.Black, Color.White, Color.Red, Color.Green, Color.Blue, Color.Yellow, Color.Cyan, Color.Magenta)
        for (pack in listOf(NaviampBuiltinTheme, NaviampForestTheme)) {
            for (theme in listOf(pack.light, pack.dark)) {
                val colors = theme.toNaviampColors()
                val content = naviampDetailHeaderContentColors(colors)
                for (primary in samples) for (secondary in samples) for (lightRatio in listOf(0f, 1f)) {
                    val palette = NaviampAlbumPalette(primary, secondary, secondary, lightSampleRatio = lightRatio)
                    val stops = naviampDetailHeaderColors(palette, colors).gradientColors
                    for ((start, end) in stops.zipWithNext()) for (step in 0..10) {
                        val surface = start.mix(end, step / 10f)
                        for (text in listOf(content.primaryText, content.secondaryText, content.mutedText)) {
                            assertTrue(contrast(text, surface) >= 4.5, "${pack.id}: text on $surface")
                        }
                        assertTrue(contrast(content.favorite, surface) >= 3.0, "${pack.id}: favorite on $surface")
                    }
                }
            }
        }
    }

    @Test
    fun missingArtworkUsesTheExactThemeSurface() {
        for (colors in listOf(NaviampColors.Light, NaviampColors.Dark)) {
            assertEquals(List(3) { colors.background }, naviampDetailHeaderColors(null, colors).gradientColors)
        }
    }

    private fun tracks(vararg covers: String?) = covers.mapIndexed { index, cover ->
        SharedTrackRowUi(index.toString(), "Track", "Artist", coverArtUrl = cover)
    }

    private fun contrast(a: Color, b: Color): Double =
        (maxOf(a.luminance(), b.luminance()) + 0.05) / (minOf(a.luminance(), b.luminance()) + 0.05)
}
