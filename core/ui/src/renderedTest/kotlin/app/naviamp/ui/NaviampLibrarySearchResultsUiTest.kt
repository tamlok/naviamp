package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalTestApi::class)
class NaviampLibrarySearchResultsUiTest {
    @Test fun artistsKeepAccentedAndAliasMatchesFromTheServer() = verifyMatches(NaviampLibraryView.Artists)
    @Test fun albumsKeepAccentedAndMetadataMatchesFromTheServer() = verifyMatches(NaviampLibraryView.Albums)
    @Test fun songsKeepAccentedAndMetadataMatchesFromTheServer() = verifyMatches(NaviampLibraryView.Songs)

    @Test fun artistFavoritesKeepServerAliasesAndDropRemovedFavorites() = verifyFavorites(NaviampLibraryView.Artists)
    @Test fun albumFavoritesKeepServerAliasesAndDropRemovedFavorites() = verifyFavorites(NaviampLibraryView.Albums)
    @Test fun songFavoritesKeepServerAliasesAndDropRemovedFavorites() = verifyFavorites(NaviampLibraryView.Songs)

    private fun verifyFavorites(view: NaviampLibraryView) = runComposeUiTest {
        val screen = mutableStateOf(favoritesScreen().copy(selectedView = view))
        var loads = 0
        setContent { FavoritesFixture(screen, onLoadMore = { loads++ }) }
        onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Favorites").assertIsNotSelected().performClick().assertIsSelected()
        onNodeWithText("$view server alias").assertIsDisplayed()
        onNodeWithText("$view ordinary").assertDoesNotExist()
        runOnIdle { loads = 0 }
        runOnIdle {
            val catalog = screen.value.selectedCatalog
            screen.value = screen.value.withSelectedCatalog(catalog.copy(
                items = catalog.items.map { it.copy(favoriteActive = false) },
                tracks = catalog.tracks.map { it.copy(favoriteActive = false) },
            ))
        }
        onNodeWithText("$view server alias").assertDoesNotExist()
        onNodeWithText("No library ${view.name.lowercase()} match").assertIsDisplayed()
        runOnIdle {
            assertEquals(0, loads)
            screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(query = "", resultsQuery = ""))
        }
        onNodeWithText("No favorites yet").assertIsDisplayed()
        onNodeWithContentDescription("Favorites").performClick().assertIsNotSelected()
        onNodeWithText("$view server alias").assertIsDisplayed()
        onNodeWithText("$view ordinary").assertIsDisplayed()
    }

    @Test fun oneHeartFiltersAllTabsWithoutChangingTheirQueriesOrBrowseRows() = runComposeUiTest {
        val original = favoritesScreen()
        val screen = mutableStateOf(original)
        setContent { FavoritesFixture(screen) }
        onNodeWithContentDescription("Favorites").performClick()
        NaviampLibraryView.entries.forEach { view ->
            onNodeWithText(view.name).performClick()
            onNodeWithContentDescription("Favorites").assertIsDisplayed().assertIsSelected()
            onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("$view server alias").assertIsDisplayed()
            onNodeWithText("$view ordinary").assertDoesNotExist()
            runOnIdle { assertEquals(original.copy(selectedView = view, favoritesOnly = true), screen.value) }
        }
        onNodeWithContentDescription("Favorites").performClick()
        NaviampLibraryView.entries.forEach { view ->
            onNodeWithText(view.name).performClick()
            onNodeWithContentDescription("Favorites").assertIsNotSelected()
            onNodeWithText("$view server alias").assertIsDisplayed()
            onNodeWithText("$view ordinary").assertIsDisplayed()
            runOnIdle { assertEquals(original.copy(selectedView = view), screen.value) }
        }
    }

    @Test fun favoritesLoadingAndFailureHideStaleRowsWithoutEmptyStatesOrLoadingMore() = runComposeUiTest {
        val screen = mutableStateOf(favoritesScreen().copy(favoritesOnly = true))
        var loads = 0
        setContent { FavoritesFixture(screen, onLoadMore = { loads++ }) }
        NaviampLibraryView.entries.forEach { view ->
            runOnIdle {
                screen.value = screen.value.copy(selectedView = view)
                    .withSelectedCatalog(favoritesCatalog(view).copy(query = "", resultsQuery = "", favoritesLoading = true))
            }
            onNodeWithText("Loading ${view.name.lowercase()}…").assertIsDisplayed()
            onNodeWithText("No favorites yet").assertDoesNotExist()
            onNodeWithText("No library ${view.name.lowercase()} found").assertDoesNotExist()
            onNodeWithText("$view server alias").assertDoesNotExist()
            onNodeWithText("$view ordinary").assertDoesNotExist()
            runOnIdle {
                screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(
                    favoritesLoading = false, favoritesFailed = true,
                ))
            }
            onNodeWithText("Could not load favorites. Refresh to try again.").assertIsDisplayed()
            onNodeWithText("No favorites yet").assertDoesNotExist()
            onNodeWithText("$view server alias").assertDoesNotExist()
            onNodeWithText("$view ordinary").assertDoesNotExist()
            runOnIdle {
                screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(
                    favoritesFailed = false, items = emptyList(), tracks = emptyList(),
                ))
            }
            onNodeWithText("No favorites yet").assertIsDisplayed()
            runOnIdle { assertEquals(0, loads) }
        }
    }

    @Test fun unsupportedTabsKeepTheHeartVisibleAndCanTurnOffTheGlobalFilter() = runComposeUiTest {
        val screen = mutableStateOf(favoritesScreen().copy(
            albums = favoritesCatalog(NaviampLibraryView.Albums).copy(supportsFavorites = false),
            songs = favoritesCatalog(NaviampLibraryView.Songs).copy(supportsFavorites = false),
        ))
        var loads = 0
        setContent { FavoritesFixture(screen, onLoadMore = { loads++ }) }
        onNodeWithText("Albums").performClick()
        onNodeWithContentDescription("Favorites").assertIsDisplayed().assertIsNotEnabled().assertIsNotSelected()
        onNodeWithText("Artists").performClick()
        onNodeWithContentDescription("Favorites").assertIsEnabled().performClick()
        runOnIdle { loads = 0 }
        listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs).forEach { view ->
            onNodeWithText(view.name).performClick()
            onNodeWithContentDescription("Favorites").assertIsEnabled().assertIsSelected()
            onNodeWithText("Favorites are not supported by this source.").assertIsDisplayed()
            onNodeWithText("$view server alias").assertDoesNotExist()
            onNodeWithText("$view ordinary").assertDoesNotExist()
            onNodeWithText("No favorites yet").assertDoesNotExist()
        }
        runOnIdle { assertEquals(0, loads) }
        onNodeWithContentDescription("Favorites").performClick().assertIsNotSelected().assertIsNotEnabled()
        onNodeWithText("Songs ordinary").assertIsDisplayed()
        onNodeWithText("Favorites are not supported by this source.").assertDoesNotExist()
        onNodeWithText("Artists").performClick()
        onNodeWithContentDescription("Favorites").assertIsEnabled().assertIsNotSelected()
        onNodeWithText("Artists ordinary").assertIsDisplayed()
    }

    @Test fun narrowHeaderHasAccessible48DpHeartBeforeMoreActions() = verifyHeartHeader(320)
    @Test fun wideHeaderHasAccessible48DpHeartBeforeMoreActions() = verifyHeartHeader(800)

    private fun verifyHeartHeader(width: Int) = runComposeUiTest {
        val screen = mutableStateOf(favoritesScreen())
        setContent { Box(Modifier.width(width.dp)) { FavoritesFixture(screen) } }
        val heart = onNodeWithContentDescription("Favorites").assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val heartBounds = heart.fetchSemanticsNode().boundsInRoot
        val overflowBounds = onAllNodesWithContentDescription("More actions").fetchSemanticsNodes()
            .minBy { it.boundsInRoot.top }.boundsInRoot
        val searchBounds = onNodeWithContentDescription("Back to search").fetchSemanticsNode().boundsInRoot
        assertTrue(heartBounds.left >= searchBounds.right)
        assertTrue(overflowBounds.left >= heartBounds.right)
        assertEquals(searchBounds.center.y, heartBounds.center.y)
        assertTrue(heartBounds.bottom <= onNodeWithText("Artists").fetchSemanticsNode().boundsInRoot.top)
        onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
        heart.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        heart.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        heart.assertIsSelected()
        runOnIdle { assertTrue(screen.value.favoritesOnly) }
    }

    @Test fun heartUsesTheExistingFavoritesTooltip() = runComposeUiTest {
        mainClock.autoAdvance = false
        val timeSource = TestTimeSource()
        val screen = mutableStateOf(favoritesScreen())
        setContent {
            val native = LocalWindowInfo.current
            val focused = remember(native) { object : WindowInfo by native { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalWindowInfo provides focused, LocalNaviampTooltipTimeSource provides timeSource,
                LocalNaviampTooltipsEnabled provides true) {
                FavoritesFixture(screen)
            }
        }
        mainClock.advanceTimeBy(48)
        onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Favorites").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(48)
        timeSource += 500.milliseconds
        mainClock.advanceTimeBy(500)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Favorites").assertIsDisplayed()
        onNodeWithContentDescription("Favorites").assertIsNotSelected()
    }

    private fun favoritesScreen() = NaviampLibraryScreenUi(
        artists = favoritesCatalog(NaviampLibraryView.Artists),
        albums = favoritesCatalog(NaviampLibraryView.Albums),
        songs = favoritesCatalog(NaviampLibraryView.Songs),
    )

    private fun favoritesCatalog(view: NaviampLibraryView) = NaviampLibraryCatalogUi(
        query = "Rufus $view", resultsQuery = "Rufus $view", supportsFavorites = true,
        items = if (view == NaviampLibraryView.Songs) emptyList() else listOf(
            SharedMediaItemUi("alias", "$view server alias", "Metadata match", favoriteActive = true),
            SharedMediaItemUi("ordinary", "$view ordinary", "Metadata match"),
        ),
        tracks = if (view != NaviampLibraryView.Songs) emptyList() else listOf(
            SharedTrackRowUi("alias", "$view server alias", "Metadata match", favoriteActive = true),
            SharedTrackRowUi("ordinary", "$view ordinary", "Metadata match"),
        ),
    )

    private fun NaviampLibraryScreenUi.withSelectedCatalog(catalog: NaviampLibraryCatalogUi) = when (selectedView) {
        NaviampLibraryView.Artists -> copy(artists = catalog)
        NaviampLibraryView.Albums -> copy(albums = catalog)
        NaviampLibraryView.Songs -> copy(songs = catalog)
    }

    @Composable
    private fun FavoritesFixture(screen: MutableState<NaviampLibraryScreenUi>, onLoadMore: () -> Unit = {}) {
        Box(Modifier.height(600.dp)) {
            NaviampLibraryContent(NaviampColors(), screen.value,
                NaviampLibraryActions(
                    onViewChanged = { screen.value = screen.value.copy(selectedView = it) },
                    onFavoritesOnlyChanged = { screen.value = screen.value.copy(favoritesOnly = it) },
                    onQueryChanged = { screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(query = it)) },
                    onRefresh = {}, onLoadMore = onLoadMore,
                    onJumpToLetter = {}, onTrackAction = {},
                ), NaviampMediaActions(onTrackAction = {}, onMediaItemAction = {}),
                rememberNaviampLibraryViewportState())
        }
    }

    private fun verifyMatches(view: NaviampLibraryView) = runComposeUiTest {
        val catalog = mutableStateOf(NaviampLibraryCatalogUi(query = "Rufus", resultsQuery = "Rufus",
            items = listOf(SharedMediaItemUi("accented", "RÜFÜS DU SOL", "Artist"),
                SharedMediaItemUi("literal", "Rufus Thomas", "Artist"),
                SharedMediaItemUi("alias", "Server alias match", "Metadata match")),
            tracks = listOf(SharedTrackRowUi("accented", "Innerbloom", "RÜFÜS DU SOL"),
                SharedTrackRowUi("literal", "Rufus song", "Artist"),
                SharedTrackRowUi("alias", "Server alias match", "Metadata match")),
        ))
        setContent {
            Box(Modifier.height(600.dp)) {
                NaviampLibraryContent(NaviampColors(), NaviampLibraryScreenUi(selectedView = view,
                    artists = catalog.value, albums = catalog.value, songs = catalog.value),
                    NaviampLibraryActions(onViewChanged = {}, onQueryChanged = {}, onRefresh = {},
                        onLoadMore = {}, onJumpToLetter = {}, onTrackAction = {}),
                    NaviampMediaActions(onTrackAction = {}, onMediaItemAction = {}),
                    rememberNaviampLibraryViewportState())
            }
        }
        onNodeWithText("RÜFÜS DU SOL").assertIsDisplayed()
        onNodeWithText("Server alias match").assertIsDisplayed()
        onNodeWithText("Loading artists…").assertDoesNotExist()
        runOnIdle { catalog.value = catalog.value.copy(query = "Different", searching = true) }
        onNodeWithText("RÜFÜS DU SOL").assertDoesNotExist()
        onNodeWithText("Server alias match").assertDoesNotExist()
        runOnIdle { catalog.value = catalog.value.copy(resultsQuery = "Different", searching = false) }
        // Matching may depend on metadata that is not present in the display row.
        onNodeWithText("RÜFÜS DU SOL").assertIsDisplayed()
        onNodeWithText("Server alias match").assertIsDisplayed()
    }
}
