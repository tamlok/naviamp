package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationController
import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Playlist
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.ui.NaviampArtistAlbumCommand
import app.naviamp.ui.NaviampArtistMediaCommand
import app.naviamp.ui.artistActionRequest
import app.naviamp.ui.NaviampPlaylistMediaCommand
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.albumActionRequest
import app.naviamp.ui.playlistActionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NaviampCoreSourceSwitchRaceTest {
    @Test fun completedSearchAndRegistryAreClearedWhenSourceChanges() = runTest {
        var active: MediaProvider = FakeCoreMediaProvider()
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val catalog = NaviampCoreCatalogController(store, { active }, mediaRegistry = registry)
        catalog.dispatch(NaviampCoreCommand.Search.ChangeQuery("old search"))
        catalog.execute(NaviampCoreCommand.Search.Submit)
        assertFalse(registry.search.isEmpty)
        active = object : MediaProvider by FakeCoreMediaProvider() { override val cacheNamespace = "second-server" }
        catalog.refreshAfterConnection()
        assertTrue(registry.search.isEmpty)
        assertEquals("", store.state.value.shell.search.query)
        assertTrue(store.state.value.shell.search.results.albums.isEmpty())
    }

    @Test fun delayedSearchSuccessAndFailureCannotCrossASourceSwitch() = runTest {
        for (fail in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val base = FakeCoreMediaProvider()
            var active: MediaProvider = object : MediaProvider by base {
                override suspend fun search(query: String, limit: Int): MediaSearchResults {
                    gate.await()
                    if (fail) error("old server failed")
                    return base.search(query, limit)
                }
            }
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val catalog = NaviampCoreCatalogController(store, { active }, mediaRegistry = registry)
            catalog.dispatch(NaviampCoreCommand.Search.ChangeQuery("old search"))
            val pending = launch { catalog.execute(NaviampCoreCommand.Search.Submit) }
            runCurrent()
            assertTrue(store.state.value.shell.search.searching)
            active = object : MediaProvider by base { override val cacheNamespace = "second-server" }
            catalog.refreshAfterConnection()
            val expected = store.state.value.shell.search
            gate.complete(Unit)
            pending.join()
            assertEquals(expected, store.state.value.shell.search)
            assertTrue(registry.search.isEmpty)
            assertFalse(store.state.value.shell.search.searching)
        }
    }

    @Test fun delayedPlaylistDetailCannotCrossASourceSwitch() = runTest {
        for (fail in listOf(false, true)) {
            val base = FakeCoreMediaProvider()
            val gate = CompletableDeferred<Unit>()
            var active: MediaProvider = object : MediaProvider by base {
                override suspend fun playlistTracks(playlistId: String): List<app.naviamp.domain.Track> {
                    gate.await()
                    if (fail) error("old server failed")
                    return base.playlistTracks(playlistId)
                }
            }
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store, { })
            val playlists = NaviampCorePlaylistBrowseController(store, { active }, navigation, mediaRegistry = registry)
            playlists.refreshAfterConnection()
            val pending = launch { playlists.execute(NaviampCoreCommand.Media.ItemAction(
                SharedMediaItemUi(base.playlist.id, "Old playlist", "").playlistActionRequest(NaviampPlaylistMediaCommand.Select),
            )) }
            runCurrent()
            active = object : MediaProvider by base {
                override val cacheNamespace = "second-server"
                override suspend fun playlists(limit: Int) = listOf(Playlist("new", "New playlist", 0))
            }
            playlists.refreshAfterConnection()
            gate.complete(Unit)
            pending.join()
            assertEquals(listOf("new"), registry.playlists.map { it.id })
            assertNull(registry.selectedPlaylist)
            assertNull(store.state.value.shell.playlistDetail.selectedPlaylist)
            assertNull(store.state.value.shell.playlistDetail.status)
        }
    }

    @Test fun switchingAwayAndBackStillRejectsTheFirstConnectionSearch() = runTest {
        val gate = CompletableDeferred<Unit>()
        val base = FakeCoreMediaProvider()
        val first = object : MediaProvider by base {
            override suspend fun search(query: String, limit: Int): MediaSearchResults {
                gate.await()
                return base.search(query, limit)
            }
        }
        var active: MediaProvider = first
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val catalog = NaviampCoreCatalogController(store, { active }, mediaRegistry = registry)
        catalog.dispatch(NaviampCoreCommand.Search.ChangeQuery("old search"))
        val pending = launch { catalog.execute(NaviampCoreCommand.Search.Submit) }
        runCurrent()
        catalog.resetForSourceChange()
        active = object : MediaProvider by base { override val cacheNamespace = "second-server" }
        catalog.refreshAfterConnection()
        catalog.resetForSourceChange()
        active = first
        catalog.refreshAfterConnection()
        gate.complete(Unit)
        pending.join()
        assertTrue(registry.search.isEmpty)
        assertEquals("", store.state.value.shell.search.query)
        assertFalse(store.state.value.shell.search.searching)
    }

    @Test fun delayedArtistSuccessAndFailureAreInvalidatedBeforePublishingTheNewSource() = runTest {
        for (fail in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val base = FakeCoreMediaProvider()
            var active: MediaProvider = object : MediaProvider by base {
                override suspend fun artistDiscography(artistId: app.naviamp.domain.ArtistId): app.naviamp.domain.media.ArtistDiscography {
                    gate.await()
                    if (fail) error("old server failed")
                    return app.naviamp.domain.media.ArtistDiscography(base.artist(artistId))
                }
            }
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store, { })
            val details = NaviampCoreMediaDetailController(store, { active }, navigation, this, mediaRegistry = registry)
            val pending = launch { details.selectArtist(SharedMediaItemUi(base.artist.id.value, "Old artist", "")) }
            runCurrent()
            details.resetForSourceChange()
            active = object : MediaProvider by base { override val cacheNamespace = "second-server" }
            gate.complete(Unit)
            pending.join()
            assertNull(registry.artistDetails)
            assertNull(store.state.value.shell.artistDetail.selectedArtist)
            assertNull(store.state.value.shell.artistDetail.status)
        }
    }

    @Test fun delayedAlbumIndexCannotReplaceAnotherSourcesRowsArtworkOrSnapshot() = runTest {
        for (fail in listOf(false, true)) {
            val base = FakeCoreMediaProvider()
            val gate = CompletableDeferred<Unit>()
            val oldAlbum = base.album.copy(title = "Old album", coverArtId = "old-cover")
            val newAlbum = base.album.copy(title = "New album", coverArtId = "new-cover")
            var active: MediaProvider = object : MediaProvider by base {
                override val cacheNamespace = "old-server"
                override suspend fun albumsPage(request: app.naviamp.domain.provider.MediaPageRequest): app.naviamp.domain.provider.MediaPage<app.naviamp.domain.Album> {
                    gate.await()
                    if (fail) error("old server failed")
                    return app.naviamp.domain.provider.MediaPage(listOf(oldAlbum), request.offset, request.limit, false)
                }
            }
            val snapshots = mutableMapOf<app.naviamp.domain.library.AlbumCatalogScope, app.naviamp.domain.library.AlbumCatalogSnapshot>()
            val repository = object : app.naviamp.domain.library.AlbumCatalogRepository {
                override fun readAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope) = snapshots[scope]
                override fun replaceAlbumCatalog(scope: app.naviamp.domain.library.AlbumCatalogScope, snapshot: app.naviamp.domain.library.AlbumCatalogSnapshot) { snapshots[scope] = snapshot }
            }
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val index = app.naviamp.domain.library.AlbumLibraryIndex(repository, { active.cacheNamespace }, { 1_000L })
            val catalog = NaviampCoreCatalogController(store, { active }, mediaRegistry = registry, albumIndex = index)
            val command = NaviampCoreCommand.Library.ChangeView(app.naviamp.ui.NaviampLibraryView.Albums)
            catalog.dispatch(command)
            val pending = launch { catalog.execute(command) }
            runCurrent()
            assertTrue(store.state.value.shell.library.albums.syncStatus.isSyncing)
            catalog.resetForSourceChange()
            active = object : MediaProvider by base {
                override val cacheNamespace = "new-server"
                override suspend fun albumsPage(request: app.naviamp.domain.provider.MediaPageRequest) =
                    app.naviamp.domain.provider.MediaPage(listOf(newAlbum), request.offset, request.limit, false)
                override fun coverArtUrl(coverArtId: String) = "https://new.example/$coverArtId"
            }
            catalog.refreshAfterConnection()
            val expected = store.state.value.shell.library
            gate.complete(Unit)
            pending.join()
            assertEquals(expected, store.state.value.shell.library)
            assertEquals(listOf(newAlbum), registry.libraryAlbums)
            assertEquals(setOf("new-server"), snapshots.keys.map { it.sourceId }.toSet())
            assertEquals(listOf(newAlbum), snapshots.values.single().albums)
            assertFalse(store.state.value.shell.library.albums.syncStatus.isSyncing)
        }
    }

    @Test fun delayedPlaylistListSuccessAndFailureCannotOverwriteTheNewServer() = runTest {
        for (fail in listOf(false, true)) {
            val base = FakeCoreMediaProvider()
            val gate = CompletableDeferred<Unit>()
            var active: MediaProvider = object : MediaProvider by base {
                override suspend fun playlists(limit: Int): List<Playlist> {
                    gate.await()
                    if (fail) error("old server failed")
                    return base.playlists(limit)
                }
            }
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store, { })
            val playlists = NaviampCorePlaylistBrowseController(store, { active }, navigation, mediaRegistry = registry)
            val pending = launch { playlists.refreshAfterConnection() }
            runCurrent()
            playlists.resetForSourceChange()
            active = object : MediaProvider by base {
                override val cacheNamespace = "new-server"
                override suspend fun playlists(limit: Int) = listOf(Playlist("new", "New playlist", 0))
            }
            playlists.refreshAfterConnection()
            val expected = store.state.value.shell.playlists
            gate.complete(Unit)
            pending.join()
            assertEquals(expected, store.state.value.shell.playlists)
            assertEquals(listOf("new"), registry.playlists.map { it.id })
            assertFalse(store.state.value.shell.playlists.refreshing)
        }
    }

    @Test fun queuedMediaSelectionsCannotRunAgainstAReplacementSource() = runTest {
        val item = SharedMediaItemUi("queued", "Queued", "")
        val requests = listOf(
            item.albumActionRequest(NaviampArtistAlbumCommand.Select),
            item.artistActionRequest(NaviampArtistMediaCommand.Select),
            item.playlistActionRequest(NaviampPlaylistMediaCommand.Select),
        )
        for (request in requests) {
            val base = FakeCoreMediaProvider()
            var providerReads = 0
            var active: MediaProvider = base
            val store = NaviampCoreStateStore()
            val registry = NaviampCoreMediaRegistry()
            lateinit var router: NaviampCoreCommandRouter
            lateinit var details: NaviampCoreMediaDetailController
            lateinit var playlists: NaviampCorePlaylistBrowseController
            val navigation = NaviampCoreNavigationController(
                NaviampNavigationController(), store,
                restoreCommand = { router.dispatch(it) },
                onPageChanging = {
                    details.invalidateNavigation()
                    playlists.invalidateNavigation()
                },
            )
            details = NaviampCoreMediaDetailController(store, { active }, navigation, backgroundScope, mediaRegistry = registry)
            playlists = NaviampCorePlaylistBrowseController(store, { active }, navigation, mediaRegistry = registry)
            router = NaviampCoreCommandRouter(backgroundScope, listOf(navigation, details, playlists))
            router.dispatch(NaviampCoreCommand.Media.ItemAction(request))
            details.resetForSourceChange()
            playlists.resetForSourceChange()
            navigation.resetForSourceChange()
            active = object : MediaProvider by base {
                override val cacheNamespace = "replacement-server"
                override suspend fun album(albumId: AlbumId): AlbumDetails {
                    providerReads++
                    return base.album(albumId)
                }
                override suspend fun artistDiscography(artistId: app.naviamp.domain.ArtistId): app.naviamp.domain.media.ArtistDiscography {
                    providerReads++
                    return base.artistDiscography(artistId)
                }
                override suspend fun playlistTracks(playlistId: String): List<app.naviamp.domain.Track> {
                    providerReads++
                    return base.playlistTracks(playlistId)
                }
            }
            runCurrent()

            assertEquals(0, providerReads)
            assertNull(registry.albumDetails)
            assertNull(registry.artistDetails)
            assertNull(registry.selectedPlaylist)
            assertNull(store.state.value.shell.albumDetail.selectedAlbum)
            assertNull(store.state.value.shell.artistDetail.selectedArtist)
            assertNull(store.state.value.shell.playlistDetail.selectedPlaylist)
            assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
        }
    }

    @Test fun delayedAlbumDoesNotPublishToAnotherProvider() = runTest {
        val base = FakeCoreMediaProvider()
        val gate = CompletableDeferred<Unit>()
        var active: MediaProvider = object : MediaProvider by base {
            override suspend fun album(albumId: AlbumId): AlbumDetails {
                gate.await()
                return base.album(albumId)
            }
        }
        val store = NaviampCoreStateStore()
        val registry = NaviampCoreMediaRegistry()
        val navigation = NaviampCoreNavigationController(NaviampNavigationController(), store, { })
        val details = NaviampCoreMediaDetailController(store, { active }, navigation, this, mediaRegistry = registry)
        val pending = launch { details.execute(NaviampCoreCommand.Media.ItemAction(
            SharedMediaItemUi(base.album.id.value, "Old album", "").albumActionRequest(NaviampArtistAlbumCommand.Select),
        )) }
        runCurrent()
        active = object : MediaProvider by base { override val cacheNamespace = "second-server" }
        gate.complete(Unit)
        pending.join()
        assertNull(registry.albumDetails)
        assertNull(store.state.value.shell.albumDetail.detail)
    }
}
