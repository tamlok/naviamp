package app.naviamp.app

import app.naviamp.domain.app.NaviampNavigationState
import app.naviamp.domain.app.NaviampRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A small destination descriptor; feature data and loading remain with their shared owners. */
interface NaviampNavigationPage {
    val route: NaviampRoute
    fun sameDestination(other: NaviampNavigationPage): Boolean = this == other
}

data class NaviampRoutePage(override val route: NaviampRoute) : NaviampNavigationPage

data class NaviampNavigationEntry(
    val page: NaviampNavigationPage,
    val contentRoute: NaviampRoute,
    val reopenNowPlaying: Boolean = false,
)

/** In-memory page history. Root selection and process-state replacement start a new history. */
class NaviampNavigationController(initialState: NaviampNavigationState = NaviampNavigationState()) {
    private val history = ArrayDeque<NaviampNavigationEntry>()
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<NaviampNavigationState> = mutableState.asStateFlow()
    var currentEntry: NaviampNavigationEntry = restoredEntry(initialState)
        private set
    var epoch: Long = 0
        private set
    val canGoBack: Boolean get() = history.isNotEmpty()

    init { publish() }

    fun openPage(page: NaviampNavigationPage, openedFromNowPlaying: Boolean = false): Boolean {
        if (currentEntry.page.sameDestination(page)) {
            currentEntry = currentEntry.copy(page = page)
            return false
        }
        history.addLast(currentEntry.copy(reopenNowPlaying = openedFromNowPlaying))
        epoch++
        val contentRoute = when (page.route) {
            NaviampRoute.AlbumDetail, NaviampRoute.ArtistDetail, NaviampRoute.PlaylistDetail -> currentEntry.contentRoute
            else -> page.route
        }
        currentEntry = NaviampNavigationEntry(page, contentRoute)
        publish()
        return true
    }

    fun back(): NaviampNavigationEntry? {
        val entry = history.removeLastOrNull() ?: return null
        epoch++
        currentEntry = entry
        publish()
        return entry
    }

    fun reset(route: NaviampRoute) {
        replace(NaviampNavigationState(route, route))
    }

    /** Replaces the active route without adding or removing history (for surface presentation). */
    fun replaceRoute(route: NaviampRoute) {
        epoch++
        currentEntry = NaviampNavigationEntry(NaviampRoutePage(route), route)
        publish()
    }

    fun replace(state: NaviampNavigationState) {
        epoch++
        history.clear()
        currentEntry = restoredEntry(state)
        publish()
    }

    private fun publish() {
        mutableState.value = NaviampNavigationState(currentEntry.page.route, currentEntry.contentRoute)
    }

    private fun restoredEntry(state: NaviampNavigationState): NaviampNavigationEntry {
        val route = if (state.route == NaviampRoute.Player) state.lastContentRoute else state.route
        val content = when (route) {
            NaviampRoute.AlbumDetail, NaviampRoute.ArtistDetail, NaviampRoute.PlaylistDetail -> state.lastContentRoute
            else -> route
        }
        return NaviampNavigationEntry(NaviampRoutePage(route), content, state.route == NaviampRoute.Player)
    }
}
