package app.naviamp.domain.home

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Genre
import app.naviamp.domain.InternetRadioStation
import app.naviamp.domain.Playlist
import app.naviamp.domain.ProviderId
import app.naviamp.domain.StreamRequest
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.provider.AlbumListType
import app.naviamp.domain.provider.ConnectionValidation
import app.naviamp.domain.provider.MediaPage
import app.naviamp.domain.provider.MediaPageRequest
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.ProviderCapabilities
import app.naviamp.domain.radio.libraryRecentRadioStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class HomeServiceTest {
    @Test fun failedDependenciesAreTypedAndSuccessfulEmptyClearsCachedSections() = runTest {
        val provider = object : MediaProvider by FakeHomeProvider() {
            override suspend fun genres(limit: Int): List<Genre> = error("genres unavailable")
            override suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int): List<Album> = error("years unavailable")
        }
        val failed = HomeService(provider, date = HomeDate(2026, 1)).load()
        assertEquals(setOf(HomeSection.Genres, HomeSection.GenreSpotlight, HomeSection.Decade), failed.failedSections)
        val genre = Genre("Rock")
        val previous = HomeContent(genres = listOf(genre), genreSpotlight = genre,
            genreSpotlightAlbums = listOf(album("genre")), decadeAlbums = listOf(album("decade")),
            decadeLabel = "The 1980s", decadeFromYear = 1980, decadeToYear = 1989)
        val merged = failed.retainFailedSections(previous)
        assertEquals(previous.genres, merged.genres)
        assertEquals(previous.genreSpotlight, merged.genreSpotlight)
        assertEquals(previous.genreSpotlightAlbums, merged.genreSpotlightAlbums)
        assertEquals(previous.decadeAlbums, merged.decadeAlbums)
        assertEquals("The 1980s", merged.decadeLabel)
        assertEquals(1980, merged.decadeFromYear)
        assertEquals(1989, merged.decadeToYear)
        val empty = HomeService(FakeHomeProvider(), date = HomeDate(2026, 1)).load().retainFailedSections(previous)
        assertTrue(empty.failedSections.isEmpty())
        assertTrue(empty.genres.isEmpty())
        assertTrue(empty.genreSpotlightAlbums.isEmpty())
        assertNull(empty.genreSpotlight)
        assertTrue(empty.decadeAlbums.isEmpty())
        assertEquals(0, empty.decadeFromYear)
    }

    @Test fun sectionFailuresDoNotDiscardPartialDataOrPlaylistPartitionMetadata() = runTest {
        val previous = HomeService(FakeHomeProvider(playlists = listOf(Playlist("ordinary", "Ordinary", 0), navibeatPlaylist("morning"))), date = HomeDate(2026, 1)).load()
        val failed = HomeContent(recentAlbums = listOf(album("new-partial")),
            failedSections = setOf(HomeSection.Playlists, HomeSection.RecentAlbums, HomeSection.RecentlyAdded))
            .retainFailedSections(previous)
        assertEquals(previous.playlists, failed.playlists)
        assertEquals(previous.navibeatMixes, failed.navibeatMixes)
        assertEquals(previous.recentlyAddedAlbums, failed.recentlyAddedAlbums)
        assertEquals(listOf(album("new-partial")), failed.recentAlbums)
        assertTrue(HomeContent().retainFailedSections(previous).playlists.isEmpty())
    }

    @Test fun cancellationFromEveryCaughtHomeFetchPropagates() = runTest {
        val provider = object : MediaProvider by FakeHomeProvider() {
            override suspend fun genres(limit: Int): List<Genre> = throw CancellationException("cancel genres")
        }
        assertFailsWith<CancellationException> { HomeService(provider, date = HomeDate(2026, 1)).load() }
        val years = object : MediaProvider by FakeHomeProvider() {
            override suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int): List<Album> = throw CancellationException("cancel years")
        }
        assertFailsWith<CancellationException> { HomeService(years, date = HomeDate(2026, 1)).load() }
    }

    @Test
    fun favoriteArtistsUseStableLocalTimestampsAndFallbackAfterProviderFailure() = runTest {
        val stored = Artist(ArtistId("stored"), "Stored", "2026-01-01T00:00:00Z")
        val repository = object : HomeLibraryRepository {
            override fun albumYears(sourceId: String): List<HomeAlbumYear> = emptyList()

            override fun reconcileFavoriteArtists(
                sourceId: String,
                artists: List<Artist>,
                observedAtIso8601: String,
                complete: Boolean,
            ) = artists.map { artist -> artist.copy(favoritedAtIso8601 = observedAtIso8601) }

            override fun locallyKnownFavoriteArtists(sourceId: String, limit: Long) = listOf(stored)
        }
        val provider = FakeHomeProvider(
            supportsArtistFavorites = true,
            favoriteArtists = listOf(Artist(ArtistId("native"), "Native")),
        )

        val native = HomeService(
            provider = provider,
            libraryRepository = repository,
            sourceId = "source",
            date = HomeDate(2026, 1),
            observedAtIso8601 = { "2026-02-01T00:00:00Z" },
        ).load()
        assertEquals("2026-02-01T00:00:00Z", native.favoriteArtists.single().favoritedAtIso8601)

        provider.favoriteFailure = true
        val fallback = HomeService(
            provider = provider,
            libraryRepository = repository,
            sourceId = "source",
            date = HomeDate(2026, 1),
        ).load()
        assertEquals(listOf(stored), fallback.favoriteArtists)
    }

    @Test
    fun favoriteArtistPersistenceFailureDoesNotSuppressHomeContent() = runTest {
        val nativeFavorite = Artist(ArtistId("native"), "Native")
        val repository = object : HomeLibraryRepository {
            override fun albumYears(sourceId: String): List<HomeAlbumYear> = emptyList()

            override fun reconcileFavoriteArtists(
                sourceId: String,
                artists: List<Artist>,
                observedAtIso8601: String,
                complete: Boolean,
            ): List<Artist> = error("favorite artist activity is unavailable")
        }

        val home = HomeService(
            provider = FakeHomeProvider(
                supportsArtistFavorites = true,
                favoriteArtists = listOf(nativeFavorite),
            ),
            libraryRepository = repository,
            sourceId = "source",
            date = HomeDate(2026, 1),
        ).load()

        assertEquals(listOf(nativeFavorite), home.favoriteArtists)
        assertEquals(listOf(album("newest")), home.recentlyAddedAlbums)
        assertEquals(listOf(Playlist("playlist", "Playlist", trackCount = 2)), home.playlists)
    }

    @Test
    fun favoriteAlbumsLoadEveryPageWithoutTheOldFavoriteLimit() = runTest {
        val albums = (5_000 downTo 0).map { index -> album(index.toString().padStart(4, '0')) }
        val provider = FakeHomeProvider(
            supportsTrackFavorites = true,
            supportsAlbumFavorites = true,
            favoriteAlbumPage = { request ->
                val items = albums.subList(request.offset, minOf(request.offset + request.limit, albums.size))
                MediaPage(items, request.offset, request.limit, request.offset + items.size < albums.size)
            },
        )

        val home = HomeService(provider, date = HomeDate(2026, 1)).load()

        assertTrue(home.supportsFavoriteSongs)
        assertFalse(home.favoriteAlbumsFailed)
        assertEquals(albums.reversed().map { it.id }, home.favoriteAlbums.map { it.id })
        assertEquals(5_001, home.favoriteAlbums.size)
        assertTrue(home.favoriteAlbums.all { it.favoritedAtIso8601 == "favorite" })
        assertEquals((0..5_000 step 200).toList(), provider.favoriteAlbumRequests.map { it.offset })
    }

    @Test
    fun favoriteAlbumsDeduplicateAcrossPagesNormalizeMembershipAndSortStableTitles() = runTest {
        val zulu = album("z").copy(title = "Zulu", favoritedAtIso8601 = "2026-01-01T00:00:00Z")
        val alpha = album("a").copy(title = "alpha", favoritedAtIso8601 = "")
        val upperAlpha = album("upper").copy(title = "Alpha")
        val alphaTie = album("b").copy(title = "alpha")
        val provider = FakeHomeProvider(
            supportsAlbumFavorites = true,
            favoriteAlbumPage = { request ->
                val items = if (request.offset == 0) listOf(zulu, alpha) else listOf(zulu, alphaTie, upperAlpha)
                MediaPage(items, request.offset, request.limit, request.offset == 0)
            },
        )

        val home = HomeService(provider, date = HomeDate(2026, 1)).load()

        assertEquals(listOf(upperAlpha.id, alpha.id, alphaTie.id, zulu.id), home.favoriteAlbums.map { it.id })
        assertEquals(listOf("favorite", "favorite", "favorite", zulu.favoritedAtIso8601), home.favoriteAlbums.map { it.favoritedAtIso8601 })
        assertFalse(home.favoriteAlbumsFailed)
        assertFalse(home.supportsFavoriteSongs)
    }

    @Test
    fun favoriteAlbumPageFailureDiscardsPartialMembershipAndPreservesOtherHomeSections() = runTest {
        val provider = FakeHomeProvider(
            supportsAlbumFavorites = true,
            favoriteAlbumPage = { request ->
                if (request.offset > 0) error("favorite album lookup failed")
                MediaPage(listOf(album("partial")), request.offset, request.limit, hasMore = true)
            },
        )

        val home = HomeService(provider, date = HomeDate(2026, 1)).load()

        assertTrue(home.favoriteAlbumsFailed)
        assertTrue(home.favoriteAlbums.isEmpty())
        assertEquals(listOf(album("newest")), home.recentlyAddedAlbums)
        assertEquals(listOf(0, 1), provider.favoriteAlbumRequests.map { it.offset })
    }

    @Test
    fun supportedFavoriteAlbumsReportUnavailableOrMalformedEnumeration() = runTest {
        val invalidPages: List<suspend (MediaPageRequest) -> MediaPage<Album>?> = listOf(
            { null },
            { request -> MediaPage(listOf(album("invalid")), request.offset + 1, request.limit, hasMore = false) },
            { request -> MediaPage(emptyList(), request.offset, request.limit, hasMore = true) },
        )
        for (page in invalidPages) {
            val provider = FakeHomeProvider(supportsAlbumFavorites = true, favoriteAlbumPage = page)

            val home = HomeService(provider, date = HomeDate(2026, 1)).load()

            assertTrue(home.favoriteAlbumsFailed)
            assertTrue(home.favoriteAlbums.isEmpty())
        }
    }

    @Test
    fun unsupportedFavoriteAlbumsAreNotQueriedAndSongCapabilityRemainsIndependent() = runTest {
        val provider = FakeHomeProvider(
            supportsTrackFavorites = true,
            favoriteAlbumPage = { error("unsupported favorites must not be queried") },
        )

        val home = HomeService(provider, date = HomeDate(2026, 1)).load()

        assertTrue(home.supportsFavoriteSongs)
        assertTrue(home.favoriteAlbums.isEmpty())
        assertFalse(home.favoriteAlbumsFailed)
        assertTrue(provider.favoriteAlbumRequests.isEmpty())
    }

    @Test
    fun supportedEmptyFavoriteAlbumCatalogIsNotAFailure() = runTest {
        val provider = FakeHomeProvider(supportsAlbumFavorites = true)

        val home = HomeService(provider, date = HomeDate(2026, 1)).load()

        assertTrue(home.favoriteAlbums.isEmpty())
        assertFalse(home.favoriteAlbumsFailed)
        assertFalse(home.supportsFavoriteSongs)
        assertEquals(1, provider.favoriteAlbumRequests.size)
    }

    @Test
    fun favoriteAlbumLoadingPropagatesCancellation() = runTest {
        val provider = FakeHomeProvider(
            supportsAlbumFavorites = true,
            favoriteAlbumPage = { throw CancellationException("source changed") },
        )

        assertFailsWith<CancellationException> {
            HomeService(provider, date = HomeDate(2026, 1)).load()
        }
    }

    @Test
    fun homeContentIsNotEmptyWhenRecommendationsHaveAnEntryOrStatus() {
        assertTrue(HomeContent().isEmpty)
        assertFalse(HomeContent(supportsFavoriteSongs = true).isEmpty)
        assertFalse(HomeContent(favoriteAlbums = listOf(album("favorite"))).isEmpty)
        assertFalse(HomeContent(favoriteAlbumsFailed = true).isEmpty)
        assertFalse(HomeContent(favoriteArtistsStatus = FavoriteArtistsStatus.Failed).isEmpty)
    }

    @Test
    fun loadUsesSharedArtistLimitAndAggregatesProviderSections() = runTest {
        val provider = FakeHomeProvider()

        val home = HomeService(
            provider = provider,
            date = HomeDate(year = 2026, dayOfYear = 1),
        ).load(
            recentRadioStreams = emptyList(),
            recentInternetRadioStations = listOf(radioStation("recent")),
            artistLimit = 123,
        )

        assertEquals(123, provider.artistLimit)
        assertEquals(listOf(album("newest")), home.recentlyAddedAlbums)
        assertEquals(listOf(album("random-14")), home.mixAlbums)
        assertEquals(listOf(album("recent")), home.recentAlbums)
        assertEquals(listOf(album("frequent")), home.frequentAlbums)
        assertEquals(listOf(album("random-14")), home.randomAlbums)
        assertEquals(listOf(Artist(ArtistId("artist-123"), "Artist 123")), home.artists)
        assertEquals(listOf(Playlist("playlist", "Playlist", trackCount = 2)), home.playlists)
        assertEquals(listOf(radioStation("station")), home.radioStations)
        assertEquals(listOf(radioStation("recent")), home.recentInternetRadioStations)
    }

    @Test
    fun loadUsesDefaultArtistLimit() = runTest {
        val provider = FakeHomeProvider()

        HomeService(
            provider = provider,
            date = HomeDate(year = 2026, dayOfYear = 1),
        ).load()

        assertEquals(HomeDefaultArtistLimit, provider.artistLimit)
    }

    @Test
    fun loadSeparatesNavibeatMixesAndPrioritizesTheCurrentTimeOfDay() = runTest {
        val provider = FakeHomeProvider(
            playlists = listOf(
                Playlist("ordinary", "Ordinary", 2),
                navibeatPlaylist("morning"),
                navibeatPlaylist("evening"),
            ),
        )

        val home = HomeService(
            provider = provider,
            date = HomeDate(year = 2026, dayOfYear = 220, hourOfDay = 20),
        ).load()

        assertEquals(listOf("ordinary"), home.playlists.map(Playlist::id))
        assertEquals(listOf("evening", "morning"), home.navibeatMixes.map { it.metadata.slot })
    }

    @Test
    fun loadHomeContentUsesRequestInputs() = runTest {
        val provider = FakeHomeProvider()

        val recentRadioStream = libraryRecentRadioStream()
        val home = loadHomeContent(
            HomeContentLoadRequest(
                provider = provider,
                date = HomeDate(year = 2026, dayOfYear = 1),
                recentRadioStreams = listOf(recentRadioStream),
                recentInternetRadioStations = listOf(radioStation("recent")),
                artistLimit = 321,
            ),
        )

        assertEquals(321, provider.artistLimit)
        assertEquals(listOf(recentRadioStream), home.recentRadioStreams)
        assertEquals(listOf(radioStation("recent")), home.recentInternetRadioStations)
    }

    @Test
    fun homeContentCoordinatorPublishesLoadingSuccessAndClearStatus() = runTest {
        val statuses = mutableListOf<String?>()
        var content = HomeContent()
        val coordinator = HomeContentCoordinator(
            setHomeContent = { content = it },
            setHomeStatus = { statuses += it },
        )

        coordinator.load(
            HomeContentLoadRequest(
                provider = FakeHomeProvider(),
                date = HomeDate(year = 2026, dayOfYear = 1),
            ),
        )

        assertEquals(listOf<String?>(HomeLoadingStatus, null), statuses)
        assertEquals(listOf(album("newest")), content.recentlyAddedAlbums)
    }

    @Test
    fun homeContentCoordinatorPublishesFailureStatusAndLeavesContentUnchanged() = runTest {
        val statuses = mutableListOf<String?>()
        var content = HomeContent(recentlyAddedAlbums = listOf(album("existing")))
        val coordinator = HomeContentCoordinator(
            setHomeContent = { content = it },
            setHomeStatus = { statuses += it },
        )

        coordinator.load(
            request = HomeContentLoadRequest(
                provider = FakeHomeProvider(),
                date = HomeDate(year = 2026, dayOfYear = 1),
            ),
            loadContent = { error("home failed") },
        )

        assertEquals(listOf<String?>(HomeLoadingStatus, "home failed"), statuses)
        assertEquals(listOf(album("existing")), content.recentlyAddedAlbums)
    }

    @Test
    fun homeLoadFailureStatusUsesFallbackWhenMessageIsMissing() {
        assertEquals(HomeLoadFailureFallbackStatus, homeLoadFailureStatus(Throwable()))
        assertNull(Throwable().message)
    }

    @Test
    fun mixBuilderAlbumCandidatesUseHomeMixSourcesWithStableDeduping() {
        val shared = album("shared")
        val home = HomeContent(
            recentlyPlayedTracks = listOf(recentTrack("played", "recent-album", "recent-artist")),
            randomAlbums = listOf(album("random"), shared),
            mixAlbums = listOf(shared, album("mix")),
            recentAlbums = listOf(album("recent")),
            frequentAlbums = listOf(album("frequent"), album("random")),
            recentlyAddedAlbums = listOf(album("newest")),
            genreSpotlightAlbums = listOf(album("genre")),
            decadeAlbums = listOf(album("decade")),
        )

        assertEquals(
            listOf("recent-album", "random", "shared", "mix", "recent", "frequent"),
            home.mixBuilderAlbumCandidates().map { it.id.value },
        )
    }

    @Test
    fun mixBuilderArtistCandidatesUseHomeArtistsWithStableDeduping() {
        val home = HomeContent(
            recentlyPlayedTracks = listOf(recentTrack("played", "recent-album", "recent-artist")),
            artists = listOf(
                Artist(ArtistId("artist-1"), "New Order"),
                Artist(ArtistId("artist-1"), "New Order duplicate"),
                Artist(ArtistId("artist-2"), "Slowdive"),
            ),
        )

        assertEquals(
            listOf("recent-artist", "artist-1", "artist-2"),
            home.mixBuilderArtistCandidates().map { it.id.value },
        )
    }

    private class FakeHomeProvider(
        private val playlists: List<Playlist> = listOf(Playlist("playlist", "Playlist", trackCount = 2)),
        supportsArtistFavorites: Boolean = false,
        private val favoriteArtists: List<Artist> = emptyList(),
        supportsTrackFavorites: Boolean = false,
        supportsAlbumFavorites: Boolean = false,
        private val favoriteAlbumPage: suspend (MediaPageRequest) -> MediaPage<Album>? = { request ->
            MediaPage(emptyList(), request.offset, request.limit, hasMore = false)
        },
    ) : MediaProvider {
        override val id: ProviderId = ProviderId("fake-home")
        override val displayName: String = "Fake Home"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            supportsStreamingTranscode = false,
            supportsDownloadTranscode = false,
            supportsArtistRadio = false,
            supportsAlbumRadio = false,
            supportsTrackRadio = false,
            supportsArtistFavorites = supportsArtistFavorites,
            supportsTrackFavorites = supportsTrackFavorites,
            supportsAlbumFavorites = supportsAlbumFavorites,
        )

        var artistLimit: Int? = null
        var favoriteFailure: Boolean = false
        val favoriteAlbumRequests = mutableListOf<MediaPageRequest>()

        override suspend fun validateConnection(): ConnectionValidation =
            ConnectionValidation(serverVersion = null, apiVersion = null)

        override suspend fun recentlyAddedAlbums(limit: Int): List<Album> =
            listOf(album("recently-added-$limit"))

        override suspend fun albumList(type: AlbumListType, limit: Int): List<Album> =
            when (type) {
                AlbumListType.Newest -> listOf(album("newest"))
                AlbumListType.Recent -> listOf(album("recent"))
                AlbumListType.Frequent -> listOf(album("frequent"))
                AlbumListType.Random -> listOf(album("random-$limit"))
                AlbumListType.Starred -> emptyList()
            }

        override suspend fun artists(limit: Int): List<Artist> {
            artistLimit = limit
            return listOf(Artist(ArtistId("artist-$limit"), "Artist $limit"))
        }

        override suspend fun favoriteArtists(limit: Int): List<Artist> {
            if (favoriteFailure) error("favorite lookup failed")
            return favoriteArtists.take(limit)
        }

        override suspend fun favoriteAlbumsPage(request: MediaPageRequest): MediaPage<Album>? {
            favoriteAlbumRequests += request
            return favoriteAlbumPage(request)
        }

        override suspend fun playlists(limit: Int): List<Playlist> =
            playlists

        override suspend fun internetRadioStations(): List<InternetRadioStation> =
            listOf(radioStation("station"))

        override suspend fun genres(limit: Int): List<Genre> =
            emptyList()

        override suspend fun album(albumId: AlbumId) =
            error("unused")

        override suspend fun artist(artistId: ArtistId) =
            error("unused")

        override suspend fun tracks(limit: Int): List<Track> =
            emptyList()

        override suspend fun search(query: String, limit: Int): MediaSearchResults =
            MediaSearchResults()

        override suspend fun streamUrl(request: StreamRequest): String =
            error("unused")

        override fun coverArtUrl(coverArtId: String): String =
            "https://example.test/cover/$coverArtId"
    }
}

private fun navibeatPlaylist(slot: String): Playlist = Playlist(
    id = "mix-$slot",
    name = slot,
    trackCount = 30,
    comment = "Description\nMade by NaviBeat  ·  navibeat.app\nnb1:timeofday:$slot:2026-08-08:fallback:30",
)

private fun album(id: String): Album =
    Album(
        id = AlbumId(id),
        title = "Album $id",
        artistName = "Artist",
        coverArtId = null,
        recentlyAddedAtIso8601 = null,
    )

private fun recentTrack(id: String, albumId: String, artistId: String): Track =
    Track(
        id = TrackId(id),
        title = "Track $id",
        artistId = ArtistId(artistId),
        artistName = "Artist $artistId",
        albumId = AlbumId(albumId),
        albumTitle = "Album $albumId",
        durationSeconds = 180,
        coverArtId = null,
        audioInfo = null,
        replayGain = null,
    )

private fun radioStation(id: String): InternetRadioStation =
    InternetRadioStation(
        id = id,
        name = "Station $id",
        streamUrl = "https://example.test/$id",
    )
