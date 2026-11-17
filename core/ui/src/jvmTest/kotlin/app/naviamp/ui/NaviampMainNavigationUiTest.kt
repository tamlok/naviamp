package app.naviamp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.InterfaceFontSize
import app.naviamp.domain.settings.InterfaceLanguage
import app.naviamp.domain.settings.NowPlayingDisplaySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampMainNavigationUiTest {
    @Test
    fun allViewportBoundariesKeepContentNavigationAndFooterDisjoint() {
        val cases = listOf(
            Triple(1280, 720, "rail"), Triple(412, 818, "bottom"),
            Triple(320, 640, "drawer"), Triple(640, 360, "drawer"),
            Triple(599, 500, "drawer"), Triple(600, 500, "rail"),
            Triple(359, 640, "drawer"), Triple(360, 640, "bottom"),
            Triple(480, 480, "bottom"),
        )
        cases.forEach { (width, height, placement) ->
            runDesktopComposeUiTest(width, height) {
                setContent { NavigationFixture() }
                val content = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                val footer = onNodeWithTag("main-mini-player").fetchSemanticsNode().boundsInRoot
                assertEquals(0f, footer.left, 0.5f)
                assertEquals(width.toFloat(), footer.right, 0.5f)
                assertEquals(content.bottom, footer.top, 0.5f)
                when (placement) {
                    "rail" -> {
                        val rail = onNodeWithTag("main-navigation-rail").fetchSemanticsNode().boundsInRoot
                        assertEquals(0f, rail.left, 0.5f)
                        assertEquals(88f, rail.width, 0.5f)
                        assertEquals(rail.right, content.left, 0.5f)
                        assertEquals(rail.bottom, footer.top, 0.5f)
                        assertEquals(height.toFloat(), footer.bottom, 0.5f)
                        onNodeWithTag("main-navigation-open").assertDoesNotExist()
                    }
                    "bottom" -> {
                        val navigation = onNodeWithTag("main-navigation-bottom").fetchSemanticsNode().boundsInRoot
                        assertEquals(footer.bottom, navigation.top, 0.5f)
                        assertEquals(height.toFloat(), navigation.bottom, 0.5f)
                        assertEquals(0f, content.top, 0.5f)
                        onNodeWithTag("main-navigation-open").assertDoesNotExist()
                    }
                    else -> {
                        onNodeWithTag("main-navigation-open").assertIsDisplayed()
                        assertEquals(48f, content.top, 0.5f)
                        assertEquals(height.toFloat(), footer.bottom, 0.5f)
                        assertDrawerClosed()
                    }
                }
            }
        }
    }

    @Test
    fun allFiveDestinationsSelectTheirContentInEveryNavigationPlacement() {
        listOf(1280 to 720, 412 to 818, 320 to 640).forEach { (width, height) ->
            runDesktopComposeUiTest(width, height) {
                var selected by mutableStateOf(SharedRoute.Home)
                setContent { NavigationFixture(selected, { selected = it }) }
                val drawer = width < 360
                listOf(SharedRoute.Home, SharedRoute.Playlists, SharedRoute.Library, SharedRoute.Radio, SharedRoute.My)
                    .forEach { route ->
                        if (drawer) openDrawer()
                        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(5)
                        onNodeWithTag("main-destination-Search").assertDoesNotExist()
                        onNodeWithTag("main-destination-Queue").assertDoesNotExist()
                        onNodeWithTag("main-destination-Settings").assertDoesNotExist()
                        onNodeWithTag("main-destination-Downloads").assertDoesNotExist()
                        onNodeWithTag("main-destination-${route.name}").performScrollTo().performClick()
                        onNodeWithTag("route-${route.name}").assertIsDisplayed()
                        if (drawer) assertDrawerClosed()
                        else onNodeWithTag("main-destination-${route.name}").assertIsSelected()
                    }
            }
        }
    }

    @Test
    fun nestedRoutesSelectTheirOwnerAndSearchSelectsNoPrimaryDestination() = runDesktopComposeUiTest(600, 500) {
        var selected by mutableStateOf(SharedRoute.Settings)
        setContent { NavigationFixture(selected) }
        onNodeWithTag("main-destination-My").assertIsSelected()
        runOnIdle { selected = SharedRoute.Downloads }
        onNodeWithTag("main-destination-My").assertIsSelected()
        runOnIdle { selected = SharedRoute.ArtistMix }
        onNodeWithTag("main-destination-Home").assertIsSelected()
        runOnIdle { selected = SharedRoute.Search }
        onAllNodes(isSelected()).assertCountEquals(0)
        onNodeWithTag("route-Search").assertIsDisplayed()
    }

    @Test
    fun drawerDismissalWinsOverNestedBackAndRestoresToggleFocus() = runDesktopComposeUiTest(320, 640) {
        val dispatcher = NaviampSystemBackDispatcher()
        var nestedBacks = 0
        lateinit var inputMode: InputModeManager
        setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(LocalNaviampSystemBackDispatcher provides dispatcher) {
                NavigationFixture(content = {
                    NaviampSystemBackHandler(enabled = true) { nestedBacks++ }
                    Button(onClick = {}, modifier = Modifier.testTag("route-action")) { Text("Route action") }
                })
            }
        }
        runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        openDrawer()
        onNodeWithTag("main-navigation-close").performClick()
        assertDrawerClosed()
        onNodeWithTag("main-navigation-open").assertIsFocused()

        openDrawer()
        onRoot().performTouchInput { click(Offset(width - 8f, 80f)) }
        assertDrawerClosed()
        onNodeWithTag("main-navigation-open").assertIsFocused()

        openDrawer()
        runOnIdle { dispatcher.currentHandler!!.invoke() }
        assertDrawerClosed()
        runOnIdle { assertEquals(0, nestedBacks) }
        onNodeWithTag("main-navigation-open").assertIsFocused()

        openDrawer()
        onRoot().performKeyInput { keyDown(Key.Escape) }
        assertDrawerClosed() // Dismiss on key-down, not on the Material key-up fallback.
        onRoot().performKeyInput { keyUp(Key.Escape) }
        onNodeWithTag("main-navigation-open").assertIsFocused()
        runOnIdle { dispatcher.currentHandler!!.invoke(); assertEquals(1, nestedBacks) }
    }

    @Test
    fun drawerFocusStartsAtSelectionAndTabsWrapWithoutReachingContentOrFooter() = runDesktopComposeUiTest(320, 640) {
        lateinit var inputMode: InputModeManager
        setContent {
            inputMode = LocalInputModeManager.current
            NavigationFixture(route = SharedRoute.Settings, content = {
                Button(onClick = {}, modifier = Modifier.testTag("route-action")) { Text("Route action") }
            })
        }
        runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        openDrawer()
        onNodeWithTag("main-destination-My").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("main-navigation-close").assertIsFocused().performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) }
        }
        onNodeWithTag("main-destination-My").assertIsFocused()
        onNodeWithTag("route-action").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("route-action").assertIsNotFocused()
        onNodeWithTag("main-destination-My").assertIsFocused()
        listOf("close", "Home", "Playlists", "Library", "Radio", "My", "close").forEach { item ->
            onRoot().performKeyInput { pressKey(Key.Tab) }
            val tag = if (item == "close") "main-navigation-close" else "main-destination-$item"
            onNodeWithTag(tag).assertIsFocused()
            onNodeWithTag("route-action").assertIsNotFocused()
            onNodeWithTag("footer-action").assertIsNotFocused()
        }
        onNodeWithTag("main-navigation-close").performClick()
        assertDrawerClosed()
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
        onNodeWithTag("main-navigation-open").assertIsFocused()
    }

    @Test
    fun edgeGestureHonorsSlopIntentTravelAndContentScrolling() = runDesktopComposeUiTest(320, 640) {
        lateinit var routeScroll: ScrollState
        setContent {
            NavigationFixture(content = {
                val scroll = rememberScrollState()
                SideEffect { routeScroll = scroll }
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) { Spacer(Modifier.height(1800.dp)) }
            })
        }
        val content = onNodeWithTag("main-route-content")
        content.performTouchInput {
            swipe(start = Offset(8f, 200f), end = Offset(12f, 80f), durationMillis = 300)
        }
        assertDrawerClosed()
        runOnIdle { assertTrue(routeScroll.value > 0, "Vertical edge drag must scroll route content") }
        content.performTouchInput {
            down(Offset(80f, 200f)); moveTo(Offset(240f, 200f), delayMillis = 300); up()
        }
        assertDrawerClosed()
        content.performTouchInput { down(Offset(8f, 200f)); moveTo(Offset(58f, 200f)); up() }
        assertDrawerClosed()
        content.performTouchInput {
            down(Offset(8f, 200f)); moveTo(Offset(110f, 201f)); moveTo(Offset(25f, 201f)); up()
        }
        assertDrawerClosed()
        onNodeWithTag("main-mini-player").performTouchInput {
            down(Offset(8f, 20f)); moveTo(Offset(120f, 20f)); up()
        }
        assertDrawerClosed()
        content.performTouchInput { down(Offset(8f, 200f)); moveTo(Offset(110f, 200f), delayMillis = 300); up() }
        onNodeWithTag("main-navigation-drawer").assertIsDisplayed()
        onNodeWithTag("main-navigation-drawer").performTouchInput {
            swipe(start = Offset(width - 16f, 100f), end = Offset(1f, 100f), durationMillis = 300)
        }
        assertDrawerClosed()
    }

    @Test
    fun cancellingAnActiveEdgeDragOnResizeNeverOpensTheDrawerOrCapturesTheFooter() = runDesktopComposeUiTest(1280, 720) {
        var size by mutableStateOf(DpSize(320.dp, 640.dp))
        setContent { Box(Modifier.size(size)) { NavigationFixture() } }
        onNodeWithTag("main-route-content").performTouchInput {
            down(Offset(8f, 200f)); moveTo(Offset(110f, 200f))
        }
        assertDrawerClosed()
        // Skiko's touch cancel() dispatches no event. Detaching the edge handler instead
        // exercises real pointer-input coroutine cancellation while the pointer is still down.
        runOnIdle { size = DpSize(1280.dp, 720.dp) }
        onNodeWithTag("main-navigation-rail").assertIsDisplayed()
        onRoot().performTouchInput { up() }
        runOnIdle { size = DpSize(320.dp, 640.dp) }
        assertDrawerClosed()
        onNodeWithTag("main-mini-player").performTouchInput {
            swipe(start = Offset(8f, 20f), end = Offset(120f, 20f), durationMillis = 300)
        }
        assertDrawerClosed()
        onNodeWithTag("main-route-content").performTouchInput {
            swipe(start = Offset(8f, 200f), end = Offset(110f, 200f), durationMillis = 300)
        }
        onNodeWithTag("main-navigation-drawer").assertIsDisplayed()
    }

    @Test
    fun resizePreservesRememberedRouteStateAndScrollAndNeverReopensDrawer() = runDesktopComposeUiTest(1280, 818) {
        var size by mutableStateOf(DpSize(1280.dp, 720.dp))
        var creations = 0
        lateinit var routeScroll: ScrollState
        var selected by mutableStateOf(SharedRoute.Library)
        setContent {
            Box(Modifier.size(size)) {
                NavigationFixture(selected, { selected = it }, content = {
                    remember { creations++ }
                    var count by remember { mutableIntStateOf(0) }
                    val scroll = rememberScrollState()
                    SideEffect { routeScroll = scroll }
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("remembered-content")) {
                        Button(onClick = { count++ }, modifier = Modifier.testTag("route-counter")) { Text("Increment") }
                        Text(count.toString(), modifier = Modifier.testTag("route-count-$count"))
                        Spacer(Modifier.height(1600.dp))
                    }
                })
            }
        }
        onNodeWithTag("route-counter").performClick()
        onNodeWithTag("remembered-content").performTouchInput {
            swipe(start = center, end = center - Offset(0f, 120f), durationMillis = 500)
        }
        val beforeResize = runOnIdle { routeScroll.value.also { assertTrue(it > 0, "Route must be scrolled before resize") } }
        runOnIdle { size = DpSize(412.dp, 818.dp) }
        onNodeWithTag("main-navigation-bottom").assertIsDisplayed()
        runOnIdle { assertEquals(beforeResize, routeScroll.value); assertEquals(1, creations) }
        onNodeWithTag("route-count-1").assertExists()
        runOnIdle { size = DpSize(320.dp, 640.dp) }
        openDrawer()
        runOnIdle { size = DpSize(1280.dp, 720.dp) }
        onNodeWithTag("main-navigation-rail").assertIsDisplayed()
        onNodeWithTag("main-destination-Library").assertIsSelected()
        runOnIdle { size = DpSize(320.dp, 640.dp) }
        assertDrawerClosed()
        runOnIdle { assertEquals(beforeResize, routeScroll.value); assertEquals(1, creations) }
        onNodeWithTag("route-count-1").assertExists()
    }

    @Test
    fun adaptivePlayerUsesRemainingHeightWithAnEmptyFooterThroughResizes() = runDesktopComposeUiTest(1280, 818) {
        var size by mutableStateOf(DpSize(1280.dp, 720.dp))
        val track = NowPlayingUi(id = "track", title = "Retained track", subtitle = "Artist", stateLabel = "Paused", isPaused = true)
        setContent {
            Box(Modifier.size(size)) {
                NavigationFixture(showFooter = false, content = {
                    NaviampNowPlayingPanel(
                        nowPlaying = track, colors = NaviampColors.Dark,
                        displaySettings = NowPlayingDisplaySettings(scrollTrackTitle = false),
                        actions = NaviampNowPlayingActions({}, {}, {}, {}, {}, {}, {}),
                        modifier = Modifier.testTag("player-details"), contentInsets = WindowInsets(0),
                    )
                })
            }
        }
        listOf(DpSize(1280.dp, 720.dp), DpSize(412.dp, 818.dp), DpSize(320.dp, 640.dp), DpSize(640.dp, 360.dp))
            .forEach { viewport ->
                runOnIdle { size = viewport }
                val player = onNodeWithTag("player-details").fetchSemanticsNode().boundsInRoot
                val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                val bottom = onAllNodesWithTag("main-navigation-bottom").fetchSemanticsNodes().singleOrNull()
                    ?.boundsInRoot?.top ?: viewport.height.value
                assertEquals(route, player)
                assertEquals(bottom, player.bottom, 0.5f)
                onNodeWithTag("footer-action").assertDoesNotExist()
                onNodeWithText("Retained track").assertExists()
            }
    }

    @Test
    fun selectedRailItemIsRevealedAfterTheViewportShrinks() = runDesktopComposeUiTest(1280, 720) {
        var size by mutableStateOf(DpSize(1280.dp, 720.dp))
        setContent {
            Box(Modifier.size(size)) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    NavigationFixture(route = SharedRoute.My, footerHeight = 180.dp)
                }
            }
        }
        onNodeWithTag("main-destination-My").assertIsDisplayed()
        runOnIdle { size = DpSize(600.dp, 500.dp) }
        onNodeWithTag("main-destination-My").assertIsSelected().assertIsDisplayed()
        val rail = onNodeWithTag("main-navigation-rail").fetchSemanticsNode()
        assertTrue(rail.config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        val selected = onNodeWithTag("main-destination-My").fetchSemanticsNode().boundsInRoot
        assertTrue(selected.top >= rail.boundsInRoot.top && selected.bottom <= rail.boundsInRoot.bottom)
    }

    @Test
    fun safeInsetsAreConsumedOnceAndKeyboardDoesNotChangePlacement() = runDesktopComposeUiTest(412, 818) {
        var keyboardHeight by mutableIntStateOf(0)
        var systemTop by mutableIntStateOf(24)
        setContent {
            val system = WindowInsets(left = 10, top = systemTop, right = 12, bottom = 20)
                .union(WindowInsets(top = 30))
            val keyboard = WindowInsets(bottom = keyboardHeight)
            NavigationFixture(systemInsets = system, keyboardInsets = keyboard, content = {
                Box(Modifier.fillMaxSize().naviampContentInsets(system, keyboard).testTag("nested-insets"))
            })
        }
        fun check(bottomInset: Int) {
            val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
            val nested = onNodeWithTag("nested-insets").fetchSemanticsNode().boundsInRoot
            val navigation = onNodeWithTag("main-navigation-bottom").fetchSemanticsNode().boundsInRoot
            assertEquals(10f, route.left, 0.5f)
            assertEquals(400f, route.right, 0.5f)
            assertEquals(30f, route.top, 0.5f)
            assertEquals(route, nested)
            assertEquals(818f - bottomInset, navigation.bottom, 0.5f)
        }
        check(20)
        runOnIdle { keyboardHeight = 500 }
        check(500)
        onNodeWithTag("main-navigation-open").assertDoesNotExist()
        runOnIdle { keyboardHeight = 0 }
        check(20)
        runOnIdle { systemTop = 350 }
        onNodeWithTag("main-navigation-open").assertIsDisplayed()
        onNodeWithTag("main-navigation-bottom").assertDoesNotExist()
    }

    @Test
    fun constrainedRailAndDrawerActuallyScrollToTheLastDestination() {
        listOf(600 to 500, 320 to 240).forEach { (width, height) ->
            runDesktopComposeUiTest(width, height) {
                var selected by mutableStateOf(SharedRoute.Home)
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        NavigationFixture(selected, { selected = it }, footerHeight = 180.dp)
                    }
                }
                val drawer = width == 320
                if (drawer) openDrawer()
                val navigation = onNodeWithTag(if (drawer) "main-navigation-drawer" else "main-navigation-rail")
                val before = navigation.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                onNodeWithTag("main-destination-My").performScrollTo().assertIsDisplayed()
                val after = navigation.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue(after > before, "The constrained navigation must scroll, not only compose its final item")
                onNodeWithTag("main-destination-My").performClick()
                runOnIdle { assertEquals(SharedRoute.My, selected) }
            }
        }
    }

    @Test
    fun narrowBottomNavigationScrollsInsteadOfShrinkingTargetsAndRevealsKeyboardFocus() = runDesktopComposeUiTest(240, 240) {
        var selected by mutableStateOf<SharedRoute?>(SharedRoute.Home)
        lateinit var inputMode: InputModeManager
        setContent {
            inputMode = LocalInputModeManager.current
            MaterialTheme { MainNavigationBottom(selected, NaviampColors.Dark, { selected = it }) }
        }
        onNodeWithTag("main-destination-My").performScrollTo().assertIsDisplayed().performClick()
        runOnIdle { assertEquals(SharedRoute.My, selected) }
        onNodeWithTag("main-destination-My").assertWidthIsAtLeast(72.dp).assertHeightIsAtLeast(64.dp)
        onNodeWithTag("main-destination-Home").performScrollTo().performClick()
        runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        onNodeWithTag("main-destination-My").performSemanticsAction(SemanticsActions.RequestFocus)
        onNodeWithTag("main-destination-My").assertIsFocused().assertIsDisplayed()
        val item = onNodeWithTag("main-destination-My").fetchSemanticsNode().boundsInRoot
        assertTrue(item.left >= 0f && item.right <= 240f)
        runOnIdle { selected = SharedRoute.Radio }
        onNodeWithTag("main-destination-Radio").assertIsDisplayed()
        runOnIdle { selected = SharedRoute.Home }
        onNodeWithTag("main-destination-Home").assertIsDisplayed()
    }

    @Test
    fun largeGermanAndArabicLabelsWrapAndRemainReachableWithPhysicalLeftNavigation() {
        listOf(InterfaceLanguage.German to LayoutDirection.Ltr, InterfaceLanguage.Arabic to LayoutDirection.Rtl)
            .forEach { (language, direction) ->
                listOf(1280 to 720, 412 to 818, 320 to 640).forEach { (width, height) ->
                    runDesktopComposeUiTest(width, height) {
                        var selected by mutableStateOf(SharedRoute.Home)
                        val localeEffect = createNaviampLocaleEffect()
                        setContent {
                            NaviampLocaleEnvironment(language, localeEffect) {
                                CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides direction) {
                                    NaviampFontSizeScope(InterfaceFontSize.Large) {
                                        NavigationFixture(selected, { selected = it })
                                    }
                                }
                            }
                        }
                        val drawer = width == 320
                        if (drawer) openDrawer()
                        if (width == 1280 || drawer) {
                            val navigation = onNodeWithTag(if (drawer) "main-navigation-drawer" else "main-navigation-rail")
                                .fetchSemanticsNode().boundsInRoot
                            assertEquals(0f, navigation.left, 0.5f)
                            if (drawer) assertTrue(navigation.width <= width - 48f)
                        }
                        listOf(SharedRoute.Home, SharedRoute.Playlists, SharedRoute.Library, SharedRoute.Radio, SharedRoute.My)
                            .forEach { route ->
                                val item = onNodeWithTag("main-destination-${route.name}").performScrollTo()
                                item.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                                val textLayouts = mutableListOf<TextLayoutResult>()
                                item.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
                                assertTrue(textLayouts.isNotEmpty())
                                assertTrue(textLayouts.none { it.hasVisualOverflow }, buildString {
                                    append("Localized navigation label clipped: language=$language viewport=${width}x$height route=$route direction=$direction ")
                                    append("item=${item.fetchSemanticsNode().boundsInRoot}; ")
                                    textLayouts.forEach { layout ->
                                        append("text=${layout.layoutInput.text.text} size=${layout.size} constraints=${layout.layoutInput.constraints} ")
                                        append("lines=${layout.lineCount} overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} ")
                                        append("paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; ")
                                    }
                                })
                            }
                        onNodeWithTag("main-destination-My").performClick()
                        onNodeWithTag("route-My").assertIsDisplayed()
                        val footer = onNodeWithTag("main-mini-player").fetchSemanticsNode().boundsInRoot
                        val route = onNodeWithTag("main-route-content").fetchSemanticsNode().boundsInRoot
                        assertEquals(route.bottom, footer.top, 0.5f)
                    }
                }
            }
    }

    private fun ComposeUiTest.openDrawer() {
        onNodeWithTag("main-navigation-open").performClick()
        onNodeWithTag("main-navigation-drawer").assertIsDisplayed()
    }

    private fun ComposeUiTest.assertDrawerClosed() {
        onNodeWithTag("main-navigation-drawer").assertDoesNotExist()
        onNodeWithTag("main-navigation-close").assertDoesNotExist()
    }
}

@Composable
private fun NavigationFixture(
    route: SharedRoute = SharedRoute.Home,
    onRouteSelected: (SharedRoute) -> Unit = {},
    systemInsets: WindowInsets = WindowInsets(0),
    keyboardInsets: WindowInsets = WindowInsets(0),
    footerHeight: androidx.compose.ui.unit.Dp = 64.dp,
    showFooter: Boolean = true,
    content: @Composable () -> Unit = { Text(route.name, modifier = Modifier.testTag("route-${route.name}")) },
) {
    CompositionLocalProvider(LocalNaviampTooltipsEnabled provides false) {
        MaterialTheme {
            NaviampMainScaffold(
                selectedRoute = route, colors = NaviampColors.Dark, onRouteSelected = onRouteSelected,
                systemInsets = systemInsets, keyboardInsets = keyboardInsets,
                miniPlayer = {
                    if (showFooter) Box(Modifier.fillMaxWidth().height(footerHeight)) {
                        Button(onClick = {}, modifier = Modifier.testTag("footer-action")) { Text("Playback") }
                    }
                },
                content = content,
            )
        }
    }
}
