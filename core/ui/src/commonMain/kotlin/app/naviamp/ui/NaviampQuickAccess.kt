package app.naviamp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import app.naviamp.domain.settings.QuickAccessKind
import app.naviamp.domain.settings.QuickAccessPin
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.quick_access_pin
import app.naviamp.ui.generated.resources.quick_access_remove
import org.jetbrains.compose.resources.stringResource

data class NaviampQuickAccessActions(
    val onPin: (QuickAccessPin) -> Unit,
    val onRemove: (QuickAccessPin) -> Unit,
    val onOpen: (QuickAccessPin) -> Unit,
)

internal data class NaviampQuickAccessUi(
    val sourceId: String?,
    val pins: List<QuickAccessPin>,
    val selected: QuickAccessPin?,
    val actions: NaviampQuickAccessActions,
)

internal val LocalNaviampQuickAccess = staticCompositionLocalOf<NaviampQuickAccessUi?> { null }

@Composable
internal fun rememberNaviampQuickAccess(
    shell: NaviampAppShellUiState,
    actions: NaviampQuickAccessActions?,
): NaviampQuickAccessUi? {
    val sourceId = shell.connectionSettings.currentSourceId
    val saved = shell.general.interfaceSettings.quickAccess
    val pins = remember(sourceId, saved) { saved.filter { it.sourceId == sourceId } }
    // Match the visible detail precedence, including an album opened from an artist.
    val selected = when {
        shell.albumDetail.selectedAlbum != null -> QuickAccessKind.Album to shell.albumDetail.selectedAlbum.id
        shell.artistDetail.selectedArtist != null -> QuickAccessKind.Artist to shell.artistDetail.selectedArtist.id
        shell.playlistDetail.selectedPlaylist != null -> QuickAccessKind.Playlist to shell.playlistDetail.selectedPlaylist.id
        else -> null
    }?.let { (kind, id) -> pins.firstOrNull { it.kind == kind && it.itemId == id } }
    return actions?.let { NaviampQuickAccessUi(sourceId, pins, selected, it) }
}

@Composable
internal fun naviampQuickAccessAction(item: SharedMediaItemUi, kind: QuickAccessKind): NaviampDetailAction? {
    val quickAccess = LocalNaviampQuickAccess.current ?: return null
    val sourceId = quickAccess.sourceId ?: return null
    if (item.id.isBlank() || item.title.isBlank()) return null
    val pin = QuickAccessPin(sourceId, kind, item.id, item.title)
    val existing = quickAccess.pins.firstOrNull { it.kind == kind && it.itemId == item.id }
    return NaviampDetailAction(
        label = stringResource(if (existing == null) Res.string.quick_access_pin else Res.string.quick_access_remove),
        icon = NaviampIcons.Pin,
        onClick = { if (existing == null) quickAccess.actions.onPin(pin) else quickAccess.actions.onRemove(existing) },
        selected = existing != null,
    )
}

internal val QuickAccessKind.icon get() = when (this) {
    QuickAccessKind.Playlist -> NaviampIcons.Playlist
    QuickAccessKind.Album -> NaviampIcons.Album
    QuickAccessKind.Artist -> NaviampIcons.Artist
}

internal val QuickAccessPin.navigationKey: String get() = "$sourceId:${kind.name}:$itemId"
