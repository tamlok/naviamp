package app.naviamp.presentation

import app.naviamp.domain.settings.QuickAccessKind
import app.naviamp.domain.settings.withQuickAccessPin
import app.naviamp.domain.settings.withoutQuickAccessPin
import app.naviamp.ui.NaviampArtistAlbumCommand
import app.naviamp.ui.NaviampArtistMediaCommand
import app.naviamp.ui.NaviampLibraryView
import app.naviamp.ui.NaviampMediaItemActionRequest
import app.naviamp.ui.NaviampMediaItemCommand
import app.naviamp.ui.NaviampPlaylistMediaCommand
import app.naviamp.ui.SharedMediaItemUi
import app.naviamp.ui.SharedRoute

/** Source-scoped shortcuts reuse the normal settings and detail-loading owners. */
internal class NaviampCoreQuickAccessController(
    private val stateStore: NaviampCoreStateStore,
    private val send: (NaviampCoreCommand) -> Unit,
) : NaviampCoreCommandController {
    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult {
        val request = command as? NaviampCoreCommand.QuickAccess
            ?: return NaviampCoreImmediateCommandResult.Unhandled
        val shell = stateStore.state.value.shell
        val pin = request.pin.normalized()
            ?: return NaviampCoreImmediateCommandResult.Handled()
        // A menu or button from the previous account must never act in the new account.
        if (pin.sourceId != shell.connectionSettings.currentSourceId) return NaviampCoreImmediateCommandResult.Handled()
        val settings = shell.general.interfaceSettings
        when (request) {
            is NaviampCoreCommand.QuickAccess.Pin -> {
                val updated = settings.withQuickAccessPin(pin)
                if (updated != settings) send(NaviampCoreCommand.Settings.ChangeInterface(updated))
            }
            is NaviampCoreCommand.QuickAccess.Remove -> {
                val updated = settings.withoutQuickAccessPin(pin)
                if (updated != settings) send(NaviampCoreCommand.Settings.ChangeInterface(updated))
            }
            is NaviampCoreCommand.QuickAccess.Open -> {
                val saved = settings.quickAccess.firstOrNull { it.sameTarget(pin) }
                    ?: return NaviampCoreImmediateCommandResult.Handled()
                val root = if (saved.kind == QuickAccessKind.Playlist) SharedRoute.Playlists else SharedRoute.Library
                send(NaviampCoreCommand.Navigation.SelectRoute(root))
                when (saved.kind) {
                    QuickAccessKind.Album -> send(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Albums))
                    QuickAccessKind.Artist -> send(NaviampCoreCommand.Library.ChangeView(NaviampLibraryView.Artists))
                    QuickAccessKind.Playlist -> Unit
                }
                val selection = when (saved.kind) {
                    QuickAccessKind.Playlist -> NaviampMediaItemCommand.Playlist(NaviampPlaylistMediaCommand.Select)
                    QuickAccessKind.Album -> NaviampMediaItemCommand.Album(NaviampArtistAlbumCommand.Select)
                    QuickAccessKind.Artist -> NaviampMediaItemCommand.Artist(NaviampArtistMediaCommand.Select)
                }
                send(NaviampCoreCommand.Media.ItemAction(NaviampMediaItemActionRequest(
                    SharedMediaItemUi(saved.itemId, saved.title, ""), selection,
                )))
            }
        }
        return NaviampCoreImmediateCommandResult.Handled()
    }
}
