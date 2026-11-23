package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.naviamp.domain.Album
import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Playlist
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.settings.InterfaceLanguage
import app.naviamp.domain.smartplaylist.SmartPlaylistDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampMediaSummaryUiTest {
    @Test
    fun albumSummaryStaysUnderYearAboveActionsAndBesideArtwork() {
        for (width in listOf(280, 800)) runComposeUiTest {
            var year by mutableStateOf<Int?>(2005)
            val requests = mutableListOf<NaviampAlbumDetailActionRequest>()
            setContent {
                Box(Modifier.size(width.dp, 500.dp)) {
                    MediaSummaryTestContent(MediaSummaryTestPage.Album, List(12) { mediaSummaryTestTrack(it, 255) }, year,
                        onAlbumAction = { requests += it })
                }
            }
            val summary = onNodeWithText("12 tracks • 51m").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val date = onNodeWithText("2005").fetchSemanticsNode().boundsInRoot
            val title = onNodeWithText("Album").fetchSemanticsNode().boundsInRoot
            val play = onNodeWithContentDescription("Play album").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(date.left, summary.left)
            assertTrue(summary.left > title.left)
            assertTrue(summary.top >= date.bottom)
            assertTrue(summary.bottom <= play.top)
            onNodeWithContentDescription("Play album").performClick()
            runOnIdle { assertEquals(NaviampAlbumDetailCommand.Play(false), requests.single().command) }
            runOnIdle { year = null }
            onNodeWithText("2005").assertDoesNotExist()
            val withoutYear = onNodeWithText("12 tracks • 51m").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val artist = onNodeWithText("Artist").fetchSemanticsNode().boundsInRoot
            assertEquals(artist.left, withoutYear.left)
            assertTrue(withoutYear.top >= artist.bottom)
            assertTrue(withoutYear.bottom <= onNodeWithContentDescription("Play album").fetchSemanticsNode().boundsInRoot.top)
        }
    }

    @Test
    fun everyDetailHeaderUsesOneSummaryAndUpdatesCountDurationAndUnknownTime() {
        for (page in MediaSummaryTestPage.entries) runComposeUiTest {
            var tracks by mutableStateOf(listOf(mediaSummaryTestTrack(0, 60)))
            setContent {
                Box(Modifier.size(1000.dp, 650.dp)) { MediaSummaryTestContent(page, tracks, 2005) }
            }
            onAllNodes(hasText("1 track • 1m", substring = true)).assertCountEquals(1)
            onNode(hasText("1 track • 1m", substring = true)).assertIsDisplayed()
            runOnIdle { tracks = listOf(mediaSummaryTestTrack(0, 1800), mediaSummaryTestTrack(1, 1830)) }
            onAllNodes(hasText("2 tracks • 1h 0m", substring = true)).assertCountEquals(1)
            runOnIdle { tracks = listOf(mediaSummaryTestTrack(0, null)) }
            onAllNodes(hasText("1 track", substring = true)).assertCountEquals(1)
            onNode(hasText("1 track", substring = true)).assertIsDisplayed()
            onNode(hasText("1 track", substring = true)).assertTextEquals(if (page == MediaSummaryTestPage.TvAlbum) "2005\n1 track" else "1 track")
            runOnIdle { tracks = emptyList() }
            onAllNodes(hasText("0 tracks", substring = true)).assertCountEquals(1)
        }
    }

    @Test
    fun albumCountUsesLocalizedSingularAndPluralForms() = runComposeUiTest {
        var tracks by mutableStateOf(listOf(mediaSummaryTestTrack(0, 60)))
        val localeEffect = createNaviampLocaleEffect()
        setContent {
            NaviampLocaleEnvironment(InterfaceLanguage.Spanish, localeEffect) {
                Box(Modifier.size(360.dp, 500.dp)) { MediaSummaryTestContent(MediaSummaryTestPage.Album, tracks, null) }
            }
        }
        onNodeWithText("1 pista • 1m").assertIsDisplayed()
        runOnIdle { tracks = listOf(mediaSummaryTestTrack(0, 60), mediaSummaryTestTrack(1, 60)) }
        onNodeWithText("2 pistas • 2m").assertIsDisplayed()
    }
}

internal enum class MediaSummaryTestPage { Album, Playlist, Favorites, TvAlbum, TvPlaylist, TvFavorites }

internal fun mediaSummaryTestTrack(index: Int, seconds: Int?) = Track(
    id = TrackId("track-$index"), title = "Song $index", artistName = "Artist", albumTitle = "Album",
    durationSeconds = seconds, coverArtId = null, audioInfo = null, replayGain = null,
)

@Composable
internal fun MediaSummaryTestContent(
    page: MediaSummaryTestPage,
    tracks: List<Track>,
    year: Int?,
    onAlbumAction: (NaviampAlbumDetailActionRequest) -> Unit = {},
) {
    val colors = NaviampColors.Light
    val album = remember(tracks, year) {
        AlbumDetails(Album(AlbumId("album"), "Album", "Artist", null, null).copy(releaseYear = year), tracks)
            .toSharedAlbumDetailUi({ null })
    }
    val playlist = remember(tracks) { Playlist("playlist", "Playlist", 99).toSharedPlaylistDetailUi(tracks, { null }) }
    val albumActions = NaviampAlbumDetailActions(onBack = {}, onAlbumAction = onAlbumAction, onTrackAction = {})
    val playlistActions = NaviampPlaylistDetailActions(onBack = {}, onPlaylistAction = {}, onUpdateStandardPlaylist = { _, _, _ -> }, onTrackAction = {})
    MaterialTheme {
        when (page) {
            MediaSummaryTestPage.Album -> NaviampAlbumDetailContent(colors, NaviampAlbumDetailScreenUi(detail = album), actions = albumActions)
            MediaSummaryTestPage.Playlist -> NaviampPlaylistDetailContent(
                colors, NaviampPlaylistDetailScreenUi(detail = playlist), playlistActions,
                NaviampPlaylistsActions(onRefresh = {}, onSortModeChanged = {}, smartPlaylist = NaviampSmartPlaylistActions(
                    onSave = {}, onUpdate = { _, _ -> }, onSaveWithPassword = { _, _ -> }, onUpdateWithPassword = { _, _, _ -> },
                    onLoad = { SmartPlaylistDefinition(name = "Fixture", rules = emptyList()) },
                    onLoadWithPassword = { _, _ -> SmartPlaylistDefinition(name = "Fixture", rules = emptyList()) },
                )), playlistChoices = emptyList(),
            )
            MediaSummaryTestPage.TvAlbum -> TelevisionAlbumDetail(NaviampAlbumDetailScreenUi(detail = album), colors, albumActions, remember { FocusRequester() })
            MediaSummaryTestPage.TvPlaylist -> TelevisionPlaylistDetail(NaviampPlaylistDetailScreenUi(detail = playlist), colors, playlistActions, remember { FocusRequester() })
            MediaSummaryTestPage.Favorites, MediaSummaryTestPage.TvFavorites -> NaviampFavoriteSongsPage(
                colors,
                SharedFavoriteSongsPageUi(tracks.map { it.toSharedTrackRowUi({ null }) }, tracks.totalDurationLabel(),
                    if (tracks.isEmpty()) FavoriteSongsStatus.Empty else FavoriteSongsStatus.Ready),
                NaviampHomeActions(onRefresh = {}, onRecentRadioSelected = {}, onInternetRadioStationSelected = {},
                    onMixBuilderSelected = {}, onStationSelected = {}, onSonicDiscoveryTrackAction = {},
                    onRecentlyPlayedTrackAction = {}, onCollectionSelected = {}, onCollectionBack = {}, onCollectionPageLayoutChanged = { _, _ -> }),
                television = page == MediaSummaryTestPage.TvFavorites,
            )
        }
    }
}
