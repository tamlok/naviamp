package app.naviamp.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import app.naviamp.ui.generated.resources.*
import app.naviamp.domain.playback.SleepTimerTarget
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class NaviampAction(
    val labelResource: StringResource,
    val icon: ImageVector,
) {
    StartTrackRadio(Res.string.tv_start_radio, NaviampTransportIcons.Radio),
    PlayTrackRadioNext(Res.string.ui_play_track_radio_next, NaviampIcons.Player),
    AddTrackRadioToQueue(Res.string.ui_add_track_radio_to_queue, NaviampIcons.Queue),
    StartAlbumRadio(Res.string.tv_start_radio, NaviampTransportIcons.Radio),
    StartArtistRadio(Res.string.tv_start_radio, NaviampTransportIcons.Radio),
    PlaySomethingNew(Res.string.something_new_title, NaviampIcons.Turntable),
    DownloadTrack(Res.string.playlist_item_download, NaviampIcons.Downloads),
    DownloadAlbum(Res.string.ui_download_album, NaviampIcons.Downloads),
    DownloadPlaylist(Res.string.playlists_download, NaviampIcons.Downloads),
    KeepPlaylistDownloaded(Res.string.download_keep_collection, NaviampIcons.Downloads),
    RemoveDownload(Res.string.ui_remove_download, NaviampIcons.Trash),
    PlayNext(Res.string.action_play_after_current_group, NaviampIcons.Player),
    PlayNextTrack(Res.string.action_play_immediately_next, NaviampIcons.Player),
    AddToQueue(Res.string.generated_radio_add_queue, NaviampIcons.Queue),
    RemoveFromQueue(Res.string.ui_remove_from_queue, NaviampIcons.Trash),
    AddToPlaylist(Res.string.ui_add_to_playlist, NaviampIcons.Playlist),
    SaveQueueAsPlaylist(Res.string.ui_save_queue_as_playlist, NaviampIcons.Playlist),
    EmptyQueue(Res.string.ui_empty_queue, NaviampIcons.Trash),
    SleepTimer(Res.string.ui_sleep_timer, NaviampIcons.Clock),
    AddPlaylistToPlaylist(Res.string.playlists_add_to_playlist, NaviampIcons.Playlist),
    RenamePlaylist(Res.string.playlists_rename_title, NaviampIcons.Edit),
    EditSmartPlaylist(Res.string.playlists_edit_smart, NaviampIcons.Brain),
    DuplicateSmartPlaylist(Res.string.playlists_duplicate_smart, NaviampIcons.Duplicate),
    DeletePlaylist(Res.string.playlists_delete_title, NaviampIcons.Trash),
    EditStation(Res.string.connection_edit_station, NaviampIcons.Edit),
    DeleteStation(Res.string.tv_delete_station, NaviampIcons.Trash),
    TrackDetails(Res.string.playlist_item_details, NaviampIcons.Info),
    ToggleFavorite(Res.string.tv_favorite, NaviampTransportIcons.Heart),
    GoToAlbum(Res.string.playlist_item_go_album, NaviampIcons.Album),
    GoToArtist(Res.string.playlist_item_go_artist, NaviampIcons.Artist),
    ShowLyrics(Res.string.ui_show_lyrics, NaviampTransportIcons.Lyrics),
    HideLyrics(Res.string.ui_hide_lyrics, NaviampTransportIcons.Lyrics),
    ShowVisualizer(Res.string.ui_show_visualizer, NaviampTransportIcons.Visualizer),
    ChangeVisualizer(Res.string.ui_change_visualizer, NaviampTransportIcons.Visualizer),
    HideVisualizer(Res.string.ui_show_album_art, NaviampIcons.Album),
    TrackPreference(Res.string.ui_track_preference, NaviampTransportIcons.Heart),
}

data class NaviampActionSpec(
    val action: NaviampAction,
    val labelResource: StringResource = action.labelResource,
    val icon: ImageVector = action.icon,
    val enabled: Boolean = true,
    val sleepTimer: NaviampSleepTimerUi? = null,
)

fun trackRowActions(
    canStartRadio: Boolean = false,
    canDownload: Boolean = false,
    canAddToQueue: Boolean = false,
    canAddToPlaylist: Boolean = false,
    canToggleFavorite: Boolean = false,
    favoriteActive: Boolean = false,
    hasAlbum: Boolean = false,
    hasArtist: Boolean = false,
    canShowDetails: Boolean = true,
    playAfterCurrentGroupLabel: StringResource = NaviampAction.PlayNext.labelResource,
    playImmediatelyNextLabel: StringResource = NaviampAction.PlayNextTrack.labelResource,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.PlayNext.takeIf { canAddToQueue }?.toSpec(labelResource = playAfterCurrentGroupLabel),
        NaviampAction.PlayNextTrack.takeIf { canAddToQueue }?.toSpec(labelResource = playImmediatelyNextLabel),
        NaviampAction.StartTrackRadio.takeIf { canStartRadio }?.toSpec(),
        NaviampAction.PlayTrackRadioNext.takeIf { canStartRadio }?.toSpec(),
        NaviampAction.AddTrackRadioToQueue.takeIf { canStartRadio }?.toSpec(),
        NaviampAction.DownloadTrack.takeIf { canDownload }?.toSpec(),
        NaviampAction.AddToQueue.takeIf { canAddToQueue }?.toSpec(),
        NaviampAction.AddToPlaylist.takeIf { canAddToPlaylist }?.toSpec(),
        NaviampAction.ToggleFavorite.takeIf { canToggleFavorite }?.toSpec(
            labelResource = if (favoriteActive) Res.string.playlist_item_unfavorite else Res.string.tv_favorite,
        ),
        NaviampAction.GoToAlbum.takeIf { hasAlbum }?.toSpec(),
        NaviampAction.GoToArtist.takeIf { hasArtist }?.toSpec(),
        NaviampAction.TrackDetails.takeIf { canShowDetails }?.toSpec(),
    )

fun queueRowActions(
    playAfterCurrentGroupLabel: StringResource = NaviampAction.PlayNext.labelResource,
    playImmediatelyNextLabel: StringResource = NaviampAction.PlayNextTrack.labelResource,
): List<NaviampActionSpec> =
    listOf(
        NaviampAction.PlayNext.toSpec(labelResource = playAfterCurrentGroupLabel),
        NaviampAction.PlayNextTrack.toSpec(labelResource = playImmediatelyNextLabel),
        NaviampAction.StartTrackRadio.toSpec(),
        NaviampAction.PlayTrackRadioNext.toSpec(),
        NaviampAction.AddTrackRadioToQueue.toSpec(),
        NaviampAction.DownloadTrack.toSpec(),
        NaviampAction.AddToPlaylist.toSpec(),
        NaviampAction.GoToArtist.toSpec(),
        NaviampAction.GoToAlbum.toSpec(),
    )

fun upNextQueueRowActions(
    playAfterCurrentGroupLabel: StringResource = NaviampAction.PlayNext.labelResource,
    playImmediatelyNextLabel: StringResource = NaviampAction.PlayNextTrack.labelResource,
): List<NaviampActionSpec> =
    listOf(NaviampAction.RemoveFromQueue.toSpec()) + queueRowActions(
        playAfterCurrentGroupLabel,
        playImmediatelyNextLabel,
    )

fun relatedTrackRowActions(
    playAfterCurrentGroupLabel: StringResource = NaviampAction.PlayNext.labelResource,
    playImmediatelyNextLabel: StringResource = NaviampAction.PlayNextTrack.labelResource,
): List<NaviampActionSpec> =
    listOf(
        NaviampAction.AddToQueue.toSpec(),
    ) + queueRowActions(playAfterCurrentGroupLabel, playImmediatelyNextLabel)

fun albumRowActions(
    canStartRadio: Boolean = false,
    canDownload: Boolean = false,
    canAddToQueue: Boolean = false,
    canAddToPlaylist: Boolean = false,
    canFavorite: Boolean = false,
    favoriteActive: Boolean = false,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.StartAlbumRadio.takeIf { canStartRadio }?.toSpec(),
        NaviampAction.DownloadAlbum.takeIf { canDownload }?.toSpec(),
        NaviampAction.AddToQueue.takeIf { canAddToQueue }?.toSpec(),
        NaviampAction.AddToPlaylist.takeIf { canAddToPlaylist }?.toSpec(),
        NaviampAction.ToggleFavorite.takeIf { canFavorite }?.toSpec(
            labelResource = if (favoriteActive) Res.string.ui_remove_album_favorite else Res.string.ui_favorite_album,
        ),
    )

fun artistRowActions(
    canStartRadio: Boolean = false,
    canAddToQueue: Boolean = false,
    canAddToPlaylist: Boolean = false,
    canFavorite: Boolean = false,
    favoriteActive: Boolean = false,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.StartArtistRadio.takeIf { canStartRadio }?.toSpec(),
        NaviampAction.AddToQueue.takeIf { canAddToQueue }?.toSpec(),
        NaviampAction.AddToPlaylist.takeIf { canAddToPlaylist }?.toSpec(),
        NaviampAction.ToggleFavorite.takeIf { canFavorite }?.toSpec(
            labelResource = if (favoriteActive) Res.string.ui_remove_artist_favorite else Res.string.ui_favorite_artist,
        ),
    )

fun NaviampAction.artistMediaCommandOrNull(): NaviampArtistMediaCommand? =
    when (this) {
        NaviampAction.StartArtistRadio -> NaviampArtistMediaCommand.StartRadio
        NaviampAction.AddToQueue -> NaviampArtistMediaCommand.AddToQueue
        NaviampAction.ToggleFavorite -> NaviampArtistMediaCommand.ToggleFavorite
        else -> null
    }

fun NaviampAction.albumMediaCommandOrNull(): NaviampArtistAlbumCommand? =
    when (this) {
        NaviampAction.StartAlbumRadio -> NaviampArtistAlbumCommand.StartRadio
        NaviampAction.DownloadAlbum -> NaviampArtistAlbumCommand.Download
        NaviampAction.AddToQueue -> NaviampArtistAlbumCommand.AddToQueue
        NaviampAction.ToggleFavorite -> NaviampArtistAlbumCommand.ToggleFavorite
        else -> null
    }

fun playlistRowActions(
    canDownload: Boolean = false,
    canKeepDownloaded: Boolean = false,
    keepDownloadedActive: Boolean = false,
    canAddToQueue: Boolean = false,
    canAddToPlaylist: Boolean = false,
    canRename: Boolean = false,
    canEditSmartPlaylist: Boolean = false,
    canDuplicateSmartPlaylist: Boolean = false,
    duplicateSmartPlaylistLabel: StringResource = NaviampAction.DuplicateSmartPlaylist.labelResource,
    canDelete: Boolean = false,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.DownloadPlaylist.takeIf { canDownload }?.toSpec(),
        NaviampAction.KeepPlaylistDownloaded.takeIf { canKeepDownloaded }?.toSpec(
            labelResource = if (keepDownloadedActive) Res.string.download_stop_keeping_collection else Res.string.download_keep_collection,
        ),
        NaviampAction.AddToQueue.takeIf { canAddToQueue }?.toSpec(),
        NaviampAction.AddPlaylistToPlaylist.takeIf { canAddToPlaylist }?.toSpec(),
        NaviampAction.RenamePlaylist.takeIf { canRename }?.toSpec(),
        NaviampAction.EditSmartPlaylist.takeIf { canEditSmartPlaylist }?.toSpec(),
        NaviampAction.DuplicateSmartPlaylist.takeIf { canDuplicateSmartPlaylist }?.toSpec(labelResource = duplicateSmartPlaylistLabel),
        NaviampAction.DeletePlaylist.takeIf { canDelete }?.toSpec(),
    )

fun downloadRowActions(
    canRemove: Boolean = true,
    canAddToPlaylist: Boolean = true,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.RemoveDownload.takeIf { canRemove }?.toSpec(),
        NaviampAction.AddToPlaylist.takeIf { canAddToPlaylist }?.toSpec(),
    )

fun stationRowActions(
    canEdit: Boolean = false,
    canDelete: Boolean = false,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.EditStation.takeIf { canEdit }?.toSpec(),
        NaviampAction.DeleteStation.takeIf { canDelete }?.toSpec(),
    )

fun nowPlayingTrackMenuActions(
    visualizerAvailable: Boolean,
    isLive: Boolean,
    hasDetails: Boolean,
    canAddToPlaylist: Boolean,
    canSaveQueueAsPlaylist: Boolean,
    canEmptyQueue: Boolean,
    sleepTimer: NaviampSleepTimerUi,
    canStartRadio: Boolean = false,
    detailsLabel: StringResource = NaviampAction.TrackDetails.labelResource,
    canPlaySomethingNew: Boolean = false,
): List<NaviampActionSpec> =
    listOfNotNull(
        NaviampAction.StartTrackRadio.takeIf { canStartRadio && !isLive }?.toSpec(),
        NaviampAction.PlaySomethingNew.takeIf { canPlaySomethingNew }?.toSpec(),
        NaviampAction.ChangeVisualizer.toSpec(enabled = visualizerAvailable),
        NaviampAction.DownloadTrack.toSpec(enabled = !isLive),
        NaviampAction.TrackDetails.toSpec(labelResource = detailsLabel, enabled = hasDetails),
        NaviampAction.GoToAlbum.toSpec(enabled = !isLive),
        NaviampAction.GoToArtist.toSpec(enabled = !isLive),
        NaviampAction.AddToPlaylist.toSpec(enabled = canAddToPlaylist),
        NaviampAction.SaveQueueAsPlaylist.toSpec(enabled = canSaveQueueAsPlaylist),
        NaviampAction.EmptyQueue.toSpec(enabled = canEmptyQueue),
        NaviampActionSpec(NaviampAction.SleepTimer, sleepTimer = sleepTimer),
    )

fun NaviampAction.toSpec(
    labelResource: StringResource = this.labelResource,
    enabled: Boolean = true,
): NaviampActionSpec =
    NaviampActionSpec(action = this, labelResource = labelResource, icon = icon, enabled = enabled)

@Composable
fun NaviampActionSpec.localizedLabel(): String =
    sleepTimer?.localizedLabel() ?: stringResource(labelResource)

@Composable
fun NaviampSleepTimerUi.localizedLabel(): String {
    if (!active) return stringResource(Res.string.ui_sleep_timer)
    return when (target) {
        SleepTimerTarget.Duration -> remainingMillis?.let { millis ->
            val seconds = (millis.coerceAtLeast(0L) / 1_000L) + if (millis % 1_000L > 0) 1L else 0L
            val duration = if (seconds >= 60L) {
                stringResource(Res.string.ui_timer_minutes_seconds, seconds / 60L, (seconds % 60L).toString().padStart(2, '0'))
            } else {
                stringResource(Res.string.settings_seconds_short, seconds)
            }
            stringResource(Res.string.ui_sleep_in_duration, duration)
        } ?: stringResource(Res.string.ui_sleep_timer)
        SleepTimerTarget.TrackEnd -> stringResource(Res.string.ui_sleep_after_track)
        SleepTimerTarget.AlbumEnd -> stringResource(Res.string.ui_sleep_after_album)
        SleepTimerTarget.QueueEnd -> stringResource(Res.string.ui_sleep_after_queue)
        null -> stringResource(Res.string.ui_sleep_timer)
    }
}
