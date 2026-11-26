package app.naviamp.ui

import app.naviamp.ui.generated.resources.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import org.jetbrains.compose.resources.stringResource

suspend fun playlistStaleDraftMessage(): String =
    naviampString(Res.string.playlist_item_stale_draft)

internal enum class PlaylistTrackEditAction { MoveToTop, MoveToBottom, MoveToPosition, Remove }

internal data class PlaylistTrackEditActionSpec(val action: PlaylistTrackEditAction, val enabled: Boolean)

internal fun playlistTrackEditActions(
    canEdit: Boolean,
    index: Int,
    trackCount: Int,
    busy: Boolean = false,
): List<PlaylistTrackEditActionSpec> = if (!canEdit || index !in 0 until trackCount) emptyList() else listOf(
    PlaylistTrackEditActionSpec(PlaylistTrackEditAction.MoveToTop, !busy && index > 0),
    PlaylistTrackEditActionSpec(PlaylistTrackEditAction.MoveToBottom, !busy && index < trackCount - 1),
    PlaylistTrackEditActionSpec(PlaylistTrackEditAction.MoveToPosition, !busy && trackCount > 1),
    PlaylistTrackEditActionSpec(PlaylistTrackEditAction.Remove, !busy),
)

/** Positions are one-based in the dialog; edits always identify a specific list occurrence. */
internal fun <T> movePlaylistTrackToPosition(tracks: List<T>, index: Int, position: Int): List<T> {
    if (index !in tracks.indices || position !in 1..tracks.size || position == index + 1) return tracks
    return tracks.toMutableList().apply { add(position - 1, removeAt(index)) }
}

internal fun playlistTrackSongActions(track: SharedTrackRowUi, capabilities: PlaylistTrackCapabilities) =
    trackRowActions(
        canStartRadio = capabilities.canStartRadio,
        canDownload = capabilities.canDownload,
        canAddToQueue = capabilities.canAddToQueue,
        canAddToPlaylist = capabilities.canAddToPlaylist,
        canToggleFavorite = track.canToggleFavorite,
        favoriteActive = track.favoriteActive,
        hasAlbum = track.hasAlbum,
        hasArtist = track.hasArtist,
        canShowDetails = track.detailSections.isNotEmpty(),
    ).mapNotNull { spec ->
        when (spec.action) {
            NaviampAction.PlayTrackRadioNext, NaviampAction.AddTrackRadioToQueue -> null
            NaviampAction.AddToPlaylist -> spec.copy(labelResource = Res.string.playlist_item_add_to_playlist)
            NaviampAction.AddToQueue -> spec.copy(labelResource = Res.string.playlist_item_add_queue)
            else -> spec
        }
    }
        .sortedBy { when (it.action) {
            NaviampAction.AddToPlaylist -> 0
            NaviampAction.PlayNextTrack -> 1
            NaviampAction.PlayNext -> 2
            NaviampAction.AddToQueue -> 3
            NaviampAction.StartTrackRadio -> 4
            NaviampAction.ToggleFavorite -> 5
            NaviampAction.DownloadTrack -> 6
            NaviampAction.GoToAlbum -> 7
            NaviampAction.GoToArtist -> 8
            else -> 9
        } }

@Composable
internal fun PlaylistTrackOverflowMenu(
    colors: NaviampColors,
    track: SharedTrackRowUi,
    capabilities: PlaylistTrackCapabilities,
    onTrackAction: (SharedTrackRowActionRequest) -> Unit,
    editActions: List<PlaylistTrackEditActionSpec> = emptyList(),
    onEdit: (PlaylistTrackEditAction) -> Unit = {},
    playlistChoices: List<NaviampPlaylistChoiceUi> = emptyList(),
) {
    var detailsOpen by remember(track.id) { mutableStateOf(false) }
    var addToPlaylistOpen by remember(track.id) { mutableStateOf(false) }
    val playlistItems = editActions.map { spec ->
        val (label, icon) = when (spec.action) {
            PlaylistTrackEditAction.MoveToTop -> stringResource(Res.string.playlist_item_move_top) to NaviampIcons.MoveToTop
            PlaylistTrackEditAction.MoveToBottom -> stringResource(Res.string.playlist_item_move_bottom) to NaviampIcons.MoveToBottom
            PlaylistTrackEditAction.MoveToPosition -> stringResource(Res.string.playlist_item_move_position) to NaviampIcons.Edit
            PlaylistTrackEditAction.Remove -> stringResource(Res.string.playlist_item_remove) to NaviampIcons.Trash
        }
        NaviampRowMenuItem(label, icon, { onEdit(spec.action) }, spec.enabled)
    }
    val songItems = playlistTrackSongActions(track, capabilities).map { spec ->
        val action = when (spec.action) {
            NaviampAction.AddToPlaylist -> SharedTrackRowAction.AddToPlaylist
            NaviampAction.PlayNextTrack -> SharedTrackRowAction.PlayNextTrack
            NaviampAction.PlayNext -> SharedTrackRowAction.PlayNext
            NaviampAction.AddToQueue -> SharedTrackRowAction.AddToQueue
            NaviampAction.StartTrackRadio -> SharedTrackRowAction.StartRadio
            NaviampAction.ToggleFavorite -> SharedTrackRowAction.ToggleFavorite
            NaviampAction.DownloadTrack -> SharedTrackRowAction.Download
            NaviampAction.GoToAlbum -> SharedTrackRowAction.GoToAlbum
            NaviampAction.GoToArtist -> SharedTrackRowAction.GoToArtist
            else -> null
        }
        NaviampRowMenuItem(
            label = spec.localizedLabel(),
            icon = if (spec.action == NaviampAction.ToggleFavorite && track.favoriteActive) NaviampTransportIcons.HeartFilled else spec.icon,
            onClick = {
                when (action) {
                    null -> detailsOpen = true
                    SharedTrackRowAction.AddToPlaylist -> addToPlaylistOpen = true
                    else -> onTrackAction(SharedTrackRowActionRequest(track, action))
                }
            },
        ) to (spec.action == NaviampAction.AddToPlaylist)
    }
    val firstSection = playlistItems + songItems.filter { it.second }.map { it.first }
    val remaining = songItems.filterNot { it.second }.mapIndexed { index, pair ->
        pair.first.copy(dividerBefore = index == 0 && firstSection.isNotEmpty())
    }
    NaviampRowOverflowMenu(colors, firstSection + remaining,
        contentDescription = stringResource(Res.string.playlist_item_more_actions))
    if (addToPlaylistOpen) AddToPlaylistDialog(
        title = track.title,
        colors = colors,
        playlists = playlistChoices,
        status = null,
        onDismissRequest = { addToPlaylistOpen = false },
        onAddToExisting = { choice ->
            addToPlaylistOpen = false
            onTrackAction(SharedTrackRowActionRequest(track, SharedTrackRowAction.AddToPlaylist, playlistChoice = choice))
        },
        onCreateAndAdd = { name ->
            addToPlaylistOpen = false
            onTrackAction(SharedTrackRowActionRequest(track, SharedTrackRowAction.CreatePlaylistAndAdd, playlistName = name))
        },
    )
    if (detailsOpen) TrackDetailsDialog(track.detailSections, colors, onDismissRequest = { detailsOpen = false })
}

@Composable
internal fun PlaylistTrackPositionDialog(
    colors: NaviampColors,
    currentPosition: Int,
    trackCount: Int,
    onDismiss: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var positionText by remember { mutableStateOf(currentPosition.toString()) }
    val position = positionText.toIntOrNull()?.takeIf { it in 1..trackCount }
    NaviampPopupPresence()
    NaviampAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.playlist_item_move_position)) },
        text = {
            OutlinedTextField(
                modifier = Modifier.naviampTextInputFocus(),
                value = positionText,
                onValueChange = { positionText = it },
                singleLine = true,
                isError = position == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                keyboardActions = KeyboardActions(onDone = { position?.takeIf { it != currentPosition }?.let(onMove) }),
                label = { Text(stringResource(Res.string.playlist_item_position, trackCount)) },
            )
        },
        confirmButton = {
            NaviampTextButton(enabled = position != null && position != currentPosition, onClick = { position?.let(onMove) }) {
                Text(stringResource(Res.string.playlist_item_move))
            }
        },
        dismissButton = { NaviampTextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
        containerColor = colors.controlSurface,
        titleContentColor = colors.primaryText,
        textContentColor = colors.secondaryText,
    )
}
