package app.naviamp.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.NaviampThemeMode
import androidx.compose.ui.graphics.luminance
import app.naviamp.domain.settings.InterfaceSettings
import androidx.compose.ui.test.*
import app.naviamp.ui.*
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import app.naviamp.app.NaviampConnectionPhase
import app.naviamp.app.NaviampConnectionRuntimeState
import app.naviamp.ui.NaviampArtistAlbumCommand
import app.naviamp.ui.NaviampArtistMediaCommand
import app.naviamp.ui.NaviampMediaItemActionRequest
import app.naviamp.ui.NaviampMediaItemCommand
import app.naviamp.ui.NaviampPlaylistMediaCommand
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.SharedRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampCoreAppSmokeTest {
    @Test
    fun homeScrollViewportUsesSmallInsetsWithoutAWidthCapOrBottomGap() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 412 to 818, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val provider = FakeCoreMediaProvider()
                val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
                    connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
                ))
                try {
                    setContent { NaviampCoreApp(core) }
                    val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                    val footer = onNodeWithTag("main-mini-player").fetchSemanticsNode().boundsInRoot
                    val scroll = onNode(
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                            hasAnyAncestor(hasTestTag("main-route-content")),
                    ).fetchSemanticsNode().boundsInRoot
                    val inset = with(onRoot().fetchSemanticsNode().layoutInfo.density) { 8.dp.toPx() }
                    val title = onNode(hasText("Home") and hasAnyAncestor(hasTestTag("main-route-content")))
                        .fetchSemanticsNode().boundsInRoot
                    assertEquals(route.left + inset, scroll.left, 0.5f, "$width x $height left inset")
                    assertEquals(route.right - inset, scroll.right, 0.5f, "$width x $height right inset")
                    assertTrue(title.top >= route.top + inset, "$width x $height top inset")
                    assertEquals(footer.top, scroll.bottom, 0.5f, "$width x $height bottom gap")
                } finally { scope.cancel() }
            }
        }
    }

    @Test
    fun selectedBackgroundStyleReachesBothPlayersWithoutTintingNavigationOrBrowsing() = runDesktopComposeUiTest(1280, 720) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())
        val redArtwork = "naviamp-radio-tile://cover?label=BackgroundRed&from=CC2222&to=CC2222"
        val blueArtwork = "naviamp-radio-tile://cover?label=BackgroundBlue&from=2222CC&to=2222CC"
        val ui = mutableStateOf(NaviampAppShellUiState(
            connectionSettings = NaviampConnectionSettingsUi(connection = NaviampShellConnectionUi(connected = true)),
            general = NaviampGeneralSettingsUi(interfaceSettings = InterfaceSettings(
                appBackgroundStyle = AppBackgroundStyle.SingleColor, singleColorHex = "#224466")),
            nowPlaying = NowPlayingUi(id = "background-track", title = "Background track", subtitle = "Artist",
                stateLabel = "Paused", isPaused = true, coverArtUrl = redArtwork),
        ))
        val fallback = NaviampPlayerColors.fallback(NaviampColors.Dark)
        var loadedPalette = fallback
        try {
            setContent {
                val palette = rememberNaviampCoverArtPlayerColors(ui.value.nowPlaying?.coverArtUrl, NaviampColors.Dark)
                SideEffect { loadedPalette = palette }
                NaviampSharedAppShell(uiState = ui.value, actions = core.actions.shell, syncActions = core.actions.settingsSync)
            }
            fun miniPixel(): Color = onNodeWithTag("mini-player").captureToImage().toPixelMap()[2, 2]
            fun fullPixels(): Pair<Color, Color> {
                val pixels = onNodeWithTag("full-player-background").captureToImage().toPixelMap()
                return pixels[2, 2] to pixels[pixels.width - 3, pixels.height - 3]
            }
            fun assertSolidNavigation() {
                val pixels = onNodeWithTag("main-navigation-rail").captureToImage().toPixelMap()
                assertEquals(NaviampColors.Dark.controlSurface, pixels[2, pixels.height - 3])
            }
            fun assertSolidBrowsing() {
                val pixels = onNodeWithTag("main-route-content").captureToImage().toPixelMap()
                assertEquals(NaviampColors.Dark.background, pixels[2, pixels.height - 3])
                assertEquals(NaviampColors.Dark.background, pixels[pixels.width - 3, pixels.height - 3])
                assertSolidNavigation()
            }
            fun expand(expanded: Boolean) {
                runOnIdle { ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(nowPlayingOpen = expanded)) }
                mainClock.advanceTimeBy(500)
                waitForIdle()
            }
            fun artwork(url: String) {
                runOnIdle { ui.value = ui.value.copy(nowPlaying = ui.value.nowPlaying!!.copy(
                    coverArtUrl = url, trackCoverArtUrl = url, albumCoverArtUrl = url)) }
            }
            assertEquals(Color(0xFF224466), miniPixel())
            assertSolidBrowsing()
            expand(true)
            assertEquals(Color(0xFF224466) to Color(0xFF224466), fullPixels())
            runOnIdle { ui.value = ui.value.copy(general = ui.value.general.copy(
                interfaceSettings = ui.value.general.interfaceSettings.copy(singleColorHex = "#663322"))) }
            assertEquals(Color(0xFF663322) to Color(0xFF663322), fullPixels())
            expand(false)
            assertEquals(Color(0xFF663322), miniPixel())
            for (style in listOf(AppBackgroundStyle.Aurora, AppBackgroundStyle.AlbumBlur)) {
                artwork(redArtwork)
                runOnIdle { ui.value = ui.value.copy(general = ui.value.general.copy(
                    interfaceSettings = ui.value.general.interfaceSettings.copy(appBackgroundStyle = style))) }
                waitUntil(timeoutMillis = 5_000) { loadedPalette != fallback && loadedPalette.backgroundStart.red > loadedPalette.backgroundStart.blue }
                mainClock.advanceTimeBy(500)
                waitForIdle()
                val redMini = miniPixel()
                assertSolidBrowsing()
                expand(true)
                val redFull = fullPixels()
                assertSolidNavigation()
                onNodeWithTag("mini-player").assertDoesNotExist()
                artwork(blueArtwork)
                waitUntil(timeoutMillis = 5_000) { loadedPalette.backgroundStart.blue > loadedPalette.backgroundStart.red }
                mainClock.advanceTimeBy(500)
                waitForIdle()
                assertTrue(fullPixels() != redFull, "$style full-player background must follow artwork")
                assertSolidNavigation()
                expand(false)
                assertTrue(miniPixel() != redMini, "$style player-bar background must follow artwork")
                assertSolidBrowsing()
                val darkMini = miniPixel()
                expand(true)
                val darkFull = fullPixels()
                runOnIdle { ui.value = ui.value.copy(general = ui.value.general.copy(
                    interfaceSettings = ui.value.general.interfaceSettings.copy(
                        theme = ui.value.general.interfaceSettings.theme.copy(mode = NaviampThemeMode.Light)))) }
                mainClock.advanceTimeBy(500)
                waitForIdle()
                val lightFull = fullPixels()
                assertTrue(lightFull.first.luminance() > darkFull.first.luminance() + 0.05f,
                    "$style expanded background must lighten without changing the track")
                expand(false)
                assertTrue(miniPixel().luminance() > darkMini.luminance() + 0.05f,
                    "$style mini background must lighten without changing the track")
                runOnIdle { ui.value = ui.value.copy(general = ui.value.general.copy(
                    interfaceSettings = ui.value.general.interfaceSettings.copy(
                        theme = ui.value.general.interfaceSettings.theme.copy(mode = NaviampThemeMode.Dark)))) }
                mainClock.advanceTimeBy(500)
                waitForIdle()
                assertEquals(darkMini.luminance(), miniPixel().luminance(), 0.01f)
                assertSolidBrowsing()
            }
        } finally { scope.cancel() }
    }

    @Test
    fun albumBlurCannotBleedAboveOrBelowThePlayerBar() = runDesktopComposeUiTest(412, 818) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())
        val firstArt = "naviamp-radio-tile://cover?label=ClipRed&from=FF1111&to=FF1111"
        val secondArt = "naviamp-radio-tile://cover?label=ClipBlue&from=1111FF&to=1111FF"
        val ui = mutableStateOf(NaviampAppShellUiState(
            connectionSettings = NaviampConnectionSettingsUi(connection = NaviampShellConnectionUi(connected = true)),
            general = NaviampGeneralSettingsUi(interfaceSettings = InterfaceSettings(
                appBackgroundStyle = AppBackgroundStyle.AlbumBlur,
                albumBlurRadiusDp = app.naviamp.domain.settings.MaxAlbumBlurRadiusDp)),
            nowPlaying = NowPlayingUi(id = "clip-track", title = "Clip track", subtitle = "Artist",
                stateLabel = "Paused", isPaused = true, coverArtUrl = firstArt),
        ))
        try {
            setContent {
                NaviampSharedAppShell(uiState = ui.value, actions = core.actions.shell, syncActions = core.actions.settingsSync)
            }
            var priorMini: Color? = null
            for (art in listOf(firstArt, secondArt)) {
                runOnIdle { ui.value = ui.value.copy(nowPlaying = ui.value.nowPlaying!!.copy(
                    coverArtUrl = art, trackCoverArtUrl = art, albumCoverArtUrl = art)) }
                waitUntil(timeoutMillis = 5_000) {
                    val pixel = onNodeWithTag("mini-player").captureToImage().toPixelMap()[2, 2]
                    if (art == firstArt) pixel.red > pixel.blue + 0.15f
                    else pixel.blue > pixel.red + 0.15f
                }
                mainClock.advanceTimeBy(500)
                waitForIdle()
                val player = onNodeWithTag("main-mini-player").fetchSemanticsNode().boundsInRoot
                val navigation = onNodeWithTag("main-navigation-bottom").fetchSemanticsNode().boundsInRoot
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("main-route-content")))
                    .captureToImage().toPixelMap()
                val x = image.width - 3
                assertEquals(NaviampColors.Dark.background, image[x, player.top.toInt() - 2], "Blur leaked into route")
                assertEquals(NaviampColors.Dark.controlSurface, image[x, navigation.top.toInt() + 2], "Blur leaked into navigation")
                val mini = image[2, player.top.toInt() + 2]
                priorMini?.let { assertTrue(it != mini, "The clipped bar must still render the changed artwork") }
                priorMini = mini
            }
        } finally { scope.cancel() }
    }

    @Test
    fun appearancePlayerBackgroundControlsApplyInlineWithoutResettingOtherStyles() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = InterfaceSettings(appBackgroundStyle = AppBackgroundStyle.AlbumBlur,
            singleColorHex = "#224466", albumBlurRadiusDp = 36)
        val provider = FakeCoreMediaProvider()
        val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
            connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
            product = NaviampCoreState(shell = NaviampAppShellUiState(general = NaviampGeneralSettingsUi(interfaceSettings = settings))),
        ))
        try {
            setContent { NaviampCoreApp(core) }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Settings)
            onNodeWithText("Appearance", substring = false).performClick()
            onNodeWithTag("player-background-style:Aurora").performScrollTo().performClick()
            for (tone in listOf("Light", "Dark", "DeepDark")) {
                onNodeWithTag("player-background-tone:$tone").assertDoesNotExist()
            }
            onNodeWithTag("player-background-color-steps").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(5f) }
            onNodeWithTag("player-background-angle").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(135f) }
            onNodeWithTag("player-background-style:SingleColor").performScrollTo().performClick()
            onNodeWithTag("player-background-color:#166534").performScrollTo().performClick()
            onNodeWithTag("player-background-style:AlbumBlur").performScrollTo().performClick()
            onNodeWithTag("player-background-blur").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(42f) }
            runOnIdle {
                assertEquals(settings.copy(auroraColorSteps = 5,
                    auroraAngleDegrees = 135, singleColorHex = "#166534", albumBlurRadiusDp = 42),
                    core.state.value.shell.general.interfaceSettings)
            }
            onNodeWithTag("player-background-style:Aurora").performScrollTo().performClick()
            onNodeWithTag("player-background-settings").assertExists()
            runOnIdle {
                val current = core.state.value.shell.general.interfaceSettings
                assertEquals(AppBackgroundStyle.Aurora, current.appBackgroundStyle)
                assertEquals("#166534", current.singleColorHex)
                assertEquals(42, current.albumBlurRadiusDp)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun connectionEditingUsesOneNavigationBarWhileThePlayerRemainsSelected() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())
        val ui = mutableStateOf(app.naviamp.ui.NaviampAppShellUiState(
            connectionSettings = app.naviamp.ui.NaviampConnectionSettingsUi(
                connection = app.naviamp.ui.NaviampShellConnectionUi(connected = true),
            ),
            shellChrome = app.naviamp.ui.NaviampShellChromeUi(
                selectedRoute = SharedRoute.Settings, nowPlayingOpen = true,
            ),
            nowPlaying = app.naviamp.ui.NowPlayingUi(id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused"),
        ))
        try {
            setContent {
                app.naviamp.ui.NaviampSharedAppShell(
                    uiState = ui.value, actions = core.actions.shell, syncActions = core.actions.settingsSync,
                )
            }
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onNodeWithTag("mini-player").assertDoesNotExist()
            onNodeWithContentDescription("Collapse player").assertExists()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(editingConnection = true, editingSavedConnection = true),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onAllNodesWithTag("mini-player").assertCountEquals(1)
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            onNodeWithTag("connection-name").assertExists()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(editingConnection = false),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onNodeWithTag("mini-player").assertDoesNotExist()
            onNodeWithContentDescription("Collapse player").assertExists()
            onNodeWithTag("connection-name").assertDoesNotExist()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(restoringConnection = true),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onAllNodesWithTag("mini-player").assertCountEquals(1)
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            ui.value = ui.value.copy(
                connectionSettings = ui.value.connectionSettings.copy(connection = ui.value.connectionSettings.connection.copy(
                    connected = false, restoringConnection = false,
                )),
                shellChrome = ui.value.shellChrome.copy(selectedRoute = SharedRoute.Home),
                connect = ui.value.connect.copy(remoteNowPlaying = ui.value.nowPlaying),
            )
            onNodeWithTag("connection-name").assertIsDisplayed()
            onNodeWithTag("mini-player").assertIsDisplayed()
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(selectedRoute = SharedRoute.Settings))
            onNodeWithTag("mini-player").assertDoesNotExist()
            onNodeWithContentDescription("Collapse player").assertExists()
        } finally { scope.cancel() }
    }

    @Test
    fun myLinksUseCoreNavigationAndReturnThroughTheSharedBackDispatcher() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = FakeCoreMediaProvider()
        val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
            connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
            product = NaviampCoreState(shell = NaviampAppShellUiState(shellChrome = NaviampShellChromeUi(supportsDownloads = true))),
        ))
        val dispatcher = NaviampSystemBackDispatcher()
        try {
            setContent { CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) { NaviampCoreApp(core) } }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.My)
            onNodeWithTag("my-settings").performClick()
            runOnIdle { assertEquals(SharedRoute.Settings, core.state.value.shell.shellChrome.selectedRoute) }
            onNodeWithText("Source", substring = false).performClick()
            runOnIdle { dispatcher.currentHandler?.invoke() }
            runOnIdle { assertEquals(SharedRoute.Settings, core.state.value.shell.shellChrome.selectedRoute) }
            runOnIdle { dispatcher.currentHandler?.invoke() }
            onNodeWithTag("my-hub").assertIsDisplayed()
            onNodeWithTag("my-downloads").performClick()
            runOnIdle { assertEquals(SharedRoute.Downloads, core.state.value.shell.shellChrome.selectedRoute); dispatcher.currentHandler?.invoke() }
            onNodeWithTag("my-hub").assertIsDisplayed()
            onNodeWithTag("my-accounts").performClick()
            onNodeWithTag("account-chooser").assertIsDisplayed()
            onNodeWithTag("account-add").performClick()
            onNodeWithTag("connection-name").assertIsDisplayed()
            onNodeWithText("Cancel", substring = false).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(false, core.state.value.shell.connectionSettings.connection.editingConnection)
                assertEquals(true, core.state.value.shell.connectionSettings.accountSwitcher.visible)
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("account-chooser").fetchSemanticsNodes().size == 1 }
            onNodeWithTag("account-chooser").assertIsDisplayed()
            core.actions.shell.connectionActions.onCloseAccounts()
            onNodeWithTag("my-hub").assertIsDisplayed()
        } finally { scope.cancel() }
    }

    @Test
    fun firstConnectionUsesTheWholeViewportAndSavedProfilesRestoreChrome() {
        for ((width, height) in listOf(1000 to 740, 412 to 818, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val core = NaviampCore.create(scope, fakeCoreServices())
                val ui = mutableStateOf(NaviampAppShellUiState())
                fun assertSetupOnly() {
                    onNodeWithTag("connection-name").performScrollTo().assertIsDisplayed()
                    for (tag in listOf("main-navigation-rail", "main-navigation-bottom", "main-navigation-open",
                        "main-navigation-drawer", "main-mini-player", "mini-player", "my-hub")) {
                        onNodeWithTag(tag).assertDoesNotExist()
                    }
                    onNodeWithText("Cancel", substring = false).assertDoesNotExist()
                    val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                    assertEquals(0f, route.left, 0.5f)
                    assertEquals(onRoot().fetchSemanticsNode().boundsInRoot.width, route.width, 0.5f)
                }
                fun assertChrome() {
                    onNodeWithTag("main-mini-player").assertIsDisplayed()
                    onNodeWithTag(when {
                        width < 360 -> "main-navigation-open"
                        width > height -> "main-navigation-rail"
                        else -> "main-navigation-bottom"
                    }).assertIsDisplayed()
                }
                try {
                    setContent { NaviampSharedAppShell(uiState = ui.value, actions = core.actions.shell,
                        syncActions = core.actions.settingsSync) }
                    for (route in listOf(SharedRoute.Home, SharedRoute.My, SharedRoute.Settings)) {
                        runOnIdle { ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(selectedRoute = route)) }
                        assertSetupOnly()
                    }
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(isConnecting = true))) }
                    assertSetupOnly()
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(isConnecting = false,
                            status = "Fixture connection error", statusIsError = true))) }
                    assertSetupOnly()
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(savedConnections = listOf(
                            NaviampSavedConnectionUi("saved", "Saved", "https://example.invalid", "listener"))))) }
                    assertChrome()
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(savedConnections = emptyList(), hasSavedConnection = true))) }
                    assertChrome()
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(hasSavedConnection = false))) }
                    assertSetupOnly()
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(connected = true))) }
                    assertChrome()
                } finally { scope.cancel() }
            }
        }
    }

    @Test
    fun detailsHideTheMiniPlayerAndEveryExitRestoresItWithoutStoppingPlayback() {
        for ((width, height) in listOf(1000 to 740, 412 to 818, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val provider = FakeCoreMediaProvider()
                val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
                    connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
                    playback = app.naviamp.app.NaviampLivePlaybackState(currentTrack = provider.track,
                        queue = app.naviamp.domain.queue.PlaybackQueue(listOf(provider.track), currentIndex = 0)),
                ))
                val dispatcher = NaviampSystemBackDispatcher()
                try {
                    setContent { CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) { NaviampCoreApp(core) } }
                    fun assertExpandedViewport() {
                        onNodeWithTag("mini-player").assertDoesNotExist()
                        val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                        val bottom = onAllNodesWithTag("main-navigation-bottom").fetchSemanticsNodes().singleOrNull()
                            ?.boundsInRoot?.top ?: onNode(isRoot() and hasAnyDescendant(hasTestTag("main-route-content")))
                                .fetchSemanticsNode().boundsInRoot.bottom
                        assertEquals(bottom, route.bottom, 0.5f, "Expanded player must reclaim the mini-player space")
                    }
                    onNodeWithTag("mini-player-identity").performClick()
                    assertExpandedViewport()
                    onNodeWithContentDescription("Collapse player").performScrollTo().assertIsDisplayed().performClick()
                    onNodeWithTag("mini-player").assertIsDisplayed()
                    onNodeWithTag("mini-player-identity").performClick()
                    assertExpandedViewport()
                    if (width < 360) onNodeWithTag("main-navigation-open").performClick()
                    onNodeWithTag("main-destination-Library").performClick()
                    onNodeWithContentDescription("Collapse player").assertDoesNotExist()
                    onNodeWithTag("mini-player").assertIsDisplayed()
                    onNodeWithTag("mini-player-identity").performClick()
                    assertExpandedViewport()
                    runOnIdle { dispatcher.currentHandler?.invoke() }
                    onNodeWithContentDescription("Collapse player").assertDoesNotExist()
                    onNodeWithTag("mini-player").assertIsDisplayed()
                    runOnIdle {
                        assertEquals(SharedRoute.Library, core.state.value.shell.shellChrome.selectedRoute)
                        assertEquals(provider.track.id.value, core.state.value.shell.nowPlaying?.id)
                        assertEquals(false, core.state.value.shell.shellChrome.nowPlayingOpen)
                    }
                } finally { scope.cancel() }
            }
        }
    }

    @Test
    fun myFavoritesOpensTheExistingHomeCollectionAndBackReturnsHome() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = FakeCoreMediaProvider()
        val section = SharedHomeCollectionSectionUi("favorites", "Favorite artists", emptyList(),
            titleResource = SharedHomeCollectionTitleResource.FavoriteArtists)
        val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
            connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
            product = NaviampCoreState(shell = NaviampAppShellUiState(home = NaviampHomeScreenUi(
                content = SharedHomeUi(collectionSections = listOf(section))))),
        ))
        val dispatcher = NaviampSystemBackDispatcher()
        try {
            setContent { CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) { NaviampCoreApp(core) } }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.My)
            onNodeWithTag("my-favorite-artists").performClick()
            runOnIdle {
                assertEquals(SharedRoute.Home, core.state.value.shell.shellChrome.selectedRoute)
                assertEquals(section, core.state.value.shell.home.collectionPage?.section)
                dispatcher.currentHandler?.invoke()
            }
            runOnIdle {
                assertEquals(SharedRoute.Home, core.state.value.shell.shellChrome.selectedRoute)
                assertEquals(null, core.state.value.shell.home.collectionPage)
            }
            onNodeWithTag("my-hub").assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test
    fun remotePlayerRemainsAvailableWithoutALocalProvider() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())
        val remote = NowPlayingUi(id = "remote-track", title = "Remote song", subtitle = "Remote artist", stateLabel = "Paused", isPaused = true)
        try {
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Settings)
            setContent {
                val shell = core.state.collectAsState().value.shell
                NaviampSharedAppShell(uiState = shell.copy(connect = shell.connect.copy(remoteNowPlaying = remote)),
                    actions = core.actions.shell, syncActions = core.actions.settingsSync)
            }
            onNodeWithTag("mini-player-identity").performClick()
            onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            onNodeWithTag("mini-player").assertDoesNotExist()
            runOnIdle { assertTrue(core.state.value.shell.shellChrome.nowPlayingOpen) }
            core.actions.shell.navigationActions.onCloseNowPlaying()
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            onNodeWithTag("mini-player-identity").assertHasClickAction()
        } finally { scope.cancel() }
    }

    @Test
    fun fakeHostMountsAndNavigatesEveryProductRoute() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())

        try {
            setContent { NaviampCoreApp(core) }
            waitForIdle()

            SharedRoute.entries.forEach { route ->
                core.actions.shell.navigationActions.onRouteSelected(route)
                waitForIdle()
                assertEquals(route, core.state.value.shell.shellChrome.selectedRoute)
            }

            core.actions.shell.navigationActions.onOpenNowPlaying()
            waitForIdle()
            assertEquals(true, core.state.value.shell.shellChrome.nowPlayingOpen)

            core.actions.shell.navigationActions.onCloseNowPlaying()
            waitForIdle()
            assertEquals(false, core.state.value.shell.shellChrome.nowPlayingOpen)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fakeHostLoadsAndRendersAlbumArtistAndPlaylistDetails() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val provider = FakeCoreMediaProvider()
        val core = NaviampCore.create(
            scope = scope,
            services = fakeCoreServices(provider),
            initialState = NaviampCoreInitialState(
                connection = NaviampConnectionRuntimeState(
                    phase = NaviampConnectionPhase.Connected,
                    sourceId = provider.id.value,
                    status = "Connected.",
                ),
            ),
        )

        try {
            setContent { NaviampCoreApp(core) }
            waitForIdle()

            core.actions.shell.mediaActions.onMediaItemAction(
                NaviampMediaItemActionRequest(
                    item = SharedMediaItemUi(provider.album.id.value, provider.album.title, provider.artist.name),
                    command = NaviampMediaItemCommand.Album(NaviampArtistAlbumCommand.Select),
                ),
            )
            waitForIdle()
            assertNotNull(core.state.value.shell.albumDetail.detail)
            assertTrue(onAllNodesWithText(provider.album.title).fetchSemanticsNodes().isNotEmpty())

            core.actions.shell.mediaActions.onMediaItemAction(
                NaviampMediaItemActionRequest(
                    item = SharedMediaItemUi(provider.artist.id.value, provider.artist.name, ""),
                    command = NaviampMediaItemCommand.Artist(NaviampArtistMediaCommand.Select),
                ),
            )
            waitForIdle()
            assertNotNull(core.state.value.shell.artistDetail.detail)
            assertTrue(onAllNodesWithText(provider.artist.name).fetchSemanticsNodes().isNotEmpty())

            core.actions.shell.mediaActions.onMediaItemAction(
                NaviampMediaItemActionRequest(
                    item = SharedMediaItemUi(
                        id = provider.playlist.id,
                        title = provider.playlist.name,
                        subtitle = "1 track",
                        trackCount = provider.playlist.trackCount,
                    ),
                    command = NaviampMediaItemCommand.Playlist(NaviampPlaylistMediaCommand.Select),
                ),
            )
            waitForIdle()
            assertNotNull(core.state.value.shell.playlistDetail.detail)
            assertTrue(onAllNodesWithText(provider.playlist.name).fetchSemanticsNodes().isNotEmpty())
        } finally {
            scope.cancel()
        }
    }
}
