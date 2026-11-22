package app.naviamp.presentation

import app.naviamp.app.NaviampConnectionPhase
import app.naviamp.app.NaviampConnectionRuntimeState
import app.naviamp.app.NaviampLivePlaybackState
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.provider.MediaPage
import app.naviamp.domain.provider.MediaPageRequest
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.ui.FavoriteSongsPlaybackStatus
import app.naviamp.ui.NaviampSavedConnectionUi
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
    fun playsEveryFavoriteAcrossPagesDeduplicatedInDeterministicTitleOrder() = runTest {
        val numbered = (0..5_000).map { index ->
            favoriteTrack("song-$index", "Song ${index.toString().padStart(5, '0')}")
        }
        val first = favoriteTrack("upper", "Alpha")
        val second = favoriteTrack("lower-a", "alpha")
        val third = favoriteTrack("lower-b", "alpha")
        val last = favoriteTrack("last", "Zulu")
        val provider = FavoriteSongsProvider(
            listOf(last, third) + numbered.reversed() + listOf(second, first, third.copy(title = "Duplicate")),
        )
        val (core, effects) = favoriteCore(provider)

        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)
        runCurrent()

        val expected = listOf(first, second, third) + numbered + last
        val selection = effects.selections.single()
        assertEquals(expected, selection.tracks)
        assertEquals(0, selection.currentIndex)
        assertEquals(0, selection.playNextCount)
        assertTrue(selection.groups.isEmpty(), "Favorites are a normal queue, not a radio or collection group")
        assertEquals(expected, core.liveSnapshot().queue.tracks)
        assertEquals(first.id.value, core.state.value.shell.nowPlaying?.id)
        assertNull(core.liveSnapshot().currentStation)
        assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
        assertTrue(provider.requests.size > 1)
        assertTrue(provider.requests.last().offset >= 5_000, "Do not truncate at the old 5,000-item bound")
        assertEquals(0, provider.libraryReads, "Favorite playback must not scan the library")
        core.close()
    }

    @Test
    fun emptyFavoritesKeepTheExistingQueueAndReportEmpty() = runTest {
        val (core, effects) = favoriteCore(FavoriteSongsProvider(emptyList()))
        val before = core.liveSnapshot()

        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)

        assertEquals(before, core.liveSnapshot())
        assertTrue(effects.selections.isEmpty())
        assertTrue(effects.appliedQueues.isEmpty())
        assertEquals(FavoriteSongsPlaybackStatus.Empty, core.state.value.shell.home.favoriteSongsPlaybackStatus)
        core.close()
    }

    @Test
    fun unavailableFavoriteEndpointNeverFallsBackToLibraryOrPartialResults() = runTest {
        for (unavailableOffset in listOf(0, 200)) {
            val provider = FavoriteSongsProvider(
                (0..250).map { favoriteTrack("favorite-$it") },
                unavailableOffset = unavailableOffset,
            )
            val (core, effects) = favoriteCore(provider)
            val before = core.liveSnapshot()

            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)

            assertEquals(before, core.liveSnapshot())
            assertTrue(effects.selections.isEmpty())
            assertTrue(effects.appliedQueues.isEmpty())
            assertEquals(FavoriteSongsPlaybackStatus.Unsupported, core.state.value.shell.home.favoriteSongsPlaybackStatus)
            assertEquals(unavailableOffset, provider.requests.last().offset)
            assertEquals(0, provider.libraryReads)
            core.close()
        }
    }

    @Test
    fun laterPageFailureKeepsTheOldQueueAndAllowsRetry() = runTest {
        var fail = true
        val favorites = (0..250).map { favoriteTrack("favorite-${it.toString().padStart(3, '0')}") }
        val provider = FavoriteSongsProvider(favorites) { request ->
            if (fail && request.offset > 0) error("Second favorite page failed")
        }
        val (core, effects) = favoriteCore(provider)
        val before = core.liveSnapshot()

        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)

        assertEquals(before, core.liveSnapshot())
        assertTrue(effects.selections.isEmpty())
        assertTrue(effects.appliedQueues.isEmpty())
        assertEquals(FavoriteSongsPlaybackStatus.Failed, core.state.value.shell.home.favoriteSongsPlaybackStatus)
        assertEquals(listOf(0, 200), provider.requests.map { it.offset })

        fail = false
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)
        runCurrent()

        assertEquals(favorites, effects.selections.single().tracks)
        assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
        core.close()
    }

    @Test
    fun unsupportedCapabilityDoesNotRequestFavoritePages() = runTest {
        val provider = FavoriteSongsProvider(listOf(favoriteTrack("favorite")), supportsFavorites = false)
        val (core, effects) = favoriteCore(provider)
        val before = core.liveSnapshot()

        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)

        assertEquals(before, core.liveSnapshot())
        assertTrue(provider.requests.isEmpty())
        assertTrue(effects.selections.isEmpty())
        assertEquals(FavoriteSongsPlaybackStatus.Unsupported, core.state.value.shell.home.favoriteSongsPlaybackStatus)
        core.close()
    }

    @Test
    fun homePublishesFavoriteCapabilityForTheCurrentProviderAndClearsItWhenDisconnected() = runTest {
        val supported = FavoriteSongsProvider(emptyList())
        var active: MediaProvider? = supported
        val defaults = fakeCoreServices()
        val core = NaviampCore.create(this, defaults.copy(content = defaults.content.copy(
            providerSource = NaviampCoreMediaProviderSource { active },
        )))

        core.execute(NaviampCoreCommand.Home.Refresh)
        assertTrue(core.state.value.shell.home.supportsFavoriteSongs)

        active = FavoriteSongsProvider(emptyList(), supportsFavorites = false)
        core.execute(NaviampCoreCommand.Home.Refresh)
        assertFalse(core.state.value.shell.home.supportsFavoriteSongs)

        active = supported
        core.execute(NaviampCoreCommand.Home.Refresh)
        assertTrue(core.state.value.shell.home.supportsFavoriteSongs)
        active = null
        core.execute(NaviampCoreCommand.Home.Refresh)
        assertFalse(core.state.value.shell.home.supportsFavoriteSongs)
        core.close()
    }

    @Test
    fun cancellingAnInFlightLoadKeepsPlaybackAndReleasesTheNextRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val favorite = favoriteTrack("favorite")
        val provider = FavoriteSongsProvider(listOf(favorite)) { gate.await() }
        val (core, effects) = favoriteCore(provider)
        val before = core.liveSnapshot()
        val pending = launch { core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs) }
        runCurrent()
        assertEquals(FavoriteSongsPlaybackStatus.Loading, core.state.value.shell.home.favoriteSongsPlaybackStatus)

        pending.cancelAndJoin()

        assertTrue(pending.isCancelled)
        assertEquals(before, core.liveSnapshot())
        assertTrue(effects.selections.isEmpty())
        assertTrue(effects.appliedQueues.isEmpty())
        assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)

        gate.complete(Unit)
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)
        runCurrent()
        assertEquals(listOf(favorite), effects.selections.single().tracks)
        assertEquals(2, provider.requests.size)
        core.close()
    }

    @Test
    fun repeatedClicksDuringLoadingDoNotReloadOrClearTheLoadingStatus() = runTest {
        val gate = CompletableDeferred<Unit>()
        val favorite = favoriteTrack("favorite")
        val provider = FavoriteSongsProvider(listOf(favorite)) { gate.await() }
        val (core, effects) = favoriteCore(provider)

        core.dispatch(NaviampCoreCommand.Home.PlayFavoriteSongs)
        runCurrent()
        core.dispatch(NaviampCoreCommand.Home.PlayFavoriteSongs)
        core.dispatch(NaviampCoreCommand.Home.PlayFavoriteSongs)
        runCurrent()

        assertEquals(1, provider.requests.size)
        assertEquals(FavoriteSongsPlaybackStatus.Loading, core.state.value.shell.home.favoriteSongsPlaybackStatus)
        assertTrue(effects.selections.isEmpty())

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(favorite), effects.selections.single().tracks)
        assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
        assertEquals(1, provider.requests.size)
        core.close()
    }

    @Test
    fun aDifferentQueueSelectedWhileFavoritesLoadIsNotOverwritten() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (core, effects) = favoriteCore(FavoriteSongsProvider(listOf(favoriteTrack("favorite"))) { gate.await() })
        val pending = launch { core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs) }
        runCurrent()
        val replacement = PlaybackQueue(listOf(favoriteTrack("replacement")), 0)
        core.updateLivePlayback { it.copy(queue = replacement, currentTrack = replacement.current) }
        val before = core.liveSnapshot()

        gate.complete(Unit)
        pending.join()
        runCurrent()

        assertEquals(before, core.liveSnapshot())
        assertTrue(effects.selections.isEmpty())
        assertTrue(effects.appliedQueues.isEmpty())
        assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
        core.close()
    }

    @Test
    fun switchingAccountsDiscardsBothStaleSuccessAndStaleFailure() = runTest {
        for (fail in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val first = FavoriteSongsProvider(listOf(favoriteTrack("old-favorite"))) {
                gate.await()
                if (fail) error("Old account failed")
            }
            var active: MediaProvider = first
            val defaults = fakeCoreServices()
            val effects = FavoritePlaybackEffects(defaults.playback.effects)
            val records = listOf("first", "second").map {
                NaviampCoreSavedConnectionRecord(it, it, "https://music.example", it)
            }
            val sessions = object : NaviampCoreProviderSessionPort by defaults.connection {
                override suspend fun connect(
                    request: NaviampCoreConnectionRequest,
                    plan: app.naviamp.app.NaviampConnectionAttemptPlan,
                ): NaviampCoreConnectedSession {
                    val id = (request as NaviampCoreConnectionRequest.Saved).id
                    active = FavoriteSongsProvider(listOf(favoriteTrack("new-favorite")))
                    return NaviampCoreConnectedSession(id, id,
                        inventory = NaviampCoreConnectionInventory(records, id))
                }
            }
            val core = NaviampCore.create(this, defaults.copy(
                connection = sessions,
                content = defaults.content.copy(providerSource = NaviampCoreMediaProviderSource { active }),
                playback = defaults.playback.copy(effects = effects),
            ), initialState = NaviampCoreInitialState(
                connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = "first"),
                connectionInventory = NaviampCoreConnectionInventory(records, "first"),
            ))
            runCurrent()
            val pending = launch { core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs) }
            runCurrent()
            assertEquals(FavoriteSongsPlaybackStatus.Loading, core.state.value.shell.home.favoriteSongsPlaybackStatus)

            core.execute(NaviampCoreCommand.Connection.SwitchAccount(
                NaviampSavedConnectionUi("second", "second", "https://music.example", "second"),
            ))
            runCurrent()
            assertEquals("second", core.state.value.shell.connectionSettings.currentSourceId)
            val replacement = PlaybackQueue(listOf(favoriteTrack("second-account-track")), 0)
            core.updateLivePlayback { it.copy(queue = replacement, currentTrack = replacement.current) }
            val before = core.liveSnapshot()
            effects.selections.clear()
            effects.appliedQueues.clear()

            gate.complete(Unit)
            pending.join()
            runCurrent()

            assertEquals(before, core.liveSnapshot())
            assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
            assertTrue(core.state.value.shell.home.supportsFavoriteSongs)
            assertTrue(effects.selections.isEmpty())
            assertTrue(effects.appliedQueues.isEmpty())
            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)
            assertEquals(listOf("new-favorite"), effects.selections.single().tracks.map { it.id.value })
            core.close()
        }
    }

    @Test
    fun changingProvidersDiscardsStaleResultsEvenWhenTheQueueHasNotChanged() = runTest {
        for (fail in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            var active: MediaProvider = FavoriteSongsProvider(listOf(favoriteTrack("old-favorite"))) {
                gate.await()
                if (fail) error("Old provider failed")
            }
            val defaults = fakeCoreServices()
            val effects = FavoritePlaybackEffects(defaults.playback.effects)
            val queue = PlaybackQueue(listOf(favoriteTrack("old-current"), favoriteTrack("old-next")), 0)
            val core = NaviampCore.create(this, defaults.copy(
                content = defaults.content.copy(providerSource = NaviampCoreMediaProviderSource { active }),
                playback = defaults.playback.copy(effects = effects),
            ), initialState = NaviampCoreInitialState(playback = NaviampLivePlaybackState(
                queue = queue, currentTrack = queue.current,
            )))
            runCurrent()
            val before = core.liveSnapshot()
            val pending = launch { core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs) }
            runCurrent()
            assertEquals(FavoriteSongsPlaybackStatus.Loading, core.state.value.shell.home.favoriteSongsPlaybackStatus)

            active = object : MediaProvider by FavoriteSongsProvider(listOf(favoriteTrack("new-provider-favorite"))) {
                override val cacheNamespace = "different-provider-account"
            }
            core.execute(NaviampCoreCommand.Home.Refresh)
            runCurrent()
            gate.complete(Unit)
            pending.join()
            runCurrent()

            assertEquals(before, core.liveSnapshot())
            assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
            assertTrue(core.state.value.shell.home.supportsFavoriteSongs)
            assertTrue(effects.selections.isEmpty())
            assertTrue(effects.appliedQueues.isEmpty())
            core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)
            assertEquals(listOf("new-provider-favorite"), effects.selections.single().tracks.map { it.id.value })
            core.close()
        }
    }

    @Test
    fun deletingCurrentAccountHidesShortcutAndClearsPreviousFavoriteStatusWithoutRefresh() = runTest {
        var active: MediaProvider? = FavoriteSongsProvider(emptyList())
        val defaults = fakeCoreServices()
        val record = NaviampCoreSavedConnectionRecord("only", "Only", "https://music.example", "user")
        val sessions = object : NaviampCoreProviderSessionPort by defaults.connection {
            override suspend fun deleteConnection(id: String): NaviampCoreConnectionInventory {
                assertEquals("only", id)
                active = null
                return NaviampCoreConnectionInventory()
            }
        }
        val core = NaviampCore.create(this, defaults.copy(
            connection = sessions,
            content = defaults.content.copy(providerSource = NaviampCoreMediaProviderSource { active }),
        ), initialState = NaviampCoreInitialState(
            connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = "only"),
            connectionInventory = NaviampCoreConnectionInventory(listOf(record), "only"),
        ))
        core.execute(NaviampCoreCommand.Home.Refresh)
        core.execute(NaviampCoreCommand.Home.PlayFavoriteSongs)
        assertTrue(core.state.value.shell.home.supportsFavoriteSongs)
        assertEquals(FavoriteSongsPlaybackStatus.Empty, core.state.value.shell.home.favoriteSongsPlaybackStatus)
        core.execute(NaviampCoreCommand.Connection.Delete(
            NaviampSavedConnectionUi("only", "Only", "https://music.example", "user"),
        ))
        assertNull(core.state.value.shell.connectionSettings.currentSourceId)
        assertFalse(core.state.value.shell.home.supportsFavoriteSongs)
        assertNull(core.state.value.shell.home.favoriteSongsPlaybackStatus)
        core.close()
    }

    private fun TestScope.favoriteCore(provider: MediaProvider): Pair<NaviampCore, FavoritePlaybackEffects> {
        val defaults = fakeCoreServices(provider)
        val effects = FavoritePlaybackEffects(defaults.playback.effects)
        val queue = PlaybackQueue(listOf(favoriteTrack("old-current"), favoriteTrack("old-next")), 0)
        val core = NaviampCore.create(this, defaults.copy(playback = defaults.playback.copy(effects = effects)),
            initialState = NaviampCoreInitialState(playback = NaviampLivePlaybackState(
                queue = queue,
                currentTrack = queue.current,
            )))
        runCurrent()
        effects.selections.clear()
        effects.appliedQueues.clear()
        return core to effects
    }
}

private class FavoriteSongsProvider(
    private val favorites: List<Track>,
    supportsFavorites: Boolean = true,
    private val unavailableOffset: Int? = null,
    private val beforePage: suspend (MediaPageRequest) -> Unit = {},
) : MediaProvider by FakeCoreMediaProvider() {
    override val capabilities = FakeCoreMediaProvider().capabilities.copy(supportsTrackFavorites = supportsFavorites)
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
