package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.NaviampForestTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemeSettings
import app.naviamp.domain.smartplaylist.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared Android/JVM tests: dimensions are measured from rendered semantics, not source text. */
@OptIn(ExperimentalTestApi::class)
class NaviampPageHeaderUiTest {
    @Test fun narrowHeaderKeepsFourActionsReachable() = verifyLayout(320, 1f)
    @Test fun largeFontNarrowHeaderKeepsFourActionsReachable() = verifyLayout(320, 2f)
    @Test fun wideHeaderKeepsTitleAndFourActionsOnOneRow() = verifyLayout(800, 1f)

    @Test fun narrowHomeThemeActionCyclesModesBeforeOverflow() = verifyHomeThemeAction(320, 1f)
    @Test fun wideHomeThemeActionCyclesModesBeforeOverflow() = verifyHomeThemeAction(800, 1f)
    @Test fun largeFontHomeThemeActionRemainsReachable() = verifyHomeThemeAction(320, 2f)

    private fun verifyHomeThemeAction(width: Int, fontScale: Float) = runComposeUiTest {
        val initial = InterfaceSettings(keepScreenAwake = true, showDesktopTooltips = false,
            theme = NaviampThemeSettings(NaviampThemeMode.Light, NaviampForestTheme.id))
        val settings = mutableStateOf(initial)
        val changes = mutableListOf<InterfaceSettings>()
        var refreshes = 0
        var pixelsPerDp = 1f
        setContent {
            HeaderViewport(width, fontScale) {
                pixelsPerDp = LocalDensity.current.density
                SharedHomeRoute(
                    colors = NaviampColors(),
                    home = NaviampHomeScreenUi(),
                    actions = NaviampHomeActions({ refreshes++ }, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }),
                    mediaActions = NaviampMediaActions({}, {}),
                    discoveryPlayback = DiscoverySamplePlaybackUi(),
                    headerActions = {
                        NaviampThemeModeAction(NaviampColors(), settings.value) {
                            changes += it
                            settings.value = it
                        }
                    },
                )
            }
        }
        val action = onNodeWithContentDescription("Color mode").assertIsDisplayed().assertHasClickAction()
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val bounds = action.fetchSemanticsNode().boundsInRoot
        val more = onNodeWithContentDescription("More actions").assertIsDisplayed()
        val moreBounds = more.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 48f * pixelsPerDp - 1f && bounds.height >= 48f * pixelsPerDp - 1f)
        assertTrue(bounds.left >= 0f && moreBounds.right <= width * pixelsPerDp + 1f)
        assertTrue(bounds.right <= moreBounds.left && abs(bounds.center.y - moreBounds.center.y) <= 1f)
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Light"))
        action.performTouchInput { click() }
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dark"))
        action.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        action.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "System"))
        action.performTouchInput { click() }
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Light"))
        runOnIdle {
            assertEquals(listOf(NaviampThemeMode.Dark, NaviampThemeMode.System, NaviampThemeMode.Light)
                .map { initial.copy(theme = initial.theme.copy(mode = it)) }, changes)
            settings.value = initial.copy(theme = initial.theme.copy(mode = NaviampThemeMode.System))
        }
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "System"))
        action.performTouchInput { click() }
        runOnIdle { assertEquals(initial, settings.value) }
        more.performTouchInput { click() }
        onNodeWithText("Refresh").assertIsDisplayed().performTouchInput { click() }
        runOnIdle { assertEquals(1, refreshes) }
    }

    private fun verifyLayout(width: Int, fontScale: Float) = runComposeUiTest {
        val clicks = IntArray(3)
        var pixelsPerDp = 1f
        setContent {
            HeaderViewport(width, fontScale) {
                pixelsPerDp = LocalDensity.current.density
                NaviampPageHeader("Playlists with a long localized title", NaviampColors(), Modifier.testTag("header")) {
                    repeat(3) { index ->
                        NaviampPageHeaderAction(NaviampIcons.Plus, "Header action $index", NaviampColors()) {
                            clicks[index]++
                        }
                    }
                    NaviampPageHeaderOverflow(NaviampColors(), listOf(
                        NaviampRowMenuItem("Header menu action", NaviampIcons.Refresh, {}),
                    ))
                }
            }
        }
        val title = onNodeWithText("Playlists with a long localized title").assertIsDisplayed()
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val headerBounds = onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        assertEquals(width * pixelsPerDp, headerBounds.width, 1f)
        assertTrue(titleBounds.width >= 120f * pixelsPerDp - 1f, "Title lost its minimum readable width")
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().getLineBottom(0) <= titleBounds.height + 1f, "Enlarged title is vertically clipped")
        val descriptions = (0..2).map { "Header action $it" } + "More actions"
        val bounds = descriptions.map { description ->
            val node = onNodeWithContentDescription(description).assertIsDisplayed().assertHasClickAction()
            node.fetchSemanticsNode().boundsInRoot.also { action ->
                assertTrue(action.width >= 48f * pixelsPerDp - 1f && action.height >= 48f * pixelsPerDp - 1f,
                    "$description has a touch target smaller than 48dp: $action")
                assertTrue(action.left >= headerBounds.left - 1f && action.right <= headerBounds.right + 1f,
                    "$description is horizontally clipped: $action in $headerBounds")
                assertTrue(action.top >= headerBounds.top - 1f && action.bottom <= headerBounds.bottom + 1f,
                    "$description is vertically clipped: $action in $headerBounds")
                assertTrue(!overlaps(titleBounds, action), "Title overlaps $description")
                if (width == 800) assertTrue(abs(titleBounds.center.y - action.center.y) <= 1f,
                    "Wide header unexpectedly wrapped $description")
            }
        }
        bounds.forEachIndexed { index, first ->
            bounds.drop(index + 1).forEach { second -> assertTrue(!overlaps(first, second), "Action targets overlap") }
        }
        repeat(3) { onNodeWithContentDescription("Header action $it").performTouchInput { click() } }
        runOnIdle { clicks.forEach { assertEquals(1, it) } }
        onNodeWithContentDescription("More actions").performTouchInput { click() }
        onNodeWithText("Header menu action").assertIsDisplayed()
    }

    @Test fun playlistSortExposesSelectionAndSupportsKeyboardActivation() = runComposeUiTest {
        val screen = mutableStateOf(NaviampPlaylistsScreenUi())
        val selections = mutableListOf<SharedPlaylistSortMode>()
        setContent {
            MaterialTheme {
                NaviampPlaylistsContent(NaviampColors(), screen.value,
                    NaviampPlaylistsActions({}, { mode ->
                        selections += mode
                        screen.value = screen.value.copy(sortMode = mode)
                    }, headerTestSmartActions()), NaviampMediaActions({}, {}), emptyList())
            }
        }
        val alphabetical = onNodeWithContentDescription("A–Z")
        val recent = onNodeWithContentDescription("Recently Played")
        recent.assertIsSelected()
        alphabetical.assertIsNotSelected().assertIsEnabled()
        alphabetical.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        alphabetical.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        alphabetical.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        alphabetical.assertIsSelected()
        recent.assertIsNotSelected()
        recent.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        recent.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        recent.assertIsSelected()
        alphabetical.assertIsNotSelected()
        runOnIdle { assertEquals(listOf(SharedPlaylistSortMode.Alphabetical, SharedPlaylistSortMode.RecentlyPlayed), selections) }
    }

    @Test fun disabledHeaderActionDoesNotInvokeCallback() = runComposeUiTest {
        var calls = 0
        setContent {
            MaterialTheme {
                NaviampPageHeader("Library", NaviampColors()) {
                    NaviampPageHeaderAction(NaviampIcons.Refresh, "Disabled refresh", NaviampColors(), enabled = false) { calls++ }
                }
            }
        }
        val disabled = onNodeWithContentDescription("Disabled refresh").assertIsNotEnabled().assertIsDisplayed()
        disabled.performTouchInput { click() }
        onRoot().performKeyInput { pressKey(Key.Tab); pressKey(Key.Enter) }
        runOnIdle { assertEquals(0, calls) }
    }

    @Test fun secondClickOnHeaderOverflowClosesTheMenuAndActionDispatchesOnce() = runComposeUiTest {
        var calls = 0
        setContent {
            MaterialTheme {
                NaviampPageHeader("Home", NaviampColors()) {
                    NaviampPageHeaderOverflow(NaviampColors(), listOf(
                        NaviampRowMenuItem("Refresh header", NaviampIcons.Refresh, { calls++ }),
                    ))
                }
            }
        }
        val trigger = onNodeWithContentDescription("More actions")
        trigger.performTouchInput { click() }
        onNodeWithText("Refresh header").assertIsDisplayed()
        trigger.performTouchInput { click() }
        onNodeWithText("Refresh header").assertDoesNotExist()
        runOnIdle { assertEquals(0, calls) }
        trigger.performTouchInput { click() }
        onNodeWithText("Refresh header").performTouchInput { click() }
        onNodeWithText("Refresh header").assertDoesNotExist()
        runOnIdle { assertEquals(1, calls) }
    }
}

/** Fit a real 800dp layout into small test activities without platform-specific window sizing. */
@Composable
private fun HeaderViewport(width: Int, fontScale: Float, content: @Composable () -> Unit) {
    BoxWithConstraints {
        val physicalDensity = LocalDensity.current.density
        val density = min(physicalDensity, maxWidth.value * physicalDensity / width)
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            MaterialTheme { Box(Modifier.width(width.dp)) { content() } }
        }
    }
}

private fun overlaps(first: Rect, second: Rect): Boolean =
    first.left < second.right && second.left < first.right && first.top < second.bottom && second.top < first.bottom

private fun headerTestSmartActions() = NaviampSmartPlaylistActions(
    onSave = {}, onUpdate = { _, _ -> }, onSaveWithPassword = { _, _ -> },
    onUpdateWithPassword = { _, _, _ -> }, onLoad = { headerTestDefinition() },
    onLoadWithPassword = { _, _ -> headerTestDefinition() },
)

private fun headerTestDefinition() = SmartPlaylistDefinition(
    name = "Fixture",
    rules = listOf(SmartPlaylistCondition(SmartPlaylistOperator.Is, SmartPlaylistFields.Artist,
        SmartPlaylistValue.Text("Fixture artist"))),
)
