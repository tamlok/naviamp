package app.naviamp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaviampMainNavigationTest {
    @Test
    fun placementChangesAtTheSafeViewportBoundaries() {
        val cases = listOf(
            Triple(1280f, 720f, NaviampNavigationPlacement.Rail),
            Triple(412f, 818f, NaviampNavigationPlacement.Bottom),
            Triple(320f, 640f, NaviampNavigationPlacement.Drawer),
            Triple(640f, 360f, NaviampNavigationPlacement.Drawer),
            Triple(599f, 500f, NaviampNavigationPlacement.Drawer),
            Triple(600f, 500f, NaviampNavigationPlacement.Rail),
            Triple(359f, 640f, NaviampNavigationPlacement.Drawer),
            Triple(360f, 640f, NaviampNavigationPlacement.Bottom),
            Triple(480f, 480f, NaviampNavigationPlacement.Bottom),
            Triple(600f, 479f, NaviampNavigationPlacement.Drawer),
            Triple(600f, 480f, NaviampNavigationPlacement.Rail),
            Triple(480f, 481f, NaviampNavigationPlacement.Bottom),
            Triple(481f, 480f, NaviampNavigationPlacement.Drawer),
        )
        cases.forEach { (width, height, expected) ->
            assertEquals(expected, naviampNavigationPlacement(width, height), "$width × $height")
        }
    }

    @Test
    fun personalAndDiscoveryRoutesKeepTheirOwningDestinationSelected() {
        listOf(SharedRoute.Settings, SharedRoute.Downloads).forEach {
            assertEquals(SharedRoute.My, naviampMainNavigationSelection(it))
        }
        listOf(SharedRoute.ArtistMix, SharedRoute.AlbumMix, SharedRoute.GenreMix,
            SharedRoute.SonicPath, SharedRoute.SonicMix).forEach {
            assertEquals(SharedRoute.Home, naviampMainNavigationSelection(it))
        }
        assertNull(naviampMainNavigationSelection(SharedRoute.Search))
    }

    @Test
    fun primarySelectionTracksTheActualDestination() {
        listOf(SharedRoute.Home, SharedRoute.Playlists, SharedRoute.Library, SharedRoute.Radio, SharedRoute.My).forEach {
            assertEquals(it, naviampMainNavigationSelection(it))
        }
    }

    @Test
    fun phoneBackFollowsTheVisibleHomePageBeforeGenericHistory() {
        var shell = NaviampAppShellUiState(
            shellChrome = NaviampShellChromeUi(canNavigateBack = true),
            home = NaviampHomeScreenUi(
                collectionPage = SharedHomeCollectionPageUi(SharedHomeCollectionSectionUi("collection", "Collection", emptyList())),
                providerDiscovery = SharedProviderDiscoveryUi("aurral", requestsOpen = true, artistPage = true,
                    artistDetail = SharedProviderDiscoveryArtistUi(app.naviamp.domain.provider.ProviderDiscoveryArtist("artist", "Artist")),
                    browser = app.naviamp.domain.provider.DiscoveryBrowserState()),
            ),
        )
        assertEquals(NaviampBackTarget.DiscoveryRequests, naviampBackTarget(shell))
        shell = shell.copy(home = shell.home.copy(providerDiscovery = shell.home.providerDiscovery?.copy(requestsOpen = false)))
        assertEquals(NaviampBackTarget.DiscoveryArtist, naviampBackTarget(shell))
        shell = shell.copy(home = shell.home.copy(providerDiscovery = shell.home.providerDiscovery?.copy(artistPage = false)))
        assertEquals(NaviampBackTarget.DiscoveryBrowser, naviampBackTarget(shell))
        shell = shell.copy(home = shell.home.copy(providerDiscovery = shell.home.providerDiscovery?.copy(browser = null)))
        assertEquals(NaviampBackTarget.HomeCollection, naviampBackTarget(shell))
        shell = shell.copy(home = shell.home.copy(collectionPage = null))
        assertEquals(NaviampBackTarget.Page, naviampBackTarget(shell))
        shell = shell.copy(shellChrome = shell.shellChrome.copy(canNavigateBack = false))
        assertNull(naviampBackTarget(shell))
    }
}
