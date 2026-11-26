package app.naviamp.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.union
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampNavigationFixesUiTest {
    @Test
    fun radioToastAvoidsSystemBarsAndDisplayCutouts() = runDesktopComposeUiTest(320, 640) {
        val insets = WindowInsets(left = 12, top = 24, right = 8, bottom = 24)
            .union(WindowInsets(top = 48))
        setContent {
            Box(Modifier.fillMaxSize()) {
                NaviampRadioGeneratingNotice(NaviampColors.Dark, Modifier.align(Alignment.TopCenter), insets)
            }
        }
        val toast = onNodeWithText("Generating radio…").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(toast.top >= root.top + 48)
        assertTrue(toast.left >= root.left + 12 && toast.right <= root.right - 8)
        capture("radio-generating-safe-area")
    }

    @Test
    fun trackRadioShowsGeneratingToastWithoutBlockingPlayback() {
        for ((width, height) in listOf(1000 to 740, 320 to 640)) {
            runDesktopComposeUiTest(width, height) {
                mainClock.autoAdvance = false
                val base = FakeCoreMediaProvider()
                val gate = CompletableDeferred<Unit>()
                val provider = object : MediaProvider by base {
                    override val capabilities = base.capabilities.copy(supportsTrackRadio = true)
                    override suspend fun instantMixTracks(trackId: TrackId, count: Int): List<Track> {
                        gate.await()
                        return listOf(base.track.copy(id = TrackId("radio-match"), title = "Radio match"))
                    }
                }
                val effects = object : NaviampCorePlaybackEffectPort by fakeCoreServices().playback.effects {
                    lateinit var observer: NaviampCorePlaybackObserver
                    override fun attach(observer: NaviampCorePlaybackObserver) { this.observer = observer }
                    override fun pause() { observer.onStateChanged(app.naviamp.domain.playback.PlaybackState.Paused) }
                    override fun resume() { observer.onStateChanged(app.naviamp.domain.playback.PlaybackState.Playing) }
                    override fun playQueueSelection(queue: app.naviamp.domain.queue.PlaybackQueue, index: Int) {
                        observer.onStateChanged(app.naviamp.domain.playback.PlaybackState.Playing)
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val core = NaviampCore.create(scope, fakeCoreServices(provider, effects), initialState = connectedInitialState())
                try {
                    core.execute(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs))
                    core.execute(NaviampCoreCommand.Library.TrackAction(SharedTrackRowActionRequest(
                        core.state.value.shell.library.songs.tracks.single(), SharedTrackRowAction.Select)))
                    effects.pause()
                    core.actions.shell.navigationActions.onOpenNowPlaying()
                    setContent { NaviampCoreApp(core) }
                    mainClock.advanceTimeBy(300)
                    onNodeWithContentDescription("Track actions").performClick()
                    mainClock.advanceTimeBy(300)
                    onNodeWithText("Start Radio").assertIsDisplayed().performClick()
                    mainClock.advanceTimeBy(300)
                    val toast = onNodeWithText("Generating radio…").assertIsDisplayed()
                    val bounds = toast.fetchSemanticsNode().boundsInRoot
                    val root = onRoot().fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= root.left && bounds.right <= root.right)
                    toast.assert(SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                        androidx.compose.ui.semantics.LiveRegionMode.Polite,
                    ))
                    onNodeWithContentDescription("Play").performClick()
                    mainClock.advanceTimeBy(300)
                    onNodeWithContentDescription("Pause").assertIsDisplayed()
                    toast.assertIsDisplayed()
                    capture("radio-generating-$width")
                    runOnIdle { gate.complete(Unit) }
                    mainClock.advanceTimeBy(300)
                    onNodeWithText("Generating radio…").assertDoesNotExist()
                    onNodeWithContentDescription("Pause").assertIsDisplayed()
                } finally {
                    core.close()
                    scope.cancel()
                }
            }
        }
    }

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
        onNodeWithText("Internet Radio").performClick()
        onNodeWithText("Recovered station").assertIsDisplayed()
        capture("radio-navigation-recovery")
        runOnIdle {
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Home)
            core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Radio)
        }
        onNodeWithText("Internet Radio").performClick()
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
