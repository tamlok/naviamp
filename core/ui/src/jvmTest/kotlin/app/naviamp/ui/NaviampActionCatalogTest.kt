package app.naviamp.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NaviampActionCatalogTest {
    @Test
    fun currentTrackMenuOffersRadioOnlyForAnEligibleTrack() {
        fun actions(available: Boolean, live: Boolean = false) = nowPlayingTrackMenuActions(
            visualizerAvailable = false, isLive = live, hasDetails = false,
            canAddToPlaylist = false, canSaveQueueAsPlaylist = false, canEmptyQueue = false,
            sleepTimer = NaviampSleepTimerUi(), canStartRadio = available,
        ).map { it.action }
        assertEquals(true, NaviampAction.StartTrackRadio in actions(true))
        assertEquals(false, NaviampAction.StartTrackRadio in actions(false))
        assertEquals(false, NaviampAction.StartTrackRadio in actions(true, live = true))
    }

    @Test
    fun currentTrackMenuOffersPlaySomethingNewOnlyWhereItCanStart() {
        fun actions(available: Boolean, live: Boolean = false) = nowPlayingTrackMenuActions(
            visualizerAvailable = false, isLive = live, hasDetails = false,
            canAddToPlaylist = false, canSaveQueueAsPlaylist = false, canEmptyQueue = false,
            sleepTimer = NaviampSleepTimerUi(), canPlaySomethingNew = available,
        ).map { it.action }
        assertEquals(true, NaviampAction.PlaySomethingNew in actions(true))
        assertEquals(true, NaviampAction.PlaySomethingNew in actions(true, live = true))
        assertEquals(false, NaviampAction.PlaySomethingNew in actions(false))
    }

    @Test
    fun sharedMediaBaselineIncludesEveryCrossPlatformProductAction() {
        assertEquals(true, NaviampSharedMediaCapabilities.album.canAddToPlaylist)
        assertEquals(true, NaviampSharedMediaCapabilities.artist.canAddToQueue)
        assertEquals(true, NaviampSharedMediaCapabilities.artist.canAddToPlaylist)
        assertEquals(true, NaviampSharedMediaCapabilities.playlist.canEditSmartPlaylist)
    }

    @Test
    fun trackRowActionsIncludeRadioQueueActionsWhenRadioIsAvailable() {
        val actions = trackRowActions(
            canStartRadio = true,
            canDownload = true,
            canAddToQueue = true,
            canAddToPlaylist = true,
        )

        assertEquals(
            listOf(
                NaviampAction.PlayNext,
                NaviampAction.PlayNextTrack,
                NaviampAction.StartTrackRadio,
                NaviampAction.PlayTrackRadioNext,
                NaviampAction.AddTrackRadioToQueue,
                NaviampAction.DownloadTrack,
                NaviampAction.AddToQueue,
                NaviampAction.AddToPlaylist,
                NaviampAction.TrackDetails,
            ),
            actions.map { it.action },
        )
    }

    @Test
    fun albumRadioAppearsBeforeFavoriteWithTheSharedLabel() {
        val actions = albumRowActions(
            canStartRadio = true,
            canFavorite = true,
        )

        assertEquals(
            listOf(NaviampAction.StartAlbumRadio, NaviampAction.ToggleFavorite),
            actions.map { it.action },
        )
    }

    @Test
    fun trackRowActionsExposeAllCapabilityAwareSwipeActionsInTheMenu() {
        val actions = trackRowActions(
            canAddToQueue = true,
            canToggleFavorite = true,
            favoriteActive = true,
            hasAlbum = true,
            hasArtist = true,
            canShowDetails = false,
        )

        assertEquals(
            listOf(
                NaviampAction.PlayNext,
                NaviampAction.PlayNextTrack,
                NaviampAction.AddToQueue,
                NaviampAction.ToggleFavorite,
                NaviampAction.GoToAlbum,
                NaviampAction.GoToArtist,
            ),
            actions.map { it.action },
        )
    }

    @Test
    fun queueRowActionsReplaceTrackDetailsWithArtistAndAlbumNavigation() {
        assertEquals(
            listOf(
                NaviampAction.PlayNext,
                NaviampAction.PlayNextTrack,
                NaviampAction.StartTrackRadio,
                NaviampAction.PlayTrackRadioNext,
                NaviampAction.AddTrackRadioToQueue,
                NaviampAction.DownloadTrack,
                NaviampAction.AddToPlaylist,
                NaviampAction.GoToArtist,
                NaviampAction.GoToAlbum,
            ),
            queueRowActions().map { it.action },
        )
    }

    @Test
    fun playlistRowActionsIncludeOnlyAvailablePlaylistActionsInMenuOrder() {
        val actions = playlistRowActions(
            canDownload = true,
            canAddToQueue = true,
            canAddToPlaylist = true,
            canRename = true,
            canEditSmartPlaylist = true,
            canDuplicateSmartPlaylist = true,
            canDelete = true,
        )

        assertEquals(
            listOf(
                NaviampAction.DownloadPlaylist,
                NaviampAction.AddToQueue,
                NaviampAction.AddPlaylistToPlaylist,
                NaviampAction.RenamePlaylist,
                NaviampAction.EditSmartPlaylist,
                NaviampAction.DuplicateSmartPlaylist,
                NaviampAction.DeletePlaylist,
            ),
            actions.map { it.action },
        )
    }

    @Test
    fun playlistRowActionsOmitUnavailableSmartPlaylistAction() {
        val actions = playlistRowActions(canRename = true, canDelete = true)

        assertEquals(
            listOf(NaviampAction.RenamePlaylist, NaviampAction.DeletePlaylist),
            actions.map { it.action },
        )
    }

}
