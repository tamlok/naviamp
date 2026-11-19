package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
    @Test fun controlCardInsetsKeepProgressAndActionsAwayFromEveryEdge() {
        for ((width, height) in listOf(1280 to 720, 360 to 640, 420 to 900, 1000 to 240)) {
            runDesktopComposeUiTest(width, height) {
                setContent {
                    NaviampNowPlayingPanel(
                        nowPlaying = NowPlayingUi(
                            id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                            isPaused = true, positionSeconds = 15.0, durationSeconds = 255.0,
                        ),
                        colors = NaviampColors(),
                        actions = NaviampNowPlayingActions({}, {}, {}, {}, {}, {}, {}),
                    )
                }
                val viewport = onRoot().getUnclippedBoundsInRoot()
                val card = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
                val duration = onNodeWithText("4:15").getUnclippedBoundsInRoot()
                val actions = onNodeWithTag("now-playing-bottom-actions").getUnclippedBoundsInRoot()
                val transport = onNodeWithTag("now-playing-transport-row").getUnclippedBoundsInRoot()
                val label = "${width}x$height"
                assertEquals(14.dp, card.left - viewport.left, "$label outer card inset")
                assertTrue(duration.top >= card.top + 16.dp, "$label progress top inset")
                assertEquals(16.dp, card.right - duration.right, "$label progress right inset")
                assertEquals(16.dp, actions.left - card.left, "$label actions left inset")
                assertEquals(16.dp, card.right - actions.right, "$label actions right inset")
                assertTrue(actions.bottom <= card.bottom - 16.dp, "$label actions bottom inset")
                assertTrue(transport.bottom <= actions.top, "$label transport must not overlap actions")
                onNodeWithContentDescription("Track actions").performScrollTo().assertIsDisplayed()
            }
        }
    }

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
            onNodeWithContentDescription("Queue").performClick()
            onNodeWithTag("now-playing-lyrics-card").assertDoesNotExist()
            val drawerBounds = onNodeWithTag("now-playing-queue-drawer").fetchSemanticsNode().boundsInRoot
            val drawerPixels = onRoot().captureToImage().toPixelMap()
            assertEquals(colors.controlSurface,
                drawerPixels[(drawerBounds.left + 2f).toInt(), drawerBounds.center.y.toInt()],
                "Drawer must cover the lyrics with an opaque surface")
            onNodeWithContentDescription("Pin queue").performClick()
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
                    assertSurface(onNodeWithTag("now-playing-lyrics-card"), surface, "lyrics card")
                    assertSurface(onNodeWithTag("now-playing-queue-card"), surface, "queue card")
                    val lyricsBounds = onNodeWithTag("now-playing-lyrics-card").fetchSemanticsNode().boundsInRoot
                    val queueBounds = onNodeWithTag("now-playing-queue-card").fetchSemanticsNode().boundsInRoot
                    assertEquals(backingColor, pixels[lyricsBounds.center.x.toInt(),
                        ((lyricsBounds.bottom + queueBounds.top) / 2).toInt()], "Cards must not share a painted wrapper")
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
                        subtitle to colors.playerSecondaryText,
                    )) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithText(text, useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertEquals(expected, layouts.single().layoutInput.style.color, "$tab $text color")
                    }
                }
            }
        }

    @Test fun adaptivePlayerHasOnePinnedBackWithoutChangingPlaybackOrWindowState() {
        val cases = listOf(
            420 to null,
            1000 to NaviampWindowPlacement.Floating,
            420 to NaviampWindowPlacement.Fullscreen,
            1000 to NaviampWindowPlacement.Fullscreen,
        )
        for ((width, placement) in cases) runDesktopComposeUiTest(width, 740) {
            val requests = mutableListOf<NaviampWindowSnapshot>()
            val initial = placement?.let { NaviampWindowSnapshot(placement = it) }
            val controller = initial?.let { NaviampWindowController({ requests += it; true }, it) }
            val windowedSnapshot = controller?.windowedSnapshot
            val playback = mutableListOf<NowPlayingPlaybackAction>()
            val display = mutableListOf<NowPlayingDisplayAction>()
            setContent {
                NaviampWindowEnvironment(controller, app.naviamp.domain.settings.DesktopShortcutPlatform.Linux) {
                    NaviampNowPlayingPanel(
                        nowPlaying = NowPlayingUi(
                            id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                            visualizerAvailable = true,
                            visualizerVisible = width == 1000 && placement == NaviampWindowPlacement.Fullscreen,
                        ),
                        colors = NaviampColors(),
                        actions = NaviampNowPlayingActions(
                            { playback += it.action }, { display += it.action }, {}, {}, {}, {}, {},
                        ),
                        contentInsets = WindowInsets(left = 16, top = 24, right = 8, bottom = 32),
                    )
                }
            }
            onAllNodesWithContentDescription("Back").assertCountEquals(1)
            onAllNodesWithTag("now-playing-back").assertCountEquals(1)
            val back = onNodeWithTag("now-playing-back").assertIsDisplayed().assertContentDescriptionEquals("Back")
            val bounds = back.fetchSemanticsNode().boundsInRoot
            val root = onRoot().fetchSemanticsNode()
            val density = root.layoutInfo.density.density
            assertEquals(root.boundsInRoot.left + 16f, bounds.left, 0.5f)
            assertEquals(root.boundsInRoot.top + 24f, bounds.top, 0.5f)
            assertEquals(48f * density, bounds.width, 0.5f)
            assertEquals(48f * density, bounds.height, 0.5f)
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
            onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
            back.performClick()
            runOnIdle {
                assertEquals(listOf(NowPlayingDisplayAction.Collapse), display)
                assertTrue(playback.isEmpty())
                assertTrue(requests.isEmpty())
                assertEquals(initial, controller?.state)
                assertEquals(windowedSnapshot, controller?.windowedSnapshot)
            }
        }
    }

    @Test fun adaptivePlayerOpensQueueDrawerAndPinsAlignedCards() {
        for ((width, height, filled) in listOf(
            Triple(1280, 720, false), Triple(983, 649, false), Triple(1000, 480, false),
            Triple(420, 800, false), Triple(420, 800, true), Triple(1000, 240, false),
            Triple(800, 1000, false),
        )) runDesktopComposeUiTest(width, height) {
            val trackId = mutableStateOf("song")
            val display = mutableListOf<NowPlayingDisplayActionRequest>()
            val playback = mutableListOf<NowPlayingPlaybackActionRequest>()
            val selections = mutableListOf<String>()
            setContent {
                NaviampNowPlayingPanel(
                    NowPlayingUi(
                        id = trackId.value, title = "Current song", subtitle = "An artist", stateLabel = "Paused",
                        isPaused = true, durationSeconds = 240.0, positionSeconds = 30.0,
                        lyricsAvailable = true,
                        lyricsLines = listOf(NaviampLyricLineUi(0, "Current lyric"), NaviampLyricLineUi(60000, "Next lyric")),
                        upNext = listOf(NaviampNowPlayingItemUi("next", "Next song", "Artist")),
                    ),
                    colors = NaviampForestTheme.dark.toNaviampColors(),
                    actions = NaviampNowPlayingActions(
                        { playback += it }, { display += it }, {}, {}, {}, { selections += it.item.id }, {},
                    ),
                    displaySettings = app.naviamp.domain.settings.NowPlayingDisplaySettings(fillArtworkArea = filled),
                )
            }
            val back = onNodeWithTag("now-playing-back").assertIsDisplayed()
            val backBounds = back.fetchSemanticsNode().boundsInRoot
            val lyrics = onNodeWithTag("now-playing-lyrics-card")
            val queueButton = onNodeWithContentDescription("Queue")
            onNodeWithTag("now-playing-queue-card").assertDoesNotExist()
            queueButton.performScrollTo().assertIsNotSelected().performClick()
            onNodeWithTag("now-playing-queue-drawer").assertIsDisplayed()
            onNodeWithContentDescription("Close").assertDoesNotExist()
            lyrics.assertDoesNotExist()
            onNodeWithContentDescription("Pin queue").assertIsDisplayed().performClick()
            onNodeWithTag("now-playing-queue-card").assertExists()
            onNodeWithContentDescription("Close").assertDoesNotExist()
            if (width < 780 || height < 400) lyrics.performScrollTo()
            lyrics.assertIsDisplayed()
            onNodeWithText("Current lyric").assertIsDisplayed()
            if (width < 780 && !filled) {
                val controls = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
                assertTrue(lyrics.getUnclippedBoundsInRoot().top - controls.bottom >= 8.dp)
            }
            val lyricsWithQueue = lyrics.getUnclippedBoundsInRoot()
            val queue = onNodeWithTag("now-playing-queue-card").getUnclippedBoundsInRoot()
            assertEquals(8.dp, queue.top - lyricsWithQueue.bottom)
            assertEquals(lyricsWithQueue.left, queue.left)
            assertEquals(lyricsWithQueue.right, queue.right)
            if (width >= 780) {
                val controls = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
                assertEquals(controls.top, queue.top, "Control and queue card tops must align")
                assertEquals(controls.bottom, queue.bottom, "Control and queue card bottoms must align")
                val slot = onNodeWithTag("now-playing-artwork-slot").getUnclippedBoundsInRoot()
                val artwork = onNodeWithTag("now-playing-artwork").getUnclippedBoundsInRoot()
                val onePixel = 1f / onRoot().fetchSemanticsNode().layoutInfo.density.density
                val topHeight = lyricsWithQueue.bottom - lyricsWithQueue.top
                val bottomHeight = controls.bottom - controls.top
                assertTrue(kotlin.math.abs((topHeight - bottomHeight).value) <= onePixel,
                    "Player rows must share the height equally, allowing one leftover pixel")
                assertEquals(lyricsWithQueue.top, slot.top)
                assertEquals(lyricsWithQueue.bottom, slot.bottom)
                assertEquals(artwork.right - artwork.left, artwork.bottom - artwork.top, "Artwork stays square")
                val expectedArtworkHeight = minOf(topHeight, controls.right - controls.left)
                assertTrue(kotlin.math.abs((artwork.bottom - artwork.top - expectedArtworkHeight).value) <= onePixel,
                    "Artwork must fill the row height unless constrained by the column width")
                assertTrue(artwork.top >= slot.top && artwork.bottom <= slot.bottom)
            }
            queueButton.performScrollTo().assertIsEnabled().assertIsSelected()
            val controlsBeforeDrawer = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
            val artworkBeforeDrawer = if (width >= 780) onNodeWithTag("now-playing-artwork-slot").getUnclippedBoundsInRoot() else null
            queueButton.performMouseInput { click() }
            val drawer = onNodeWithTag("now-playing-queue-drawer").assertIsDisplayed().getUnclippedBoundsInRoot()
            onNodeWithTag("now-playing-queue-card").assertDoesNotExist()
            lyrics.assertDoesNotExist()
            assertEquals(48.dp, drawer.top, "Drawer is below the pinned Back button")
            assertEquals((height - 14).dp, drawer.bottom, "Drawer fills the visible pane above the bottom inset")
            if (width >= 780) {
                assertEquals(controlsBeforeDrawer, onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot())
                assertEquals(artworkBeforeDrawer, onNodeWithTag("now-playing-artwork-slot").getUnclippedBoundsInRoot())
                assertEquals(queue.left, drawer.left)
                assertEquals(queue.right, drawer.right)
                queueButton.performClick()
                onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                queueButton.performClick()
                onAllNodesWithTag("now-playing-queue-drawer").assertCountEquals(1)
            } else {
                onNodeWithTag("now-playing-controller").assertDoesNotExist()
                assertEquals(14.dp, drawer.left)
                assertEquals((width - 14).dp, drawer.right)
            }
            back.assertIsDisplayed()
            assertEquals(backBounds, back.fetchSemanticsNode().boundsInRoot)
            back.performClick()
            onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
            queueButton.assertIsNotSelected()
            if (width < 780 || height < 400) lyrics.performScrollTo()
            lyrics.assertIsDisplayed()
            if (width >= 780) {
                val side = onNodeWithTag("now-playing-side-panel").getUnclippedBoundsInRoot()
                val expandedLyrics = lyrics.getUnclippedBoundsInRoot()
                assertEquals(side.bottom - side.top, expandedLyrics.bottom - expandedLyrics.top)
            }
            runOnIdle {
                assertEquals(listOf(NowPlayingDisplayAction.EnsureLyrics), display.map { it.action })
                assertTrue(playback.isEmpty())
                trackId.value = "changed-song"
            }
            onNodeWithTag("now-playing-queue-card").assertDoesNotExist()
            queueButton.performScrollTo().performClick()
            onNodeWithContentDescription("Pin queue").performClick()
            if (width < 780 || height < 400) onNodeWithTag("now-playing-queue-card").performScrollTo()
            onNodeWithText("Next song").performScrollTo().assertIsDisplayed().performClick()
            back.assertIsDisplayed()
            assertEquals(backBounds, back.fetchSemanticsNode().boundsInRoot)
            runOnIdle {
                assertEquals(listOf("next"), selections)
                assertEquals(listOf("song", "changed-song"), display.map { it.lyricsTrackId })
                assertTrue(playback.isEmpty())
            }
            for (queueShown in listOf(true, false)) {
                if (!queueShown) {
                    onNodeWithContentDescription("Unpin queue").performScrollTo().performClick()
                    back.performClick()
                }
                onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("now-playing-controller")))
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
                val viewport = onRoot().getUnclippedBoundsInRoot()
                val bottomCard = onNodeWithTag(if (queueShown) "now-playing-queue-card" else "now-playing-lyrics-card")
                    .getUnclippedBoundsInRoot()
                assertEquals(14.dp, viewport.bottom - bottomCard.bottom, "Bottom outer inset must match the side insets")
                if (width >= 780) {
                    val controls = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
                    assertEquals(controls.bottom, bottomCard.bottom, "Bottom padding must preserve card alignment")
                }
            }
        }
    }

    @Test fun unavailableLyricsAndStationsStillSupportQueueDrawerPinAndDismiss() = runDesktopComposeUiTest(1280, 720) {
        val live = mutableStateOf(false)
        val display = mutableListOf<NowPlayingDisplayAction>()
        setContent {
            NaviampNowPlayingPanel(
                NowPlayingUi(id = "unavailable", title = "Track or station", subtitle = "Artist", stateLabel = "Paused",
                    isLive = live.value, lyricsAvailable = false),
                colors = NaviampColors(),
                actions = NaviampNowPlayingActions({}, { display += it.action }, {}, {}, {}, {}, {}),
            )
        }
        for (isLive in listOf(false, true)) {
            runOnIdle { live.value = isLive }
            onNodeWithText("Lyrics unavailable").assertIsDisplayed()
            onNodeWithContentDescription("Queue").assertIsEnabled().performClick()
            onNodeWithTag("now-playing-queue-drawer").assertIsDisplayed()
            onNodeWithText("Lyrics unavailable").assertDoesNotExist()
            if (isLive) onNodeWithText("STATIONS").assertIsDisplayed()
            onNodeWithContentDescription("Pin queue").assertIsDisplayed().performClick()
            onNodeWithTag("now-playing-queue-card").assertIsDisplayed()
            onNodeWithText("Lyrics unavailable").assertIsDisplayed()
            onNodeWithContentDescription("Unpin queue").assertIsSelected().performClick()
            onNodeWithTag("now-playing-queue-drawer").assertIsDisplayed()
            onNodeWithTag("now-playing-queue-card").assertDoesNotExist()
            onNodeWithContentDescription("Close").assertDoesNotExist()
            onNodeWithTag("now-playing-back").performClick()
            onNodeWithText("Lyrics unavailable").assertIsDisplayed()
        }
        runOnIdle { assertTrue(display.isEmpty()) }
    }

    @Test fun outsidePointerDismissesOnlyDrawerWithoutActivatingCoveredControls() {
        for ((width, height, filled) in listOf(
            Triple(1280, 800, false), Triple(1000, 300, false),
            Triple(420, 800, false), Triple(420, 800, true),
        )) runDesktopComposeUiTest(width, height) {
            val playback = mutableListOf<NowPlayingPlaybackActionRequest>()
            val display = mutableListOf<NowPlayingDisplayAction>()
            val selections = mutableListOf<String>()
            setContent {
                Box(Modifier.fillMaxSize().padding(start = 23.dp, top = 17.dp, end = 11.dp, bottom = 9.dp)) {
                    NaviampNowPlayingPanel(
                        NowPlayingUi(id = "track", title = "Track", subtitle = "Artist", stateLabel = "Paused",
                            isPaused = true, positionSeconds = 30.0, durationSeconds = 180.0,
                            upNext = listOf(NaviampNowPlayingItemUi("next", "Next song", "Artist"))),
                        colors = NaviampColors(),
                        actions = NaviampNowPlayingActions({ playback += it }, { display += it.action }, {}, {}, {},
                            { selections += it.item.id }, {}),
                        modifier = Modifier.testTag("outside-click-player"),
                        displaySettings = app.naviamp.domain.settings.NowPlayingDisplaySettings(fillArtworkArea = filled),
                        contentInsets = WindowInsets(left = 5, top = 7, right = 3, bottom = 11),
                    )
                }
            }
            val queue = onNodeWithContentDescription("Queue")
            val drawer = onNodeWithTag("now-playing-queue-drawer")
            val player = onNodeWithTag("outside-click-player").fetchSemanticsNode().boundsInRoot
            fun open() {
                queue.performScrollTo().performClick()
                drawer.assertIsDisplayed()
            }
            open()
            if (width >= 780) queue.performSemanticsAction(SemanticsActions.OnClick) { it() }
            drawer.performMouseInput { click(Offset(right - 6f, center.y)) }
            drawer.assertIsDisplayed()
            onNodeWithText("Next song").performScrollTo().performMouseInput { click() }
            drawer.assertIsDisplayed()
            runOnIdle { assertEquals(listOf("next"), selections) }
            onRoot().performTouchInput { click(Offset(player.right - 2f, player.top + 20f)) }
            drawer.assertDoesNotExist()
            if (width >= 780) {
                open()
                onNodeWithContentDescription("Play").performScrollTo().performMouseInput { click() }
                drawer.assertDoesNotExist()
                open()
                onNodeWithContentDescription("Playback position").performScrollTo().performTouchInput { click() }
                drawer.assertDoesNotExist()
                open()
                queue.performMouseInput { click() }
                drawer.assertDoesNotExist()
            }
            open()
            onRoot().performMouseInput { click(Offset(player.right - 2f, player.center.y)) }
            drawer.assertDoesNotExist()
            runOnIdle { assertTrue(playback.isEmpty()); assertTrue(display.isEmpty()) }
            open()
            onNodeWithContentDescription("Pin queue").performClick()
            onNodeWithContentDescription("Play").performScrollTo().performMouseInput { click() }
            onNodeWithTag("now-playing-queue-card").assertExists()
            runOnIdle { assertEquals(listOf(NowPlayingPlaybackAction.Resume), playback.map { it.action }) }
            onNodeWithContentDescription("Playback position").performScrollTo().performTouchInput { click() }
            runOnIdle { assertEquals(NowPlayingPlaybackAction.Seek, playback.last().action) }
            onNodeWithTag("now-playing-queue-card").assertExists()
        }
    }

    @Test fun queueDrawerDismissalRestoresFocusWithoutClosingPlayer() {
        for (width in listOf(1280, 420)) runDesktopComposeUiTest(width, 800) {
            val dispatcher = NaviampSystemBackDispatcher()
            val popups = NaviampPopupRegistry()
            var shellBacks = 0
            val display = mutableListOf<NowPlayingDisplayAction>()
            lateinit var inputMode: InputModeManager
            setContent {
                inputMode = LocalInputModeManager.current
                CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher,
                    LocalNaviampPopupRegistry provides popups) {
                    NaviampSystemBackHandler(true) { shellBacks++ }
                    NaviampNowPlayingPanel(
                        NowPlayingUi(id = "track", title = "Track", subtitle = "Artist", stateLabel = "Paused"),
                        colors = NaviampColors(), actions = NaviampNowPlayingActions({}, { display += it.action }, {}, {}, {}, {}, {}),
                    )
                }
            }
            runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
            val queue = onNodeWithContentDescription("Queue")
            fun open() {
                queue.performScrollTo().performClick()
                onNodeWithContentDescription("Pin queue").assertIsFocused()
                runOnIdle { assertTrue(popups.blocksWindowEscape) }
            }
            fun closed() {
                onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                queue.assertIsFocused()
                runOnIdle { assertTrue(display.isEmpty()); assertEquals(0, shellBacks); assertFalse(popups.blocksWindowEscape) }
            }
            open()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            closed()
            open()
            runOnIdle { dispatcher.currentHandler!!.invoke() }
            closed()
            open()
            onNodeWithTag("now-playing-back").performClick()
            closed()
            open()
            onRoot().performMouseInput { click(Offset(width - 2f, center.y)) }
            closed()
            open()
            onNodeWithContentDescription("Pin queue").performClick()
            onNodeWithContentDescription("Unpin queue").assertIsDisplayed().assertIsFocused()
            onNodeWithContentDescription("Unpin queue").performClick()
            onNodeWithContentDescription("Pin queue").assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            closed()
            onNodeWithTag("now-playing-back").performClick()
            runOnIdle { assertEquals(listOf(NowPlayingDisplayAction.Collapse), display) }
        }
    }

    @Test fun queueTabAndScrollSurvivePinningAndReopeningDrawer() = runDesktopComposeUiTest(1280, 720) {
        val selections = mutableListOf<String>()
        setContent {
            NaviampNowPlayingPanel(
                NowPlayingUi(id = "track", title = "Track", subtitle = "Artist", stateLabel = "Paused",
                    backTo = List(50) { NaviampNowPlayingItemUi("history-$it", "History $it", "Artist") },
                    upNext = listOf(NaviampNowPlayingItemUi("next", "Next song", "Artist"))),
                colors = NaviampColors(),
                actions = NaviampNowPlayingActions({}, {}, {}, {}, {}, { selections += it.item.id }, {}),
            )
        }
        val queue = onNodeWithContentDescription("Queue")
        queue.performClick()
        onNodeWithText("BACK TO").performClick()
        onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("now-playing-queue-drawer"))).performScrollToIndex(20)
        onNodeWithText("History 20").assertIsDisplayed()
        onNodeWithContentDescription("Pin queue").performClick()
        onNodeWithText("History 20").assertIsDisplayed()
        onNodeWithText("History 0").assertDoesNotExist()
        onNodeWithText("Next song").assertDoesNotExist()
        queue.performClick()
        onNodeWithText("History 20").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(listOf("history-20"), selections) }
    }

    @Test fun shortCompactPlayerKeepsTransportBackAndQueueReachable() = checkShortPlayer(420)

    @Test fun shortWidePlayerKeepsTransportBackAndQueueReachable() = checkShortPlayer(1000)

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
        onAllNodesWithContentDescription("Back").assertCountEquals(1)
        val back = onNodeWithTag("now-playing-back").assertIsDisplayed()
        val backBounds = back.fetchSemanticsNode().boundsInRoot
        assertEquals(panel.top, backBounds.top, 0.5f)
        assertEquals(panel.left, backBounds.left, 0.5f)
        onNodeWithContentDescription("Collapse player").assertDoesNotExist()
        onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
        onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
        onNodeWithText("Current song").performScrollTo().assertIsDisplayed()
        onNodeWithContentDescription("Pause").performScrollTo().assertIsDisplayed().performClick()
        onNodeWithContentDescription("Queue").performScrollTo().performClick()
        onNodeWithContentDescription("Pin queue").performClick()
        onNodeWithTag("now-playing-queue-card").performScrollTo().assertIsDisplayed()
        if (width < 780) {
            val queue = onNodeWithTag("now-playing-queue-card").performScrollTo().assertIsDisplayed()
            val bounds = queue.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= panel.top && bounds.bottom <= panel.bottom)
        }
        onNodeWithText("Next song").performScrollTo().assertIsDisplayed().performClick()
        back.assertIsDisplayed()
        assertEquals(backBounds, back.fetchSemanticsNode().boundsInRoot)
        back.performClick()
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
