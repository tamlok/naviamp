package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.InterfaceFontSize
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemeSettings
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampMiniPlayerUiTest {
    private fun actions(playback: (NowPlayingPlaybackAction) -> Unit = {}) =
        NaviampNowPlayingActions({ playback(it.action) }, {}, {}, {}, {}, {}, {})

    @Test
    fun emptyPlayerBackgroundTransitionsRemainLiveAcrossStyles() = runComposeUiTest {
        val settings = mutableStateOf(InterfaceSettings(appBackgroundStyle = AppBackgroundStyle.AlbumBlur,
            albumBlurRadiusDp = 36, singleColorHex = "#315577"))
        setContent { NaviampTheme(settings.value.theme) {
            Box(Modifier.width(1000.dp)) {
                NaviampMiniNowPlaying(null, LocalNaviampTheme.current.colors, {}, actions(), settings.value)
            }
        } }
        runOnIdle { settings.value = settings.value.copy(appBackgroundStyle = AppBackgroundStyle.Aurora) }
        mainClock.advanceTimeBy(500)
        runOnIdle { settings.value = settings.value.copy(theme = NaviampThemeSettings(NaviampThemeMode.Light)) }
        mainClock.advanceTimeBy(500)
        runOnIdle { settings.value = settings.value.copy(auroraColorSteps = 5) }
        mainClock.advanceTimeBy(500)
        val gradient = onNodeWithTag("mini-player").captureToImage().toPixelMap()
        assertTrue(gradient[2, 2] != gradient[gradient.width - 3, 2], "Empty player retains its fallback artwork gradient")
        runOnIdle { settings.value = settings.value.copy(appBackgroundStyle = AppBackgroundStyle.SingleColor) }
        val solid = onNodeWithTag("mini-player").captureToImage().toPixelMap()
        assertEquals(Color(0xFF315577), solid[2, 2])
        assertEquals(Color(0xFF315577), solid[solid.width - 3, 2])
        onNodeWithText("Nothing playing").assertIsDisplayed()
        onNodeWithContentDescription("Play").assertIsNotEnabled()
    }

    @Test
    fun backgroundsFollowLiveSystemAppearanceAndExplicitModesWithUnchangedArtwork() = runComposeUiTest {
        val settings = mutableStateOf(InterfaceSettings())
        val systemDark = mutableStateOf(true)
        val track = NowPlayingUi(id = "theme-track", title = "Theme track", subtitle = "Artist", stateLabel = "Paused",
            isPaused = true, coverArtUrl = "naviamp-radio-tile://cover?label=Theme&from=884422&to=224488")
        setContent { NaviampTheme(settings.value.theme, systemDark.value) {
            Box(Modifier.width(1000.dp)) {
                NaviampMiniNowPlaying(track, LocalNaviampTheme.current.colors, {}, actions(), settings.value)
            }
        } }
        fun pixel(): Color = onNodeWithTag("mini-player-background").captureToImage().toPixelMap()[2, 2]
        fun settle() { mainClock.advanceTimeBy(1000); waitForIdle() }
        for (pack in listOf("naviamp", "forest")) {
            for (style in listOf(AppBackgroundStyle.Aurora, AppBackgroundStyle.AlbumBlur)) {
                runOnIdle {
                    settings.value = settings.value.copy(appBackgroundStyle = style,
                        theme = NaviampThemeSettings(NaviampThemeMode.System, pack))
                    systemDark.value = true
                }
                // Palette/image loading is asynchronous; wait for the same cached artwork before comparing themes.
                waitUntil(timeoutMillis = 5_000) { pixel().red > pixel().blue }
                settle()
                val dark = pixel().luminance()
                runOnIdle { systemDark.value = false }
                settle()
                val light = pixel().luminance()
                assertTrue(light > dark + 0.05f, "$pack $style must follow System without a settings or artwork change")
                runOnIdle { systemDark.value = true }
                settle()
                assertEquals(dark, pixel().luminance(), 0.01f)
                runOnIdle { settings.value = settings.value.copy(theme = settings.value.theme.copy(mode = NaviampThemeMode.Light)) }
                settle()
                assertEquals(light, pixel().luminance(), 0.01f)
                runOnIdle { systemDark.value = false }
                settle()
                assertEquals(light, pixel().luminance(), 0.01f, "Explicit Light must ignore System")
            }
        }
    }

    @Test
    fun emptyAndSyntheticPlayersStayVisibleWithoutPlayableActions() = runComposeUiTest {
        val state = mutableStateOf<NowPlayingUi?>(null)
        var opened = false
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, { opened = true }, actions(), InterfaceSettings())
        } }
        fun assertEmpty() {
            onNodeWithText("Nothing playing").assertIsDisplayed()
            onNodeWithText("Choose something to play").assertIsDisplayed()
            onNodeWithTag("mini-player-identity").assert(hasClickAction().not())
                .performTouchInput { click() }
            onNodeWithContentDescription("Previous").assertIsNotEnabled()
            onNodeWithContentDescription("Play").assertIsNotEnabled()
            onNodeWithContentDescription("Next").assertIsNotEnabled()
            runOnIdle { assertEquals(false, opened) }
        }
        assertEmpty()
        runOnIdle { state.value = NowPlayingUi(id = "  ", title = "Stale title", subtitle = "Stale artist",
            stateLabel = "Playing", isPlaying = true, hasPrevious = true, hasNext = true) }
        assertEmpty()
        onNodeWithText("Stale title").assertDoesNotExist()
    }

    @Test
    fun transportTransitionsAndIdentityKeepTheCurrentTrack() = runComposeUiTest {
        val track = NowPlayingUi(id = "track", title = "Song", subtitle = "Artist", stateLabel = "Playing",
            isPlaying = true, hasPrevious = true, hasNext = true)
        val state = mutableStateOf(track)
        val dispatched = mutableListOf<NowPlayingPlaybackAction>()
        var opened = 0
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, { opened++ }, actions {
                dispatched += it
                state.value = when (it) {
                    NowPlayingPlaybackAction.Pause -> state.value.copy(isPlaying = false, isPaused = true)
                    NowPlayingPlaybackAction.Resume, NowPlayingPlaybackAction.PlayCurrent -> state.value.copy(isPlaying = true, isPaused = false)
                    else -> state.value
                }
            }, InterfaceSettings())
        } }
        onNodeWithContentDescription("Pause").assertIsEnabled().performClick()
        onNodeWithContentDescription("Play").performClick()
        onNodeWithContentDescription("Pause").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(isPlaying = false, isPaused = false) }
        onNodeWithContentDescription("Play").performClick()
        onNodeWithContentDescription("Previous").performClick()
        onNodeWithContentDescription("Next").performClick()
        onNodeWithTag("mini-player-identity").performClick().performClick()
        runOnIdle {
            assertEquals(listOf(NowPlayingPlaybackAction.Pause, NowPlayingPlaybackAction.Resume,
                NowPlayingPlaybackAction.PlayCurrent, NowPlayingPlaybackAction.Previous, NowPlayingPlaybackAction.Next), dispatched)
            assertEquals(track.id, state.value.id)
            assertEquals(2, opened)
            state.value = state.value.copy(hasPrevious = false, hasNext = false, canPlayPause = false)
        }
        onNodeWithContentDescription("Previous").assertIsNotEnabled()
        onNodeWithContentDescription("Pause").assertIsNotEnabled()
        onNodeWithContentDescription("Next").assertIsNotEnabled()
    }

    @Test
    fun narrowLargeTextKeepsTitleAndTransportTargetsSeparate() = runComposeUiTest {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NaviampFontSizeScope(InterfaceFontSize.Large, includeStandardScale = true) {
                    Box(Modifier.width(320.dp)) {
                        NaviampMiniNowPlaying(NowPlayingUi(id = "track", title = "A long current track title",
                            subtitle = "Artist", stateLabel = "Paused", isPaused = true, hasNext = true),
                            NaviampColors.Dark, {}, actions(), InterfaceSettings())
                    }
                }
            }
        }
        onNodeWithContentDescription("Previous").assertDoesNotExist()
        onNodeWithText("A long current track title", useUnmergedTree = true).assertIsDisplayed()
        val identity = onNodeWithTag("mini-player-identity").getUnclippedBoundsInRoot()
        val play = onNodeWithContentDescription("Play").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
        val next = onNodeWithContentDescription("Next").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
        assertTrue(play.right - play.left >= 48.dp && play.bottom - play.top >= 48.dp)
        assertTrue(next.right - next.left >= 48.dp && next.bottom - next.top >= 48.dp)
        assertTrue(identity.right <= play.left && play.right <= next.left)
    }

    @Test
    fun liveRadioWithNoTrackIdUsesStationIdentityAndSuppliedCapabilities() = runComposeUiTest {
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(NowPlayingUi(title = "Fixture radio", subtitle = "Live station", stateLabel = "Live",
                isLive = true, isPlaying = true, hasPrevious = false, hasNext = true, canSeek = false),
                NaviampColors.Dark, {}, actions(), InterfaceSettings())
        } }
        onNodeWithText("Fixture radio", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Live station", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("mini-player-identity").assertHasClickAction()
        onNodeWithContentDescription("Previous").assertIsNotEnabled()
        onNodeWithContentDescription("Pause").assertIsEnabled()
        onNodeWithContentDescription("Next").assertIsEnabled()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
    }
}
