package app.naviamp.presentation

import app.naviamp.domain.Genre
import app.naviamp.domain.cache.LibraryAlbumYear
import app.naviamp.domain.cache.LibraryIndexStats
import app.naviamp.domain.cache.LibrarySnapshot
import app.naviamp.domain.cache.LocalLibraryIndexRepository
import app.naviamp.domain.popular.ArtistPopularTrackCandidate
import app.naviamp.domain.popular.ArtistPopularTrackMatch

import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistDetails
import app.naviamp.domain.ArtistId
import app.naviamp.domain.ProviderId
import app.naviamp.domain.StreamRequest
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.provider.ConnectionValidation
import app.naviamp.domain.provider.AlphabeticalLibraryKind
import app.naviamp.domain.provider.MediaPage
import app.naviamp.domain.provider.MediaPageRequest
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.ProviderCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import app.naviamp.ui.NaviampLibraryView
import app.naviamp.domain.settings.LibraryAlbumSortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NaviampCoreCatalogControllerTest {
    @Test
    fun explicitRefreshAcceptsRemoteMembershipAfterAnEarlierLocalMutation() = runTest {
        for (filtered in listOf(true, false)) {
            val provider = FavoriteCatalogProvider()
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.execute(NaviampCoreCommand.Library.Refresh)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.artistUpdated(provider, Artist(ArtistId("a"), "Alpha"))
            assertEquals(listOf("z"), store.favoriteArtistIds())
            if (!filtered) controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))

            // A later server snapshot is authoritative over mutations completed before refresh.
            controller.execute(NaviampCoreCommand.Library.Refresh)
            if (!filtered) controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))

            assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        }
    }

    @Test
    fun flaglessArtistIndexUsesCompleteProviderFavoritesWithoutFetchingNamePages() = runTest {
        val provider = FavoriteCatalogProvider()
        val repository = CatalogIndexRepository().apply { artists += provider.browseArtists }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val registry = NaviampCoreMediaRegistry()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1,
            libraryIndex = repository, mediaRegistry = registry)
        controller.refreshAfterConnection()
        assertTrue(store.state.value.shell.library.artists.supportsFavorites)
        assertTrue(store.state.value.shell.library.artists.items.none { it.favoriteActive })

        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))

        assertEquals(listOf(0, 1), provider.favoriteOffsets)
        assertTrue(provider.browseOffsets.isEmpty())
        assertEquals(listOf("a", "m", "z"), store.state.value.shell.library.artists.items.map { it.id })
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        assertTrue(registry.libraryArtists.filter { it.id.value in listOf("a", "z") }
            .all { it.favoritedAtIso8601 != null })
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertFalse(store.state.value.shell.library.artists.favoritesFailed)
    }

    @Test
    fun filteredLoadMoreAndLaterLetterJumpNeverReplaceOrAdvanceTheOrdinaryBrowseCursor() = runTest {
        val provider = FavoriteCatalogProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))
        assertEquals(listOf(0), provider.browseOffsets)
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        assertEquals('Z', store.state.value.shell.library.jumpRequest?.letter)
        assertNull(store.state.value.shell.library.artists.pendingJump)

        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        assertEquals(listOf(0, 1), provider.browseOffsets)
        assertEquals(listOf("a", "m", "z"), store.state.value.shell.library.artists.items.map { it.id })
        assertEquals(listOf(0, 1), provider.favoriteOffsets)
    }

    @Test
    fun unfilteredLetterJumpDoesNotStopAtAnInjectedFavoriteFromALaterPage() = runTest {
        val provider = FavoriteCatalogProvider().apply {
            browseArtists = listOf(Artist(ArtistId("a"), "Alpha"), Artist(ArtistId("m"), "Middle"),
                Artist(ArtistId("y"), "Yarrow"), Artist(ArtistId("ordinary-z"), "Zebra"),
                Artist(ArtistId("z"), "Zulu"))
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))

        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))

        assertEquals(listOf(0, 0, 1, 2, 3), provider.browseOffsets)
        assertEquals(listOf("a", "m", "y", "ordinary-z"), store.state.value.shell.library.artists.items.map { it.id })
        assertEquals('Z', store.state.value.shell.library.jumpRequest?.letter)
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        assertEquals(listOf("a", "m", "y", "ordinary-z", "z"), store.state.value.shell.library.artists.items.map { it.id })
    }

    @Test
    fun membershipMarkersPreserveKnownFavoriteDatesInHomeEvenWithOpenArtistDetails() = runTest {
        for (marker in listOf(null, "favorite")) {
            val provider = FavoriteCatalogProvider().apply {
                favorites = favorites.map { it.copy(favoritedAtIso8601 = marker) }
            }
            val known = listOf(Artist(ArtistId("a"), "Alpha", "2025-01-01T00:00:00Z"),
                Artist(ArtistId("z"), "Zulu", "2026-01-01T00:00:00Z"))
            val registry = NaviampCoreMediaRegistry()
            registry.updateHome(app.naviamp.domain.home.HomeContent(favoriteArtists = known),
                app.naviamp.domain.sonichome.SonicHomeDiscoveryRows())
            registry.updateArtist(ArtistDetails(Artist(ArtistId("z"), "Zulu", marker), emptyList()))
            val controller = NaviampCoreCatalogController(NaviampCoreStateStore(), { provider }, mediaRegistry = registry)

            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))

            assertEquals(known, registry.home.favoriteArtists)
        }
    }

    @Test
    fun filteredSearchCompletesNonfavoriteFirstPageAndKeepsServerAliasMatches() = runTest {
        val provider = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        provider.beforeSearch = { _, request -> if (request.offset == 1) gate.await() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        val query = NaviampCoreCommand.Library.ChangeQuery("server alias")
        controller.dispatch(query)
        val searching = launch { controller.execute(query) }
        runCurrent()
        assertTrue(store.state.value.shell.library.artists.favoritesLoading)
        assertTrue(store.favoriteArtistIds().isEmpty())
        assertEquals("server alias", store.state.value.shell.library.artists.resultsQuery)
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        assertEquals(listOf("server alias" to 0, "server alias" to 1), provider.searchRequests)
        gate.complete(Unit)
        searching.join()

        assertEquals(listOf("m", "z"), store.state.value.shell.library.artists.items.map { it.id })
        assertEquals(listOf("z"), store.favoriteArtistIds())
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertEquals(listOf(0, 1), provider.favoriteOffsets)
        assertEquals(listOf(0), provider.browseOffsets)
    }

    @Test
    fun artistMutationsUpdateDetachedBrowseSnapshotWithoutAddingUnmatchedSearchRows() = runTest {
        val provider = FavoriteCatalogProvider()
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1,
            mediaRegistry = registry)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("server alias"))
        controller.artistUpdated(provider, Artist(ArtistId("z"), "Zulu"))
        controller.artistUpdated(provider, Artist(ArtistId("x"), "Xylophone", "2026-01-01"))
        assertTrue(store.favoriteArtistIds().isEmpty())
        assertEquals(listOf("m", "z"), store.state.value.shell.library.artists.items.map { it.id })

        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
        assertEquals(listOf("a", "x"), store.favoriteArtistIds())
        assertNull(registry.artist("z")?.favoritedAtIso8601)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
        assertTrue(store.state.value.shell.library.favoritesOnly)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Artists))
        assertEquals(listOf("a", "x"), store.favoriteArtistIds())
        assertEquals(listOf(0, 1), provider.favoriteOffsets)
    }

    @Test
    fun mutationsWinOverMembershipPagesAlreadyInFlightAndRepublishMissingBrowseArtists() = runTest {
        val provider = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        provider.beforeFavorites = { if (it.offset == 1) gate.await() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
        controller.dispatch(enable)
        val loading = launch { controller.execute(enable) }
        runCurrent()
        controller.artistUpdated(provider, Artist(ArtistId("z"), "Zulu"))
        controller.artistUpdated(provider, Artist(ArtistId("x"), "Xylophone", "2026-01-01"))
        assertEquals(listOf("x"), store.favoriteArtistIds())
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('X'))
        assertNull(store.state.value.shell.library.jumpRequest)
        assertEquals('X', store.state.value.shell.library.artists.pendingJump)
        gate.complete(Unit)
        loading.join()
        assertEquals(listOf("a", "x"), store.favoriteArtistIds())
        assertEquals('X', store.state.value.shell.library.jumpRequest?.letter)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
    }

    @Test
    fun unsupportedFavoriteEnumerationFallsBackToCompleteLiveArtistsNotTheFlaglessIndex() = runTest {
        val provider = FavoriteCatalogProvider().apply {
            favoriteEnumerationSupported = false
            browseArtists = listOf(Artist(ArtistId("a"), "Alpha"), Artist(ArtistId("z"), "Zulu", "2026-01-01"))
        }
        val repository = CatalogIndexRepository().apply {
            artists += provider.browseArtists.map { it.copy(favoritedAtIso8601 = null) }
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1, libraryIndex = repository)
        controller.refreshAfterConnection()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        assertEquals(listOf(0), provider.favoriteOffsets)
        assertEquals(listOf(0, 1), provider.browseOffsets)
        assertEquals(listOf("z"), store.favoriteArtistIds())
        assertFalse(store.state.value.shell.library.artists.favoritesFailed)
    }

    @Test
    fun favoriteFailureDoesNotFallbackOrReportPartialSuccessAndExplicitRefreshRetries() = runTest {
        val provider = FavoriteCatalogProvider()
        provider.beforeFavorites = { if (it.offset == 1) error("Offline") }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        assertTrue(store.state.value.shell.library.artists.favoritesFailed)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertTrue(store.favoriteArtistIds().isEmpty())
        assertEquals(listOf(0), provider.browseOffsets)
        assertEquals(listOf(0, 1), provider.favoriteOffsets)

        provider.beforeFavorites = {}
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        assertFalse(store.state.value.shell.library.artists.favoritesFailed)
        assertEquals(listOf(0, 1, 0, 1), provider.favoriteOffsets)
        provider.favorites = listOf(Artist(ArtistId("z"), "Zulu"))
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(listOf("z"), store.favoriteArtistIds())
        assertEquals(listOf(0), provider.browseOffsets)
    }

    @Test
    fun failedMembershipRefreshHidesStaleFavoritesUntilASuccessfulRetry() = runTest {
        val provider = FavoriteCatalogProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias"))
        provider.beforeFavorites = { error("Offline") }

        controller.execute(NaviampCoreCommand.Library.Refresh)

        assertTrue(store.state.value.shell.library.artists.favoritesFailed)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertTrue(store.favoriteArtistIds().isEmpty())
        controller.artistUpdated(provider, Artist(ArtistId("a"), "Alpha"))
        assertTrue(store.favoriteArtistIds().isEmpty())
        val clearQuery = NaviampCoreCommand.Library.ChangeQuery("")
        controller.dispatch(clearQuery)
        assertTrue(store.favoriteArtistIds().isEmpty())
        controller.execute(clearQuery)
        assertTrue(store.state.value.shell.library.artists.favoritesFailed)
        assertTrue(store.favoriteArtistIds().isEmpty())
        provider.beforeFavorites = {}
        provider.favorites = listOf(Artist(ArtistId("z"), "Zulu"))
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(listOf("z"), store.favoriteArtistIds())
        assertFalse(store.state.value.shell.library.artists.favoritesFailed)
    }

    @Test
    fun nonadvancingFavoriteAndSearchPagesFailWithoutAnAutomaticRetryLoop() = runTest {
        for (searchFailure in listOf(false, true)) {
            val base = FavoriteCatalogProvider()
            var requests = 0
            val provider = object : MediaProvider by base {
                override suspend fun favoriteArtistsPage(request: MediaPageRequest): MediaPage<Artist>? {
                    if (searchFailure) return base.favoriteArtistsPage(request)
                    requests++
                    return MediaPage(emptyList(), request.offset, request.limit, true)
                }
                override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> {
                    requests++
                    return MediaPage(emptyList(), request.offset, request.limit, true)
                }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            if (searchFailure) controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias"))
            assertEquals(1, requests)
            assertTrue(store.state.value.shell.library.artists.favoritesFailed)
            assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        }
    }

    @Test
    fun failedSearchCompletionKeepsLoadingUntilFailureAndCanRetryWithoutNamePages() = runTest {
        val provider = FavoriteCatalogProvider()
        provider.beforeSearch = { _, request -> if (request.offset == 1) error("Offline") }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias"))
        assertTrue(store.state.value.shell.library.artists.favoritesFailed)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertTrue(store.favoriteArtistIds().isEmpty())
        provider.beforeSearch = { _, _ -> }
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertFalse(store.state.value.shell.library.artists.favoritesFailed)
        assertEquals(listOf("z"), store.favoriteArtistIds())
        assertTrue(provider.browseOffsets.isEmpty())
    }

    @Test
    fun canceledMembershipStopsLoadingAndCanBeRetried() = runTest {
        val provider = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        provider.beforeFavorites = { gate.await() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
        controller.dispatch(enable)
        val loading = launch { controller.execute(enable) }
        runCurrent()
        assertTrue(store.state.value.shell.library.artists.favoritesLoading)
        loading.cancel()
        loading.join()
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertFalse(store.state.value.shell.library.artists.favoritesFailed)
        provider.beforeFavorites = {}
        controller.execute(enable)
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
    }

    @Test
    fun rapidFilterToggleRejectsOldMembershipAndDoesNotClearTheNewLoadingOwner() = runTest {
        val provider = FavoriteCatalogProvider()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var calls = 0
        provider.beforeFavorites = {
            when (++calls) { 1 -> first.await(); 2 -> second.await() }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
        controller.dispatch(enable)
        val old = launch { controller.execute(enable) }
        runCurrent()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        provider.favorites = listOf(Artist(ArtistId("x"), "Xylophone"))
        controller.dispatch(enable)
        val current = launch { controller.execute(enable) }
        runCurrent()
        first.complete(Unit)
        old.join()
        assertTrue(store.state.value.shell.library.artists.favoritesLoading)
        assertTrue(store.favoriteArtistIds().isEmpty())
        second.complete(Unit)
        current.join()
        assertEquals(listOf("x"), store.favoriteArtistIds())
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
    }

    @Test
    fun queryAndViewChangesRejectLateFilteredPagesAndClearingKeepsTheBrowseFavorites() = runTest {
        val provider = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        provider.beforeSearch = { query, _ -> if (query == "old") gate.await() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        val oldQuery = NaviampCoreCommand.Library.ChangeQuery("old")
        controller.dispatch(oldQuery)
        val old = launch { controller.execute(oldQuery) }
        runCurrent()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("new"))
        assertEquals("new", store.state.value.shell.library.artists.resultsQuery)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
        gate.complete(Unit)
        old.join()
        assertEquals("new", store.state.value.shell.library.artists.resultsQuery)
        assertTrue(store.state.value.shell.library.favoritesOnly)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Artists))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        assertNull(store.state.value.shell.library.artists.resultsQuery)
    }

    @Test
    fun returningToArtistsKeepsPendingMembershipLoadingBeforeDeferredExecution() = runTest {
        val provider = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        provider.beforeFavorites = { gate.await() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
        controller.dispatch(enable)
        val oldLoad = launch { controller.execute(enable) }
        runCurrent()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
        val returnToArtists = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Artists)

        controller.dispatch(returnToArtists)

        assertTrue(store.state.value.shell.library.artists.favoritesLoading)
        gate.complete(Unit)
        oldLoad.join()
        assertTrue(store.state.value.shell.library.artists.favoritesLoading)
        controller.execute(returnToArtists)
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
    }

    @Test
    fun sourceResetRejectsOldMembershipAndMutationAndUpdatesTheCapability() = runTest {
        val oldProvider = FavoriteCatalogProvider(namespace = "old")
        val gate = CompletableDeferred<Unit>()
        oldProvider.beforeFavorites = { gate.await() }
        var provider: MediaProvider = oldProvider
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
        controller.dispatch(enable)
        val old = launch { controller.execute(enable) }
        runCurrent()
        provider = FavoriteCatalogProvider(namespace = "new", supportsFavorites = false)
        controller.resetForSourceChange()
        controller.artistUpdated(oldProvider, Artist(ArtistId("stale"), "Stale", "2026-01-01"))
        gate.complete(Unit)
        old.join()
        assertFalse(store.state.value.shell.library.artists.supportsFavorites)
        assertFalse(store.state.value.shell.library.favoritesOnly)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
        assertTrue(store.state.value.shell.library.artists.items.isEmpty())
        controller.applyCommand(enable)
        assertFalse(store.state.value.shell.library.favoritesOnly)
    }

    @Test
    fun enablingFavoritesRejectsAnOrdinaryBrowsePageAlreadyInFlight() = runTest {
        val base = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        val provider = object : MediaProvider by base {
            override suspend fun artistsPage(request: MediaPageRequest): MediaPage<Artist> {
                gate.await()
                return base.artistsPage(request)
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        val browsing = launch { controller.execute(NaviampCoreCommand.Library.Refresh) }
        runCurrent()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        gate.complete(Unit)
        browsing.join()
        assertEquals(listOf("a", "z"), store.favoriteArtistIds())
        assertEquals(listOf("a", "z"), store.state.value.shell.library.artists.items.map { it.id })
        assertFalse(store.state.value.shell.library.artists.syncStatus.isSyncing)
    }

    @Test
    fun clearingAnInFlightFilteredSearchDoesNotResurrectAnUnfavoriteOrItsQueryRows() = runTest {
        val provider = FavoriteCatalogProvider()
        val gate = CompletableDeferred<Unit>()
        provider.beforeSearch = { _, request -> if (request.offset == 1) gate.await() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        val query = NaviampCoreCommand.Library.ChangeQuery("alias")
        controller.dispatch(query)
        val searching = launch { controller.execute(query) }
        runCurrent()
        controller.artistUpdated(provider, Artist(ArtistId("z"), "Zulu"))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
        gate.complete(Unit)
        searching.join()
        assertEquals(listOf("a"), store.favoriteArtistIds())
        assertEquals(listOf("a", "z"), store.state.value.shell.library.artists.items.map { it.id })
        assertNull(store.state.value.shell.library.artists.resultsQuery)
        assertFalse(store.state.value.shell.library.artists.favoritesLoading)
    }

    @Test
    fun disablingAnEmptyFavoritesFilterResumesAnUnloadedOrdinaryCatalog() = runTest {
        val provider = FavoriteCatalogProvider().apply { favorites = emptyList() }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        assertTrue(store.state.value.shell.library.artists.items.isEmpty())
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        assertEquals(listOf(0), provider.browseOffsets)
        assertEquals(listOf("a"), store.state.value.shell.library.artists.items.map { it.id })
    }

    @Test
    fun explicitUnfilteredRefreshReloadsKnownMembershipWithoutCompletingLazySearchPages() = runTest {
        val provider = FavoriteCatalogProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias"))
        provider.favorites = listOf(Artist(ArtistId("z"), "Zulu"))
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(listOf("alias" to 0, "alias" to 0), provider.searchRequests)
        assertEquals(listOf(0, 1, 0), provider.favoriteOffsets)
        assertEquals(listOf("m"), store.state.value.shell.library.artists.items.map { it.id })
        controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
        assertEquals(listOf("a"), store.state.value.shell.library.artists.items.map { it.id })
        assertTrue(store.favoriteArtistIds().isEmpty())
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        assertEquals(listOf(0, 0, 1), provider.browseOffsets)
        assertEquals(listOf("z"), store.favoriteArtistIds())
    }

    @Test
    fun globalHeartCompletesEveryKindAndRestoresAllOrdinaryCursors() = runTest {
        val provider = FavoriteCatalogProvider(supportsAllKinds = true)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        for (view in NaviampLibraryView.entries) {
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            assertEquals(listOf("a"), store.catalogIds(view))
        }
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        for (view in NaviampLibraryView.entries) {
            val select = NaviampCoreCommand.Library.ChangeView(view)
            controller.dispatch(select)
            assertTrue(store.state.value.shell.library.favoritesOnly)
            assertTrue(store.state.value.shell.library.catalog(view).favoritesLoading)
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.execute(select)
            assertEquals(listOf("a", "z"), store.visibleFavoriteIds(view))
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))
            assertEquals(view, store.state.value.shell.library.jumpRequest?.view)
        }
        assertEquals(listOf(0, 1), provider.favoriteOffsets)
        assertEquals(listOf(0, 1), provider.albumFavoriteOffsets)
        assertEquals(listOf(0, 1), provider.songFavoriteOffsets)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        for (view in NaviampLibraryView.entries) assertEquals(listOf("a"), store.catalogIds(view))
        for (view in NaviampLibraryView.entries) {
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            assertEquals(listOf("a", "m", "z"), store.catalogIds(view))
        }
        assertEquals(listOf(0, 1), provider.browseOffsets)
        assertEquals(listOf(0, 1), provider.albumBrowseOffsets)
        assertEquals(listOf(0, 1), provider.songBrowseOffsets)
    }

    @Test
    fun globalHeartKeepsIndependentQueriesAndSearchCursorsWithServerAliasIntersection() = runTest {
        val provider = FavoriteCatalogProvider(supportsAllKinds = true)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        for (view in NaviampLibraryView.entries) {
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias-$view"))
            assertEquals(listOf("m"), store.catalogIds(view))
        }
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        for (view in NaviampLibraryView.entries) {
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            assertEquals("alias-$view", store.state.value.shell.library.catalog(view).resultsQuery)
            assertEquals(listOf("z"), store.visibleFavoriteIds(view))
        }
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        for (view in NaviampLibraryView.entries) {
            assertEquals("alias-$view", store.state.value.shell.library.catalog(view).query)
            assertEquals(listOf("m"), store.catalogIds(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            assertEquals(listOf("m", "z"), store.catalogIds(view))
        }
        assertEquals(listOf(0, 0, 1, 1), provider.searchRequests.map { it.second })
        assertEquals(listOf(0, 0, 1, 1), provider.albumSearchRequests.map { it.second })
        assertEquals(listOf(0, 0, 1, 1), provider.songSearchRequests.map { it.second })
    }

    @Test
    fun identicalIdsNeverShareMembershipOrMutationsAcrossKinds() = runTest {
        val provider = FavoriteCatalogProvider(supportsAllKinds = true)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        for (view in NaviampLibraryView.entries) controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
        controller.artistUpdated(provider, Artist(ArtistId("a"), "Alpha"))
        controller.albumUpdated(provider, provider.albumItem("z"))
        controller.trackUpdated(provider, provider.trackItem("a"))
        assertEquals(listOf("z"), store.visibleFavoriteIds(NaviampLibraryView.Artists))
        assertEquals(listOf("a"), store.visibleFavoriteIds(NaviampLibraryView.Albums))
        assertEquals(listOf("z"), store.visibleFavoriteIds(NaviampLibraryView.Songs))
        controller.trackUpdated(provider, provider.trackItem("x", "2026-01-01"))
        assertEquals(listOf("x", "z"), store.visibleFavoriteIds(NaviampLibraryView.Songs))
        assertEquals(listOf("z"), store.visibleFavoriteIds(NaviampLibraryView.Artists))
        assertEquals(listOf("a"), store.visibleFavoriteIds(NaviampLibraryView.Albums))
    }

    @Test
    fun everyMutationUpdatesDetachedOrdinarySnapshotsButNeverInjectsUnmatchedQueryRows() = runTest {
        for (view in NaviampLibraryView.entries) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true)
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1, mediaRegistry = registry)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("server alias"))
            controller.mutateFavorite(view, provider, "z", null)
            controller.mutateFavorite(view, provider, "a", null)
            controller.mutateFavorite(view, provider, "x", "2026-01-01")
            assertEquals(listOf("m", "z"), store.catalogIds(view))
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
            assertEquals(listOf("x"), store.visibleFavoriteIds(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
            assertEquals(listOf("a"), store.catalogIds(view))
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            assertEquals(listOf("x"), store.visibleFavoriteIds(view))
            controller.execute(NaviampCoreCommand.Library.Refresh)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            assertEquals(listOf("a", "z"), store.visibleFavoriteIds(view))
        }
    }

    @Test
    fun nullableFavoriteEndpointsEnumerateTheEntireLiveCatalogForEachKind() = runTest {
        val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
            favoriteEnumerationSupported = false
            browseArtists = listOf(Artist(ArtistId("a"), "Alpha"), Artist(ArtistId("m"), "Middle", "favorite"),
                Artist(ArtistId("z"), "Zulu", "2026-01-01"))
            browseAlbums = listOf(albumItem("a"), albumItem("m", "favorite"), albumItem("z", "2026-01-01"))
            browseTracks = listOf(trackItem("a"), trackItem("m", "favorite"), trackItem("z", "2026-01-01"))
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        for (view in NaviampLibraryView.entries) {
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            assertEquals(listOf("m", "z"), store.visibleFavoriteIds(view))
        }
        assertEquals(listOf(0, 1, 2), provider.browseOffsets)
        assertEquals(listOf(0, 1, 2), provider.albumBrowseOffsets)
        assertEquals(listOf(0, 1, 2), provider.songBrowseOffsets)
        assertEquals(listOf(0), provider.favoriteOffsets)
        assertEquals(listOf(0), provider.albumFavoriteOffsets)
        assertEquals(listOf(0), provider.songFavoriteOffsets)
    }

    @Test
    fun favoriteSongsExceedTheLegacyCappedShortcutAndKeepTheirLastPage() = runTest {
        val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
            favoriteSongs = (0..5_000).map { trackItem("song-$it") }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 200)
        controller.dispatch(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs))
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        assertEquals(5_001, store.visibleFavoriteIds(NaviampLibraryView.Songs).size)
        assertTrue("song-5000" in store.visibleFavoriteIds(NaviampLibraryView.Songs))
        assertEquals((0..5_000 step 200).toList(), provider.songFavoriteOffsets)
        assertTrue(provider.songBrowseOffsets.isEmpty())
    }

    @Test
    fun filteredAlbumsBypassTheIndexAndRespectSortChangesWithoutPublishingRawRows() = runTest {
        val snapshots = mutableMapOf<app.naviamp.domain.library.AlbumCatalogScope, app.naviamp.domain.library.AlbumCatalogSnapshot>()
        val repository = object : app.naviamp.domain.library.AlbumCatalogRepository {
            override fun readAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope) = snapshots[scope]
            override fun replaceAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope, snapshot: app.naviamp.domain.library.AlbumCatalogSnapshot) {
                snapshots[scope] = snapshot
            }
        }
        val provider = FavoriteCatalogProvider(supportsAllKinds = true)
        val store = NaviampCoreStateStore()
        val index = app.naviamp.domain.library.AlbumLibraryIndex(repository, { "source" }, { 1_000L })
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1, albumIndex = index)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
        val ordinary = store.catalogIds(NaviampLibraryView.Albums)
        val browseRequests = provider.albumBrowseOffsets.toList()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.dispatch(NaviampCoreCommand.Library.ChangeAlbumSortOrder(LibraryAlbumSortOrder.RecentlyAdded))
        assertEquals(listOf("z", "a"), store.visibleFavoriteIds(NaviampLibraryView.Albums))
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))
        assertNull(store.state.value.shell.library.jumpRequest)
        controller.dispatch(NaviampCoreCommand.Library.ChangeAlbumSortOrder(LibraryAlbumSortOrder.Title))
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))
        assertEquals(listOf("a", "z"), store.visibleFavoriteIds(NaviampLibraryView.Albums))
        assertEquals(browseRequests, provider.albumBrowseOffsets)
        assertEquals(listOf(0, 1), provider.albumFavoriteOffsets)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        assertTrue(store.catalogIds(NaviampLibraryView.Albums).containsAll(ordinary))
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('M'))
        assertEquals(browseRequests, provider.albumBrowseOffsets)
    }

    @Test
    fun unsupportedFilteredTabDoesNotFetchAnyCatalogAndTheGlobalHeartCanBeDisabled() = runTest {
        val provider = FavoriteCatalogProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        for (view in listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs)) {
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            assertTrue(store.state.value.shell.library.favoritesOnly)
            assertFalse(store.state.value.shell.library.catalog(view).supportsFavorites)
            assertFalse(store.state.value.shell.library.catalog(view).favoritesLoading)
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            controller.execute(NaviampCoreCommand.Library.Refresh)
        }
        assertTrue(provider.albumFavoriteOffsets.isEmpty())
        assertTrue(provider.songFavoriteOffsets.isEmpty())
        assertTrue(provider.albumBrowseOffsets.isEmpty())
        assertTrue(provider.songBrowseOffsets.isEmpty())
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
        assertFalse(store.state.value.shell.library.favoritesOnly)
        assertEquals(listOf("a"), store.catalogIds(NaviampLibraryView.Songs))
    }

    @Test
    fun everyKindRejectsLateMembershipOnTabDepartureWithoutClearingTheNewTabsLoadingOwner() = runTest {
        for (view in NaviampLibraryView.entries) {
            val next = NaviampLibraryView.entries[(view.ordinal + 1) % NaviampLibraryView.entries.size]
            val oldGate = CompletableDeferred<Unit>()
            val newGate = CompletableDeferred<Unit>()
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                beforeKindFavorites = { kind, _ -> if (kind == view) oldGate.await() else if (kind == next) newGate.await() }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
            controller.dispatch(enable)
            val old = launch { controller.execute(enable) }
            runCurrent()
            val change = NaviampCoreCommand.Library.ChangeView(next)
            controller.dispatch(change)
            assertTrue(store.state.value.shell.library.catalog(next).favoritesLoading)
            val current = launch { controller.execute(change) }
            runCurrent()
            oldGate.complete(Unit)
            old.join()
            assertTrue(store.state.value.shell.library.catalog(next).favoritesLoading)
            assertFalse(store.state.value.shell.library.catalog(view).favoritesLoading)
            assertTrue(store.catalogIds(view).isEmpty())
            newGate.complete(Unit)
            current.join()
            assertEquals(listOf("a", "z"), store.visibleFavoriteIds(next))
        }
    }

    @Test
    fun albumAndSongQueriesRejectOldResponsesAndKeepMutationsMadeDuringCompletion() = runTest {
        for (view in listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs)) {
            val gate = CompletableDeferred<Unit>()
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                beforeKindSearch = { kind, query, request -> if (kind == view && query == "old" && request.offset == 1) gate.await() }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            val query = NaviampCoreCommand.Library.ChangeQuery("old")
            controller.dispatch(query)
            val old = launch { controller.execute(query) }
            runCurrent()
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.mutateFavorite(view, provider, "z", null)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("new"))
            gate.complete(Unit)
            old.join()
            assertEquals("new", store.state.value.shell.library.catalog(view).resultsQuery)
            assertEquals(listOf("a"), store.visibleFavoriteIds(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
            assertEquals(listOf("a"), store.visibleFavoriteIds(view))
        }
    }

    @Test
    fun allKindsKeepInFlightLocalMutationsButClearCompletedOverridesOnExplicitRefresh() = runTest {
        for (view in NaviampLibraryView.entries) {
            val gate = CompletableDeferred<Unit>()
            val provider = FavoriteCatalogProvider(supportsAllKinds = true)
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.mutateFavorite(view, provider, "a", null)
            provider.beforeKindFavorites = { kind, request -> if (kind == view && request.offset == 1) gate.await() }
            val refreshing = launch { controller.execute(NaviampCoreCommand.Library.Refresh) }
            runCurrent()
            controller.mutateFavorite(view, provider, "z", null)
            controller.mutateFavorite(view, provider, "x", "2026-01-01")
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            gate.complete(Unit)
            refreshing.join()
            assertEquals(listOf("a", "x"), store.visibleFavoriteIds(view))
        }
    }

    @Test
    fun failedRefreshInvalidationSurvivesAllSnapshotRestoresAndLaterOrdinaryPages() = runTest {
        for (view in NaviampLibraryView.entries) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                browseArtists = browseArtists.map { it.copy(favoritedAtIso8601 = "2025-01-01") }
                browseAlbums = browseAlbums.map { it.copy(favoritedAtIso8601 = "2025-01-01") }
                browseTracks = browseTracks.map { it.copy(favoritedAtIso8601 = "2025-01-01") }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias"))
            provider.beforeKindFavorites = { kind, _ -> if (kind == view) error("Offline") }
            controller.execute(NaviampCoreCommand.Library.Refresh)
            assertTrue(store.state.value.shell.library.catalog(view).favoritesFailed)
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery(""))
            assertTrue(store.state.value.shell.library.catalog(view).favoritesFailed)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
            assertEquals(listOf("a"), store.catalogIds(view))
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            assertEquals(listOf("a", "m"), store.catalogIds(view))
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            provider.beforeKindFavorites = { _, _ -> }
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            assertEquals(listOf("a", "z"), store.visibleFavoriteIds(view))
        }
    }

    @Test
    fun namespaceFoldersAndSourceChangesInvalidateInactiveMembershipCaches() = runTest {
        for (scopeChange in listOf("namespace", "folders", "source")) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true)
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            for (view in NaviampLibraryView.entries) controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            provider.favorites = listOf(Artist(ArtistId("new"), "new"))
            provider.favoriteAlbums = listOf(provider.albumItem("new"))
            provider.favoriteSongs = listOf(provider.trackItem("new"))
            when (scopeChange) {
                "namespace" -> provider.cacheNamespace = "new"
                "folders" -> provider.selectedMusicFolderIds = listOf("new")
                else -> store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "new")) }
            }
            for (view in NaviampLibraryView.entries) {
                controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
                assertEquals(listOf("new"), store.visibleFavoriteIds(view))
            }
            assertEquals(listOf(0, 1, 0), provider.favoriteOffsets)
            assertEquals(listOf(0, 1, 0), provider.albumFavoriteOffsets)
            assertEquals(listOf(0, 1, 0), provider.songFavoriteOffsets)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
            assertTrue(store.catalogIds(NaviampLibraryView.Artists).isEmpty())
            assertTrue(store.catalogIds(NaviampLibraryView.Albums).isEmpty())
            assertEquals(listOf("a"), store.catalogIds(NaviampLibraryView.Songs))
        }
    }

    @Test
    fun sameProviderSourceSwitchRejectsAllLateFavoritePages() = runTest {
        for (view in NaviampLibraryView.entries) {
            val gate = CompletableDeferred<Unit>()
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                beforeKindFavorites = { kind, _ -> if (kind == view) gate.await() }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
            controller.dispatch(enable)
            val old = launch { controller.execute(enable) }
            runCurrent()
            store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "new")) }
            controller.resetForSourceChange()
            gate.complete(Unit)
            old.join()
            assertFalse(store.state.value.shell.library.favoritesOnly)
            assertFalse(store.state.value.shell.library.catalog(view).favoritesLoading)
            assertTrue(store.catalogIds(view).isEmpty())
        }
    }

    @Test
    fun albumAndTrackMarkersPreserveIndependentHomeDetailAndSearchTimestamps() = runTest {
        for (marker in listOf(null, "favorite", "")) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                favoriteAlbums = favoriteAlbums.map { it.copy(favoritedAtIso8601 = marker) }
                favoriteSongs = favoriteSongs.map { it.copy(favoritedAtIso8601 = marker) }
            }
            val album = provider.albumItem("z", "2026-01-01")
            val track = provider.trackItem("z", "2025-01-01")
            val registry = NaviampCoreMediaRegistry()
            registry.updateHome(app.naviamp.domain.home.HomeContent(recentlyAddedAlbums = listOf(album),
                recentlyPlayedTracks = listOf(track)), app.naviamp.domain.sonichome.SonicHomeDiscoveryRows())
            registry.updateSearch(MediaSearchResults(albums = listOf(album), tracks = listOf(track)))
            registry.updateAlbum(AlbumDetails(album.copy(favoritedAtIso8601 = marker), listOf(track.copy(favoritedAtIso8601 = marker))))
            val controller = NaviampCoreCatalogController(NaviampCoreStateStore(), { provider }, mediaRegistry = registry)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs))
            assertEquals(listOf(album), registry.home.recentlyAddedAlbums)
            assertEquals(listOf(track), registry.home.recentlyPlayedTracks)
            assertEquals(listOf(album), registry.search.albums)
            assertEquals(listOf(track), registry.search.tracks)
            controller.albumUpdated(provider, album.copy(favoritedAtIso8601 = "favorite"))
            controller.trackUpdated(provider, track.copy(favoritedAtIso8601 = "favorite"))
            assertEquals(listOf(album), registry.home.recentlyAddedAlbums)
            assertEquals(listOf(track), registry.home.recentlyPlayedTracks)
        }
    }

    @Test
    fun failedFavoriteRefreshKeepsHomeDetailAndSearchDatesUntilAnAuthoritativeUpdate() = runTest {
        for (view in NaviampLibraryView.entries) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true)
            val artist = Artist(ArtistId("z"), "Zulu", "2024-01-01T00:00:00Z")
            val album = provider.albumItem("z", "2025-01-01T00:00:00Z")
            val track = provider.trackItem("z", "2026-01-01T00:00:00Z")
            val registry = NaviampCoreMediaRegistry()
            registry.updateHome(app.naviamp.domain.home.HomeContent(favoriteArtists = listOf(artist),
                recentlyAddedAlbums = listOf(album), recentlyPlayedTracks = listOf(track)),
                app.naviamp.domain.sonichome.SonicHomeDiscoveryRows())
            registry.updateSearch(MediaSearchResults(artists = listOf(artist), albums = listOf(album), tracks = listOf(track)))
            registry.updateAlbum(AlbumDetails(album, listOf(track)))
            registry.updateArtist(ArtistDetails(artist, listOf(album)), popularTracks = listOf(track),
                appearanceAlbums = listOf(album), appearanceTracks = listOf(track))
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1, mediaRegistry = registry)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            assertTrue("z" in store.visibleFavoriteIds(view))
            provider.beforeKindFavorites = { kind, _ -> if (kind == view) error("Offline") }

            controller.execute(NaviampCoreCommand.Library.Refresh)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("alias"))
            controller.dispatch(NaviampCoreCommand.Library.ChangeQuery(""))

            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            assertEquals(listOf(artist), registry.home.favoriteArtists)
            assertEquals(listOf(album), registry.home.recentlyAddedAlbums)
            assertEquals(listOf(track), registry.home.recentlyPlayedTracks)
            assertEquals(MediaSearchResults(listOf(artist), listOf(album), listOf(track)), registry.search)
            assertEquals(AlbumDetails(album, listOf(track)), registry.albumDetails)
            assertEquals(ArtistDetails(artist, listOf(album)), registry.artistDetails)
            assertEquals(listOf(track), registry.artistPopularTracks)
            assertEquals(listOf(album), registry.artistAppearanceAlbums)
            assertEquals(listOf(track), registry.artistAppearanceTracks)

            provider.beforeKindFavorites = { _, _ -> }
            provider.favorites = emptyList()
            provider.favoriteAlbums = emptyList()
            provider.favoriteSongs = emptyList()
            controller.execute(NaviampCoreCommand.Library.Refresh)
            assertFalse(store.state.value.shell.library.catalog(view).favoritesFailed)
            val timestamp = when (view) {
                NaviampLibraryView.Artists -> registry.home.favoriteArtists.single().favoritedAtIso8601
                NaviampLibraryView.Albums -> registry.home.recentlyAddedAlbums.single().favoritedAtIso8601
                NaviampLibraryView.Songs -> registry.home.recentlyPlayedTracks.single().favoritedAtIso8601
            }
            assertNull(timestamp)
        }
    }

    @Test
    fun malformedAlbumAndSongPagesFailWithoutFallbackOrPartialSuccess() = runTest {
        for (view in listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs)) {
            for (failure in listOf("empty", "metadata", "repeat", "token", "exception")) {
                val base = FavoriteCatalogProvider(supportsAllKinds = true)
                var requests = 0
                fun <T> malformed(request: MediaPageRequest, item: T): MediaPage<T> {
                    requests++
                    if (failure == "exception") error("Offline")
                    return MediaPage(if (failure == "empty") emptyList() else listOf(item),
                        if (failure == "metadata") request.offset + 1 else request.offset, request.limit, true,
                        nextContinuationToken = if (failure == "token") "cycle" else null)
                }
                val provider = object : MediaProvider by base {
                    override suspend fun favoriteAlbumsPage(request: MediaPageRequest): MediaPage<Album> =
                        malformed(request, base.albumItem(if (failure == "token") "a-${request.offset}" else "a"))
                    override suspend fun favoriteTracksPage(request: MediaPageRequest): MediaPage<Track> =
                        malformed(request, base.trackItem(if (failure == "token") "a-${request.offset}" else "a"))
                }
                val store = NaviampCoreStateStore()
                val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
                controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
                controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
                assertTrue(store.state.value.shell.library.catalog(view).favoritesFailed)
                assertFalse(store.state.value.shell.library.catalog(view).favoritesLoading)
                assertTrue(store.visibleFavoriteIds(view).isEmpty())
                assertEquals(if (failure == "repeat" || failure == "token") 2 else 1, requests)
                assertTrue(base.albumBrowseOffsets.isEmpty())
                assertTrue(base.songBrowseOffsets.isEmpty())
            }
        }
    }

    @Test
    fun unfilteredAlbumAndSongJumpsWalkRawPagesPastAnInjectedFavorite() = runTest {
        for (view in listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs)) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                browseAlbums = listOf(albumItem("a"), albumItem("m"), albumItem("y"),
                    albumItem("ordinary-z").copy(title = "Zebra"), albumItem("z"))
                browseTracks = listOf(trackItem("a"), trackItem("m"), trackItem("y"),
                    trackItem("ordinary-z").copy(title = "Zebra"), trackItem("z"))
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.applyCommand(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(false))
            controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))
            assertEquals(listOf("a", "m", "y", "ordinary-z"), store.catalogIds(view))
            assertEquals(listOf(0, 0, 1, 2, 3), if (view == NaviampLibraryView.Albums)
                provider.albumBrowseOffsets else provider.songBrowseOffsets)
            assertEquals('Z', store.state.value.shell.library.jumpRequest?.letter)
        }
    }

    @Test
    fun albumAndSongSearchFailureNeverPublishesPartialMembershipAndRefreshRetriesTheExactQuery() = runTest {
        for (view in listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs)) {
            val provider = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                beforeKindSearch = { kind, _, request -> if (kind == view && request.offset == 1) error("Offline") }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
            controller.applyCommand(NaviampCoreCommand.Library.ChangeQuery("server alias"))
            assertTrue(store.state.value.shell.library.catalog(view).favoritesFailed)
            assertTrue(store.visibleFavoriteIds(view).isEmpty())
            provider.beforeKindSearch = { _, _, _ -> }
            controller.execute(NaviampCoreCommand.Library.Refresh)
            assertEquals("server alias", store.state.value.shell.library.catalog(view).query)
            assertEquals(listOf("z"), store.visibleFavoriteIds(view))
            assertFalse(store.state.value.shell.library.catalog(view).favoritesFailed)
            assertEquals(listOf("server alias" to 0, "server alias" to 1, "server alias" to 0, "server alias" to 1),
                if (view == NaviampLibraryView.Albums) provider.albumSearchRequests else provider.songSearchRequests)
            assertTrue(provider.albumBrowseOffsets.isEmpty())
            assertTrue(provider.songBrowseOffsets.isEmpty())
        }
    }

    @Test
    fun lateAlbumIndexCompletionAndSortChangesCannotOverwriteFilteredMembership() = runTest {
        val gate = CompletableDeferred<Unit>()
        val base = FavoriteCatalogProvider(supportsAllKinds = true)
        val provider = object : MediaProvider by base {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                gate.await()
                return base.albumsPage(request)
            }
        }
        val snapshots = mutableMapOf<app.naviamp.domain.library.AlbumCatalogScope, app.naviamp.domain.library.AlbumCatalogSnapshot>()
        val repository = object : app.naviamp.domain.library.AlbumCatalogRepository {
            override fun readAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope) = snapshots[scope]
            override fun replaceAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope, snapshot: app.naviamp.domain.library.AlbumCatalogSnapshot) {
                snapshots[scope] = snapshot
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1,
            albumIndex = app.naviamp.domain.library.AlbumLibraryIndex(repository, { "source" }, { 1_000L }))
        val select = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(select)
        val ordinary = launch { controller.execute(select) }
        runCurrent()
        controller.applyCommand(NaviampCoreCommand.Library.ChangeFavoritesOnly(true))
        controller.dispatch(NaviampCoreCommand.Library.ChangeAlbumSortOrder(LibraryAlbumSortOrder.RecentlyAdded))
        gate.complete(Unit)
        ordinary.join()
        assertEquals(listOf("z", "a"), store.visibleFavoriteIds(NaviampLibraryView.Albums))
        assertEquals(listOf("z", "a"), store.catalogIds(NaviampLibraryView.Albums))
        assertFalse(store.state.value.shell.library.albums.syncStatus.isSyncing)
        assertFalse(store.state.value.shell.library.albums.favoritesLoading)
    }

    @Test
    fun lateFolderOrProviderMembershipCannotPublishIntoTheReplacementScope() = runTest {
        for (view in listOf(NaviampLibraryView.Albums, NaviampLibraryView.Songs)) {
            for (replaceProvider in listOf(false, true)) {
                val gate = CompletableDeferred<Unit>()
                val original = FavoriteCatalogProvider(supportsAllKinds = true).apply {
                    beforeKindFavorites = { kind, _ -> if (kind == view) gate.await() }
                }
                var provider: MediaProvider = original
                val store = NaviampCoreStateStore()
                val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
                controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
                val enable = NaviampCoreCommand.Library.ChangeFavoritesOnly(true)
                controller.dispatch(enable)
                val old = launch { controller.execute(enable) }
                runCurrent()
                if (replaceProvider) {
                    provider = FavoriteCatalogProvider(namespace = "replacement", supportsAllKinds = true).apply {
                        favoriteAlbums = listOf(albumItem("new"))
                        favoriteSongs = listOf(trackItem("new"))
                    }
                } else {
                    original.selectedMusicFolderIds = listOf("new")
                    original.favoriteAlbums = listOf(original.albumItem("new"))
                    original.favoriteSongs = listOf(original.trackItem("new"))
                    original.beforeKindFavorites = { _, _ -> }
                }
                controller.execute(NaviampCoreCommand.Library.Refresh)
                gate.complete(Unit)
                old.join()
                assertEquals(listOf("new"), store.visibleFavoriteIds(view))
                assertFalse(store.state.value.shell.library.catalog(view).favoritesLoading)
            }
        }
    }

    private fun NaviampCoreStateStore.catalogIds(view: NaviampLibraryView): List<String> {
        val catalog = state.value.shell.library.catalog(view)
        return if (view == NaviampLibraryView.Songs) catalog.tracks.map { it.id } else catalog.items.map { it.id }
    }

    private fun NaviampCoreStateStore.visibleFavoriteIds(view: NaviampLibraryView): List<String> {
        val library = state.value.shell.library
        val catalog = library.catalog(view)
        if (library.favoritesOnly && (!catalog.supportsFavorites || catalog.favoritesLoading || catalog.favoritesFailed)) return emptyList()
        return if (view == NaviampLibraryView.Songs) catalog.tracks.filter { it.favoriteActive }.map { it.id }
            else catalog.items.filter { it.favoriteActive }.map { it.id }
    }

    private fun NaviampCoreCatalogController.mutateFavorite(view: NaviampLibraryView, provider: FavoriteCatalogProvider,
        id: String, timestamp: String?) {
        when (view) {
            NaviampLibraryView.Artists -> artistUpdated(provider, Artist(ArtistId(id), when (id) {
                "a" -> "Alpha"; "z" -> "Zulu"; else -> id
            }, timestamp))
            NaviampLibraryView.Albums -> albumUpdated(provider, provider.albumItem(id, timestamp))
            NaviampLibraryView.Songs -> trackUpdated(provider, provider.trackItem(id, timestamp))
        }
    }

    private suspend fun NaviampCoreCatalogController.applyCommand(command: NaviampCoreCommand) {
        dispatch(command)
        execute(command)
    }

    private fun NaviampCoreStateStore.favoriteArtistIds() =
        state.value.shell.library.artists.items.filter { it.favoriteActive }.map { it.id }

    @Test fun previewSyncRejectsSourceChangesBeforePruningAndRunsOffTheRenderDispatcher() = runTest {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val repository = CatalogIndexRepository()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun libraryTracksPage(request: MediaPageRequest): MediaPage<Track> {
                assertEquals(kotlinx.coroutines.Dispatchers.Default,
                    kotlinx.coroutines.currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor])
                started.complete(Unit)
                proceed.await()
                return MediaPage(emptyList(), request.offset, request.limit, false)
            }
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = repository)
        val job = async { controller.syncSmartPlaylistPreview {} }
        started.await()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "other")) }
        proceed.complete(Unit)
        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
        assertFalse(repository.pruned)
    }

    @Test
    fun explicitAlbumRefreshDuringQueryUpdatesTheBrowseCacheWithoutCachingSearchResults() = runTest {
        val snapshots = mutableMapOf<app.naviamp.domain.library.AlbumCatalogScope, app.naviamp.domain.library.AlbumCatalogSnapshot>()
        val repository = object : app.naviamp.domain.library.AlbumCatalogRepository {
            override fun readAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope) = snapshots[scope]
            override fun replaceAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope, snapshot: app.naviamp.domain.library.AlbumCatalogSnapshot) {
                snapshots[scope] = snapshot
            }
        }
        var version = 1
        var browseRequests = 0
        var searchRequests = 0
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                browseRequests++
                return MediaPage(listOf(Album(AlbumId("browse-$version"), "Browse $version", "Artist", null, null)),
                    0, request.limit, false)
            }
            override suspend fun searchAlbumsPage(query: String, request: MediaPageRequest): MediaPage<Album> {
                searchRequests++
                return MediaPage(listOf(Album(AlbumId("match"), "RÃ¼fÃ¼s EP", "RÃœFÃœS DU SOL", null, null)),
                    0, request.limit, false)
            }
        }
        val index = app.naviamp.domain.library.AlbumLibraryIndex(repository, { "source" }, { 1000L })
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, albumIndex = index)
        controller.dispatch(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
        controller.execute(NaviampCoreCommand.Library.Refresh)
        val query = NaviampCoreCommand.Library.ChangeQuery("Rufus")
        controller.dispatch(query)
        controller.execute(query)
        assertEquals(listOf("Browse 1"), index.snapshot(index.scope(provider))?.albums?.map { it.title })
        assertEquals("Rufus", store.state.value.shell.library.albums.resultsQuery)
        version = 2
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(2, browseRequests)
        assertEquals(2, searchRequests)
        assertEquals(listOf("Browse 2"), index.snapshot(index.scope(provider))?.albums?.map { it.title })
        assertEquals("Rufus", store.state.value.shell.library.albums.resultsQuery)
        val clear = NaviampCoreCommand.Library.ChangeQuery("")
        controller.dispatch(clear)
        controller.execute(clear)
        assertEquals(listOf("Browse 2"), store.state.value.shell.library.albums.items.map { it.title })
        assertNull(store.state.value.shell.library.albums.resultsQuery)
    }

    @Test
    fun serverMatchesAreTaggedWithTheirExactQueryAndPagingKeepsThatProvenance() = runTest {
        val requests = mutableListOf<Pair<String, Int>>()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> {
                requests += query to request.offset
                val artist = if (request.offset == 0) Artist(ArtistId("accented"), "RÃœFÃœS DU SOL")
                    else Artist(ArtistId("alias"), "An alias known only to the server")
                return MediaPage(listOf(artist), request.offset, request.limit, request.offset == 0)
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        val original = store.state.value.shell.library.artists.items
        val query = NaviampCoreCommand.Library.ChangeQuery("Rufus")
        controller.dispatch(query)
        controller.execute(query)
        assertEquals("Rufus", store.state.value.shell.library.artists.resultsQuery)
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        assertEquals(listOf("RÃœFÃœS DU SOL", "An alias known only to the server"),
            store.state.value.shell.library.artists.items.map { it.title })
        assertEquals("Rufus", store.state.value.shell.library.artists.resultsQuery)
        assertEquals(listOf("Rufus" to 0, "Rufus" to 1), requests)
        val clear = NaviampCoreCommand.Library.ChangeQuery("")
        controller.dispatch(clear)
        controller.execute(clear)
        assertEquals(original, store.state.value.shell.library.artists.items)
        assertNull(store.state.value.shell.library.artists.resultsQuery)
    }

    @Test
    fun explicitRefreshDuringSearchUpdatesTheBrowseDataRestoredOnClear() = runTest {
        var version = 1
        var browseRequests = 0
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun artistsPage(request: MediaPageRequest): MediaPage<Artist> {
                browseRequests++
                return MediaPage(listOf(Artist(ArtistId("browse-$version"), "Artist $version")), 0, request.limit, false)
            }
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = CatalogIndexRepository())
        controller.execute(NaviampCoreCommand.Library.Refresh)
        val query = NaviampCoreCommand.Library.ChangeQuery("One")
        controller.dispatch(query)
        controller.execute(query)
        assertEquals(1, browseRequests)
        version = 2
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(2, browseRequests)
        assertEquals("One", store.state.value.shell.library.artists.query)
        val clear = NaviampCoreCommand.Library.ChangeQuery("")
        controller.dispatch(clear)
        controller.execute(clear)
        assertEquals(2, browseRequests)
        assertEquals(listOf("browse-2"), store.state.value.shell.library.artists.items.map { it.id })
    }

    @Test
    fun searchingAndClearingDoNotRefreshTheArtistIndexOrLoseTheBrowseCatalog() = runTest {
        var browseRequests = 0
        var genreRefreshes = 0
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun artistsPage(request: MediaPageRequest): MediaPage<Artist> {
                browseRequests++
                return MediaPage(listOf(Artist(ArtistId("browse"), "Original artist")), 0, request.limit, false)
            }
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val registry = NaviampCoreMediaRegistry()
        val repository = CatalogIndexRepository()
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = repository,
            mediaRegistry = registry, libraryGenreRefresh = NaviampCoreLibraryGenreRefreshPort { genreRefreshes++ })
        controller.execute(NaviampCoreCommand.Library.Refresh)
        val original = store.state.value.shell.library.artists.items
        val initialBrowseRequests = browseRequests
        val initialGenreRefreshes = genreRefreshes
        for (query in listOf("O", "One", "", "Two", "")) {
            val command = NaviampCoreCommand.Library.ChangeQuery(query)
            controller.dispatch(command)
            controller.execute(command)
            assertFalse(store.state.value.shell.library.artists.syncStatus.isSyncing)
            assertEquals(initialBrowseRequests, browseRequests)
            assertEquals(initialGenreRefreshes, genreRefreshes)
            if (query.isEmpty()) {
                assertEquals(original, store.state.value.shell.library.artists.items)
                assertEquals(listOf("browse"), registry.libraryArtists.map { it.id.value })
            }
        }
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertTrue(browseRequests > initialBrowseRequests)
    }

    @Test
    fun serverSearchDoesNotPublishRefreshStatusAndClearingRejectsItsLateResponse() = runTest {
        val gate = CompletableDeferred<Unit>()
        var searches = 0
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> {
                searches++
                gate.await()
                return MediaPage(listOf(Artist(ArtistId("late"), "Late result")), 0, request.limit, false)
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider })
        controller.execute(NaviampCoreCommand.Library.Refresh)
        val original = store.state.value.shell.library.artists.items
        val command = NaviampCoreCommand.Library.ChangeQuery("Late")
        controller.dispatch(command)
        val searching = launch { controller.execute(command) }
        runCurrent()
        assertEquals(1, searches)
        assertTrue(store.state.value.shell.library.artists.searching)
        assertFalse(store.state.value.shell.library.artists.syncStatus.isSyncing)
        assertNull(store.state.value.shell.library.artists.syncStatus.message)
        val clear = NaviampCoreCommand.Library.ChangeQuery("")
        controller.dispatch(clear)
        controller.execute(clear)
        gate.complete(Unit)
        searching.join()
        assertEquals(original, store.state.value.shell.library.artists.items)
        assertEquals("", store.state.value.shell.library.artists.query)
        assertFalse(store.state.value.shell.library.artists.searching)
    }

    @Test
    fun clearingEachViewsSearchRestoresItsBrowseMediaAndPagingCursor() = runTest {
        for (view in NaviampLibraryView.entries) {
            val provider = CatalogTestProvider()
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val controller = NaviampCoreCatalogController(store, { provider }, libraryPageSize = 1,
                mediaRegistry = registry)
            controller.dispatch(NaviampCoreCommand.Library.ChangeView(view))
            controller.execute(NaviampCoreCommand.Library.Refresh)
            val original = store.state.value.shell.library.catalog(view)
            val originalArtists = registry.libraryArtists
            val originalAlbums = registry.libraryAlbums
            val originalTracks = registry.libraryTracks
            for (query in listOf(" ", "First", "")) {
                val command = NaviampCoreCommand.Library.ChangeQuery(query)
                controller.dispatch(command)
                controller.execute(command)
            }
            assertEquals(original, store.state.value.shell.library.catalog(view))
            assertEquals(originalArtists, registry.libraryArtists)
            assertEquals(originalAlbums, registry.libraryAlbums)
            assertEquals(originalTracks, registry.libraryTracks)
            controller.execute(NaviampCoreCommand.Library.LoadMore)
            val offsets = when (view) {
                NaviampLibraryView.Artists -> provider.artistPageOffsets
                NaviampLibraryView.Albums -> provider.albumPageOffsets
                NaviampLibraryView.Songs -> provider.trackPageOffsets
            }
            assertEquals(1, offsets.last())
        }
    }

    @Test
    fun failedOrCancelledLibrarySearchStopsSearchingWithoutStartingARefresh() = runTest {
        for (cancel in listOf(false, true)) {
            val provider = object : MediaProvider by CatalogTestProvider() {
                override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> {
                    if (cancel) throw kotlinx.coroutines.CancellationException("Cancelled")
                    error("Search unavailable")
                }
            }
            val store = NaviampCoreStateStore()
            val controller = NaviampCoreCatalogController(store, { provider })
            val command = NaviampCoreCommand.Library.ChangeQuery("query")
            controller.dispatch(command)
            if (cancel) assertFailsWith<kotlinx.coroutines.CancellationException> { controller.execute(command) }
            else controller.execute(command)
            assertFalse(store.state.value.shell.library.artists.searching)
            assertFalse(store.state.value.shell.library.artists.syncStatus.isSyncing)
            assertEquals(if (cancel) null else "Search unavailable", store.state.value.shell.library.artists.syncStatus.message)
        }
    }

    @Test
    fun explicitLocalIndexRefreshPopulatesTracksForSmartPlaylistSamples() = runTest {
        val repository = CatalogIndexRepository()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun libraryTracksPage(request: MediaPageRequest) = tracksPage(request)
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreCatalogController(
            store, { object : MediaProvider by provider {} }, libraryIndex = repository,
        )
        controller.refreshLocalLibraryIndex()
        assertTrue(repository.syncCompleted)
        assertEquals(listOf("track-2", "track-1"), repository.tracks.map { it.id.value })
        val preview = naviampCoreSmartPlaylistPreviewPort({ "source" }, repository, { 0L })
            .preview(app.naviamp.domain.smartplaylist.SmartPlaylistDefinition.fromNspJson("""{"name":"Songs","all":[{"contains":{"title":"Song"}}]}"""))
        assertEquals(2, preview.matchingTrackCount)
        assertEquals(setOf("First Song", "Second Song"), preview.exampleTracks.map { it.title }.toSet())
    }

    @Test
    fun failedTrackIndexRefreshCannotReportCompletedSync() = runTest {
        val repository = CatalogIndexRepository()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun libraryTracksPage(request: MediaPageRequest): MediaPage<Track> = error("Offline")
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = repository)
        assertFailsWith<IllegalStateException> { controller.refreshLocalLibraryIndex() }
        assertFalse(repository.syncCompleted)
        assertTrue(repository.tracks.isEmpty())
    }

    @Test
    fun sourceChangeCancelsOldFullIndexRefreshWithoutReportingSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = CatalogIndexRepository()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun libraryTracksPage(request: MediaPageRequest): MediaPage<Track> {
                gate.await()
                return tracksPage(request)
            }
        }
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "old")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = repository)
        val refresh = launch { controller.refreshLocalLibraryIndex() }
        runCurrent()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "new")) }
        gate.complete(Unit)
        refresh.join()
        assertTrue(refresh.isCancelled)
        assertFalse(repository.syncCompleted)
    }

    @Test
    fun explicitRefreshSignalsOnlyItsCatalogAfterLoadingAndEachTimeItCompletes() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                gate.await()
                return MediaPage(listOf(Album(AlbumId("new"), "New Album", "Artist", null, null)), 0, request.limit, false)
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider })
        val select = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(select)
        val refresh = launch { controller.execute(NaviampCoreCommand.Library.Refresh) }
        runCurrent()
        assertEquals(0L, store.state.value.shell.library.albums.refreshGeneration)
        controller.dispatch(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs))
        gate.complete(Unit)
        refresh.join()
        assertEquals(1L, store.state.value.shell.library.albums.refreshGeneration)
        assertEquals(0L, store.state.value.shell.library.songs.refreshGeneration)
        controller.dispatch(select)
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(listOf("new"), store.state.value.shell.library.albums.items.map { it.id })
        assertEquals(2L, store.state.value.shell.library.albums.refreshGeneration)
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        controller.execute(select)
        assertEquals(2L, store.state.value.shell.library.albums.refreshGeneration)
    }

    @Test
    fun failedRefreshStillSignalsTheTopButSourceResetRejectsAnOldRefresh() = runTest {
        val store = NaviampCoreStateStore()
        val failing = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> = error("Offline")
        }
        var provider: MediaProvider = failing
        val controller = NaviampCoreCatalogController(store, { provider })
        controller.dispatch(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
        controller.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(1L, store.state.value.shell.library.albums.refreshGeneration)
        val gate = CompletableDeferred<Unit>()
        provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                gate.await()
                return MediaPage(emptyList(), 0, request.limit, false)
            }
        }
        val refresh = launch { controller.execute(NaviampCoreCommand.Library.Refresh) }
        runCurrent()
        controller.resetForSourceChange()
        gate.complete(Unit)
        refresh.join()
        assertEquals(0L, store.state.value.shell.library.albums.refreshGeneration)
    }

    @Test
    fun cachedArtistsAndPagedSongsCoexistAfterConnection() = runTest {
        val repository = CatalogIndexRepository()
        repository.artists += Artist(ArtistId("cached"), "Cached Artist")
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = repository)

        controller.refreshAfterConnection()
        assertEquals(listOf("cached"), store.state.value.shell.library.artists.items.map { it.id })
        assertTrue(provider.artistPageOffsets.isEmpty())
        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)
        assertTrue(store.state.value.shell.library.songs.tracks.isNotEmpty())
        assertEquals(listOf("cached"), store.state.value.shell.library.artists.items.map { it.id })
    }

    @Test
    fun staleCompleteArtistRefreshCannotReplaceTheNewSourceCache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun artistsPage(request: MediaPageRequest): MediaPage<Artist> {
                gate.await()
                return MediaPage(listOf(Artist(ArtistId("stale"), "Stale")), request.offset, request.limit, false)
            }
        }
        val repository = CatalogIndexRepository()
        repository.artists += Artist(ArtistId("cached"), "Cached")
        val store = NaviampCoreStateStore()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "old")) }
        val controller = NaviampCoreCatalogController(store, { provider }, libraryIndex = repository)
        val refresh = launch { controller.execute(NaviampCoreCommand.Library.Refresh) }
        runCurrent()
        store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "new")) }
        controller.resetForSourceChange()
        gate.complete(Unit)
        refresh.join()
        assertEquals(listOf("cached"), repository.artists.map { it.id.value })
        assertTrue(store.state.value.shell.library.artists.items.isEmpty())
    }

    @Test
    fun completeArtistLibraryConsumesEveryProviderPage() = runTest {
        val provider = CatalogTestProvider()

        val artists = provider.loadCompleteArtistLibrary(maximumArtists = 3, pageSize = 2)

        assertEquals(listOf("artist-1", "artist-2", "artist-3"), artists.map { it.id.value })
        assertEquals(listOf(0, 2), provider.artistPageOffsets)
    }

    @Test
    fun completeArtistLibraryRejectsAFalseContinuingPage() = runTest {
        val provider = CatalogTestProvider(emptyContinuingPage = true)

        val failure = assertFailsWith<IllegalStateException> {
            provider.loadCompleteArtistLibrary(maximumArtists = 3, pageSize = 2)
        }

        assertTrue(failure.message.orEmpty().contains("empty continuing page"))
    }

    @Test
    fun offsetLookupCannotOvertakeANewerLetterOrCrossSources() = runTest {
        val gate = CompletableDeferred<Unit>()
        val backing = CatalogTestProvider()
        val provider = object : MediaProvider by backing {
            override suspend fun alphabeticalLibraryOffset(kind: AlphabeticalLibraryKind, letter: Char): Int? {
                if (letter == 'M') gate.await()
                return if (letter == 'M') 10 else 20
            }
        }
        var active: MediaProvider = provider
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { active })
        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)
        val old = launch { controller.execute(NaviampCoreCommand.Library.JumpToLetter('M')) }
        runCurrent()
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('S'))
        gate.complete(Unit)
        old.join()
        assertEquals(listOf(0, 20), backing.trackPageOffsets)
        assertEquals('S', store.state.value.shell.library.jumpRequest?.letter)

        val secondGate = CompletableDeferred<Unit>()
        active = object : MediaProvider by backing {
            override suspend fun alphabeticalLibraryOffset(kind: AlphabeticalLibraryKind, letter: Char): Int? {
                secondGate.await()
                return 99
            }
        }
        val stale = launch { controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z')) }
        runCurrent()
        active = object : MediaProvider by backing { override val cacheNamespace = "different-source" }
        secondGate.complete(Unit)
        stale.join()
        assertEquals(listOf(0, 20), backing.trackPageOffsets)
    }

    @Test
    fun failedFallbackPageStopsInsteadOfRetryingAThousandTimes() = runTest {
        var calls = 0
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun tracksPage(request: MediaPageRequest): MediaPage<Track> {
                calls++
                error("offline")
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, { provider })
        controller.dispatch(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs))
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))
        assertEquals(1, calls)
        assertNull(store.state.value.shell.library.jumpRequest)
        assertNull(store.state.value.shell.library.songs.pendingJump)
    }

    @Test
    fun changingTheSharedSearchFieldExecutesAProviderSearch() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider })
        val command = NaviampCoreCommand.Search.ChangeQuery("Canibus")

        assertEquals(NaviampCoreImmediateCommandResult.Deferred, controller.dispatch(command))
        controller.execute(command)

        assertEquals(listOf("Canibus"), provider.searchQueries)
        assertEquals("Canibus", store.state.value.shell.search.results.artists.single().title)
    }

    @Test
    fun searchPublishesMappedProviderResultsAndCommonStatus() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider })
        controller.dispatch(NaviampCoreCommand.Search.ChangeQuery("  ambient  "))

        controller.execute(NaviampCoreCommand.Search.Submit)

        val search = store.state.value.shell.search
        assertEquals("ambient", provider.searchQueries.single())
        assertEquals("ambient", search.results.artists.single().title)
        assertEquals("Found 1 matches", search.status)
        assertFalse(search.searching)
    }

    @Test
    fun staleSearchCannotOverwriteANewerResult() = runTest {
        val firstSearchGate = CompletableDeferred<Unit>()
        val provider = CatalogTestProvider(firstSearchGate)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider })
        controller.dispatch(NaviampCoreCommand.Search.ChangeQuery("first"))
        val first = launch { controller.execute(NaviampCoreCommand.Search.Submit) }
        runCurrent()
        controller.dispatch(NaviampCoreCommand.Search.ChangeQuery("second"))
        val second = launch { controller.execute(NaviampCoreCommand.Search.Submit) }
        second.join()
        firstSearchGate.complete(Unit)
        first.join()

        assertEquals("second", store.state.value.shell.search.results.artists.single().title)
    }

    @Test
    fun libraryRefreshAndLoadMoreOwnPagingAndDeduplication() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryPageSize = 2,
        )

        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.execute(NaviampCoreCommand.Library.LoadMore)

        assertEquals(listOf("artist-1", "artist-2", "artist-3"), store.state.value.shell.library.artists.items.map { it.id })
        assertFalse(store.state.value.shell.library.artists.syncStatus.isSyncing)
        assertNull(store.state.value.shell.library.artists.syncStatus.message)
        assertEquals(listOf(0, 2), provider.artistPageOffsets)
    }

    @Test
    fun fullLibraryRefreshAlsoRefreshesTheSourceGenreInventory() = runTest {
        var refreshes = 0
        val provider = CatalogTestProvider()
        val controller = NaviampCoreCatalogController(
            stateStore = NaviampCoreStateStore(),
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryGenreRefresh = NaviampCoreLibraryGenreRefreshPort { refreshes += 1 },
        )

        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.execute(NaviampCoreCommand.Library.LoadMore)

        assertEquals(1, refreshes)
    }

    @Test
    fun genreInventoryFailureDoesNotHideTheBrowsableLibrary() = runTest {
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { CatalogTestProvider() },
            libraryGenreRefresh = NaviampCoreLibraryGenreRefreshPort { error("genre endpoint unavailable") },
        )

        controller.execute(NaviampCoreCommand.Library.Refresh)

        assertEquals(listOf("artist-1", "artist-2", "artist-3"), store.state.value.shell.library.artists.items.map { it.id })
        assertNull(store.state.value.shell.library.artists.syncStatus.message)
    }

    @Test
    fun libraryQueryUsesProviderPagingSearchAndJumpIntentStaysInCore() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider })

        controller.dispatch(NaviampCoreCommand.Library.ChangeQuery("three"))
        controller.execute(NaviampCoreCommand.Library.Refresh)
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('t'))

        assertEquals(listOf("artist-3"), store.state.value.shell.library.artists.items.map { it.id })
        assertEquals('T', store.state.value.shell.library.jumpRequest?.letter)
        assertEquals(1L, store.state.value.shell.library.jumpRequest?.generation)
    }

    @Test
    fun alphabetJumpLoadsPagesUntilTheRequestedRangeIsAvailable() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryPageSize = 1,
        )
        controller.execute(NaviampCoreCommand.Library.Refresh)

        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))

        assertEquals(listOf("artist-1", "artist-2", "artist-3"), store.state.value.shell.library.artists.items.map { it.id })
        assertEquals(listOf(0, 0, 1, 2), provider.artistPageOffsets)
        assertEquals('Z', store.state.value.shell.library.jumpRequest?.letter)
    }

    @Test
    fun albumFallbackJumpContinuesPastUnicodeSymbolsAndNumbers() = runTest {
        val titles = listOf("â€™90s Rock Essentials", "25", "G I R L")
        val offsets = mutableListOf<Int>()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                offsets += request.offset
                val items = titles.drop(request.offset).take(request.limit).map {
                    Album(AlbumId(it), it, "Artist", null, null)
                }
                return MediaPage(items, request.offset, request.limit,
                    request.offset + items.size < titles.size)
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider }, libraryPageSize = 1)
        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        controller.execute(albums)
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('G'))
        assertEquals(listOf(0, 0, 1, 2), offsets)
        assertEquals(titles, store.state.value.shell.library.albums.items.map { it.title })
        assertEquals('G', store.state.value.shell.library.jumpRequest?.letter)
        assertNull(store.state.value.shell.library.albums.pendingJump)
    }

    @Test
    fun albumFallbackDoesNotMistakeArticleSortedTitlesForTheRequestedRange() = runTest {
        val titles = listOf("25", "Les AnnÃ©es 80", "The Aquabats!", "Another Album", "G I R L", "Generationwhy")
        val offsets = mutableListOf<Int>()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                offsets += request.offset
                val items = titles.drop(request.offset).take(request.limit).map {
                    Album(AlbumId(it), it, "Artist", null, null)
                }
                return MediaPage(items, request.offset, request.limit, request.offset + items.size < titles.size)
            }
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider }, libraryPageSize = 3)
        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        controller.execute(albums)
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('G'))
        assertEquals(listOf(0, 0, 3), offsets)
        val loaded = store.state.value.shell.library.albums.items.map { it.title }
        assertEquals(titles, loaded)
        assertEquals(4, app.naviamp.domain.library.libraryLetterJumpIndex(loaded, 'G'))
        assertEquals('G', store.state.value.shell.library.jumpRequest?.letter)
    }

    @Test
    fun indexedAlbumsBrowseAndJumpFromCacheButSearchUsesServerMatches() = runTest {
        val snapshots = mutableMapOf<app.naviamp.domain.library.AlbumCatalogScope, app.naviamp.domain.library.AlbumCatalogSnapshot>()
        val repository = object : app.naviamp.domain.library.AlbumCatalogRepository {
            override fun readAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope) = snapshots[scope]
            override fun replaceAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope, snapshot: app.naviamp.domain.library.AlbumCatalogSnapshot) { snapshots[scope] = snapshot }
        }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var requests = 0
        val searchQueries = mutableListOf<String>()
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
                requests++
                gate.await()
                return MediaPage(listOf("The Aquabats!", "G I R L", "25").map {
                    Album(AlbumId(it), it, "Artist", null, null)
                }, 0, request.limit, false)
            }
            override suspend fun searchAlbumsPage(query: String, request: MediaPageRequest): MediaPage<Album> {
                searchQueries += query
                return MediaPage(listOf(Album(AlbumId("G I R L"), "G I R L", "Artist", null, null)),
                    request.offset, request.limit, false)
            }
        }
        val store = NaviampCoreStateStore()
        val index = app.naviamp.domain.library.AlbumLibraryIndex(repository, { "source" }, { 1000L })
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider }, albumIndex = index)
        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        val loading = launch { controller.execute(albums) }
        runCurrent()
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('G'))
        assertEquals('G', store.state.value.shell.library.albums.pendingJump)
        gate.complete(Unit)
        loading.join()
        assertEquals(listOf("25", "G I R L", "The Aquabats!"), store.state.value.shell.library.albums.items.map { it.title })
        assertEquals('G', store.state.value.shell.library.jumpRequest?.letter)
        val query = NaviampCoreCommand.Library.ChangeQuery("girl")
        controller.dispatch(query)
        controller.execute(query)
        assertEquals(listOf("girl"), searchQueries)
        assertEquals("girl", store.state.value.shell.library.albums.resultsQuery)
        assertEquals(listOf("G I R L"), store.state.value.shell.library.albums.items.map { it.title })
        val artists = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Artists)
        controller.dispatch(artists)
        controller.execute(artists)
        controller.dispatch(albums)
        controller.execute(albums)
        assertEquals(listOf("G I R L"), store.state.value.shell.library.albums.items.map { it.title })
        assertEquals(listOf("girl"), searchQueries)
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('T'))
        assertEquals(1, requests)
        val clear = NaviampCoreCommand.Library.ChangeQuery("")
        controller.dispatch(clear)
        controller.execute(clear)
        assertEquals(listOf("25", "G I R L", "The Aquabats!"), store.state.value.shell.library.albums.items.map { it.title })
        val restartedStore = NaviampCoreStateStore()
        val restarted = NaviampCoreCatalogController(restartedStore, NaviampCoreMediaProviderSource { provider }, albumIndex = index)
        restarted.dispatch(albums)
        restarted.execute(albums)
        assertEquals(1, requests)
        assertEquals(store.state.value.shell.library.albums.items, restartedStore.state.value.shell.library.albums.items)
        restarted.execute(NaviampCoreCommand.Library.Refresh)
        assertEquals(2, requests)
        assertEquals(1L, restartedStore.state.value.shell.library.albums.refreshGeneration)
    }

    @Test
    fun indexedAlbumsCanSortByDateAddedAndDisableAlphabeticalJumping() = runTest {
        val snapshots = mutableMapOf<app.naviamp.domain.library.AlbumCatalogScope, app.naviamp.domain.library.AlbumCatalogSnapshot>()
        val repository = object : app.naviamp.domain.library.AlbumCatalogRepository {
            override fun readAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope) = snapshots[scope]
            override fun replaceAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope, snapshot: app.naviamp.domain.library.AlbumCatalogSnapshot) {
                snapshots[scope] = snapshot
            }
        }
        val provider = object : MediaProvider by CatalogTestProvider() {
            override suspend fun albumsPage(request: MediaPageRequest) = MediaPage(
                listOf(
                    Album(AlbumId("old"), "Alpha", "Artist", null, "2026-09-01T00:00:00Z"),
                    Album(AlbumId("new"), "Zulu", "Artist", null, "2026-09-15T00:00:00Z"),
                    Album(AlbumId("missing"), "Beta", "Artist", null, null),
                ),
                0,
                request.limit,
                false,
            )
        }
        val store = NaviampCoreStateStore()
        val changes = mutableListOf<LibraryAlbumSortOrder>()
        val controller = NaviampCoreCatalogController(
            store,
            NaviampCoreMediaProviderSource { provider },
            albumIndex = app.naviamp.domain.library.AlbumLibraryIndex(repository, { "source" }, { 1_000L }),
            onLibraryAlbumSortOrderChanged = changes::add,
        )
        val selectAlbums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(selectAlbums)
        controller.execute(selectAlbums)

        controller.dispatch(NaviampCoreCommand.Library.ChangeAlbumSortOrder(LibraryAlbumSortOrder.RecentlyAdded))
        controller.execute(NaviampCoreCommand.Library.JumpToLetter('Z'))

        assertEquals(listOf("Zulu", "Alpha", "Beta"), store.state.value.shell.library.albums.items.map { it.title })
        assertEquals(LibraryAlbumSortOrder.RecentlyAdded, store.state.value.shell.library.albums.albumSortOrder)
        assertEquals(listOf(LibraryAlbumSortOrder.RecentlyAdded), changes)
        assertEquals(null, store.state.value.shell.library.jumpRequest)
    }

    @Test
    fun songAlphabetJumpUsesTheProvidersGlobalCatalogOffset() = runTest {
        val provider = CatalogTestProvider().apply {
            alphabeticalJumpOffset = 12_345
            alphabeticalJumpTitle = "M Song"
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryPageSize = 25,
        )
        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)

        controller.execute(NaviampCoreCommand.Library.JumpToLetter('m'))

        assertEquals(AlphabeticalLibraryKind.Tracks, provider.alphabeticalJumpKind)
        assertEquals('M', provider.alphabeticalJumpLetter)
        assertEquals(listOf(0, 12_345), provider.trackPageOffsets)
        assertEquals(listOf("M Song"), store.state.value.shell.library.songs.tracks.map { it.title })
        assertEquals('M', store.state.value.shell.library.jumpRequest?.letter)
    }

    @Test
    fun albumAlphabetJumpUsesTheProvidersGlobalCatalogOffset() = runTest {
        val provider = CatalogTestProvider().apply {
            alphabeticalJumpOffset = 8_765
            alphabeticalJumpTitle = "M Album"
        }
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryPageSize = 25,
        )
        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        controller.execute(albums)

        controller.execute(NaviampCoreCommand.Library.JumpToLetter('m'))

        assertEquals(AlphabeticalLibraryKind.Albums, provider.alphabeticalJumpKind)
        assertEquals('M', provider.alphabeticalJumpLetter)
        assertEquals(listOf(0, 8_765), provider.albumPageOffsets)
        assertEquals(listOf("M Album"), store.state.value.shell.library.albums.items.map { it.title })
    }

    @Test
    fun missingProviderProducesSharedDisconnectedState() = runTest {
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { null })
        controller.dispatch(NaviampCoreCommand.Search.ChangeQuery("query"))

        controller.execute(NaviampCoreCommand.Search.Submit)
        controller.execute(NaviampCoreCommand.Library.Refresh)

        assertEquals("Connect to Navidrome to search.", store.state.value.shell.search.status)
        assertEquals("Connect to Navidrome to search.", store.state.value.shell.library.artists.syncStatus.message)
    }

    @Test
    fun switchingViewsLoadsAlbumsAndSongsWithIndependentQueriesAndPaging() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryPageSize = 1,
        )

        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        controller.execute(albums)
        controller.execute(NaviampCoreCommand.Library.LoadMore)
        val albumQuery = NaviampCoreCommand.Library.ChangeQuery("Second")
        controller.dispatch(albumQuery)
        controller.execute(albumQuery)

        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)

        val library = store.state.value.shell.library
        assertEquals(NaviampLibraryView.Songs, library.selectedView)
        assertEquals("Second", library.albums.query)
        assertEquals(listOf("album-2"), library.albums.items.map { it.id })
        assertEquals(listOf("track-2"), library.songs.tracks.map { it.id })
        assertEquals(listOf(0, 1, 0), provider.albumPageOffsets)
        assertEquals(listOf(0), provider.trackPageOffsets)
    }

    @Test
    fun librarySearchIsScopedToTheSelectedView() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider })

        val artistQuery = NaviampCoreCommand.Library.ChangeQuery("One")
        controller.dispatch(artistQuery)
        controller.execute(artistQuery)
        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        controller.execute(albums)
        val albumQuery = NaviampCoreCommand.Library.ChangeQuery("Second")
        controller.dispatch(albumQuery)
        controller.execute(albumQuery)
        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)
        val songQuery = NaviampCoreCommand.Library.ChangeQuery("First")
        controller.dispatch(songQuery)
        controller.execute(songQuery)

        assertEquals(listOf("One"), provider.artistSearchQueries)
        assertEquals(listOf("Second"), provider.albumSearchQueries)
        assertEquals(listOf("First"), provider.trackSearchQueries)
        assertEquals("One", store.state.value.shell.library.artists.query)
        assertEquals("Second", store.state.value.shell.library.albums.query)
        assertEquals("First", store.state.value.shell.library.songs.query)
        assertEquals("One", store.state.value.shell.library.artists.resultsQuery)
        assertEquals("Second", store.state.value.shell.library.albums.resultsQuery)
        assertEquals("First", store.state.value.shell.library.songs.resultsQuery)
    }

    @Test
    fun changingViewRejectsThePreviousViewsLateResponse() = runTest {
        val albumGate = CompletableDeferred<Unit>()
        val provider = CatalogTestProvider(albumPageGate = albumGate)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(store, NaviampCoreMediaProviderSource { provider })
        val albums = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums)
        controller.dispatch(albums)
        val albumLoad = launch { controller.execute(albums) }
        runCurrent()

        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)
        albumGate.complete(Unit)
        albumLoad.join()

        assertEquals(NaviampLibraryView.Songs, store.state.value.shell.library.selectedView)
        assertEquals(emptyList(), store.state.value.shell.library.albums.items)
        assertEquals(listOf("track-1", "track-2"), store.state.value.shell.library.songs.tracks.map { it.id })
    }

    @Test
    fun songsAreAlphabetizedAsAdditionalPagesLoad() = runTest {
        val provider = CatalogTestProvider()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreCatalogController(
            stateStore = store,
            providerSource = NaviampCoreMediaProviderSource { provider },
            libraryPageSize = 1,
        )

        val songs = NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Songs)
        controller.dispatch(songs)
        controller.execute(songs)
        assertEquals(listOf("Second Song"), store.state.value.shell.library.songs.tracks.map { it.title })

        controller.execute(NaviampCoreCommand.Library.LoadMore)
        assertEquals(listOf("First Song", "Second Song"), store.state.value.shell.library.songs.tracks.map { it.title })
    }
}

private class FavoriteCatalogProvider(
    namespace: String = "favorites",
    supportsFavorites: Boolean = true,
    supportsAllKinds: Boolean = false,
) : MediaProvider by CatalogTestProvider() {
    override var cacheNamespace = namespace
    override var selectedMusicFolderIds: List<String> = emptyList()
    override val capabilities = CatalogTestProvider().capabilities.copy(supportsArtistFavorites = supportsFavorites,
        supportsAlbumFavorites = supportsAllKinds, supportsTrackFavorites = supportsAllKinds)
    var browseArtists = listOf(Artist(ArtistId("a"), "Alpha"), Artist(ArtistId("m"), "Middle"))
    var favorites = listOf(Artist(ArtistId("a"), "Alpha"), Artist(ArtistId("z"), "Zulu"))
    var favoriteEnumerationSupported = true
    var beforeFavorites: suspend (MediaPageRequest) -> Unit = {}
    var beforeSearch: suspend (String, MediaPageRequest) -> Unit = { _, _ -> }
    val browseOffsets = mutableListOf<Int>()
    val favoriteOffsets = mutableListOf<Int>()
    val searchRequests = mutableListOf<Pair<String, Int>>()
    var browseAlbums = listOf(albumItem("a"), albumItem("m"))
    var browseTracks = listOf(trackItem("a"), trackItem("m"))
    var favoriteAlbums = listOf(albumItem("a"), albumItem("z"))
    var favoriteSongs = listOf(trackItem("a"), trackItem("z"))
    var beforeKindFavorites: suspend (NaviampLibraryView, MediaPageRequest) -> Unit = { _, _ -> }
    var beforeKindSearch: suspend (NaviampLibraryView, String, MediaPageRequest) -> Unit = { _, _, _ -> }
    val albumBrowseOffsets = mutableListOf<Int>()
    val songBrowseOffsets = mutableListOf<Int>()
    val albumFavoriteOffsets = mutableListOf<Int>()
    val songFavoriteOffsets = mutableListOf<Int>()
    val albumSearchRequests = mutableListOf<Pair<String, Int>>()
    val songSearchRequests = mutableListOf<Pair<String, Int>>()

    override suspend fun artistsPage(request: MediaPageRequest): MediaPage<Artist> {
        browseOffsets += request.offset
        return page(browseArtists, request)
    }

    override suspend fun favoriteArtistsPage(request: MediaPageRequest): MediaPage<Artist>? {
        favoriteOffsets += request.offset
        val result = if (favoriteEnumerationSupported) page(favorites, request) else null
        beforeFavorites(request)
        beforeKindFavorites(NaviampLibraryView.Artists, request)
        return result
    }

    override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> {
        searchRequests += query to request.offset
        val matches = if (query == "new") listOf(Artist(ArtistId("a"), "Alpha"))
            else listOf(Artist(ArtistId("m"), "Middle"), Artist(ArtistId("z"), "Zulu"))
        val result = page(matches, request)
        beforeSearch(query, request)
        beforeKindSearch(NaviampLibraryView.Artists, query, request)
        return result
    }

    override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
        albumBrowseOffsets += request.offset
        return page(browseAlbums, request)
    }

    override suspend fun tracksPage(request: MediaPageRequest): MediaPage<Track> {
        songBrowseOffsets += request.offset
        return page(browseTracks, request)
    }

    override suspend fun favoriteAlbumsPage(request: MediaPageRequest): MediaPage<Album>? {
        albumFavoriteOffsets += request.offset
        val result = if (favoriteEnumerationSupported) page(favoriteAlbums, request) else null
        beforeKindFavorites(NaviampLibraryView.Albums, request)
        return result
    }

    override suspend fun favoriteTracksPage(request: MediaPageRequest): MediaPage<Track>? {
        songFavoriteOffsets += request.offset
        val result = if (favoriteEnumerationSupported) page(favoriteSongs, request) else null
        beforeKindFavorites(NaviampLibraryView.Songs, request)
        return result
    }

    override suspend fun favoriteTracks(limit: Int): List<Track> = error("Capped favorites are not authoritative")
    override suspend fun libraryTracksPage(request: MediaPageRequest): MediaPage<Track>? = error("Index pages are not live membership")

    override suspend fun searchAlbumsPage(query: String, request: MediaPageRequest): MediaPage<Album> {
        albumSearchRequests += query to request.offset
        val result = page(if (query == "new") listOf(albumItem("a")) else listOf(albumItem("m"), albumItem("z")), request)
        beforeKindSearch(NaviampLibraryView.Albums, query, request)
        return result
    }

    override suspend fun searchTracksPage(query: String, request: MediaPageRequest): MediaPage<Track> {
        songSearchRequests += query to request.offset
        val result = page(if (query == "new") listOf(trackItem("a")) else listOf(trackItem("m"), trackItem("z")), request)
        beforeKindSearch(NaviampLibraryView.Songs, query, request)
        return result
    }

    fun albumItem(id: String, favorite: String? = null) = Album(AlbumId(id), title(id), "Artist", null,
        if (id == "z") "2026-10-01T00:00:00Z" else "2025-01-01T00:00:00Z", favoritedAtIso8601 = favorite)

    fun trackItem(id: String, favorite: String? = null) = Track(TrackId(id), title(id), artistName = "Artist",
        albumTitle = "Album", durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
        favoritedAtIso8601 = favorite)

    private fun title(id: String) = when (id) { "a" -> "Alpha"; "m" -> "Middle"; "z" -> "Zulu"; else -> id }

    private fun <T> page(items: List<T>, request: MediaPageRequest): MediaPage<T> {
        val selected = items.drop(request.offset).take(request.limit)
        return MediaPage(selected, request.offset, request.limit, request.offset + selected.size < items.size)
    }
}

private class CatalogTestProvider(
    private val firstSearchGate: CompletableDeferred<Unit>? = null,
    private val emptyContinuingPage: Boolean = false,
    private val albumPageGate: CompletableDeferred<Unit>? = null,
) : MediaProvider {
    override val id = ProviderId("test")
    override val displayName = "Test"
    override val capabilities = ProviderCapabilities(
        supportsStreamingTranscode = false,
        supportsDownloadTranscode = false,
        supportsArtistRadio = false,
        supportsAlbumRadio = false,
        supportsTrackRadio = false,
        supportsArtistFavorites = true,
    )
    val searchQueries = mutableListOf<String>()
    val artistPageOffsets = mutableListOf<Int>()
    val albumPageOffsets = mutableListOf<Int>()
    val trackPageOffsets = mutableListOf<Int>()
    val artistSearchQueries = mutableListOf<String>()
    val albumSearchQueries = mutableListOf<String>()
    val trackSearchQueries = mutableListOf<String>()
    var alphabeticalJumpOffset: Int? = null
    var alphabeticalJumpTitle: String? = null
    var alphabeticalJumpKind: AlphabeticalLibraryKind? = null
    var alphabeticalJumpLetter: Char? = null
    private val libraryArtists = listOf(
        artist("artist-1", "One"),
        artist("artist-2", "Two"),
        artist("artist-3", "Three"),
    )
    private val libraryAlbums = listOf(
        Album(AlbumId("album-1"), "First Album", "One", null, null),
        Album(AlbumId("album-2"), "Second Album", "Two", null, null),
    )
    private val libraryTracks = listOf(
        track("track-2", "Second Song"),
        track("track-1", "First Song"),
    )

    override suspend fun validateConnection() = ConnectionValidation(null, null)
    override suspend fun recentlyAddedAlbums(limit: Int) = emptyList<app.naviamp.domain.Album>()
    override suspend fun album(albumId: AlbumId): AlbumDetails = error("Not used")
    override suspend fun artist(artistId: ArtistId): ArtistDetails = error("Not used")
    override suspend fun artists(limit: Int) = libraryArtists.take(limit)
    override suspend fun artistsPage(request: MediaPageRequest): MediaPage<Artist> {
        artistPageOffsets += request.offset
        if (emptyContinuingPage) {
            return MediaPage(emptyList(), request.offset, request.limit, hasMore = true)
        }
        val items = libraryArtists.drop(request.offset).take(request.limit)
        return MediaPage(items, request.offset, request.limit, request.offset + items.size < libraryArtists.size)
    }

    override suspend fun searchArtistsPage(query: String, request: MediaPageRequest): MediaPage<Artist> {
        artistSearchQueries += query
        val items = libraryArtists.filter { it.name.contains(query, ignoreCase = true) }
        return MediaPage(items, request.offset, request.limit, hasMore = false)
    }

    override suspend fun albumsPage(request: MediaPageRequest): MediaPage<Album> {
        albumPageGate?.await()
        albumPageOffsets += request.offset
        if (request.offset == alphabeticalJumpOffset) {
            val title = requireNotNull(alphabeticalJumpTitle)
            return MediaPage(
                listOf(Album(AlbumId("jump-album"), title, "Artist", null, null)),
                request.offset,
                request.limit,
                hasMore = true,
            )
        }
        val items = libraryAlbums.drop(request.offset).take(request.limit)
        return MediaPage(items, request.offset, request.limit, request.offset + items.size < libraryAlbums.size)
    }

    override suspend fun searchAlbumsPage(query: String, request: MediaPageRequest): MediaPage<Album> {
        albumSearchQueries += query
        albumPageOffsets += request.offset
        val items = libraryAlbums.filter { it.title.contains(query, ignoreCase = true) }
        return MediaPage(items, request.offset, request.limit, hasMore = false)
    }

    override suspend fun tracks(limit: Int) = libraryTracks.take(limit)
    override suspend fun tracksPage(request: MediaPageRequest): MediaPage<Track> {
        trackPageOffsets += request.offset
        if (request.offset == alphabeticalJumpOffset) {
            val title = requireNotNull(alphabeticalJumpTitle)
            return MediaPage(listOf(track("jump-track", title)), request.offset, request.limit, hasMore = true)
        }
        val items = libraryTracks.drop(request.offset).take(request.limit)
        return MediaPage(items, request.offset, request.limit, request.offset + items.size < libraryTracks.size)
    }

    override suspend fun alphabeticalLibraryOffset(kind: AlphabeticalLibraryKind, letter: Char): Int? {
        alphabeticalJumpKind = kind
        alphabeticalJumpLetter = letter
        return alphabeticalJumpOffset
    }

    override suspend fun searchTracksPage(query: String, request: MediaPageRequest): MediaPage<Track> {
        trackSearchQueries += query
        trackPageOffsets += request.offset
        val items = libraryTracks.filter { it.title.contains(query, ignoreCase = true) }
        return MediaPage(items, request.offset, request.limit, hasMore = false)
    }
    override suspend fun search(query: String, limit: Int): MediaSearchResults {
        searchQueries += query
        if (query == "first") firstSearchGate?.await()
        return MediaSearchResults(artists = listOf(artist("search-$query", query)))
    }

    override suspend fun streamUrl(request: StreamRequest) = "https://stream.example"
    override fun coverArtUrl(coverArtId: String) = "https://art.example/$coverArtId"

    private fun artist(id: String, name: String) = Artist(ArtistId(id), name)

    private fun track(id: String, title: String) = Track(
        id = TrackId(id),
        title = title,
        artistName = "Artist",
        albumTitle = "Album",
        durationSeconds = 180,
        coverArtId = null,
        audioInfo = null,
        replayGain = null,
    )
}

    private class CatalogIndexRepository : LocalLibraryIndexRepository {
        var syncStarted = false
            private set
        var syncCompleted = false
            private set
        val artists = mutableListOf<Artist>()
        val albums = mutableListOf<Album>()
        val tracks = mutableListOf<Track>()
        var trackDetailAlbumWrites = 0
            private set
        var checkedScanSignature: String? = null
            private set
        var genres = emptyList<Genre>()
            private set

        override fun mediaSource(sourceId: String) =
            null

        override fun markLibraryScanChecked(sourceId: String, signature: String) {
            checkedScanSignature = signature
        }

        override fun markLibrarySyncStarted(sourceId: String) {
            syncStarted = true
        }

        override fun markLibrarySyncCompleted(sourceId: String) {
            syncCompleted = true
        }

        override fun upsertLibraryArtists(sourceId: String, artists: List<Artist>) {
            this.artists += artists
        }

        override fun replaceLibraryArtists(sourceId: String, artists: List<Artist>) {
            this.artists.clear()
            this.artists += artists
        }

        override fun upsertLibraryAlbums(sourceId: String, albums: List<Album>) {
            if (albums.size == 1) trackDetailAlbumWrites += 1
            this.albums += albums
        }

        var pruned = false
        override fun pruneLibraryTracks(sourceId: String, retainedIds: Set<TrackId>) { pruned = true }
        override fun upsertLibraryTracks(sourceId: String, tracks: List<Track>) {
            this.tracks += tracks
        }

        override fun replaceLibraryGenreInventory(sourceId: String, genres: List<Genre>) {
            this.genres = genres
        }

        override fun libraryTracksForSmartPlaylistPreview(sourceId: String): List<Track> = tracks.toList()

        override fun librarySnapshot(sourceId: String, limit: Long, offset: Long): LibrarySnapshot =
            LibrarySnapshot(artists = artists.toList())

        override fun searchLibrary(sourceId: String, query: String, limit: Long, offset: Long): LibrarySnapshot =
            LibrarySnapshot(artists = artists.toList())

        override fun randomLibraryTrackForAlbum(sourceId: String, albumId: AlbumId): Track? =
            null

        override fun libraryTracksForAlbum(sourceId: String, albumId: AlbumId, limit: Long): List<Track> =
            emptyList()

        override fun randomLibraryTrackForArtist(sourceId: String, artistId: ArtistId): Track? =
            null

        override fun libraryTracksForArtist(sourceId: String, artistId: ArtistId, limit: Long): List<Track> =
            emptyList()

        override fun libraryTracksForArtistName(sourceId: String, artistName: String, limit: Long): List<Track> =
            emptyList()

        override fun relatedLibraryTracks(sourceId: String, track: Track, limit: Long): List<Track> =
            emptyList()

        override fun libraryIndexStats(sourceId: String): LibraryIndexStats =
            LibraryIndexStats(artistCount = artists.size.toLong(), albumCount = albums.size.toLong(), trackCount = tracks.size.toLong())

        override fun libraryAlbumYears(sourceId: String): List<LibraryAlbumYear> =
            emptyList()

        override fun clearLibraryData(sourceId: String?) = Unit

        override fun artistPopularTracks(
            sourceId: String,
            artistId: ArtistId,
            source: String,
        ): List<ArtistPopularTrackMatch> =
            emptyList()

        override fun replaceArtistPopularTracks(
            sourceId: String,
            artistId: ArtistId,
            source: String,
            candidates: List<ArtistPopularTrackCandidate>,
            matchedTracksBySourceTrackId: Map<String, Track>,
            fetchedAtEpochMillis: Long,
        ) = Unit
    }
