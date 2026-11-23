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
import app.naviamp.domain.settings.HomeSectionPageLayout
import app.naviamp.domain.settings.InterfaceLanguage
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

    @Test fun narrowCollectionLayoutIconsSwitchTheRenderedLayout() = verifyCollectionLayout(320, InterfaceLanguage.English)
    @Test fun wideCollectionLayoutIconsSwitchTheRenderedLayout() = verifyCollectionLayout(800, InterfaceLanguage.English)
    @Test fun collectionLayoutIconsUseLocalizedAccessibleNames() = verifyCollectionLayout(320, InterfaceLanguage.German)

    private fun verifyCollectionLayout(width: Int, language: InterfaceLanguage) = runComposeUiTest {
        val page = mutableStateOf(SharedHomeCollectionPageUi(SharedHomeCollectionSectionUi(
            id = "albums", title = "Recent albums", items = (1..2).map { index ->
                SharedHomeCollectionItemUi(SharedMediaItemUi("album-$index", "Album $index", "Artist"),
                    SharedMediaItemKind.Album, action = SharedHomeCollectionItemAction.OpenAlbum)
            },
        )))
        val changes = mutableListOf<Pair<String, HomeSectionPageLayout>>()
        val selections = mutableListOf<String>()
        var pixelsPerDp = 1f
        val localeEffect = createNaviampLocaleEffect()
        setContent {
            NaviampLocaleEnvironment(language, localeEffect) {
                HeaderViewport(width, 1f) {
                    pixelsPerDp = LocalDensity.current.density
                    SharedHomeRoute(NaviampColors(), NaviampHomeScreenUi(collectionPage = page.value),
                        NaviampHomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, { id, layout ->
                            changes += id to layout
                            page.value = page.value.copy(layout = layout)
                        }), NaviampMediaActions(onTrackAction = {}, onMediaItemAction = { selections += it.item.id }),
                        DiscoverySamplePlaybackUi())
                }
            }
        }
        val list = onNodeWithContentDescription(if (language == InterfaceLanguage.German) "Liste" else "List")
        val grid = onNodeWithContentDescription(if (language == InterfaceLanguage.German) "Raster" else "Grid")
        val bounds = listOf(list, grid).map { node ->
            node.assertIsDisplayed().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            node.fetchSemanticsNode().boundsInRoot.also { rect ->
                assertTrue(rect.width >= 48f * pixelsPerDp - 1f && rect.height >= 48f * pixelsPerDp - 1f)
                assertTrue(rect.left >= 0f && rect.right <= width * pixelsPerDp + 1f)
            }
        }
        assertTrue(!overlaps(bounds[0], bounds[1]))
        fun verifyItemsAreInGrid(inGrid: Boolean) {
            val first = onNodeWithText("Album 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val second = onNodeWithText("Album 2").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            if (inGrid) {
                assertEquals(first.top, second.top, 1f)
                assertTrue(second.left > first.right)
            } else {
                assertTrue(second.top > first.bottom)
                assertEquals(first.left, second.left, 1f)
            }
        }
        grid.assertIsSelected()
        list.assertIsNotSelected()
        verifyItemsAreInGrid(true)
        list.performTouchInput { click() }
        list.assertIsSelected()
        grid.assertIsNotSelected()
        verifyItemsAreInGrid(false)
        grid.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        grid.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        grid.assertIsSelected()
        list.assertIsNotSelected()
        verifyItemsAreInGrid(true)
        onNodeWithText("Album 2").performClick()
        runOnIdle {
            assertEquals(listOf("albums" to HomeSectionPageLayout.List, "albums" to HomeSectionPageLayout.Grid), changes)
            assertEquals(listOf("album-2"), selections)
            page.value = page.value.copy(section = page.value.section.copy(
                supportedPageLayouts = setOf(HomeSectionPageLayout.List)), layout = HomeSectionPageLayout.List)
        }
        grid.assertDoesNotExist()
        list.assertIsDisplayed().assertIsSelected()
        verifyItemsAreInGrid(false)
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
