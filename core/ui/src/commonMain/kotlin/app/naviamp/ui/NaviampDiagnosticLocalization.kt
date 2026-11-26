package app.naviamp.ui

import androidx.compose.runtime.Composable
import app.naviamp.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** New vocabulary applies only to app-owned headings and labels, never diagnostic values. */
@Composable
internal fun localizedDiagnosticText(text: String, isLabel: Boolean = false): String {
    if (isLabel) {
        val resource = when (text) {
            "Application" -> Res.string.diagnostics_application
            "Version" -> Res.string.diagnostics_version
            "Build" -> Res.string.tv_build
            "Route" -> Res.string.diagnostics_route
            "Now Playing open" -> Res.string.diagnostics_now_playing_open
            "Connection" -> Res.string.tv_connection
            "Server" -> Res.string.settings_lyrics_source_provider
            "Username" -> Res.string.connection_username
            "Provider" -> Res.string.diagnostics_provider
            "Provider cache namespace" -> Res.string.diagnostics_provider_cache_namespace
            "Source ID" -> Res.string.diagnostics_source_id
            "Connected" -> Res.string.diagnostics_connected
            "Status" -> Res.string.tv_status
            "Server version" -> Res.string.tv_server_version
            "Saved connections" -> Res.string.diagnostics_saved_connections
            "Selected libraries" -> Res.string.diagnostics_selected_libraries
            "Library" -> Res.string.library_title
            "View" -> Res.string.diagnostics_view
            "Syncing" -> Res.string.diagnostics_syncing
            "Search query" -> Res.string.diagnostics_search_query
            "Loaded artists" -> Res.string.diagnostics_loaded_artists
            "Loaded albums" -> Res.string.diagnostics_loaded_albums
            "Loaded songs" -> Res.string.diagnostics_loaded_songs
            "Indexed artists" -> Res.string.diagnostics_indexed_artists
            "Indexed albums" -> Res.string.diagnostics_indexed_albums
            "Indexed tracks" -> Res.string.diagnostics_indexed_tracks
            "Playback" -> Res.string.settings_category_playback_title
            "State" -> Res.string.tv_status
            "Track ID" -> Res.string.diagnostics_track_id
            "Title" -> Res.string.tv_album_sort_title
            "Artist" -> Res.string.tv_artist
            "Album" -> Res.string.tv_album
            "Audio" -> Res.string.settings_about_audio_title
            "Position" -> Res.string.now_playing_playback_position
            "Duration" -> Res.string.diagnostics_duration
            "Queue" -> Res.string.player_queue
            "Shuffle" -> Res.string.transport_shuffle
            "Repeat" -> Res.string.transport_repeat
            "Volume" -> Res.string.now_playing_volume
            "Stream quality" -> Res.string.diagnostics_stream_quality
            "Gapless" -> Res.string.settings_gapless_title
            "Crossfade" -> Res.string.settings_crossfade_title
            "Track sidecars" -> Res.string.diagnostics_track_sidecars
            "Waveform" -> Res.string.diagnostics_waveform
            "Embedded tags" -> Res.string.diagnostics_embedded_tags
            "Lyrics" -> Res.string.settings_lyrics_title
            "Lyrics synced" -> Res.string.diagnostics_lyrics_synced
            "Lyrics karaoke cues" -> Res.string.diagnostics_lyrics_karaoke_cues
            "Lyrics offset" -> Res.string.diagnostics_lyrics_offset
            "Visualizer" -> Res.string.diagnostics_visualizer
            "Selected visualizer" -> Res.string.diagnostics_selected_visualizer
            "Related tracks" -> Res.string.diagnostics_related_tracks
            "Stream metadata title" -> Res.string.diagnostics_stream_metadata_title
            "Stream metadata" -> Res.string.stream_details_metadata
            "Playback engine" -> Res.string.diagnostics_playback_engine
            "Playback profile" -> Res.string.diagnostics_playback_profile
            "Provider features" -> Res.string.diagnostics_provider_features
            "Database" -> Res.string.diagnostics_database
            "File size" -> Res.string.diagnostics_file_size
            "Saved sources" -> Res.string.diagnostics_saved_sources
            "Playback sessions" -> Res.string.diagnostics_playback_sessions
            "Pending provider actions" -> Res.string.diagnostics_pending_provider_actions
            "Pending listens" -> Res.string.diagnostics_pending_listens
            "Failed provider actions" -> Res.string.diagnostics_failed_provider_actions
            "Storage" -> Res.string.settings_storage_title
            "Images" -> Res.string.diagnostics_images
            "Provider responses" -> Res.string.diagnostics_provider_responses
            "Audio cache" -> Res.string.settings_audio_cache_title
            "Downloads" -> Res.string.settings_downloads_title
            "Waveforms" -> Res.string.settings_waveforms_title
            "Hot images" -> Res.string.diagnostics_hot_images
            "Image budget" -> Res.string.diagnostics_image_budget
            "Audio budget" -> Res.string.diagnostics_audio_budget
            "Waveform budget" -> Res.string.diagnostics_waveform_budget
            "Hot image budget" -> Res.string.diagnostics_hot_image_budget
            "Audio cache directory" -> Res.string.diagnostics_audio_cache_directory
            "Download directory" -> Res.string.diagnostics_download_directory
            "Recent API calls" -> Res.string.diagnostics_recent_api_calls
            "Error" -> Res.string.diagnostics_error
            "Streaming transcode" -> Res.string.diagnostics_streaming_transcode
            "Download transcode" -> Res.string.diagnostics_download_transcode
            "Artist radio" -> Res.string.diagnostics_artist_radio
            "Album radio" -> Res.string.diagnostics_album_radio
            "Track radio" -> Res.string.diagnostics_track_radio
            "Track favorites" -> Res.string.diagnostics_track_favorites
            "Artist favorites" -> Res.string.diagnostics_artist_favorites
            "Album favorites" -> Res.string.diagnostics_album_favorites
            "Track ratings" -> Res.string.diagnostics_track_ratings
            "Play reporting" -> Res.string.diagnostics_play_reporting
            "Smart playlists" -> Res.string.diagnostics_smart_playlists
            "Sonic similarity" -> Res.string.settings_related_sonic_similarity
            "Custom profile active" -> Res.string.diagnostics_custom_profile_active
            "Queue group" -> Res.string.diagnostics_queue_group
            "Profile target" -> Res.string.diagnostics_profile_target
            "Transition override" -> Res.string.diagnostics_transition_override
            "ReplayGain override" -> Res.string.diagnostics_replaygain_override
            "Resolved ReplayGain" -> Res.string.diagnostics_resolved_replaygain
            "Next transition source" -> Res.string.diagnostics_next_transition_source
            "Next transition" -> Res.string.diagnostics_next_transition
            else -> null
        }
        if (resource != null) return stringResource(resource)
    }
    return when {
        text == "Listen reporting mode" -> stringResource(Res.string.diagnostics_listen_reporting_mode)
        text == "Idle" -> stringResource(Res.string.diagnostics_mode_idle)
        text == "Timeline presence" -> stringResource(Res.string.diagnostics_mode_timeline_presence)
        text == "Legacy presence" -> stringResource(Res.string.diagnostics_mode_legacy_presence)
        text == "Legacy fallback" -> stringResource(Res.string.diagnostics_mode_legacy_fallback)
        text == "Last listen reporting failure" ->
            stringResource(Res.string.diagnostics_last_listen_reporting_failure)
        text == "External scrobbling" -> stringResource(Res.string.diagnostics_external_scrobbling)
        text == "Server-managed; API acceptance does not confirm external delivery" ->
            stringResource(Res.string.diagnostics_external_scrobbling_server_managed)
        text == "Pending listens" -> stringResource(Res.string.diagnostics_pending_listens)
        text.startsWith("Legacy presence: ") ->
            stringResource(Res.string.diagnostics_failure_legacy_presence) + text.removePrefix("Legacy presence")
        text.startsWith("Listen submission: ") ->
            stringResource(Res.string.diagnostics_failure_listen_submission) + text.removePrefix("Listen submission")
        text.startsWith("Timeline ") && ": " in text ->
            stringResource(Res.string.diagnostics_failure_timeline) + text.removePrefix("Timeline")
        else -> text
    }
}
