package app.naviamp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.HomeSectionLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampPullToRefreshUiTest {
    @Test fun homeRefreshesFromEmptySpace() = verifyScreen(RefreshTestDestination.Home)
    @Test fun playlistsRefreshFromEmptySpace() = verifyScreen(RefreshTestDestination.Playlists)
    @Test fun artistsRefreshFromEmptySpace() = verifyScreen(RefreshTestDestination.Artists)
    @Test fun albumsRefreshFromEmptySpace() = verifyScreen(RefreshTestDestination.Albums)
    @Test fun songsRefreshFromEmptySpace() = verifyScreen(RefreshTestDestination.Songs)
    @Test fun radioRefreshesFromEmptySpace() = verifyScreen(RefreshTestDestination.Radio)

    private fun verifyScreen(destination: RefreshTestDestination) = runComposeUiTest {
        var refreshes = 0
        var selections = 0
        val refreshing = mutableStateOf(false)
        val populated = mutableStateOf(false)
        setContent {
            Box(Modifier.size(700.dp, 600.dp).testTag("screen")) {
                RefreshTestScreen(destination, refreshing.value, if (populated.value) 1 else 0,
                    onRefresh = { refreshes++; refreshing.value = true }, onSelect = { selections++ })
            }
        }
        val surface = onNodeWithTag("screen")
        surface.performTouchInput {
            down(Offset(width * 0.8f, height * 0.35f))
            moveBy(Offset(0f, height * 0.45f), delayMillis = 400)
        }
        runOnIdle { assertEquals(0, refreshes) }
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        surface.performTouchInput { up() }
        runOnIdle { assertEquals(1, refreshes) }
        surface.performTouchInput { swipeDown() }
        surface.pullWithMouse()
        runOnIdle { assertEquals(1, refreshes); refreshing.value = false }
        surface.pullWithMouse(release = false)
        runOnIdle { assertEquals(1, refreshes) }
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        surface.performMouseInput { release() }
        runOnIdle { assertEquals(2, refreshes); refreshing.value = false; populated.value = true }

        // Ordinary mouse clicks still select; starting a pull on a row must cancel its click.
        onNodeWithText("Item 0").performMouseInput { click() }
        runOnIdle { assertEquals(1, selections) }
        onNodeWithText("Item 0").performMouseInput {
            moveTo(center); press(); moveBy(Offset(0f, 260.dp.toPx()), delayMillis = 400); release()
        }
        runOnIdle { assertEquals(3, refreshes); assertEquals(1, selections) }
    }

    @Test fun shortReversedHorizontalAndSecondaryDragsDoNotRefresh() = runComposeUiTest {
        var refreshes = 0
        setContent { RefreshGestureSurface { refreshes++ } }
        val surface = onNodeWithTag("gesture")
        surface.performMouseInput {
            moveTo(Offset(100f, 60f)); press(); moveBy(Offset(0f, 30f)); release()
        }
        surface.performMouseInput {
            moveTo(Offset(100f, 60f)); press()
            moveBy(Offset(0f, 260.dp.toPx()), delayMillis = 300)
            moveTo(Offset(100f, 60f), delayMillis = 300); release()
        }
        surface.performMouseInput {
            moveTo(Offset(100f, 60f)); press(); moveBy(Offset(150f, 20f)); release()
        }
        surface.performMouseInput {
            moveTo(Offset(100f, 60f)); press(MouseButton.Secondary)
            moveBy(Offset(0f, 260.dp.toPx())); release(MouseButton.Secondary)
        }
        runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun becomingBusyCancelsAnInProgressMousePull() = runComposeUiTest {
        var refreshes = 0
        val refreshing = mutableStateOf(false)
        setContent { RefreshGestureSurface(refreshing.value) { refreshes++ } }
        val surface = onNodeWithTag("gesture")
        surface.pullWithMouse(release = false)
        runOnIdle { refreshing.value = true }
        surface.performMouseInput { moveBy(Offset(0f, 5f)); release() }
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        runOnIdle { refreshing.value = false }
        surface.performMouseInput { moveTo(center); click() }
        runOnIdle { assertEquals(0, refreshes) }
        surface.pullWithMouse()
        runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun wheelAndScrolledDownMouseDragDoNotRefresh() = runComposeUiTest {
        var refreshes = 0
        lateinit var scrollState: ScrollState
        setContent {
            scrollState = rememberScrollState()
            MaterialTheme {
                NaviampPullToRefreshBox(false, { refreshes++ }, { !scrollState.canScrollBackward },
                    Modifier.size(500.dp, 600.dp).testTag("gesture")) {
                    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                        repeat(40) { Text("Row $it", Modifier.height(60.dp)) }
                    }
                }
            }
        }
        val surface = onNodeWithTag("gesture")
        surface.performMouseInput { moveTo(center); repeat(10) { scroll(-10f); advanceEventTime(100) } }
        runOnIdle { assertEquals(0, refreshes); assertEquals(0, scrollState.value) }
        surface.performMouseInput { moveTo(center); scroll(12f) }
        runOnIdle { assertTrue(scrollState.value > 0) }
        val beforePull = scrollState.value
        surface.pullWithMouse()
        runOnIdle { assertEquals(0, refreshes); assertEquals(beforePull, scrollState.value) }
        surface.performMouseInput { moveTo(center); scroll(12f) }
        runOnIdle { assertTrue(scrollState.value > beforePull); assertEquals(0, refreshes) }
    }

    private fun SemanticsNodeInteraction.pullWithMouse(release: Boolean = true) = performMouseInput {
        moveTo(Offset(width * 0.8f, height * 0.35f)); press()
        moveBy(Offset(0f, height * 0.45f), delayMillis = 400)
        if (release) release()
    }
}

@Composable
private fun RefreshGestureSurface(refreshing: Boolean = false, onRefresh: () -> Unit) {
    MaterialTheme {
        NaviampPullToRefreshBox(refreshing, onRefresh, { true },
            Modifier.size(500.dp, 600.dp).testTag("gesture")) {
            Box(Modifier.fillMaxSize().clickable {})
        }
    }
}

internal enum class RefreshTestDestination { Home, Playlists, Artists, Albums, Songs, Radio }

@Composable
internal fun RefreshTestScreen(
    destination: RefreshTestDestination,
    refreshing: Boolean,
    itemCount: Int,
    onRefresh: () -> Unit,
    onSelect: () -> Unit = {},
) {
    val colors = NaviampColors()
    val items = List(itemCount) { SharedMediaItemUi("$it", "Item $it", "Artist") }
    val tracks = List(itemCount) { SharedTrackRowUi("$it", "Item $it", "Artist") }
    val media = NaviampMediaActions({ onSelect() }, { onSelect() })
    MaterialTheme {
        when (destination) {
            RefreshTestDestination.Home -> SharedHomeRoute(colors,
                NaviampHomeScreenUi(refreshing = refreshing, content = SharedHomeUi(collectionSections =
                    if (items.isEmpty()) emptyList() else listOf(SharedHomeCollectionSectionUi(
                        "test", "Albums", items.map { SharedHomeCollectionItemUi(it, SharedMediaItemKind.Album,
                            action = SharedHomeCollectionItemAction.OpenAlbum) }, homeLayout = HomeSectionLayout.List,
                    )))),
                NaviampHomeActions(onRefresh, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }),
                media, DiscoverySamplePlaybackUi())
            RefreshTestDestination.Playlists -> NaviampPlaylistsContent(colors,
                NaviampPlaylistsScreenUi(playlists = items, refreshing = refreshing),
                NaviampPlaylistsActions(onRefresh, {}, NaviampSmartPlaylistActions(
                    onSave = {}, onUpdate = { _, _ -> }, onSaveWithPassword = { _, _ -> },
                    onUpdateWithPassword = { _, _, _ -> }, onLoad = { error("Not used") },
                    onLoadWithPassword = { _, _ -> error("Not used") },
                )), media, emptyList())
            RefreshTestDestination.Radio -> NaviampInternetRadioContent(colors,
                NaviampInternetRadioScreenUi(items.map { NaviampInternetRadioStationUi(it, "https://example.test") },
                    refreshing = refreshing), NaviampInternetRadioActions(onRefresh, { onSelect() }, {}))
            else -> {
                val view = when (destination) {
                    RefreshTestDestination.Artists -> NaviampLibraryView.Artists
                    RefreshTestDestination.Albums -> NaviampLibraryView.Albums
                    else -> NaviampLibraryView.Songs
                }
                val catalog = NaviampLibraryCatalogUi(items = items, tracks = tracks,
                    syncStatus = NaviampLibrarySyncStatusUi(isSyncing = refreshing))
                NaviampLibraryContent(colors, NaviampLibraryScreenUi(view, catalog, catalog, catalog),
                    NaviampLibraryActions({}, {}, onRefresh, {}, {}, onTrackAction = { onSelect() }),
                    media, rememberNaviampLibraryViewportState())
            }
        }
    }
}
