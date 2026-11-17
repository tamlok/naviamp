package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.naviamp.domain.playback.EqualizerSettings
import app.naviamp.domain.playback.PlaybackProfileTargetType
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampNowPlayingLayoutTest {
    @Test fun adaptivePlayerHasOneWorkingFullscreenControl() = runDesktopComposeUiTest(1000, 740) {
        val controller = NaviampWindowController({ true }, NaviampWindowSnapshot())
        setContent {
            NaviampWindowEnvironment(controller, app.naviamp.domain.settings.DesktopShortcutPlatform.Linux) {
                NaviampNowPlayingPanel(
                    nowPlaying = NowPlayingUi(id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused"),
                    colors = NaviampColors(),
                    actions = NaviampNowPlayingActions({}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        onAllNodesWithTag("window-fullscreen").assertCountEquals(1)
        onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(NaviampWindowPlacement.Fullscreen, controller.state.placement) }
        onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(NaviampWindowPlacement.Floating, controller.state.placement) }
    }

    @Test fun adaptiveWidePlayerTogglesLyricsWithoutRemovingQueueOrTransport() = runDesktopComposeUiTest(1280, 720) {
        val lyricsVisible = mutableStateOf(false)
        val selections = mutableListOf<String>()
        setContent {
            NaviampNowPlayingPanel(
                NowPlayingUi(
                    id = "song", title = "Current song", subtitle = "An artist", stateLabel = "Paused",
                    isPaused = true, durationSeconds = 240.0, positionSeconds = 30.0,
                    lyricsAvailable = true, lyricsVisible = lyricsVisible.value,
                    lyricsLines = listOf(NaviampLyricLineUi(0, "Current lyric"), NaviampLyricLineUi(60000, "Next lyric")),
                    upNext = listOf(NaviampNowPlayingItemUi("next", "Next song", "Artist")),
                ),
                colors = NaviampColors(),
                actions = NaviampNowPlayingActions(
                    {}, { lyricsVisible.value = !lyricsVisible.value }, {}, {}, {},
                    { selections += it.item.id }, {},
                ),
            )
        }
        onNodeWithContentDescription("Show lyrics").performClick()
        onNodeWithText("Current lyric").assertIsDisplayed()
        onNodeWithText("Current song").assertIsDisplayed()
        onNodeWithContentDescription("Play").assertIsDisplayed()
        onNodeWithText("Next song").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("next"), selections) }
        onNodeWithContentDescription("Hide lyrics").performClick()
        onNodeWithText("Current lyric").assertDoesNotExist()
        onNodeWithText("Next song").assertIsDisplayed()
    }

    @Test fun shortCompactPlayerKeepsTransportCollapseAndQueueReachable() = checkShortPlayer(420)

    @Test fun shortWidePlayerKeepsTransportCollapseAndQueueReachable() = checkShortPlayer(1000)

    private fun checkShortPlayer(width: Int) = runDesktopComposeUiTest(width, 240) {
        val playback = mutableListOf<NowPlayingPlaybackAction>()
        val display = mutableListOf<NowPlayingDisplayAction>()
        val selections = mutableListOf<String>()
        setContent {
            NaviampNowPlayingPanel(
                NowPlayingUi(
                    id = "song", title = "Current song", subtitle = "Artist", stateLabel = "Playing",
                    isPlaying = true,
                    upNext = listOf(NaviampNowPlayingItemUi("next", "Next song", "Artist")),
                ),
                colors = NaviampColors(),
                actions = NaviampNowPlayingActions(
                    { playback += it.action }, { display += it.action }, {}, {}, {},
                    { selections += it.item.id }, {},
                ),
                modifier = Modifier.testTag("detail-panel"),
            )
        }
        val panel = onNodeWithTag("detail-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.bottom <= onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        onNodeWithText("Current song").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Pause").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithContentDescription("Collapse player").performScrollTo().assertIsDisplayed().performClick()
        if (width < 780) {
            val queue = onNodeWithTag("now-playing-side-panel").performScrollTo().assertIsDisplayed()
            val bounds = queue.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= panel.top && bounds.bottom <= panel.bottom)
        }
        onNodeWithText("Next song").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(listOf(NowPlayingPlaybackAction.Pause), playback)
            assertEquals(listOf(NowPlayingDisplayAction.Collapse), display)
            assertEquals(listOf("next"), selections)
        }
    }

    @Test fun playlistContextUpdatesAndDisappearsInTheRenderedPlayer() = runDesktopComposeUiTest(420, 800) {
        val context = mutableStateOf<NowPlayingQueueContextUi?>(
            NowPlayingQueueContextUi(PlaybackProfileTargetType.Playlist, "Road Trip"),
        )
        val showPlaybackSource = mutableStateOf(false)
        setContent {
            NaviampNowPlayingPanel(
                nowPlaying = NowPlayingUi(
                    id = "song", title = "Song", subtitle = "Artist", stateLabel = "Playing",
                    queueContext = context.value,
                ),
                colors = NaviampColors(),
                actions = NaviampNowPlayingActions({}, {}, {}, {}, {}, {}, {}),
                displaySettings = app.naviamp.domain.settings.NowPlayingDisplaySettings(
                    showPlaybackSource = showPlaybackSource.value,
                ),
            )
        }
        onNodeWithTag("now-playing-queue-context").assertDoesNotExist()
        showPlaybackSource.value = true
        waitForIdle()
        onNodeWithTag("now-playing-queue-context").assertTextContains("Road Trip", substring = true)
        context.value = NowPlayingQueueContextUi(PlaybackProfileTargetType.Album, "Evening Songs")
        waitForIdle()
        onNodeWithTag("now-playing-queue-context").assertTextContains("Evening Songs", substring = true)
        context.value = null
        waitForIdle()
        onNodeWithTag("now-playing-queue-context").assertDoesNotExist()
    }

    @Test fun readableSurfaceSupportsTransparentAndOpaqueBackgrounds() = runDesktopComposeUiTest(1000, 200) {
        val opacity = mutableStateOf(0f)
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                NaviampReadableContent(
                    NaviampColors.Dark,
                    keepDarkSurface = true,
                    surfaceOpacity = opacity.value,
                ) {}
            }
        }
        assertEquals(Color.Magenta.toArgb(), onRoot().captureToImage().toPixelMap()[500, 100].toArgb())
        opacity.value = 1f
        waitForIdle()
        assertEquals(
            NaviampColors.Dark.background.toArgb(),
            onRoot().captureToImage().toPixelMap()[500, 100].toArgb(),
        )
    }

    @Test fun narrowPagesPreserveSelectedBackground() = checkReadingSurface(300, false, false)
    @Test fun widePagesKeepDarkSurface() = checkReadingSurface(1000, false, true)
    @Test fun narrowSettingsKeepDarkSurface() = checkReadingSurface(300, true, true)

    private fun checkReadingSurface(width: Int, keepDarkSurface: Boolean, expectDark: Boolean) = runDesktopComposeUiTest(width, 640) {
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                NaviampReadableContent(NaviampColors.Dark, keepDarkSurface = keepDarkSurface) {}
            }
        }
        val pixel = onRoot().captureToImage().toPixelMap()[width / 2, 320]
        val expected = if (expectDark) readableSurfaceColor(NaviampColors.Dark).compositeOver(Color.Magenta) else Color.Magenta
        assertEquals(expected.red, pixel.red, 0.01f)
        assertEquals(expected.green, pixel.green, 0.01f)
        assertEquals(expected.blue, pixel.blue, 0.01f)
    }

    @Test fun mutedTextHasReadableContrastEvenOverWhiteArtwork() {
        val colors = NaviampColors.Dark
        val surface = readableSurfaceColor(colors).compositeOver(Color.White)
        val contrast = (colors.mutedText.luminance() + 0.05f) / (surface.luminance() + 0.05f)
        assertTrue(contrast >= 4.5f, "Muted text contrast: $contrast")
    }
    @Test fun adaptivePlayerTransportControlsReceivePointerInput() = runDesktopComposeUiTest(1280, 720) {
        val actions = mutableListOf<NowPlayingPlaybackAction>()
        setContent {
            NaviampNowPlayingPanel(
                NowPlayingUi(
                    id = "song",
                    title = "Song",
                    subtitle = "Artist",
                    stateLabel = "Playing",
                    isPlaying = true,
                    hasPrevious = true,
                    hasNext = true,
                    volumePercent = 50,
                ),
                colors = NaviampColors(),
                actions = NaviampNowPlayingActions(
                    onPlaybackAction = { actions += it.action },
                    onDisplayAction = {},
                    onCurrentTrackAction = {},
                    onQueueAction = {},
                    onSleepTimerAction = {},
                    onSelectionAction = {},
                    onQueueItemAction = {},
                ),
            )
        }
        onNodeWithContentDescription("Previous").performMouseInput { click() }
        onNodeWithContentDescription("Pause").performMouseInput { click() }
        onNodeWithContentDescription("Next").performMouseInput { click() }
        assertEquals(
            listOf(
                NowPlayingPlaybackAction.Previous,
                NowPlayingPlaybackAction.Pause,
                NowPlayingPlaybackAction.Next,
            ),
            actions,
        )
    }

    @Test fun equalizerLabelsFitMinimumWidth() = runDesktopComposeUiTest(258, 360) {
        setContent { EqualizerCurvePanel(NaviampColors(), EqualizerSettings(), false) { _, _ -> } }
        for (label in listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")) {
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(label).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }, "$label must fit")
        }
    }
}
