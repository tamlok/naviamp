package app.naviamp.presentation

import app.naviamp.domain.Album
import app.naviamp.domain.library.AlbumLibraryIndex
import app.naviamp.domain.library.orderAlbumCatalog
import app.naviamp.domain.library.syncLibraryIndexAndMarkScanChecked
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.LibraryAlbumSortOrder
import app.naviamp.domain.provider.MediaPageRequest
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.AlphabeticalLibraryKind
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.MaximumMediaPageSize
import app.naviamp.domain.provider.SearchDisconnectedStatus
import app.naviamp.domain.provider.normalizedSearchQuery
import app.naviamp.domain.provider.searchResultsUpdate
import app.naviamp.domain.cache.LocalLibraryIndexRepository
import app.naviamp.domain.Artist
import app.naviamp.domain.Track
import app.naviamp.domain.provider.MediaPage
import app.naviamp.ui.NaviampLibrarySyncStatusUi
import app.naviamp.ui.NaviampLibraryCatalogUi
import app.naviamp.ui.NaviampLibraryView
import app.naviamp.ui.NaviampSearchScreenUi
import app.naviamp.ui.SharedSearchResultsUi
import app.naviamp.ui.toSharedMediaItemUi
import app.naviamp.ui.toSharedSearchResultsUi
import app.naviamp.ui.toSharedTrackRowUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns Search and Library state, provider transactions, paging, and stale-result rejection. */
class NaviampCoreCatalogController(
    private val stateStore: NaviampCoreStateStore,
    private val providerSource: NaviampCoreMediaProviderSource,
    private val libraryPageSize: Int = 50,
    private val libraryGenreRefresh: NaviampCoreLibraryGenreRefreshPort = NaviampCoreLibraryGenreRefreshPort { },
    private val libraryIndex: LocalLibraryIndexRepository? = null,
    private val mediaRegistry: NaviampCoreMediaRegistry = NaviampCoreMediaRegistry(),
    private val albumIndex: AlbumLibraryIndex? = null,
    private val onLibraryAlbumSortOrderChanged: (LibraryAlbumSortOrder) -> Unit = {},
) : NaviampCoreCommandController {
    init {
        val order = stateStore.state.value.shell.general.interfaceSettings.libraryAlbumSortOrder
        updateLibraryCatalog(NaviampLibraryView.Albums) { it.copy(albumSortOrder = order) }
        NaviampLibraryView.entries.forEach { view ->
            updateLibraryCatalog(view) { it.copy(supportsFavorites = supportsFavorites(view, providerSource.current())) }
        }
    }

    private val fullIndexRefreshMutex = Mutex()
    private var searchGeneration = 0L
    private data class LibraryBrowseSnapshot(
        val catalog: NaviampLibraryCatalogUi,
        val nextRequest: MediaPageRequest?,
        val sourceKey: Pair<String, String>?,
        val loadedSource: Pair<String, String>?,
        val media: MediaSearchResults,
    )
    private data class LibraryLoadState(
        var generation: Long = 0L,
        var nextRequest: MediaPageRequest? = null,
        var loadingGeneration: Long? = null,
        var loadedSource: Pair<String, String>? = null,
        var completeAlbumCatalog: Boolean = false,
        var browseSnapshot: LibraryBrowseSnapshot? = null,
        var ordinarySnapshot: LibraryBrowseSnapshot? = null,
        var scope: FavoritesScope? = null,
    )

    private val libraryLoads = NaviampLibraryView.entries.associateWith {
        LibraryLoadState(nextRequest = MediaPageRequest(limit = libraryPageSize))
    }
    private var jumpGeneration = 0L
    private var refreshGeneration = 0L
    private val explicitRefreshes = mutableMapOf<NaviampLibraryView, Long>()

    private data class FavoritesScope(
        val sourceId: String?,
        val providerId: String,
        val namespace: String,
        val musicFolders: Set<String>,
    )
    private var favoriteScope: FavoritesScope? = null

    /** Typed storage; completion, projection, mutation and invalidation policy is shared below. */
    private class Favorites<T>(
        val view: NaviampLibraryView,
        val id: (T) -> String,
        val title: (T) -> String,
        val timestamp: (T) -> String?,
        val withTimestamp: (T, String?) -> T,
        val media: (MediaSearchResults) -> List<T>,
        val withMedia: (MediaSearchResults, List<T>) -> MediaSearchResults,
        val library: () -> List<T>,
        val publish: (List<T>) -> Unit,
        val catalog: (NaviampLibraryCatalogUi, List<T>, MediaProvider) -> NaviampLibraryCatalogUi,
        val favoritePage: suspend (MediaProvider, MediaPageRequest) -> MediaPage<T>?,
        val browsePage: suspend (MediaProvider, MediaPageRequest) -> MediaPage<T>,
        val searchPage: suspend (MediaProvider, String, MediaPageRequest) -> MediaPage<T>,
    ) {
        var members: MutableMap<String, T>? = null
        val mutations = mutableMapOf<String, T>()
        var invalidated = false
        var generation = 0L
        var loadingGeneration: Long? = null
        var searchQuery: String? = null
        var searchItems: List<T> = emptyList()
        var searchComplete = false
        var browseItems: List<T> = emptyList()

        fun cancel() {
            generation++
            loadingGeneration = null
        }

        fun clear() {
            cancel()
            members = null
            mutations.clear()
            searchQuery = null
            searchItems = emptyList()
            searchComplete = false
            browseItems = emptyList()
            invalidated = false
        }
    }

    private val artistFavorites = Favorites(
        NaviampLibraryView.Artists, { it: Artist -> it.id.value }, Artist::name, Artist::favoritedAtIso8601,
        { item, timestamp -> item.copy(favoritedAtIso8601 = timestamp) },
        MediaSearchResults::artists, { media, items -> media.copy(artists = items) },
        { mediaRegistry.libraryArtists }, { mediaRegistry.updateLibraryArtists(it, true) },
        { catalog, items, provider -> catalog.copy(items = artistItems(items, provider)) },
        MediaProvider::favoriteArtistsPage, MediaProvider::artistsPage, MediaProvider::searchArtistsPage,
    )
    private val albumFavorites = Favorites(
        NaviampLibraryView.Albums, { it: Album -> it.id.value }, Album::title, Album::favoritedAtIso8601,
        { item, timestamp -> item.copy(favoritedAtIso8601 = timestamp) },
        MediaSearchResults::albums, { media, items -> media.copy(albums = items) },
        { mediaRegistry.libraryAlbums }, { mediaRegistry.updateLibraryAlbums(it, true) },
        { catalog, items, provider -> catalog.copy(items = items.map {
            it.toSharedMediaItemUi(coverArtUrl = { id -> id?.let(provider::coverArtUrl) },
                canFavorite = provider.capabilities.supportsAlbumFavorites)
        }, albumSortOrder = currentAlbumSortOrder()) },
        MediaProvider::favoriteAlbumsPage, MediaProvider::albumsPage, MediaProvider::searchAlbumsPage,
    )
    private val trackFavorites = Favorites(
        NaviampLibraryView.Songs, { it: Track -> it.id.value }, Track::title, Track::favoritedAtIso8601,
        { item, timestamp -> item.copy(favoritedAtIso8601 = timestamp) },
        MediaSearchResults::tracks, { media, items -> media.copy(tracks = items) },
        { mediaRegistry.libraryTracks }, { mediaRegistry.updateLibraryTracks(it, true) },
        { catalog, items, provider -> catalog.copy(tracks = items.map {
            it.toSharedTrackRowUi(coverArtUrl = { id -> id?.let(provider::coverArtUrl) })
        }) },
        MediaProvider::favoriteTracksPage, MediaProvider::tracksPage, MediaProvider::searchTracksPage,
    )

    private fun favorites(view: NaviampLibraryView): Favorites<*> = when (view) {
        NaviampLibraryView.Artists -> artistFavorites
        NaviampLibraryView.Albums -> albumFavorites
        NaviampLibraryView.Songs -> trackFavorites
    }

    private fun supportsFavorites(view: NaviampLibraryView, provider: MediaProvider?): Boolean = when (view) {
        NaviampLibraryView.Artists -> provider?.capabilities?.supportsArtistFavorites == true
        NaviampLibraryView.Albums -> provider?.capabilities?.supportsAlbumFavorites == true
        NaviampLibraryView.Songs -> provider?.capabilities?.supportsTrackFavorites == true
    }

    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult = when (command) {
        is NaviampCoreCommand.Search.ChangeQuery -> {
            updateSearchQuery(command.query)
            NaviampCoreImmediateCommandResult.Deferred
        }
        NaviampCoreCommand.Search.Clear -> handled(::clearSearch)
        NaviampCoreCommand.Search.Submit -> NaviampCoreImmediateCommandResult.Deferred
        is NaviampCoreCommand.Library.ChangeView -> {
            selectLibraryView(command.view)
            NaviampCoreImmediateCommandResult.Deferred
        }
        is NaviampCoreCommand.Library.ChangeQuery -> {
            updateLibraryQuery(command.query)
            NaviampCoreImmediateCommandResult.Deferred
        }
        is NaviampCoreCommand.Library.ChangeFavoritesOnly -> {
            changeFavoritesOnly(command.enabled)
            NaviampCoreImmediateCommandResult.Deferred
        }
        is NaviampCoreCommand.Library.JumpToLetter -> NaviampCoreImmediateCommandResult.Deferred
        is NaviampCoreCommand.Library.ChangeAlbumSortOrder -> handled {
            changeAlbumSortOrder(command.order)
        }
        NaviampCoreCommand.Library.Refresh,
        NaviampCoreCommand.Library.LoadMore,
        -> NaviampCoreImmediateCommandResult.Deferred
        else -> NaviampCoreImmediateCommandResult.Unhandled
    }

    override suspend fun execute(command: NaviampCoreCommand): NaviampCoreCommandResult? {
        if (command is NaviampCoreCommand.Library) useFavoritesScope(providerSource.current())
        when (command) {
            NaviampCoreCommand.Search.Submit,
            is NaviampCoreCommand.Search.ChangeQuery,
            -> search()
            is NaviampCoreCommand.Library.ChangeView -> {
                val provider = providerSource.current()
                useFavoritesScope(provider)
                val library = stateStore.state.value.shell.library
                if (library.selectedView == command.view) {
                    val catalog = library.catalog(command.view)
                    val loaded = libraryLoads.getValue(command.view).loadedSource ==
                        provider?.let { it.id.value to it.cacheNamespace }
                    if (library.favoritesOnly) {
                        completeFavorites(command.view)
                    } else if (catalog.query.isNotBlank()) {
                        if (!loaded) searchLibrary(catalog.query)
                    } else if (!loaded || command.view == NaviampLibraryView.Albums && albumIndex != null) {
                        refreshLibrary(command.view)
                    }
                }
            }
            is NaviampCoreCommand.Library.ChangeQuery -> {
                val library = stateStore.state.value.shell.library
                if (library.catalog(library.selectedView).query == command.query && library.favoritesOnly) {
                    completeFavorites(library.selectedView)
                } else searchLibrary(command.query)
            }
            is NaviampCoreCommand.Library.ChangeFavoritesOnly -> {
                val library = stateStore.state.value.shell.library
                if (library.favoritesOnly == command.enabled) {
                    val view = library.selectedView
                    if (command.enabled) completeFavorites(view)
                    else if (libraryLoads.getValue(view).loadedSource == null) {
                        val query = library.catalog(view).query
                        if (query.isBlank()) refreshLibrary(view) else searchLibrary(query)
                    }
                }
            }
            NaviampCoreCommand.Library.Refresh -> refreshLibraryExplicitly()
            NaviampCoreCommand.Library.LoadMore -> loadMoreLibrary()
            is NaviampCoreCommand.Library.JumpToLetter -> jumpToLetter(command.letter)
            is NaviampCoreCommand.Library.ChangeAlbumSortOrder -> Unit
            else -> return null
        }
        return NaviampCoreCommandResult.Completed
    }

    private suspend fun search() {
        val generation = ++searchGeneration
        val query = stateStore.state.value.shell.search.query
        val normalized = normalizedSearchQuery(query)
        val provider = providerSource.current()
        if (normalized == null) {
            publishSearch(MediaSearchResults(), status = null, searching = false, provider = provider)
            return
        }
        if (provider == null) {
            publishSearch(MediaSearchResults(), SearchDisconnectedStatus, searching = false, provider = null)
            return
        }
        updateSearchState { it.copy(searching = true, status = "Searching...") }
        val update = searchResultsUpdate(normalized) { searchQuery, limit ->
            provider.search(searchQuery, limit)
        }
        if (generation != searchGeneration || !providerSource.isCurrent(provider)) return
        publishSearch(update.results, update.status, searching = false, provider = provider)
    }

    private suspend fun refreshLibraryExplicitly() {
        val view = stateStore.state.value.shell.library.selectedView
        val provider = providerSource.current()
        val sourceId = activeLibrarySourceId()
        val query = stateStore.state.value.shell.library.catalog(view).query
        useFavoritesScope(provider)
        val expectedLoadGeneration = libraryLoads.getValue(view).generation + 1
        val generation = ++refreshGeneration
        val favorites = favorites(view)
        val refreshFavorites = favorites.members != null || favorites.invalidated
        explicitRefreshes[view] = generation
        if (stateStore.state.value.shell.library.favoritesOnly) {
            favorites.cancel()
            libraryLoads.getValue(view).generation++
            completeFavorites(view, forceRefresh = true)
            if (explicitRefreshes[view] == generation && activeLibrarySourceId() == sourceId &&
                provider != null && providerSource.isCurrent(provider)) {
                updateLibraryCatalog(view) { it.copy(refreshGeneration = generation) }
            }
            return
        }
        favorites.cancel()
        favorites.members = null
        favorites.mutations.clear()
        refreshLibrary(view, forceRefresh = true)
        val refreshed = stateStore.state.value.shell.library.catalog(view)
        if (query.isNotBlank() && refreshed.query == query && !refreshed.searching &&
            stateStore.state.value.shell.library.selectedView == view &&
            libraryLoads.getValue(view).generation == expectedLoadGeneration &&
            !refreshed.syncStatus.isSyncing && refreshed.syncStatus.message == null &&
            !refreshed.syncStatus.albumIndexFailed && activeLibrarySourceId() == sourceId &&
            providerSource.current() === provider) {
            // Explicit refresh replaces the browse snapshot too; clearing must not revive old data.
            libraryLoads.getValue(view).browseSnapshot = captureBrowseSnapshot(view)
            searchLibrary(query)
        }
        if (refreshFavorites && explicitRefreshes[view] == generation &&
            libraryLoads.getValue(view).generation == expectedLoadGeneration &&
            stateStore.state.value.shell.library.selectedView == view &&
            stateStore.state.value.shell.library.catalog(view).query == query &&
            activeLibrarySourceId() == sourceId && provider != null && providerSource.isCurrent(provider)) {
            completeFavorites(view, forceRefresh = true)
        }
        // Publish after the replacement data so keyed lists cannot anchor to an old first item.
        if (explicitRefreshes[view] == generation && activeLibrarySourceId() == sourceId &&
            providerSource.current() === provider
        ) {
            updateLibraryCatalog(view) { it.copy(refreshGeneration = generation) }
        }
    }

    private suspend fun refreshLibrary(
        view: NaviampLibraryView = stateStore.state.value.shell.library.selectedView,
        forceRefresh: Boolean = false,
    ) {
        useFavoritesScope(providerSource.current())
        if (stateStore.state.value.shell.library.favoritesOnly) {
            completeFavorites(view, forceRefresh)
            return
        }
        if (view == NaviampLibraryView.Artists && libraryIndex != null &&
            (forceRefresh || stateStore.state.value.shell.library.artists.query.isBlank()) &&
            activeLibrarySourceId() != null && providerSource.current() != null
        ) {
            refreshArtistIndex()
            return
        }
        if (view == NaviampLibraryView.Albums && albumIndex != null) {
            refreshAlbumIndex(forceRefresh)
            return
        }
        val load = libraryLoads.getValue(view)
        val generation = ++load.generation
        load.loadedSource = null
        updateLibraryCatalog(view) { it.copy(pendingJump = null) }
        val request = MediaPageRequest(limit = libraryPageSize)
        load.nextRequest = request
        loadLibraryPage(view, request, replace = true, generation = generation,
            queryOverride = if (forceRefresh) "" else null)
    }

    private suspend fun refreshAlbumIndex(force: Boolean) {
        val index = albumIndex ?: return
        val provider = providerSource.current() ?: return
        val view = NaviampLibraryView.Albums
        val load = libraryLoads.getValue(view)
        if (!force && load.loadingGeneration == load.generation) return
        val generation = ++load.generation
        val sourceKey = provider.id.value to provider.cacheNamespace
        load.scope = favoritesScope(provider)
        val scope = index.scope(provider)
        fun current() = isCurrentLibraryLoad(load, generation, sourceKey)
        fun publish(albums: List<Album>) {
            val ordered = orderAlbumCatalog(projectFavorites(albumFavorites, albums, mergeFavorites = true), currentAlbumSortOrder())
            mediaRegistry.updateLibraryAlbums(ordered, replace = true)
            val items = ordered.map { album -> album.toSharedMediaItemUi(
                coverArtUrl = { id -> id?.let(provider::coverArtUrl) },
                canFavorite = provider.capabilities.supportsAlbumFavorites,
            ) }
            updateLibraryCatalog(view) { it.copy(items = items, resultsQuery = null, searching = false,
                albumSortOrder = currentAlbumSortOrder()) }
        }
        val cached = index.snapshot(scope)
        load.completeAlbumCatalog = cached != null
        if (cached != null) publish(cached.albums)
        load.nextRequest = null
        load.loadedSource = sourceKey.takeIf { cached != null }
        if (!force && cached != null && index.isFresh(cached)) {
            updateLibraryCatalog(view) { it.copy(syncStatus = NaviampLibrarySyncStatusUi()) }
            return
        }
        load.loadingGeneration = generation
        updateLibraryCatalog(view) { it.copy(syncStatus = NaviampLibrarySyncStatusUi(isSyncing = true, albumIndexCount = 0)) }
        try {
            val refreshed = index.refresh(scope, provider, ::current) { partial ->
                if (cached == null) publish(partial)
                updateLibraryCatalog(view) { it.copy(syncStatus = NaviampLibrarySyncStatusUi(
                    isSyncing = true, albumIndexCount = partial.size,
                )) }
            } ?: return
            if (!current()) return
            publish(refreshed.albums)
            load.completeAlbumCatalog = true
            load.loadedSource = sourceKey
            updateLibraryCatalog(view) { it.copy(syncStatus = NaviampLibrarySyncStatusUi()) }
            stateStore.state.value.shell.library.albums.pendingJump?.let { letter ->
                if (stateStore.state.value.shell.library.selectedView == view) publishLibraryJump(letter)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            if (current()) updateLibraryCatalog(view) { it.copy(
                syncStatus = NaviampLibrarySyncStatusUi(albumIndexFailed = true),
            ) }
        } finally {
            if (current()) {
                load.loadingGeneration = null
                updateLibraryCatalog(view) { it.copy(pendingJump = null, syncStatus = it.syncStatus.copy(isSyncing = false, albumIndexCount = null)) }
            }
        }
    }

    fun resetForSourceChange() {
        explicitRefreshes.clear()
        NaviampLibraryView.entries.forEach { favorites(it).clear() }
        favoriteScope = null
        clearSearch()
        jumpGeneration++
        libraryLoads.values.forEach {
            it.generation++
            it.loadedSource = null
            it.loadingGeneration = null
            it.completeAlbumCatalog = false
            it.browseSnapshot = null
            it.ordinarySnapshot = null
            it.nextRequest = MediaPageRequest(limit = libraryPageSize)
        }
        stateStore.updateShell {
            it.copy(library = app.naviamp.ui.NaviampLibraryScreenUi(
                selectedView = it.library.selectedView,
                artists = NaviampLibraryCatalogUi(supportsFavorites = supportsFavorites(NaviampLibraryView.Artists, providerSource.current())),
                albums = NaviampLibraryCatalogUi(albumSortOrder = currentAlbumSortOrder(),
                    supportsFavorites = supportsFavorites(NaviampLibraryView.Albums, providerSource.current())),
                songs = NaviampLibraryCatalogUi(supportsFavorites = supportsFavorites(NaviampLibraryView.Songs, providerSource.current())),
            ))
        }
        mediaRegistry.updateLibraryArtists(emptyList(), true)
        mediaRegistry.updateLibraryAlbums(emptyList(), true)
        mediaRegistry.updateLibraryTracks(emptyList(), true)
    }

    /** Keep metadata/database work off the render thread; the editor owns cancellation. */
    suspend fun syncSmartPlaylistPreview(onProgress: suspend (app.naviamp.domain.library.LibrarySyncProgress) -> Unit) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            fullIndexRefreshMutex.withLock {
                val provider = providerSource.current() ?: throw IllegalStateException(SearchDisconnectedStatus)
                val sourceId = activeLibrarySourceId() ?: throw IllegalStateException(SearchDisconnectedStatus)
                val repository = checkNotNull(libraryIndex)
                app.naviamp.domain.library.syncSmartPlaylistPreviewTracks(sourceId, provider, repository) { progress ->
                    if (!providerSource.isCurrent(provider) || activeLibrarySourceId() != sourceId) {
                        throw CancellationException("Library source changed during preview sync")
                    }
                    onProgress(progress)
                    if (!providerSource.isCurrent(provider) || activeLibrarySourceId() != sourceId) {
                        throw CancellationException("Library source changed during preview sync")
                    }
                }
                Unit
            }
        }

    /** Explicit local-data refresh, including the tracks consumed by smart-playlist previews. */
    suspend fun refreshLocalLibraryIndex() = fullIndexRefreshMutex.withLock {
        val repository = libraryIndex
        if (repository == null) {
            refreshAfterConnection()
            return@withLock
        }
        val provider = providerSource.current() ?: throw IllegalStateException(SearchDisconnectedStatus)
        val sourceId = activeLibrarySourceId() ?: throw IllegalStateException(SearchDisconnectedStatus)
        fun requireCurrentSource() {
            if (!providerSource.isCurrent(provider) || activeLibrarySourceId() != sourceId) {
                throw CancellationException("Library source changed during index refresh")
            }
        }
        syncLibraryIndexAndMarkScanChecked(
            sourceId = sourceId,
            provider = provider,
            libraryIndexRepository = repository,
            artistLimit = Int.MAX_VALUE,
            albumPageSize = MaximumMediaPageSize,
            includeAlbumTracks = true,
            onProgress = { requireCurrentSource() },
        )
        requireCurrentSource()
        refreshAfterConnection()
    }

    suspend fun refreshAfterConnection() {
        resetForSourceChange()
        val provider = providerSource.current()
        val sourceId = activeLibrarySourceId()
        val repository = libraryIndex
        if (provider == null || sourceId == null || repository == null ||
            stateStore.state.value.shell.library.selectedView != NaviampLibraryView.Artists
        ) {
            refreshLibrary()
            return
        }
        val cached = repository.librarySnapshot(sourceId, limit = Long.MAX_VALUE).artists
        if (cached.isEmpty()) {
            refreshLibrary()
            return
        }
        val load = libraryLoads.getValue(NaviampLibraryView.Artists)
        val generation = load.generation
        val sourceKey = provider.id.value to provider.cacheNamespace
        load.scope = favoritesScope(provider)
        publishLibraryArtists(cached, provider)
        load.nextRequest = null
        load.loadedSource = sourceKey
        val scan = try {
            provider.libraryScanStatus()
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            null
        }
        if (!isCurrentLibraryLoad(load, generation, sourceKey) || sourceId != activeLibrarySourceId()) return
        val scanSignature = scan?.signature
        val previousSignature = repository.mediaSource(sourceId)?.lastLibraryScanSignature
        when {
            scan?.scanning == true -> publishLibraryStatus(
                NaviampLibraryView.Artists,
                "Navidrome is scanning. Refresh library after the scan finishes.",
                loading = false,
            )
            scanSignature != null && previousSignature == null -> {
                repository.markLibraryScanChecked(sourceId, scanSignature)
                publishLibraryStatus(NaviampLibraryView.Artists, null, loading = false)
            }
            scanSignature == null || scanSignature == previousSignature ->
                publishLibraryStatus(NaviampLibraryView.Artists, null, loading = false)
            else -> refreshLibrary()
        }
    }

    private fun activeLibrarySourceId(): String? =
        stateStore.state.value.shell.connectionSettings.currentSourceId

    private suspend fun refreshArtistIndex() {
        val provider = providerSource.current() ?: return
        val sourceId = activeLibrarySourceId() ?: return
        val repository = libraryIndex ?: return
        val view = NaviampLibraryView.Artists
        val load = libraryLoads.getValue(view)
        val generation = ++load.generation
        val sourceKey = provider.id.value to provider.cacheNamespace
        load.scope = favoritesScope(provider)
        fun current() = isCurrentLibraryLoad(load, generation, sourceKey) && sourceId == activeLibrarySourceId()
        load.loadingGeneration = generation
        publishLibraryStatus(view, "Refreshing libraryâ€¦", loading = true)
        try {
            val artists = provider.loadCompleteArtistLibrary()
            if (!current()) return
            repository.replaceLibraryArtists(sourceId, artists)
            publishLibraryArtists(artists, provider)
            load.nextRequest = null
            load.loadedSource = sourceKey
            provider.libraryScanStatus()?.signature?.let { signature ->
                if (current()) repository.markLibraryScanChecked(sourceId, signature)
            }
            if (current()) runCatching { libraryGenreRefresh.refresh() }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (current()) publishLibraryStatus(view, cause.message ?: "Could not load library.", loading = false)
        } finally {
            if (current()) {
                load.loadingGeneration = null
                updateLibraryCatalog(view) { it.copy(syncStatus = it.syncStatus.copy(isSyncing = false)) }
            }
        }
    }

    private fun favoritesScope(provider: MediaProvider) = FavoritesScope(
        activeLibrarySourceId(), provider.id.value, provider.cacheNamespace, provider.selectedMusicFolderIds.toSet(),
    )

    private fun useFavoritesScope(provider: MediaProvider?) {
        if (provider == null) return
        val scope = favoritesScope(provider)
        if (favoriteScope == scope) return
        val changed = favoriteScope != null
        favoriteScope = scope
        NaviampLibraryView.entries.forEach { view ->
            favorites(view).clear()
            if (changed) {
                val load = libraryLoads.getValue(view)
                load.generation++
                load.loadingGeneration = null
                load.loadedSource = null
                load.browseSnapshot = null
                load.ordinarySnapshot = null
                load.nextRequest = MediaPageRequest(limit = libraryPageSize)
                load.completeAlbumCatalog = false
                clearLibraryMedia(view)
            }
            updateLibraryCatalog(view) { catalog ->
                val current = if (changed) NaviampLibraryCatalogUi(query = catalog.query,
                    albumSortOrder = currentAlbumSortOrder()) else catalog
                current.copy(supportsFavorites = supportsFavorites(view, provider),
                    favoritesLoading = stateStore.state.value.shell.library.favoritesOnly && supportsFavorites(view, provider),
                    favoritesFailed = false)
            }
            if (changed && stateStore.state.value.shell.library.favoritesOnly) {
                libraryLoads.getValue(view).ordinarySnapshot = captureSnapshot(view)
            }
        }
    }

    private fun changeFavoritesOnly(enabled: Boolean) {
        val provider = providerSource.current()
        useFavoritesScope(provider)
        val library = stateStore.state.value.shell.library
        if (enabled && !supportsFavorites(library.selectedView, provider)) return
        if (library.favoritesOnly == enabled) return
        NaviampLibraryView.entries.forEach { view ->
            favorites(view).cancel()
            val load = libraryLoads.getValue(view)
            if (enabled) load.ordinarySnapshot = captureSnapshot(view)
            load.generation++
            load.loadingGeneration = null
        }
        // Selection/status and filter publish together: no frame can show an unfiltered empty state.
        stateStore.updateShell { shell ->
            fun catalog(view: NaviampLibraryView): NaviampLibraryCatalogUi {
                val current = shell.library.catalog(view)
                val retained = if (enabled) current else libraryLoads.getValue(view).ordinarySnapshot?.catalog ?: current
                return retained.copy(query = current.query, pendingJump = null, searching = false,
                    syncStatus = NaviampLibrarySyncStatusUi(), refreshGeneration = current.refreshGeneration,
                    supportsFavorites = supportsFavorites(view, provider),
                    favoritesLoading = enabled && supportsFavorites(view, provider), favoritesFailed = false,
                    albumSortOrder = if (view == NaviampLibraryView.Albums) currentAlbumSortOrder() else retained.albumSortOrder)
            }
            shell.copy(library = shell.library.copy(favoritesOnly = enabled,
                artists = catalog(NaviampLibraryView.Artists), albums = catalog(NaviampLibraryView.Albums),
                songs = catalog(NaviampLibraryView.Songs)))
        }
        NaviampLibraryView.entries.forEach { view ->
            val load = libraryLoads.getValue(view)
            if (!enabled) {
                load.ordinarySnapshot?.let { restoreSnapshot(view, it, publishCatalog = false) }
                load.ordinarySnapshot = null
            }
            if (provider != null) reprojectFavorites(view, provider)
        }
    }

    /** A local mutation wins over in-flight provider pages and detached ordinary/query snapshots. */
    fun artistUpdated(provider: MediaProvider, artist: Artist) = favoriteUpdated(artistFavorites, provider, artist)

    fun albumUpdated(provider: MediaProvider, album: Album) = favoriteUpdated(albumFavorites, provider, album)

    fun trackUpdated(provider: MediaProvider, track: Track) = favoriteUpdated(trackFavorites, provider, track)

    private fun <T> favoriteUpdated(favorites: Favorites<T>, provider: MediaProvider, item: T) {
        if (!providerSource.isCurrent(provider)) return
        useFavoritesScope(provider)
        favorites.mutations[favorites.id(item)] = item
        favorites.members?.let { members ->
            if (favorites.timestamp(item) == null) members.remove(favorites.id(item))
            else members[favorites.id(item)] = item
        }
        reprojectFavorites(favorites, provider)
    }

    private fun <T> overlayFavorite(favorites: Favorites<T>, item: T): T {
        val mutation = favorites.mutations[favorites.id(item)]
        val base = mutation ?: item
        if (mutation == null && favorites.members == null && !favorites.invalidated) return item
        val membershipTimestamp = if (mutation != null) favorites.timestamp(mutation)
            else favorites.members?.get(favorites.id(item))?.let(favorites.timestamp)
        val knownTimestamp = favorites.timestamp(item)?.takeUnless { it.isBlank() || it == "favorite" }
        val timestamp = if (membershipTimestamp != null && (membershipTimestamp.isBlank() || membershipTimestamp == "favorite"))
            knownTimestamp ?: membershipTimestamp else membershipTimestamp
        return if (favorites.timestamp(base) == timestamp) base else favorites.withTimestamp(base, timestamp)
    }

    private fun <T> projectFavorites(favorites: Favorites<T>, items: List<T>, mergeFavorites: Boolean): List<T> {
        if (favorites.members == null && favorites.mutations.isEmpty() && !favorites.invalidated) return items
        val candidates = if (mergeFavorites) {
            val merged = items.associateByTo(linkedMapOf(), favorites.id)
            favorites.members?.values?.forEach { if (favorites.id(it) !in merged) merged[favorites.id(it)] = it }
            favorites.mutations.values.forEach {
                if (favorites.timestamp(it) != null && favorites.id(it) !in merged) merged[favorites.id(it)] = it
            }
            merged.values
        } else items
        return candidates.map { overlayFavorite(favorites, it) }
    }

    private fun artistItems(artists: List<Artist>, provider: MediaProvider) = artists.map { artist ->
        artist.toSharedMediaItemUi(coverArtUrl = { id -> id?.let(provider::coverArtUrl) },
            canFavorite = provider.capabilities.supportsArtistFavorites)
    }

    private fun reprojectFavorites(view: NaviampLibraryView, provider: MediaProvider) =
        reprojectFavorites(favorites(view), provider)

    private fun <T> reprojectFavorites(favorites: Favorites<T>, provider: MediaProvider) {
        val view = favorites.view
        val load = libraryLoads.getValue(view)
        val library = stateStore.state.value.shell.library
        val query = library.catalog(view).query
        fun projectSnapshot(snapshot: LibraryBrowseSnapshot): LibraryBrowseSnapshot {
            val items = projectFavorites(favorites, favorites.media(snapshot.media), mergeFavorites = false)
            return snapshot.copy(media = favorites.withMedia(snapshot.media, items),
                catalog = favorites.catalog(snapshot.catalog, items, provider))
        }
        load.ordinarySnapshot = load.ordinarySnapshot?.let(::projectSnapshot)
        load.browseSnapshot = load.browseSnapshot?.let(::projectSnapshot)
        val base = if (library.favoritesOnly) {
            if (query.isNotBlank()) {
                if (favorites.searchQuery == query) favorites.searchItems else emptyList()
            } else {
                val ordinary = load.browseSnapshot?.let { favorites.media(it.media) }
                    ?: load.ordinarySnapshot?.let { favorites.media(it.media) }.orEmpty()
                (ordinary + favorites.browseItems).distinctBy(favorites.id)
            }
        } else favorites.library()
        val merge = query.isBlank() && (library.favoritesOnly || load.nextRequest == null)
        val projected = projectFavorites(favorites, base, merge)
        val ordered = if (view == NaviampLibraryView.Albums) projected else if (library.favoritesOnly && query.isBlank())
            projected.sortedWith(compareBy({ favorites.title(it).lowercase() }, favorites.title, favorites.id)) else projected
        if (library.favoritesOnly && query.isBlank()) favorites.browseItems = ordered
        favorites.publish(ordered)
        if (view == NaviampLibraryView.Albums) {
            val albums = orderAlbumCatalog(mediaRegistry.libraryAlbums, currentAlbumSortOrder())
            mediaRegistry.updateLibraryAlbums(albums, true)
            updateLibraryCatalog(view) { albumFavorites.catalog(it, albums, provider) }
        } else updateLibraryCatalog(view) { favorites.catalog(it, ordered, provider) }
        if (!favorites.invalidated) overlayFavoriteMetadata(view)
    }

    /** Overlay each metadata copy independently so marker-only detail data cannot erase rich dates. */
    private fun overlayFavoriteMetadata(view: NaviampLibraryView) {
        val home = mediaRegistry.home
        val search = mediaRegistry.search
        when (view) {
            NaviampLibraryView.Artists -> {
                fun overlay(item: Artist) = overlayFavorite(artistFavorites, item)
                mediaRegistry.artistDetails?.let { details -> mediaRegistry.updateArtist(
                    details.copy(artist = overlay(details.artist)), mediaRegistry.artistPopularTracks,
                    mediaRegistry.artistSimilarArtists, mediaRegistry.artistAppearanceAlbums, mediaRegistry.artistAppearanceTracks) }
                mediaRegistry.updateHome(home.copy(artists = home.artists.map(::overlay),
                    favoriteArtists = home.favoriteArtists.map(::overlay)), mediaRegistry.sonicRows)
                mediaRegistry.updateSearch(search.copy(artists = search.artists.map(::overlay)))
            }
            NaviampLibraryView.Albums -> {
                fun overlay(item: Album) = overlayFavorite(albumFavorites, item)
                mediaRegistry.albumDetails?.let { mediaRegistry.updateAlbum(it.copy(album = overlay(it.album))) }
                mediaRegistry.artistDetails?.let { details -> mediaRegistry.updateArtist(
                    details.copy(albums = details.albums.map(::overlay)), mediaRegistry.artistPopularTracks,
                    mediaRegistry.artistSimilarArtists, mediaRegistry.artistAppearanceAlbums.map(::overlay), mediaRegistry.artistAppearanceTracks) }
                mediaRegistry.updateHome(home.copy(recentlyAddedAlbums = home.recentlyAddedAlbums.map(::overlay),
                    mixAlbums = home.mixAlbums.map(::overlay), recentAlbums = home.recentAlbums.map(::overlay),
                    frequentAlbums = home.frequentAlbums.map(::overlay), randomAlbums = home.randomAlbums.map(::overlay),
                    genreSpotlightAlbums = home.genreSpotlightAlbums.map(::overlay), decadeAlbums = home.decadeAlbums.map(::overlay)), mediaRegistry.sonicRows)
                mediaRegistry.updateSearch(search.copy(albums = search.albums.map(::overlay)))
            }
            NaviampLibraryView.Songs -> {
                fun overlay(item: Track) = overlayFavorite(trackFavorites, item)
                mediaRegistry.albumDetails?.let { mediaRegistry.updateAlbum(it.copy(tracks = it.tracks.map(::overlay))) }
                mediaRegistry.updateArtist(mediaRegistry.artistDetails, mediaRegistry.artistPopularTracks.map(::overlay),
                    mediaRegistry.artistSimilarArtists, mediaRegistry.artistAppearanceAlbums, mediaRegistry.artistAppearanceTracks.map(::overlay))
                mediaRegistry.selectedPlaylist?.let { mediaRegistry.updateSelectedPlaylist(it, mediaRegistry.selectedPlaylistTracks.map(::overlay)) }
                mediaRegistry.updateHome(home.copy(recentlyPlayedTracks = home.recentlyPlayedTracks.map(::overlay)),
                    mediaRegistry.sonicRows.copy(rows = mediaRegistry.sonicRows.rows.map { it.copy(tracks = it.tracks.map(::overlay)) }))
                mediaRegistry.updateSearch(search.copy(tracks = search.tracks.map(::overlay)))
            }
        }
    }

    private suspend fun completeFavorites(view: NaviampLibraryView, forceRefresh: Boolean = false) =
        completeFavorites(favorites(view), forceRefresh)

    private suspend fun <T> completeFavorites(favorites: Favorites<T>, forceRefresh: Boolean) {
        val provider = providerSource.current() ?: return
        useFavoritesScope(provider)
        val library = stateStore.state.value.shell.library
        val view = favorites.view
        if (library.selectedView != view || (!library.favoritesOnly && !forceRefresh) || !supportsFavorites(view, provider)) return
        if (favorites.loadingGeneration == favorites.generation) return
        val scope = favoritesScope(provider)
        val token = favorites.generation
        val load = libraryLoads.getValue(view)
        val generation = load.generation
        val query = library.catalog(view).query
        fun current(): Boolean {
            val active = stateStore.state.value.shell.library
            return token == favorites.generation && generation == load.generation && providerSource.isCurrent(provider) &&
                favoritesScope(provider) == scope && active.selectedView == view && active.favoritesOnly == library.favoritesOnly &&
                active.catalog(view).query == query
        }
        favorites.loadingGeneration = token
        updateLibraryCatalog(view) { it.copy(favoritesLoading = true, favoritesFailed = false,
            resultsQuery = if (library.favoritesOnly) query.takeIf(String::isNotBlank) else it.resultsQuery) }
        if (forceRefresh) {
            favorites.members = null
            if (library.favoritesOnly) favorites.mutations.clear()
            favorites.searchComplete = false
        }
        try {
            if (favorites.members == null) {
                val members = loadFavoriteMembers(favorites, provider, ::current) ?: return
                if (!current()) return
                favorites.members = members.associateByTo(linkedMapOf(), favorites.id)
                favorites.invalidated = false
                favorites.mutations.values.forEach { item ->
                    if (favorites.timestamp(item) == null) favorites.members?.remove(favorites.id(item))
                    else favorites.members?.set(favorites.id(item), item)
                }
            }
            if (query.isNotBlank() && library.favoritesOnly && (forceRefresh || !favorites.searchComplete || favorites.searchQuery != query)) {
                val matches = collectCompletePages(favorites, ::current) { request ->
                    favorites.searchPage(provider, query.trim(), request)
                } ?: return
                if (!current()) return
                favorites.searchItems = matches
                favorites.searchQuery = query
                favorites.searchComplete = true
            }
            if (!current()) return
            reprojectFavorites(favorites, provider)
            stateStore.state.value.shell.library.catalog(view).pendingJump?.let(::publishLibraryJump)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            if (current()) {
                if (favorites.members == null) {
                    // This tombstone survives later snapshot restoration and ordinary provider pages.
                    favorites.invalidated = true
                    reprojectFavorites(favorites, provider)
                }
                updateLibraryCatalog(view) { it.copy(favoritesFailed = true) }
            }
        } finally {
            if (current()) {
                favorites.loadingGeneration = null
                updateLibraryCatalog(view) { it.copy(pendingJump = null, searching = false,
                    syncStatus = NaviampLibrarySyncStatusUi(), favoritesLoading = false) }
            }
        }
    }

    private suspend fun <T> loadFavoriteMembers(favorites: Favorites<T>, provider: MediaProvider, current: () -> Boolean): List<T>? {
        val members = collectCompletePages(favorites, current) { favorites.favoritePage(provider, it) }
        if (!current()) return null
        if (members != null) return members.map {
            if (favorites.timestamp(it) == null) favorites.withTimestamp(it, "") else it
        }
        // Only a null optional endpoint permits fallback, never an exception or partial failure.
        return collectCompletePages(favorites, current) { favorites.browsePage(provider, it) }
            ?.filter { favorites.timestamp(it) != null }
    }

    private suspend fun <T> collectCompletePages(
        favorites: Favorites<T>,
        current: () -> Boolean,
        page: suspend (MediaPageRequest) -> MediaPage<T>?,
    ): List<T>? {
        val items = linkedMapOf<String, T>()
        val requests = mutableSetOf<MediaPageRequest>()
        val tokens = mutableSetOf<String>()
        var request: MediaPageRequest? = MediaPageRequest(limit = libraryPageSize)
        while (request != null) {
            if (!current()) return null
            check(requests.add(request)) { "Provider favorite paging did not advance." }
            val result = page(request) ?: return null
            if (!current()) return null
            require(result.offset == request.offset && result.limit == request.limit && result.items.size <= request.limit) {
                "Provider page metadata must match the request."
            }
            val before = items.size
            result.items.forEach { items[favorites.id(it)] = it }
            if (result.hasMore) {
                check(items.size > before) { "Provider paging returned a nonadvancing continuing page." }
                result.nextContinuationToken?.let { check(tokens.add(it)) { "Provider continuation token cycle." } }
            }
            val next = result.nextRequest
            check(next == null || next.offset > request.offset) { "Provider page offset did not advance." }
            request = next
        }
        return items.values.toList()
    }

    private fun publishLibraryArtists(artists: List<Artist>, provider: MediaProvider) {
        useFavoritesScope(provider)
        val projected = projectFavorites(artistFavorites, artists, mergeFavorites = true)
        mediaRegistry.updateLibraryArtists(projected, replace = true)
        updateLibraryCatalog(NaviampLibraryView.Artists) {
            it.copy(items = artistItems(projected, provider), resultsQuery = null, syncStatus = NaviampLibrarySyncStatusUi())
        }
    }

    private suspend fun loadMoreLibrary() {
        val view = stateStore.state.value.shell.library.selectedView
        if (stateStore.state.value.shell.library.favoritesOnly) return
        if (view == NaviampLibraryView.Albums && albumIndex != null &&
            stateStore.state.value.shell.library.albums.query.isBlank()) return
        val load = libraryLoads.getValue(view)
        val request = load.nextRequest ?: return
        val generation = load.generation
        if (load.loadingGeneration == generation) return
        loadLibraryPage(view, request, replace = request.offset == 0, generation = generation)
    }

    /** Query lookups never refresh the index, genres or the unfiltered browse catalog. */
    private suspend fun searchLibrary(query: String) {
        val library = stateStore.state.value.shell.library
        val view = library.selectedView
        if (query.isBlank() || library.catalog(view).query != query) return
        if (library.favoritesOnly) {
            completeFavorites(view)
            return
        }
        val load = libraryLoads.getValue(view)
        if (load.loadingGeneration == load.generation) return
        loadLibraryPage(view, MediaPageRequest(limit = libraryPageSize), replace = true,
            generation = load.generation)
    }

    private suspend fun loadLibraryPage(
        view: NaviampLibraryView,
        request: MediaPageRequest,
        replace: Boolean,
        generation: Long,
        queryOverride: String? = null,
    ): Boolean {
        val provider = providerSource.current()
        if (provider == null) {
            publishLibraryStatus(view, SearchDisconnectedStatus, loading = false)
            return false
        }
        val load = libraryLoads.getValue(view)
        if (generation != load.generation || stateStore.state.value.shell.library.favoritesOnly) return false
        load.scope = favoritesScope(provider)
        val sourceKey = provider.id.value to provider.cacheNamespace
        val query = queryOverride ?: stateStore.state.value.shell.library.catalog(view).query
        load.loadingGeneration = generation
        if (query.isBlank()) {
            publishLibraryStatus(view, "Loading library...", loading = true)
        } else {
            updateLibraryCatalog(view) { it.copy(searching = true, syncStatus = NaviampLibrarySyncStatusUi()) }
        }
        val result = runCatching {
            when (view) {
                NaviampLibraryView.Artists -> {
                    val page = if (query.isBlank()) {
                        provider.artistsPage(request)
                    } else {
                        provider.searchArtistsPage(query.trim(), request)
                    }
                    if (!isCurrentLibraryLoad(load, generation, sourceKey)) return@runCatching false to null
                    useFavoritesScope(provider)
                    val incoming = if (replace) page.items else
                        (mediaRegistry.libraryArtists + page.items).distinctBy(Artist::id)
                    val artists = projectFavorites(artistFavorites, incoming,
                        mergeFavorites = query.isBlank() && !page.hasMore)
                    mediaRegistry.updateLibraryArtists(artists, replace = true)
                    updateLibraryCatalog(view) { current ->
                        current.copy(items = artistItems(artists, provider), resultsQuery = query.takeIf { it.isNotBlank() },
                            searching = false, syncStatus = NaviampLibrarySyncStatusUi())
                    }
                    true to page.nextRequest
                }
                NaviampLibraryView.Albums -> {
                    val page = if (query.isBlank()) {
                        provider.albumsPage(request)
                    } else {
                        provider.searchAlbumsPage(query.trim(), request)
                    }
                    if (!isCurrentLibraryLoad(load, generation, sourceKey)) return@runCatching false to null
                    val incoming = if (replace) page.items else
                        (mediaRegistry.libraryAlbums + page.items).distinctBy { it.id }
                    val albums = projectFavorites(albumFavorites, incoming, mergeFavorites = query.isBlank() && !page.hasMore)
                    mediaRegistry.updateLibraryAlbums(albums, true)
                    updateLibraryCatalog(view) { current ->
                        albumFavorites.catalog(current, albums, provider).copy(resultsQuery = query.takeIf { it.isNotBlank() },
                            searching = false, syncStatus = NaviampLibrarySyncStatusUi())
                    }
                    true to page.nextRequest
                }
                NaviampLibraryView.Songs -> {
                    val page = if (query.isBlank()) {
                        provider.tracksPage(request)
                    } else {
                        provider.searchTracksPage(query.trim(), request)
                    }
                    if (!isCurrentLibraryLoad(load, generation, sourceKey)) return@runCatching false to null
                    val merged = if (replace) {
                        page.items
                    } else {
                        (mediaRegistry.libraryTracks + page.items).distinctBy { it.id }
                    }
                    val projected = projectFavorites(trackFavorites, merged, mergeFavorites = query.isBlank() && !page.hasMore)
                    val ordered = if (page.alphabeticallySortedByTitle) {
                        projected
                    } else {
                        projected.sortedWith(
                            compareBy<app.naviamp.domain.Track>(
                                { it.title.lowercase() },
                                { it.title },
                                { it.id.value },
                            ),
                        )
                    }
                    mediaRegistry.updateLibraryTracks(ordered, replace = true)
                    val mapped = ordered.map { track ->
                        track.toSharedTrackRowUi(coverArtUrl = { id -> id?.let { provider.coverArtUrl(it) } })
                    }
                    updateLibraryCatalog(view) { current ->
                        current.copy(tracks = mapped, resultsQuery = query.takeIf { it.isNotBlank() },
                            searching = false, syncStatus = NaviampLibrarySyncStatusUi())
                    }
                    true to page.nextRequest
                }
            }
        }.onSuccess { (accepted, nextRequest) ->
            if (accepted && view == NaviampLibraryView.Artists && replace && query.isBlank()) {
                // Keep the browsable library available when a provider's optional genre endpoint fails.
                runCatching { libraryGenreRefresh.refresh() }
            }
            if (accepted && isCurrentLibraryLoad(load, generation, sourceKey)) {
                load.nextRequest = nextRequest
                load.loadedSource = sourceKey
            }
        }.onFailure { cause ->
            if (cause is CancellationException) {
                if (isCurrentLibraryLoad(load, generation, sourceKey)) {
                    load.loadingGeneration = null
                    publishLibraryStatus(view, null, loading = false)
                }
                throw cause
            }
            if (isCurrentLibraryLoad(load, generation, sourceKey)) {
                publishLibraryStatus(view, cause.message ?: "Could not load library.", loading = false)
            }
        }
        if (generation == load.generation) {
            load.loadingGeneration = null
            val currentStatus = stateStore.state.value.shell.library.catalog(view).syncStatus.message
            if (currentStatus == "Loading library...") publishLibraryStatus(view, null, loading = false)
        }
        return result.getOrNull()?.first == true && isCurrentLibraryLoad(load, generation, sourceKey)
    }

    private fun updateSearchQuery(query: String) {
        updateSearchState { it.copy(query = query) }
    }

    private fun clearSearch() {
        searchGeneration += 1
        mediaRegistry.updateSearch(MediaSearchResults())
        updateSearchState { NaviampSearchScreenUi() }
    }

    private fun publishSearch(
        results: MediaSearchResults,
        status: String?,
        searching: Boolean,
        provider: MediaProvider?,
    ) {
        mediaRegistry.updateSearch(results)
        val mapped = provider?.let { active ->
            results.toSharedSearchResultsUi(
                coverArtUrl = { id -> id?.let(active::coverArtUrl) },
                canFavoriteArtists = active.capabilities.supportsArtistFavorites,
                canFavoriteAlbums = active.capabilities.supportsAlbumFavorites,
            )
        } ?: SharedSearchResultsUi()
        updateSearchState { current ->
            current.copy(results = mapped, status = status, searching = searching)
        }
    }

    private fun updateSearchState(transform: (NaviampSearchScreenUi) -> NaviampSearchScreenUi) {
        stateStore.updateShell { shell -> shell.copy(search = transform(shell.search)) }
    }

    private fun updateLibraryQuery(query: String) {
        val provider = providerSource.current()
        useFavoritesScope(provider)
        val library = stateStore.state.value.shell.library
        val view = library.selectedView
        val catalog = library.catalog(view)
        if (catalog.query == query) return
        if (catalog.query.isBlank() && query.isBlank() && !library.favoritesOnly) {
            updateLibraryCatalog(view) { it.copy(query = query) }
            return
        }
        favorites(view).cancel()
        val load = libraryLoads.getValue(view)
        val sourceKey = provider?.let { it.id.value to it.cacheNamespace }
        if (catalog.query.isBlank() && query.isNotBlank()) {
            load.browseSnapshot = if (library.favoritesOnly) load.ordinarySnapshot else captureBrowseSnapshot(view)
        }
        load.generation++
        load.loadingGeneration = null
        val snapshot = if (query.isBlank()) load.browseSnapshot?.takeIf { it.sourceKey == sourceKey } else null
        if (query.isBlank()) load.browseSnapshot = null
        if (library.favoritesOnly) {
            val retained = snapshot ?: LibraryBrowseSnapshot(
                NaviampLibraryCatalogUi(query = query, supportsFavorites = supportsFavorites(view, provider),
                    albumSortOrder = currentAlbumSortOrder()),
                MediaPageRequest(limit = libraryPageSize), sourceKey, null, MediaSearchResults(),
            )
            load.ordinarySnapshot = retained.copy(catalog = retained.catalog.copy(query = query))
            load.nextRequest = retained.nextRequest
            load.loadedSource = retained.loadedSource
            updateLibraryCatalog(view) { it.copy(query = query, resultsQuery = query.takeIf(String::isNotBlank),
                pendingJump = null, jumpFailed = false, searching = false, syncStatus = NaviampLibrarySyncStatusUi(),
                favoritesLoading = supportsFavorites(view, provider), favoritesFailed = false) }
            if (provider != null) reprojectFavorites(view, provider)
            return
        }
        if (snapshot != null) {
            restoreSnapshot(view, snapshot.copy(catalog = snapshot.catalog.copy(query = query)))
            if (provider != null) reprojectFavorites(view, provider)
            return
        }
        load.nextRequest = MediaPageRequest(limit = libraryPageSize)
        load.loadedSource = null
        updateLibraryCatalog(view) { it.copy(query = query, pendingJump = null, jumpFailed = false,
            searching = query.isNotBlank(), syncStatus = NaviampLibrarySyncStatusUi()) }
    }

    private fun captureSnapshot(view: NaviampLibraryView): LibraryBrowseSnapshot {
        val load = libraryLoads.getValue(view)
        return LibraryBrowseSnapshot(
            catalog = stateStore.state.value.shell.library.catalog(view).copy(pendingJump = null,
                searching = false, syncStatus = NaviampLibrarySyncStatusUi()),
            nextRequest = load.nextRequest,
            sourceKey = providerSource.current()?.let { it.id.value to it.cacheNamespace },
            loadedSource = load.loadedSource,
            media = when (view) {
                NaviampLibraryView.Artists -> MediaSearchResults(artists = mediaRegistry.libraryArtists)
                NaviampLibraryView.Albums -> MediaSearchResults(albums = mediaRegistry.libraryAlbums)
                NaviampLibraryView.Songs -> MediaSearchResults(tracks = mediaRegistry.libraryTracks)
            },
        )
    }

    private fun captureBrowseSnapshot(view: NaviampLibraryView): LibraryBrowseSnapshot = captureSnapshot(view).let {
        it.copy(catalog = it.catalog.copy(query = "", resultsQuery = null))
    }

    private fun clearLibraryMedia(view: NaviampLibraryView) {
        when (view) {
            NaviampLibraryView.Artists -> mediaRegistry.updateLibraryArtists(emptyList(), true)
            NaviampLibraryView.Albums -> mediaRegistry.updateLibraryAlbums(emptyList(), true)
            NaviampLibraryView.Songs -> mediaRegistry.updateLibraryTracks(emptyList(), true)
        }
    }

    private fun restoreSnapshot(view: NaviampLibraryView, snapshot: LibraryBrowseSnapshot, publishCatalog: Boolean = true) {
        val load = libraryLoads.getValue(view)
        load.nextRequest = snapshot.nextRequest
        load.loadedSource = snapshot.loadedSource
        when (view) {
            NaviampLibraryView.Artists -> mediaRegistry.updateLibraryArtists(snapshot.media.artists, true)
            NaviampLibraryView.Albums -> mediaRegistry.updateLibraryAlbums(snapshot.media.albums, true)
            NaviampLibraryView.Songs -> mediaRegistry.updateLibraryTracks(snapshot.media.tracks, true)
        }
        if (publishCatalog) updateLibraryCatalog(view) { snapshot.catalog.copy(refreshGeneration = it.refreshGeneration,
            favoritesLoading = false, favoritesFailed = false,
            albumSortOrder = if (view == NaviampLibraryView.Albums) currentAlbumSortOrder() else snapshot.catalog.albumSortOrder) }
    }

    private fun selectLibraryView(view: NaviampLibraryView) {
        useFavoritesScope(providerSource.current())
        // The global heart remains selected across tabs; only the departing tab loses its work owner.
        val previous = stateStore.state.value.shell.library.selectedView
        if (previous == view) return
        favorites(previous).cancel()
        val load = libraryLoads.getValue(previous)
        if (stateStore.state.value.shell.library.catalog(previous).searching) load.loadedSource = null
        load.generation++
        load.loadingGeneration = null
        stateStore.updateShell { shell ->
            fun catalog(kind: NaviampLibraryView): NaviampLibraryCatalogUi {
                val current = shell.library.catalog(kind)
                return when (kind) {
                    previous -> current.copy(pendingJump = null, searching = false, favoritesLoading = false)
                    view -> current.copy(favoritesLoading = shell.library.favoritesOnly && current.supportsFavorites,
                        favoritesFailed = false)
                    else -> current
                }
            }
            shell.copy(library = shell.library.copy(selectedView = view,
                artists = catalog(NaviampLibraryView.Artists), albums = catalog(NaviampLibraryView.Albums),
                songs = catalog(NaviampLibraryView.Songs)))
        }
    }

    private fun currentAlbumSortOrder(): LibraryAlbumSortOrder =
        stateStore.state.value.shell.general.interfaceSettings.libraryAlbumSortOrder

    private fun changeAlbumSortOrder(order: LibraryAlbumSortOrder) {
        if (currentAlbumSortOrder() == order) return
        val current = stateStore.state.value.shell.general.interfaceSettings
        interfaceSettingsChanged(current.copy(libraryAlbumSortOrder = order))
        onLibraryAlbumSortOrderChanged(order)
    }

    fun interfaceSettingsChanged(settings: InterfaceSettings) {
        stateStore.updateShell { shell ->
            shell.copy(
                general = shell.general.copy(interfaceSettings = settings),
                library = shell.library.copy(
                    albums = shell.library.albums.copy(albumSortOrder = settings.libraryAlbumSortOrder),
                ),
            )
        }
        val provider = providerSource.current() ?: return
        useFavoritesScope(provider)
        reprojectFavorites(NaviampLibraryView.Albums, provider)
    }

    private fun publishLibraryStatus(view: NaviampLibraryView, message: String?, loading: Boolean) {
        updateLibraryCatalog(view) { catalog ->
            catalog.copy(searching = false, syncStatus = NaviampLibrarySyncStatusUi(message = message, isSyncing = loading))
        }
    }

    private fun updateLibraryCatalog(
        view: NaviampLibraryView,
        transform: (NaviampLibraryCatalogUi) -> NaviampLibraryCatalogUi,
    ) {
        stateStore.updateShell { shell ->
            val library = shell.library
            shell.copy(
                library = when (view) {
                    NaviampLibraryView.Artists -> library.copy(artists = transform(library.artists))
                    NaviampLibraryView.Albums -> library.copy(albums = transform(library.albums))
                    NaviampLibraryView.Songs -> library.copy(songs = transform(library.songs))
                },
            )
        }
    }

    private fun isCurrentLibraryLoad(
        load: LibraryLoadState,
        generation: Long,
        sourceKey: Pair<String, String>,
    ): Boolean {
        val currentProvider = providerSource.current()
        return generation == load.generation &&
            currentProvider?.let { it.id.value to it.cacheNamespace } == sourceKey &&
            (load.scope == null || currentProvider?.let(::favoritesScope) == load.scope)
    }

    private fun publishLibraryJump(letter: Char) {
        stateStore.update { state ->
            state.copy(
                shell = state.shell.copy(
                    library = state.shell.library.copy(
                        jumpRequest = app.naviamp.ui.NaviampLibraryJumpUi(
                            state.shell.library.selectedView,
                            letter.uppercaseChar(),
                            ++jumpGeneration,
                        ),
                    ),
                ),
            )
        }
    }

    private suspend fun jumpToLetter(letter: Char) {
        val normalized = letter.uppercaseChar()
        val library = stateStore.state.value.shell.library
        val view = library.selectedView
        val query = library.catalog(view).query
        if (view == NaviampLibraryView.Albums && currentAlbumSortOrder() != LibraryAlbumSortOrder.Title) return
        if (library.favoritesOnly) {
            if (!library.catalog(view).supportsFavorites) return
            updateLibraryCatalog(view) { it.copy(pendingJump = normalized, jumpFailed = false) }
            completeFavorites(view)
            return
        }
        if (view == NaviampLibraryView.Albums && albumIndex != null && query.isBlank()) {
            if (currentAlbumSortOrder() != LibraryAlbumSortOrder.Title) return
            val load = libraryLoads.getValue(view)
            if (load.loadingGeneration == load.generation && !load.completeAlbumCatalog) {
                updateLibraryCatalog(view) { it.copy(pendingJump = normalized) }
            } else {
                publishLibraryJump(normalized)
            }
            return
        }
        val provider = providerSource.current() ?: return
        val sourceKey = provider.id.value to provider.cacheNamespace
        val load = libraryLoads.getValue(view)
        load.scope = favoritesScope(provider)
        if (library.catalog(view).pendingJump == normalized) return
        val generation = ++load.generation
        updateLibraryCatalog(view) { it.copy(pendingJump = normalized, jumpFailed = false) }
        fun current() = isCurrentLibraryLoad(load, generation, sourceKey) &&
            stateStore.state.value.shell.library.selectedView == view &&
            stateStore.state.value.shell.library.catalog(view).query == query
        try {
            if (query.isBlank()) {
                val kind = when (view) {
                    NaviampLibraryView.Artists -> null
                    NaviampLibraryView.Albums -> AlphabeticalLibraryKind.Albums
                    NaviampLibraryView.Songs -> AlphabeticalLibraryKind.Tracks
                }
                val offset = if (normalized != '#' && kind != null) {
                    provider.alphabeticalLibraryOffset(kind, normalized)
                } else null
                if (!current()) return
                val first = MediaPageRequest(offset = offset ?: 0, limit = libraryPageSize)
                load.nextRequest = first
                if (!loadLibraryPage(view, first, replace = true, generation = generation) || !current()) return
                if (offset == null && normalized != '#') {
                    var remainingPages = 1_000
                    while (current() && remainingPages-- > 0) {
                        val catalog = stateStore.state.value.shell.library.catalog(view)
                        // Providers may ignore articles or use sort tags ("The Aquabats" sorts
                        // under A). A displayed title beyond the requested letter does not prove
                        // that its server-sorted range has been reached.
                        val titles = if (view == NaviampLibraryView.Songs) {
                            catalog.tracks.map { it.title }
                        } else {
                            catalog.items.map { it.title }
                        }
                        if (titles.any { app.naviamp.domain.library.libraryTitleLetter(it) == normalized }) break
                        val next = load.nextRequest ?: break
                        if (!loadLibraryPage(view, next, replace = false, generation = generation)) return
                        if (load.nextRequest == next) return
                    }
                }
            }
            if (current()) publishLibraryJump(normalized)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            if (current()) updateLibraryCatalog(view) { it.copy(jumpFailed = true) }
        } finally {
            if (generation == load.generation) updateLibraryCatalog(view) { it.copy(pendingJump = null) }
        }
    }
    private inline fun handled(action: () -> Unit): NaviampCoreImmediateCommandResult {
        action()
        return NaviampCoreImmediateCommandResult.Handled()
    }
}

internal suspend fun MediaProvider.loadCompleteArtistLibrary(
    maximumArtists: Int = MaximumCachedLibraryArtists,
    pageSize: Int = MaximumMediaPageSize,
): List<Artist> {
    require(maximumArtists > 0) { "The maximum artist count must be positive." }
    require(pageSize in 1..MaximumMediaPageSize) { "The artist page size is invalid." }
    val artists = mutableListOf<Artist>()
    val seenRequests = mutableSetOf<MediaPageRequest>()
    var request: MediaPageRequest? = MediaPageRequest(
        limit = minOf(pageSize, maximumArtists),
    )
    while (request != null) {
        check(seenRequests.add(request)) { "Provider artist paging did not advance." }
        val page = artistsPage(request)
        require(page.offset == request.offset && page.limit == request.limit) {
            "Provider artist page metadata must match the requested offset and limit."
        }
        require(page.items.size <= request.limit) {
            "Provider artist page returned more items than requested."
        }
        check(artists.size + page.items.size <= maximumArtists) {
            "The artist library exceeds the supported refresh limit of $maximumArtists."
        }
        artists += page.items
        request = page.nextRequest
        if (request != null) {
            check(page.items.isNotEmpty()) { "Provider artist paging returned an empty continuing page." }
            val remaining = maximumArtists - artists.size
            check(remaining > 0) {
                "The artist library exceeds the supported refresh limit of $maximumArtists."
            }
            request = request.copy(limit = minOf(request.limit, remaining))
        }
    }
    return artists.distinctBy { it.id }
}

private const val MaximumCachedLibraryArtists = 100_000
