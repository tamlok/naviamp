package app.naviamp.app

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.playback.PlaybackProfileTarget
import app.naviamp.domain.playback.PlaybackProfileTargetType
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.playback.PlaybackState
import app.naviamp.domain.playback.PlaylistPlaybackBookmark
import app.naviamp.domain.playback.PlaylistPlaybackBookmarkRepository
import app.naviamp.domain.playback.PlaylistPlaybackResume
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.domain.queue.PlaybackQueueGroup
import app.naviamp.domain.queue.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaviampPlaylistBookmarkControllerTest {
    @Test
    fun rapidPlaylistSwitchFlushesOutgoingProgressAndResumeFlushesTheOtherPlaylist() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val a = playlist("a", listOf(track("a1"), track("a2")), index = 1)
        val b = playlist("b", listOf(track("b1")))

        controller.observe("source", live(a, 5.0), 0)
        val outgoingA = live(a, 23.0)
        controller.observe("source", outgoingA, 1_000)
        controller.observe("source", live(b, 23.0).copy(progress = outgoingA.progress), 1_100)
        assertEquals(bookmark("a2", 23.0), repository.loadBookmark("source", "a"))
        assertNull(repository.loadBookmark("source", "b"))

        controller.observe("source", live(b, 7.0), 1_200)
        val outgoingB = live(b, 19.0)
        controller.observe("source", outgoingB, 1_300)
        assertEquals(PlaylistPlaybackResume(1, 23.0), controller.resume("source", "a", a.tracks))
        assertEquals(bookmark("b1", 19.0), repository.loadBookmark("source", "b"))
        controller.observe("source", live(a, 19.0).copy(progress = outgoingB.progress), 1_400)
        assertEquals(bookmark("a2", 23.0), repository.loadBookmark("source", "a"))
        controller.observe("source", live(a, 23.0), 1_500)
        assertEquals(PlaylistPlaybackResume(0, 19.0), controller.resume("source", "b", b.tracks))
    }

    @Test
    fun sourceChangesFlushUnderTheCapturedSourceAndDoNotShareBookmarks() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val queue = playlist("playlist", listOf(track("track")))
        controller.observe("one", live(queue, 10.0), 0)
        val outgoing = live(queue, 25.0)
        controller.observe("one", outgoing, 1_000)
        controller.observe("two", outgoing, 1_100)
        assertEquals(bookmark("track", 25.0), repository.loadBookmark("one", "playlist"))
        assertNull(repository.loadBookmark("two", "playlist"))
        controller.observe("two", live(queue, 3.0), 1_200)
        controller.observe("two", live(queue, 6.0), 1_300)
        assertEquals(PlaylistPlaybackResume(0, 25.0), controller.resume("one", "playlist", queue.tracks))
        assertEquals(PlaylistPlaybackResume(0, 6.0), controller.resume("two", "playlist", queue.tracks))
        assertNull(controller.resume(null, "playlist", queue.tracks))
    }

    @Test
    fun adjacentDuplicateTracksRejectTheInheritedClockEvenWhenCurrentTrackDoesNotChange() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val repeated = track("repeat")
        val queue = playlist("playlist", listOf(repeated, repeated))
        val playback = NaviampLivePlaybackController()
        var nowMillis = 0L
        playback.observe { controller.observe("source", it, nowMillis++) }
        playback.replace(live(queue, 80.0))
        playback.updateProgress(PlaybackProgress(81.0, 180.0))
        playback.updateQueue(queue.copy(currentIndex = 1))
        playback.updateCurrentTrack(repeated)
        assertEquals(bookmark("repeat", 81.0), repository.loadBookmark("source", "playlist"))
        playback.updateRepeatMode(RepeatMode.Queue)
        assertEquals(2, repository.writes.size)
        playback.updateProgress(PlaybackProgress.Unknown)
        playback.updateProgress(PlaybackProgress(0.0, 180.0))
        assertEquals(bookmark("repeat", 0.0, occurrence = 1), repository.loadBookmark("source", "playlist"))
    }

    @Test
    fun atomicQueueReplacementAcceptsAFreshClockEqualToThePreviousOccurrence() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val repeated = track("repeat")
        val queue = playlist("playlist", listOf(repeated, repeated))
        val playback = NaviampLivePlaybackController()
        var nowMillis = 0L
        playback.observe { controller.observe("source", it, nowMillis++) }
        playback.replace(live(queue, 50.0))
        playback.replace(live(queue.copy(currentIndex = 1), 50.0))
        assertEquals(bookmark("repeat", 50.0, occurrence = 1), repository.loadBookmark("source", "playlist"))
        assertEquals(2, repository.writes.size)
    }

    @Test
    fun fieldOnlyQueueTransitionDoesNotBlockAnExplicitEqualClockBeforeImmediatePause() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val repeated = track("repeat")
        val queue = playlist("playlist", listOf(repeated, repeated))
        val first = live(queue, 0.0)
        controller.observe("source", first, 0)
        val switched = first.copy(queue = queue.copy(currentIndex = 1))
        controller.observe("source", switched, 1)
        assertEquals(bookmark("repeat", 0.0), repository.loadBookmark("source", "playlist"))
        val explicitStart = switched.copy(progress = PlaybackProgress(0.0, 180.0))
        controller.observe("source", explicitStart, 2)
        controller.observe("source", explicitStart.copy(playbackState = PlaybackState.Paused), 3)
        assertEquals(bookmark("repeat", 0.0, occurrence = 1), repository.loadBookmark("source", "playlist"))
        assertEquals(2, repository.writes.size)
    }

    @Test
    fun mismatchedQueueAndCurrentTrackNeverAttributeTheClockToEitherSelection() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val a = track("a")
        val b = track("b")
        val queue = playlist("playlist", listOf(a, b))
        controller.observe("source", live(queue, 10.0), 0)
        val mismatched = live(queue.copy(currentIndex = 1), 14.0).copy(currentTrack = a)
        controller.observe("source", mismatched, 100)
        assertEquals(bookmark("a", 10.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", mismatched.copy(currentTrack = b), 200)
        assertEquals(bookmark("a", 10.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue.copy(currentIndex = 1), 0.0), 300)
        assertEquals(bookmark("b", 0.0), repository.loadBookmark("source", "playlist"))
    }

    @Test
    fun onlyPlaylistGroupsAreRecordedAndLeavingTheGroupFlushes() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val tracks = listOf(track("a"), track("b"))
        val playlist = playlist("playlist", tracks)
        controller.observe(null, live(playlist, 50.0), 0)
        controller.observe("source", live(PlaybackQueue(tracks, currentIndex = 0), 50.0), 1)
        val album = playlist.copy(groups = listOf(playlist.groups.single().copy(
            target = PlaybackProfileTarget(PlaybackProfileTargetType.Album, "album"),
        )))
        controller.observe("source", live(album, 50.0), 2)
        assertEquals(0, repository.writes.size)
        controller.observe("source", live(playlist, 0.0), 3)
        controller.observe("source", live(playlist, 17.0), 4)
        controller.observe("source", live(album, 17.0), 5)
        assertEquals(bookmark("a", 17.0), repository.loadBookmark("source", "playlist"))
        assertNull(repository.loadBookmark("source", "album"))
    }

    @Test
    fun completionAdvancesWithinTheGroupAndRepeatedOrLateStoppedCallbacksCannotUndoIt() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val repeated = track("repeat")
        val queue = playlist("playlist", listOf(track("first"), repeated, repeated), index = 1)
        controller.observe("source", live(queue, 170.0), 0)
        controller.observe("source", live(queue, 180.0, PlaybackState.Finished), 100)
        assertEquals(bookmark("repeat", 0.0, occurrence = 1), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 180.0, PlaybackState.Finished), 200)
        controller.observe("source", live(queue, 179.0, PlaybackState.Stopped), 300)
        controller.flush()
        assertEquals(2, repository.writes.size)

        val last = queue.copy(currentIndex = 2)
        controller.observe("source", live(last, 180.0, PlaybackState.Finished), 400)
        controller.observe("source", live(last, 179.0, PlaybackState.Stopped), 500)
        assertEquals(2, repository.writes.size)
        controller.observe("source", live(last, 0.0), 600)
        controller.observe("source", live(last, 180.0, PlaybackState.Finished), 700)
        assertNull(repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(last, 180.0, PlaybackState.Finished), 800)
        controller.observe("source", live(last, 179.0, PlaybackState.Stopped), 900)
        controller.observe("source", live(last, 0.0, PlaybackState.Paused), 1_000)
        controller.flush()
        assertEquals(4, repository.writes.size)
        assertNull(controller.resume("source", "playlist", queue.tracks))
    }

    @Test
    fun occurrencesAndCompletionAreScopedToTheCurrentGroupNotTheWholeQueue() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val repeated = track("repeat")
        val queue = PlaybackQueue(
            tracks = listOf(repeated, repeated, repeated, track("outside")),
            currentIndex = 2,
            groups = listOf(PlaybackQueueGroup(
                id = "group",
                target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "playlist"),
                label = "Playlist",
                startIndex = 1,
                endIndexExclusive = 3,
            )),
        )
        controller.observe("source", live(queue, 50.0), 0)
        assertEquals(bookmark("repeat", 50.0, occurrence = 1), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 180.0, PlaybackState.Finished), 1)
        assertNull(repository.loadBookmark("source", "playlist"))
    }

    @Test
    fun repeatedPlaybackCanStartANewCheckpointWithoutChangingRepeatPolicy() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val queue = playlist("playlist", listOf(track("track")))
        val playing = live(queue, 170.0).copy(repeatMode = RepeatMode.Track)
        controller.observe("source", playing, 0)
        controller.observe("source", playing.copy(playbackState = PlaybackState.Finished), 1)
        assertNull(repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 0.0, PlaybackState.Loading).copy(repeatMode = RepeatMode.Track), 2)
        controller.observe("source", live(queue, 0.0).copy(repeatMode = RepeatMode.Track), 3)
        assertEquals(bookmark("track", 0.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 10.0).copy(repeatMode = RepeatMode.Track), 4)
        controller.flush()
        assertEquals(bookmark("track", 10.0), repository.loadBookmark("source", "playlist"))
    }

    @Test
    fun pauseSeekFinalPausedClockAndStopPersistImmediatelyWithoutSavingTheStopResetClock() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val queue = playlist("playlist", listOf(track("track")))
        controller.observe("source", live(queue, 10.0), 0)
        controller.observe("source", live(queue, 20.0), 100)
        controller.observe("source", live(queue, 20.0, PlaybackState.Paused), 200)
        assertEquals(bookmark("track", 20.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 21.0, PlaybackState.Paused), 300)
        assertEquals(bookmark("track", 21.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 100.0, PlaybackState.Paused, seekGeneration = 1), 400)
        assertEquals(bookmark("track", 100.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 100.0, seekGeneration = 1), 500)
        controller.observe("source", live(queue, 101.0, seekGeneration = 2), 600)
        assertEquals(bookmark("track", 101.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 102.0, seekGeneration = 2), 700)
        controller.observe("source", live(queue, 102.0, PlaybackState.Stopped, seekGeneration = 2), 800)
        assertEquals(bookmark("track", 102.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 0.0, PlaybackState.Stopped, seekGeneration = 2), 900)
        assertEquals(bookmark("track", 102.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, 103.0, PlaybackState.Stopped, seekGeneration = 2), 1_000)
        assertEquals(bookmark("track", 103.0), repository.loadBookmark("source", "playlist"))
    }

    @Test
    fun invalidUnknownLoadingAndErrorClocksDoNotOverwriteKnownProgress() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val queue = playlist("playlist", listOf(track("track")))
        controller.observe("source", live(queue, 10.0), 0)
        controller.observe("source", live(queue, 25.0), 100)
        controller.observe("source", live(queue, null), 200)
        controller.observe("source", live(queue, Double.NaN), 300)
        controller.observe("source", live(queue, Double.POSITIVE_INFINITY), 400)
        controller.observe("source", live(queue, -1.0), 500)
        controller.observe("source", live(queue, 0.0, PlaybackState.Loading), 600)
        controller.observe("source", live(queue, 0.0, PlaybackState.Error("unavailable")), 700)
        controller.observe("source", live(queue, null, PlaybackState.Paused), 800)
        assertEquals(bookmark("track", 25.0), repository.loadBookmark("source", "playlist"))
        controller.observe("source", live(queue, null, PlaybackState.Stopped), 900, force = true)
        assertEquals(2, repository.writes.size)
        assertEquals(PlaylistPlaybackResume(0, 25.0), controller.resume("source", "playlist", queue.tracks))
    }

    @Test
    fun unchangedFramesNeverWriteAndProgressUsesTheFiveSecondGateUnlessForced() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val queue = playlist("playlist", listOf(track("track")))
        controller.observe("source", live(queue, 0.0), 0)
        controller.observe("source", live(queue, 1.0), 4_999)
        assertEquals(1, repository.writes.size)
        controller.observe("source", live(queue, 2.0), 5_000)
        assertEquals(2, repository.writes.size)
        controller.observe("source", live(queue, 2.0), 10_000)
        controller.observe("source", live(queue, 2.0), 15_000, force = true)
        controller.observe("source", live(queue, 2.0, PlaybackState.Paused), 15_001)
        controller.flush()
        assertEquals(2, repository.writes.size)
        controller.observe("source", live(queue, 3.0), 15_002)
        controller.observe("source", live(queue, 4.0), 15_003, force = true)
        assertEquals(bookmark("track", 4.0), repository.loadBookmark("source", "playlist"))
        assertEquals(4, repository.writes.size)
    }

    @Test
    fun canonicalReplacementRecordsRemoteClockAndLatePausedProgressWithoutNativeCallbacks() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val playback = NaviampLivePlaybackController()
        val queue = playlist("playlist", listOf(track("track")))
        var nowMillis = 0L
        playback.observe { controller.observe("source", it, nowMillis++) }
        playback.replace(live(queue, 120.0))
        playback.replace(live(queue, 121.0, PlaybackState.Paused))
        playback.replace(live(queue, 122.0, PlaybackState.Paused))
        assertEquals(bookmark("track", 122.0), repository.loadBookmark("source", "playlist"))
        assertEquals(3, repository.writes.size)
    }

    @Test
    fun resetDiscardsPendingWritesAndClearedCanonicalPlaybackDoesNotRecreateHistory() {
        val repository = RecordingPlaylistBookmarks()
        val controller = NaviampPlaylistBookmarkController(repository)
        val queue = playlist("playlist", listOf(track("track")))
        controller.observe("source", live(queue, 10.0), 0)
        controller.observe("source", live(queue, 20.0), 100)
        controller.reset()
        controller.flush()
        assertEquals(bookmark("track", 10.0), repository.loadBookmark("source", "playlist"))
        repository.bookmarks.clear()
        controller.observe("source", NaviampLivePlaybackState(
            playbackState = PlaybackState.Stopped,
            progress = PlaybackProgress(21.0, 180.0),
        ), 200)
        controller.flush()
        assertEquals(1, repository.writes.size)
        assertNull(controller.resume("source", "playlist", queue.tracks))
    }

    private fun live(
        queue: PlaybackQueue,
        positionSeconds: Double?,
        state: PlaybackState = PlaybackState.Playing,
        seekGeneration: Long = 0L,
    ) = NaviampLivePlaybackState(
        queue = queue,
        currentTrack = queue.tracks.getOrNull(queue.currentIndex),
        playbackState = state,
        progress = PlaybackProgress(positionSeconds, 180.0, seekGeneration = seekGeneration),
    )

    private fun playlist(id: String, tracks: List<Track>, index: Int = 0) = PlaybackQueue(
        tracks = tracks,
        currentIndex = index,
        groups = listOf(PlaybackQueueGroup(
            id = "group:$id",
            target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, id),
            label = id,
            startIndex = 0,
            endIndexExclusive = tracks.size,
        )),
    )

    private fun bookmark(id: String, positionSeconds: Double, occurrence: Int = 0) =
        PlaylistPlaybackBookmark(TrackId(id), occurrence, positionSeconds)

    private fun track(id: String) = Track(
        id = TrackId(id),
        title = "Track $id",
        artistName = "Artist",
        albumTitle = "Album",
        durationSeconds = 180,
        coverArtId = null,
        audioInfo = null,
        replayGain = null,
    )
}

private class RecordingPlaylistBookmarks : PlaylistPlaybackBookmarkRepository {
    val bookmarks = mutableMapOf<Pair<String, String>, PlaylistPlaybackBookmark>()
    val writes = mutableListOf<PlaylistPlaybackBookmark?>()

    override fun loadBookmark(sourceId: String, playlistId: String): PlaylistPlaybackBookmark? =
        bookmarks[sourceId to playlistId]

    override fun saveBookmark(sourceId: String, playlistId: String, bookmark: PlaylistPlaybackBookmark?) {
        writes += bookmark
        if (bookmark == null) bookmarks.remove(sourceId to playlistId)
        else bookmarks[sourceId to playlistId] = bookmark
    }
}
