package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationController
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.ui.toSharedRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Genre
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.albummix.AlbumMixBuilderService
import app.naviamp.domain.artistmix.ArtistMixBuilderService
import app.naviamp.domain.genremix.GenreMixBuilderService
import app.naviamp.domain.library.LibraryGenreOntologyNode
import app.naviamp.domain.library.LibraryGenreOntologyAudit
import app.naviamp.domain.library.LibraryGenreOntologyProjection
import app.naviamp.domain.popular.ArtistPopularTrackCandidate
import app.naviamp.domain.popular.ArtistPopularTracksClient
import app.naviamp.domain.popular.ArtistPopularTracksResult
import app.naviamp.domain.popular.ArtistPopularTracksService
import app.naviamp.domain.popular.SessionArtistPopularTracksRepository
import app.naviamp.domain.popular.SimilarArtistCandidate
import app.naviamp.domain.popular.SimilarArtistsClient
import app.naviamp.domain.popular.SimilarArtistsService
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaviampCoreStandardMixControllerTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun failedCandidateSearchesRecoverThroughNavigationOnce() = runTest {
        for (route in listOf(NaviampRoute.ArtistMix, NaviampRoute.AlbumMix, NaviampRoute.GenreMix)) {
            var failed = true
            var calls = 0
            suspend fun request() { calls++; check(!failed) { "Offline" } }
            val fixture = fixture(
                artistCandidates = { request(); listOf(Artist(ArtistId("artist"), "Artist")) },
                albumCandidates = { request(); listOf(Album(AlbumId("album"), "Album", "Artist", null, null)) },
                genreService = GenreMixBuilderService { request(); listOf(Genre("Ambient")) },
            )
            val router = fixture.router(this)
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(route.toSharedRoute()))
            assertEquals(0, calls, "Unattempted $route must not search")
            router.execute(search(route))
            assertEquals(1, calls)
            val destination = route
            assertTrue(fixture.controller.recoverEmptyPage(object : NaviampNavigationPage {
                override val route = destination
            }).isEmpty())
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(NaviampRoute.Home.toSharedRoute()))
            failed = false
            repeat(3) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(route.toSharedRoute())) }
            assertEquals(1, calls, "Navigation only schedules the retry")
            runCurrent()
            assertEquals(2, calls, route.name)
            assertTrue(fixture.controller.recoverEmptyPage(NaviampRoutePage(route)).isEmpty())
            assertTrue(when (route) {
                NaviampRoute.ArtistMix -> fixture.store.state.value.shell.artistMixBuilder.suggestedArtists.isNotEmpty()
                NaviampRoute.AlbumMix -> fixture.store.state.value.shell.albumMixBuilder.suggestedAlbums.isNotEmpty()
                else -> fixture.store.state.value.shell.genreMixBuilder.suggestedGenres.isNotEmpty()
            })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun successfulEmptyAndLoadedCandidatesNeverRecover() = runTest {
        for (empty in listOf(true, false)) {
            var calls = 0
            var failed = false
            suspend fun request() { calls++; check(!failed) }
            val fixture = fixture(
                artistCandidates = { request(); if (empty) emptyList() else listOf(Artist(ArtistId("a"), "A")) },
                albumCandidates = { request(); if (empty) emptyList() else listOf(Album(AlbumId("a"), "A", "A", null, null)) },
                genreService = GenreMixBuilderService { request(); if (empty) emptyList() else listOf(Genre("A")) },
            )
            val router = fixture.router(this)
            for (route in listOf(NaviampRoute.ArtistMix, NaviampRoute.AlbumMix, NaviampRoute.GenreMix)) {
                router.execute(search(route))
                repeat(2) { router.onPageEntered(NaviampRoutePage(route)) }
            }
            runCurrent()
            assertEquals(3, calls)
            if (!empty) {
                failed = true
                for (route in listOf(NaviampRoute.ArtistMix, NaviampRoute.AlbumMix, NaviampRoute.GenreMix)) {
                    router.execute(search(route))
                    router.onPageEntered(NaviampRoutePage(route))
                }
                runCurrent()
                assertEquals(6, calls, "A failed refresh must retain loaded candidates without retrying")
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun canceledRecoveryRemainsEligibleAndInFlightEntryIsSingleFlight() = runTest {
        var calls = 0
        val result = CompletableDeferred<List<Genre>>()
        val fixture = fixture(genreService = GenreMixBuilderService {
            calls++
            if (calls == 1) error("Offline")
            result.await()
        })
        val page = NaviampRoutePage(NaviampRoute.GenreMix)
        fixture.controller.execute(search(page.route))
        val retry = fixture.controller.recoverEmptyPage(page).single()
        val job = launch { fixture.controller.execute(retry) }
        runCurrent()
        repeat(3) { assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty()) }
        fixture.controller.execute(search(page.route))
        assertEquals(2, calls)
        job.cancelAndJoin()
        assertFalse(fixture.store.state.value.shell.genreMixBuilder.loading)
        val next = fixture.controller.recoverEmptyPage(page).single()
        result.complete(listOf(Genre("Recovered")))
        fixture.controller.execute(next)
        assertEquals(3, calls)
        assertEquals("Recovered", fixture.store.state.value.shell.genreMixBuilder.suggestedGenres.single().title)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun queuedAndInFlightRecoveryCannotCrossQueryOrSourceReset() = runTest {
        for (sourceReset in listOf(false, true)) {
            var calls = 0
            val result = CompletableDeferred<List<Genre>>()
            val fixture = fixture(genreService = GenreMixBuilderService {
                calls++
                if (calls == 1) error("Offline")
                result.await()
            })
            val page = NaviampRoutePage(NaviampRoute.GenreMix)
            fixture.controller.execute(search(page.route))
            val queued = fixture.controller.recoverEmptyPage(page).single()
            if (sourceReset) fixture.controller.resetForSourceChange()
            else fixture.controller.dispatch(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.ChangeQuery("new")))
            fixture.controller.execute(queued)
            assertEquals(1, calls, "A queued old request must not become a new search")
            assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
            val loading = launch { fixture.controller.execute(search(page.route)) }
            runCurrent()
            if (sourceReset) fixture.controller.resetForSourceChange()
            else fixture.controller.dispatch(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.ChangeQuery("other")))
            result.complete(listOf(Genre("Old")))
            loading.join()
            assertTrue(fixture.store.state.value.shell.genreMixBuilder.suggestedGenres.isEmpty())
            assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
        }
    }

    @Test
    fun failedInitialArtistAcquisitionRetriesInitialRatherThanTypedQuery() = runTest {
        val queries = mutableListOf<String>()
        val fixture = fixture(artistCandidates = { query ->
            queries += query
            if (queries.size == 1) error("Offline")
            listOf(Artist(ArtistId("a"), "A"))
        })
        fixture.controller.dispatch(NaviampCoreCommand.MixBuilder.Artist(NaviampCoreCommand.ArtistAction.ChangeQuery("unsubmitted")))
        fixture.controller.initializeArtist()
        fixture.controller.execute(fixture.controller.recoverEmptyPage(NaviampRoutePage(NaviampRoute.ArtistMix)).single())
        assertEquals(listOf("", ""), queries)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun artistAndAlbumRecoveryPreserveCancellationAndRejectChangedContext() = runTest {
        for (route in listOf(NaviampRoute.ArtistMix, NaviampRoute.AlbumMix)) {
            for (sourceReset in listOf(false, true)) {
                var calls = 0
                val result = CompletableDeferred<Unit>()
                suspend fun request() {
                    calls++
                    if (calls == 1) error("Offline")
                    result.await()
                }
                val fixture = fixture(
                    artistCandidates = { request(); listOf(Artist(ArtistId("old"), "Old")) },
                    albumCandidates = { request(); listOf(Album(AlbumId("old"), "Old", "Old", null, null)) },
                )
                val page = NaviampRoutePage(route)
                fixture.controller.execute(search(route))
                val retry = fixture.controller.recoverEmptyPage(page).single()
                val canceled = launch { fixture.controller.execute(retry) }
                runCurrent()
                assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
                fixture.controller.execute(search(route))
                assertEquals(2, calls)
                canceled.cancelAndJoin()
                val queued = fixture.controller.recoverEmptyPage(page).single()
                fun changeContext() {
                    if (sourceReset) fixture.controller.resetForSourceChange()
                    else fixture.controller.dispatch(when (route) {
                        NaviampRoute.ArtistMix -> NaviampCoreCommand.MixBuilder.Artist(NaviampCoreCommand.ArtistAction.ChangeQuery("new-$calls"))
                        else -> NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.ChangeQuery("new-$calls"))
                    })
                }
                changeContext()
                fixture.controller.execute(queued)
                assertEquals(2, calls)
                assertTrue(fixture.controller.recoverEmptyPage(page).isEmpty())
                val loading = launch { fixture.controller.execute(search(route)) }
                runCurrent()
                changeContext()
                result.complete(Unit)
                loading.join()
                assertTrue(fixture.store.state.value.shell.artistMixBuilder.suggestedArtists.isEmpty())
                assertTrue(fixture.store.state.value.shell.albumMixBuilder.suggestedAlbums.isEmpty())
            }
        }
    }

    private fun search(route: NaviampRoute): NaviampCoreCommand = when (route) {
        NaviampRoute.ArtistMix -> NaviampCoreCommand.MixBuilder.Artist(NaviampCoreCommand.ArtistAction.Search)
        NaviampRoute.AlbumMix -> NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.Search)
        NaviampRoute.GenreMix -> NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Search)
        else -> error("Not a standard builder")
    }

    private fun StandardMixFixture.router(scope: CoroutineScope): NaviampCoreCommandRouter {
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
    fun sourceChangeDiscardsAnOldAccountsPendingSuggestions() = runTest {
        val result = kotlinx.coroutines.CompletableDeferred<List<Genre>>()
        val fixture = fixture(genreService = GenreMixBuilderService { result.await() })
        val loading = launch { fixture.controller.initializeGenre() }
        runCurrent()
        fixture.controller.resetForSourceChange()
        result.complete(listOf(Genre("Old account genre")))
        loading.join()
        assertEquals(emptyList(), fixture.store.state.value.shell.genreMixBuilder.suggestedGenres)
        assertEquals(emptyList(), fixture.store.state.value.shell.genreMixBuilder.selectedGenres)
    }

    @Test
    fun genreSongsBrowseAndPlayThroughCoreAndResetWithSelection() = runTest {
        val base = FakeCoreMediaProvider()
        val tags = mutableListOf<String>()
        val provider = object : app.naviamp.domain.provider.MediaProvider by base {
            override val capabilities = base.capabilities.copy(supportsGenreTrackBrowsing = true)
            override suspend fun genreTracksPage(genre: String, request: app.naviamp.domain.provider.MediaPageRequest): app.naviamp.domain.provider.MediaPage<Track> {
                tags += genre
                return app.naviamp.domain.provider.MediaPage(listOf(track("one"), track("two")), request.offset, request.limit, false)
            }
        }
        val fixture = fixture(provider = provider)
        fixture.controller.initializeGenre()
        val genre = fixture.store.state.value.shell.genreMixBuilder.suggestedGenres.first()
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Select(genre)))
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.BrowseSongs))
        assertEquals(listOf("Ambient"), tags)
        assertEquals(listOf("one", "two"), fixture.store.state.value.shell.genreMixBuilder.songs.map { it.id })
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.PlaySong("two")))
        assertEquals(listOf("one,two:1"), fixture.playback.genreSongPlays)
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Remove(genre)))
        assertEquals(emptyList(), fixture.store.state.value.shell.genreMixBuilder.songs)
    }

    @Test
    fun artistBuilderOwnsSuggestionsSelectionTracksAndPlaybackIntent() = runTest {
        val fixture = fixture()
        fixture.controller.initializeArtist()

        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Artist(
                NaviampCoreCommand.ArtistAction.Select(fixture.store.state.value.shell.artistMixBuilder.suggestedArtists.first()),
            ),
        )
        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Artist(NaviampCoreCommand.ArtistAction.Play),
        )

        val ui = fixture.store.state.value.shell.artistMixBuilder
        assertEquals(listOf("artist-a"), ui.selectedArtists.map { it.id })
        assertEquals(listOf("artist-b"), ui.suggestedArtists.map { it.id })
        assertEquals(listOf("artist-a:popular-artist-a"), fixture.playback.artistPlays)
        assertNull(ui.status)
    }

    @Test
    fun albumBuilderOwnsSearchSelectionSeedTracksAndPlaybackIntent() = runTest {
        val fixture = fixture()
        fixture.controller.dispatch(
            NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.ChangeQuery("Album A")),
        )
        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.Search),
        )
        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Album(
                NaviampCoreCommand.AlbumAction.Select(fixture.store.state.value.shell.albumMixBuilder.suggestedAlbums.first()),
            ),
        )
        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.Play),
        )

        val ui = fixture.store.state.value.shell.albumMixBuilder
        assertEquals("Album A", ui.query)
        assertEquals(listOf("album-a"), ui.selectedAlbums.map { it.id })
        assertEquals(listOf("album-b"), ui.suggestedAlbums.map { it.id })
        assertEquals(listOf("album-a:track-album-a"), fixture.playback.albumPlays)
    }

    @Test
    fun genreBuilderOwnsFilteringSelectionResetAndPlaybackIntent() = runTest {
        val fixture = fixture()
        fixture.controller.dispatch(
            NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.ChangeQuery("amb")),
        )
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Search))
        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Genre(
                NaviampCoreCommand.GenreAction.Select(fixture.store.state.value.shell.genreMixBuilder.suggestedGenres.single()),
            ),
        )
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Play))

        assertEquals(listOf("Ambient"), fixture.playback.genrePlays)
        assertEquals(listOf("Ambient"), fixture.store.state.value.shell.genreMixBuilder.selectedGenres.map { it.title })
    }

    @Test
    fun genreBuilderBrowsesAncestorsAndPlaysTheOriginalServerGenreName() = runTest {
        val projection = LibraryGenreOntologyProjection(
            nodes = listOf(
                LibraryGenreOntologyNode("music", "Music", emptyList(), emptyList(), listOf("rock")),
                LibraryGenreOntologyNode("rock", "Rock", listOf("Rock"), listOf("music"), listOf("dream-pop", "shoegaze")),
                LibraryGenreOntologyNode(
                    "dream-pop",
                    "Dream Pop",
                    listOf("Dream-Pop"),
                    listOf("rock"),
                    emptyList(),
                    albumCount = 12,
                    trackCount = 345,
                ),
                LibraryGenreOntologyNode(
                    "shoegaze",
                    "Shoegaze",
                    listOf("Shoegaze", "Nu Gaze"),
                    listOf("rock"),
                    emptyList(),
                ),
            ),
            rootIds = listOf("music"),
            unmatchedGenreNames = listOf("Server Only"),
            audit = LibraryGenreOntologyAudit(
                inventoryGenreCount = 5,
                exactMatchCount = 4,
                unmatchedCount = 1,
                projectedRootCount = 1,
                largestSelectableGroupSize = 4,
            ),
        )
        val genreService = GenreMixBuilderService(
            genres = { listOf(Genre("Rock"), Genre("Dream-Pop"), Genre("Shoegaze"), Genre("Nu Gaze"), Genre("Server Only")) },
            ontologyProjection = { projection },
        )
        val fixture = fixture(genreService)

        fixture.controller.initializeGenre()
        var ui = fixture.store.state.value.shell.genreMixBuilder
        assertEquals(listOf("Music"), ui.treeRows.map { it.title })
        assertEquals(listOf("Server Only"), ui.unmatchedGenres.map { it.title })

        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.ToggleBranch("music")),
        )
        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.ToggleBranch("rock")),
        )
        ui = fixture.store.state.value.shell.genreMixBuilder
        assertEquals(listOf("Music", "Rock", "Dream Pop", "Shoegaze"), ui.treeRows.map { it.title })
        assertEquals(listOf(0, 1, 2, 2), ui.treeRows.map { it.depth })
        assertEquals(4, ui.treeRows[1].selectableGenreCount)
        assertEquals("345 tracks · 12 albums", ui.treeRows[2].subtitle)

        fixture.controller.execute(
            NaviampCoreCommand.MixBuilder.Genre(
                NaviampCoreCommand.GenreAction.SelectBranch("rock"),
            ),
        )
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Play))

        assertEquals(listOf("Dream-Pop", "Nu Gaze", "Rock", "Shoegaze"), fixture.playback.genrePlays)
        assertEquals(true, fixture.store.state.value.shell.genreMixBuilder.treeRows[1].selected)
    }

    @Test
    fun genreBuilderKeepsTheCompleteFlatBrowserWhenTheOntologyIsNotUseful() = runTest {
        val genres = listOf(Genre("Rock"), Genre("Jazz"), Genre("Server Only"))
        val projection = LibraryGenreOntologyProjection(
            nodes = listOf(
                LibraryGenreOntologyNode("rock", "Rock", listOf("Rock"), emptyList(), emptyList()),
                LibraryGenreOntologyNode("jazz", "Jazz", listOf("Jazz"), emptyList(), emptyList()),
            ),
            rootIds = listOf("rock", "jazz"),
            unmatchedGenreNames = listOf("Server Only"),
            audit = LibraryGenreOntologyAudit(
                inventoryGenreCount = 3,
                exactMatchCount = 2,
                unmatchedCount = 1,
                projectedRootCount = 2,
                largestSelectableGroupSize = 1,
            ),
        )
        val fixture = fixture(
            GenreMixBuilderService(genres = { genres }, ontologyProjection = { projection }),
        )

        fixture.controller.initializeGenre()

        val ui = fixture.store.state.value.shell.genreMixBuilder
        assertEquals(emptyList(), ui.treeRows)
        assertEquals(emptyList(), ui.unmatchedGenres)
        assertEquals(listOf("Rock", "Jazz", "Server Only"), ui.suggestedGenres.map { it.title })
    }

    @Test
    fun emptyBuilderPlaybackProducesCommonValidationStatus() = runTest {
        val fixture = fixture()

        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Artist(NaviampCoreCommand.ArtistAction.Play))
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Album(NaviampCoreCommand.AlbumAction.Play))
        fixture.controller.execute(NaviampCoreCommand.MixBuilder.Genre(NaviampCoreCommand.GenreAction.Play))

        assertEquals("Select artists with matched songs first.", fixture.store.state.value.shell.artistMixBuilder.status)
        assertEquals("Select albums with matched songs first.", fixture.store.state.value.shell.albumMixBuilder.status)
        assertEquals("Select at least one genre first.", fixture.store.state.value.shell.genreMixBuilder.status)
    }

    private fun fixture(
        genreService: GenreMixBuilderService = GenreMixBuilderService {
            listOf(Genre("Ambient"), Genre("Rock"))
        },
        provider: app.naviamp.domain.provider.MediaProvider? = null,
        artistCandidates: (suspend (String) -> List<Artist>)? = null,
        albumCandidates: (suspend (String) -> List<Album>)? = null,
    ): StandardMixFixture {
        val artistA = Artist(ArtistId("artist-a"), "Artist A")
        val artistB = Artist(ArtistId("artist-b"), "Artist B")
        val albumA = Album(AlbumId("album-a"), "Album A", "Artist A", null, null)
        val albumB = Album(AlbumId("album-b"), "Album B", "Artist B", null, null)
        val similar = SimilarArtistsService(
            libraryArtistsSearch = { query, _ -> listOf(artistA, artistB).filter { it.name.contains(query) } },
            client = object : SimilarArtistsClient {
                override suspend fun similarArtists(artist: Artist, limit: Int) = listOf(
                    SimilarArtistCandidate("test", "artist-b", "Artist B"),
                )
            },
        )
        val popular = ArtistPopularTracksService(
            repository = SessionArtistPopularTracksRepository(),
            libraryTracksForArtist = { artist, _ -> listOf(track("popular-${artist.id.value}")) },
            client = object : ArtistPopularTracksClient {
                override val source = "test"
                override suspend fun popularTracks(artist: Artist, limit: Int): ArtistPopularTracksResult {
                    val id = "popular-${artist.id.value}"
                    val candidate = ArtistPopularTrackCandidate("test", id, 1, id)
                    return ArtistPopularTracksResult("test", listOf(candidate), mapOf(id to track(id)))
                }
            },
            nowMillis = { 0 },
        )
        val artistService = ArtistMixBuilderService(
            sourceId = { "source" },
            artistSearch = { query, _ -> artistCandidates?.invoke(query) ?: listOf(artistA, artistB).filter { it.name.contains(query, true) } },
            randomArtists = { artistCandidates?.invoke("") ?: listOf(artistA, artistB) },
            popularTracksService = popular,
            similarArtistsService = similar,
        )
        val albumService = AlbumMixBuilderService(
            albumSearch = { query, _ -> albumCandidates?.invoke(query) ?: listOf(albumA, albumB).filter { it.title.contains(query, true) } },
            randomAlbums = { albumCandidates?.invoke("") ?: listOf(albumA, albumB) },
            albumsForArtist = { artist, _ -> listOf(albumB).filter { it.artistName == artist.name } },
            albumTracks = { album, _ -> listOf(track("track-${album.id.value}")) },
            similarArtistsService = similar,
        )
        val store = NaviampCoreStateStore()
        val playback = StandardMixTestPlayback()
        return StandardMixFixture(
            store,
            NaviampCoreStandardMixController(
                stateStore = store,
                providerSource = NaviampCoreMediaProviderSource { provider },
                artistService = { artistService },
                albumService = { albumService },
                genreService = { genreService },
                playback = playback,
            ),
            playback,
        )
    }
}

private data class StandardMixFixture(
    val store: NaviampCoreStateStore,
    val controller: NaviampCoreStandardMixController,
    val playback: StandardMixTestPlayback,
)

private class StandardMixTestPlayback : NaviampCoreStandardMixPlaybackPort {
    val artistPlays = mutableListOf<String>()
    val albumPlays = mutableListOf<String>()
    var genrePlays = emptyList<String>()
    val genreSongPlays = mutableListOf<String>()

    override suspend fun playArtistMix(artists: List<Artist>, seedTracks: List<Track>) {
        artistPlays += "${artists.joinToString { it.id.value }}:${seedTracks.joinToString { it.id.value }}"
    }

    override suspend fun playAlbumMix(albums: List<Album>, seedTracks: List<Track>) {
        albumPlays += "${albums.joinToString { it.id.value }}:${seedTracks.joinToString { it.id.value }}"
    }

    override suspend fun playGenreMix(genres: List<Genre>) {
        genrePlays = genres.map(Genre::name)
    }
    override suspend fun playGenreSongs(tracks: List<Track>, startIndex: Int) {
        genreSongPlays += "${tracks.joinToString(",") { it.id.value }}:$startIndex"
    }
}

private fun track(id: String) = Track(
    id = TrackId(id),
    title = id,
    artistName = "Artist",
    albumTitle = "Album",
    durationSeconds = 180,
    coverArtId = null,
    audioInfo = null,
    replayGain = null,
)
