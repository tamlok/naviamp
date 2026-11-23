package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationController
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.ui.toSharedRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertTrue
import app.naviamp.domain.Album
import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistDetails
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Playlist
import app.naviamp.domain.ProviderId
import app.naviamp.domain.StreamRequest
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.provider.ConnectionValidation
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.ProviderCapabilities
import app.naviamp.domain.provider.SonicPathMatch
import app.naviamp.domain.provider.SonicSimilarTrack
import app.naviamp.ui.SharedSonicMixBiasUi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NaviampCoreSonicBuilderControllerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun failedSonicSearchesRecoverThroughNavigationWithoutGenerating() = runTest {
        for ((route, change, search) in candidateSearches()) {
            val provider = SonicTestProvider()
            var failed = true
            provider.searchResult = { check(!failed) { "Offline" }; MediaSearchResults(tracks = listOf(sonicTrack("recovered"))) }
            val fixture = fixture(provider)
            val router = fixture.router(this)
            router.dispatch(change)
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(route.toSharedRoute()))
            runCurrent()
            assertTrue(provider.searches.isEmpty(), "Unattempted searches must stay idle")
            router.execute(search)
            assertEquals(1, provider.searches.size)
            val destination = route
            assertTrue(fixture.controller.recoverEmptyPage(object : NaviampNavigationPage {
                override val route = destination
            }).isEmpty())
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(NaviampRoute.Home.toSharedRoute()))
            failed = false
            repeat(3) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(route.toSharedRoute())) }
            assertEquals(1, provider.searches.size)
            runCurrent()
            assertEquals(2, provider.searches.size)
            assertTrue(fixture.controller.recoverEmptyPage(NaviampRoutePage(route)).isEmpty())
            val shell = fixture.store.state.value.shell
            assertEquals(listOf("recovered"), (shell.sonicPathBuilder.startSuggestions +
                shell.sonicPathBuilder.endSuggestions + shell.sonicMixBuilder.suggestedTracks).map { it.id })
            assertTrue(shell.sonicPathBuilder.pathTracks.isEmpty())
            assertTrue(shell.sonicMixBuilder.mixTracks.isEmpty())
            assertTrue(fixture.played.isEmpty())
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun bothFailedPathQueriesAreClaimedOnceEvenWhenFirstRecoveryPopulatesCandidates() = runTest {
        val provider = SonicTestProvider()
        provider.searchResult = { error("Offline") }
        val fixture = fixture(provider)
        val router = fixture.router(this)
        for ((_, change, search) in candidateSearches().take(2)) {
            router.dispatch(change)
            router.execute(search)
        }
        provider.searchResult = { MediaSearchResults(tracks = listOf(sonicTrack(it))) }
        val page = NaviampRoutePage(NaviampRoute.SonicPath)
        repeat(3) { router.onPageEntered(page) }
        runCurrent()
        assertEquals(listOf("start", "end", "start", "end"), provider.searches)
        assertEquals("start", fixture.store.state.value.shell.sonicPathBuilder.startSuggestions.single().id)
        assertEquals("end", fixture.store.state.value.shell.sonicPathBuilder.endSuggestions.single().id)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun successfulEmptyAndLoadedSonicCandidatesNeverRecover() = runTest {
        for (empty in listOf(true, false)) {
            for ((route, change, search) in candidateSearches()) {
                val provider = SonicTestProvider()
                provider.searchResult = { MediaSearchResults(tracks = if (empty) emptyList() else listOf(sonicTrack("cached"))) }
                val fixture = fixture(provider)
                val router = fixture.router(this)
                router.dispatch(change)
                router.execute(search)
                repeat(2) { router.onPageEntered(NaviampRoutePage(route)) }
                runCurrent()
                assertEquals(1, provider.searches.size)
                if (!empty) {
                    provider.searchResult = { error("Offline") }
                    router.execute(search)
                    router.onPageEntered(NaviampRoutePage(route))
                    runCurrent()
                    assertEquals(2, provider.searches.size)
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun canceledSonicRecoveryRemainsEligibleWithoutParallelSearches() = runTest {
        for ((route, change, search) in candidateSearches()) {
            val provider = SonicTestProvider()
            provider.searchResult = { error("Offline") }
            val fixture = fixture(provider)
            fixture.controller.dispatch(change)
            fixture.controller.execute(search)
            val result = CompletableDeferred<MediaSearchResults>()
            provider.searchResult = { result.await() }
            val page = NaviampRoutePage(route)
            val retry = fixture.controller.recoverEmptyPage(page).single()
            val job = launch { fixture.controller.execute(retry) }
            runCurrent()
            repeat(3) { assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty()) }
            fixture.controller.execute(search)
            assertEquals(2, provider.searches.size)
            job.cancelAndJoin()
            assertFalse(fixture.store.state.value.shell.sonicPathBuilder.loading)
            assertFalse(fixture.store.state.value.shell.sonicMixBuilder.loading)
            val next = fixture.controller.recoverEmptyPage(page).single()
            result.complete(MediaSearchResults(tracks = listOf(sonicTrack("recovered"))))
            fixture.controller.execute(next)
            assertEquals(3, provider.searches.size)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun sonicRecoveryRejectsQueuedAndInFlightOldQueriesAndSources() = runTest {
        for ((route, change, search) in candidateSearches()) {
            for (sourceReset in listOf(false, true)) {
                val provider = SonicTestProvider()
                provider.searchResult = { error("Offline") }
                val fixture = fixture(provider)
                fixture.controller.dispatch(change)
                fixture.controller.execute(search)
                val page = NaviampRoutePage(route)
                val retry = fixture.controller.recoverEmptyPage(page).single()
                fun changeContext() {
                    if (sourceReset) {
                        provider.cacheNamespace += "-new"
                        fixture.controller.resetForSourceChange()
                    } else fixture.controller.dispatch(changeQuery(search, "new-${provider.searches.size}"))
                }
                changeContext()
                fixture.controller.execute(retry)
                assertEquals(1, provider.searches.size)
                assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
                fixture.controller.dispatch(change)
                val result = CompletableDeferred<MediaSearchResults>()
                provider.searchResult = { result.await() }
                val job = launch { fixture.controller.execute(search) }
                runCurrent()
                changeContext()
                result.complete(MediaSearchResults(tracks = listOf(sonicTrack("old"))))
                job.join()
                val shell = fixture.store.state.value.shell
                assertTrue(shell.sonicPathBuilder.startSuggestions.isEmpty())
                assertTrue(shell.sonicPathBuilder.endSuggestions.isEmpty())
                assertTrue(shell.sonicMixBuilder.suggestedTracks.isEmpty())
                assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
            }
        }
    }

    @Test
    fun selectedSeedsPreventFailedEmptyCandidateRecovery() = runTest {
        val provider = SonicTestProvider()
        val fixture = fixture(provider)
        fixture.controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeStartQuery("start")))
        fixture.controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchStart))
        fixture.controller.dispatch(path(NaviampCoreCommand.SonicPathAction.SelectStart(
            fixture.store.state.value.shell.sonicPathBuilder.startSuggestions.first())))
        fixture.controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.ChangeQuery("seed")))
        fixture.controller.execute(mix(NaviampCoreCommand.SonicMixAction.Search))
        fixture.store.state.value.shell.sonicMixBuilder.suggestedTracks.forEach {
            fixture.controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.Select(it)))
        }
        provider.searchResult = { error("Offline") }
        fixture.controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeEndQuery("end")))
        fixture.controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchEnd))
        fixture.controller.execute(mix(NaviampCoreCommand.SonicMixAction.Search))
        assertTrue(fixture.controller.recoverEmptyPage(NaviampRoutePage(NaviampRoute.SonicPath)).isEmpty())
        assertTrue(fixture.controller.recoverEmptyPage(NaviampRoutePage(NaviampRoute.SonicMix)).isEmpty())
    }

    private fun candidateSearches() = listOf(
        Triple(NaviampRoute.SonicPath, path(NaviampCoreCommand.SonicPathAction.ChangeStartQuery("start")),
            path(NaviampCoreCommand.SonicPathAction.SearchStart)),
        Triple(NaviampRoute.SonicPath, path(NaviampCoreCommand.SonicPathAction.ChangeEndQuery("end")),
            path(NaviampCoreCommand.SonicPathAction.SearchEnd)),
        Triple(NaviampRoute.SonicMix, mix(NaviampCoreCommand.SonicMixAction.ChangeQuery("seed")),
            mix(NaviampCoreCommand.SonicMixAction.Search)),
    )

    private fun changeQuery(search: NaviampCoreCommand, query: String): NaviampCoreCommand = when (search) {
        path(NaviampCoreCommand.SonicPathAction.SearchStart) -> path(NaviampCoreCommand.SonicPathAction.ChangeStartQuery(query))
        path(NaviampCoreCommand.SonicPathAction.SearchEnd) -> path(NaviampCoreCommand.SonicPathAction.ChangeEndQuery(query))
        else -> mix(NaviampCoreCommand.SonicMixAction.ChangeQuery(query))
    }

    private fun SonicFixture.router(scope: CoroutineScope): NaviampCoreCommandRouter {
        lateinit var router: NaviampCoreCommandRouter
        val navigation = NaviampCoreNavigationController(
            NaviampNavigationController(), store,
            restoreCommand = { router.dispatch(it) },
            onPageEntered = { router.onPageEntered(it) },
        )
        router = NaviampCoreCommandRouter(scope, listOf(navigation, controller))
        return router
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun pathRecoveryWaitsForTheOtherInFlightCandidateSearch() = runTest {
        val provider = SonicTestProvider()
        val endResult = CompletableDeferred<MediaSearchResults>()
        provider.searchResult = { query ->
            if (query == "start") error("Offline")
            endResult.await()
        }
        val fixture = fixture(provider)
        fixture.controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeStartQuery("start")))
        fixture.controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeEndQuery("end")))
        val endJob = launch { fixture.controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchEnd)) }
        runCurrent()
        fixture.controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchStart))
        assertTrue(fixture.store.state.value.shell.sonicPathBuilder.loading)
        val page = NaviampRoutePage(NaviampRoute.SonicPath)
        assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
        endResult.complete(MediaSearchResults())
        endJob.join()
        assertFalse(fixture.store.state.value.shell.sonicPathBuilder.loading)
        val retry = fixture.controller.recoverEmptyPage(page).single()
        provider.searchResult = { MediaSearchResults(tracks = listOf(sonicTrack("recovered"))) }
        fixture.controller.execute(retry)
        assertEquals(listOf("end", "start", "start"), provider.searches)
    }

    @Test
    fun sonicPathOwnsSearchSelectionBuildPlaybackQueueAndSave() = runTest {
        val fixture = fixture()
        val controller = fixture.controller
        controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeStartQuery("start")))
        controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchStart))
        controller.dispatch(
            path(
                NaviampCoreCommand.SonicPathAction.SelectStart(
                    fixture.store.state.value.shell.sonicPathBuilder.startSuggestions.first(),
                ),
            ),
        )
        controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeEndQuery("end")))
        controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchEnd))
        controller.dispatch(
            path(
                NaviampCoreCommand.SonicPathAction.SelectEnd(
                    fixture.store.state.value.shell.sonicPathBuilder.endSuggestions.last(),
                ),
            ),
        )

        controller.execute(path(NaviampCoreCommand.SonicPathAction.Build))
        controller.execute(path(NaviampCoreCommand.SonicPathAction.Play))
        controller.execute(path(NaviampCoreCommand.SonicPathAction.AddToQueue))
        controller.execute(path(NaviampCoreCommand.SonicPathAction.SaveAsPlaylist("My Path")))

        assertEquals(listOf("start", "middle", "end"), fixture.store.state.value.shell.sonicPathBuilder.pathTracks.map { it.id })
        assertEquals(listOf("sonic path:start,middle,end"), fixture.played)
        assertEquals(listOf("sonic path:start,middle,end"), fixture.queued)
        assertEquals(listOf("My Path:start,middle,end"), fixture.provider.created)
        assertEquals("Saved My Path", fixture.store.state.value.shell.playlists.status)
    }

    @Test
    fun sonicMixOwnsSeedsBiasLengthBuildPlaybackQueueAndSave() = runTest {
        val fixture = fixture()
        val controller = fixture.controller
        controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.ChangeQuery("seed")))
        controller.execute(mix(NaviampCoreCommand.SonicMixAction.Search))
        val suggestions = fixture.store.state.value.shell.sonicMixBuilder.suggestedTracks
        controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.Select(suggestions[0])))
        controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.Select(suggestions[1])))
        controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.ChangeLength(5)))
        controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.ChangeBias(SharedSonicMixBiasUi.Favorites)))
        assertFalse(fixture.store.state.value.shell.sonicMixBuilder.includeSeeds)
        controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.ChangeIncludeSeeds(true)))

        controller.execute(mix(NaviampCoreCommand.SonicMixAction.Build))
        controller.execute(mix(NaviampCoreCommand.SonicMixAction.Play))
        controller.execute(mix(NaviampCoreCommand.SonicMixAction.AddToQueue))
        controller.execute(mix(NaviampCoreCommand.SonicMixAction.SaveAsPlaylist("My Mix")))

        assertEquals(
            listOf("seed-a", "seed-b", "mix-a", "mix-b"),
            fixture.store.state.value.shell.sonicMixBuilder.mixTracks.map { it.id },
        )
        assertEquals("Favorites", fixture.store.state.value.shell.sonicMixBuilder.bias.label)
        assertEquals(listOf("sonic mix:seed-a,seed-b,mix-a,mix-b"), fixture.played)
        assertEquals(listOf("sonic mix:seed-a,seed-b,mix-a,mix-b"), fixture.queued)
        assertEquals("My Mix:seed-a,seed-b,mix-a,mix-b", fixture.provider.created.last())
    }

    @Test
    fun disconnectedAndUnsupportedSonicFeaturesPublishCommonFailures() = runTest {
        val disconnected = fixture(provider = null)
        disconnected.controller.dispatch(path(NaviampCoreCommand.SonicPathAction.ChangeStartQuery("track")))
        disconnected.controller.execute(path(NaviampCoreCommand.SonicPathAction.SearchStart))
        assertEquals(
            "Connect to Navidrome to use Sonic features.",
            disconnected.store.state.value.shell.sonicPathBuilder.status,
        )

        val unsupported = fixture(SonicTestProvider(supportsSonic = false))
        unsupported.controller.dispatch(mix(NaviampCoreCommand.SonicMixAction.ChangeQuery("track")))
        unsupported.controller.execute(mix(NaviampCoreCommand.SonicMixAction.Search))
        assertEquals(
            "The connected server does not support Sonic features.",
            unsupported.store.state.value.shell.sonicMixBuilder.status,
        )
    }

    private fun fixture(provider: SonicTestProvider? = SonicTestProvider()): SonicFixture {
        val store = NaviampCoreStateStore()
        val source = NaviampCoreMediaProviderSource { provider }
        val navigation = NaviampCoreNavigationController(
            NaviampNavigationController(),
            store,
            restoreCommand = { error("Not expected") },
        )
        val browse = NaviampCorePlaylistBrowseController(store, source, navigation)
        val played = mutableListOf<String>()
        val queued = mutableListOf<String>()
        return SonicFixture(
            store = store,
            provider = provider ?: SonicTestProvider(),
            controller = NaviampCoreSonicBuilderController(
                stateStore = store,
                providerSource = source,
                playlistBrowseController = browse,
                playback = NaviampCoreSonicPlaybackPort { tracks, label ->
                    played += "$label:${tracks.joinToString(",") { it.id.value }}"
                },
                queue = NaviampCoreSonicQueuePort { tracks, label ->
                    queued += "$label:${tracks.joinToString(",") { it.id.value }}"
                },
            ),
            played = played,
            queued = queued,
        )
    }

    private fun path(action: NaviampCoreCommand.SonicPathAction) = NaviampCoreCommand.MixBuilder.SonicPath(action)
    private fun mix(action: NaviampCoreCommand.SonicMixAction) = NaviampCoreCommand.MixBuilder.SonicMix(action)
}

private data class SonicFixture(
    val store: NaviampCoreStateStore,
    val provider: SonicTestProvider,
    val controller: NaviampCoreSonicBuilderController,
    val played: List<String>,
    val queued: List<String>,
)

private class SonicTestProvider(
    supportsSonic: Boolean = true,
) : MediaProvider {
    override val id = ProviderId("sonic")
    override val displayName = "Sonic"
    override val capabilities = ProviderCapabilities(
        false,
        false,
        false,
        false,
        false,
        supportsSonicSimilarity = supportsSonic,
    )
    val created = mutableListOf<String>()
    override var cacheNamespace = "sonic"
    val searches = mutableListOf<String>()
    var searchResult: (suspend (String) -> MediaSearchResults)? = null
    private val start = sonicTrack("start")
    private val end = sonicTrack("end")

    override suspend fun validateConnection() = ConnectionValidation(null, null)
    override suspend fun recentlyAddedAlbums(limit: Int) = emptyList<Album>()
    override suspend fun album(albumId: AlbumId): AlbumDetails = error("Not used")
    override suspend fun artist(artistId: ArtistId): ArtistDetails = error("Not used")
    override suspend fun artists(limit: Int) = emptyList<Artist>()
    override suspend fun tracks(limit: Int) = emptyList<Track>()
    override suspend fun search(query: String, limit: Int): MediaSearchResults {
        searches += query
        searchResult?.let { return it(query) }
        return when (query) {
            "start" -> MediaSearchResults(tracks = listOf(start, sonicTrack("other-start")))
            "end" -> MediaSearchResults(tracks = listOf(sonicTrack("other-end"), end))
            else -> MediaSearchResults(tracks = listOf(sonicTrack("seed-a"), sonicTrack("seed-b")))
        }
    }

    override suspend fun findSonicPath(startTrackId: TrackId, endTrackId: TrackId, count: Int) =
        listOf(SonicPathMatch(start), SonicPathMatch(sonicTrack("middle")), SonicPathMatch(end))

    override suspend fun sonicSimilarTrackMatches(trackId: TrackId, count: Int) = when (trackId.value) {
        "seed-a" -> listOf(SonicSimilarTrack(sonicTrack("mix-a"), 0.9))
        else -> listOf(SonicSimilarTrack(sonicTrack("mix-b"), 0.8))
    }

    override suspend fun createPlaylist(name: String, trackIds: List<TrackId>): Playlist {
        created += "$name:${trackIds.joinToString(",") { it.value }}"
        return Playlist("created", name, trackIds.size)
    }

    override suspend fun playlists(limit: Int) = emptyList<Playlist>()
    override suspend fun streamUrl(request: StreamRequest) = "https://stream.example"
    override fun coverArtUrl(coverArtId: String) = "https://art.example/$coverArtId"
}

private fun sonicTrack(id: String) = Track(
    id = TrackId(id),
    title = id,
    artistName = "Artist $id",
    albumTitle = "Album $id",
    durationSeconds = 180,
    coverArtId = null,
    audioInfo = null,
    replayGain = null,
)
