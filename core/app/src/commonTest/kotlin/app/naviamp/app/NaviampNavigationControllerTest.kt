package app.naviamp.app

import app.naviamp.domain.app.NaviampNavigationState
import app.naviamp.domain.app.NaviampRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NaviampNavigationControllerTest {
    @Test fun mixedPagesReturnInVisitOrderIncludingDifferentItemsOfTheSameKind() {
        val navigation = NaviampNavigationController(NaviampNavigationState(NaviampRoute.Library))
        val pages = listOf(
            MediaPage(NaviampRoute.ArtistDetail, "first"),
            MediaPage(NaviampRoute.ArtistDetail, "second"),
            MediaPage(NaviampRoute.PlaylistDetail, "playlist"),
            MediaPage(NaviampRoute.AlbumDetail, "album"),
        )
        pages.forEach { navigation.openPage(it) }
        for (page in pages.dropLast(1).asReversed()) {
            assertEquals(page, navigation.back()?.page)
            assertEquals(page.route, navigation.state.value.route)
            assertEquals(NaviampRoute.Library, navigation.state.value.lastContentRoute)
        }
        assertEquals(NaviampRoutePage(NaviampRoute.Library), navigation.back()?.page)
        assertFalse(navigation.canGoBack)
        assertNull(navigation.back())
        assertEquals(NaviampRoute.Library, navigation.state.value.route)
    }

    @Test fun refreshingTheSameDestinationDoesNotAddAnotherBackStep() {
        val navigation = NaviampNavigationController()
        val page = MediaPage(NaviampRoute.PlaylistDetail, "playlist")
        navigation.openPage(page)
        navigation.openPage(page.copy())
        assertEquals(NaviampRoute.Home, navigation.back()?.page?.route)
        assertNull(navigation.back())
    }

    @Test fun rootResetDiscardsEveryNestedDestinationEvenWhenReselectingTheSameRoot() {
        for (route in listOf(NaviampRoute.Home, NaviampRoute.Playlists, NaviampRoute.Library,
            NaviampRoute.Radio, NaviampRoute.My, NaviampRoute.Downloads, NaviampRoute.Settings)) {
            val navigation = NaviampNavigationController(NaviampNavigationState(route))
            navigation.openPage(NaviampRoutePage(NaviampRoute.Search))
            navigation.openPage(MediaPage(NaviampRoute.AlbumDetail, "album"))
            navigation.reset(route)
            assertNull(navigation.back())
            assertEquals(NaviampNavigationState(route, route), navigation.state.value)
        }
    }

    @Test fun originRetainsPlayerVisibilityAndNestedContentSelection() {
        val navigation = NaviampNavigationController()
        navigation.openPage(NaviampRoutePage(NaviampRoute.Search))
        navigation.openPage(MediaPage(NaviampRoute.ArtistDetail, "artist"), openedFromNowPlaying = true)
        navigation.openPage(MediaPage(NaviampRoute.AlbumDetail, "album"))
        assertFalse(navigation.back()!!.reopenNowPlaying)
        val origin = navigation.back()!!
        assertEquals(NaviampRoute.Search, origin.page.route)
        assertEquals(NaviampRoute.Search, origin.contentRoute)
        assertTrue(origin.reopenNowPlaying)
        assertFalse(navigation.back()!!.reopenNowPlaying)
    }

    @Test fun replacingASurfaceRouteDoesNotAddHistoryAndProcessRestorationClearsIt() {
        val navigation = NaviampNavigationController()
        navigation.openPage(NaviampRoutePage(NaviampRoute.Search))
        navigation.replaceRoute(NaviampRoute.ArtistMix)
        assertEquals(NaviampRoute.Home, navigation.back()?.page?.route)
        navigation.openPage(MediaPage(NaviampRoute.PlaylistDetail, "playlist"))
        navigation.replace(NaviampNavigationState(NaviampRoute.Player, NaviampRoute.Radio))
        assertEquals(NaviampNavigationState(NaviampRoute.Radio, NaviampRoute.Radio), navigation.state.value)
        assertTrue(navigation.currentEntry.reopenNowPlaying)
        assertNull(navigation.back())
    }

    private data class MediaPage(override val route: NaviampRoute, val id: String) : NaviampNavigationPage
}
