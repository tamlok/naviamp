package app.naviamp.domain.playback

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.queue.PlaybackQueue
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackQueueAddOutcomeTest {
    private val manager = PlaybackQueueMutationManager()
    private val queue = PlaybackQueue(listOf(track("a")), 0)

    @Test
    fun addsReportWhereTheTracksWent() {
        assertEquals(PlaybackQueueAddOutcome.AddedToQueue, manager.appendTracks(queue, listOf(track("b"))).outcome)
        assertEquals(PlaybackQueueAddOutcome.AddedToPlayNext, manager.playNextTracks(queue, listOf(track("b"))).outcome)
        assertEquals(PlaybackQueueAddOutcome.AddedToPlayNext, manager.playNextTrack(queue, track("b")).outcome)
    }

    @Test
    fun deduplicatedAndEmptyAddsAreDistinguished() {
        assertEquals(PlaybackQueueAddOutcome.AlreadyQueued,
            manager.appendTracks(queue, listOf(track("a")), deduplicateExisting = true).outcome)
        assertEquals(PlaybackQueueAddOutcome.AlreadyQueued,
            manager.playNextTracks(queue, listOf(track("a")), deduplicateExisting = true).outcome)
        assertEquals(PlaybackQueueAddOutcome.NothingFound, manager.appendTracks(queue, emptyList()).outcome)
        assertEquals(PlaybackQueueAddOutcome.NothingFound, manager.playNextTracks(queue, emptyList()).outcome)
        assertEquals(PlaybackQueueAddOutcome.NothingFound,
            manager.appendTracks(queue, emptyList(), deduplicateExisting = true).outcome)
        assertEquals(PlaybackQueueAddOutcome.NothingFound,
            manager.playNextTracks(queue, emptyList(), deduplicateExisting = true).outcome)
    }

    @Test
    fun partialDeduplicationAndIntentionalDuplicatesStillReportAnAdd() {
        val requested = listOf(track("a"), track("b"))
        val appended = manager.appendTracks(queue, requested, deduplicateExisting = true)
        val next = manager.playNextTracks(queue, requested, deduplicateExisting = true)

        assertEquals(PlaybackQueueAddOutcome.AddedToQueue, appended.outcome)
        assertEquals(PlaybackQueueAddOutcome.AddedToPlayNext, next.outcome)
        assertEquals(listOf(track("a"), track("b")), appended.queue.tracks)
        assertEquals(listOf(track("a"), track("b")), next.queue.tracks)
        assertEquals(true, appended.tracksChanged)
        assertEquals(true, next.tracksChanged)
        assertEquals(PlaybackQueueAddOutcome.AddedToQueue, manager.appendTracks(queue, listOf(track("a"))).outcome)
        assertEquals(PlaybackQueueAddOutcome.AddedToPlayNext, manager.playNextTracks(queue, listOf(track("a"))).outcome)
    }

    private fun track(id: String) = Track(TrackId(id), "Track $id", artistName = "Artist", albumTitle = null,
        durationSeconds = null, coverArtId = null, audioInfo = null, replayGain = null)
}
