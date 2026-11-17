package app.naviamp.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
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
            onAllNodesWithTag("mini-player").assertCountEquals(1)
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
            onAllNodesWithTag("mini-player").assertCountEquals(1)
            onNodeWithContentDescription("Collapse player").assertExists()
            onNodeWithTag("connection-name").assertDoesNotExist()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(restoringConnection = true),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onAllNodesWithTag("mini-player").assertCountEquals(1)
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
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
    fun detailsAndPrimaryNavigationKeepPlaybackAndBackClosesOnlyDetails() = runDesktopComposeUiTest(1000, 740) {
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
            onNodeWithTag("mini-player-identity").performClick()
            onNodeWithContentDescription("Collapse player").assertIsDisplayed()
            onNodeWithTag("mini-player-identity").performClick()
            runOnIdle { assertTrue(core.state.value.shell.shellChrome.nowPlayingOpen) }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Library)
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            onNodeWithTag("mini-player-identity").performClick()
            runOnIdle { dispatcher.currentHandler?.invoke() }
            onNodeWithContentDescription("Collapse player").assertDoesNotExist()
            runOnIdle {
                assertEquals(SharedRoute.Library, core.state.value.shell.shellChrome.selectedRoute)
                assertEquals(provider.track.id.value, core.state.value.shell.nowPlaying?.id)
                assertEquals(false, core.state.value.shell.shellChrome.nowPlayingOpen)
            }
            onAllNodesWithTag("mini-player").assertCountEquals(1)
        } finally { scope.cancel() }
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
            onAllNodesWithTag("mini-player").assertCountEquals(1)
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
