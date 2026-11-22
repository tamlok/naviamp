package app.naviamp.presentation

import app.naviamp.app.NaviampConnectionPhase
import app.naviamp.app.NaviampConnectionRuntimeState
import app.naviamp.app.NaviampLivePlaybackState
import app.naviamp.domain.*
import app.naviamp.domain.provider.MediaPage
import app.naviamp.domain.provider.MediaPageRequest
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.domain.settings.FavoriteArtistSort
import app.naviamp.domain.settings.HomeSectionIds
import app.naviamp.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NaviampHomeFavoriteSongsTest {
    @Test
    fun openingLoadsEveryFavoriteWithoutPlayingAndPlayUsesTheCompleteSortedList() = runTest {
        val favorites = (0..5_000).map { favoriteTrack("song-$it", "Song ${it.toString().padStart(5, '0')}") }
        val provider = FavoriteSongsProvider(favorites.reversed() + favorites.first())
        val (core, effects) = favoriteCore(provider)
        val before = core.liveSnapshot()
        core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
        val page = core.favoritePage()
        assertEquals(FavoriteSongsStatus.Ready, page.status)
        assertEquals(favorites.map { it.id.value }, page.tracks.map { it.id })
        assertTrue(page.tracks.all { it.favoriteActive })
        assertEquals(before, core.liveSnapshot())
        assertTrue(effects.selections.isEmpty())
        assertTrue(effects.appliedQueues.isEmpty())
        assertTrue(provider.requests.last().offset >= 5_000)
        val reads = provider.requests.size
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
        assertEquals(favorites.map { it.id }, effects.selections.single().tracks.map { it.id })
        assertEquals(0, effects.selections.single().currentIndex)
        assertTrue(effects.selections.single().groups.isEmpty())
        assertEquals(reads, provider.requests.size, "Play must use the displayed complete collection")
        assertEquals(0, provider.libraryReads)
        core.close()
    }

    @Test
    fun shuffleAndRowSelectionUseAllDisplayedSongsWithoutChangingThePageOrder() = runTest {
        val favorites = (0..12).map { favoriteTrack("song-$it", "Song ${it.toString().padStart(2, '0')}") }
        val (core, effects) = favoriteCore(FavoriteSongsProvider(favorites))
        core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
        val page = core.favoritePage()
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs(shuffle = true))
        val shuffled = effects.selections.single()
        assertEquals(favorites.map { it.id }.toSet(), shuffled.tracks.map { it.id }.toSet())
        assertEquals(favorites.size, shuffled.tracks.size)
        assertEquals(0, shuffled.currentIndex)
        assertEquals(page, core.favoritePage())
        core.execute(NaviampCoreCommand.Home.FavoriteSongAction(SharedTrackRowActionRequest(page.tracks[7], SharedTrackRowAction.Select)))
        assertEquals(favorites.map { it.id }, effects.selections.last().tracks.map { it.id })
        assertEquals(7, effects.selections.last().currentIndex)
        assertEquals(favorites[7].id, core.liveSnapshot().currentTrack?.id)
        core.close()
    }

    @Test
    fun emptyAndUnsupportedPagesNeverReplacePlayback() = runTest {
        for ((provider, status) in listOf(
            FavoriteSongsProvider(emptyList()) to FavoriteSongsStatus.Empty,
            FavoriteSongsProvider(listOf(favoriteTrack("favorite")), supportsFavorites = false) to FavoriteSongsStatus.Unsupported,
            FavoriteSongsProvider(listOf(favoriteTrack("favorite")), unavailableOffset = 0) to FavoriteSongsStatus.Unsupported,
        )) {
            val (core, effects) = favoriteCore(provider)
            val before = core.liveSnapshot()
            core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs(true))
            assertEquals(status, core.favoritePage().status)
            assertTrue(core.favoritePage().tracks.isEmpty())
            assertEquals(before, core.liveSnapshot())
            assertTrue(effects.selections.isEmpty())
            assertTrue(effects.appliedQueues.isEmpty())
            core.close()
        }
    }

    @Test
    fun incompleteFavoritePagesAreNeverDisplayedOrPlayedAndRetryRecovers() = runTest {
        for (unsupported in listOf(false, true)) {
            var fail = true
            val favorites = (0..250).map { favoriteTrack("favorite-${it.toString().padStart(3, '0')}") }
            val provider = FavoriteSongsProvider(favorites, unavailableOffset = if (unsupported) 200 else null) {
                if (fail && !unsupported && it.offset > 0) error("Second page failed")
            }
            val (core, effects) = favoriteCore(provider)
            val before = core.liveSnapshot()
            core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
            assertEquals(if (unsupported) FavoriteSongsStatus.Unsupported else FavoriteSongsStatus.Failed, core.favoritePage().status)
            assertTrue(core.favoritePage().tracks.isEmpty())
            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
            assertEquals(before, core.liveSnapshot())
            assertTrue(effects.selections.isEmpty())
            assertEquals(0, provider.libraryReads)
            if (!unsupported) {
                fail = false
                core.execute(NaviampCoreCommand.Home.RefreshFavoriteSongs)
                assertEquals(favorites.size, core.favoritePage().tracks.size)
                core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
                assertEquals(favorites.map { it.id }, effects.selections.single().tracks.map { it.id })
            }
            core.close()
        }
    }

    @Test
    fun canceledLoadIsRetryableAndNeverReportsAnErrorOrChangesPlayback() = runTest {
        val gate = CompletableDeferred<Unit>()
        val provider = FavoriteSongsProvider(listOf(favoriteTrack("favorite"))) { gate.await() }
        val (core, effects) = favoriteCore(provider)
        val before = core.liveSnapshot()
        val pending = launch { core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs) }
        runCurrent()
        assertEquals(FavoriteSongsStatus.Loading, core.favoritePage().status)
        pending.cancelAndJoin()
        assertEquals(FavoriteSongsStatus.Idle, core.favoritePage().status)
        assertEquals(before, core.liveSnapshot())
        gate.complete(Unit)
        core.execute(NaviampCoreCommand.Home.RefreshFavoriteSongs)
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
        assertEquals(listOf("favorite"), effects.selections.single().tracks.map { it.id.value })
        core.close()
    }

    @Test
    fun refreshSupersedesAnOlderInFlightLoad() = runTest {
        val gate = CompletableDeferred<Unit>()
        var first = true
        val provider = FavoriteSongsProvider(listOf(favoriteTrack("favorite"))) {
            if (first) { first = false; gate.await() }
        }
        val (core, effects) = favoriteCore(provider)
        val pending = launch { core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs) }
        runCurrent()
        core.execute(NaviampCoreCommand.Home.RefreshFavoriteSongs)
        val ready = core.favoritePage()
        gate.complete(Unit)
        pending.join()
        assertEquals(ready, core.favoritePage())
        assertEquals(FavoriteSongsStatus.Ready, ready.status)
        assertTrue(effects.selections.isEmpty())
        core.close()
    }

    @Test
    fun backOrHomeReselectionDuringLoadingCannotResurrectThePage() = runTest {
        for (homeSelected in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val (core, effects) = favoriteCore(FavoriteSongsProvider(listOf(favoriteTrack("favorite"))) { gate.await() })
            val pending = launch { core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs) }
            runCurrent()
            if (homeSelected) core.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Home))
            else core.dispatch(NaviampCoreCommand.Navigation.Back)
            assertNull(core.state.value.shell.home.favoriteSongsPage)
            gate.complete(Unit)
            pending.join()
            assertNull(core.state.value.shell.home.favoriteSongsPage)
            assertTrue(effects.selections.isEmpty())
            core.close()
        }
    }

    @Test
    fun anInterveningPlaybackChoiceIsNotOverwrittenWhenThePageFinishesLoading() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (core, effects) = favoriteCore(FavoriteSongsProvider(listOf(favoriteTrack("favorite"))) { gate.await() })
        val pending = launch { core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs) }
        runCurrent()
        val replacement = PlaybackQueue(listOf(favoriteTrack("replacement")), 0)
        core.updateLivePlayback { it.copy(queue = replacement, currentTrack = replacement.current) }
        gate.complete(Unit)
        pending.join()
        assertEquals(replacement, core.liveSnapshot().queue)
        assertEquals(FavoriteSongsStatus.Ready, core.favoritePage().status)
        assertTrue(effects.selections.isEmpty())
        core.close()
    }

    @Test
    fun nestedAlbumBackRestoresTheFavoriteSongsPageAndBackReturnsHome() = runTest {
        val provider = FavoriteSongsProvider(listOf(favoriteTrack("favorite").copy(albumId = AlbumId("album"))))
        val (core, effects) = favoriteCore(provider)
        core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
        core.execute(NaviampCoreCommand.Home.FavoriteSongAction(SharedTrackRowActionRequest(core.favoritePage().tracks.single(), SharedTrackRowAction.GoToAlbum)))
        assertNull(core.state.value.shell.home.favoriteSongsPage)
        assertEquals("album", core.state.value.shell.albumDetail.selectedAlbum?.id)
        core.dispatch(NaviampCoreCommand.Navigation.Back)
        runCurrent()
        assertEquals(listOf("favorite"), core.favoritePage().tracks.map { it.id })
        core.dispatch(NaviampCoreCommand.Navigation.Back)
        assertNull(core.state.value.shell.home.favoriteSongsPage)
        assertTrue(effects.selections.isEmpty())
        core.close()
    }

    @Test
    fun unfavoritingUpdatesThePageButKeepsThePlayingQueueMembership() = runTest {
        val provider = FavoriteSongsProvider(listOf(favoriteTrack("first"), favoriteTrack("second")))
        val (core, effects) = favoriteCore(provider)
        core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
        val queuedIds = core.liveSnapshot().queue.tracks.map { it.id }
        core.execute(NaviampCoreCommand.Home.FavoriteSongAction(SharedTrackRowActionRequest(core.favoritePage().tracks.first(), SharedTrackRowAction.ToggleFavorite)))
        assertEquals(listOf("second"), core.favoritePage().tracks.map { it.id })
        assertEquals(queuedIds, core.liveSnapshot().queue.tracks.map { it.id })
        assertEquals(1, effects.selections.size)
        core.close()
    }

    @Test
    fun changingProviderOrFolderCannotPlayTheOldListAndRefreshUsesTheNewSource() = runTest {
        val defaults = fakeCoreServices()
        var folder = "first"
        val provider = FavoriteSongsProvider(listOf(favoriteTrack("first-favorite")))
        var active: MediaProvider = object : MediaProvider by provider {
            override val selectedMusicFolderIds get() = listOf(folder)
        }
        val effects = FavoritePlaybackEffects(defaults.playback.effects)
        val core = NaviampCore.create(this, defaults.copy(
            content = defaults.content.copy(providerSource = NaviampCoreMediaProviderSource { active }),
            playback = defaults.playback.copy(effects = effects),
        ))
        core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
        folder = "second"
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
        assertTrue(effects.selections.isEmpty())
        active = object : MediaProvider by FavoriteSongsProvider(listOf(favoriteTrack("second-favorite"))) {
            override val cacheNamespace = "second-provider"
        }
        core.execute(NaviampCoreCommand.Home.RefreshFavoriteSongs)
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
        assertEquals(listOf("second-favorite"), effects.selections.single().tracks.map { it.id.value })
        core.close()
    }

    @Test
    fun switchingAccountsDuringLoadingDiscardsStaleSuccessAndFailureAndNewSourceCanPlay() = runTest {
        for (fail in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            var active: MediaProvider = FavoriteSongsProvider(listOf(favoriteTrack("old-favorite"))) {
                gate.await()
                if (fail) error("Old source failed")
            }
            val defaults = fakeCoreServices()
            val effects = FavoritePlaybackEffects(defaults.playback.effects)
            val records = listOf("first", "second").map { NaviampCoreSavedConnectionRecord(it, it, "https://music.example", it) }
            val sessions = object : NaviampCoreProviderSessionPort by defaults.connection {
                override suspend fun connect(request: NaviampCoreConnectionRequest, plan: app.naviamp.app.NaviampConnectionAttemptPlan): NaviampCoreConnectedSession {
                    val id = (request as NaviampCoreConnectionRequest.Saved).id
                    active = FavoriteSongsProvider(listOf(favoriteTrack("new-favorite")))
                    return NaviampCoreConnectedSession(id, id, inventory = NaviampCoreConnectionInventory(records, id))
                }
            }
            val core = NaviampCore.create(this, defaults.copy(connection = sessions,
                content = defaults.content.copy(providerSource = NaviampCoreMediaProviderSource { active }),
                playback = defaults.playback.copy(effects = effects)), initialState = NaviampCoreInitialState(
                connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = "first"),
                connectionInventory = NaviampCoreConnectionInventory(records, "first"),
            ))
            val pending = launch { core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs) }
            runCurrent()
            core.execute(NaviampCoreCommand.Connection.SwitchAccount(NaviampSavedConnectionUi("second", "second", "https://music.example", "second")))
            runCurrent()
            assertNull(core.state.value.shell.home.favoriteSongsPage)
            gate.complete(Unit)
            pending.join()
            assertNull(core.state.value.shell.home.favoriteSongsPage)
            core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs)
            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs())
            assertEquals(listOf("new-favorite"), effects.selections.last().tracks.map { it.id.value })
            core.close()
        }
    }

    @Test
    fun deletingTheCurrentAccountDuringLoadingClearsThePageAndItsBackHistory() = runTest {
        val gate = CompletableDeferred<Unit>()
        var active: MediaProvider? = FavoriteSongsProvider(listOf(favoriteTrack("favorite"))) { gate.await() }
        val defaults = fakeCoreServices()
        val record = NaviampCoreSavedConnectionRecord("only", "Only", "https://music.example", "user")
        val sessions = object : NaviampCoreProviderSessionPort by defaults.connection {
            override suspend fun deleteConnection(id: String): NaviampCoreConnectionInventory {
                active = null
                return NaviampCoreConnectionInventory()
            }
        }
        val core = NaviampCore.create(this, defaults.copy(connection = sessions,
            content = defaults.content.copy(providerSource = NaviampCoreMediaProviderSource { active })), initialState = NaviampCoreInitialState(
            connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = "only"),
            connectionInventory = NaviampCoreConnectionInventory(listOf(record), "only"),
        ))
        val pending = launch { core.execute(NaviampCoreCommand.Home.OpenFavoriteSongs) }
        runCurrent()
        core.execute(NaviampCoreCommand.Connection.Delete(NaviampSavedConnectionUi("only", "Only", "https://music.example", "user")))
        assertNull(core.state.value.shell.home.favoriteSongsPage)
        gate.complete(Unit)
        pending.join()
        core.dispatch(NaviampCoreCommand.Navigation.Back)
        assertNull(core.state.value.shell.home.favoriteSongsPage)
        assertNull(core.state.value.shell.connectionSettings.currentSourceId)
        assertFalse(core.state.value.shell.shellChrome.canNavigateBack)
        core.close()
    }

    @Test
    fun recommendationsKeepSongArtistAlbumGroupsWhenSortingAndFavoritesChange() = runTest {
        val provider = FavoriteSongsProvider(emptyList())
        provider.artistCatalog = listOf(Artist(ArtistId("z"), "Zulu", "2026-02-01"), Artist(ArtistId("a"), "Alpha", "2026-01-01"))
        provider.albumCatalog = listOf(Album(AlbumId("album"), "Album", "Artist", null, null, favoritedAtIso8601 = "now"))
        val (core, _) = favoriteCore(provider)
        core.execute(NaviampCoreCommand.Home.Refresh)
        core.dispatch(NaviampCoreCommand.Home.OpenCollection(HomeSectionIds.FavoriteArtists))
        for ((sort, artistIds) in listOf(FavoriteArtistSort.DateFavorited to listOf("z", "a"), FavoriteArtistSort.LastRadioPlayed to listOf("a", "z"))) {
            core.dispatch(NaviampCoreCommand.Settings.ChangeFavoriteArtistSort(sort))
            val expected = listOf("favorite-songs") + artistIds + "album"
            assertEquals(expected, core.recommendations().items.map { it.mediaItem.id })
            assertEquals(expected, core.state.value.shell.home.collectionPage?.section?.items?.map { it.mediaItem.id })
        }
        val albumItem = core.recommendations().items.last().mediaItem
        core.execute(NaviampCoreCommand.Media.ItemAction(albumItem.albumActionRequest(NaviampArtistAlbumCommand.ToggleFavorite)))
        assertEquals(listOf("favorite-songs", "a", "z"), core.recommendations().items.map { it.mediaItem.id })
        core.execute(NaviampCoreCommand.Media.ItemAction(albumItem.albumActionRequest(NaviampArtistAlbumCommand.ToggleFavorite)))
        assertEquals("album", core.recommendations().items.last().mediaItem.id)
        val artist = core.recommendations().items[1].mediaItem
        core.execute(NaviampCoreCommand.Media.ItemAction(artist.artistActionRequest(NaviampArtistMediaCommand.ToggleFavorite)))
        assertEquals(listOf("favorite-songs", "z", "album"), core.recommendations().items.map { it.mediaItem.id })
        core.execute(NaviampCoreCommand.Media.ItemAction(artist.artistActionRequest(NaviampArtistMediaCommand.ToggleFavorite)))
        assertEquals(listOf("favorite-songs", "a", "z", "album"), core.recommendations().items.map { it.mediaItem.id })
        core.close()
    }

    private fun TestScope.favoriteCore(provider: MediaProvider): Pair<NaviampCore, FavoritePlaybackEffects> {
        val defaults = fakeCoreServices(provider)
        val effects = FavoritePlaybackEffects(defaults.playback.effects)
        val queue = PlaybackQueue(listOf(favoriteTrack("old-current"), favoriteTrack("old-next")), 0)
        val core = NaviampCore.create(this, defaults.copy(playback = defaults.playback.copy(effects = effects)),
            initialState = NaviampCoreInitialState(playback = NaviampLivePlaybackState(queue = queue, currentTrack = queue.current)))
        runCurrent()
        effects.selections.clear()
        effects.appliedQueues.clear()
        return core to effects
    }
}

private class FavoriteSongsProvider(
    private var favorites: List<Track>,
    supportsFavorites: Boolean = true,
    private val unavailableOffset: Int? = null,
    private val beforePage: suspend (MediaPageRequest) -> Unit = {},
) : MediaProvider by FakeCoreMediaProvider() {
    override val capabilities = FakeCoreMediaProvider().capabilities.copy(
        supportsTrackFavorites = supportsFavorites, supportsArtistFavorites = true, supportsAlbumFavorites = true,
    )
    var artistCatalog: List<Artist> = emptyList()
    var albumCatalog: List<Album> = emptyList()
    val requests = mutableListOf<MediaPageRequest>()
    var libraryReads = 0

    override suspend fun favoriteTracksPage(request: MediaPageRequest): MediaPage<Track>? {
        requests += request
        beforePage(request)
        if (request.offset == unavailableOffset) return null
        val items = favorites.subList(request.offset, minOf(request.offset + request.limit, favorites.size))
        return MediaPage(items, request.offset, request.limit,
            hasMore = request.offset + items.size < favorites.size)
    }

    override suspend fun setTrackFavorite(trackId: TrackId, favorite: Boolean) {
        if (!favorite) favorites = favorites.filterNot { it.id == trackId }
    }

    override suspend fun artists(limit: Int) = artistCatalog.take(limit)
    override suspend fun favoriteArtists(limit: Int) = artistCatalog.filter { it.favoritedAtIso8601 != null }.take(limit)
    override suspend fun albumList(type: app.naviamp.domain.provider.AlbumListType, limit: Int) = albumCatalog.take(limit)
    override suspend fun favoriteAlbumsPage(request: MediaPageRequest): MediaPage<Album> {
        val albums = albumCatalog.filter { it.favoritedAtIso8601 != null }
        val items = albums.drop(request.offset).take(request.limit)
        return MediaPage(items, request.offset, request.limit, request.offset + items.size < albums.size)
    }

    override suspend fun album(albumId: AlbumId) = AlbumDetails(
        albumCatalog.firstOrNull { it.id == albumId } ?: Album(albumId, "Album", "Artist", null, null),
        favorites.filter { it.albumId == albumId },
    )

    override suspend fun setArtistFavorite(artistId: ArtistId, favorite: Boolean) {
        artistCatalog = artistCatalog.map { if (it.id == artistId) it.copy(favoritedAtIso8601 = if (favorite) "now" else null) else it }
    }

    override suspend fun setAlbumFavorite(albumId: AlbumId, favorite: Boolean) {
        albumCatalog = albumCatalog.map { if (it.id == albumId) it.copy(favoritedAtIso8601 = if (favorite) "now" else null) else it }
    }

    override suspend fun tracks(limit: Int): List<Track> {
        libraryReads++
        return listOf(favoriteTrack("not-a-favorite"))
    }
}

private class FavoritePlaybackEffects(delegate: NaviampCorePlaybackEffectPort) : NaviampCorePlaybackEffectPort by delegate {
    val selections = mutableListOf<PlaybackQueue>()
    val appliedQueues = mutableListOf<PlaybackQueue>()

    override fun playQueueSelection(queue: PlaybackQueue, index: Int) {
        selections += queue.jumpTo(index)
    }

    override fun applyQueue(queue: PlaybackQueue, clearPreparedNext: Boolean) {
        appliedQueues += queue
    }
}

private fun favoriteTrack(id: String, title: String = id): Track = Track(
    id = TrackId(id),
    title = title,
    artistName = "Artist",
    albumTitle = "Album",
    durationSeconds = 180,
    coverArtId = null,
    audioInfo = null,
    replayGain = null,
)

private fun NaviampCore.liveSnapshot(): NaviampLivePlaybackState {
    var snapshot: NaviampLivePlaybackState? = null
    updateLivePlayback { live -> live.also { snapshot = it } }
    return checkNotNull(snapshot)
}

private fun NaviampCore.favoritePage() = requireNotNull(state.value.shell.home.favoriteSongsPage)

private fun NaviampCore.recommendations() = state.value.shell.home.content.collectionSections.single {
    it.id == HomeSectionIds.FavoriteArtists
}
