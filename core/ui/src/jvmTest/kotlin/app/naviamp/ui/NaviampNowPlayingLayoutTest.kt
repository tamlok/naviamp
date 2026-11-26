package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampForestTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemePack
import app.naviamp.domain.playback.EqualizerSettings
import app.naviamp.domain.playback.PlaybackProfileTargetType
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampNowPlayingLayoutTest {
    @Test fun compactRatingStaysCenteredBetweenAudioFields() {
        for ((width, height, fontScale) in listOf(
            Triple(1000, 680, 1f), Triple(780, 680, 1.4f), Triple(320, 500, 1f),
        )) for (direction in LayoutDirection.entries) runDesktopComposeUiTest(width, height) {
            val playing = mutableStateOf(NowPlayingUi(
                id = "song", title = "Song", subtitle = "Artist", albumTitle = "Album", stateLabel = "Paused",
                isPaused = true, canPlayPause = true, canRate = true, audioInfo = "FLAC 44.1 / 16",
            ))
            val showAudio = mutableStateOf(true)
            val requests = mutableListOf<NowPlayingCurrentTrackUiActionRequest>()
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalLayoutDirection provides direction,
                ) {
                    NaviampNowPlayingPanel(
                        playing.value, colors = NaviampColors.Dark,
                        actions = NaviampNowPlayingActions({}, {}, {
                            requests += it
                            playing.value = playing.value.copy(userRating = it.rating)
                        }, {}, {}, {}, {}),
                        displaySettings = app.naviamp.domain.settings.NowPlayingDisplaySettings(showAudioInfo = showAudio.value),
                    )
                }
            }
            val label = "${width}x$height scale=$fontScale $direction"
            onNodeWithContentDescription("3 star rating").performScrollTo()
            val stars = (1..5).map {
                onNodeWithContentDescription("$it star rating").assertIsDisplayed()
                    .assertWidthIsEqualTo(28.dp).fetchSemanticsNode().boundsInRoot
            }
            val starsLeft = stars.minOf { it.left }
            val starsRight = stars.maxOf { it.right }
            val starsCenter = (starsLeft + starsRight) / 2
            val controller = onNodeWithTag("now-playing-controller").fetchSemanticsNode().boundsInRoot
            val play = onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
            val format = onNodeWithText("FLAC").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val quality = onNodeWithText("44.1 / 16").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            // Separate flanking labels prove this exercises the compact row, not the stacked layout.
            val (leftAudio, rightAudio) = if (direction == LayoutDirection.Ltr) format to quality else quality to format
            assertTrue(leftAudio.right <= starsLeft && rightAudio.left >= starsRight, "$label audio avoids stars")
            assertTrue(leftAudio.left >= controller.left && rightAudio.right <= controller.right, "$label audio fits card")
            assertEquals(stars[2].center.y, format.center.y, 1f, "$label format baseline")
            assertEquals(stars[2].center.y, quality.center.y, 1f, "$label quality baseline")
            assertEquals(controller.center.x, starsCenter, 1f, "$label rating centered in card")
            assertEquals(play.center.x, starsCenter, 1f, "$label rating aligned with Play")
            onNodeWithContentDescription("5 star rating").performMouseInput { click() }
            onNodeWithContentDescription("5 star rating").performMouseInput { click() }
            assertEquals(listOf(5, null), requests.map { it.rating }, "$label rating sets and clears")
            assertTrue(requests.all { it.action == NowPlayingCurrentTrackAction.SetRating })
            System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
                val image = java.io.File(directory, "rating-$width-$height-$direction.png")
                image.parentFile.mkdirs()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", image)
            }
            runOnIdle { showAudio.value = false }
            onNodeWithText("FLAC").assertDoesNotExist()
            onNodeWithText("44.1 / 16").assertDoesNotExist()
            assertEquals(controller.center.x,
                onNodeWithContentDescription("3 star rating").fetchSemanticsNode().boundsInRoot.center.x,
                1f, "$label rating remains centered without audio")
            runOnIdle { showAudio.value = true; playing.value = playing.value.copy(canRate = false) }
            (1..5).forEach { onNodeWithContentDescription("$it star rating").assertDoesNotExist() }
            onNodeWithText("FLAC").assertIsDisplayed()
            onNodeWithText("44.1 / 16").assertIsDisplayed()
        }
    }

    @Test fun stationFlameOpensDetailsWithoutMovingPlayerContent() {
        for ((width, height) in listOf(320 to 720, 1000 to 720)) runDesktopComposeUiTest(width, height) {
            val station = app.naviamp.domain.radio.GeneratedRadioStation("station-id", "A long station name that must not resize the player controls",
                app.naviamp.domain.radio.GeneratedRadioKind.Track)
            val playing = mutableStateOf(NowPlayingUi(
                id = "song", title = "Song", subtitle = "Artist", albumTitle = "Album", stateLabel = "Paused",
                isPaused = true, canPlayPause = true, canStartRadio = true, canRate = true,
                hasPrevious = true, hasNext = true, canChangeVolume = true, castAvailable = true,
                audioInfo = "FLAC 44.1 / 16", coverArtUrl = "naviamp-radio-tile://cover?label=ART&from=CC5511&to=1133AA",
            ))
            val requests = mutableListOf<GeneratedRadioActionRequest>()
            val playbackRequests = mutableListOf<NowPlayingPlaybackActionRequest>()
            val trackRequests = mutableListOf<NowPlayingCurrentTrackUiActionRequest>()
            setContent {
                NaviampNowPlayingPanel(playing.value, colors = NaviampColors.Dark,
                    actions = NaviampNowPlayingActions(playbackRequests::add, {}, trackRequests::add, {}, {}, {}, {},
                        onGeneratedRadioAction = requests::add))
            }
            onNodeWithTag("now-playing-bottom-actions").performScrollTo()
            val button = onNodeWithTag("now-playing-station-button")
            button.assertIsDisplayed().assertIsNotEnabled().assertIsNotSelected()
                .assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
            button.performMouseInput { click() }
            assertTrue(requests.isEmpty())
            val tags = listOf("now-playing-controller", "now-playing-transport-row", "now-playing-bottom-actions") +
                if (width >= 780) listOf("now-playing-artwork-slot") else emptyList()
            val before = tags.associateWith { onNodeWithTag(it).getUnclippedBoundsInRoot() }
            runOnIdle { playing.value = playing.value.copy(generatedStation = station,
                generatedStatus = app.naviamp.domain.radio.GeneratedRadioStatus.Active) }
            button.assertIsEnabled().assertIsSelected()
            val dj = onNodeWithContentDescription("DJs").getUnclippedBoundsInRoot()
            val flame = button.getUnclippedBoundsInRoot()
            assertEquals(dj.right, flame.left)
            assertEquals(dj.top, flame.top)
            button.performClick().performSemanticsAction(SemanticsActions.RequestFocus)
            onRoot().performKeyInput { pressKey(Key.Enter) }
            assertEquals(List(2) { GeneratedRadioActionRequest(GeneratedRadioAction.Open, station.id) }, requests)
            assertTrue(playbackRequests.isEmpty())
            assertTrue(trackRequests.isEmpty())
            tags.forEach { assertEquals(before.getValue(it), onNodeWithTag(it).getUnclippedBoundsInRoot(), "$width: $it") }
            System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
                val image = java.io.File(directory, "station-flame-$width.png")
                image.parentFile.mkdirs()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", image)
            }
            for (status in listOf(app.naviamp.domain.radio.GeneratedRadioStatus.Fetching,
                app.naviamp.domain.radio.GeneratedRadioStatus.Exhausted, app.naviamp.domain.radio.GeneratedRadioStatus.Failed)) {
                runOnIdle { playing.value = playing.value.copy(generatedStatus = status) }
                button.assertIsEnabled().performClick()
                assertEquals(GeneratedRadioActionRequest(GeneratedRadioAction.Open, station.id), requests.last())
            }
            runOnIdle { playing.value = playing.value.copy(generatedStatus = app.naviamp.domain.radio.GeneratedRadioStatus.Inactive) }
            button.assertIsNotEnabled().assertIsNotSelected()
            tags.forEach { assertEquals(before.getValue(it), onNodeWithTag(it).getUnclippedBoundsInRoot(), "$width inactive: $it") }
        }
    }

    @Test fun enlargedControlsFitAndVolumeSitsImmediatelyBeforeCast() {
        val cases = listOf(
            Triple(280, 500, true), Triple(320, 720, false), Triple(360, 640, false),
            Triple(420, 900, false), Triple(780, 720, false), Triple(1280, 720, false),
            Triple(1000, 240, false),
        )
        for ((width, height, filled) in cases) runDesktopComposeUiTest(width, height) {
            val playing = mutableStateOf(NowPlayingUi(
                id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                isPaused = true, canPlayPause = true, hasPrevious = true, hasNext = true,
                canFavorite = true, canRate = true, audioInfo = "MP3 320 kbps",
                volumePercent = 50, canChangeVolume = true, castAvailable = true,
            ))
            val playback = mutableListOf<NowPlayingPlaybackActionRequest>()
            var castClicks = 0
            setContent {
                NaviampNowPlayingPanel(
                    nowPlaying = playing.value, colors = NaviampColors.Dark,
                    actions = NaviampNowPlayingActions(
                        { playback += it }, {}, {}, {}, {}, {}, {}, onCastPicker = { castClicks++ },
                    ),
                    displaySettings = app.naviamp.domain.settings.NowPlayingDisplaySettings(fillArtworkArea = filled),
                )
            }
            val label = "${width}x$height filled=$filled"
            val bottom = onNodeWithTag("now-playing-bottom-actions").performScrollTo().getUnclippedBoundsInRoot()
            val speaker = onNodeWithTag("volume-popup-button")
            val cast = onNodeWithContentDescription("Cast to a device")
            val bottomNodes = listOf("Start track radio", "DJs", "Preview station", "Volume", "Cast to a device", "Queue", "Track actions")
                .map { onNodeWithContentDescription(it).assertIsDisplayed().assertWidthIsAtLeast(40.dp)
                    .assertHeightIsAtLeast(40.dp).getUnclippedBoundsInRoot() }
            for (bounds in bottomNodes) {
                assertTrue(bounds.left >= bottom.left && bounds.right <= bottom.right, "$label bottom control fits horizontally")
                assertTrue(bounds.top >= bottom.top && bounds.bottom <= bottom.bottom, "$label bottom control fits vertically")
            }
            bottomNodes.zipWithNext().forEach { (left, right) ->
                assertTrue(left.right <= right.left || left.bottom <= right.top,
                    "$label bottom controls must not overlap within or between rows")
            }
            val volumeBounds = speaker.getUnclippedBoundsInRoot()
            val castBounds = cast.getUnclippedBoundsInRoot()
            assertEquals(volumeBounds.right, castBounds.left, "$label volume immediately precedes Cast")
            assertEquals(volumeBounds.top + (volumeBounds.bottom - volumeBounds.top) / 2,
                castBounds.top + (castBounds.bottom - castBounds.top) / 2, "$label same baseline")
            val transport = listOf("Shuffle Up Next", "Previous", "Play", "Next", "Repeat off")
                .map { onNodeWithContentDescription(it).assertWidthIsAtLeast(40.dp)
                    .assertHeightIsAtLeast(40.dp).getUnclippedBoundsInRoot() }
            transport.zipWithNext().forEach { (left, right) ->
                assertTrue(left.right <= right.left, "$label transport controls must not overlap")
            }
            assertTrue(transport.first().left >= bottom.left && transport.last().right <= bottom.right,
                "$label transport fits within card insets")
            assertTrue(transport.maxOf { it.bottom } <= bottom.top, "$label transport stays above bottom actions")
            onNodeWithContentDescription("Play").assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
            val favoriteBounds = onNodeWithContentDescription("Favorite").getUnclippedBoundsInRoot()
            val nextBounds = onNodeWithContentDescription("Next").getUnclippedBoundsInRoot()
            assertEquals(nextBounds.right - nextBounds.left, favoriteBounds.right - favoriteBounds.left, "$label favorite matches Next width")
            assertEquals(nextBounds.bottom - nextBounds.top, favoriteBounds.bottom - favoriteBounds.top, "$label favorite matches Next height")
            onNodeWithContentDescription("5 star rating").assertWidthIsAtLeast(28.dp).assertHeightIsAtLeast(24.dp)
            System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
                val target = java.io.File(directory, "controls-$width-$height-$filled.png")
                target.parentFile.mkdirs()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
            }
            speaker.performMouseInput { click() }
            onNodeWithTag("now-playing-volume-slider").assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
                .performKeyInput { pressKey(Key.Escape) }
            cast.performMouseInput { click() }
            runOnIdle {
                assertEquals(1, castClicks, "$label Cast remains clickable beside volume")
                assertEquals(listOf(75), playback.map { it.volumePercent }, "$label speaker opens without muting")
                playing.value = playing.value.copy(castAvailable = false)
            }
            cast.assertDoesNotExist()
            assertEquals(speaker.getUnclippedBoundsInRoot().right,
                onNodeWithContentDescription("Queue").getUnclippedBoundsInRoot().left,
                "$label volume remains available without Cast")
        }
    }

    @Test fun artworkCardFavoriteMatchesNextAndDoesNotTriggerArtworkGestures() {
        for ((width, height, filled) in listOf(
            Triple(1280, 720, false), Triple(420, 900, false), Triple(320, 900, false),
            Triple(360, 640, false), Triple(320, 720, true), Triple(420, 900, true),
            Triple(280, 500, true), Triple(640, 240, true), Triple(280, 500, false),
        )) runDesktopComposeUiTest(width, height) {
            val playing = mutableStateOf(NowPlayingUi(
                id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                isPaused = true, canPlayPause = true, canFavorite = true, canRate = true,
                visualizerAvailable = true,
                coverArtUrl = "naviamp-radio-tile://cover?label=ART&from=CC5511&to=1133AA",
            ))
            val trackActions = mutableListOf<NowPlayingCurrentTrackAction>()
            val displayActions = mutableListOf<NowPlayingDisplayAction>()
            setContent {
                NaviampNowPlayingPanel(
                    playing.value, colors = NaviampColors.Dark,
                    actions = NaviampNowPlayingActions({}, { displayActions += it.action }, {
                        trackActions += it.action
                        if (it.action == NowPlayingCurrentTrackAction.ToggleFavorite) {
                            playing.value = playing.value.copy(favoriteActive = !playing.value.favoriteActive)
                        }
                    }, {}, {}, {}, {}),
                    displaySettings = app.naviamp.domain.settings.NowPlayingDisplaySettings(fillArtworkArea = filled),
                    contentInsets = WindowInsets(left = 4, top = 8, right = 4, bottom = 8),
                )
            }
            val label = "${width}x$height filled=$filled"
            val favorite = onNodeWithTag("now-playing-artwork-favorite")
            val nextBounds = onNodeWithContentDescription("Next").getUnclippedBoundsInRoot()
            val nextIconBounds = onNodeWithContentDescription("Next", useUnmergedTree = true).getUnclippedBoundsInRoot()
            favorite.performScrollTo().assertIsDisplayed().assertIsNotSelected()
            onAllNodesWithContentDescription("Favorite").assertCountEquals(1)
            favorite.assertWidthIsEqualTo(nextBounds.right - nextBounds.left)
                .assertHeightIsEqualTo(nextBounds.bottom - nextBounds.top)
            onNodeWithContentDescription("Favorite", useUnmergedTree = true)
                .assertWidthIsEqualTo(nextIconBounds.right - nextIconBounds.left)
                .assertHeightIsEqualTo(nextIconBounds.bottom - nextIconBounds.top)
            val bounds = favorite.getUnclippedBoundsInRoot()
            if (filled) {
                val art = onNodeWithTag("now-playing-filled-artwork").getUnclippedBoundsInRoot()
                val controls = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
                assertTrue(bounds.left >= art.left && bounds.right <= art.right, "$label inside artwork")
                assertTrue(bounds.bottom <= controls.top, "$label does not overlap controls")
            } else {
                val card = onNodeWithTag("now-playing-artwork-card").getUnclippedBoundsInRoot()
                val art = onNodeWithTag("now-playing-artwork").getUnclippedBoundsInRoot()
                val controls = onNodeWithTag("now-playing-controller").getUnclippedBoundsInRoot()
                assertEquals(card.left + 8.dp, bounds.left, "$label card left corner")
                assertEquals(card.bottom - 8.dp, bounds.bottom, "$label card bottom corner")
                assertEquals(controls.left, card.left, "$label card left aligns with controller")
                assertEquals(controls.right, card.right, "$label card right aligns with controller")
                if (width >= 780) {
                    val slot = onNodeWithTag("now-playing-artwork-slot").getUnclippedBoundsInRoot()
                    assertEquals(slot.top, card.top, "$label card uses full artwork area height")
                    assertEquals(slot.bottom, card.bottom, "$label card uses full artwork area height")
                    assertTrue(bounds.left < art.left, "$label favorite anchors to card, not centered image")
                } else if (height >= 900) {
                    assertEquals(controls.top - 8.dp, bounds.bottom, "$label favorite uses bottom of artwork area")
                }
            }
            favorite.performMouseInput { click() }
            favorite.assertIsSelected().assertContentDescriptionEquals("Unfavorite")
            favorite.performTouchInput { click() }
            favorite.assertIsNotSelected().assertContentDescriptionEquals("Favorite")
            runOnIdle {
                assertEquals(List(2) { NowPlayingCurrentTrackAction.ToggleFavorite }, trackActions)
                assertTrue(displayActions.isEmpty(), "$label heart must not toggle artwork")
            }
            System.getenv("NAVIAMP_ARTWORK_CAPTURE")?.let { directory ->
                val target = java.io.File(directory, "favorite-$width-$height-$filled.png")
                target.parentFile.mkdirs()
                javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
            }
            if (!filled) {
                val imageBefore = onNodeWithTag("now-playing-artwork").getUnclippedBoundsInRoot()
                val visualizerWidthBefore = onNodeWithTag(if (width >= 780) "now-playing-artwork-slot" else "now-playing-artwork-card")
                    .getUnclippedBoundsInRoot().let { it.right - it.left }
                runOnIdle { playing.value = playing.value.copy(visualizerVisible = true) }
                favorite.assertIsDisplayed()
                val visualizerBounds = onNodeWithTag("now-playing-visualizer").getUnclippedBoundsInRoot()
                assertEquals(visualizerWidthBefore, visualizerBounds.right - visualizerBounds.left, "$label visualizer retains width")
                assertEquals(imageBefore.bottom - imageBefore.top + (if (width >= 780) 0.dp else 24.dp),
                    visualizerBounds.bottom - visualizerBounds.top, "$label visualizer retains height")
                assertEquals(bounds, favorite.getUnclippedBoundsInRoot(), "$label stable visualizer anchor")
                favorite.performMouseInput { click(button = MouseButton.Secondary) }
                favorite.performTouchInput { swipeLeft() }
                favorite.performMouseInput { click() }
                runOnIdle { assertTrue(displayActions.isEmpty(), "$label no visualizer gesture leaks") }
                onAllNodesWithText(NaviampVisualizer.AudioSphere.label).assertCountEquals(0)
                onNodeWithTag("now-playing-visualizer").performTouchInput { click(center) }
                runOnIdle { assertEquals(listOf(NowPlayingDisplayAction.ToggleVisualizer), displayActions) }
            }
            runOnIdle { playing.value = playing.value.copy(canFavorite = false, favoriteActive = true) }
            favorite.assertDoesNotExist()
            onNodeWithContentDescription("5 star rating").assertExists()
            runOnIdle { playing.value = playing.value.copy(canFavorite = true, canRate = false, visualizerVisible = false) }
            favorite.performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("5 star rating").assertDoesNotExist()
            runOnIdle { playing.value = playing.value.copy(isLive = true) }
            favorite.assertDoesNotExist()
        }
    }

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

    @Test fun forestLightPlayerSurfacesUseSixtyFivePercentOpacity() = checkPlayerSurfaces(
        NaviampForestTheme, NaviampThemeMode.Light, expectedSurface = Color(0xFFE8F6EC).copy(alpha = 0.65f),
    )

    @Test fun forestDarkPlayerSurfacesUseSixtyFivePercentOpacity() = checkPlayerSurfaces(
        NaviampForestTheme, NaviampThemeMode.Dark, expectedSurface = Color(0xFF132B1B).copy(alpha = 0.65f),
    )

    @Test fun themedPlayerSurfacesCompositeOnceIncludingQueueRows() {
        for (mode in listOf(NaviampThemeMode.Light, NaviampThemeMode.Dark)) {
            for (percent in listOf(0, 37, 100)) {
                checkTranslucentPlayerSurfaces(mode, percent, width = 1280)
            }
        }
    }

    @Test fun narrowPlayerCardsShareTheThemeOpacity() {
        for (mode in listOf(NaviampThemeMode.Light, NaviampThemeMode.Dark)) {
            checkTranslucentPlayerSurfaces(mode, percent = 50, width = 420)
        }
    }

    private fun checkTranslucentPlayerSurfaces(mode: NaviampThemeMode, percent: Int, width: Int) {
        val pack = NaviampForestTheme.copy(
            id = "translucent-test",
            light = NaviampForestTheme.light.copy(playerSurface = "#B32A15", playerSurfaceOpacityPercent = percent),
            dark = NaviampForestTheme.dark.copy(playerSurface = "#B32A15", playerSurfaceOpacityPercent = percent),
        )
        checkPlayerSurfaces(pack, mode, Color(0xFFB32A15).copy(alpha = percent / 100f), width)
    }

    private fun checkPlayerSurfaces(
        pack: NaviampThemePack,
        mode: NaviampThemeMode,
        expectedSurface: Color?,
        width: Int = 1280,
    ) =
        runDesktopComposeUiTest(width, if (width < 780) 900 else 720) {
            val backdrop = mutableStateOf(Color(0xFF2359A1))
            val live = mutableStateOf(false)
            val colors = (if (mode == NaviampThemeMode.Dark) pack.dark else pack.light).toNaviampColors()
            fun assertOpaqueSwipeCover(expected: Color) {
                val row = onNode(hasText("Queued item") and hasClickAction()).performScrollTo()
                val bounds = row.fetchSemanticsNode().boundsInRoot
                row.performTouchInput {
                    down(center)
                    moveBy(Offset(40f, 0f))
                }
                val pixels = onNodeWithTag("player-surface-fixture").captureToImage().toPixelMap()
                assertEquals(expected, pixels[bounds.center.x.toInt(), (bounds.top + 2f).toInt()],
                    "Opaque row must cover the swipe-action background")
                assertNotEquals(expected, pixels[(bounds.left + 2f).toInt(), bounds.center.y.toInt()],
                    "The revealed swipe strip must still show the action")
                row.performTouchInput { up() }
            }
            setContent {
                NaviampTheme(NaviampUiTheme(colors, mode == NaviampThemeMode.Dark, pack, colors.toMaterialColorScheme())) {
                    Box(Modifier.testTag("player-surface-fixture").fillMaxSize().background(backdrop.value).padding(12.dp)) {
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
            val drawerPixels = onNodeWithTag("player-surface-fixture").captureToImage().toPixelMap()
            assertEquals(colors.controlSurface,
                drawerPixels[(drawerBounds.left + 2f).toInt(), drawerBounds.center.y.toInt()],
                "Drawer must cover the lyrics with an opaque surface")
            val drawerRow = onNode(hasText("Queued item") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertEquals(colors.controlSurface,
                drawerPixels[(drawerRow.left + 2f).toInt(), drawerRow.center.y.toInt()],
                "Unselected drawer rows must use the opaque drawer surface, not a second player fill")
            assertOpaqueSwipeCover(colors.controlSurface)
            onNodeWithContentDescription("Pin queue").performClick()
            for ((tab, title, subtitle) in listOf(
                Triple("UP NEXT", "Queued item", "Queued artist"),
                Triple("BACK TO", "History item", "History artist"),
                Triple("RELATED", "Related item", "Related artist"),
                Triple("STATIONS", "Other station", "Other broadcaster"),
            )) {
                if (tab == "STATIONS") runOnIdle { live.value = true }
                else onNodeWithText(tab).performScrollTo().performClick()
                for (backingColor in listOf(Color(0xFF2359A1), Color(0xFFAF763B))) {
                    runOnIdle { backdrop.value = backingColor }
                    waitUntil(timeoutMillis = 5_000) {
                        onNodeWithTag("player-surface-fixture").captureToImage().toPixelMap()[0, 0] == backingColor
                    }
                    fun assertSurface(node: SemanticsNodeInteraction, expected: Color, label: String) {
                        node.performScrollTo().assertIsDisplayed()
                        val pixels = onNodeWithTag("player-surface-fixture").captureToImage().toPixelMap()
                        val bounds = node.fetchSemanticsNode().boundsInRoot
                        val x = bounds.left + 2f
                        val y = bounds.center.y
                        val actual = pixels[x.toInt(), y.toInt()]
                        assertEquals(expected.red, actual.red, 1f / 255f, "$tab $label red")
                        assertEquals(expected.green, actual.green, 1f / 255f, "$tab $label green")
                        assertEquals(expected.blue, actual.blue, 1f / 255f, "$tab $label blue")
                        assertEquals(1f, actual.alpha, "$tab $label alpha")
                    }
                    val surface = expectedSurface?.compositeOver(backingColor) ?: backingColor
                    assertSurface(onNodeWithTag("now-playing-controller"), surface, "controller")
                    assertSurface(onNodeWithTag("now-playing-lyrics-card"), surface, "lyrics card")
                    assertSurface(onNodeWithTag("now-playing-queue-card"), surface, "queue card")
                    if (width >= 780) {
                        val lyricsBounds = onNodeWithTag("now-playing-lyrics-card").fetchSemanticsNode().boundsInRoot
                        val queueBounds = onNodeWithTag("now-playing-queue-card").fetchSemanticsNode().boundsInRoot
                        val pixels = onNodeWithTag("player-surface-fixture").captureToImage().toPixelMap()
                        assertEquals(backingColor, pixels[lyricsBounds.center.x.toInt(),
                            ((lyricsBounds.bottom + queueBounds.top) / 2).toInt()], "Cards must not share a painted wrapper")
                    }
                    assertSurface(onNode(hasText(title) and hasClickAction()), surface, "unselected row")
                    if (tab == "UP NEXT" && expectedSurface?.alpha == 1f) assertOpaqueSwipeCover(expectedSurface)
                    if (tab == "STATIONS") {
                        val selected = onNode(hasText("Selected station") and hasClickAction())
                            .performScrollTo().fetchSemanticsNode().boundsInRoot
                        val pixels = onNodeWithTag("player-surface-fixture").captureToImage().toPixelMap()
                        assertNotEquals(surface, pixels[(selected.left + 2f).toInt(), selected.center.y.toInt()],
                            "Selected station must remain distinguishable from an unselected row")
                    }
                    onNodeWithText("Current title", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    onNodeWithText("Current artist", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    for ((text, expected) in listOf(
                        title to colors.primaryText,
                        subtitle to colors.playerSecondaryText,
                    )) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithText(text, useUnmergedTree = true)
                            .performScrollTo()
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
            onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
            onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
            back.assertIsDisplayed()
            queueButton.performScrollTo().assertIsNotSelected().performClick()
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
                onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("now-playing-side-panel")))
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
        setContent {
            MaterialTheme(typography = rememberNaviampTypography()) {
                EqualizerCurvePanel(NaviampColors(), EqualizerSettings(), false) { _, _ -> }
            }
        }
        for (label in listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")) {
            val layouts = mutableListOf<TextLayoutResult>()
            onNodeWithText(label).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }, "$label must fit")
        }
    }
}
