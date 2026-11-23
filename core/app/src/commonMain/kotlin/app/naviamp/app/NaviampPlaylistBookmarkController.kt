package app.naviamp.app

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.playback.PlaybackProfileTargetType
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.playback.PlaybackState
import app.naviamp.domain.playback.PlaylistPlaybackBookmark
import app.naviamp.domain.playback.PlaylistPlaybackBookmarkRepository
import app.naviamp.domain.playback.PlaylistPlaybackResume
import app.naviamp.domain.playback.resolvePlaylistPlaybackBookmark
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.domain.queue.groupAt

/** Records canonical playback, including remote output, independently of the last active session. */
class NaviampPlaylistBookmarkController(
    private val repository: PlaylistPlaybackBookmarkRepository,
) {
    private var observedSourceId: String? = null
    private var observedQueue: PlaybackQueue? = null
    private var observedProgress: PlaybackProgress? = null
    private var selection: BookmarkSelection? = null
    private var positionSeconds = Double.NaN
    private var savedPositionSeconds = Double.NaN
    private var lastSavedAtMillis: Long? = null
    private var nowMillis = 0L
    private var blockedProgress: PlaybackProgress? = null
    private var hasAcceptedClock = false
    private var completed = false
    private var completedPositionSeconds = Double.NaN

    fun observe(sourceId: String?, live: NaviampLivePlaybackState, nowMillis: Long, force: Boolean = false) {
        this.nowMillis = nowMillis
        val previousProgress = observedProgress
        observedProgress = live.progress
        val previousQueue = observedQueue
        if (
            sourceId != observedSourceId || previousQueue == null ||
            previousQueue.tracks !== live.queue.tracks || previousQueue.groups !== live.queue.groups ||
            previousQueue.currentIndex != live.queue.currentIndex
        ) {
            val next = bookmarkSelection(sourceId, live.queue)
            if (next != selection) {
                // Only the previously accepted clock belongs to the outgoing selection. Queue
                // and currentTrack may be published separately, making this snapshot ambiguous.
                flush()
                selection = next
                clearClock()
                // Atomic replacements provide a fresh clock, even when its value happens to
                // equal the outgoing position. Field-only queue changes inherit its reference.
                if (live.progress === previousProgress) blockClock(previousProgress)
            }
            observedQueue = live.queue
            observedSourceId = sourceId
        }

        val current = selection ?: return
        if (live.currentStation != null || live.currentTrack?.id != current.trackId) {
            flush()
            blockClock(live.progress)
            return
        }

        val position = live.progress.positionSeconds
        val seekChanged = previousProgress != null &&
            live.progress.seekGeneration != previousProgress.seekGeneration
        if (completed) {
            // Finished may be followed by repeated Finished, Stopped, and final clock callbacks.
            // Only a new playing clock (or an explicit seek) starts this occurrence again.
            if (
                live.playbackState != PlaybackState.Playing || position == null ||
                !position.isFinite() || position < 0.0 ||
                (!seekChanged && !(position < completedPositionSeconds))
            ) return
            clearClock()
        }
        if (live.playbackState == PlaybackState.Finished) {
            if (hasAcceptedClock) finish(current, live.queue, position)
            return
        }

        capturePosition(live, previousProgress)
        val savedAt = lastSavedAtMillis
        if (
            force || live.playbackState == PlaybackState.Paused || live.playbackState == PlaybackState.Stopped ||
            seekChanged || savedAt == null || nowMillis < savedAt || nowMillis - savedAt >= BookmarkSaveIntervalMillis
        ) flush()
    }

    /** Flushes the remembered outgoing source/playlist, not the caller's newly selected source. */
    fun flush() {
        val current = selection ?: return
        if (completed || !positionSeconds.isFinite() || positionSeconds == savedPositionSeconds) return
        repository.saveBookmark(
            current.sourceId,
            current.playlistId,
            PlaylistPlaybackBookmark(current.trackId, current.occurrence, positionSeconds),
        )
        savedPositionSeconds = positionSeconds
        lastSavedAtMillis = nowMillis
    }

    /** Database clearing discards pending playback history instead of writing it back. */
    fun reset() {
        observedSourceId = null
        observedQueue = null
        observedProgress = null
        selection = null
        clearClock()
    }

    fun resume(sourceId: String?, playlistId: String, tracks: List<Track>): PlaylistPlaybackResume? {
        flush()
        if (sourceId == null) return null
        return resolvePlaylistPlaybackBookmark(tracks, repository.loadBookmark(sourceId, playlistId))
    }

    private fun capturePosition(live: NaviampLivePlaybackState, previousProgress: PlaybackProgress?) {
        val position = live.progress.positionSeconds ?: return
        if (!position.isFinite() || position < 0.0) return
        when (live.playbackState) {
            PlaybackState.Playing, PlaybackState.Paused -> Unit
            PlaybackState.Stopped -> if (!hasAcceptedClock) return
            else -> return
        }
        val seekChanged = previousProgress != null &&
            live.progress.seekGeneration != previousProgress.seekGeneration
        if (live.progress === blockedProgress) return
        // Stop may reset the native clock to zero. Preserve the checkpoint, but accept a later
        // final forward sample; explicit seeks remain authoritative in either paused state.
        if (live.playbackState == PlaybackState.Stopped && position < positionSeconds && !seekChanged) return
        positionSeconds = position
        hasAcceptedClock = true
        blockedProgress = null
    }

    private fun finish(current: BookmarkSelection, queue: PlaybackQueue, finalPosition: Double?) {
        val nextIndex = current.index + 1
        val next = if (nextIndex < current.groupEndIndexExclusive) {
            val track = queue.tracks[nextIndex]
            PlaylistPlaybackBookmark(
                track.id,
                occurrence(queue, current.groupStartIndex, nextIndex, track.id),
                0.0,
            )
        } else null
        repository.saveBookmark(current.sourceId, current.playlistId, next)
        completedPositionSeconds = if (finalPosition != null && finalPosition.isFinite() && finalPosition >= 0.0) {
            finalPosition
        } else positionSeconds
        completed = true
        lastSavedAtMillis = nowMillis
    }

    private fun clearClock() {
        positionSeconds = Double.NaN
        savedPositionSeconds = Double.NaN
        lastSavedAtMillis = null
        blockedProgress = null
        hasAcceptedClock = false
        completed = false
        completedPositionSeconds = Double.NaN
    }

    private fun blockClock(progress: PlaybackProgress?) {
        blockedProgress = progress
    }
}

private data class BookmarkSelection(
    val sourceId: String,
    val playlistId: String,
    val groupId: String,
    val groupStartIndex: Int,
    val groupEndIndexExclusive: Int,
    val index: Int,
    val trackId: TrackId,
    val occurrence: Int,
)

private fun bookmarkSelection(sourceId: String?, queue: PlaybackQueue): BookmarkSelection? {
    if (sourceId == null) return null
    val track = queue.tracks.getOrNull(queue.currentIndex) ?: return null
    val group = queue.groupAt() ?: return null
    if (group.target.type != PlaybackProfileTargetType.Playlist) return null
    return BookmarkSelection(
        sourceId = sourceId,
        playlistId = group.target.id,
        groupId = group.id,
        groupStartIndex = group.startIndex,
        groupEndIndexExclusive = group.endIndexExclusive,
        index = queue.currentIndex,
        trackId = track.id,
        occurrence = occurrence(queue, group.startIndex, queue.currentIndex, track.id),
    )
}

private fun occurrence(queue: PlaybackQueue, startIndex: Int, index: Int, trackId: TrackId): Int {
    var occurrence = 0
    for (previousIndex in startIndex until index) {
        if (queue.tracks[previousIndex].id == trackId) occurrence++
    }
    return occurrence
}

private const val BookmarkSaveIntervalMillis = 5_000L
