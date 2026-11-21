package app.naviamp.presentation

import app.naviamp.ui.NaviampAlbumDetailScreenUi
import app.naviamp.ui.NaviampArtistDetailScreenUi
import app.naviamp.ui.NaviampPlaylistDetailScreenUi
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.SharedHomeCollectionPageUi
import app.naviamp.ui.SharedHomeCollectionSectionUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaviampCoreSystemBackPolicyTest {
    @Test fun playerDetailsConsumeBackBeforeTheUnderlyingDiscoveryPage() {
        val initial = NaviampCoreState()
        val discovery = app.naviamp.ui.SharedProviderDiscoveryUi("aurral", browser = app.naviamp.domain.provider.DiscoveryBrowserState())
        val page = initial.copy(shell = initial.shell.copy(home = initial.shell.home.copy(providerDiscovery = discovery),
            shellChrome = initial.shell.shellChrome.copy(nowPlayingOpen = true)))
        assertEquals(NaviampCoreCommand.Navigation.CloseNowPlaying, page.systemBackCommand())
        val closed = page.copy(shell = page.shell.copy(shellChrome = page.shell.shellChrome.copy(nowPlayingOpen = false)))
        assertEquals(NaviampCoreCommand.Home.Browse(app.naviamp.domain.provider.DiscoveryBrowseAction.Close), closed.systemBackCommand())
    }
    @Test fun requestsPageConsumesBackBeforeTheUnderlyingAurralPage() {
        val initial = NaviampCoreState()
        val discovery = app.naviamp.ui.SharedProviderDiscoveryUi("aurral", requestsOpen = true,
            browser = app.naviamp.domain.provider.DiscoveryBrowserState(page = app.naviamp.domain.provider.DiscoveryBrowsePage.Hub))
        val page = initial.copy(shell = initial.shell.copy(home = initial.shell.home.copy(providerDiscovery = discovery)))
        assertEquals(NaviampCoreCommand.Home.DismissDiscoveryRequests, page.systemBackCommand())
    }
    @Test fun discoveryPageConsumesBackOnlyWhileItsHomeDestinationIsVisible() {
        val initial = NaviampCoreState()
        val discovery = app.naviamp.ui.SharedProviderDiscoveryUi("aurral", artistPage = true,
            artistDetail = app.naviamp.ui.SharedProviderDiscoveryArtistUi(app.naviamp.domain.provider.ProviderDiscoveryArtist("artist", "Artist")))
        val page = initial.copy(shell = initial.shell.copy(home = initial.shell.home.copy(providerDiscovery = discovery)))
        assertEquals(NaviampCoreCommand.Home.DismissDiscoveryArtist, page.systemBackCommand())
        assertNull(page.copy(shell = page.shell.copy(shellChrome = page.shell.shellChrome.copy(selectedRoute = app.naviamp.ui.SharedRoute.Search))).systemBackCommand())
    }
    @Test
    fun manuallyRenderedDetailsUseTheGenericPageBackCommand() {
        assertEquals(
            NaviampCoreCommand.Navigation.Back,
            stateWithPlaylist().systemBackCommand(),
        )
        assertEquals(
            NaviampCoreCommand.Navigation.Back,
            stateWithAlbum().systemBackCommand(),
        )
        assertEquals(
            NaviampCoreCommand.Navigation.Back,
            stateWithArtist().systemBackCommand(),
        )
    }

    @Test
    fun overlaysCloseBeforeUnderlyingDetails() {
        val detail = stateWithPlaylist()

        assertEquals(
            NaviampCoreCommand.Settings.CloseStats,
            detail.copy(overlays = NaviampCoreOverlayState(statsForNerdsVisible = true)).systemBackCommand(),
        )
        assertEquals(
            NaviampCoreCommand.Navigation.CloseNowPlaying,
            detail.copy(
                shell = detail.shell.copy(
                    shellChrome = detail.shell.shellChrome.copy(nowPlayingOpen = true),
                ),
            ).systemBackCommand(),
        )
    }

    @Test
    fun rootScreenLetsTheHostHandleBack() {
        assertNull(NaviampCoreState().systemBackCommand())
    }

    @Test
    fun nestedRoutesConsumeBackOnlyWhenPageHistoryExists() {
        val initial = NaviampCoreState()
        listOf(app.naviamp.ui.SharedRoute.Search, app.naviamp.ui.SharedRoute.ArtistMix,
            app.naviamp.ui.SharedRoute.AlbumMix, app.naviamp.ui.SharedRoute.GenreMix,
            app.naviamp.ui.SharedRoute.SonicPath, app.naviamp.ui.SharedRoute.SonicMix).forEach { route ->
            val page = initial.copy(shell = initial.shell.copy(
                shellChrome = initial.shell.shellChrome.copy(selectedRoute = route, canNavigateBack = true)))
            assertEquals(NaviampCoreCommand.Navigation.Back, page.systemBackCommand(), route.name)
            assertNull(page.copy(shell = page.shell.copy(
                shellChrome = page.shell.shellChrome.copy(canNavigateBack = false))).systemBackCommand(), route.name)
        }
    }

    @Test
    fun visibleMediaConsumesBackBeforeRetainedHomePages() {
        val discovery = app.naviamp.ui.SharedProviderDiscoveryUi("aurral", requestsOpen = true,
            artistPage = true,
            artistDetail = app.naviamp.ui.SharedProviderDiscoveryArtistUi(
                app.naviamp.domain.provider.ProviderDiscoveryArtist("artist", "Artist")),
            browser = app.naviamp.domain.provider.DiscoveryBrowserState())
        listOf(stateWithAlbum(), stateWithArtist(), stateWithPlaylist()).forEach { detail ->
            val page = detail.copy(shell = detail.shell.copy(home = detail.shell.home.copy(
                providerDiscovery = discovery,
                collectionPage = SharedHomeCollectionPageUi(
                    SharedHomeCollectionSectionUi("section", "SECTION", emptyList())))))
            assertEquals(NaviampCoreCommand.Navigation.Back, page.systemBackCommand())
        }
    }

    @Test
    fun homeCollectionPageUsesItsCoreBackCommand() {
        val state = NaviampCoreState().let { initial ->
            initial.copy(
                shell = initial.shell.copy(
                    home = initial.shell.home.copy(
                        collectionPage = SharedHomeCollectionPageUi(
                            SharedHomeCollectionSectionUi("section", "SECTION", emptyList()),
                        ),
                    ),
                ),
            )
        }

        assertEquals(NaviampCoreCommand.Home.CloseCollection, state.systemBackCommand())
    }

    private fun stateWithPlaylist() = NaviampCoreState(
        shell = NaviampCoreState().shell.copy(
            playlistDetail = NaviampPlaylistDetailScreenUi(selectedPlaylist = item("playlist")),
        ),
    )

    private fun stateWithAlbum() = NaviampCoreState(
        shell = NaviampCoreState().shell.copy(
            albumDetail = NaviampAlbumDetailScreenUi(selectedAlbum = item("album")),
        ),
    )

    private fun stateWithArtist() = NaviampCoreState(
        shell = NaviampCoreState().shell.copy(
            artistDetail = NaviampArtistDetailScreenUi(selectedArtist = item("artist")),
        ),
    )

    private fun item(id: String) = SharedMediaItemUi(id = id, title = id, subtitle = "")
}
