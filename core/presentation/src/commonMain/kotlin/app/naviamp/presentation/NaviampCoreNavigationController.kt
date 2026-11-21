package app.naviamp.presentation

import app.naviamp.app.NaviampNavigationController
import app.naviamp.app.NaviampNavigationPage
import app.naviamp.app.NaviampRoutePage
import app.naviamp.domain.app.NaviampRoute
import app.naviamp.ui.NaviampAlbumDetailScreenUi
import app.naviamp.ui.NaviampArtistDetailScreenUi
import app.naviamp.ui.NaviampPlaylistDetailScreenUi
import app.naviamp.ui.NaviampMediaItemActionRequest
import app.naviamp.ui.NaviampMediaItemCommand
import app.naviamp.ui.NaviampArtistAlbumCommand
import app.naviamp.ui.NaviampArtistMediaCommand
import app.naviamp.ui.NaviampPlaylistMediaCommand
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.toNaviampRoute
import app.naviamp.ui.toSharedRoute

data class NaviampCoreMediaPage(override val route: NaviampRoute, val item: SharedMediaItemUi) : NaviampNavigationPage {
    override fun sameDestination(other: NaviampNavigationPage): Boolean =
        other is NaviampCoreMediaPage && route == other.route && item.id == other.item.id
}

data class NaviampCoreHomeCollectionPage(val sectionId: String) : NaviampNavigationPage {
    override val route: NaviampRoute get() = NaviampRoute.Home
}

data class NaviampCoreDiscoveryPage(val sourceId: String?, val sourceGeneration: Long) : NaviampNavigationPage {
    override val route: NaviampRoute get() = NaviampRoute.Home
}

/** Owns page history and shell publication; feature controllers restore their own data. */
class NaviampCoreNavigationController(
    private val navigation: NaviampNavigationController,
    private val stateStore: NaviampCoreStateStore,
    private val restoreCommand: (NaviampCoreCommand) -> Unit,
    private val persistNowPlayingOpen: (Boolean) -> Unit = {},
    private val onPageChanging: () -> Unit = {},
    private val onResetDiscovery: () -> Unit = {},
) : NaviampCoreCommandController {
    val epoch: Long get() = navigation.epoch

    init { publishNavigation(clearDetails = false, nowPlayingOpen = navigation.currentEntry.reopenNowPlaying) }

    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult {
        val request = command as? NaviampCoreCommand.Navigation ?: return NaviampCoreImmediateCommandResult.Unhandled
        when (request) {
            is NaviampCoreCommand.Navigation.SelectRoute -> selectRoute(request.route.toNaviampRoute())
            is NaviampCoreCommand.Navigation.ReplaceRoute -> {
                onPageChanging()
                navigation.replaceRoute(request.route.toNaviampRoute())
                publishNavigation(clearDetails = false, nowPlayingOpen = stateStore.state.value.shell.shellChrome.nowPlayingOpen)
            }
            NaviampCoreCommand.Navigation.OpenNowPlaying -> openNowPlaying()
            NaviampCoreCommand.Navigation.CloseNowPlaying -> setNowPlayingOpen(false)
            NaviampCoreCommand.Navigation.Back -> back()
        }
        return NaviampCoreImmediateCommandResult.Handled()
    }

    fun openPage(page: NaviampNavigationPage) {
        val changing = !navigation.currentEntry.page.sameDestination(page)
        if (changing) onPageChanging()
        navigation.openPage(page, stateStore.state.value.shell.shellChrome.nowPlayingOpen)
        publishNavigation(clearDetails = changing)
        persistNowPlayingOpen(false)
    }

    fun openMediaPage(route: NaviampRoute, item: SharedMediaItemUi) {
        require(route == NaviampRoute.AlbumDetail || route == NaviampRoute.ArtistDetail || route == NaviampRoute.PlaylistDetail)
        openPage(NaviampCoreMediaPage(route, item))
    }

    fun openHomeCollection(sectionId: String) = openPage(NaviampCoreHomeCollectionPage(sectionId))

    fun openDiscoveryPage() {
        val discovery = stateStore.state.value.shell.home.providerDiscovery
        openPage(NaviampCoreDiscoveryPage(discovery?.sourceId, discovery?.sourceGeneration ?: 0))
    }

    fun closeDiscoveryPage() {
        if (navigation.currentEntry.page is NaviampCoreDiscoveryPage) back()
    }

    fun back() {
        if (!navigation.canGoBack) return
        onPageChanging()
        var entry = navigation.back() ?: return
        while (!isRestorable(entry.page)) {
            entry = navigation.back() ?: run {
                navigation.reset(NaviampRoute.Home)
                navigation.currentEntry
            }
        }
        publishNavigation(clearDetails = true)
        when (val page = entry.page) {
            is NaviampCoreMediaPage -> {
                val action = when (page.route) {
                    NaviampRoute.AlbumDetail -> NaviampMediaItemCommand.Album(NaviampArtistAlbumCommand.Select)
                    NaviampRoute.ArtistDetail -> NaviampMediaItemCommand.Artist(NaviampArtistMediaCommand.Select)
                    NaviampRoute.PlaylistDetail -> NaviampMediaItemCommand.Playlist(NaviampPlaylistMediaCommand.Select)
                    else -> error("Invalid media destination: ${page.route}")
                }
                restoreCommand(NaviampCoreCommand.Media.ItemAction(NaviampMediaItemActionRequest(page.item, action)))
            }
            is NaviampCoreHomeCollectionPage -> restoreCommand(NaviampCoreCommand.Home.OpenCollection(page.sectionId))
            is NaviampCoreDiscoveryPage, is NaviampRoutePage -> Unit
            else -> error("Unknown navigation page: $page")
        }
        setNowPlayingOpen(entry.reopenNowPlaying)
    }

    fun openNowPlaying() = setNowPlayingOpen(true)

    fun restoreNowPlayingOpen() = setNowPlayingOpen(open = true, persist = false)

    fun resetAfterDatabaseClear() = resetRoot(NaviampRoute.Settings)

    fun resetForSourceChange() = resetRoot(navigation.state.value.lastContentRoute)

    private fun selectRoute(route: NaviampRoute) {
        when (route) {
            NaviampRoute.Home, NaviampRoute.Playlists, NaviampRoute.Library, NaviampRoute.Radio,
            NaviampRoute.My, NaviampRoute.Downloads, NaviampRoute.Settings -> resetRoot(route)
            else -> openPage(NaviampRoutePage(route))
        }
    }

    private fun resetRoot(route: NaviampRoute) {
        onPageChanging()
        onResetDiscovery()
        navigation.reset(route)
        stateStore.updateShell { shell -> shell.copy(home = shell.home.copy(providerDiscovery = shell.home.providerDiscovery?.copy(
            artistDetail = null, artistPage = false, artistBackStack = emptyList(), releasePage = null,
            browser = null, requestsOpen = false,
        ))) }
        publishNavigation(clearDetails = true)
        persistNowPlayingOpen(false)
    }

    private fun isRestorable(page: NaviampNavigationPage): Boolean = when (page) {
        is NaviampCoreDiscoveryPage -> stateStore.state.value.shell.home.providerDiscovery?.let {
            it.sourceId == page.sourceId && it.sourceGeneration == page.sourceGeneration
        } == true
        is NaviampCoreHomeCollectionPage -> stateStore.state.value.shell.home.content.collectionSections.any {
            it.id == page.sectionId && it.visible
        }
        else -> true
    }

    private fun setNowPlayingOpen(open: Boolean, persist: Boolean = true) {
        stateStore.updateShell { shell -> shell.copy(shellChrome = shell.shellChrome.copy(nowPlayingOpen = open)) }
        if (persist) persistNowPlayingOpen(open)
    }

    private fun publishNavigation(clearDetails: Boolean, nowPlayingOpen: Boolean = false) {
        stateStore.updateShell { shell -> shell.copy(
            shellChrome = shell.shellChrome.copy(
                selectedRoute = navigation.currentEntry.contentRoute.toSharedRoute(),
                nowPlayingOpen = nowPlayingOpen,
                canNavigateBack = navigation.canGoBack,
            ),
            albumDetail = if (clearDetails) NaviampAlbumDetailScreenUi() else shell.albumDetail,
            artistDetail = if (clearDetails) NaviampArtistDetailScreenUi() else shell.artistDetail,
            playlistDetail = if (clearDetails) NaviampPlaylistDetailScreenUi() else shell.playlistDetail,
            home = if (clearDetails) shell.home.copy(collectionPage = null) else shell.home,
        ) }
    }
}
