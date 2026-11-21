package app.naviamp.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.naviamp.domain.provider.*
import app.naviamp.ui.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampCoreBackInputTest {
    @Test fun mouseAndPhoneDispatcherUseTheSameVisibleAurralBackCommand() = runDesktopComposeUiTest(1100, 850) {
        val commands = mutableListOf<NaviampCoreCommand>()
        val handler = object : NaviampCoreCommandHandler {
            override fun dispatch(command: NaviampCoreCommand) { commands += command }
            override suspend fun execute(command: NaviampCoreCommand) = NaviampCoreCommandResult.Completed
        }
        val actions = createNaviampCoreActions(handler)
        val dispatcher = NaviampSystemBackDispatcher()
        val state = NaviampAppShellUiState(connectionSettings = NaviampConnectionSettingsUi(
            connection = NaviampShellConnectionUi(connected = true)), home = NaviampHomeScreenUi(
            providerDiscovery = SharedProviderDiscoveryUi(ProviderIdAurral, browser = DiscoveryBrowserState(page = DiscoveryBrowsePage.Hub))))
        setContent { CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) {
            NaviampSharedAppShell(uiState = state, actions = actions.shell, syncActions = actions.settingsSync)
        } }
        onNodeWithText("Back").assertIsDisplayed()
        runOnIdle { commands.clear() } // Initial viewport reports are not navigation input.
        onRoot().performMouseInput { moveTo(center); click(button = MouseButton(3)) }
        assertEquals(1, commands.size, commands.toString())
        runOnIdle { assertNotNull(dispatcher.currentHandler).invoke() }
        assertEquals(2, commands.size, commands.toString())
        assertEquals(commands[0], commands[1])
        val browse = commands.first() as NaviampCoreCommand.Home.Browse
        assertEquals(DiscoveryBrowseAction.Close, (browse.action as DiscoveryBrowseAction.Scoped).action)
    }

    @Test fun renderedDetailsBackBeforeRetainedHomePagesWithEachShellsOwnOrdering() {
        listOf(false, true).forEach { television ->
            runDesktopComposeUiTest(1280, 850) {
                val commands = mutableListOf<NaviampCoreCommand>()
                val dispatcher = NaviampSystemBackDispatcher()
                var state by mutableStateOf(connectedShell().copy(
                    shellChrome = NaviampShellChromeUi(canNavigateBack = true),
                    albumDetail = NaviampAlbumDetailScreenUi(selectedAlbum = SharedMediaItemUi("album", "Visible album", "")),
                    artistDetail = NaviampArtistDetailScreenUi(selectedArtist = SharedMediaItemUi("artist", "Visible artist", "")),
                    playlistDetail = NaviampPlaylistDetailScreenUi(selectedPlaylist = SharedMediaItemUi("playlist", "Visible playlist", "")),
                    home = NaviampHomeScreenUi(
                        collectionPage = SharedHomeCollectionPageUi(SharedHomeCollectionSectionUi("collection", "Retained collection", emptyList())),
                        providerDiscovery = SharedProviderDiscoveryUi(ProviderIdAurral, requestsOpen = true,
                            browser = DiscoveryBrowserState(page = DiscoveryBrowsePage.Hub)),
                    ),
                ))
                val actions = recordingActions(commands) { command ->
                    state = when (command) {
                        NaviampCoreCommand.Navigation.Back -> when {
                            state.albumDetail.selectedAlbum != null -> state.copy(albumDetail = NaviampAlbumDetailScreenUi())
                            state.artistDetail.selectedArtist != null -> state.copy(artistDetail = NaviampArtistDetailScreenUi())
                            else -> state.copy(playlistDetail = NaviampPlaylistDetailScreenUi())
                        }
                        NaviampCoreCommand.Home.CloseCollection -> state.copy(home = state.home.copy(collectionPage = null))
                        NaviampCoreCommand.Home.DismissDiscoveryRequests -> state.copy(home = state.home.copy(
                            providerDiscovery = state.home.providerDiscovery?.copy(requestsOpen = false)))
                        else -> state
                    }
                }
                setContent {
                    CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) {
                        if (television) NaviampTelevisionAppShell(uiState = state, actions = actions.shell, syncActions = actions.settingsSync)
                        else NaviampSharedAppShell(uiState = state, actions = actions.shell, syncActions = actions.settingsSync)
                    }
                }
                listOf("Visible album", "Visible artist", "Visible playlist").forEachIndexed { index, title ->
                    onAllNodesWithText(title).onFirst().assertIsDisplayed()
                    onNodeWithText("Retained collection").assertDoesNotExist()
                    onNodeWithTag("page-back").assertDoesNotExist()
                    runOnIdle { commands.clear() }
                    if (index == 1) runOnIdle { assertNotNull(dispatcher.currentHandler).invoke() }
                    else onRoot().performMouseInput { moveTo(center); click(button = MouseButton(3)) }
                    runOnIdle { assertEquals(listOf<NaviampCoreCommand>(NaviampCoreCommand.Navigation.Back), commands) }
                }
                if (television) {
                    onNodeWithText("Retained collection").assertIsDisplayed()
                    runOnIdle { commands.clear(); assertNotNull(dispatcher.currentHandler).invoke() }
                    runOnIdle { assertEquals(listOf<NaviampCoreCommand>(NaviampCoreCommand.Home.CloseCollection), commands) }
                } else {
                    onNodeWithText("Retained collection").assertDoesNotExist()
                }
                onNodeWithText("Back").assertIsDisplayed()
                runOnIdle { commands.clear(); assertNotNull(dispatcher.currentHandler).invoke() }
                onNodeWithText("Recommendations").assertIsDisplayed()
            }
        }
    }

    @Test fun mouseBackClosesNavigationDrawerBeforePoppingSearch() = runDesktopComposeUiTest(320, 640) {
        val commands = mutableListOf<NaviampCoreCommand>()
        val actions = recordingActions(commands)
        val state = connectedShell().copy(shellChrome = NaviampShellChromeUi(
            selectedRoute = SharedRoute.Search, canNavigateBack = true))
        setContent { NaviampSharedAppShell(uiState = state, actions = actions.shell, syncActions = actions.settingsSync) }
        onNodeWithTag("page-back").assertIsDisplayed()
        onNodeWithTag("main-navigation-open").performClick()
        onNodeWithTag("main-navigation-drawer").assertIsDisplayed()
        runOnIdle { commands.clear() }
        onRoot().performMouseInput { moveTo(center); click(button = MouseButton(3)) }
        onNodeWithTag("main-navigation-drawer").assertDoesNotExist()
        runOnIdle { assertTrue(commands.isEmpty(), commands.toString()) }
        onRoot().performMouseInput { click(button = MouseButton(3)) }
        runOnIdle { assertEquals(listOf<NaviampCoreCommand>(NaviampCoreCommand.Navigation.Back), commands) }
    }

    @Test fun televisionSettingsReplacesSearchAndMixBackgroundsWithoutReopeningOnBack() {
        listOf(SharedRoute.Search, SharedRoute.ArtistMix, SharedRoute.AlbumMix, SharedRoute.GenreMix,
            SharedRoute.SonicPath, SharedRoute.SonicMix).forEach { background ->
            runDesktopComposeUiTest(1280, 850) {
                val commands = mutableListOf<NaviampCoreCommand>()
                val dispatcher = NaviampSystemBackDispatcher()
                var state by mutableStateOf(connectedShell())
                val actions = recordingActions(commands) { command ->
                    when (command) {
                        is NaviampCoreCommand.Navigation.ReplaceRoute -> state = state.copy(
                            shellChrome = state.shellChrome.copy(selectedRoute = command.route))
                        NaviampCoreCommand.Navigation.Back -> state = state.copy(
                            shellChrome = state.shellChrome.copy(selectedRoute = SharedRoute.Home, canNavigateBack = false))
                        else -> Unit
                    }
                }
                setContent {
                    CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) {
                        NaviampTelevisionAppShell(uiState = state, actions = actions.shell, syncActions = actions.settingsSync)
                    }
                }
                waitForIdle()
                runOnIdle { state = state.copy(shellChrome = state.shellChrome.copy(selectedRoute = background, canNavigateBack = true)) }
                onNodeWithTag("page-back").assertIsDisplayed().assertContentDescriptionEquals("Back")
                // A routed settings request is intercepted as a TV sheet, not another history push.
                runOnIdle {
                    commands.clear()
                    state = state.copy(shellChrome = state.shellChrome.copy(selectedRoute = SharedRoute.Settings))
                }
                onNodeWithText("Settings").assertIsDisplayed()
                runOnIdle {
                    assertEquals(background, state.shellChrome.selectedRoute)
                    assertEquals(listOf(NaviampCoreCommand.Navigation.ReplaceRoute(background)),
                        commands.filter { it is NaviampCoreCommand.Navigation.ReplaceRoute || it is NaviampCoreCommand.Navigation.SelectRoute })
                }
                onNodeWithText("Settings").performKeyInput { pressKey(Key.Escape) }
                onNodeWithText("Settings").assertDoesNotExist()
                onNodeWithTag("page-back").assertIsDisplayed()
                runOnIdle { commands.clear(); assertNotNull(dispatcher.currentHandler).invoke() }
                onNodeWithTag("page-back").assertDoesNotExist()
                onNodeWithText("Settings").assertDoesNotExist()
                runOnIdle {
                    assertEquals(SharedRoute.Home, state.shellChrome.selectedRoute)
                    assertEquals(1, commands.count { it == NaviampCoreCommand.Navigation.Back })
                    assertTrue(commands.none { it is NaviampCoreCommand.Navigation.SelectRoute || it is NaviampCoreCommand.Navigation.ReplaceRoute })
                }
            }
        }
    }

    private fun connectedShell() = NaviampAppShellUiState(connectionSettings = NaviampConnectionSettingsUi(
        connection = NaviampShellConnectionUi(connected = true)))

    private fun recordingActions(commands: MutableList<NaviampCoreCommand>, onCommand: (NaviampCoreCommand) -> Unit = {}) =
        createNaviampCoreActions(object : NaviampCoreCommandHandler {
            override fun dispatch(command: NaviampCoreCommand) { commands += command; onCommand(command) }
            override suspend fun execute(command: NaviampCoreCommand) = NaviampCoreCommandResult.Completed
        })
}
