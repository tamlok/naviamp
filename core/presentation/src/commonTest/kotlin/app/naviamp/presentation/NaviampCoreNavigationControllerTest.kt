package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationController
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampNavigationState
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.ui.NaviampAlbumDetailScreenUi
import app.naviamp.ui.NaviampArtistDetailScreenUi
import app.naviamp.ui.NaviampPlaylistDetailScreenUi
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.SharedProviderDiscoveryUi
import app.naviamp.ui.SharedRoute
import app.naviamp.ui.toSharedRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaviampCoreNavigationControllerTest {
    @Test fun recoveryEntriesAreOnceOnlyAndNeverEmittedByStructuralOpenOrSourceReset() {
        val store = NaviampCoreStateStore()
        val routes = NaviampNavigationController()
        val entries = mutableListOf<NaviampNavigationPage>()
        val restores = mutableListOf<NaviampCoreCommand>()
        val controller = NaviampCoreNavigationController(routes, store, restores::add,
            onPageEntered = { page ->
                assertEquals(page.route.toSharedRoute(), store.state.value.shell.shellChrome.selectedRoute)
                entries += page
            })
        assertTrue(entries.isEmpty())
        controller.openPage(NaviampRoutePage(NaviampRoute.Search))
        assertTrue(entries.isEmpty())
        controller.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Search))
        assertEquals(listOf<NaviampNavigationPage>(NaviampRoutePage(NaviampRoute.Search)), entries)
        controller.dispatch(NaviampCoreCommand.Navigation.ReplaceRoute(SharedRoute.AlbumMix))
        assertEquals(2, entries.size)
        controller.back()
        assertEquals(NaviampRoutePage(NaviampRoute.Home), entries.last())
        assertEquals(3, entries.size)
        controller.resetForSourceChange()
        controller.resetAfterDatabaseClear()
        assertEquals(3, entries.size)
        controller.openMediaPage(NaviampRoute.AlbumDetail, item("album"))
        controller.openMediaPage(NaviampRoute.ArtistDetail, item("artist"))
        controller.back()
        assertEquals(1, restores.size)
        assertEquals(3, entries.size)
    }

    @Test fun retainedDiscoveryBackEmitsItsTypedPageExactlyOnce() {
        val store = NaviampCoreStateStore()
        val routes = NaviampNavigationController()
        val entries = mutableListOf<NaviampNavigationPage>()
        val controller = NaviampCoreNavigationController(routes, store, { error("Unexpected restore") }, onPageEntered = entries::add)
        store.updateShell { it.copy(home = it.home.copy(providerDiscovery = SharedProviderDiscoveryUi("provider", sourceId = "source"))) }
        controller.openDiscoveryPage()
        controller.openMediaPage(NaviampRoute.AlbumDetail, item("album"))
        controller.back()
        assertEquals(listOf<NaviampNavigationPage>(NaviampCoreDiscoveryPage("source", 0)), entries)
    }

    @Test fun everyRoutedChildReturnsToItsOpeningRoot() {
        for (root in roots) for (child in children) {
            val routes = NaviampNavigationController(NaviampNavigationState(root))
            val store = NaviampCoreStateStore()
            val controller = controller(routes, store)
            controller.dispatch(NaviampCoreCommand.Navigation.SelectRoute(child))
            assertEquals(child, store.state.value.shell.shellChrome.selectedRoute)
            assertTrue(store.state.value.shell.shellChrome.canNavigateBack)
            controller.back()
            assertEquals(root, routes.state.value.route)
            assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
        }
    }

    @Test fun everyMediaKindReturnsToHomeOrItsActualOpeningPage() {
        for (root in roots) for (kind in mediaKinds) {
            val routes = NaviampNavigationController(NaviampNavigationState(root))
            val store = NaviampCoreStateStore()
            val controller = controller(routes, store)
            controller.openMediaPage(kind, item("selected"))
            store.updateShell { shell -> shell.copy(
                albumDetail = NaviampAlbumDetailScreenUi(selectedAlbum = item("album")),
                artistDetail = NaviampArtistDetailScreenUi(selectedArtist = item("artist")),
                playlistDetail = NaviampPlaylistDetailScreenUi(selectedPlaylist = item("playlist")),
            ) }
            controller.dispatch(NaviampCoreCommand.Navigation.Back)
            assertEquals(root, routes.state.value.route)
            assertNull(store.state.value.shell.albumDetail.selectedAlbum)
            assertNull(store.state.value.shell.artistDetail.selectedArtist)
            assertNull(store.state.value.shell.playlistDetail.selectedPlaylist)
            assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
        }
    }

    @Test fun refreshedMediaMetadataDoesNotCreateADuplicateBackStep() {
        val store = NaviampCoreStateStore()
        val routes = NaviampNavigationController()
        val controller = controller(routes, store)
        controller.openMediaPage(NaviampRoute.PlaylistDetail, item("playlist"))
        controller.openMediaPage(NaviampRoute.PlaylistDetail, item("playlist").copy(title = "Renamed"))
        controller.back()
        assertEquals(NaviampRoute.Home, routes.state.value.route)
        assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
    }

    @Test fun rootSelectionClearsHistoryDetailsAndDiscoveryWithoutRollingBackOtherState() {
        for (root in roots) {
            val store = NaviampCoreStateStore()
            val routes = NaviampNavigationController()
            var reset = 0
            val controller = NaviampCoreNavigationController(routes, store, {}, onResetDiscovery = { reset++ })
            controller.openMediaPage(NaviampRoute.PlaylistDetail, item("playlist"))
            controller.openNowPlaying()
            store.updateShell { shell -> shell.copy(home = shell.home.copy(providerDiscovery = SharedProviderDiscoveryUi(
                "provider", artistPage = true, requestsOpen = true,
            ))) }
            val settings = store.state.value.shell.general
            val playback = store.state.value.shell.playback
            controller.dispatch(NaviampCoreCommand.Navigation.SelectRoute(root.toSharedRoute()))
            assertEquals(root, routes.state.value.route)
            assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
            assertFalse(store.state.value.shell.shellChrome.nowPlayingOpen)
            assertFalse(store.state.value.shell.home.providerDiscovery!!.artistPage)
            assertFalse(store.state.value.shell.home.providerDiscovery!!.requestsOpen)
            assertEquals(1, reset)
            assertEquals(settings, store.state.value.shell.general)
            assertEquals(playback, store.state.value.shell.playback)
            controller.back()
            assertEquals(root, routes.state.value.route)
        }
    }

    @Test fun allMediaOpenedFromPlayerReturnToPlayerOverTheSamePage() {
        for (kind in mediaKinds) {
            val routes = NaviampNavigationController(NaviampNavigationState(NaviampRoute.Library))
            val store = NaviampCoreStateStore()
            val persisted = mutableListOf<Boolean>()
            val controller = NaviampCoreNavigationController(routes, store, {}, persistNowPlayingOpen = persisted::add)
            controller.openNowPlaying()
            controller.openMediaPage(kind, item("media"))
            assertFalse(store.state.value.shell.shellChrome.nowPlayingOpen)
            controller.back()
            assertTrue(store.state.value.shell.shellChrome.nowPlayingOpen)
            assertEquals(SharedRoute.Library, store.state.value.shell.shellChrome.selectedRoute)
            assertEquals(listOf(true, false, true), persisted)
        }
    }

    @Test fun playerOverlayDoesNotConsumePageHistoryAndRestorationDoesNotPersistAgain() {
        val store = NaviampCoreStateStore()
        val persisted = mutableListOf<Boolean>()
        val controller = NaviampCoreNavigationController(NaviampNavigationController(), store, {}, persistNowPlayingOpen = persisted::add)
        controller.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Search))
        persisted.clear()
        controller.restoreNowPlayingOpen()
        assertTrue(persisted.isEmpty())
        controller.dispatch(NaviampCoreCommand.Navigation.CloseNowPlaying)
        assertEquals(SharedRoute.Search, store.state.value.shell.shellChrome.selectedRoute)
        assertTrue(store.state.value.shell.shellChrome.canNavigateBack)
        controller.back()
        assertEquals(SharedRoute.Home, store.state.value.shell.shellChrome.selectedRoute)
    }

    @Test fun discoveryBoundaryReturnsToRadioAndRepeatedEntryDoesNotPushAgain() {
        val store = NaviampCoreStateStore()
        val routes = NaviampNavigationController(NaviampNavigationState(NaviampRoute.Radio))
        val controller = controller(routes, store)
        store.updateShell { it.copy(home = it.home.copy(providerDiscovery = SharedProviderDiscoveryUi("provider", sourceId = "source"))) }
        controller.openDiscoveryPage()
        controller.openDiscoveryPage()
        assertEquals(SharedRoute.Home, store.state.value.shell.shellChrome.selectedRoute)
        controller.closeDiscoveryPage()
        assertEquals(SharedRoute.Radio, store.state.value.shell.shellChrome.selectedRoute)
        assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
    }

    @Test fun staleDiscoveryOriginIsSkippedWithoutReopeningAnotherSource() {
        val store = NaviampCoreStateStore()
        val routes = NaviampNavigationController(NaviampNavigationState(NaviampRoute.Radio))
        val controller = controller(routes, store)
        store.updateShell { it.copy(home = it.home.copy(providerDiscovery = SharedProviderDiscoveryUi("provider", sourceId = "old"))) }
        controller.openDiscoveryPage()
        controller.openMediaPage(NaviampRoute.AlbumDetail, item("album"))
        store.updateShell { it.copy(home = it.home.copy(providerDiscovery = SharedProviderDiscoveryUi("provider", sourceId = "new"))) }
        controller.back()
        assertEquals(SharedRoute.Radio, store.state.value.shell.shellChrome.selectedRoute)
        assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
    }

    @Test fun sourceChangeDropsOldMediaOrigins() {
        val store = NaviampCoreStateStore()
        val routes = NaviampNavigationController()
        val controller = controller(routes, store)
        controller.openMediaPage(NaviampRoute.ArtistDetail, item("old-artist"))
        controller.openMediaPage(NaviampRoute.AlbumDetail, item("old-album"))
        controller.resetForSourceChange()
        controller.back()
        assertEquals(SharedRoute.Home, store.state.value.shell.shellChrome.selectedRoute)
        assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
    }

    @Test fun replacingTvSettingsBackgroundDoesNotStackSettingsBehindSearchOrMixes() {
        for (background in children) {
            val store = NaviampCoreStateStore()
            val controller = controller(NaviampNavigationController(), store)
            controller.dispatch(NaviampCoreCommand.Navigation.SelectRoute(background))
            controller.dispatch(NaviampCoreCommand.Navigation.SelectRoute(SharedRoute.Settings))
            controller.dispatch(NaviampCoreCommand.Navigation.ReplaceRoute(background))
            controller.back()
            assertEquals(background, store.state.value.shell.shellChrome.selectedRoute)
            assertFalse(store.state.value.shell.shellChrome.canNavigateBack)
        }
    }

    private fun controller(routes: NaviampNavigationController, store: NaviampCoreStateStore) =
        NaviampCoreNavigationController(routes, store, { error("Unexpected data restoration: $it") })

    private fun item(id: String) = SharedMediaItemUi(id, id, "")
    private val roots = listOf(NaviampRoute.Home, NaviampRoute.Playlists, NaviampRoute.Library,
        NaviampRoute.Radio, NaviampRoute.My, NaviampRoute.Downloads, NaviampRoute.Settings)
    private val children = listOf(SharedRoute.Search, SharedRoute.ArtistMix, SharedRoute.AlbumMix,
        SharedRoute.GenreMix, SharedRoute.SonicPath, SharedRoute.SonicMix)
    private val mediaKinds = listOf(NaviampRoute.AlbumDetail, NaviampRoute.ArtistDetail, NaviampRoute.PlaylistDetail)
}
