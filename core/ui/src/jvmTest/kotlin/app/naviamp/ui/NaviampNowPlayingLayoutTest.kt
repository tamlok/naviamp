package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampForestTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemePack
import app.naviamp.domain.settings.NaviampThemeSettings
import app.naviamp.domain.playback.EqualizerSettings
import app.naviamp.domain.playback.PlaybackProfileTargetType
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampNowPlayingLayoutTest {
    @Test fun naviampLightPlayerSurfacesRevealChangingBackdrop() = checkPlayerSurfaces(
        NaviampBuiltinTheme, NaviampThemeMode.Light, expectedSurface = null,
    )

    @Test fun naviampDarkPlayerSurfacesRevealChangingBackdrop() = checkPlayerSurfaces(
        NaviampBuiltinTheme, NaviampThemeMode.Dark, expectedSurface = null,
    )

    @Test fun forestLightPlayerSurfacesRemainOpaque() = checkPlayerSurfaces(
        NaviampForestTheme, NaviampThemeMode.Light, expectedSurface = Color(0xFFE8F6EC),
    )

    @Test fun forestDarkPlayerSurfacesRemainOpaque() = checkPlayerSurfaces(
        NaviampForestTheme, NaviampThemeMode.Dark, expectedSurface = Color(0xFF132B1B),
    )

    private fun checkPlayerSurfaces(pack: NaviampThemePack, mode: NaviampThemeMode, expectedSurface: Color?) =
        runDesktopComposeUiTest(1280, 720) {
            val backdrop = mutableStateOf(Color(0xFF2359A1))
            val live = mutableStateOf(false)
            val colors = (if (mode == NaviampThemeMode.Dark) pack.dark else pack.light).toNaviampColors()
            setContent {
                NaviampTheme(NaviampThemeSettings(mode = mode, selectedPackId = pack.id)) {
                    Box(Modifier.fillMaxSize().background(backdrop.value).padding(12.dp)) {
                        NaviampNowPlayingPanel(
                            nowPlaying = NowPlayingUi(
                                id = "current", title = "Current title", subtitle = "Current artist", stateLabel = "Paused",
                                isPaused = true, isLive = live.value,
                                upNext = listOf(NaviampNowPlayingItemUi("next", "Queued item", "Queued artist")),
                                backTo = listOf(NaviampNowPlayingItemUi("past", "History item", "History artist")),
                                related = listOf(NaviampNowPlayingItemUi("related", "Related item", "Related artist")),
                                radioStations = listOf(
                                    NaviampNowPlayingItemUi("current", "Selected station", "Selected broadcaster"),
                                    NaviampNowPlayingItemUi("station", "Other station", "Other broadcaster"),
                                ),
                            ),
                            colors = LocalNaviampTheme.current.colors,
                            actions = NaviampNowPlayingActions({}, {}, {}, {}, {}, {}, {}),
                        )
                    }
                }
            }
            for ((tab, title, subtitle) in listOf(
                Triple("UP NEXT", "Queued item", "Queued artist"),
                Triple("BACK TO", "History item", "History artist"),
                Triple("RELATED", "Related item", "Related artist"),
                Triple("STATIONS", "Other station", "Other broadcaster"),
            )) {
                if (tab == "STATIONS") runOnIdle { live.value = true }
                else onNodeWithText(tab).performClick()
                onNodeWithText(title).assertIsDisplayed()
                for (backingColor in listOf(Color(0xFF2359A1), Color(0xFFAF763B))) {
                    runOnIdle { backdrop.value = backingColor }
                    waitUntil(timeoutMillis = 5_000) {
                        onRoot().captureToImage().toPixelMap()[0, 0] == backingColor
                    }
                    val pixels = onRoot().captureToImage().toPixelMap()
                    fun assertSurface(node: SemanticsNodeInteraction, expected: Color, label: String) {
                        val bounds = node.fetchSemanticsNode().boundsInRoot
                        val x = bounds.left + 2f
                        val y = bounds.center.y
                        val actual = pixels[x.toInt(), y.toInt()]
                        assertEquals(expected.red, actual.red, 1f / 255f, "$tab $label red")
                        assertEquals(expected.green, actual.green, 1f / 255f, "$tab $label green")
                        assertEquals(expected.blue, actual.blue, 1f / 255f, "$tab $label blue")
                        assertEquals(1f, actual.alpha, "$tab $label alpha")
                    }
                    val surface = expectedSurface ?: backingColor
                    assertSurface(onNodeWithTag("now-playing-controller"), surface, "controller")
                    assertSurface(onNodeWithTag("now-playing-side-panel"), surface, "panel")
                    assertSurface(onNode(hasText(title) and hasClickAction()), surface, "unselected row")
                    if (tab == "STATIONS") {
                        val selected = onNode(hasText("Selected station") and hasClickAction())
                            .fetchSemanticsNode().boundsInRoot
                        assertNotEquals(surface, pixels[(selected.left + 2f).toInt(), selected.center.y.toInt()],
                            "Selected station must remain distinguishable from an unselected row")
                    }
                    onNodeWithText("Current title", useUnmergedTree = true).assertIsDisplayed()
                    onNodeWithText("Current artist", useUnmergedTree = true).assertIsDisplayed()
                    for ((text, expected) in listOf(
                        title to colors.primaryText,
                        subtitle to colors.secondaryText,
                    )) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithText(text, useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertEquals(expected, layouts.single().layoutInput.style.color, "$tab $text color")
                    }
                }
            }
        }

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
