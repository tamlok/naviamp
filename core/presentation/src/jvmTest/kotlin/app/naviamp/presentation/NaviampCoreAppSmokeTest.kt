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
import app.naviamp.domain.settings.AuroraTone
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
    fun artworkBackgroundBelongsOnlyToExpandedPlayerRegardlessOfLegacyStyle() = runDesktopComposeUiTest(1280, 720) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())
        val redArtwork = "naviamp-radio-tile://cover?label=BackgroundRed&from=CC2222&to=CC2222"
        val blueArtwork = "naviamp-radio-tile://cover?label=BackgroundBlue&from=2222CC&to=2222CC"
        val ui = mutableStateOf(NaviampAppShellUiState(
            connectionSettings = NaviampConnectionSettingsUi(connection = NaviampShellConnectionUi(connected = true)),
            general = NaviampGeneralSettingsUi(interfaceSettings = InterfaceSettings(singleColorHex = "#FFFFFF")),
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
            fun routeCorners(): Pair<Color, Color> {
                val pixels = onNodeWithTag("main-route-content").captureToImage().toPixelMap()
                return pixels[2, 2] to pixels[pixels.width - 3, pixels.height - 3]
            }
            fun railColor(): Color {
                val rail = onNodeWithTag("main-navigation-rail").captureToImage().toPixelMap()
                return rail[2, rail.height - 3]
            }
            fun miniPlayerColor(): Color = onNodeWithTag("mini-player").captureToImage().toPixelMap()[2, 2]
            fun assertSolidApp() {
                val pixels = onNodeWithTag("main-route-content").captureToImage().toPixelMap()
                assertEquals(NaviampColors.Dark.background, pixels[2, pixels.height - 3])
                assertEquals(NaviampColors.Dark.background, pixels[pixels.width - 3, pixels.height - 3])
            }
            for (legacyStyle in AppBackgroundStyle.entries) {
                runOnIdle { ui.value = ui.value.copy(general = ui.value.general.copy(
                    interfaceSettings = ui.value.general.interfaceSettings.copy(appBackgroundStyle = legacyStyle))) }
                assertSolidApp()
            }
            waitUntil(timeoutMillis = 5_000) { loadedPalette != fallback }
            val redPalette = loadedPalette
            val solidRail = railColor()
            val solidMiniPlayer = miniPlayerColor()
            runOnIdle { ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(nowPlayingOpen = true)) }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            val redCorners = routeCorners()
            assertTrue(redCorners.first != redCorners.second, "Expanded player has an artwork gradient")
            assertEquals(solidRail, railColor(), "Artwork must not tint navigation")
            onNodeWithTag("mini-player").assertDoesNotExist()
            runOnIdle { ui.value = ui.value.copy(nowPlaying = ui.value.nowPlaying!!.copy(coverArtUrl = blueArtwork, trackCoverArtUrl = blueArtwork, albumCoverArtUrl = blueArtwork)) }
            waitUntil(timeoutMillis = 5_000) { loadedPalette != redPalette && loadedPalette != fallback }
            mainClock.advanceTimeBy(500)
            waitForIdle()
            val blueCorners = routeCorners()
            assertTrue(blueCorners != redCorners, "Settled player background must respond to the loaded artwork")
            mainClock.advanceTimeBy(500)
            assertEquals(blueCorners, routeCorners(), "Compare settled colors, not an intermediate animation frame")
            assertEquals(solidRail, railColor())
            onNodeWithTag("mini-player").assertDoesNotExist()
            runOnIdle { ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(nowPlayingOpen = false)) }
            assertEquals(solidMiniPlayer, miniPlayerColor())
            assertSolidApp()
            runOnIdle { ui.value = ui.value.copy(nowPlaying = ui.value.nowPlaying!!.copy(coverArtUrl = redArtwork, trackCoverArtUrl = redArtwork, albumCoverArtUrl = redArtwork)) }
            assertSolidApp()
        } finally { scope.cancel() }
    }

    @Test
    fun playerGradientControlsPreserveTelevisionBackgroundPreferences() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = InterfaceSettings(appBackgroundStyle = AppBackgroundStyle.AlbumBlur,
            singleColorHex = "#FFFFFF", albumBlurRadiusDp = 36)
        val core = NaviampCore.create(scope, fakeCoreServices(), initialState = NaviampCoreInitialState(
            product = NaviampCoreState(shell = NaviampAppShellUiState(general = NaviampGeneralSettingsUi(interfaceSettings = settings))),
        ))
        try {
            setContent { NaviampCoreApp(core) }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Settings)
            onNodeWithText("Experience", substring = false).performClick()
            onNodeWithText("Player", substring = false).performScrollTo().performClick()
            onNodeWithText("Light", substring = false).performScrollTo().performClick()
            runOnIdle {
                assertEquals(settings.copy(auroraTone = AuroraTone.Light), core.state.value.shell.general.interfaceSettings)
            }
            onNodeWithText("Dark", substring = false).performScrollTo().performClick()
            runOnIdle {
                assertEquals(settings.copy(auroraTone = AuroraTone.DeepDark), core.state.value.shell.general.interfaceSettings)
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
    fun disconnectedMyKeepsEmptyAccountsAndCancelUsableWithoutDownloads() = runDesktopComposeUiTest(412, 818) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val core = NaviampCore.create(scope, fakeCoreServices())
        try {
            setContent { NaviampCoreApp(core) }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.My)
            onNodeWithTag("my-hub").assertIsDisplayed()
            onNodeWithTag("my-downloads").assertDoesNotExist()
            onNodeWithTag("my-favorite-artists").assertDoesNotExist()
            onNodeWithTag("my-settings").assertIsDisplayed()
            onNodeWithTag("my-accounts").performClick()
            onNodeWithTag("account-add").performClick()
            onNodeWithTag("connection-name").assertIsDisplayed()
            onNodeWithText("Cancel", substring = false).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(false, core.state.value.shell.connectionSettings.connection.editingConnection)
                assertEquals(true, core.state.value.shell.connectionSettings.accountSwitcher.visible)
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("account-chooser").fetchSemanticsNodes().size == 1 }
            onNodeWithTag("account-add").assertIsDisplayed()
            core.actions.shell.connectionActions.onCloseAccounts()
            onNodeWithTag("my-hub").assertIsDisplayed()
            onAllNodesWithTag("mini-player").assertCountEquals(1)
        } finally { scope.cancel() }
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
                            ?.boundsInRoot?.top ?: onRoot().fetchSemanticsNode().boundsInRoot.bottom
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
