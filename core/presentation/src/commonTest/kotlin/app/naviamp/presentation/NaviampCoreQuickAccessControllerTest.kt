package app.naviamp.presentation

import app.naviamp.app.NaviampConnectionPhase
import app.naviamp.app.NaviampConnectionRuntimeState
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.QuickAccessKind
import app.naviamp.domain.settings.QuickAccessPin
import app.naviamp.ui.NaviampLibraryView
import app.naviamp.ui.SharedRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NaviampCoreQuickAccessControllerTest {
    @Test
    fun shortcutsLoadAllMediaKindsUnderTheirOwnersAndBackReturnsToTheOwnerWithoutPlayback() = runTest {
        val provider = FakeCoreMediaProvider()
        val core = NaviampCore.create(backgroundScope, fakeCoreServices(provider), initialState())
        try {
            val actions = assertNotNull(core.actions.shell.quickAccessActions)
            val pins = listOf(
                QuickAccessPin("source", QuickAccessKind.Playlist, provider.playlist.id, provider.playlist.name),
                QuickAccessPin("source", QuickAccessKind.Album, provider.album.id.value, provider.album.title),
                QuickAccessPin("source", QuickAccessKind.Artist, provider.artist.id.value, provider.artist.name),
            )
            for (pin in pins) {
                actions.onPin(pin)
                core.actions.shell.navigationActions.onRouteSelected(SharedRoute.Radio)
                actions.onOpen(pin)
                runCurrent()
                val shell = core.state.value.shell
                val owner = if (pin.kind == QuickAccessKind.Playlist) SharedRoute.Playlists else SharedRoute.Library
                assertEquals(owner, shell.shellChrome.selectedRoute)
                assertTrue(shell.shellChrome.canNavigateBack)
                when (pin.kind) {
                    QuickAccessKind.Playlist -> assertEquals(pin.itemId, shell.playlistDetail.detail?.playlist?.id)
                    QuickAccessKind.Album -> {
                        assertEquals(pin.itemId, shell.albumDetail.detail?.album?.id)
                        assertEquals(NaviampLibraryView.Albums, shell.library.selectedView)
                    }
                    QuickAccessKind.Artist -> {
                        assertEquals(pin.itemId, shell.artistDetail.detail?.artist?.id)
                        assertEquals(NaviampLibraryView.Artists, shell.library.selectedView)
                    }
                }
                // Reopening is still one detail over the owning root, not an accumulating stack.
                actions.onOpen(pin)
                runCurrent()
                core.actions.shell.navigationActions.onBack()
                runCurrent()
                val back = core.state.value.shell
                assertEquals(owner, back.shellChrome.selectedRoute)
                assertFalse(back.shellChrome.canNavigateBack)
                assertNull(back.albumDetail.selectedAlbum)
                assertNull(back.artistDetail.selectedArtist)
                assertNull(back.playlistDetail.selectedPlaylist)
            }
            assertTrue(provider.streamRequests.isEmpty())
        } finally { core.close() }
    }

    @Test
    fun pinChangesPersistIdempotentlyAndStaleSourceActionsCannotChangeSettingsOrNavigation() = runTest {
        val defaults = fakeCoreServices(FakeCoreMediaProvider())
        val writes = mutableListOf<InterfaceSettings>()
        val services = defaults.copy(settings = defaults.settings.copy(
            interfaceSettings = NaviampCoreInterfaceSettingsStore { writes += it },
        ))
        val core = NaviampCore.create(backgroundScope, services, initialState())
        try {
            val actions = assertNotNull(core.actions.shell.quickAccessActions)
            val pin = QuickAccessPin("source", QuickAccessKind.Playlist, "core-playlist", "Original")
            actions.onPin(pin)
            actions.onPin(pin)
            assertEquals(listOf(pin), writes.single().quickAccess)
            actions.onPin(pin.copy(title = "Renamed"))
            assertEquals(listOf(pin.copy(title = "Renamed")), writes.last().quickAccess)
            assertEquals(2, writes.size)
            core.stateStore.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "other")) }
            actions.onPin(pin.copy(itemId = "another"))
            actions.onRemove(pin)
            actions.onOpen(pin)
            assertEquals(2, writes.size)
            assertEquals(SharedRoute.Home, core.state.value.shell.shellChrome.selectedRoute)
            assertNull(core.state.value.shell.playlistDetail.selectedPlaylist)
            core.stateStore.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
            actions.onRemove(pin) // Identity, not a stale menu's title.
            actions.onRemove(pin)
            actions.onOpen(pin) // A removed shortcut cannot reopen from a stale click.
            assertEquals(3, writes.size)
            assertTrue(writes.last().quickAccess.isEmpty())
            assertEquals(SharedRoute.Home, core.state.value.shell.shellChrome.selectedRoute)
        } finally { core.close() }
    }

    @Test
    fun failedDetailKeepsItsIdentityAndCanBeUnpinned() = runTest {
        val provider = object : app.naviamp.domain.provider.MediaProvider by FakeCoreMediaProvider() {
            override suspend fun album(albumId: app.naviamp.domain.AlbumId): app.naviamp.domain.AlbumDetails = error("Deleted")
        }
        val core = NaviampCore.create(backgroundScope, fakeCoreServices(provider), initialState())
        try {
            val pin = QuickAccessPin("source", QuickAccessKind.Album, "deleted", "Deleted album")
            val actions = assertNotNull(core.actions.shell.quickAccessActions)
            actions.onPin(pin)
            actions.onOpen(pin)
            runCurrent()
            val detail = core.state.value.shell.albumDetail
            assertEquals(pin.itemId, detail.selectedAlbum?.id)
            assertNull(detail.detail)
            assertNotNull(detail.status)
            actions.onRemove(pin)
            assertTrue(core.state.value.shell.general.interfaceSettings.quickAccess.isEmpty())
        } finally { core.close() }
    }

    private fun initialState(): NaviampCoreInitialState = NaviampCoreInitialState(
        connection = NaviampConnectionRuntimeState(phase = NaviampConnectionPhase.Connected, sourceId = "source"),
        connectionInventory = NaviampCoreConnectionInventory(currentSourceId = "source"),
    )
}
