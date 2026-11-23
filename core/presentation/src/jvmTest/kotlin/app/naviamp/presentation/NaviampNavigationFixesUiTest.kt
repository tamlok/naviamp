package app.naviamp.presentation

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.naviamp.app.NaviampConnectionPhase
import app.naviamp.app.NaviampConnectionRuntimeState
import app.naviamp.domain.*
import app.naviamp.domain.provider.*
import app.naviamp.domain.radio.albumRecentRadioStream
import app.naviamp.domain.radio.randomAlbumRecentRadioStream
import app.naviamp.domain.settings.HomeSectionIds
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.settings.SavedTrack
import app.naviamp.ui.*
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampNavigationFixesUiTest {
    @Test
    fun recommendationsDropdownReordersAlbumsAndRadioReplaySurvivesAnOlderRefresh() = runDesktopComposeUiTest(1100, 800) {
        val provider = NavigationFixesProvider()
        var history = listOf(
            radio(provider.albums[0], provider.track, 400).copy(sourceId = "other-source"),
            radio(provider.albums[1], provider.track, 300),
            randomAlbumRecentRadioStream(provider.albums[2]).copy(sourceId = "source", startedAtEpochMillis = 200),
            radio(provider.albums[0], provider.track, 100),
        )
        lateinit var core: NaviampCore
        setContent {
            val scope = rememberCoroutineScope()
            core = remember {
                val defaults = fakeCoreServices(provider)
                NaviampCore.create(scope, defaults.copy(
                    content = defaults.content.copy(homeSupplement = NaviampCoreHomeSupplementSource {
                        NaviampCoreHomeSupplement(sourceId = "source", recentRadioStreams = history)
                    }),
                    radio = defaults.radio.copy(generatedRecents = NaviampCoreGeneratedRadioRecentsPort(
                        load = { history }, save = { history = it },
                    )),
                ), initialState = connectedInitialState())
            }
            NaviampCoreApp(core)
            LaunchedEffect(Unit) { core.execute(NaviampCoreCommand.Home.Refresh) }
        }
        waitUntil(timeoutMillis = 10_000) { core.state.value.shell.home.content.collectionSections.any { it.id == HomeSectionIds.FavoriteArtists } }
        runOnIdle { core.dispatch(NaviampCoreCommand.Home.OpenCollection(HomeSectionIds.FavoriteArtists)) }
        fun assertOrder(vararg names: String) {
            val bounds = names.map { onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            bounds.zipWithNext().forEach { (first, second) ->
                assertTrue(first.top < second.top || first.top == second.top && first.left < second.left,
                    "Cards must follow ${names.toList()}: $bounds")
            }
        }
        assertOrder("Favorite Songs", "Artist Alpha", "Artist Zulu", "Album Alpha", "Album Bravo", "Album Charlie")
        val initialReads = provider.albumListReads
        onNodeWithText("Name").performClick()
        onNodeWithText("Date favorited").performClick()
        assertOrder("Favorite Songs", "Artist Zulu", "Artist Alpha", "Album Charlie", "Album Alpha", "Album Bravo")
        capture("recommendations-date-favorited")
        onNodeWithText("Date favorited").performClick()
        onNodeWithText("Last radio played").performClick()
        assertOrder("Favorite Songs", "Artist Alpha", "Artist Zulu", "Album Bravo", "Album Charlie", "Album Alpha")
        runOnIdle { assertEquals(initialReads, provider.albumListReads, "Sorting must use loaded metadata, not reload the page") }
        capture("recommendations-last-radio")

        provider.blockNextAlbumRead = true
        runOnIdle { core.dispatch(NaviampCoreCommand.Home.Refresh) }
        waitUntil(timeoutMillis = 10_000) { provider.albumReadStarted.isCompleted }
        val oldest = history.last()
        core.execute(NaviampCoreCommand.Home.SelectRecentRadio(SharedMediaItemUi(oldest.id, oldest.label, "")))
        runOnIdle { core.dispatch(NaviampCoreCommand.Navigation.CloseNowPlaying) }
        assertOrder("Favorite Songs", "Artist Alpha", "Artist Zulu", "Album Alpha", "Album Bravo", "Album Charlie")
        provider.releaseAlbumRead.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { !core.state.value.shell.home.refreshing }
        assertOrder("Favorite Songs", "Artist Alpha", "Artist Zulu", "Album Alpha", "Album Bravo", "Album Charlie")
        assertEquals(oldest.id, history.first().id)
        onNodeWithText("Last radio played").performClick()
        onNodeWithText("Name").performClick()
        assertOrder("Favorite Songs", "Artist Alpha", "Artist Zulu", "Album Alpha", "Album Bravo", "Album Charlie")
        runOnIdle { core.close() }
    }

    @Test
    fun everyMixBuilderUsesTheStandardArrowAndReturnsThroughRealHistory() = runDesktopComposeUiTest(620, 650) {
        lateinit var core: NaviampCore
        setContent {
            val scope = rememberCoroutineScope()
            core = remember { NaviampCore.create(scope, fakeCoreServices(NavigationFixesProvider()), initialState = connectedInitialState()) }
            NaviampCoreApp(core)
        }
        val builders = listOf("artist", "album", "genre", "sonic-path", "sonic-mix")
        for (builder in builders) {
            runOnIdle { core.dispatch(NaviampCoreCommand.Home.SelectMixBuilder(SharedMixBuilderUi(builder, builder, ""))) }
            onNodeWithTag("page-back").assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            onNodeWithContentDescription("Back").assertIsDisplayed()
            onNodeWithText("Back").assertDoesNotExist()
            capture("$builder-back-arrow")
            onNodeWithTag("page-back").performClick()
            runOnIdle { assertEquals(SharedRoute.Home, core.state.value.shell.shellChrome.selectedRoute) }
            onNodeWithTag("page-back").assertDoesNotExist()
        }
        runOnIdle { core.close() }
    }

    @Test
    fun enteringAnEmptyFailedRadioPageRetriesWithoutManualRefresh() = runDesktopComposeUiTest(900, 650) {
        var fail = true
        var reads = 0
        val provider = object : MediaProvider by NavigationFixesProvider() {
            override suspend fun internetRadioStations(): List<InternetRadioStation> {
                reads++
                if (fail) error("Transient test failure")
                return listOf(InternetRadioStation("recovered", "Recovered station", "https://example.invalid/radio"))
            }
        }
        lateinit var core: NaviampCore
        setContent {
            val scope = rememberCoroutineScope()
            core = remember { NaviampCore.create(scope, fakeCoreServices(provider), initialState = connectedInitialState()) }
            NaviampCoreApp(core)
            LaunchedEffect(Unit) { core.execute(NaviampCoreCommand.Radio.Refresh) }
        }
        waitUntil(timeoutMillis = 10_000) { reads == 1 && !core.state.value.shell.radio.refreshing }
        runOnIdle {
            fail = false
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Radio)
        }
        waitUntil(timeoutMillis = 10_000) { reads == 2 && !core.state.value.shell.radio.refreshing }
        onNodeWithText("Recovered station").assertIsDisplayed()
        capture("radio-navigation-recovery")
        runOnIdle {
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Home)
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Radio)
        }
        onNodeWithText("Recovered station").assertIsDisplayed()
        runOnIdle { assertEquals(2, reads); core.close() }
    }
}

private fun connectedInitialState() = NaviampCoreInitialState(
    connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = "source"),
)

private fun radio(album: Album, track: Track, started: Long): RecentRadioStream = albumRecentRadioStream(album).copy(
    sourceId = "source", startedAtEpochMillis = started,
    sessionTracks = listOf(SavedTrack.fromTrack(track.copy(id = TrackId("radio-${album.id.value}"), albumId = album.id, albumTitle = album.title))),
)

private class NavigationFixesProvider : MediaProvider by FakeCoreMediaProvider() {
    val track = FakeCoreMediaProvider().track
    val albums = listOf(
        Album(AlbumId("alpha"), "Album Alpha", "Artist", null, null, favoritedAtIso8601 = "2026-01-02T00:00:00Z"),
        Album(AlbumId("bravo"), "Album Bravo", "Artist", null, null, favoritedAtIso8601 = "2026-01-01T00:00:00Z"),
        Album(AlbumId("charlie"), "Album Charlie", "Artist", null, null, favoritedAtIso8601 = "2026-01-03T00:00:00Z"),
    )
    private val artists = listOf(
        Artist(ArtistId("artist-a"), "Artist Alpha", "2026-01-01T00:00:00Z"),
        Artist(ArtistId("artist-z"), "Artist Zulu", "2026-01-02T00:00:00Z"),
    )
    override val capabilities = FakeCoreMediaProvider().capabilities.copy(
        supportsTrackFavorites = true, supportsArtistFavorites = true, supportsAlbumFavorites = true, supportsSonicSimilarity = true,
    )
    var albumListReads = 0
    var blockNextAlbumRead = false
    val albumReadStarted = CompletableDeferred<Unit>()
    val releaseAlbumRead = CompletableDeferred<Unit>()
    override suspend fun artists(limit: Int) = artists
    override suspend fun favoriteArtists(limit: Int) = artists
    override suspend fun favoriteAlbumsPage(request: MediaPageRequest) = MediaPage(albums, request.offset, request.limit, false)
    override suspend fun albumList(type: AlbumListType, limit: Int): List<Album> {
        albumListReads++
        if (blockNextAlbumRead) {
            blockNextAlbumRead = false
            albumReadStarted.complete(Unit)
            releaseAlbumRead.await()
        }
        return albums
    }
    override suspend fun album(albumId: AlbumId) = AlbumDetails(albums.first { it.id == albumId }, listOf(track))
    override suspend fun genres(limit: Int) = emptyList<Genre>()
    override suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int) = emptyList<Album>()
    override suspend fun internetRadioStations() = emptyList<InternetRadioStation>()
}

@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.capture(name: String) {
    val directory = System.getenv("NAVIAMP_NAVIGATION_FIXES_CAPTURE") ?: return
    val target = File(directory, "$name.png")
    target.parentFile.mkdirs()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", target)
}
