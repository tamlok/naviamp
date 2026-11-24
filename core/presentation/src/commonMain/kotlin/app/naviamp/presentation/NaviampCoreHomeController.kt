package app.naviamp.presentation

import app.naviamp.ui.toSharedRecommendationsSection
import app.naviamp.domain.radio.recentRadioStreamsForSource
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.domain.home.HomeSection
import app.naviamp.domain.home.retainFailedSections
import app.naviamp.domain.settings.HomeSectionIds
import app.naviamp.domain.sonichome.SonicHomeDiscoveryRowId
import app.naviamp.ui.SharedHomeCollectionItemAction

import app.naviamp.domain.InternetRadioStation
import app.naviamp.domain.Track
import app.naviamp.domain.cache.ProviderResponseService
import app.naviamp.domain.cache.LocalLibraryIndexRepository
import app.naviamp.domain.home.HomeAlbumYear
import app.naviamp.domain.home.HomeContentLoadRequest
import app.naviamp.domain.home.HomeDate
import app.naviamp.domain.home.HomeLibraryRepository
import app.naviamp.domain.home.homeLoadFailureStatus
import app.naviamp.domain.home.loadHomeContent
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.radio.sessionSubtitle
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.homeSectionPresentation
import app.naviamp.domain.settings.effectiveSonicSimilarityEnabled
import app.naviamp.domain.settings.resolvedHomeSectionOrder
import app.naviamp.domain.sonichome.SonicHomeDiscoveryService
import app.naviamp.domain.sonichome.SonicHomeDiscoveryRows
import app.naviamp.ui.SharedRoute
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.toSharedHomeUi
import app.naviamp.ui.SharedHomeCollectionPageUi
import app.naviamp.ui.withRecentRadioStreams
import app.naviamp.domain.provider.*
import app.naviamp.domain.provider.ProviderDiscoveryFeed
import app.naviamp.domain.provider.ProviderDiscoveryStatus
import app.naviamp.ui.SharedProviderDiscoveryUi
import app.naviamp.ui.SharedProviderDiscoveryArtistUi
import app.naviamp.domain.provider.ProviderDiscoveryArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.naviamp.ui.withRecentlyPlayedTracks
import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.home.HomePlaybackActivity
import app.naviamp.domain.radio.GeneratedRadioRepository
import app.naviamp.domain.radio.MemoryGeneratedRadioRepository
import app.naviamp.domain.radio.GeneratedRadioStation

fun interface NaviampCoreHomeDateSource {
    fun current(): HomeDate
}

data class NaviampCoreHomeSupplement(
    val sourceId: String? = null,
    val recentRadioStreams: List<RecentRadioStream> = emptyList(),
    val recentInternetRadioStations: List<InternetRadioStation> = emptyList(),
    val playlistTracksById: Map<String, List<Track>> = emptyMap(),
    val keepDownloadedPlaylistIds: Set<String> = emptySet(),
    val sonicDiscoveryRows: SonicHomeDiscoveryRows = SonicHomeDiscoveryRows(),
)

fun interface NaviampCoreHomeSupplementSource {
    fun current(): NaviampCoreHomeSupplement
}

fun interface NaviampCoreSonicHomeDiscoverySource {
    suspend fun load(provider: MediaProvider, sourceId: String): SonicHomeDiscoveryRows
}

/** Builds Sonic Home discovery from portable provider and library-index contracts. */
fun naviampCoreSonicHomeDiscoverySource(
    libraryIndex: LocalLibraryIndexRepository,
): NaviampCoreSonicHomeDiscoverySource = NaviampCoreSonicHomeDiscoverySource { provider, sourceId ->
    if (!provider.capabilities.supportsSonicSimilarity) return@NaviampCoreSonicHomeDiscoverySource SonicHomeDiscoveryRows()
    try {
        val library = libraryIndex.librarySnapshot(sourceId, limit = SonicHomeDiscoveryLibrarySampleLimit).tracks
        val recent = libraryIndex.recentlyPlayedLibraryTracks(sourceId, limit = SonicHomeDiscoveryRecentTrackLimit)
        var favoritesFailed = false
        val favorites = try {
            provider.favoriteTracks(limit = SonicHomeDiscoveryFavoriteTrackLimit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { favoritesFailed = true; emptyList() }
        val rows = SonicHomeDiscoveryService(provider).loadRows(library, recent, favorites)
        if (favoritesFailed && rows.row(SonicHomeDiscoveryRowId.SimilarToStarredTracks)?.tracks.isNullOrEmpty()) {
            rows.copy(failedRowIds = rows.failedRowIds + SonicHomeDiscoveryRowId.SimilarToStarredTracks)
        } else rows
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { SonicHomeDiscoveryRows(failedRowIds = SonicHomeDiscoveryRowId.entries.toSet()) }
}

/** Adapts the portable library index to Home without host-owned repository mapping. */
fun localLibraryHomeRepository(
    libraryIndex: LocalLibraryIndexRepository,
): HomeLibraryRepository = object : HomeLibraryRepository {
    override fun albumYears(sourceId: String): List<HomeAlbumYear> =
        libraryIndex.libraryAlbumYears(sourceId).map { year ->
            HomeAlbumYear(year = year.year, albumCount = year.albumCount)
        }

    override fun recentlyPlayedTracks(sourceId: String, limit: Long): List<Track> =
        libraryIndex.recentlyPlayedLibraryTracks(sourceId, limit)

    override fun recordPlayedTrack(sourceId: String, track: Track, playedAtEpochMillis: Long) =
        libraryIndex.recordLibraryTrackPlayed(sourceId, track, playedAtEpochMillis)

    override fun recentPlaybackHistory(sourceId: String, limit: Int): List<HomePlaybackActivity> =
        libraryIndex.recentPlaybackHistory(sourceId, limit)

    override fun reconcileFavoriteArtists(
        sourceId: String,
        artists: List<app.naviamp.domain.Artist>,
        observedAtIso8601: String,
        complete: Boolean,
    ) = libraryIndex.reconcileFavoriteArtists(sourceId, artists, observedAtIso8601, complete)

    override fun locallyKnownFavoriteArtists(sourceId: String, limit: Long) =
        libraryIndex.locallyKnownFavoriteArtists(sourceId, limit)

    override fun favoriteArtistRadioLastPlayed(sourceId: String) =
        libraryIndex.favoriteArtistRadioLastPlayed(sourceId)

    override fun setArtistFavoriteActivity(
        sourceId: String,
        artist: app.naviamp.domain.Artist,
        favorite: Boolean,
        changedAtIso8601: String,
    ) = libraryIndex.setArtistFavoriteActivity(sourceId, artist, favorite, changedAtIso8601)

    override fun recordArtistRadioPlayed(
        sourceId: String,
        artist: app.naviamp.domain.Artist,
        playedAtIso8601: String,
    ) = libraryIndex.recordArtistRadioPlayed(sourceId, artist, playedAtIso8601)

    override fun recordTrackArtistRadioPlayedIfFavorite(
        sourceId: String,
        artistId: app.naviamp.domain.ArtistId,
        artistName: String,
        playedAtIso8601: String,
    ) = libraryIndex.recordTrackArtistRadioPlayedIfFavorite(sourceId, artistId, artistName, playedAtIso8601)
}

/** Owns Home loading, stale refresh rejection, mapping, status, and builder navigation. */
class NaviampCoreHomeController(
    private val stateStore: NaviampCoreStateStore,
    private val providerSource: NaviampCoreMediaProviderSource,
    private val navigationController: NaviampCoreNavigationController,
    private val dateSource: NaviampCoreHomeDateSource,
    private val supplementSource: NaviampCoreHomeSupplementSource =
        NaviampCoreHomeSupplementSource { NaviampCoreHomeSupplement() },
    private val providerResponseService: ProviderResponseService? = null,
    private val libraryRepository: HomeLibraryRepository? = null,
    private val sonicDiscoverySource: NaviampCoreSonicHomeDiscoverySource? = null,
    private val artistLimit: Int = 50,
    private val mediaRegistry: NaviampCoreMediaRegistry = NaviampCoreMediaRegistry(),
    private val observedAtIso8601: () -> String = { "" },
    private val externalUri: NaviampCoreExternalUriPort = NaviampCoreExternalUriPort {},
    private val playDiscoverySample: suspend (Track) -> Unit = {},
    private val stopDiscoverySample: () -> Unit = {},
    private val openLibraryArtist: suspend (app.naviamp.domain.Artist) -> Unit = {},
    private val openLibraryAlbum: suspend (app.naviamp.domain.Album) -> Unit = {},
    acquisitionRepository: app.naviamp.domain.provider.DiscoveryAcquisitionRepository = app.naviamp.domain.provider.MemoryDiscoveryAcquisitionRepository(),
    private val nowMillis: () -> Long = { 0L },
    private val generatedRadio: GeneratedRadioRepository = MemoryGeneratedRadioRepository(),
    private val generatedRadioSourceIdentity: () -> NaviampConnectSourceIdentity? = { null },
) : NaviampCoreCommandController {
    private val recentHistoryMutex = Mutex()
    private var recentHistoryRevision = 0L

    /** A real local play refreshes Home and external browsing without a provider/catalog reload. */
    suspend fun recordPlayedTrack(sourceId: String, track: Track, playedAtEpochMillis: Long): Unit = recentHistoryMutex.withLock {
        val repository = libraryRepository ?: return@withLock
        val provider = providerSource.current() ?: return@withLock
        if (stateStore.state.value.shell.connectionSettings.currentSourceId != sourceId) return@withLock
        val tracks = try {
            withContext(Dispatchers.Default) {
                repository.recordPlayedTrack(sourceId, track, playedAtEpochMillis)
                repository.recentlyPlayedTracks(sourceId, 12)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withLock }
        if (!providerSource.isCurrent(provider) ||
            stateStore.state.value.shell.connectionSettings.currentSourceId != sourceId) return@withLock
        recentHistoryRevision++
        mediaRegistry.updateHome(mediaRegistry.home.copy(recentlyPlayedTracks = tracks), mediaRegistry.sonicRows)
        stateStore.updateShell { shell ->
            val content = shell.home.content.withRecentlyPlayedTracks(tracks,
                coverArtUrl = { id -> id?.let(provider::coverArtUrl) },
                interfaceSettings = shell.general.interfaceSettings)
            val page = shell.home.collectionPage?.let { page ->
                if (page.section.id != app.naviamp.domain.settings.HomeSectionIds.RecentlyPlayed) page else
                    content.collectionSections.firstOrNull { it.id == page.section.id }?.let { page.copy(section = it) }
            }
            shell.copy(home = shell.home.copy(content = content, collectionPage = page))
        }
    }

    private var refreshGeneration = 0L
    private var discoveryFeedGeneration = 0L
    private var sourceGeneration = 0L
    private val scheduledRefreshes = ArrayDeque<Long>()
    private var recoveryScheduled = false
    private var refreshInFlight = false
    private var refreshFailed = false
    private var cachedHomeProvider: MediaProvider? = null
    private var cachedHomeSourceId: String? = null
    private var cachedHomeLibraryScope: String? = null
    private var cachedSavedStations: List<GeneratedRadioStation> = emptyList()
    private var discoveryFeedAccount: String? = null
    private var discoveryFeedSourceId: String? = null

    override fun recoverEmptyPage(page: NaviampNavigationPage): List<NaviampCoreCommand> {
        if (page is NaviampCoreDiscoveryPage) return discoveryController.recoverEmptyPage(page)
        if (providerSource.current() == null) return emptyList()
        val home = stateStore.state.value.shell.home
        if (recoveryScheduled || refreshInFlight || scheduledRefreshes.any { it == sourceGeneration } || home.refreshing || home.providerDiscovery?.loading == true) return emptyList()
        val sectionId = (page as? NaviampCoreHomeCollectionPage)?.sectionId
        val eligible = when {
            sectionId != null -> {
                val section = home.collectionPage?.section?.takeIf { it.id == sectionId } ?: return emptyList()
                section.items.none { it.action.isBrowsableHomeMedia() } &&
                    (refreshFailed || failedHomeSection(sectionId))
            }
            page is NaviampRoutePage && page.route == NaviampRoute.Home -> {
                val feed = home.providerDiscovery?.feed
                home.content.collectionSections.none { section -> section.visible && section.items.any { it.action.isBrowsableHomeMedia() } } &&
                    (feed == null || feed.recommendations.isEmpty() && feed.trending.isEmpty()) &&
                    (refreshFailed || mediaRegistry.home.failedSections.isNotEmpty() || mediaRegistry.sonicRows.failedRowIds.isNotEmpty() || feed?.status.isHomeFetchFailure())
            }
            else -> false
        }
        if (!eligible || cachedHomeProvider?.let { !providerSource.isCurrent(it) || cachedHomeSourceId != currentHomeSourceId() } == true) return emptyList()
        recoveryScheduled = true
        return listOf(NaviampCoreCommand.Home.Refresh)
    }

    private fun currentHomeSourceId(): String? =
        supplementSource.current().sourceId ?: stateStore.state.value.shell.connectionSettings.currentSourceId

    private fun failedHomeSection(id: String): Boolean {
        val failed = mediaRegistry.home.failedSections
        return when (id) {
            HomeSectionIds.FavoriteArtists -> HomeSection.FavoriteArtists in failed || HomeSection.FavoriteAlbums in failed
            HomeSectionIds.MixesForYou -> HomeSection.GeneratedMixes in failed
            HomeSectionIds.RandomAlbums -> HomeSection.RandomAlbums in failed
            HomeSectionIds.NavibeatMixes, HomeSectionIds.RecentPlaylists -> HomeSection.Playlists in failed
            HomeSectionIds.RecentlyAdded -> HomeSection.RecentlyAdded in failed
            HomeSectionIds.RecentlyPlayed -> HomeSection.RecentlyPlayed in failed
            HomeSectionIds.RecentAlbums -> HomeSection.RecentAlbums in failed
            HomeSectionIds.FrequentlyPlayedAlbums -> HomeSection.FrequentAlbums in failed
            HomeSectionIds.GenreSpotlight -> HomeSection.GenreSpotlight in failed
            HomeSectionIds.Decade -> HomeSection.Decade in failed
            else -> mediaRegistry.sonicRows.failedRowIds.any { it.value == id }
        }
    }
    private val discoveryController = NaviampCoreDiscoveryController(
        stateStore, providerSource, { supplementSource.current().sourceId }, externalUri,
        playDiscoverySample, stopDiscoverySample, openLibraryArtist,
        refreshFeed = { provider -> refreshProviderDiscovery(provider, refreshGeneration) },
        showPage = navigationController::openDiscoveryPage,
        leaveSurface = navigationController::closeDiscoveryPage,
        currentNavigationEpoch = { navigationController.epoch },
        openLibraryAlbum = openLibraryAlbum,
        acquisitionRepository = acquisitionRepository,
        nowMillis = nowMillis,
    )

    suspend fun maintainDiscoveryAcquisitions() = discoveryController.maintainAcquisitions()

    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult {
        val discoveryResult = discoveryController.dispatch(command)
        if (discoveryResult != NaviampCoreImmediateCommandResult.Unhandled) return discoveryResult
        val home = command as? NaviampCoreCommand.Home
            ?: return NaviampCoreImmediateCommandResult.Unhandled
        return when (home) {
            NaviampCoreCommand.Home.Refresh -> {
                scheduledRefreshes.addLast(sourceGeneration)
                NaviampCoreImmediateCommandResult.Deferred
            }
            is NaviampCoreCommand.Home.SelectMixBuilder -> {
                selectMixBuilder(home.item.id)
                NaviampCoreImmediateCommandResult.Handled()
            }
            is NaviampCoreCommand.Home.OpenCollection -> {
                openCollection(home.sectionId)
                NaviampCoreImmediateCommandResult.Handled()
            }
            NaviampCoreCommand.Home.CloseCollection -> {
                closeCollection()
                NaviampCoreImmediateCommandResult.Handled()
            }
            is NaviampCoreCommand.Home.SelectRecentRadio,
            is NaviampCoreCommand.Home.SelectInternetRadio,
            is NaviampCoreCommand.Home.SelectStation,
            is NaviampCoreCommand.Home.SonicTrackAction,
            is NaviampCoreCommand.Home.RecentTrackAction,
            -> NaviampCoreImmediateCommandResult.Unhandled
            else -> NaviampCoreImmediateCommandResult.Unhandled
        }
    }

    override suspend fun execute(command: NaviampCoreCommand): NaviampCoreCommandResult? {
        discoveryController.execute(command)?.let { return it }
        when (command) {
            NaviampCoreCommand.Home.Refresh -> {
                val scheduledGeneration = scheduledRefreshes.removeFirstOrNull() ?: sourceGeneration
                if (scheduledGeneration != sourceGeneration) return NaviampCoreCommandResult.Completed
                refresh(forceRefreshRecentlyAdded = true)
            }
            else -> return null
        }
        return NaviampCoreCommandResult.Completed
    }

    private suspend fun refresh(forceRefreshRecentlyAdded: Boolean = false) = coroutineScope {
        val generation = ++refreshGeneration
        recoveryScheduled = false
        refreshInFlight = true
        val historyRevision = recentHistoryRevision
        val radioSnapshot = mediaRegistry.home.recentRadioStreams
        val provider = providerSource.current()
        if (provider == null) {
            refreshInFlight = false
            stateStore.updateShell { it.copy(home = it.home.copy(providerDiscovery = null)) }
            publish(refreshing = false, status = "Connect to Navidrome to load Home.")
            return@coroutineScope
        }
        val supplement = supplementSource.current()
        val sourceId = supplement.sourceId ?: stateStore.state.value.shell.connectionSettings.currentSourceId
        val libraryScope = provider.cacheNamespace
        val sourceIdentity = generatedRadioSourceIdentity()
        val playbackSettings = stateStore.state.value.shell.playback.settings
        publish(refreshing = true, status = "Loading home...")
        launch { refreshProviderDiscovery(provider, generation) }
        try {
        runCatching {
            // Home and the Playlists page must use current IDs, including after a server upgrade.
            providerResponseService?.invalidatePlaylists(provider)
            val content = loadHomeContent(
                HomeContentLoadRequest(
                    provider = provider,
                    providerResponseService = providerResponseService,
                    libraryRepository = libraryRepository,
                    sourceId = sourceId,
                    date = dateSource.current(),
                    recentRadioStreams = supplement.recentRadioStreams,
                    recentInternetRadioStations = supplement.recentInternetRadioStations,
                    artistLimit = artistLimit,
                    forceRefreshRecentlyAdded = forceRefreshRecentlyAdded,
                    observedAtIso8601 = observedAtIso8601,
                    generatedRadio = generatedRadio,
                    generatedRadioSourceIdentity = sourceIdentity,
                    forceRefreshMixes = forceRefreshRecentlyAdded,
                    nowMillis = nowMillis,
                    sonicSimilarity = playbackSettings.effectiveSonicSimilarityEnabled(),
                    radioTuning = playbackSettings.radioTuning,
                    radioBatchSize = playbackSettings.radioMinimumTracks,
                    isCurrent = {
                        generation == refreshGeneration && providerSource.isCurrent(provider) &&
                            sourceId == currentHomeSourceId() && libraryScope == provider.cacheNamespace &&
                            sourceIdentity == generatedRadioSourceIdentity()
                    },
                ),
            )
            val sonicEnabled = stateStore.state.value.shell.playback.settings.effectiveSonicSimilarityEnabled() &&
                provider.capabilities.supportsSonicSimilarity
            val sonicRows = if (sonicEnabled && sourceId != null) {
                try { sonicDiscoverySource?.load(provider, sourceId) ?: SonicHomeDiscoveryRows() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SonicHomeDiscoveryRows(failedRowIds = SonicHomeDiscoveryRowId.entries.toSet()) }
            } else {
                SonicHomeDiscoveryRows()
            }
            content to sonicRows
        }.onSuccess { (loadedContent, loadedSonicRows) ->
            if (generation != refreshGeneration || !providerSource.isCurrent(provider) ||
                sourceId != currentHomeSourceId() || libraryScope != provider.cacheNamespace ||
                sourceIdentity != generatedRadioSourceIdentity()) return@onSuccess
            val sameSource = cachedHomeSourceId == sourceId && cachedHomeLibraryScope == libraryScope &&
                cachedHomeProvider?.let(providerSource::isCurrent) == true
            var content = if (sameSource) loadedContent.retainFailedSections(mediaRegistry.home) else loadedContent
            val sonicRows = if (sameSource) loadedSonicRows.retainFailedRows(mediaRegistry.sonicRows) else loadedSonicRows
            refreshFailed = false
            cachedHomeProvider = provider
            cachedHomeSourceId = sourceId
            cachedHomeLibraryScope = libraryScope
            // A network/sidecar refresh started before a play must not restore older history.
            if (recentHistoryRevision != historyRevision) {
                content = content.copy(recentlyPlayedTracks = mediaRegistry.home.recentlyPlayedTracks)
            }
            if (mediaRegistry.home.recentRadioStreams !== radioSnapshot) {
                content = content.copy(recentRadioStreams = mediaRegistry.home.recentRadioStreams)
            }
            mediaRegistry.updateHome(content, sonicRows)
            val sonicEnabled = stateStore.state.value.shell.playback.settings.effectiveSonicSimilarityEnabled() &&
                provider.capabilities.supportsSonicSimilarity
            stateStore.update { state ->
                val mappedContent = content.toSharedHomeUi(
                    coverArtUrl = { id -> id?.let(provider::coverArtUrl) },
                    playlistTracksById = supplement.playlistTracksById,
                    keepDownloadedPlaylistIds = supplement.keepDownloadedPlaylistIds,
                    sonicDiscoveryRows = sonicRows,
                    canFavoriteAlbums = provider.capabilities.supportsAlbumFavorites,
                    showSonicPathBuilder = sonicEnabled,
                    showSonicMixBuilder = sonicEnabled,
                    interfaceSettings = state.shell.general.interfaceSettings,
                    savedGeneratedStations = scopedSavedStations(provider, sourceId, sourceIdentity),
                )
                val refreshedPage = state.shell.home.collectionPage?.let { currentPage ->
                    mappedContent.collectionSections
                        .firstOrNull { it.id == currentPage.section.id }
                        ?.let { section -> currentPage.copy(section = section) }
                }
                state.copy(
                    shell = state.shell.copy(
                        home = state.shell.home.copy(
                            content = mappedContent,
                            refreshing = false,
                            collectionPage = refreshedPage,
                        ),
                    ),
                    overlays = state.overlays.copy(status = null),
                )
            }
        }.onFailure { cause ->
            if (cause is CancellationException) throw cause
            if (generation == refreshGeneration && providerSource.isCurrent(provider) && sourceId == currentHomeSourceId()) {
                refreshFailed = true
                publish(refreshing = false, status = homeLoadFailureStatus(cause))
            }
        }
        } finally {
            if (generation == refreshGeneration) {
                refreshInFlight = false
                stateStore.updateShell { it.copy(home = it.home.copy(refreshing = false)) }
            }
        }
    }

    private suspend fun refreshProviderDiscovery(provider: MediaProvider, generation: Long) {
        if (generation != refreshGeneration || !providerSource.isCurrentDiscovery(provider)) return
        val feedGeneration = ++discoveryFeedGeneration
        val discovery = provider.discovery
        if (discovery == null) {
            stateStore.updateShell { it.copy(home = it.home.copy(providerDiscovery = null)) }
            return
        }
        val sourceId = currentHomeSourceId()
        val sameAccount = discoveryFeedAccount == provider.discoveryAccountScope && discoveryFeedSourceId == sourceId
        discoveryFeedAccount = provider.discoveryAccountScope
        discoveryFeedSourceId = sourceId
        stateStore.updateShell { shell -> shell.copy(home = shell.home.copy(
            providerDiscovery = shell.home.providerDiscovery?.takeIf { sameAccount && it.providerId == provider.effectiveDiscoveryProviderId.value }?.copy(loading = true)
                ?: SharedProviderDiscoveryUi(provider.effectiveDiscoveryProviderId.value, loading = true, isCompanion = provider.effectiveDiscoveryProviderId != provider.id),
        )) }
        val feed = try {
            discovery.load()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                if (generation == refreshGeneration && feedGeneration == discoveryFeedGeneration && providerSource.isCurrentDiscovery(provider) && sourceId == currentHomeSourceId()) {
                    stateStore.updateShell { shell -> shell.copy(home = shell.home.copy(providerDiscovery = shell.home.providerDiscovery?.copy(loading = false))) }
                }
                throw error
            }
            ProviderDiscoveryFeed(ProviderDiscoveryStatus.Unavailable)
        }
        if (generation != refreshGeneration || feedGeneration != discoveryFeedGeneration || !providerSource.isCurrentDiscovery(provider) || sourceId != currentHomeSourceId()) return
        stateStore.updateShell { shell ->
            val current = shell.home.providerDiscovery ?: SharedProviderDiscoveryUi(provider.effectiveDiscoveryProviderId.value, isCompanion = provider.effectiveDiscoveryProviderId != provider.id)
            val merged = if (sameAccount && feed.status.isHomeFetchFailure() && feed.recommendations.isEmpty() && feed.trending.isEmpty()) {
                current.feed.copy(status = feed.status)
            } else feed
            shell.copy(home = shell.home.copy(providerDiscovery = current.copy(feed = merged, loading = false)))
        }
    }


    suspend fun refreshAfterCompanionChange() {
        discoveryController.resetForSourceChange()
        stateStore.updateShell { it.copy(home = it.home.copy(providerDiscovery = null)) }
        providerSource.current()?.let { refreshProviderDiscovery(it, refreshGeneration) }
    }

    suspend fun refreshAfterConnection() = refresh()

    internal fun generatedStationsChanged() {
        val provider = providerSource.current() ?: return
        val supplement = supplementSource.current()
        val sourceId = currentHomeSourceId()
        val identity = generatedRadioSourceIdentity()
        val library = try { generatedRadio.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        val current = library.stations.filter { it.belongsTo(sourceId, identity, provider.cacheNamespace) }.associateBy { it.id }
        val stations = current.values.filter { it.saved }
        cachedSavedStations = stations
        mediaRegistry.updateHome(mediaRegistry.home.copy(generatedMixes = mediaRegistry.home.generatedMixes
            .mapNotNull { current[it.id] }.filterNot { it.saved }), mediaRegistry.sonicRows)
        stateStore.updateShell { shell ->
            val sonicEnabled = shell.playback.settings.effectiveSonicSimilarityEnabled() && provider.capabilities.supportsSonicSimilarity
            val content = mediaRegistry.home.toSharedHomeUi(
                coverArtUrl = { id -> id?.let(provider::coverArtUrl) },
                playlistTracksById = supplement.playlistTracksById,
                keepDownloadedPlaylistIds = supplement.keepDownloadedPlaylistIds,
                sonicDiscoveryRows = mediaRegistry.sonicRows,
                canFavoriteAlbums = provider.capabilities.supportsAlbumFavorites,
                showSonicPathBuilder = sonicEnabled,
                showSonicMixBuilder = sonicEnabled,
                interfaceSettings = shell.general.interfaceSettings,
                savedGeneratedStations = stations,
            )
            val page = shell.home.collectionPage?.let { page ->
                content.collectionSections.firstOrNull { it.id == page.section.id }?.let { page.copy(section = it) }
            }
            shell.copy(home = shell.home.copy(content = content, collectionPage = page))
        }
    }

    private fun scopedSavedStations(
        provider: MediaProvider,
        sourceId: String?,
        sourceIdentity: NaviampConnectSourceIdentity?,
    ): List<GeneratedRadioStation> {
        val stations = try { generatedRadio.load().stations }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cachedSavedStations }
        return stations.filter {
            it.saved && it.belongsTo(sourceId, sourceIdentity, provider.cacheNamespace)
        }.also { cachedSavedStations = it }
    }

    fun restoreRecentRadioStreams(streams: List<RecentRadioStream>) {
        val provider = providerSource.current()
        val scoped = recentRadioStreamsForSource(streams, stateStore.state.value.shell.connectionSettings.currentSourceId)
        mediaRegistry.updateHome(mediaRegistry.home.copy(recentRadioStreams = scoped), mediaRegistry.sonicRows)
        stateStore.updateShell { shell ->
            val recentItems = scoped.map { recent ->
                val coverArtUrls = recent.coverArtIds
                    .mapNotNull { id -> provider?.coverArtUrl(id) }
                    .distinct()
                    .take(4)
                SharedMediaItemUi(
                    id = recent.id,
                    title = recent.label,
                    subtitle = recent.sessionSubtitle(),
                    coverArtUrl = coverArtUrls.firstOrNull(),
                    coverArtUrls = coverArtUrls,
                )
            }
            shell.copy(
                home = shell.home.copy(
                    content = shell.home.content.withRecentRadioStreams(
                        streams = recentItems,
                        interfaceSettings = shell.general.interfaceSettings,
                    ),
                ),
            )
        }
        if (provider != null) favoritesChanged(provider)
    }

    internal fun favoritesChanged(provider: MediaProvider) {
        if (providerSource.isCurrent(provider)) interfaceSettingsChanged(stateStore.state.value.shell.general.interfaceSettings)
    }

    internal fun interfaceSettingsChanged(settings: InterfaceSettings) {
        val provider = providerSource.current()
        val recommendations = mediaRegistry.home.toSharedRecommendationsSection(
            coverArtUrl = { id -> id?.let { provider?.coverArtUrl(it) } },
            canFavoriteAlbums = provider?.capabilities?.supportsAlbumFavorites == true,
            interfaceSettings = settings,
        )
        stateStore.updateShell { shell ->
            val updatedSections = listOfNotNull(recommendations) + shell.home.content.collectionSections
                .filterNot { it.id == app.naviamp.domain.settings.HomeSectionIds.FavoriteArtists }
                .map { section ->
                    val presentation = settings.homeSectionPresentation(section.id)
                    section.copy(
                        visible = presentation.visible,
                        homeLayout = presentation.homeLayout,
                        homeItemLimit = presentation.homeItemLimit,
                        defaultPageLayout = presentation.pageLayout,
                    )
                }
            val orderIndex = settings.resolvedHomeSectionOrder(updatedSections.map { it.id })
                .withIndex()
                .associate { it.value to it.index }
            val sections = updatedSections.sortedBy { orderIndex[it.id] ?: Int.MAX_VALUE }
            val currentPage = shell.home.collectionPage?.let { page ->
                val section = sections.firstOrNull { it.id == page.section.id && it.visible }
                    ?: return@let null
                page.copy(
                    section = section,
                    layout = settings.homeSectionPresentation(section.id).pageLayout,
                )
            }
            shell.copy(
                home = shell.home.copy(
                    content = shell.home.content.copy(
                        collectionSections = sections,
                        homeSectionOrder = settings.resolvedHomeSectionOrder(),
                        aurralHomeVisible = settings.homeSectionPresentation(app.naviamp.domain.settings.HomeSectionIds.Aurral).visible,
                    ),
                    collectionPage = currentPage,
                ),
            )
        }
    }

    fun resetNavigation() = discoveryController.resetNavigation()

    fun resetForSourceChange() {
        discoveryController.resetForSourceChange()
        sourceGeneration++
        recoveryScheduled = false
        refreshInFlight = false
        refreshFailed = false
        cachedHomeProvider = null
        cachedHomeSourceId = null
        cachedHomeLibraryScope = null
        cachedSavedStations = emptyList()
        discoveryFeedAccount = null
        discoveryFeedSourceId = null
        refreshGeneration += 1
        discoveryFeedGeneration++
        mediaRegistry.updateHome(
            app.naviamp.domain.home.HomeContent(),
            SonicHomeDiscoveryRows(),
        )
        stateStore.updateShell { shell ->
            shell.copy(
                home = shell.home.copy(
                    content = app.naviamp.ui.SharedHomeUi(),
                    refreshing = false,
                    collectionPage = null,
                    providerDiscovery = null,
                    favoriteSongsPage = null,
                ),
            )
        }
    }

    private fun openCollection(sectionId: String) {
        val section = stateStore.state.value.shell.home.content.collectionSections
            .firstOrNull { it.id == sectionId && it.visible }
        if (section == null) {
            publish(
                refreshing = stateStore.state.value.shell.home.refreshing,
                status = "This Home section is no longer available.",
            )
            return
        }
        navigationController.openHomeCollection(sectionId)
        stateStore.updateShell { shell ->
            shell.copy(home = shell.home.copy(collectionPage = SharedHomeCollectionPageUi(section)))
        }
        navigationController.recoverEnteredPage()
    }

    private fun closeCollection() = navigationController.back()

    private fun selectMixBuilder(id: String) {
        val route = when (id) {
            "artist" -> SharedRoute.ArtistMix
            "album" -> SharedRoute.AlbumMix
            "genre" -> SharedRoute.GenreMix
            "sonic-path" -> SharedRoute.SonicPath
            "sonic-mix" -> SharedRoute.SonicMix
            else -> {
                publish(
                    refreshing = stateStore.state.value.shell.home.refreshing,
                    status = "Unknown mix builder.",
                )
                return
            }
        }
        navigationController.dispatch(NaviampCoreCommand.Navigation.SelectRoute(route))
    }

    private fun publish(refreshing: Boolean, status: String?) {
        stateStore.update { state ->
            state.copy(
                shell = state.shell.copy(home = state.shell.home.copy(refreshing = refreshing)),
                overlays = state.overlays.copy(status = status),
            )
        }
    }
}

private fun SharedHomeCollectionItemAction.isBrowsableHomeMedia(): Boolean = when (this) {
    SharedHomeCollectionItemAction.SelectMixBuilder, SharedHomeCollectionItemAction.SelectStation,
    SharedHomeCollectionItemAction.OpenFavoriteSongs -> false
    else -> true
}

private fun ProviderDiscoveryStatus?.isHomeFetchFailure(): Boolean = when (this) {
    ProviderDiscoveryStatus.PasswordRequired, ProviderDiscoveryStatus.SignInRequired,
    ProviderDiscoveryStatus.Forbidden, ProviderDiscoveryStatus.Unavailable -> true
    else -> false
}

private const val SonicHomeDiscoveryLibrarySampleLimit = 5_000L
private const val SonicHomeDiscoveryRecentTrackLimit = 20L
private const val SonicHomeDiscoveryFavoriteTrackLimit = 5_000
