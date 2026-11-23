package app.naviamp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampHomeFavoriteSongsUiTest {
    @Test
    fun completeFavoriteLibraryIsVirtualizedAndLastSongRemainsSelectable() = runDesktopComposeUiTest(900, 650) {
        val tracks = List(5_003, ::favoriteTrack)
        val requests = mutableListOf<SharedTrackRowActionRequest>()
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(
                    NaviampColors.Dark,
                    SharedFavoriteSongsPageUi(tracks, "250h 9m", FavoriteSongsStatus.Ready),
                    favoriteSongsActions(trackAction = { requests += it }),
                )
            }
        }
        onNodeWithText("Favorite Songs").assertIsDisplayed()
        onNodeWithText("All songs you have marked as favorites").assertIsDisplayed()
        onNodeWithText("5003 tracks • 250h 9m").assertIsDisplayed()
        onNodeWithText("Song 0").assertIsDisplayed()
        onNodeWithText("Artist 0 - Album 0").assertIsDisplayed()
        onAllNodesWithText("3:00")[0].assertIsDisplayed()
        onNodeWithText("Song 5002").assertDoesNotExist()
        assertTrue(onAllNodes(hasText("Song ", substring = true)).fetchSemanticsNodes().size < 100)

        onNodeWithTag("favorite-songs-tracks").performScrollToIndex(tracks.lastIndex)
        onNodeWithText("Song 5002").assertIsDisplayed().performClick()
        onNodeWithText("Artist 5002 - Album 5002").assertIsDisplayed()
        onNodeWithText("Song 0").assertDoesNotExist()
        runOnIdle {
            assertEquals(SharedTrackRowActionRequest(tracks.last(), SharedTrackRowAction.Select), requests.single())
        }
    }

    @Test
    fun playAndShuffleEmitDistinctIntentsWithoutPlayingOnOpen() = runDesktopComposeUiTest(700, 550) {
        val intents = mutableListOf<Boolean>()
        val rows = mutableListOf<SharedTrackRowActionRequest>()
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(
                    NaviampColors.Dark,
                    SharedFavoriteSongsPageUi(List(2, ::favoriteTrack), "6m", FavoriteSongsStatus.Ready),
                    favoriteSongsActions(play = { intents += it }, trackAction = { rows += it }),
                )
            }
        }
        runOnIdle { assertTrue(intents.isEmpty()) }
        onNode(favoriteArtworkPlay).assertIsDisplayed().assertIsEnabled().performClick()
        onNode(favoriteHeaderPlay).assertIsEnabled().performClick()
        onNodeWithContentDescription("Shuffle").assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(listOf(false, false, true), intents)
            assertTrue(rows.isEmpty())
        }
    }

    @Test
    fun playbackAvailabilityTracksStatusAndSongCountWhileRefreshStaysUsable() = runDesktopComposeUiTest(700, 600) {
        val page = mutableStateOf(SharedFavoriteSongsPageUi())
        var refreshes = 0
        val intents = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(
                    NaviampColors.Dark, page.value,
                    favoriteSongsActions(play = { intents += it }, refresh = { refreshes++ }),
                )
            }
        }
        val inactiveStates = listOf(
            FavoriteSongsStatus.Loading to "Loading songs…",
            FavoriteSongsStatus.Empty to "No favorites yet.",
            FavoriteSongsStatus.Failed to "Could not load favorite songs. Try again.",
            FavoriteSongsStatus.Unsupported to "Favorites are not supported by this source.",
            FavoriteSongsStatus.Idle to null,
        )
        inactiveStates.forEach { (status, label) ->
            runOnIdle { page.value = SharedFavoriteSongsPageUi(status = status) }
            onNode(favoriteHeaderPlay).assertIsNotEnabled()
            onNodeWithContentDescription("Shuffle").assertIsNotEnabled()
            onNode(favoriteArtworkPlay).assertIsNotEnabled().performTouchInput { click() }
            label?.let { onNodeWithText(it).assertIsDisplayed() }
            onNodeWithContentDescription("Back").assertIsEnabled()
            onNodeWithContentDescription(if (status == FavoriteSongsStatus.Failed) "Retry" else "Refresh")
                .assertIsEnabled().performClick()
        }
        runOnIdle {
            assertEquals(inactiveStates.size, refreshes)
            assertTrue(intents.isEmpty())
            page.value = SharedFavoriteSongsPageUi(status = FavoriteSongsStatus.Ready)
        }
        onNodeWithText("0 tracks").assertIsDisplayed()
        onNode(favoriteHeaderPlay).assertIsNotEnabled()
        onNodeWithContentDescription("Shuffle").assertIsNotEnabled()
        onNode(favoriteArtworkPlay).assertIsNotEnabled()
        runOnIdle { page.value = page.value.copy(tracks = listOf(favoriteTrack(0)), totalDurationLabel = "3m") }
        onNodeWithText("1 track • 3m").assertIsDisplayed()
        onNode(favoriteHeaderPlay).assertIsEnabled()
        onNodeWithContentDescription("Shuffle").assertIsNotEnabled()
        onNode(favoriteArtworkPlay).assertIsEnabled()
        runOnIdle { page.value = page.value.copy(tracks = List(2, ::favoriteTrack), totalDurationLabel = "6m") }
        onNodeWithText("2 tracks • 6m").assertIsDisplayed()
        onNodeWithContentDescription("Shuffle").assertIsEnabled()
        runOnIdle { page.value = page.value.copy(status = FavoriteSongsStatus.Loading) }
        onNode(favoriteHeaderPlay).assertIsNotEnabled()
        onNodeWithContentDescription("Shuffle").assertIsNotEnabled()
        onNode(favoriteArtworkPlay).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithText("Song 0").assertDoesNotExist()
    }

    @Test
    fun compactPageKeepsBothPlaybackButtonsBackAndRefreshVisible() = runDesktopComposeUiTest(280, 500) {
        var backs = 0
        var refreshes = 0
        val intents = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(
                    NaviampColors.Dark,
                    SharedFavoriteSongsPageUi(List(2, ::favoriteTrack), "6m", FavoriteSongsStatus.Ready),
                    favoriteSongsActions(play = { intents += it }, refresh = { refreshes++ }, back = { backs++ }),
                )
            }
        }
        onNodeWithText("Favorite Songs").assertIsDisplayed()
        onNodeWithText("2 tracks • 6m").assertIsDisplayed()
        val backBounds = onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        val titleBounds = onNodeWithText("Favorite Songs").fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.left >= backBounds.right)
        assertTrue(titleBounds.center.y in backBounds.top..backBounds.bottom)
        onNode(favoriteArtworkPlay).assertIsDisplayed().performClick()
        onNode(favoriteHeaderPlay).assertIsDisplayed().performClick()
        onNodeWithContentDescription("Shuffle").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Refresh").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        onNodeWithTag("favorite-songs-tracks").performScrollToIndex(1)
        onNodeWithText("Song 1").assertIsDisplayed()
        runOnIdle {
            assertEquals(listOf(false, false, true), intents)
            assertEquals(1, backs)
            assertEquals(1, refreshes)
        }
    }

    @Test
    fun rowMenuUsesStandardTrackCapabilitiesWithoutPlaylistManagement() = runDesktopComposeUiTest(800, 700) {
        val page = mutableStateOf(SharedFavoriteSongsPageUi(
            tracks = listOf(favoriteTrack(0)), status = FavoriteSongsStatus.Ready,
        ))
        val requests = mutableListOf<SharedTrackRowActionRequest>()
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(NaviampColors.Dark, page.value, favoriteSongsActions(trackAction = { requests += it }))
            }
        }
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Download track").assertDoesNotExist()
        onNodeWithText("Start Radio").assertDoesNotExist()
        onNodeWithText("Add to playlist").assertIsDisplayed()
        onNodeWithText("Remove from This Playlist").assertDoesNotExist()
        onNodeWithText("Move to Top").assertDoesNotExist()
        onNodeWithText("Add to queue").performClick()
        runOnIdle { page.value = page.value.copy(canDownload = true, canStartRadio = true) }
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Download track").assertIsDisplayed().performClick()
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Start Radio").assertIsDisplayed().performClick()
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Add to playlist").performClick()
        runOnIdle {
            assertEquals(
                listOf(SharedTrackRowAction.AddToQueue, SharedTrackRowAction.Download,
                    SharedTrackRowAction.StartRadio, SharedTrackRowAction.AddToPlaylist),
                requests.map { it.action },
            )
            assertTrue(requests.all { it.track.id == "song-0" })
        }
    }

    @Test
    fun keyboardCanActivatePlaybackRefreshAndBack() = runDesktopComposeUiTest(700, 550) {
        val intents = mutableListOf<Boolean>()
        var backs = 0
        var refreshes = 0
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(
                    NaviampColors.Dark,
                    SharedFavoriteSongsPageUi(List(2, ::favoriteTrack), status = FavoriteSongsStatus.Ready),
                    favoriteSongsActions(play = { intents += it }, refresh = { refreshes++ }, back = { backs++ }),
                )
            }
        }
        onNode(favoriteArtworkPlay).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNode(favoriteArtworkPlay).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onNode(favoriteHeaderPlay).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNode(favoriteHeaderPlay).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithContentDescription("Shuffle").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onNodeWithContentDescription("Refresh").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNodeWithContentDescription("Refresh").performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        runOnIdle {
            assertEquals(listOf(false, false, true), intents)
            assertEquals(1, refreshes)
            assertEquals(1, backs)
        }
    }

    @Test
    fun televisionDpadReachesPlaybackTracksAndBack() = runDesktopComposeUiTest(1000, 650) {
        val intents = mutableListOf<Boolean>()
        val requests = mutableListOf<SharedTrackRowActionRequest>()
        var backs = 0
        setContent {
            MaterialTheme {
                NaviampFavoriteSongsPage(
                    NaviampColors.Dark,
                    SharedFavoriteSongsPageUi(List(20, ::favoriteTrack), "1h", FavoriteSongsStatus.Ready),
                    favoriteSongsActions(play = { intents += it }, trackAction = { requests += it }, back = { backs++ }),
                    television = true,
                )
            }
        }
        onNodeWithContentDescription("Back").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNode(favoriteHeaderPlay).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        onNode(favoriteArtworkPlay).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithContentDescription("Back").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionLeft); pressKey(Key.DirectionDown) }
        onNodeWithText("Song 0").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionLeft); pressKey(Key.DirectionRight) }
        onNode(favoriteHeaderPlay).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithContentDescription("Shuffle").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithText("Song 0").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        repeat(10) { onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        onNodeWithText("Song 10").assertIsDisplayed().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        onRoot().performKeyInput { pressKey(Key.Back) }
        runOnIdle {
            assertEquals(listOf(false, false, true), intents)
            assertEquals(listOf("song-0", "song-10"), requests.map { it.track.id })
            assertTrue(requests.all { it.action == SharedTrackRowAction.Select })
            assertEquals(1, backs)
        }
    }
}

private val favoriteArtworkPlay = hasContentDescription("Play") and
    (hasTestTag("favorite-songs-artwork-play") or hasAnyAncestor(hasTestTag("favorite-songs-artwork-play")))
private val favoriteHeaderPlay = hasContentDescription("Play") and !favoriteArtworkPlay

private fun favoriteTrack(index: Int) = SharedTrackRowUi(
    id = "song-$index",
    title = "Song $index",
    subtitle = "Artist $index - Album $index",
    durationLabel = "3:00",
    favoriteActive = true,
    hasArtist = true,
    hasAlbum = true,
    albumTitle = "Album $index",
)

private fun favoriteSongsActions(
    play: (Boolean) -> Unit = {},
    refresh: () -> Unit = {},
    trackAction: (SharedTrackRowActionRequest) -> Unit = {},
    back: () -> Unit = {},
) = NaviampHomeActions(
    onRefresh = {}, onRecentRadioSelected = {}, onInternetRadioStationSelected = {},
    onMixBuilderSelected = {}, onStationSelected = {}, onSonicDiscoveryTrackAction = {},
    onRecentlyPlayedTrackAction = {}, onCollectionSelected = {}, onCollectionBack = {},
    onCollectionPageLayoutChanged = { _, _ -> }, onFavoriteSongsPlay = play,
    onFavoriteSongsRefresh = refresh, onFavoriteSongAction = trackAction, onFavoriteSongsBack = back,
)
