package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NaviampLibrarySearchResultsUiTest {
    @Test fun artistsKeepAccentedAndAliasMatchesFromTheServer() = verifyMatches(NaviampLibraryView.Artists)
    @Test fun albumsKeepAccentedAndMetadataMatchesFromTheServer() = verifyMatches(NaviampLibraryView.Albums)
    @Test fun songsKeepAccentedAndMetadataMatchesFromTheServer() = verifyMatches(NaviampLibraryView.Songs)

    @Test fun favoritesToggleKeepsServerAliasesAndDropsUnfavoritedRows() = runComposeUiTest {
        val screen = mutableStateOf(NaviampLibraryScreenUi(
            supportsArtistFavorites = true,
            artists = NaviampLibraryCatalogUi(query = "Rufus", resultsQuery = "Rufus", items = listOf(
                SharedMediaItemUi("alias", "Server alias match", "Artist", favoriteActive = true),
                SharedMediaItemUi("literal", "Rufus Thomas", "Artist"),
            )),
        ))
        var loads = 0
        setContent { FavoritesFixture(screen, onLoadMore = { loads++ }) }
        onNodeWithText("Favorites").assertIsNotSelected().performClick()
        onNodeWithText("Favorites").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        onNodeWithText("Server alias match").assertIsDisplayed()
        onNodeWithText("Rufus Thomas").assertDoesNotExist()
        runOnIdle { loads = 0 }
        runOnIdle {
            screen.value = screen.value.copy(artists = screen.value.artists.copy(items =
                screen.value.artists.items.map { it.copy(favoriteActive = false) }))
        }
        onNodeWithText("Server alias match").assertDoesNotExist()
        onNodeWithText("No library artists match.").assertIsDisplayed()
        runOnIdle {
            assertEquals(0, loads)
            screen.value = screen.value.copy(artists = screen.value.artists.copy(query = "", resultsQuery = ""))
        }
        onNodeWithText("No favorite artists yet.").assertIsDisplayed()
        onNodeWithText("Favorites").performClick().assertIsNotSelected()
        onNodeWithText("Server alias match").assertIsDisplayed()
    }

    @Test fun favoritesLoadingAndFailureNeverReportAnEmptyLibraryOrLoadMore() = runComposeUiTest {
        val screen = mutableStateOf(NaviampLibraryScreenUi(
            supportsArtistFavorites = true, artistFavoritesOnly = true, artistFavoritesLoading = true,
        ))
        var loads = 0
        setContent { FavoritesFixture(screen, onLoadMore = { loads++ }) }
        onNodeWithText("Loading artists…").assertIsDisplayed()
        onNodeWithText("No favorite artists yet.").assertDoesNotExist()
        onNodeWithText("No library artists found.").assertDoesNotExist()
        runOnIdle { screen.value = screen.value.copy(artistFavoritesLoading = false, artistFavoritesFailed = true) }
        onNodeWithText("Could not load favorite artists. Refresh to try again.").assertIsDisplayed()
        onNodeWithText("No favorite artists yet.").assertDoesNotExist()
        runOnIdle { screen.value = screen.value.copy(artistFavoritesFailed = false) }
        onNodeWithText("No favorite artists yet.").assertIsDisplayed()
        runOnIdle {
            screen.value = screen.value.copy(artists = NaviampLibraryCatalogUi(items = listOf(
                SharedMediaItemUi("favorite", "Favorite artist", "Artist", favoriteActive = true),
            )))
        }
        onNodeWithText("Favorite artist").assertIsDisplayed()
        runOnIdle { assertEquals(0, loads) }
    }

    @Test fun favoritesToggleAppearsOnlyForSupportedArtists() = runComposeUiTest {
        val screen = mutableStateOf(NaviampLibraryScreenUi())
        setContent { FavoritesFixture(screen) }
        onNodeWithText("Favorites").assertDoesNotExist()
        runOnIdle { screen.value = screen.value.copy(supportsArtistFavorites = true) }
        onNodeWithText("Favorites").assertIsDisplayed()
        onNodeWithText("Albums").performClick()
        onNodeWithText("Favorites").assertDoesNotExist()
        onNodeWithText("Songs").performClick()
        onNodeWithText("Favorites").assertDoesNotExist()
        onNodeWithText("Artists").performClick()
        onNodeWithText("Favorites").assertIsDisplayed()
    }

    @Composable
    private fun FavoritesFixture(screen: MutableState<NaviampLibraryScreenUi>, onLoadMore: () -> Unit = {}) {
        Box(Modifier.height(600.dp)) {
            NaviampLibraryContent(NaviampColors(), screen.value,
                NaviampLibraryActions(
                    onViewChanged = { screen.value = screen.value.copy(selectedView = it) },
                    onArtistFavoritesOnlyChanged = { screen.value = screen.value.copy(artistFavoritesOnly = it) },
                    onQueryChanged = {}, onRefresh = {}, onLoadMore = onLoadMore,
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
