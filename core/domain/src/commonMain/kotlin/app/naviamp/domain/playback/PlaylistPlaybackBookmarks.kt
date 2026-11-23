package app.naviamp.domain.playback

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId

/** Occurrence distinguishes repeated appearances of a track within one playlist. */
data class PlaylistPlaybackBookmark(
    val trackId: TrackId,
    val occurrence: Int,
    val positionSeconds: Double,
)

data class PlaylistPlaybackResume(val index: Int, val positionSeconds: Double)

/** Playback history, isolated from both the last active queue and portable preferences. */
interface PlaylistPlaybackBookmarkRepository {
    fun loadBookmark(sourceId: String, playlistId: String): PlaylistPlaybackBookmark?
    fun saveBookmark(sourceId: String, playlistId: String, bookmark: PlaylistPlaybackBookmark?)
}

class MemoryPlaylistPlaybackBookmarkRepository : PlaylistPlaybackBookmarkRepository {
    private val bookmarks = mutableMapOf<Pair<String, String>, PlaylistPlaybackBookmark>()

    override fun loadBookmark(sourceId: String, playlistId: String): PlaylistPlaybackBookmark? =
        bookmarks[sourceId to playlistId]

    override fun saveBookmark(sourceId: String, playlistId: String, bookmark: PlaylistPlaybackBookmark?) {
        val key = sourceId to playlistId
        if (bookmark == null) bookmarks.remove(key) else bookmarks[key] = bookmark
    }
}

/** Resolve against today's playlist order, not an obsolete queue index. */
fun resolvePlaylistPlaybackBookmark(
    tracks: List<Track>,
    bookmark: PlaylistPlaybackBookmark?,
): PlaylistPlaybackResume? {
    if (bookmark == null || bookmark.occurrence < 0) return null
    var occurrence = 0
    for (index in tracks.indices) {
        val track = tracks[index]
        if (track.id != bookmark.trackId) continue
        if (occurrence++ != bookmark.occurrence) continue
        val position = bookmark.positionSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val duration = track.durationSeconds?.takeIf { it > 0 }
        return if (duration != null && position >= duration) {
            if (index < tracks.lastIndex) PlaylistPlaybackResume(index + 1, 0.0) else null
        } else {
            PlaylistPlaybackResume(index, position)
        }
    }
    return null
}
