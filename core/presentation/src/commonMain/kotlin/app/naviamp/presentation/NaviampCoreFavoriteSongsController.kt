package app.naviamp.presentation

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.cache.FavoriteDownloadCatalog
import app.naviamp.domain.cache.enumerateFavoriteDownloadCatalog
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.ui.FavoriteSongsStatus
import app.naviamp.ui.SharedFavoriteSongsPageUi
import app.naviamp.ui.SharedTrackRowAction
import app.naviamp.ui.toSharedTrackRowUi
import app.naviamp.ui.totalDurationLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owns the virtual favorite-song collection; it never creates a provider playlist. */
class NaviampCoreFavoriteSongsController(
    private val stateStore: NaviampCoreStateStore,
    private val providerSource: NaviampCoreMediaProviderSource,
    private val navigation: NaviampCoreNavigationController,
    private val registry: NaviampCoreMediaRegistry,
    private val media: NaviampCoreMediaTransactions,
    private val trackActions: NaviampCoreTrackActionController,
) : NaviampCoreCommandController {
    private data class Source(
        val sourceId: String?,
        val providerId: String,
        val namespace: String,
        val folders: Set<String>,
    )

    private var generation = 0L
    private var requestedSource: Source? = null
    private var loadedSource: Source? = null
    private val pendingTrackUpdates = mutableMapOf<TrackId, Track>()

    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult = when (command) {
        NaviampCoreCommand.Home.OpenFavoriteSongs,
        NaviampCoreCommand.Home.RefreshFavoriteSongs,
        is NaviampCoreCommand.Home.PlayFavoriteSongs,
        is NaviampCoreCommand.Home.FavoriteSongAction,
        -> NaviampCoreImmediateCommandResult.Deferred
        else -> NaviampCoreImmediateCommandResult.Unhandled
    }

    override suspend fun execute(command: NaviampCoreCommand): NaviampCoreCommandResult? {
        when (command) {
            NaviampCoreCommand.Home.OpenFavoriteSongs -> {
                navigation.openPage(NaviampCoreFavoriteSongsPage(stateStore.state.value.shell.connectionSettings.currentSourceId))
                load()
            }
            NaviampCoreCommand.Home.RefreshFavoriteSongs -> if (page() != null) load()
            is NaviampCoreCommand.Home.PlayFavoriteSongs -> if (canPlay()) {
                media.playFavoriteSongs(registry.favoriteSongTracks, shuffle = command.shuffle)
            }
            is NaviampCoreCommand.Home.FavoriteSongAction -> if (canPlay()) {
                val tracks = registry.favoriteSongTracks
                val index = tracks.indexOfFirst { it.id.value == command.request.track.id }
                if (command.request.action == SharedTrackRowAction.Select && index >= 0) {
                    media.playFavoriteSongs(tracks, index = index)
                } else {
                    trackActions.executeTrackAction(command.request, tracks)
                }
            }
            else -> return null
        }
        return NaviampCoreCommandResult.Completed
    }

    fun resetForSourceChange() {
        generation++
        requestedSource = null
        loadedSource = null
        pendingTrackUpdates.clear()
        registry.updateFavoriteSongs(emptyList())
        stateStore.updateShell { shell -> shell.copy(home = shell.home.copy(favoriteSongsPage = null)) }
    }

    fun trackUpdated(provider: MediaProvider, track: Track) {
        if (!providerSource.isCurrent(provider) || requestedSource != source(provider) || page() == null) return
        if (page()?.status == FavoriteSongsStatus.Loading) {
            pendingTrackUpdates[track.id] = track
            return
        }
        if (loadedSource != source(provider)) return
        val tracks = registry.favoriteSongTracks.filterNot { it.id == track.id } +
            listOfNotNull(track.takeIf { it.favoritedAtIso8601 != null })
        publishTracks(provider, tracks.sortedWith(FavoriteSongsOrder))
    }

    private suspend fun load() {
        val token = ++generation
        val epoch = navigation.epoch
        val provider = providerSource.current()
        val boundSource = provider?.let(::source)
        requestedSource = boundSource
        loadedSource = null
        pendingTrackUpdates.clear()
        registry.updateFavoriteSongs(emptyList())
        publish(SharedFavoriteSongsPageUi())
        if (provider == null || !provider.capabilities.supportsTrackFavorites) {
            publish(SharedFavoriteSongsPageUi(status = FavoriteSongsStatus.Unsupported))
            return
        }
        fun current(): Boolean = token == generation && epoch == navigation.epoch && page() != null &&
            providerSource.current()?.let(::source) == boundSource
        try {
            val result = enumerateFavoriteDownloadCatalog(Int.MAX_VALUE) { request ->
                currentCoroutineContext().ensureActive()
                if (!current()) return@enumerateFavoriteDownloadCatalog null
                provider.favoriteTracksPage(request)
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return
            when (result) {
                is FavoriteDownloadCatalog.Complete -> {
                    val tracks = linkedMapOf<TrackId, Track>()
                    result.items.forEach { track ->
                        if (track.id !in tracks) tracks[track.id] = if (track.favoritedAtIso8601 == null) {
                            track.copy(favoritedAtIso8601 = "")
                        } else track
                    }
                    pendingTrackUpdates.forEach { (id, track) ->
                        if (track.favoritedAtIso8601 == null) tracks.remove(id) else tracks[id] = track
                    }
                    pendingTrackUpdates.clear()
                    loadedSource = boundSource
                    publishTracks(provider, tracks.values.sortedWith(FavoriteSongsOrder))
                }
                FavoriteDownloadCatalog.Unsupported -> publish(SharedFavoriteSongsPageUi(status = FavoriteSongsStatus.Unsupported))
                is FavoriteDownloadCatalog.TooLarge -> publish(SharedFavoriteSongsPageUi(status = FavoriteSongsStatus.Failed))
            }
        } catch (cancelled: CancellationException) {
            if (current()) publish(SharedFavoriteSongsPageUi(status = FavoriteSongsStatus.Idle))
            throw cancelled
        } catch (_: Exception) {
            if (current()) publish(SharedFavoriteSongsPageUi(status = FavoriteSongsStatus.Failed))
        }
    }

    private fun canPlay(): Boolean = page()?.status == FavoriteSongsStatus.Ready &&
        loadedSource != null && providerSource.current()?.let(::source) == loadedSource

    private fun source(provider: MediaProvider) = Source(
        stateStore.state.value.shell.connectionSettings.currentSourceId,
        provider.id.value,
        provider.cacheNamespace,
        provider.selectedMusicFolderIds.toSet(),
    )

    private fun page() = stateStore.state.value.shell.home.favoriteSongsPage

    private fun publishTracks(provider: MediaProvider, tracks: List<Track>) {
        registry.updateFavoriteSongs(tracks)
        publish(SharedFavoriteSongsPageUi(
            tracks = tracks.map { it.toSharedTrackRowUi(coverArtUrl = { id -> id?.let(provider::coverArtUrl) }) },
            totalDurationLabel = tracks.totalDurationLabel(),
            status = if (tracks.isEmpty()) FavoriteSongsStatus.Empty else FavoriteSongsStatus.Ready,
            canDownload = provider.capabilities.supportsDownloads,
            canStartRadio = provider.capabilities.supportsTrackRadio,
        ))
    }

    private fun publish(page: SharedFavoriteSongsPageUi) {
        stateStore.updateShell { shell -> shell.copy(home = shell.home.copy(favoriteSongsPage = page)) }
    }
}

private val FavoriteSongsOrder = compareBy<Track>({ it.title.lowercase() }, { it.title }, { it.id.value })
