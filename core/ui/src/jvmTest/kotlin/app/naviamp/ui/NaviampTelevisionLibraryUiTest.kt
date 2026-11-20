package app.naviamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.LibraryAlbumSortOrder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampTelevisionLibraryUiTest {
    @Test fun favoritesHasRemoteNeighborsAndFiltersServerMatchesWithoutLoadingMore() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(screen().copy(
            artists = NaviampLibraryCatalogUi(supportsFavorites = true, query = "Rufus", resultsQuery = "Rufus", items = listOf(
                SharedMediaItemUi("plain", "Rufus Thomas", "Artist"),
                SharedMediaItemUi("alias", "Server alias match", "Artist", favoriteActive = true),
            ))))
        var loads = 0
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState(), onLoadMore = { loads++ }) }
        onNodeWithTag(viewTag(NaviampLibraryView.Artists)).performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag(viewTag(NaviampLibraryView.Songs)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Choose libraries").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Refresh").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
        val heart = onNodeWithContentDescription("Favorites").assertIsFocused().assertIsNotSelected()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertTrue(heart.fetchSemanticsNode().boundsInRoot.left >=
            onNodeWithText("Refresh").fetchSemanticsNode().boundsInRoot.right)
        heart.performKeyInput { pressKey(Key.Enter) }
        heart.assertIsFocused().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        onNodeWithTag(itemTag("plain")).assertDoesNotExist()
        onNodeWithTag(itemTag("alias")).assertIsDisplayed()
        runOnIdle { loads = 0 }
        heart.performKeyInput { pressKey(Key.DirectionRight) }.assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithText("Refresh").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        heart.assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag(itemTag("alias")).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        runOnIdle {
            assertEquals(0, loads)
            screen.value = screen.value.copy(artists = screen.value.artists.copy(items =
                screen.value.artists.items.map { it.copy(favoriteActive = false) }))
        }
        onNodeWithTag(itemTag("alias")).assertDoesNotExist()
        onNodeWithText("No library artists match.").assertIsDisplayed()
    }

    @Test fun favoritesStatusHidesStaleRowsAndNeverLoadsOrdinaryPagesOnAnyTab() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(favoritesScreen().copy(favoritesOnly = true))
        var loads = 0
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState(), onLoadMore = { loads++ }) }
        NaviampLibraryView.entries.forEach { view ->
            runOnIdle {
                screen.value = screen.value.copy(selectedView = view).withSelectedCatalog(
                    favoritesCatalog(view).copy(query = "", resultsQuery = "", favoritesLoading = true),
                )
            }
            onNodeWithText("Loading ${view.name.lowercase()}…").assertIsDisplayed()
            onNodeWithText("No favorites yet.").assertDoesNotExist()
            onNodeWithText("No library ${view.name.lowercase()} found.").assertDoesNotExist()
            onNodeWithTag(itemTag("$view-alias")).assertDoesNotExist()
            onNodeWithTag(itemTag("$view-ordinary")).assertDoesNotExist()
            runOnIdle {
                screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(
                    favoritesLoading = false, favoritesFailed = true,
                ))
            }
            onNodeWithText("Could not load favorites. Refresh to try again.").assertIsDisplayed()
            onNodeWithText("No favorites yet.").assertDoesNotExist()
            onNodeWithTag(itemTag("$view-alias")).assertDoesNotExist()
            onNodeWithTag(itemTag("$view-ordinary")).assertDoesNotExist()
            runOnIdle {
                screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(
                    favoritesFailed = false, items = emptyList(), tracks = emptyList(),
                ))
            }
            onNodeWithText("No favorites yet.").assertIsDisplayed()
            onNodeWithContentDescription("Favorites").assertIsDisplayed().assertIsSelected()
            runOnIdle { assertEquals(0, loads) }
        }
    }

    @Test fun globalHeartFiltersAllTabsAndRetainsQueriesThroughFavoriteRemovalAndToggleOff() = runDesktopComposeUiTest(1280, 720) {
        val original = favoritesScreen()
        val screen = mutableStateOf(original)
        var loads = 0
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState(), onLoadMore = { loads++ }) }
        onNodeWithContentDescription("Favorites").performClick().assertIsSelected()
        runOnIdle { loads = 0 }
        NaviampLibraryView.entries.forEach { view ->
            onNodeWithTag(viewTag(view)).performClick()
            onNodeWithContentDescription("Favorites").assertIsSelected()
            onNodeWithText("Favorites", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag(itemTag("$view-alias")).assertIsDisplayed()
            onNodeWithTag(itemTag("$view-ordinary")).assertDoesNotExist()
            runOnIdle {
                assertEquals(original.copy(selectedView = view, favoritesOnly = true), screen.value)
                val catalog = screen.value.selectedCatalog
                screen.value = screen.value.withSelectedCatalog(catalog.copy(
                    items = catalog.items.map { it.copy(favoriteActive = false) },
                    tracks = catalog.tracks.map { it.copy(favoriteActive = false) },
                ))
            }
            onNodeWithTag(itemTag("$view-alias")).assertDoesNotExist()
            onNodeWithText("No library ${view.name.lowercase()} match.").assertIsDisplayed()
            runOnIdle {
                screen.value = screen.value.withSelectedCatalog(screen.value.selectedCatalog.copy(query = "", resultsQuery = ""))
            }
            onNodeWithText("No favorites yet.").assertIsDisplayed()
            runOnIdle { screen.value = screen.value.withSelectedCatalog(favoritesCatalog(view)) }
            onNodeWithContentDescription("Favorites")
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag(itemTag("$view-alias")).assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
            runOnIdle { assertEquals(0, loads) }
        }
        onNodeWithContentDescription("Favorites").performClick().assertIsNotSelected()
        NaviampLibraryView.entries.forEach { view ->
            onNodeWithTag(viewTag(view)).performClick()
            onNodeWithContentDescription("Favorites").assertIsNotSelected()
            onNodeWithTag(itemTag("$view-alias")).assertIsDisplayed()
            onNodeWithTag(itemTag("$view-ordinary")).assertIsDisplayed()
            runOnIdle { assertEquals(original.copy(selectedView = view), screen.value) }
        }
    }

    @Test fun unsupportedTabsKeepAnEnabledSelectedHeartUntilTheFilterIsTurnedOff() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(favoritesScreen().copy(
            albums = favoritesCatalog(NaviampLibraryView.Albums).copy(supportsFavorites = false),
            songs = favoritesCatalog(NaviampLibraryView.Songs).copy(supportsFavorites = false),
        ))
        var loads = 0
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState(), onLoadMore = { loads++ }) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).performClick()
        onNodeWithContentDescription("Favorites").assertIsDisplayed().assertIsNotEnabled().assertIsNotSelected()
        onNodeWithText("Refresh").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }.assertIsFocused()
        onNodeWithTag(viewTag(NaviampLibraryView.Artists)).performClick()
        onNodeWithContentDescription("Favorites").performClick()
        runOnIdle { loads = 0 }
        listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs).forEach { view ->
            onNodeWithTag(viewTag(view)).performClick()
            onNodeWithContentDescription("Favorites").assertIsEnabled().assertIsSelected()
            onNodeWithText("Favorites are not supported by this source.").assertIsDisplayed()
            onNodeWithText("No favorites yet.").assertDoesNotExist()
            onNodeWithTag(itemTag("$view-alias")).assertDoesNotExist()
            onNodeWithTag(itemTag("$view-ordinary")).assertDoesNotExist()
        }
        runOnIdle { assertEquals(0, loads) }
        onNodeWithText("Refresh").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithContentDescription("Favorites").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            .assertIsNotSelected().assertIsNotEnabled()
        onNodeWithTag(itemTag("Songs-ordinary")).assertIsDisplayed()
        onNodeWithText("Favorites are not supported by this source.").assertDoesNotExist()
        onNodeWithTag(viewTag(NaviampLibraryView.Artists)).performClick()
        onNodeWithContentDescription("Favorites").assertIsNotSelected().assertIsEnabled()
    }

    @Test fun filteredAlbumsKeepSortRailPolicyAndTheRightmostHeartFocusNeighbor() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(favoritesScreen().copy(selectedView = NaviampLibraryView.Albums, favoritesOnly = true))
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState()) }
        onNodeWithTag(TelevisionLibraryShortcutRailTestTag).assertIsDisplayed()
        onNodeWithText("Choose libraries").performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Sort albums: Recently added").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Refresh").assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithContentDescription("Favorites").assertIsFocused().assertIsSelected()
            .performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithText("Refresh").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithText("Sort albums: Recently added").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag(TelevisionLibraryShortcutRailTestTag).assertDoesNotExist()
        runOnIdle { assertEquals(LibraryAlbumSortOrder.RecentlyAdded, screen.value.albums.albumSortOrder) }
        onNodeWithTag(itemTag("Albums-alias")).assertIsDisplayed()
        onNodeWithTag(itemTag("Albums-ordinary")).assertDoesNotExist()
        onNodeWithContentDescription("Favorites").assertIsSelected()
        onNodeWithText("Sort albums: Title").performClick()
        onNodeWithTag(TelevisionLibraryShortcutRailTestTag).assertIsDisplayed()
        runOnIdle { assertEquals(LibraryAlbumSortOrder.Title, screen.value.albums.albumSortOrder) }
    }

    @Test fun viewSwitchingDetailReturnAndSongActionsPreserveFocus() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(screen())
        val detailOpen = mutableStateOf(false)
        val selections = mutableListOf<NaviampMediaItemActionRequest>()
        val trackActions = mutableListOf<SharedTrackRowActionRequest>()
        setContent {
            val viewport = rememberNaviampTelevisionLibraryState()
            if (detailOpen.value) Text("Details") else LibraryFixture(screen, viewport,
                onMedia = { selections += it; detailOpen.value = true }, onTrack = trackActions::add)
        }
        onNodeWithTag(viewTag(NaviampLibraryView.Artists)).performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).assertIsSelected()
            .performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag(itemTag("album-0")).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag(itemTag("album-1")).assertIsFocused().performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals("album-1", selections.single().item.id)
            screen.value = screen.value.copy(albums = screen.value.albums.copy(
                items = listOf(SharedMediaItemUi("inserted", "Inserted", "Artist")) + screen.value.albums.items,
            ))
            detailOpen.value = false
        }
        onNodeWithTag(itemTag("album-1")).assertIsFocused().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
        onNodeWithTag(viewTag(NaviampLibraryView.Songs)).assertIsSelected().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag(itemTag("song-0")).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(SharedTrackRowAction.Select, trackActions.last().action) }
        onNodeWithTag(itemTag("song-0")).performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithText("Track actions").assertIsDisplayed()
        onNodeWithText("Play After Current Group").performClick()
        onNodeWithTag(itemTag("song-0")).assertIsFocused()
        runOnIdle { assertEquals(SharedTrackRowAction.PlayNext, trackActions.last().action) }
        onNodeWithTag(itemTag("song-0")).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).performClick()
        onNodeWithTag(itemTag("album-1")).assertIsFocused()
    }

    @Test fun pendingJumpsStayScopedToTheSelectedView() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(screen().copy(selectedView = NaviampLibraryView.Songs))
        var requestedLetter: Char? = null
        var loads = 0
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState(),
            onLoadMore = { loads++ }, onJump = { requestedLetter = it }) }
        onNodeWithTag(TelevisionLibraryLetterTagPrefix + 'A')
            .performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(25) {
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
        }
        onNodeWithTag(TelevisionLibraryLetterTagPrefix + 'Z').assertIsDisplayed().assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }
        runOnIdle {
            assertEquals('Z', requestedLetter)
            screen.value = screen.value.copy(songs = screen.value.songs.copy(pendingJump = 'Z'))
        }
        onNodeWithText("Loading Z…").assertIsDisplayed()
        onNodeWithTag(TelevisionLibraryLetterTagPrefix + 'Z').assertIsFocused()
        runOnIdle {
            screen.value = screen.value.copy(
                songs = screen.value.songs.copy(pendingJump = null,
                    tracks = screen.value.songs.tracks + SharedTrackRowUi("z", "Zebra", "Artist")),
                jumpRequest = NaviampLibraryJumpUi(NaviampLibraryView.Songs, 'Z', 1),
            )
        }
        onNodeWithTag(itemTag("z")).assertIsFocused()
        runOnIdle { assertTrue(loads > 0) }
        onNodeWithTag(itemTag("z")).performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).performClick()
        runOnIdle { screen.value = screen.value.copy(jumpRequest = NaviampLibraryJumpUi(NaviampLibraryView.Songs, 'A', 2)) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).assertIsFocused()
    }

    @Test fun appendingAlbumsKeepsTheCurrentFocusInsteadOfReplayingEntry() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(screen().copy(selectedView = NaviampLibraryView.Albums))
        setContent { LibraryFixture(screen, rememberNaviampTelevisionLibraryState()) }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag(itemTag("album-0")).assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag(itemTag("album-1")).assertIsFocused()
        runOnIdle {
            screen.value = screen.value.copy(albums = screen.value.albums.copy(
                items = screen.value.albums.items + SharedMediaItemUi("next-page", "Next page", "Artist"),
            ))
        }
        onNodeWithTag(itemTag("album-1")).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag(itemTag("album-2")).assertIsFocused()
    }

    @Test fun refreshResetsAlbumGridAndSongListAndClearsOldFocus() = runDesktopComposeUiTest(1280, 720) {
        val screen = mutableStateOf(screen().copy(selectedView = NaviampLibraryView.Albums))
        lateinit var viewport: NaviampTelevisionLibraryState
        setContent {
            viewport = rememberNaviampTelevisionLibraryState()
            LibraryFixture(screen, viewport)
        }
        runOnIdle { viewport.grid(NaviampLibraryView.Albums).requestScrollToItem(20, 12) }
        waitForIdle()
        runOnIdle {
            viewport.record(NaviampLibraryView.Albums, "album-20")
            screen.value = screen.value.copy(albums = screen.value.albums.copy(
                items = listOf(SharedMediaItemUi("new", "New Album", "Artist")) + screen.value.albums.items,
                refreshGeneration = 1L,
            ))
        }
        waitForIdle()
        onNodeWithTag(itemTag("new")).assertIsDisplayed()
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).assertIsFocused()
        runOnIdle {
            assertEquals(0, viewport.grid(NaviampLibraryView.Albums).firstVisibleItemIndex)
            assertEquals(0, viewport.grid(NaviampLibraryView.Albums).firstVisibleItemScrollOffset)
            assertEquals(null, viewport.focusedId(NaviampLibraryView.Albums))
            screen.value = screen.value.copy(selectedView = NaviampLibraryView.Songs)
        }
        waitForIdle()
        runOnIdle { viewport.lists.listState(NaviampLibraryView.Songs).requestScrollToItem(20, 12) }
        waitForIdle()
        runOnIdle { screen.value = screen.value.copy(songs = screen.value.songs.copy(refreshGeneration = 2L)) }
        waitForIdle()
        onNodeWithTag(itemTag("song-0")).assertIsDisplayed()
        runOnIdle {
            assertEquals(0, viewport.lists.listState(NaviampLibraryView.Songs).firstVisibleItemIndex)
            assertEquals(0, viewport.lists.listState(NaviampLibraryView.Songs).firstVisibleItemScrollOffset)
        }
    }

    @Test fun libraryAt720p() = checkSize(1280, 720)
    @Test fun libraryAt1080p() = checkSize(1920, 1080)
    @Test fun libraryAtNative4k() = checkSize(3840, 2160)
    @Test fun libraryAt4kDoubleDensity() = checkSize(3840, 2160, 2f)

    private fun checkSize(width: Int, height: Int, density: Float = 1f) = runDesktopComposeUiTest(width, height) {
        val screen = mutableStateOf(screen().copy(selectedView = NaviampLibraryView.Albums))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Box(Modifier.fillMaxSize().background(NaviampColors.Dark.background)) {
                    LibraryFixture(screen, rememberNaviampTelevisionLibraryState())
                }
            }
        }
        onNodeWithTag(viewTag(NaviampLibraryView.Albums)).performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag(itemTag("album-0")).assertIsFocused().assertIsDisplayed()
        NaviampLibraryView.entries.forEach { onNodeWithTag(viewTag(it)).assertIsDisplayed() }
        onNodeWithTag(TelevisionLibraryShortcutRailTestTag).assertIsDisplayed()
        onNodeWithTag(TelevisionLibraryLetterTagPrefix + 'A').assertIsDisplayed()
        val pixels = onRoot().captureToImage().toPixelMap()
        val snapshot = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) snapshot.setRGB(x, y, pixels[x, y].toArgb())
        val output = File("build/reports/television-library/library-${width}x$height-${density}x.png")
        output.parentFile.mkdirs()
        ImageIO.write(snapshot, "png", output)
    }

    @Composable
    private fun LibraryFixture(
        screen: MutableState<NaviampLibraryScreenUi>,
        viewport: NaviampTelevisionLibraryState,
        onMedia: (NaviampMediaItemActionRequest) -> Unit = {},
        onTrack: (SharedTrackRowActionRequest) -> Unit = {},
        onLoadMore: () -> Unit = {},
        onJump: (Char) -> Unit = {},
    ) {
        val colors = NaviampColors.Dark
        MaterialTheme(colorScheme = darkColorScheme(background = colors.background, surface = colors.controlSurface,
            primary = colors.accent, onPrimary = colors.onAccent, onBackground = colors.primaryText, onSurface = colors.primaryText),
            typography = rememberNaviampTypography()) {
        TelevisionLibrary(screen.value, NaviampColors.Dark, NaviampLibraryActions(
            onViewChanged = { screen.value = screen.value.copy(selectedView = it) },
            onFavoritesOnlyChanged = { screen.value = screen.value.copy(favoritesOnly = it) },
            onAlbumSortOrderChanged = { screen.value = screen.value.copy(albums = screen.value.albums.copy(albumSortOrder = it)) },
            onQueryChanged = { query ->
                val value = screen.value
                screen.value = when (value.selectedView) {
                    NaviampLibraryView.Artists -> value.copy(artists = value.artists.copy(query = query))
                    NaviampLibraryView.Albums -> value.copy(albums = value.albums.copy(query = query))
                    NaviampLibraryView.Songs -> value.copy(songs = value.songs.copy(query = query))
                }
            }, onRefresh = {}, onLoadMore = onLoadMore, onJumpToLetter = onJump, onTrackAction = onTrack,
        ), NaviampMediaActions({}, onMedia), viewport, FocusRequester())
        }
    }

    private fun favoritesScreen() = NaviampLibraryScreenUi(
        artists = favoritesCatalog(NaviampLibraryView.Artists),
        albums = favoritesCatalog(NaviampLibraryView.Albums),
        songs = favoritesCatalog(NaviampLibraryView.Songs),
    )

    private fun favoritesCatalog(view: NaviampLibraryView) = NaviampLibraryCatalogUi(
        query = "Rufus $view", resultsQuery = "Rufus $view", supportsFavorites = true,
        items = if (view == NaviampLibraryView.Songs) emptyList() else listOf(
            SharedMediaItemUi("$view-alias", "$view server alias", "Metadata match", favoriteActive = true),
            SharedMediaItemUi("$view-ordinary", "$view ordinary", "Metadata match"),
        ),
        tracks = if (view != NaviampLibraryView.Songs) emptyList() else listOf(
            SharedTrackRowUi("$view-alias", "$view server alias", "Metadata match", favoriteActive = true),
            SharedTrackRowUi("$view-ordinary", "$view ordinary", "Metadata match"),
        ),
    )

    private fun NaviampLibraryScreenUi.withSelectedCatalog(catalog: NaviampLibraryCatalogUi) = when (selectedView) {
        NaviampLibraryView.Artists -> copy(artists = catalog)
        NaviampLibraryView.Albums -> copy(albums = catalog)
        NaviampLibraryView.Songs -> copy(songs = catalog)
    }

    private fun screen() = NaviampLibraryScreenUi(
        artists = NaviampLibraryCatalogUi(items = List(30) { SharedMediaItemUi("artist-$it", "Artist $it", "Artist") }),
        albums = NaviampLibraryCatalogUi(items = List(30) { SharedMediaItemUi("album-$it", "Album $it", "Artist") }),
        songs = NaviampLibraryCatalogUi(tracks = List(30) { SharedTrackRowUi("song-$it", "Song $it", "Artist") }),
    )
    private fun viewTag(view: NaviampLibraryView) = TelevisionLibraryViewTagPrefix + view.name
    private fun itemTag(id: String) = TelevisionLibraryItemTagPrefix + id
}
