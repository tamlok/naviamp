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
import androidx.compose.ui.semantics.getOrNull
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
                val checkAurora = ui.value.general.interfaceSettings.appBackgroundStyle == AppBackgroundStyle.Aurora
                mainClock.autoAdvance = false
                runOnIdle { ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(nowPlayingOpen = expanded)) }
                mainClock.advanceTimeByFrame()
                waitForIdle()
                val firstFrame = if (checkAurora) {
                    if (expanded) fullPixels() else miniPixel().let { it to it }
                } else null
                mainClock.advanceTimeBy(500)
                waitForIdle()
                if (firstFrame != null) {
                    val settled = if (expanded) fullPixels() else miniPixel().let { it to it }
                    assertEquals(settled, firstFrame, "Switching player size must not restart Aurora from fallback")
                }
                mainClock.autoAdvance = true
                if (expanded) {
                    onNodeWithTag("now-playing-back").assertIsDisplayed()
                    onNodeWithTag("main-navigation-rail").assertDoesNotExist()
                }
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
                onNodeWithTag("main-navigation-rail").assertDoesNotExist()
                onNodeWithTag("mini-player").assertDoesNotExist()
                artwork(blueArtwork)
                waitUntil(timeoutMillis = 5_000) { loadedPalette.backgroundStart.blue > loadedPalette.backgroundStart.red }
                mainClock.advanceTimeBy(500)
                waitForIdle()
                assertTrue(fullPixels() != redFull, "$style full-player background must follow artwork")
                onNodeWithTag("main-navigation-rail").assertDoesNotExist()
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
            onAllNodesWithTag("main-destination-Home").assertCountEquals(0)
            onNodeWithTag("mini-player").assertDoesNotExist()
            onNodeWithTag("now-playing-back").assertExists()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(editingConnection = true, editingSavedConnection = true),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onAllNodesWithTag("mini-player").assertCountEquals(1)
            onNodeWithTag("now-playing-back").assertDoesNotExist()
            onNodeWithTag("connection-name").assertExists()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(editingConnection = false),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(0)
            onNodeWithTag("mini-player").assertDoesNotExist()
            onNodeWithTag("now-playing-back").assertExists()
            onNodeWithTag("connection-name").assertDoesNotExist()
            ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                connection = ui.value.connectionSettings.connection.copy(restoringConnection = true),
            ))
            waitForIdle()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            onAllNodesWithTag("mini-player").assertCountEquals(1)
            onNodeWithTag("now-playing-back").assertDoesNotExist()
            ui.value = ui.value.copy(
                connectionSettings = ui.value.connectionSettings.copy(connection = ui.value.connectionSettings.connection.copy(
                    connected = false, restoringConnection = false,
                )),
                shellChrome = ui.value.shellChrome.copy(selectedRoute = SharedRoute.Home),
                connect = ui.value.connect.copy(remoteNowPlaying = ui.value.nowPlaying),
            )
            onNodeWithTag("connection-name").assertIsDisplayed()
            onNodeWithTag("mini-player").assertIsDisplayed()
            onNodeWithTag("now-playing-back").assertDoesNotExist()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(selectedRoute = SharedRoute.Settings))
            onNodeWithTag("mini-player").assertDoesNotExist()
            onNodeWithTag("now-playing-back").assertExists()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(0)
        } finally { scope.cancel() }
    }

    @Test
    fun myTabsKeepTheirHeaderAndSettingsStateAndUseSharedBackAndAccounts() = runDesktopComposeUiTest(1000, 740) {
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
            onNodeWithTag("my-accounts").assertIsSelected()
            onNodeWithTag("my-settings").performClick().assertIsSelected()
            onNodeWithTag("my-account-info").assertDoesNotExist()
            runOnIdle { assertEquals(SharedRoute.Settings, core.state.value.shell.shellChrome.selectedRoute) }
            onNodeWithText("Source", substring = false).performClick()
            onNodeWithText("Connections", substring = false).assertIsDisplayed()
            onNodeWithTag("my-downloads").performClick().assertIsSelected()
            onNodeWithTag("my-hub").assertIsDisplayed()
            onNodeWithTag("my-account-info").assertDoesNotExist()
            onNodeWithTag("my-settings").performClick().assertIsSelected()
            onNodeWithText("Connections", substring = false).assertIsDisplayed()
            // Leaving My and returning must not discard the nested Settings destination.
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Home)
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Settings)
            onNodeWithText("Connections", substring = false).assertIsDisplayed()
            runOnIdle { dispatcher.currentHandler?.invoke() }
            runOnIdle { assertEquals(SharedRoute.Settings, core.state.value.shell.shellChrome.selectedRoute) }
            onNodeWithText("Source", substring = false).assertIsDisplayed()
            runOnIdle { dispatcher.currentHandler?.invoke() }
            onNodeWithTag("my-accounts").assertIsSelected()
            onNodeWithTag("my-downloads").performClick()
            runOnIdle { assertEquals(SharedRoute.Downloads, core.state.value.shell.shellChrome.selectedRoute); dispatcher.currentHandler?.invoke() }
            onNodeWithTag("my-accounts").assertIsSelected()
            onNodeWithTag("my-account-chooser").performClick()
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
            onNodeWithTag("my-accounts").assertIsSelected()
        } finally { scope.cancel() }
    }

    @Test
    fun myTabsConfineAccountDetailsAndKeepOfflineDownloadsBoundedAtEveryWidth() {
        for ((width, height) in listOf(1000 to 740, 412 to 818, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val core = NaviampCore.create(scope, fakeCoreServices())
                val server = "https://music.example.test:8888"
                val account = NaviampSavedConnectionUi("my-server", server, server, "listener", current = true)
                val ui = mutableStateOf(NaviampAppShellUiState(
                    shellChrome = NaviampShellChromeUi(selectedRoute = SharedRoute.My, supportsDownloads = true),
                    connectionSettings = NaviampConnectionSettingsUi(connection = NaviampShellConnectionUi(
                        connected = false, hasSavedConnection = true, savedConnections = listOf(account),
                    )),
                ))
                val actions = core.actions.shell.copy(navigationActions = core.actions.shell.navigationActions.copy(
                    onRouteSelected = { route -> ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(selectedRoute = route)) },
                ))
                try {
                    setContent { NaviampSharedAppShell(uiState = ui.value, actions = actions, syncActions = core.actions.settingsSync) }
                    onNodeWithTag("my-accounts").assertIsSelected()
                    onAllNodesWithText(server, substring = false).assertCountEquals(1)
                    onNodeWithText("listener", substring = false).assertIsDisplayed()
                    val credentialUrl = "https://sensitive:credential@music.example.test:8888/?auth=token#fragment"
                    for ((saved, expected) in listOf(
                        account.copy(serverUrl = " $server/ ") to server,
                        account.copy(displayName = credentialUrl, serverUrl = " $credentialUrl ") to
                            "https://<redacted>@music.example.test:8888/",
                    )) {
                        runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                            connection = ui.value.connectionSettings.connection.copy(savedConnections = listOf(saved)),
                        )) }
                        onAllNodesWithText("https://", substring = true).assertCountEquals(1)
                        onNodeWithText(expected, substring = false).assertIsDisplayed()
                        onAllNodesWithText("credential", substring = true).assertCountEquals(0)
                        onAllNodesWithText("auth=", substring = true).assertCountEquals(0)
                    }
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(savedConnections = listOf(account)),
                    )) }
                    val tabs = listOf("my-accounts", "my-downloads", "my-settings")
                    val initialBounds = tabs.map { tag ->
                        val node = onNodeWithTag(tag).assertIsDisplayed()
                        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
                        node.fetchSemanticsNode().boundsInRoot
                    }
                    for (tag in listOf("my-downloads", "my-settings", "my-accounts")) {
                        onNodeWithTag(tag).performClick().assertIsSelected()
                        tabs.forEachIndexed { index, tab ->
                            assertEquals(initialBounds[index], onNodeWithTag(tab).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
                        }
                        onNodeWithTag("connection-name").assertDoesNotExist()
                        if (tag != "my-accounts") {
                            onNodeWithTag("my-account-info").assertDoesNotExist()
                            onNodeWithText("listener", substring = false).assertDoesNotExist()
                        }
                        // Settings retains its existing Source summary, not the Accounts information panel.
                        if (tag == "my-downloads") onNodeWithText(server, substring = false).assertDoesNotExist()
                    }
                    onNodeWithTag("my-accounts").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                    onNodeWithTag("my-accounts").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
                    onNodeWithTag("my-downloads").assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
                    onNodeWithTag("my-downloads").assertIsSelected()
                    // Capability changes can leave Core on Downloads; show Accounts rather than a login form.
                    runOnIdle { ui.value = ui.value.copy(shellChrome = ui.value.shellChrome.copy(supportsDownloads = false)) }
                    onNodeWithTag("my-downloads").assertDoesNotExist()
                    onNodeWithTag("my-accounts").assertIsSelected()
                    onNodeWithText(server, substring = false).assertIsDisplayed()
                    onNodeWithTag("connection-name").assertDoesNotExist()
                    runOnIdle { ui.value = ui.value.copy(connectionSettings = ui.value.connectionSettings.copy(
                        connection = ui.value.connectionSettings.connection.copy(savedConnections = listOf(account.copy(displayName = "Home music"))),
                    )) }
                    onNodeWithText("Home music", substring = false).assertIsDisplayed()
                    onAllNodesWithText(server, substring = false).assertCountEquals(1)
                } finally { scope.cancel() }
            }
        }
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
    fun miniPlayerQueuePreservesBrowsingAndDismissesWithoutOpeningThePlayer() {
        for ((width, height) in listOf(1000 to 740, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val provider = FakeCoreMediaProvider()
                val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
                    connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
                ))
                val playing = mutableStateOf<NowPlayingUi?>(NowPlayingUi(id = "queue-track", title = "Current song", subtitle = "Artist",
                    stateLabel = "Paused", isPaused = true, hasNext = false, canPlayPause = false,
                    upNext = listOf(NaviampNowPlayingItemUi("next", "Upcoming song", "Artist"))))
                val dispatcher = NaviampSystemBackDispatcher()
                try {
                    setContent {
                        val shell = core.state.collectAsState().value.shell
                        CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) {
                            NaviampSharedAppShell(uiState = shell.copy(nowPlaying = playing.value),
                                actions = core.actions.shell, syncActions = core.actions.settingsSync)
                        }
                    }
                    val playback = core.state.value.shell.playback
                    val route = onNodeWithTag("main-route-content").getUnclippedBoundsInRoot()
                    fun assertBrowsing() {
                        onNodeWithTag("mini-player").assertIsDisplayed()
                        onNodeWithTag("now-playing-back").assertDoesNotExist()
                        onNodeWithTag(if (width == 320) "main-navigation-open" else "main-navigation-rail").assertIsDisplayed()
                        assertEquals(route, onNodeWithTag("main-route-content").getUnclippedBoundsInRoot())
                        runOnIdle {
                            assertEquals(false, core.state.value.shell.shellChrome.nowPlayingOpen)
                            assertEquals(SharedRoute.Home, core.state.value.shell.shellChrome.selectedRoute)
                            assertEquals(playback, core.state.value.shell.playback)
                        }
                    }
                    fun openQueue() {
                        onNodeWithContentDescription("Queue").assertIsEnabled().performClick()
                        onNodeWithTag("now-playing-queue-drawer").assertIsDisplayed()
                        onNodeWithTag("now-playing-queue-pin").assertDoesNotExist()
                        onNodeWithText("Upcoming song").assertIsDisplayed()
                        assertBrowsing()
                    }
                    if (width < 480) onNodeWithContentDescription("Next").assertDoesNotExist()
                    else onNodeWithContentDescription("Next").assertIsNotEnabled()
                    openQueue()
                    onNodeWithTag("now-playing-queue-close").assertIsDisplayed().performClick()
                    onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                    assertBrowsing()
                    onNodeWithContentDescription("Queue").assertIsFocused()
                    openQueue()
                    onNodeWithTag("now-playing-queue-drawer").performKeyInput {
                        pressKey(androidx.compose.ui.input.key.Key.Escape)
                    }
                    onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                    onNodeWithContentDescription("Queue").assertIsFocused()
                    openQueue()
                    runOnIdle { assertNotNull(dispatcher.currentHandler).invoke() }
                    onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                    openQueue()
                    // A click on the identity beneath the scrim dismisses, never opens details.
                    onNodeWithTag("mini-player-identity").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, centerY)) }
                    onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                    assertBrowsing()
                    openQueue()
                    runOnIdle { core.actions.shell.navigationActions.onOpenNowPlaying() }
                    onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                    onNodeWithTag("now-playing-back").assertIsDisplayed().performClick()
                    assertBrowsing()
                    openQueue()
                    runOnIdle { playing.value = null }
                    onNodeWithTag("now-playing-queue-drawer").assertDoesNotExist()
                    onNodeWithContentDescription("Queue").assertIsNotEnabled()
                } finally { scope.cancel() }
            }
        }
    }

    @Test
    fun detailsHideTheMiniPlayerAndEveryExitRestoresItWithoutStoppingPlayback() {
        for ((width, height) in listOf(1000 to 740, 412 to 818, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                // Active playback animates continuously; advance frames explicitly for navigation.
                mainClock.autoAdvance = false
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val provider = FakeCoreMediaProvider()
                val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
                    connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
                ))
                kotlinx.coroutines.runBlocking {
                    core.execute(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs))
                    core.execute(NaviampCoreCommand.Library.TrackAction(SharedTrackRowActionRequest(
                        core.state.value.shell.library.songs.tracks.single(), SharedTrackRowAction.Select)))
                    core.execute(NaviampCoreCommand.Navigation.CloseNowPlaying)
                }
                core.updateLivePlayback { it.copy(playbackState = app.naviamp.domain.playback.PlaybackState.Playing) }
                val dispatcher = NaviampSystemBackDispatcher()
                try {
                    setContent { CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) { NaviampCoreApp(core) } }
                    mainClock.advanceTimeBy(300)
                    val playing = assertNotNull(core.state.value.shell.nowPlaying)
                    val playback = core.state.value.shell.playback
                    assertEquals(provider.track.id.value, playing.id)
                    assertEquals(false, playing.isPaused)
                    fun assertExpandedViewport() {
                        mainClock.advanceTimeBy(300)
                        onNodeWithTag("now-playing-back").assertIsDisplayed()
                        for (tag in listOf("mini-player", "main-mini-player", "main-navigation-rail",
                            "main-navigation-bottom", "main-navigation-open", "main-navigation-drawer")) {
                            onNodeWithTag(tag).assertDoesNotExist()
                        }
                        onAllNodes(SemanticsMatcher("main destinations") {
                            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("main-destination-") == true
                        }).assertCountEquals(0)
                        val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                        assertEquals(onRoot().fetchSemanticsNode().boundsInRoot, route,
                            "$width x $height expanded player must reclaim the whole viewport")
                    }
                    fun assertRestored(route: SharedRoute) {
                        mainClock.advanceTimeBy(300)
                        onNodeWithTag("now-playing-back").assertDoesNotExist()
                        onNodeWithTag("mini-player").assertIsDisplayed()
                        onNodeWithTag(when {
                            width < 360 -> "main-navigation-open"
                            width > height -> "main-navigation-rail"
                            else -> "main-navigation-bottom"
                        }).assertIsDisplayed()
                        runOnIdle {
                            assertEquals(route, core.state.value.shell.shellChrome.selectedRoute)
                            assertEquals(false, core.state.value.shell.shellChrome.nowPlayingOpen)
                            assertEquals(playing, core.state.value.shell.nowPlaying, "Track, queue and playback must survive exit")
                            assertEquals(playback, core.state.value.shell.playback)
                        }
                    }
                    val initialRoute = core.state.value.shell.shellChrome.selectedRoute
                    onNodeWithTag("mini-player-identity").performClick()
                    assertExpandedViewport()
                    onNodeWithTag("now-playing-back").performClick()
                    assertRestored(initialRoute)
                    if (width < 360) {
                        onNodeWithTag("main-navigation-open").performClick()
                        mainClock.advanceTimeBy(300)
                    }
                    onNodeWithTag("main-destination-Library").performClick()
                    mainClock.advanceTimeBy(300)
                    onNodeWithTag("mini-player-identity").performClick()
                    assertExpandedViewport()
                    runOnIdle { assertNotNull(dispatcher.currentHandler).invoke() }
                    assertRestored(SharedRoute.Library)
                } finally { scope.cancel() }
            }
        }
    }

    @Test
    fun libraryHeartFiltersAllTabsWithHomeSectionHiddenAndMyHasNoShortcut() = runDesktopComposeUiTest(1000, 740) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val base = FakeCoreMediaProvider()
        val favorite = app.naviamp.domain.Artist(app.naviamp.domain.ArtistId("favorite"), "Favorite Artist",
            favoritedAtIso8601 = "2026-11-20T00:00:00Z")
        val ordinary = app.naviamp.domain.Artist(app.naviamp.domain.ArtistId("ordinary"), "Ordinary Artist")
        val favoriteAlbum = base.album.copy(id = app.naviamp.domain.AlbumId("favorite-album"), title = "Favorite Album",
            favoritedAtIso8601 = "2026-11-20T00:00:00Z")
        val ordinaryAlbum = base.album.copy(id = app.naviamp.domain.AlbumId("ordinary-album"), title = "Ordinary Album")
        val favoriteSong = base.track.copy(id = app.naviamp.domain.TrackId("favorite-song"), title = "Favorite Song",
            favoritedAtIso8601 = "2026-11-20T00:00:00Z")
        val ordinarySong = base.track.copy(id = app.naviamp.domain.TrackId("ordinary-song"), title = "Ordinary Song")
        val provider = object : app.naviamp.domain.provider.MediaProvider by base {
            override val capabilities = base.capabilities.copy(
                supportsArtistFavorites = true, supportsAlbumFavorites = true, supportsTrackFavorites = true)
            override suspend fun artists(limit: Int) = listOf(favorite.copy(favoritedAtIso8601 = null), ordinary)
            override suspend fun artistsPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                app.naviamp.domain.provider.MediaPage(artists(request.limit), request.offset, request.limit, false)
            override suspend fun favoriteArtistsPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                app.naviamp.domain.provider.MediaPage(listOf(favorite), request.offset, request.limit, false)
            override suspend fun albumsPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                app.naviamp.domain.provider.MediaPage(listOf(favoriteAlbum.copy(favoritedAtIso8601 = null), ordinaryAlbum), request.offset, request.limit, false)
            override suspend fun favoriteAlbumsPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                app.naviamp.domain.provider.MediaPage(listOf(favoriteAlbum), request.offset, request.limit, false)
            override suspend fun tracksPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                app.naviamp.domain.provider.MediaPage(listOf(favoriteSong.copy(favoritedAtIso8601 = null), ordinarySong), request.offset, request.limit, false)
            override suspend fun favoriteTracksPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                app.naviamp.domain.provider.MediaPage(listOf(favoriteSong), request.offset, request.limit, false)
        }
        val section = SharedHomeCollectionSectionUi("favorites", "Favorite artists", emptyList(),
            titleResource = SharedHomeCollectionTitleResource.FavoriteArtists, visible = false)
        val core = NaviampCore.create(scope, fakeCoreServices(provider), initialState = NaviampCoreInitialState(
            connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = provider.id.value),
            product = NaviampCoreState(shell = NaviampAppShellUiState(home = NaviampHomeScreenUi(
                content = SharedHomeUi(collectionSections = listOf(section))))),
        ))
        try {
            setContent { NaviampCoreApp(core) }
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.My)
            onNodeWithTag("my-hub").assertIsDisplayed()
            onNodeWithTag("my-favorite-artists").assertDoesNotExist()
            onNodeWithTag("my-accounts").assertIsDisplayed()
            onNodeWithTag("my-settings").assertIsDisplayed()
            onNodeWithTag("main-destination-Library").performClick()
            onNodeWithText("Artists").performClick()
            onNodeWithText("Ordinary Artist").assertIsDisplayed()
            onNodeWithText("Favorites").assertDoesNotExist()
            onNodeWithContentDescription("Favorites").assertIsNotSelected().performClick()
            onNodeWithContentDescription("Favorites").assertIsSelected()
            onNodeWithText("Favorite Artist").assertIsDisplayed()
            onNodeWithText("Ordinary Artist").assertDoesNotExist()
            runOnIdle { assertTrue(core.state.value.shell.library.favoritesOnly) }
            onNodeWithText("Albums").performClick()
            onNodeWithContentDescription("Favorites").assertIsSelected()
            onNodeWithText("Favorite Album").assertIsDisplayed()
            onNodeWithText("Ordinary Album").assertDoesNotExist()
            onNodeWithText("Songs").performClick()
            onNodeWithContentDescription("Favorites").assertIsSelected()
            onNodeWithText("Favorite Song").assertIsDisplayed()
            onNodeWithText("Ordinary Song").assertDoesNotExist()
            onNodeWithContentDescription("Favorites").performClick().assertIsNotSelected()
            onNodeWithText("Ordinary Song").assertIsDisplayed()
            onNodeWithText("Albums").performClick()
            onNodeWithContentDescription("Favorites").assertIsNotSelected()
            onNodeWithText("Ordinary Album").assertIsDisplayed()
            onNodeWithText("Artists").performClick()
            onNodeWithContentDescription("Favorites").assertIsNotSelected()
            onNodeWithText("Ordinary Artist").assertIsDisplayed()
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
            val playback = core.state.value.shell.playback
            val localNowPlaying = core.state.value.shell.nowPlaying
            onNodeWithTag("mini-player-identity").performClick()
            onNodeWithTag("now-playing-back").assertIsDisplayed()
            onNodeWithTag("mini-player").assertDoesNotExist()
            for (tag in listOf("main-navigation-rail", "main-navigation-bottom", "main-navigation-open", "main-navigation-drawer")) {
                onNodeWithTag(tag).assertDoesNotExist()
            }
            onAllNodes(SemanticsMatcher("main destinations") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("main-destination-") == true
            }).assertCountEquals(0)
            assertEquals(onRoot().fetchSemanticsNode().boundsInRoot,
                onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot)
            runOnIdle { assertTrue(core.state.value.shell.shellChrome.nowPlayingOpen) }
            onNodeWithTag("now-playing-back").performClick()
            onNodeWithTag("now-playing-back").assertDoesNotExist()
            onNodeWithTag("mini-player-identity").assertIsDisplayed().assertHasClickAction()
            onNodeWithText(remote.title).assertIsDisplayed()
            onNodeWithTag("main-navigation-rail").assertIsDisplayed()
            onAllNodesWithTag("main-destination-Home").assertCountEquals(1)
            runOnIdle {
                assertEquals(SharedRoute.Settings, core.state.value.shell.shellChrome.selectedRoute)
                assertEquals(false, core.state.value.shell.shellChrome.nowPlayingOpen)
                assertEquals(playback, core.state.value.shell.playback)
                assertEquals(localNowPlaying, core.state.value.shell.nowPlaying)
            }
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
