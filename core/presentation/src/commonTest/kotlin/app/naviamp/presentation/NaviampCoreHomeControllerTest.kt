package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationController
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.domain.settings.HomeSectionIds
import app.naviamp.domain.home.HomeSection
import app.naviamp.domain.Album
import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistDetails
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Genre
import app.naviamp.domain.InternetRadioStation
import app.naviamp.domain.Playlist
import app.naviamp.domain.ProviderId
import app.naviamp.domain.StreamRequest
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.cache.CachedProviderResponse
import app.naviamp.domain.cache.ProviderResponseCacheService
import app.naviamp.domain.cache.ProviderResponseService
import app.naviamp.domain.cache.ProviderResponseStore
import app.naviamp.domain.provider.*
import app.naviamp.domain.home.HomeDate
import app.naviamp.domain.provider.AlbumListType
import app.naviamp.domain.provider.ConnectionValidation
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.ProviderCapabilities
import app.naviamp.domain.sonichome.SonicHomeDiscoveryRow
import app.naviamp.domain.sonichome.SonicHomeDiscoveryRowId
import app.naviamp.domain.sonichome.SonicHomeDiscoveryRows
import app.naviamp.ui.SharedMixBuilderUi
import app.naviamp.domain.settings.HomeSectionLayout
import app.naviamp.domain.settings.HomeSectionPageLayout
import app.naviamp.domain.settings.HomeSectionPresentationSettings
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.ui.SharedHomeCollectionSectionIds
import app.naviamp.ui.SharedRoute
import app.naviamp.ui.NaviampPlaylistMediaCommand
import app.naviamp.ui.playlistActionRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancelChildren
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NaviampCoreHomeControllerTest {
    @Test fun homeConnectionLoadsKeepPersistedMixesAndExplicitRefreshRotatesThemLazily() = runTest {
        var calls = 0
        val provider = object : MediaProvider by HomeTestProvider(supportsTrackRadio = true) {
            override val capabilities = HomeTestProvider(supportsTrackRadio = true).capabilities.copy(supportsAlbumFavorites = false)
            override suspend fun albumList(type: AlbumListType, limit: Int): List<Album> {
                calls++
                return (1..30).map { Album(AlbumId("${type.name}-$it"), "Album $it", "Artist $it", null, null) }.take(limit)
            }
            override suspend fun trackRadio(trackId: TrackId, count: Int): List<Track> = error("Home generated a batch eagerly")
            override suspend fun album(albumId: AlbumId): AlbumDetails = error("Home resolved a seed eagerly")
        }
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val repository = app.naviamp.domain.radio.MemoryGeneratedRadioRepository()
        val home = NaviampCoreHomeController(store, { provider }, NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, mediaRegistry = registry, generatedRadio = repository,
            supplementSource = NaviampCoreHomeSupplementSource { NaviampCoreHomeSupplement(sourceId = "source") })
        home.refreshAfterConnection()
        val first = registry.home.generatedMixes
        assertEquals(8, first.size)
        home.refreshAfterConnection()
        assertEquals(first, registry.home.generatedMixes)
        home.execute(NaviampCoreCommand.Home.Refresh)
        assertFalse(first.map { it.id } == registry.home.generatedMixes.map { it.id })
        val savedStation = registry.home.generatedMixes.first().copy(saved = true, name = "My station")
        repository.save(repository.load().copy(stations = repository.load().stations.map { if (it.id == savedStation.id) savedStation else it }))
        val previousCalls = calls
        home.generatedStationsChanged()
        assertEquals(previousCalls, calls)
        assertFalse(registry.home.generatedMixes.any { it.id == savedStation.id })
        val savedSection = store.state.value.shell.home.content.collectionSections.single {
            it.id == SharedHomeCollectionSectionIds.SavedGeneratedStations
        }
        assertEquals(savedStation.id, savedSection.items.single().mediaItem.id)
        assertEquals("My station", savedSection.items.single().mediaItem.title)
        repository.save(repository.load().copy(stations = repository.load().stations.filterNot { it.id == savedStation.id }))
        home.generatedStationsChanged()
        assertTrue(store.state.value.shell.home.content.collectionSections
            .firstOrNull { it.id == SharedHomeCollectionSectionIds.SavedGeneratedStations }?.items.isNullOrEmpty())
    }

    @Test fun folderChangeRejectsInflightHomeRotationBeforePersistence() = runTest {
        var namespace = "folder-one"
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val provider = object : MediaProvider by HomeTestProvider(supportsTrackRadio = true) {
            override val capabilities = HomeTestProvider(supportsTrackRadio = true).capabilities.copy(supportsAlbumFavorites = false)
            override val cacheNamespace get() = namespace
            override suspend fun albumList(type: AlbumListType, limit: Int): List<Album> {
                if (type == AlbumListType.Newest) gate?.await()
                return listOf(Album(AlbumId(namespace), namespace, "Artist", null, null))
            }
        }
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val repository = app.naviamp.domain.radio.MemoryGeneratedRadioRepository()
        val home = NaviampCoreHomeController(store, { provider }, NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, mediaRegistry = registry, generatedRadio = repository,
            supplementSource = NaviampCoreHomeSupplementSource { NaviampCoreHomeSupplement(sourceId = "source") })
        home.refreshAfterConnection()
        val original = repository.load()
        gate = kotlinx.coroutines.CompletableDeferred()
        val job = launch { home.execute(NaviampCoreCommand.Home.Refresh) }
        runCurrent()
        namespace = "folder-two"
        gate!!.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(original, repository.load())
        gate = null
        home.refreshAfterConnection()
        assertTrue(registry.home.generatedMixes.all { it.libraryScope == "folder-two" })
        assertTrue(registry.home.generatedMixes.none { station -> original.stations.any { it.id == station.id } })
    }

    @Test fun refreshAndConnectionLoadReplaceCachedPlaylistIdsBeforeOpeningHomeCards() = runTest {
        for (afterConnection in listOf(false, true)) {
            val legacy = Playlist("f47ac10b-58cc-4372-a567-0e02b2c3d479", "Soundtrack", 34, coverArtId = "old-cover")
            val canonical = legacy.copy(id = "7rke2SAWaicSeSYzkhww6R", coverArtId = "new-cover")
            var playlists = listOf(legacy)
            var playlistFailure = false
            var fetches = 0
            val openedIds = mutableListOf<String>()
            val provider = object : MediaProvider by HomeTestProvider() {
                override val cacheNamespace = "navidrome:https://example.test:user:folders=1"
                override suspend fun playlists(limit: Int): List<Playlist> {
                    fetches++
                    if (playlistFailure) error("offline")
                    return playlists
                }
                override suspend fun playlistTracks(playlistId: String): List<Track> {
                    openedIds += playlistId
                    check(playlistId == canonical.id) { "playlist not found" }
                    return listOf(track("current-track"))
                }
            }
            val otherProvider = object : MediaProvider by provider {
                override val cacheNamespace = "navidrome:https://example.test:other-user:folders=1"
            }
            val responses = ProviderResponseService(ProviderResponseCacheService(HomeResponseStore()) { 1L })
            assertEquals(listOf(legacy), responses.playlists(provider, 50))
            assertEquals(listOf(legacy), responses.playlists(otherProvider, 50))
            val initialFetches = fetches
            playlists = listOf(canonical)

            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store, { error("unused") })
            val home = NaviampCoreHomeController(store, { provider }, navigation,
                NaviampCoreHomeDateSource { HomeDate(2026, 100) },
                providerResponseService = responses, mediaRegistry = registry)
            if (afterConnection) home.refreshAfterConnection() else home.execute(NaviampCoreCommand.Home.Refresh)

            assertEquals(initialFetches + 1, fetches)
            assertEquals(listOf(canonical), registry.home.playlists)
            assertEquals(listOf(canonical), responses.playlists(provider, 50))
            assertEquals(listOf(legacy), responses.playlists(otherProvider, 50))
            assertEquals(initialFetches + 1, fetches)
            val card = store.state.value.shell.home.content.collectionSections
                .flatMap { it.items }.single { it.mediaItem.id == canonical.id }.mediaItem
            assertEquals(canonical.id, store.state.value.shell.home.content.playlists.single().id)
            val browse = NaviampCorePlaylistBrowseController(store, { provider }, navigation, mediaRegistry = registry)
            browse.execute(NaviampCoreCommand.Media.ItemAction(card.playlistActionRequest(NaviampPlaylistMediaCommand.Select)))
            assertEquals(listOf(canonical.id), openedIds)
            assertEquals("current-track", store.state.value.shell.playlistDetail.detail?.tracks?.single()?.id)

            playlistFailure = true
            home.execute(NaviampCoreCommand.Home.Refresh)
            assertEquals(listOf(canonical), registry.home.playlists)
            assertTrue(HomeSection.Playlists in registry.home.failedSections)
            assertEquals(canonical.id, store.state.value.shell.home.content.playlists.single().id)

            playlistFailure = false
            playlists = emptyList()
            home.execute(NaviampCoreCommand.Home.Refresh)
            assertTrue(registry.home.playlists.isEmpty())
            assertTrue(store.state.value.shell.home.content.playlists.isEmpty())
            assertFalse(HomeSection.Playlists in registry.home.failedSections)
            assertEquals(emptyList(), responses.playlists(provider, 50))
        }
    }

    @Test fun rootHomeRecoversFailedEmptyDiscoveryButNeverReadyEmptyOrCachedRails() = runTest {
        var reads = 0
        var response = ProviderDiscoveryFeed(ProviderDiscoveryStatus.Unavailable)
        val provider = object : MediaProvider by RecoveryHomeProvider({ emptyList() }) {
            override val discovery = ProviderDiscovery { reads++; response }
        }
        val store = NaviampCoreStateStore()
        lateinit var router: NaviampCoreCommandRouter
        val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store, { router.dispatch(it) }, onPageEntered = { router.onPageEntered(it) })
        val home = NaviampCoreHomeController(store, { provider }, navigation, NaviampCoreHomeDateSource { HomeDate(2026, 100) })
        router = NaviampCoreCommandRouter(this, listOf(navigation, home))
        router.execute(NaviampCoreCommand.Home.Refresh)
        response = ProviderDiscoveryFeed(trending = listOf(ProviderDiscoveryArtist("loaded", "Loaded")))
        router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Search))
        router.dispatch(NaviampCoreCommand.Navigation.Back)
        runCurrent()
        assertEquals(2, reads)
        response = ProviderDiscoveryFeed(ProviderDiscoveryStatus.Unavailable)
        router.execute(NaviampCoreCommand.Home.Refresh)
        repeat(3) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home)) }
        runCurrent()
        assertEquals(3, reads)
        assertEquals("loaded", store.state.value.shell.home.providerDiscovery!!.feed.trending.single().id)
        response = ProviderDiscoveryFeed()
        router.execute(NaviampCoreCommand.Home.Refresh)
        repeat(3) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home)) }
        runCurrent()
        assertEquals(4, reads)
        assertTrue(store.state.value.shell.home.providerDiscovery!!.feed.trending.isEmpty())
    }

    @Test fun sonicPrerequisiteFailuresRemainTypedAndCancellationPropagates() = runTest {
        var failure: Exception? = null
        val index = SonicPrerequisiteIndex { failure?.let { throw it }; app.naviamp.domain.cache.LibrarySnapshot() }
        var favoritesFailure: Exception? = IllegalStateException("favorites unavailable")
        val provider = object : MediaProvider by HomeTestProvider(supportsSonicSimilarity = true) {
            override suspend fun favoriteTracks(limit: Int): List<Track> {
                favoritesFailure?.let { throw it }
                return emptyList()
            }
        }
        val source = naviampCoreSonicHomeDiscoverySource(index)
        assertEquals(setOf(SonicHomeDiscoveryRowId.SimilarToStarredTracks), source.load(provider, "source").failedRowIds)
        favoritesFailure = null
        assertTrue(source.load(provider, "source").failedRowIds.isEmpty())
        failure = IllegalStateException("index unavailable")
        assertEquals(SonicHomeDiscoveryRowId.entries.toSet(), source.load(provider, "source").failedRowIds)
        failure = kotlinx.coroutines.CancellationException("cancel index")
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { source.load(provider, "source") }
        failure = null
        favoritesFailure = kotlinx.coroutines.CancellationException("cancel favorites")
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { source.load(provider, "source") }
    }

    @Test fun radioReplayDuringHomeRefreshKeepsTheCurrentRegistryHistory() = runTest {
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val provider = RecoveryHomeProvider { gate?.await(); emptyList() }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val registry = NaviampCoreMediaRegistry()
        val album = Album(AlbumId("album"), "Album", "Artist", null, null)
        val old = app.naviamp.domain.radio.albumRecentRadioStream(album).copy(sourceId = "source", startedAtEpochMillis = 1)
        val replayed = old.copy(startedAtEpochMillis = 2)
        val home = NaviampCoreHomeController(store, { provider }, NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, mediaRegistry = registry,
            supplementSource = NaviampCoreHomeSupplementSource { NaviampCoreHomeSupplement(sourceId = "source", recentRadioStreams = listOf(old)) })
        home.execute(NaviampCoreCommand.Home.Refresh)
        gate = kotlinx.coroutines.CompletableDeferred()
        val refresh = launch { home.execute(NaviampCoreCommand.Home.Refresh) }
        runCurrent()
        home.restoreRecentRadioStreams(listOf(replayed))
        gate!!.complete(Unit)
        refresh.join()
        assertEquals(listOf(replayed), registry.home.recentRadioStreams)
    }

    @Test fun emptyHomeReentryRetriesOnceAndCancellationKeepsFailureEligible() = runTest {
        var reads = 0
        var fail = true
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val provider = RecoveryHomeProvider {
            reads++
            gate?.await()
            if (fail) error("home unavailable")
            emptyList()
        }
        val store = NaviampCoreStateStore()
        val history = NaviampNavigationController()
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + job)
        lateinit var router: NaviampCoreCommandRouter
        val navigation = NaviampCoreNavigationController(history, store, { router.dispatch(it) }, onPageEntered = { router.onPageEntered(it) })
        val home = NaviampCoreHomeController(store, { provider }, navigation, NaviampCoreHomeDateSource { HomeDate(2026, 100) })
        router = NaviampCoreCommandRouter(scope, listOf(navigation, home))
        try {
            router.execute(NaviampCoreCommand.Home.Refresh)
            assertEquals(1, reads)
            gate = kotlinx.coroutines.CompletableDeferred()
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Search))
            router.dispatch(NaviampCoreCommand.Navigation.Back)
            repeat(4) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home)) }
            runCurrent()
            assertEquals(2, reads)
            repeat(4) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home)) }
            runCurrent()
            assertEquals(2, reads)
            job.cancelChildren()
            runCurrent()
            assertFalse(store.state.value.shell.home.refreshing)
            gate = null
            fail = false
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Search))
            router.dispatch(NaviampCoreCommand.Navigation.Back)
            runCurrent()
            assertEquals(3, reads)
            repeat(4) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home)) }
            runCurrent()
            assertEquals(3, reads)
            fail = true
            router.execute(NaviampCoreCommand.Home.Refresh)
            router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home))
            home.resetForSourceChange()
            navigation.resetForSourceChange()
            runCurrent()
            assertEquals(4, reads)
        } finally { job.cancel() }
    }

    @Test fun sourceAccountChangesAndDisabledRowsNeverKeepAnotherSourcesCachedHome() = runTest {
        val album = Album(AlbumId("cached"), "Cached", "Artist", null, null)
        val artist = ProviderDiscoveryArtist("cached", "Cached")
        var source = "source-a"
        var account = "account-a"
        var fail = false
        var enabled = true
        val base = RecoveryHomeProvider { if (fail) error("offline") else listOf(album) }
        fun provider(): MediaProvider = object : MediaProvider by base {
            override val cacheNamespace = source
            override val discoveryAccountScope = account
            override val capabilities = base.capabilities.copy(supportsSonicSimilarity = true)
            override val discovery = if (!enabled) null else ProviderDiscovery {
                if (fail) ProviderDiscoveryFeed(ProviderDiscoveryStatus.Unavailable) else ProviderDiscoveryFeed(trending = listOf(artist))
            }
        }
        var active = provider()
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = source),
            playback = it.playback.copy(settings = it.playback.settings.copy(sonicSimilarityEnabled = true))) }
        val registry = NaviampCoreMediaRegistry()
        val home = NaviampCoreHomeController(store, { active }, NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, mediaRegistry = registry,
            sonicDiscoverySource = NaviampCoreSonicHomeDiscoverySource { _, _ ->
                if (fail) SonicHomeDiscoveryRows(failedRowIds = SonicHomeDiscoveryRowId.entries.toSet())
                else SonicHomeDiscoveryRows(listOf(SonicHomeDiscoveryRow(SonicHomeDiscoveryRowId.SonicDeepCuts, "Cached", listOf(track("cached")))))
            })
        home.execute(NaviampCoreCommand.Home.Refresh)
        fail = true
        account = "account-b"
        active = provider()
        home.execute(NaviampCoreCommand.Home.Refresh)
        assertEquals(listOf(album), registry.home.recentlyAddedAlbums)
        assertTrue(registry.sonicRows.rows.isNotEmpty())
        assertTrue(store.state.value.shell.home.providerDiscovery!!.feed.trending.isEmpty())
        source = "source-b"
        active = provider()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = source)) }
        home.resetForSourceChange()
        home.execute(NaviampCoreCommand.Home.Refresh)
        assertTrue(registry.home.recentlyAddedAlbums.isEmpty())
        assertTrue(registry.sonicRows.rows.isEmpty())
        assertTrue(store.state.value.shell.home.providerDiscovery!!.feed.trending.isEmpty())
        fail = false
        home.execute(NaviampCoreCommand.Home.Refresh)
        assertTrue(registry.sonicRows.rows.isNotEmpty())
        enabled = false
        active = provider()
        store.updateShell { it.copy(playback = it.playback.copy(settings = it.playback.settings.copy(sonicSimilarityEnabled = false))) }
        home.execute(NaviampCoreCommand.Home.Refresh)
        assertTrue(registry.sonicRows.rows.isEmpty())
        assertEquals(null, store.state.value.shell.home.providerDiscovery)
    }

    @Test fun failedEmptyCollectionBackRecoversAfterOwnerPublicationAndRetainsFailedSiblings() = runTest {
        var phase = 0
        var favoritesReads = 0
        val recommendation = Album(AlbumId("recovered"), "Recovered", "Artist", null, null)
        val discoveryArtist = ProviderDiscoveryArtist("discovery", "Discovery")
        val base = HomeTestProvider(supportsSonicSimilarity = true)
        val provider = object : MediaProvider by base {
            override val capabilities = base.capabilities.copy(supportsTrackFavorites = true)
            override val discovery = ProviderDiscovery {
                when (phase) {
                    0 -> ProviderDiscoveryFeed(recommendations = listOf(discoveryArtist), trending = listOf(discoveryArtist), lastUpdated = "cached")
                    1 -> throw IllegalStateException("feed unavailable")
                    else -> ProviderDiscoveryFeed()
                }
            }
            override suspend fun favoriteAlbumsPage(request: MediaPageRequest): MediaPage<Album> {
                favoritesReads++
                if (phase == 0) error("recommendations failed")
                return MediaPage(if (phase == 1) listOf(recommendation) else emptyList(), request.offset, request.limit, false)
            }
            override suspend fun albumList(type: AlbumListType, limit: Int): List<Album> = when (phase) {
                0 -> base.albumList(type, limit)
                1 -> error("albums unavailable")
                else -> emptyList()
            }
            override suspend fun genres(limit: Int): List<Genre> = when (phase) {
                0 -> base.genres(limit)
                1 -> error("genres unavailable")
                else -> emptyList()
            }
            override suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int): List<Album> = when (phase) {
                0 -> base.albumsByYear(fromYear, toYear, limit)
                1 -> error("decade unavailable")
                else -> emptyList()
            }
            override suspend fun playlists(limit: Int): List<Playlist> = when (phase) {
                0 -> base.playlists(limit)
                1 -> error("playlists unavailable")
                else -> emptyList()
            }
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source"),
            playback = it.playback.copy(settings = it.playback.settings.copy(sonicSimilarityEnabled = true))) }
        val registry = NaviampCoreMediaRegistry()
        val history = NaviampNavigationController()
        val entered = mutableListOf<NaviampNavigationPage>()
        lateinit var router: NaviampCoreCommandRouter
        val navigation = NaviampCoreNavigationController(history, store, { router.dispatch(it) }, onPageEntered = { page ->
            if (page is NaviampCoreHomeCollectionPage) assertEquals(page.sectionId, store.state.value.shell.home.collectionPage?.section?.id)
            entered += page
            router.onPageEntered(page)
        })
        val home = NaviampCoreHomeController(store, { provider }, navigation, NaviampCoreHomeDateSource { HomeDate(2026, 100) },
            mediaRegistry = registry, sonicDiscoverySource = NaviampCoreSonicHomeDiscoverySource { _, _ ->
                when (phase) {
                    0 -> SonicHomeDiscoveryRows(SonicHomeDiscoveryRowId.entries.map { SonicHomeDiscoveryRow(it, "Cached ${it.value}", listOf(track(it.value))) })
                    1 -> SonicHomeDiscoveryRows(failedRowIds = SonicHomeDiscoveryRowId.entries.toSet())
                    else -> SonicHomeDiscoveryRows()
                }
            })
        router = NaviampCoreCommandRouter(this, listOf(navigation, home))
        router.execute(NaviampCoreCommand.Home.Refresh)
        val cached = registry.home
        val cachedSonic = registry.sonicRows
        router.dispatch(NaviampCoreCommand.Home.OpenCollection(HomeSectionIds.FavoriteArtists))
        runCurrent()
        assertEquals(2, favoritesReads)
        assertEquals(1, entered.size)
        navigation.openMediaPage(NaviampRoute.AlbumDetail, app.naviamp.ui.SharedMediaItemUi("detail", "Detail", ""))
        phase = 1
        router.dispatch(NaviampCoreCommand.Navigation.Back)
        assertEquals(2, entered.size)
        repeat(3) { navigation.recoverEnteredPage() }
        runCurrent()
        assertEquals(3, favoritesReads)
        assertEquals(listOf("favorite-songs", "recovered"), store.state.value.shell.home.collectionPage?.section?.items?.map { it.mediaItem.id })
        assertEquals(cached.recentlyAddedAlbums, registry.home.recentlyAddedAlbums)
        assertEquals(cached.playlists, registry.home.playlists)
        assertEquals(cached.genreSpotlight, registry.home.genreSpotlight)
        assertEquals(cached.genreSpotlightAlbums, registry.home.genreSpotlightAlbums)
        assertEquals(cached.decadeAlbums, registry.home.decadeAlbums)
        assertEquals(cached.decadeLabel, registry.home.decadeLabel)
        assertEquals(cached.decadeFromYear, registry.home.decadeFromYear)
        assertEquals(cachedSonic.rows.toSet(), registry.sonicRows.rows.toSet())
        assertEquals(ProviderDiscoveryStatus.Unavailable, store.state.value.shell.home.providerDiscovery?.feed?.status)
        assertEquals(listOf(discoveryArtist), store.state.value.shell.home.providerDiscovery?.feed?.recommendations)
        assertEquals(listOf(discoveryArtist), store.state.value.shell.home.providerDiscovery?.feed?.trending)
        navigation.recoverEnteredPage()
        runCurrent()
        assertEquals(3, favoritesReads)
        phase = 2
        router.execute(NaviampCoreCommand.Home.Refresh)
        assertTrue(registry.home.recentlyAddedAlbums.isEmpty())
        assertTrue(registry.home.playlists.isEmpty())
        assertTrue(registry.home.genreSpotlightAlbums.isEmpty())
        assertTrue(registry.home.decadeAlbums.isEmpty())
        assertTrue(registry.sonicRows.rows.isEmpty())
        assertTrue(store.state.value.shell.home.providerDiscovery!!.feed.recommendations.isEmpty())
        repeat(3) { router.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home)) }
        runCurrent()
        assertEquals(4, favoritesReads)
    }

    @Test fun collectionMediaBackRestoresTheSectionWithoutDuplicatingHistory() = runTest {
        for (route in listOf(app.naviamp.domain.app.NaviampRoute.AlbumDetail,
            app.naviamp.domain.app.NaviampRoute.ArtistDetail, app.naviamp.domain.app.NaviampRoute.PlaylistDetail)) {
            val store = NaviampCoreStateStore()
            val history = NaviampNavigationController()
            lateinit var home: NaviampCoreHomeController
            val navigation = NaviampCoreNavigationController(history, store, { home.dispatch(it) },
                onResetDiscovery = { home.resetNavigation() })
            home = NaviampCoreHomeController(store, { HomeTestProvider() }, navigation,
                NaviampCoreHomeDateSource { HomeDate(2026, 100) })
            home.execute(NaviampCoreCommand.Home.Refresh)
            val section = store.state.value.shell.home.content.collectionSections.single {
                it.items.any { item -> item.mediaItem.id == "playlist-1" }
            }
            home.dispatch(NaviampCoreCommand.Home.OpenCollection(section.id))
            home.dispatch(NaviampCoreCommand.Home.OpenCollection(section.id))
            navigation.openMediaPage(route, section.items.first().mediaItem)
            assertEquals(null, store.state.value.shell.home.collectionPage)
            navigation.dispatch(NaviampCoreCommand.Navigation.Back)
            assertEquals(section.id, store.state.value.shell.home.collectionPage?.section?.id)
            assertEquals(NaviampCoreHomeCollectionPage(section.id), history.currentEntry.page)
            home.dispatch(NaviampCoreCommand.Home.CloseCollection)
            assertEquals(SharedRoute.Home, store.state.value.shell.shellChrome.selectedRoute)
            assertEquals(null, store.state.value.shell.home.collectionPage)
            assertFalse(history.canGoBack)
        }
    }

    @Test fun unavailableCollectionsDoNotPushAndMixBuildersReturnToTheirCollection() = runTest {
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val history = NaviampNavigationController()
        lateinit var home: NaviampCoreHomeController
        val navigation = NaviampCoreNavigationController(history, store, { home.dispatch(it) })
        home = NaviampCoreHomeController(store, { HomeTestProvider(supportsTrackRadio = true) }, navigation,
            NaviampCoreHomeDateSource { HomeDate(2026, 100) })
        home.execute(NaviampCoreCommand.Home.Refresh)
        home.dispatch(NaviampCoreCommand.Home.OpenCollection("missing"))
        assertFalse(history.canGoBack)
        home.dispatch(NaviampCoreCommand.Home.OpenCollection(SharedHomeCollectionSectionIds.MixesForYou))
        home.dispatch(NaviampCoreCommand.Home.SelectMixBuilder(SharedMixBuilderUi("artist", "Artist Mix", "")))
        assertEquals(SharedRoute.ArtistMix, store.state.value.shell.shellChrome.selectedRoute)
        navigation.back()
        assertEquals(SharedHomeCollectionSectionIds.MixesForYou, store.state.value.shell.home.collectionPage?.section?.id)
        home.dispatch(NaviampCoreCommand.Home.CloseCollection)
        assertFalse(history.canGoBack)
    }

    @Test fun discoveryBoundariesPreserveRadioAndSearchOriginsAndCurrentSourceTokens() = runTest {
        val artist = ProviderDiscoveryArtist("11111111-1111-1111-1111-111111111111", "Artist", detailsAvailable = true)
        val provider = HomeTestProvider(discovery = object : ProviderDiscovery {
            override suspend fun load() = ProviderDiscoveryFeed(trending = listOf(artist))
            override suspend fun artistDetails(artist: ProviderDiscoveryArtist) = ProviderDiscoveryArtistResult(
                ProviderDiscoveryStatus.Ready, ProviderDiscoveryArtistDetails(artist))
        })
        for (origin in listOf(SharedRoute.Radio, SharedRoute.Search)) {
            for (artistPage in listOf(false, true)) {
                val store = NaviampCoreStateStore()
                val history = NaviampNavigationController()
                lateinit var home: NaviampCoreHomeController
                val navigation = NaviampCoreNavigationController(history, store, { home.dispatch(it) },
                    onResetDiscovery = { home.resetNavigation() })
                home = NaviampCoreHomeController(store, { provider }, navigation,
                    NaviampCoreHomeDateSource { HomeDate(2026, 100) },
                    supplementSource = NaviampCoreHomeSupplementSource { NaviampCoreHomeSupplement(sourceId = "source") })
                home.execute(NaviampCoreCommand.Home.Refresh)
                navigation.dispatch(NaviampCoreCommand.Navigation.SelectRoute(origin))
                val originEntry = history.currentEntry
                repeat(2) {
                    val open = if (artistPage) NaviampCoreCommand.Home.SelectDiscoveryArtist(artist.id)
                        else NaviampCoreCommand.Home.Browse(DiscoveryBrowseAction.Open(DiscoveryBrowsePage.Hub))
                    home.dispatch(open); home.execute(open)
                    val page = store.state.value.shell.home.providerDiscovery!!
                    assertEquals("source", page.sourceId)
                    assertEquals(NaviampCoreDiscoveryPage(page.sourceId, page.sourceGeneration), history.currentEntry.page)
                }
                home.dispatch(if (artistPage) NaviampCoreCommand.Home.DismissDiscoveryArtist
                    else NaviampCoreCommand.Home.Browse(DiscoveryBrowseAction.Close))
                assertEquals(originEntry, history.currentEntry)
                assertEquals(origin, store.state.value.shell.shellChrome.selectedRoute)
                assertEquals(listOf(artist), store.state.value.shell.home.providerDiscovery?.feed?.trending)
            }
        }
    }

    @Test fun rootChangesInvalidateQueuedDiscoveryWithoutDiscardingHomeOrFeedData() = runTest {
        val artist = ProviderDiscoveryArtist("11111111-1111-1111-1111-111111111111", "Artist", detailsAvailable = true)
        var reads = 0
        val provider = HomeTestProvider(discovery = object : ProviderDiscovery {
            override suspend fun load() = ProviderDiscoveryFeed(trending = listOf(artist))
            override suspend fun artistDetails(artist: ProviderDiscoveryArtist): ProviderDiscoveryArtistResult {
                reads++
                return ProviderDiscoveryArtistResult(ProviderDiscoveryStatus.Ready, ProviderDiscoveryArtistDetails(artist))
            }
        })
        val store = NaviampCoreStateStore()
        val history = NaviampNavigationController()
        lateinit var home: NaviampCoreHomeController
        val navigation = NaviampCoreNavigationController(history, store, { home.dispatch(it) },
            onResetDiscovery = { home.resetNavigation() })
        home = NaviampCoreHomeController(store, { provider }, navigation, NaviampCoreHomeDateSource { HomeDate(2026, 100) })
        home.execute(NaviampCoreCommand.Home.Refresh)
        val content = store.state.value.shell.home.content
        val queued = NaviampCoreCommand.Home.SelectDiscoveryArtist(artist.id)
        home.dispatch(queued)
        navigation.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Library))
        home.execute(queued)
        assertEquals(0, reads)
        assertEquals(SharedRoute.Library, store.state.value.shell.shellChrome.selectedRoute)
        assertEquals(null, store.state.value.shell.home.providerDiscovery?.artistDetail)
        assertEquals(content, store.state.value.shell.home.content)
        assertEquals(listOf(artist), store.state.value.shell.home.providerDiscovery?.feed?.trending)
        assertFalse(history.canGoBack)
    }

    @Test fun realListeningUpdatesRecentRowsRegistryAndRefreshWithoutUsingOldIndexMetadata() = runTest {
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        store.updateShell { it.copy(playback = it.playback.copy(settings = it.playback.settings.copy(sonicSimilarityEnabled = true))) }
        val provider = HomeTestProvider(supportsSonicSimilarity = true)
        var blockRefresh = false
        val refreshEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val refreshGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val registry = NaviampCoreMediaRegistry()
        val tracks = mutableListOf<Track>()
        var failHistoryWrite = false
        val repository = object : app.naviamp.domain.home.HomeLibraryRepository {
            override fun albumYears(sourceId: String) = emptyList<app.naviamp.domain.home.HomeAlbumYear>()
            override fun recentlyPlayedTracks(sourceId: String, limit: Long) = tracks.toList()
            override fun recordPlayedTrack(sourceId: String, track: Track, playedAtEpochMillis: Long) {
                check(!failHistoryWrite)
                tracks.removeAll { it.id == track.id }; tracks.add(0, track)
            }
        }
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
            NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) },
            supplementSource = NaviampCoreHomeSupplementSource { NaviampCoreHomeSupplement(sourceId = "source") },
            libraryRepository = repository, mediaRegistry = registry,
            sonicDiscoverySource = NaviampCoreSonicHomeDiscoverySource { _, _ ->
                if (blockRefresh) { refreshEntered.complete(Unit); refreshGate.await() }
                SonicHomeDiscoveryRows()
            })
        val first = Track(TrackId("first"), "First", artistName = "Artist", albumTitle = "Album", durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null)
        val second = first.copy(id = TrackId("second"), title = "Second")
        controller.recordPlayedTrack("source", first, 100L)
        store.updateShell { it.copy(home = it.home.copy(collectionPage = app.naviamp.ui.SharedHomeCollectionPageUi(
            it.home.content.collectionSections.single { section -> section.id == app.naviamp.domain.settings.HomeSectionIds.RecentlyPlayed }))) }
        controller.recordPlayedTrack("source", second, 200L)
        assertEquals(listOf("second", "first"), store.state.value.shell.home.content.recentlyPlayedTracks.map { it.id })
        assertEquals(listOf(second, first), registry.home.recentlyPlayedTracks)
        assertEquals(listOf("second", "first"), store.state.value.shell.home.content.collectionSections
            .single { it.id == app.naviamp.domain.settings.HomeSectionIds.RecentlyPlayed }.items.map { it.mediaItem.id })

        controller.execute(NaviampCoreCommand.Home.Refresh)
        assertEquals(listOf("second", "first"), store.state.value.shell.home.content.recentlyPlayedTracks.map { it.id })
        assertEquals(listOf("second", "first"), store.state.value.shell.home.collectionPage!!.section.items.map { it.mediaItem.id })
        blockRefresh = true
        val pendingRefresh = launch { controller.execute(NaviampCoreCommand.Home.Refresh) }
        refreshEntered.await()
        controller.recordPlayedTrack("source", first, 300L)
        refreshGate.complete(Unit)
        pendingRefresh.join()
        assertEquals(listOf("first", "second"), store.state.value.shell.home.content.recentlyPlayedTracks.map { it.id })
        assertEquals(listOf(first, second), registry.home.recentlyPlayedTracks)
        controller.recordPlayedTrack("inactive-source", second, 400L)
        failHistoryWrite = true
        controller.recordPlayedTrack("source", second, 500L)
        assertEquals(listOf(first, second), tracks)
        assertEquals(listOf("first", "second"), store.state.value.shell.home.content.recentlyPlayedTracks.map { it.id })
    }

    @Test fun rejectedRequestsPermitExplicitRetryButConflictsAndUncertainResultsStayBlocked() = runTest {
        for (status in listOf(ProviderDiscoveryOperationStatus.Rejected,
            ProviderDiscoveryOperationStatus.Conflict, ProviderDiscoveryOperationStatus.Uncertain)) {
            val artist = ProviderDiscoveryArtist("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Artist", detailsAvailable = true)
            var submissions = 0
            val discovery = object : ProviderDiscovery {
                override suspend fun load() = ProviderDiscoveryFeed(trending = listOf(artist))
                override suspend fun artistDetails(artist: ProviderDiscoveryArtist) = ProviderDiscoveryArtistResult(
                    ProviderDiscoveryStatus.Ready, ProviderDiscoveryArtistDetails(artist,
                        releases = listOf(ProviderDiscoveryRelease("release", "Album")), canRequestAlbums = true))
                override suspend fun requestAlbum(artist: ProviderDiscoveryArtist, release: ProviderDiscoveryRelease): ProviderDiscoveryOperation {
                    submissions++
                    return ProviderDiscoveryOperation(status)
                }
            }
            val provider = HomeTestProvider(discovery = discovery)
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
                NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
                NaviampCoreHomeDateSource { HomeDate(2026, 100) })
            controller.execute(NaviampCoreCommand.Home.Refresh)
            val select = NaviampCoreCommand.Home.SelectDiscoveryArtist(artist.id)
            controller.dispatch(select); controller.execute(select)
            fun request() = NaviampCoreCommand.Home.DiscoveryAction(artist.id, ProviderDiscoveryAction.RequestAlbum("release"))
            val first = request()
            controller.dispatch(first); controller.execute(first)
            runCurrent()
            assertEquals(1, submissions, "No automatic retry after $status")
            val detail = store.state.value.shell.home.providerDiscovery!!.artistDetail!!
            assertEquals(status, detail.operationStatus)
            assertFalse(detail.actionBusy)
            assertEquals(status != ProviderDiscoveryOperationStatus.Rejected, first.action in detail.blockedActions)
            val explicitRetry = request()
            controller.dispatch(explicitRetry); controller.execute(explicitRetry)
            assertEquals(if (status == ProviderDiscoveryOperationStatus.Rejected) 2 else 1, submissions)
        }
    }

    @Test fun feedbackPreservesSamplesThatFinishWhileFeedbackIsPending() = runTest {
        val artist = ProviderDiscoveryArtist("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Artist", detailsAvailable = true)
        val samples = kotlinx.coroutines.CompletableDeferred<ProviderDiscoverySamples>()
        val feedback = kotlinx.coroutines.CompletableDeferred<ProviderDiscoveryFeedbackResult>()
        val native = object : ProviderDiscovery {
            override suspend fun load() = ProviderDiscoveryFeed(trending = listOf(artist))
            override suspend fun artistDetails(artist: ProviderDiscoveryArtist) = ProviderDiscoveryArtistResult(
                ProviderDiscoveryStatus.Ready, ProviderDiscoveryArtistDetails(artist, feedbackAvailable = true))
            override suspend fun samples(artist: ProviderDiscoveryArtist) = samples.await()
            override suspend fun toggleFeedback(artist: ProviderDiscoveryArtist, action: ProviderDiscoveryFeedbackAction) = feedback.await()
        }
        val provider = HomeTestProvider(discovery = native)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
            NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) })
        controller.execute(NaviampCoreCommand.Home.Refresh)
        val select = NaviampCoreCommand.Home.SelectDiscoveryArtist("a74b1b7f-71a5-4011-9441-d0b5e4122711")
        controller.dispatch(select)
        val selectionJob = launch { controller.execute(select) }
        runCurrent()
        val action = NaviampCoreCommand.Home.DiscoveryFeedback("a74b1b7f-71a5-4011-9441-d0b5e4122711", ProviderDiscoveryFeedbackAction.MoreLikeThis)
        controller.dispatch(action)
        val feedbackJob = launch { controller.execute(action) }
        runCurrent()
        samples.complete(ProviderDiscoverySamples(ProviderDiscoveryStatus.Ready, listOf(ProviderDiscoverySample("42", "Sample"))))
        selectionJob.join()
        assertEquals(listOf("42"), store.state.value.shell.home.providerDiscovery!!.artistDetail!!.samples.map { it.id })
        feedback.complete(ProviderDiscoveryFeedbackResult(ProviderDiscoveryStatus.Ready, setOf(ProviderDiscoveryFeedbackAction.MoreLikeThis)))
        feedbackJob.join()
        val detail = store.state.value.shell.home.providerDiscovery!!.artistDetail!!
        assertEquals(listOf("42"), detail.samples.map { it.id }, "Feedback must not overwrite newly loaded samples")
        assertFalse(detail.samplesLoading)
    }

    @Test fun samplesLoadAutomaticallyAndLateResultsCannotReplaceAnotherArtist() = runTest {
        val first = ProviderDiscoveryArtist("11111111-1111-1111-1111-111111111111", "First", detailsAvailable = true)
        val second = ProviderDiscoveryArtist("22222222-2222-2222-2222-222222222222", "Second", detailsAvailable = true)
        val waiting = kotlinx.coroutines.CompletableDeferred<ProviderDiscoverySamples>()
        val calls = mutableListOf<String>()
        val native = object : ProviderDiscovery {
            override suspend fun load() = ProviderDiscoveryFeed(trending = listOf(first, second))
            override suspend fun artistDetails(artist: ProviderDiscoveryArtist) = ProviderDiscoveryArtistResult(
                ProviderDiscoveryStatus.Ready, ProviderDiscoveryArtistDetails(artist))
            override suspend fun samples(artist: ProviderDiscoveryArtist): ProviderDiscoverySamples {
                calls += artist.id
                return if (artist.id == "11111111-1111-1111-1111-111111111111") waiting.await() else ProviderDiscoverySamples(ProviderDiscoveryStatus.Ready,
                    listOf(ProviderDiscoverySample("2", "Second sample")))
            }
        }
        val provider = HomeTestProvider(discovery = native)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
            NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) })
        controller.execute(NaviampCoreCommand.Home.Refresh)
        val old = NaviampCoreCommand.Home.SelectDiscoveryArtist("11111111-1111-1111-1111-111111111111")
        controller.dispatch(old)
        val job = launch { controller.execute(old) }
        runCurrent()
        assertTrue(store.state.value.shell.home.providerDiscovery!!.artistDetail!!.samplesLoading)
        assertFalse(store.state.value.shell.home.providerDiscovery!!.artistDetail!!.loading)
        val current = NaviampCoreCommand.Home.SelectDiscoveryArtist("22222222-2222-2222-2222-222222222222")
        controller.dispatch(current); controller.execute(current)
        waiting.complete(ProviderDiscoverySamples(ProviderDiscoveryStatus.Ready, listOf(ProviderDiscoverySample("1", "Old sample"))))
        job.join()
        val detail = store.state.value.shell.home.providerDiscovery!!.artistDetail!!
        assertEquals("22222222-2222-2222-2222-222222222222", detail.artist.id)
        assertEquals(listOf("2"), detail.samples.map { it.id })
        assertFalse(detail.samplesLoading)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"), calls)
    }

    @Test fun nativeRequestsRejectDuplicatesAndStaleCommandsAndSamplesUseTheSharedPlaybackCallback() = runTest {
        val artist = ProviderDiscoveryArtist("a74b1b7f-71a5-4011-9441-d0b5e4122711", "Artist", detailsAvailable = true)
        var requests = 0
        val pending = kotlinx.coroutines.CompletableDeferred<ProviderDiscoveryOperation>()
        val discovery = object : ProviderDiscovery {
            override suspend fun load() = ProviderDiscoveryFeed(trending = listOf(artist))
            override suspend fun artistDetails(artist: ProviderDiscoveryArtist) = ProviderDiscoveryArtistResult(
                ProviderDiscoveryStatus.Ready, ProviderDiscoveryArtistDetails(artist,
                    releases = listOf(ProviderDiscoveryRelease("release", "Album")), canRequestAlbums = true, canRequestTracks = true))
            override suspend fun samples(artist: ProviderDiscoveryArtist) = ProviderDiscoverySamples(ProviderDiscoveryStatus.Ready,
                listOf(ProviderDiscoverySample("42", "Sample")))
            override suspend fun requestAlbum(artist: ProviderDiscoveryArtist, release: ProviderDiscoveryRelease): ProviderDiscoveryOperation {
                requests++; return pending.await()
            }
        }
        val provider = HomeTestProvider(discovery = discovery)
        val store = NaviampCoreStateStore()
        var played: Track? = null
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
            NaviampCoreNavigationController(NaviampNavigationController(), store, {}),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, playDiscoverySample = { played = it })
        controller.execute(NaviampCoreCommand.Home.Refresh)
        val select = NaviampCoreCommand.Home.SelectDiscoveryArtist("a74b1b7f-71a5-4011-9441-d0b5e4122711")
        controller.dispatch(select); controller.execute(select)
        suspend fun act(action: ProviderDiscoveryAction) {
            val command = NaviampCoreCommand.Home.DiscoveryAction("a74b1b7f-71a5-4011-9441-d0b5e4122711", action)
            controller.dispatch(command); controller.execute(command)
        }
        assertEquals(listOf("42"), store.state.value.shell.home.providerDiscovery!!.artistDetail!!.samples.map { it.id })
        act(ProviderDiscoveryAction.PlaySample("42"))
        assertEquals("discovery-preview:42", played?.id?.value)
        val request = NaviampCoreCommand.Home.DiscoveryAction("a74b1b7f-71a5-4011-9441-d0b5e4122711", ProviderDiscoveryAction.RequestAlbum("release"))
        controller.dispatch(request)
        val running = launch { controller.execute(request) }
        runCurrent()
        assertTrue(store.state.value.shell.home.providerDiscovery!!.artistDetail!!.actionBusy)
        assertTrue(controller.dispatch(request) is NaviampCoreImmediateCommandResult.Handled)
        played = null
        act(ProviderDiscoveryAction.PlaySample("42"))
        assertEquals("discovery-preview:42", played?.id?.value)
        assertTrue(store.state.value.shell.home.providerDiscovery!!.artistDetail!!.actionBusy)
        controller.dispatch(NaviampCoreCommand.Home.DismissDiscoveryArtist)
        pending.complete(ProviderDiscoveryOperation(ProviderDiscoveryOperationStatus.Queued))
        running.join()
        assertEquals(1, requests)
        assertEquals(null, store.state.value.shell.home.providerDiscovery?.artistDetail)
        controller.dispatch(select); controller.execute(select)
        controller.dispatch(request)
        controller.resetForSourceChange()
        controller.execute(NaviampCoreCommand.Home.Refresh)
        controller.dispatch(select); controller.execute(select)
        controller.execute(request)
        assertEquals(1, requests)
    }

    @Test fun discoveryDetailsAndFeedbackStayScopedToTheSelectedArtistAndAccount() = runTest {
        val artist = app.naviamp.domain.provider.ProviderDiscoveryArtist("33333333-3333-3333-3333-333333333333", "Discovery", detailsAvailable = true)
        val waiting = kotlinx.coroutines.CompletableDeferred<app.naviamp.domain.provider.ProviderDiscoveryArtistResult>()
        var blocked = false
        var waitForDetails = true
        var slowFeed: kotlinx.coroutines.CompletableDeferred<app.naviamp.domain.provider.ProviderDiscoveryFeed>? = null
        val discovery = object : app.naviamp.domain.provider.ProviderDiscovery {
            override suspend fun load(): app.naviamp.domain.provider.ProviderDiscoveryFeed {
                slowFeed?.let { pending -> slowFeed = null; return pending.await() }
                return app.naviamp.domain.provider.ProviderDiscoveryFeed(trending = if (blocked) emptyList() else listOf(artist))
            }
            override suspend fun artistDetails(artist: app.naviamp.domain.provider.ProviderDiscoveryArtist) =
                if (waitForDetails) waiting.await() else app.naviamp.domain.provider.ProviderDiscoveryArtistResult(
                    app.naviamp.domain.provider.ProviderDiscoveryStatus.Ready,
                    app.naviamp.domain.provider.ProviderDiscoveryArtistDetails(artist,
                        libraryArtist = Artist(ArtistId("subsonic-library-id"), "Owned"), feedbackAvailable = true))
            override suspend fun toggleFeedback(artist: app.naviamp.domain.provider.ProviderDiscoveryArtist,
                action: app.naviamp.domain.provider.ProviderDiscoveryFeedbackAction): app.naviamp.domain.provider.ProviderDiscoveryFeedbackResult {
                blocked = !blocked
                return app.naviamp.domain.provider.ProviderDiscoveryFeedbackResult(app.naviamp.domain.provider.ProviderDiscoveryStatus.Ready,
                    if (blocked) setOf(action) else emptySet())
            }
        }
        val provider = HomeTestProvider(discovery = discovery)
        val store = NaviampCoreStateStore()
        val opened = mutableListOf<String>()
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
            NaviampCoreNavigationController(NaviampNavigationController(), store, { error("unused") }),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, openLibraryArtist = { opened += it.id.value })
        controller.execute(NaviampCoreCommand.Home.Refresh)
        val queued = NaviampCoreCommand.Home.SelectDiscoveryArtist("33333333-3333-3333-3333-333333333333")
        controller.dispatch(queued)
        controller.dispatch(NaviampCoreCommand.Home.DismissDiscoveryArtist)
        controller.execute(queued)
        assertEquals(null, store.state.value.shell.home.providerDiscovery?.artistDetail)

        val selecting = NaviampCoreCommand.Home.SelectDiscoveryArtist("33333333-3333-3333-3333-333333333333")
        controller.dispatch(selecting)
        val loading = backgroundScope.launch { controller.execute(selecting) }
        runCurrent()
        controller.dispatch(NaviampCoreCommand.Home.DismissDiscoveryArtist)
        waiting.complete(app.naviamp.domain.provider.ProviderDiscoveryArtistResult(app.naviamp.domain.provider.ProviderDiscoveryStatus.Ready))
        loading.join()
        assertEquals(null, store.state.value.shell.home.providerDiscovery?.artistDetail)
        waitForDetails = false
        val current = NaviampCoreCommand.Home.SelectDiscoveryArtist("33333333-3333-3333-3333-333333333333")
        controller.dispatch(current); controller.execute(current)
        val libraryArtist = NaviampCoreCommand.Home.OpenDiscoveryLibraryArtist("33333333-3333-3333-3333-333333333333")
        controller.dispatch(libraryArtist); controller.execute(libraryArtist)
        assertEquals(listOf("subsonic-library-id"), opened)
        val pendingFeed = kotlinx.coroutines.CompletableDeferred<app.naviamp.domain.provider.ProviderDiscoveryFeed>()
        slowFeed = pendingFeed
        val olderRefresh = backgroundScope.launch { controller.execute(NaviampCoreCommand.Home.Refresh) }
        runCurrent()
        val action = NaviampCoreCommand.Home.DiscoveryFeedback("33333333-3333-3333-3333-333333333333", app.naviamp.domain.provider.ProviderDiscoveryFeedbackAction.BlockArtist)
        controller.dispatch(action); controller.execute(action)
        pendingFeed.complete(app.naviamp.domain.provider.ProviderDiscoveryFeed(trending = listOf(artist)))
        olderRefresh.join()
        val state = store.state.value.shell.home.providerDiscovery!!
        assertTrue(state.feed.trending.isEmpty())
        assertEquals(setOf(app.naviamp.domain.provider.ProviderDiscoveryFeedbackAction.BlockArtist), state.artistDetail?.details?.feedback)
        assertFalse(state.artistDetail!!.savingFeedback)
        // A queued old-account mutation must never run against a newly opened account/detail.
        controller.dispatch(action)
        controller.resetForSourceChange()
        blocked = false
        controller.execute(NaviampCoreCommand.Home.Refresh)
        val newSelection = NaviampCoreCommand.Home.SelectDiscoveryArtist("33333333-3333-3333-3333-333333333333")
        controller.dispatch(newSelection); controller.execute(newSelection)
        val newAction = NaviampCoreCommand.Home.DiscoveryFeedback("33333333-3333-3333-3333-333333333333", app.naviamp.domain.provider.ProviderDiscoveryFeedbackAction.BlockArtist)
        controller.dispatch(newAction)
        controller.execute(action)
        assertFalse(blocked)
        controller.execute(newAction)
        assertTrue(blocked)
    }

    @Test fun aurralDiscoveryFailureDoesNotHideHomeAndSourceChangesRejectStaleResults() = runTest {
        val waiting = kotlinx.coroutines.CompletableDeferred<app.naviamp.domain.provider.ProviderDiscoveryFeed>()
        var active: MediaProvider = HomeTestProvider(discovery = app.naviamp.domain.provider.ProviderDiscovery { waiting.await() })
        val store = NaviampCoreStateStore()
        val opened = mutableListOf<String>()
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { active },
            NaviampCoreNavigationController(NaviampNavigationController(), store, { error("unused") }),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, externalUri = NaviampCoreExternalUriPort { opened += it })
        val refresh = backgroundScope.launch { controller.execute(NaviampCoreCommand.Home.Refresh) }
        runCurrent()
        assertTrue(store.state.value.shell.home.content.collectionSections.isNotEmpty())
        assertTrue(store.state.value.shell.home.providerDiscovery!!.loading)
        active = HomeTestProvider()
        controller.resetForSourceChange()
        waiting.complete(app.naviamp.domain.provider.ProviderDiscoveryFeed(trending = listOf(
            app.naviamp.domain.provider.ProviderDiscoveryArtist("discovery-id", "External", externalUrl = "https://music.test/artist/id"))))
        refresh.join()
        assertEquals(null, store.state.value.shell.home.providerDiscovery)
        controller.dispatch(NaviampCoreCommand.Home.OpenDiscoveryArtist("discovery-id"))
        assertTrue(opened.isEmpty())

        active = HomeTestProvider(discovery = app.naviamp.domain.provider.ProviderDiscovery { error("offline") })
        controller.execute(NaviampCoreCommand.Home.Refresh)
        assertEquals(app.naviamp.domain.provider.ProviderDiscoveryStatus.Unavailable,
            store.state.value.shell.home.providerDiscovery?.feed?.status)
        assertTrue(store.state.value.shell.home.content.collectionSections.isNotEmpty())
    }

    @Test fun discoveryOpensOnlyTheCurrentFeedsExternalIdentityWithoutLibraryNavigation() = runTest {
        val feed = app.naviamp.domain.provider.ProviderDiscoveryFeed(trending = listOf(
            app.naviamp.domain.provider.ProviderDiscoveryArtist("mbid", "External", externalUrl = "https://music.test/artist/mbid")))
        val provider = HomeTestProvider(discovery = app.naviamp.domain.provider.ProviderDiscovery { feed })
        val store = NaviampCoreStateStore()
        val opened = mutableListOf<String>()
        val controller = NaviampCoreHomeController(store, NaviampCoreMediaProviderSource { provider },
            NaviampCoreNavigationController(NaviampNavigationController(), store, { error("unused") }),
            NaviampCoreHomeDateSource { HomeDate(2026, 100) }, externalUri = NaviampCoreExternalUriPort { opened += it })
        controller.execute(NaviampCoreCommand.Home.Refresh)
        controller.dispatch(NaviampCoreCommand.Home.OpenDiscoveryArtist("unknown"))
        controller.dispatch(NaviampCoreCommand.Home.OpenDiscoveryArtist("mbid"))
        assertEquals(listOf("https://music.test/artist/mbid"), opened)
        assertEquals(SharedRoute.Home, store.state.value.shell.shellChrome.selectedRoute)
    }
    @Test
    fun collectionPageNavigationAndLayoutAreOwnedByCore() = runTest {
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreHomeController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { HomeTestProvider(supportsTrackRadio = true) },
            navigationController = NaviampCoreNavigationController(
                NaviampNavigationController(),
                store,
                { error("Not expected") },
            ),
            dateSource = NaviampCoreHomeDateSource { HomeDate(2026, 100) },
        )
        controller.execute(NaviampCoreCommand.Home.Refresh)

        controller.dispatch(NaviampCoreCommand.Home.OpenCollection(SharedHomeCollectionSectionIds.MixesForYou))
        assertEquals(SharedHomeCollectionSectionIds.MixesForYou, store.state.value.shell.home.collectionPage?.section?.id)

        controller.interfaceSettingsChanged(
            InterfaceSettings(
                homeSectionPresentations = mapOf(
                    SharedHomeCollectionSectionIds.MixesForYou to HomeSectionPresentationSettings(
                        homeLayout = HomeSectionLayout.List,
                        pageLayout = HomeSectionPageLayout.List,
                    ),
                ),
            ),
        )
        assertEquals(HomeSectionLayout.List, store.state.value.shell.home.collectionPage?.section?.homeLayout)
        assertEquals(HomeSectionPageLayout.List, store.state.value.shell.home.collectionPage?.layout)

        controller.interfaceSettingsChanged(
            InterfaceSettings(
                homeSectionPresentations = mapOf(
                    app.naviamp.domain.settings.HomeSectionIds.Aurral to HomeSectionPresentationSettings(visible = false),
                    SharedHomeCollectionSectionIds.MixesForYou to HomeSectionPresentationSettings(
                        visible = false,
                    ),
                ),
            ),
        )
        assertFalse(
            store.state.value.shell.home.content.collectionSections
                .single { it.id == SharedHomeCollectionSectionIds.MixesForYou }
                .visible,
        )
        assertFalse(store.state.value.shell.home.content.aurralHomeVisible)
        assertEquals(null, store.state.value.shell.home.collectionPage)

        controller.dispatch(NaviampCoreCommand.Home.CloseCollection)
        assertEquals(null, store.state.value.shell.home.collectionPage)
    }

    @Test
    fun refreshLoadsAndMapsTheCompleteHomeSnapshotInCore() = runTest {
        val provider = HomeTestProvider()
        val store = NaviampCoreStateStore()
        val navigation = NaviampCoreNavigationController(
            navigation = NaviampNavigationController(),
            stateStore = store,
            restoreCommand = { error("Not expected") },
        )
        val controller = NaviampCoreHomeController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            navigationController = navigation,
            dateSource = NaviampCoreHomeDateSource { HomeDate(2026, 100) },
            supplementSource = NaviampCoreHomeSupplementSource {
                NaviampCoreHomeSupplement(
                    keepDownloadedPlaylistIds = setOf("playlist-1"),
                    recentInternetRadioStations = listOf(
                        InternetRadioStation("station-1", "Station", "https://radio.example"),
                    ),
                )
            },
        )

        controller.execute(NaviampCoreCommand.Home.Refresh)

        val home = store.state.value.shell.home
        assertFalse(home.refreshing)
        assertEquals("Newest", home.content.recentlyAddedAlbums.single().title)
        assertEquals("playlist-1", home.content.playlists.single().id)
        assertTrue(home.content.playlists.single().keepDownloadedActive)
        assertEquals("Station", home.content.radioStations.single().title)
        assertEquals(listOf("artist", "album", "genre"), home.content.mixBuilders.map { it.id })
        assertEquals(null, store.state.value.overlays.status)
    }

    @Test
    fun builderSelectionUsesTheSharedNavigationController() {
        val store = NaviampCoreStateStore()
        val navigation = NaviampCoreNavigationController(
            navigation = NaviampNavigationController(),
            stateStore = store,
            restoreCommand = { error("Not expected") },
        )
        val controller = NaviampCoreHomeController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { HomeTestProvider() },
            navigationController = navigation,
            dateSource = NaviampCoreHomeDateSource { HomeDate(2026, 100) },
        )

        controller.dispatch(
            NaviampCoreCommand.Home.SelectMixBuilder(
                SharedMixBuilderUi("sonic-path", "Sonic Path", ""),
            ),
        )

        assertEquals(SharedRoute.SonicPath, store.state.value.shell.shellChrome.selectedRoute)
    }

    @Test
    fun refreshBuildsDynamicSonicRowsThroughTheCommonDiscoverySource() = runTest {
        val provider = HomeTestProvider(supportsSonicSimilarity = true)
        val store = NaviampCoreStateStore()
        store.updateShell { shell ->
            shell.copy(
                playback = shell.playback.copy(
                    settings = shell.playback.settings.copy(sonicSimilarityEnabled = true),
                ),
            )
        }
        var loadedSourceId: String? = null
        val controller = NaviampCoreHomeController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            navigationController = NaviampCoreNavigationController(
                NaviampNavigationController(),
                store,
                { error("Not expected") },
            ),
            dateSource = NaviampCoreHomeDateSource { HomeDate(2026, 100) },
            supplementSource = NaviampCoreHomeSupplementSource {
                NaviampCoreHomeSupplement(sourceId = "source-1")
            },
            sonicDiscoverySource = NaviampCoreSonicHomeDiscoverySource { _, sourceId ->
                loadedSourceId = sourceId
                SonicHomeDiscoveryRows(
                    listOf(
                        SonicHomeDiscoveryRow(
                            SonicHomeDiscoveryRowId.MoreLikeRecentPlays,
                            "More Like Recent Plays",
                            listOf(track("sonic-track")),
                        ),
                    ),
                )
            },
        )

        controller.execute(NaviampCoreCommand.Home.Refresh)

        assertEquals("source-1", loadedSourceId)
        assertEquals("sonic-track", store.state.value.shell.home.content.sonicDiscoveryRows.single().tracks.single().id)
        assertEquals(
            listOf("artist", "album", "genre", "sonic-path", "sonic-mix"),
            store.state.value.shell.home.content.mixBuilders.map { it.id },
        )
    }

    @Test
    fun disconnectedHomePublishesACommonResult() = runTest {
        val store = NaviampCoreStateStore()
        val navigation = NaviampCoreNavigationController(
            NaviampNavigationController(),
            store,
            { error("Not expected") },
        )
        val controller = NaviampCoreHomeController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { null },
            navigationController = navigation,
            dateSource = NaviampCoreHomeDateSource { HomeDate(2026, 100) },
        )

        controller.execute(NaviampCoreCommand.Home.Refresh)

        assertFalse(store.state.value.shell.home.refreshing)
        assertEquals("Connect to Navidrome to load Home.", store.state.value.overlays.status)
    }

    @Test
    fun sourceSwitchImmediatelyRemovesThePreviousHomesContent() = runTest {
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val controller = NaviampCoreHomeController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { HomeTestProvider() },
            navigationController = NaviampCoreNavigationController(
                NaviampNavigationController(),
                store,
                { error("Not expected") },
            ),
            dateSource = NaviampCoreHomeDateSource { HomeDate(2026, 100) },
            mediaRegistry = registry,
        )
        controller.execute(NaviampCoreCommand.Home.Refresh)
        assertFalse(store.state.value.shell.home.content.isEmpty)

        controller.resetForSourceChange()

        assertTrue(store.state.value.shell.home.content.isEmpty)
        assertTrue(registry.home.isEmpty)
    }
}

private class HomeTestProvider(
    supportsSonicSimilarity: Boolean = false,
    supportsTrackRadio: Boolean = false,
    override val discovery: app.naviamp.domain.provider.ProviderDiscovery? = null,
) : MediaProvider {
    override val id = ProviderId("home")
    override val displayName = "Home"
    override val capabilities = ProviderCapabilities(
        supportsStreamingTranscode = false,
        supportsDownloadTranscode = false,
        supportsArtistRadio = false,
        supportsAlbumRadio = false,
        supportsTrackRadio = supportsTrackRadio,
        supportsAlbumFavorites = true,
        supportsSonicSimilarity = supportsSonicSimilarity,
    )

    override suspend fun validateConnection() = ConnectionValidation(null, null)
    override suspend fun recentlyAddedAlbums(limit: Int) = listOf(album("Newest"))
    override suspend fun album(albumId: AlbumId): AlbumDetails = error("Not used")
    override suspend fun artist(artistId: ArtistId): ArtistDetails = error("Not used")
    override suspend fun artists(limit: Int) = listOf(Artist(ArtistId("artist-1"), "Artist"))
    override suspend fun albumList(type: AlbumListType, limit: Int) = listOf(album(type.name))
    override suspend fun albumsByGenre(genre: String, limit: Int) = listOf(album("Genre"))
    override suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int) = listOf(album("Decade"))
    override suspend fun tracks(limit: Int) = emptyList<Track>()
    override suspend fun search(query: String, limit: Int) = MediaSearchResults()
    override suspend fun playlists(limit: Int) = listOf(Playlist("playlist-1", "Playlist", 10))
    override suspend fun genres(limit: Int) = listOf(Genre("Ambient", 1, 10))
    override suspend fun internetRadioStations() =
        listOf(InternetRadioStation("station-1", "Station", "https://radio.example"))
    override suspend fun streamUrl(request: StreamRequest) = "https://stream.example"
    override fun coverArtUrl(coverArtId: String) = "https://art.example/$coverArtId"

    private fun album(title: String) = Album(
        id = AlbumId(title.lowercase()),
        title = title,
        artistName = "Artist",
        coverArtId = title.lowercase(),
        recentlyAddedAtIso8601 = null,
    )
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

private class RecoveryHomeProvider(
    private val newest: suspend () -> List<Album>,
) : MediaProvider by HomeTestProvider() {
    override val capabilities = HomeTestProvider().capabilities.copy(supportsAlbumFavorites = false)
    override suspend fun albumList(type: AlbumListType, limit: Int): List<Album> =
        if (type == AlbumListType.Newest) newest() else emptyList()
    override suspend fun artists(limit: Int): List<Artist> = emptyList()
    override suspend fun playlists(limit: Int): List<Playlist> = emptyList()
    override suspend fun genres(limit: Int): List<Genre> = emptyList()
    override suspend fun albumsByYear(fromYear: Int, toYear: Int, limit: Int): List<Album> = emptyList()
    override suspend fun internetRadioStations(): List<InternetRadioStation> = emptyList()
}

private class HomeResponseStore : ProviderResponseStore {
    private data class Entry(
        val providerId: String,
        val resourceType: String,
        val resourceId: String,
        val response: CachedProviderResponse,
    )
    private val entries = mutableMapOf<String, Entry>()

    override fun cachedResponse(cacheKey: String) = entries[cacheKey]?.response
    override fun touchResponse(cacheKey: String, lastAccessedEpochMillis: Long) = Unit
    override fun upsertResponse(
        cacheKey: String,
        providerId: String,
        resourceType: String,
        resourceId: String,
        payload: String,
        createdAtEpochMillis: Long,
        lastAccessedEpochMillis: Long,
    ) {
        entries[cacheKey] = Entry(providerId, resourceType, resourceId, CachedProviderResponse(payload, createdAtEpochMillis))
    }
    override fun deleteResponsesByProviderAndType(providerId: String, resourceType: String) {
        entries.entries.removeAll { (_, entry) -> entry.providerId == providerId && entry.resourceType == resourceType }
    }
    override fun deleteResponseByProviderTypeAndId(providerId: String, resourceType: String, resourceId: String) {
        entries.entries.removeAll { (_, entry) ->
            entry.providerId == providerId && entry.resourceType == resourceType && entry.resourceId == resourceId
        }
    }
}

private class SonicPrerequisiteIndex(
    private val snapshot: () -> app.naviamp.domain.cache.LibrarySnapshot,
) : app.naviamp.domain.cache.LocalLibraryIndexRepository {
    override fun librarySnapshot(sourceId: String, limit: Long, offset: Long) = snapshot()
    override fun mediaSource(sourceId: String) = error("unused")
    override fun markLibraryScanChecked(sourceId: String, signature: String) = error("unused")
    override fun markLibrarySyncStarted(sourceId: String) = error("unused")
    override fun markLibrarySyncCompleted(sourceId: String) = error("unused")
    override fun upsertLibraryArtists(sourceId: String, artists: List<Artist>) = error("unused")
    override fun upsertLibraryAlbums(sourceId: String, albums: List<Album>) = error("unused")
    override fun upsertLibraryTracks(sourceId: String, tracks: List<Track>) = error("unused")
    override fun searchLibrary(sourceId: String, query: String, limit: Long, offset: Long) = error("unused")
    override fun randomLibraryTrackForAlbum(sourceId: String, albumId: AlbumId) = error("unused")
    override fun libraryTracksForAlbum(sourceId: String, albumId: AlbumId, limit: Long) = error("unused")
    override fun randomLibraryTrackForArtist(sourceId: String, artistId: ArtistId) = error("unused")
    override fun libraryTracksForArtist(sourceId: String, artistId: ArtistId, limit: Long) = error("unused")
    override fun libraryTracksForArtistName(sourceId: String, artistName: String, limit: Long) = error("unused")
    override fun relatedLibraryTracks(sourceId: String, track: Track, limit: Long) = error("unused")
    override fun libraryIndexStats(sourceId: String) = error("unused")
    override fun libraryAlbumYears(sourceId: String) = error("unused")
    override fun clearLibraryData(sourceId: String?) = error("unused")
    override fun artistPopularTracks(sourceId: String, artistId: ArtistId, source: String) = error("unused")
    override fun replaceArtistPopularTracks(sourceId: String, artistId: ArtistId, source: String,
        candidates: List<app.naviamp.domain.popular.ArtistPopularTrackCandidate>, matchedTracksBySourceTrackId: Map<String, Track>, fetchedAtEpochMillis: Long) = error("unused")
}
