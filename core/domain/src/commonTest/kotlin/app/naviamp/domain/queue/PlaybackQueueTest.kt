package app.naviamp.domain.queue

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.radio.RadioQueueOccurrence
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlaybackQueueTest {
    @Test
    fun repeatedPlayNextTracksPreserveInsertionOrder() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("context-1"), track("context-2")),
            currentIndex = 0,
        )
            .playNextTracks(listOf(track("priority-1")))
            .playNextTracks(listOf(track("priority-2")))

        assertEquals(
            listOf(track("current"), track("priority-1"), track("priority-2"), track("context-1"), track("context-2")),
            queue.tracks,
        )
        assertEquals(listOf(track("priority-1"), track("priority-2")), queue.playNext())
        assertEquals(listOf(track("context-1"), track("context-2")), queue.contextUpNext())
    }

    @Test
    fun advancingConsumesOnlyTheStartedPriorityTrack() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("priority-1"), track("priority-2"), track("context")),
            currentIndex = 0,
            playNextCount = 2,
        ).next()

        assertEquals(track("priority-1"), queue.current)
        assertEquals(listOf(track("priority-2")), queue.playNext())
        assertEquals(listOf(track("context")), queue.contextUpNext())
    }

    @Test
    fun shuffleLeavesPriorityTracksInOrder() {
        val priority = listOf(track("priority-1"), track("priority-2"))
        val context = listOf(track("context-1"), track("context-2"), track("context-3"))
        val result = PlaybackQueue(
            tracks = listOf(track("current")) + priority + context,
            currentIndex = 0,
            playNextCount = priority.size,
        ).shuffleUpcoming()

        assertNotNull(result)
        assertEquals(priority, result.first.playNext())
        assertEquals(context.toSet(), result.first.contextUpNext().toSet())
        assertEquals(context, result.second)
    }

    @Test
    fun selectingAContextTrackMovesRemainingPriorityTracksAfterIt() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("priority-1"), track("priority-2"), track("context-1"), track("context-2")),
            currentIndex = 0,
            playNextCount = 2,
        ).jumpTo(3)

        assertEquals(track("context-1"), queue.current)
        assertEquals(listOf(track("priority-1"), track("priority-2")), queue.playNext())
        assertEquals(listOf(track("context-2")), queue.contextUpNext())
    }

    @Test
    fun replacingUpcomingTracksKeepsTheCurrentDuplicateOccurrence() {
        val duplicate = track("duplicate")
        val queue = PlaybackQueue(
            tracks = listOf(track("before"), duplicate, track("middle"), duplicate, track("old-upcoming")),
            currentIndex = 3,
        ).replaceUpcomingTracks(
            currentTrack = duplicate,
            upcomingTracks = listOf(track("replacement")),
        )

        assertEquals(
            listOf(track("before"), duplicate, track("middle"), duplicate, track("replacement")),
            queue.tracks,
        )
        assertEquals(3, queue.currentIndex)
        assertEquals(track("replacement"), queue.upNext().single())
    }

    @Test
    fun resolvingTrackOccurrencePrefersTheValidatedQueueIndex() {
        val duplicate = track("duplicate")
        val tracks = listOf(track("before"), duplicate, track("middle"), duplicate)

        assertEquals(3, resolveTrackOccurrenceIndex(tracks, duplicate, preferredIndex = 3))
        assertEquals(1, resolveTrackOccurrenceIndex(tracks, duplicate, preferredIndex = 2))
    }

    @Test
    fun removingPriorityTrackShrinksPriorityBlock() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("priority-1"), track("priority-2"), track("context")),
            currentIndex = 0,
            playNextCount = 2,
        ).removeAt(1)

        assertEquals(listOf(track("priority-2")), queue.playNext())
        assertEquals(listOf(track("context")), queue.contextUpNext())
    }

    @Test
    fun movingExistingPriorityTrackNextPromotesItToTheFront() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("priority-1"), track("priority-2"), track("priority-3"), track("context")),
            currentIndex = 0,
            playNextCount = 3,
        ).moveToNext(2)

        assertEquals(
            listOf(track("priority-2"), track("priority-1"), track("priority-3")),
            queue.playNext(),
        )
        assertEquals(listOf(track("context")), queue.contextUpNext())
    }

    @Test
    fun backToListsRecentTracksFirst() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3")),
            currentIndex = 2,
        )

        assertEquals(listOf(track("2"), track("1")), queue.backTo())
    }

    @Test
    fun invalidCurrentIndexHasNoHistoryOrUpcomingTracks() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2")),
            currentIndex = -1,
        )

        assertEquals(emptyList(), queue.backTo())
        assertEquals(emptyList(), queue.upNext())
        assertEquals(false, queue.hasPrevious())
        assertEquals(false, queue.hasNext())
    }

    @Test
    fun adjacentIndexMovesThroughQueue() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3")),
            currentIndex = 1,
        )

        assertEquals(2, queue.nextIndex())
        assertEquals(0, queue.previousIndex())
    }

    @Test
    fun adjacentIndexCanWrapWithQueueRepeat() {
        val lastTrackQueue = PlaybackQueue(
            tracks = listOf(track("1"), track("2")),
            currentIndex = 1,
        )
        val firstTrackQueue = lastTrackQueue.copy(currentIndex = 0)

        assertEquals(null, lastTrackQueue.nextIndex(repeatMode = RepeatMode.Off))
        assertEquals(0, lastTrackQueue.nextIndex(repeatMode = RepeatMode.Queue))
        assertEquals(1, firstTrackQueue.previousIndex(repeatMode = RepeatMode.Queue))
        assertEquals(null, firstTrackQueue.previousIndex(repeatMode = RepeatMode.Queue, wrapQueue = false))
    }

    @Test
    fun adjacentIndexCanRepeatCurrentTrack() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2")),
            currentIndex = 0,
        )

        assertEquals(0, queue.nextIndex(repeatMode = RepeatMode.Track))
        assertEquals(1, queue.nextIndex(repeatMode = RepeatMode.Track, repeatTrack = false))
    }

    @Test
    fun jumpingToUpcomingKeepsSkippedTracksUpcoming() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3"), track("4")),
            currentIndex = 0,
        ).jumpTo(2)

        assertEquals(track("3"), queue.tracks[queue.currentIndex])
        assertEquals(listOf(track("1")), queue.backTo())
        assertEquals(listOf(track("2"), track("4")), queue.upNext())
    }

    @Test
    fun jumpingToUpcomingCanSkipThroughQueue() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3"), track("4")),
            currentIndex = 0,
        ).jumpTo(2, moveSelectedToCurrent = false)

        assertEquals(track("3"), queue.tracks[queue.currentIndex])
        assertEquals(listOf(track("2"), track("1")), queue.backTo())
        assertEquals(listOf(track("4")), queue.upNext())
    }

    @Test
    fun removeAtRemovesUpcomingTrackWithoutChangingCurrent() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3")),
            currentIndex = 0,
        ).removeAt(1)

        assertEquals(track("1"), queue.current)
        assertEquals(listOf(track("3")), queue.upNext())
    }

    @Test
    fun removeAtAdjustsCurrentIndexWhenRemovingHistory() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3")),
            currentIndex = 2,
        ).removeAt(0)

        assertEquals(1, queue.currentIndex)
        assertEquals(track("3"), queue.current)
        assertEquals(listOf(track("2")), queue.backTo())
    }

    @Test
    fun clearUpcomingKeepsCurrentAndHistory() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3"), track("4")),
            currentIndex = 2,
        ).clearUpcoming()

        assertEquals(track("3"), queue.current)
        assertEquals(listOf(track("2"), track("1")), queue.backTo())
        assertEquals(emptyList(), queue.upNext())
    }

    @Test
    fun jumpingToHistoryMakesLaterHistoryUpcoming() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3"), track("4")),
            currentIndex = 3,
        ).jumpTo(1)

        assertEquals(track("2"), queue.tracks[queue.currentIndex])
        assertEquals(listOf(track("1")), queue.backTo())
        assertEquals(listOf(track("3"), track("4")), queue.upNext())
    }

    @Test
    fun restoringUpcomingPreservesCurrentAndHistory() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("9"), track("8")),
            currentIndex = 1,
        ).restoreUpcoming(listOf(track("3"), track("4")))

        assertEquals(track("2"), queue.tracks[queue.currentIndex])
        assertEquals(listOf(track("1")), queue.backTo())
        assertEquals(listOf(track("3"), track("4")), queue.upNext())
    }

    @Test
    fun shuffleUpcomingReturnsOriginalUpcomingSnapshot() {
        val originalUpcoming = listOf(track("2"), track("3"), track("4"))
        val result = PlaybackQueue(
            tracks = listOf(track("1")) + originalUpcoming,
            currentIndex = 0,
        ).shuffleUpcoming()

        assertNotNull(result)
        val (queue, snapshot) = result
        assertEquals(originalUpcoming, snapshot)
        assertEquals(track("1"), queue.current)
        assertEquals(originalUpcoming.toSet(), queue.upNext().toSet())
    }

    @Test
    fun toggleUpcomingShuffleSwitchesBetweenShuffledAndRestoredQueue() {
        val originalUpcoming = listOf(track("2"), track("3"), track("4"))
        val queue = PlaybackQueue(
            tracks = listOf(track("1")) + originalUpcoming,
            currentIndex = 0,
        )

        val shuffled = assertNotNull(queue.toggleUpcomingShuffle(shuffledSnapshot = null))
        assertEquals(originalUpcoming, shuffled.shuffledSnapshot)
        assertEquals(originalUpcoming.toSet(), shuffled.queue.upNext().toSet())

        val restored = assertNotNull(shuffled.queue.toggleUpcomingShuffle(shuffled.shuffledSnapshot))
        assertEquals(null, restored.shuffledSnapshot)
        assertEquals(originalUpcoming, restored.queue.upNext())
    }

    @Test
    fun moveUpcomingTrackToNextReordersWithoutDuplicating() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3"), track("4")),
            currentIndex = 0,
        ).moveToNext(3)

        assertEquals(listOf(track("1"), track("4"), track("2"), track("3")), queue.tracks)
        assertEquals(0, queue.currentIndex)
        assertEquals(1, queue.playNextCount)
    }

    @Test
    fun moveHistoryTrackToNextPreservesCurrentTrack() {
        val queue = PlaybackQueue(
            tracks = listOf(track("1"), track("2"), track("3"), track("4")),
            currentIndex = 2,
        ).moveToNext(0)

        assertEquals(listOf(track("2"), track("3"), track("1"), track("4")), queue.tracks)
        assertEquals(track("3"), queue.current)
    }

    @Test
    fun moveUpcomingUsesOccurrenceIndexesAndKeepsCurrentPinned() {
        val firstDuplicate = track("duplicate").copy(title = "First occurrence")
        val secondDuplicate = track("duplicate").copy(title = "Second occurrence")
        val queue = PlaybackQueue(
            tracks = listOf(track("history"), track("current"), firstDuplicate, track("middle"), secondDuplicate),
            currentIndex = 1,
            playNextCount = 1,
        ).moveUpcoming(fromIndex = 4, toIndex = 2)

        assertEquals(
            listOf(track("history"), track("current"), secondDuplicate, firstDuplicate, track("middle")),
            queue.tracks,
        )
        assertEquals(track("current"), queue.current)
        assertEquals(1, queue.currentIndex)
        assertEquals(1, queue.playNextCount)
    }

    @Test
    fun moveUpcomingRejectsCurrentHistoryAndOutOfBoundsPositions() {
        val queue = PlaybackQueue(
            tracks = listOf(track("history"), track("current"), track("next")),
            currentIndex = 1,
        )

        assertEquals(queue, queue.moveUpcoming(fromIndex = 1, toIndex = 2))
        assertEquals(queue, queue.moveUpcoming(fromIndex = 0, toIndex = 2))
        assertEquals(queue, queue.moveUpcoming(fromIndex = 2, toIndex = 3))
    }

    @Test
    fun everyReorderSelectionAndTrimKeepsOccurrenceIdentityWithIdenticalTracks() {
        val sameTrack = track("duplicate")
        val queue = PlaybackQueue(
            tracks = List(5) { sameTrack },
            currentIndex = 1,
            playNextCount = 1,
            occurrences = listOf(
                RadioQueueOccurrence("history", "station", "old"),
                RadioQueueOccurrence("current", "station", "old"),
                RadioQueueOccurrence("priority"),
                RadioQueueOccurrence("generated", "station", "old"),
                RadioQueueOccurrence("manual"),
            ),
        )
        val operations = listOf(
            queue.removeAt(0) to listOf("current", "priority", "generated", "manual"),
            queue.moveToNext(4) to listOf("history", "current", "priority", "manual", "generated"),
            queue.moveToPlayNext(4) to listOf("history", "current", "priority", "manual", "generated"),
            queue.moveUpcoming(4, 2) to listOf("history", "current", "manual", "priority", "generated"),
            queue.jumpTo(4) to listOf("history", "current", "manual", "priority", "generated"),
            queue.jumpTo(0) to listOf("history", "priority", "current", "generated", "manual"),
            queue.jumpTo(3, moveSelectedToCurrent = false) to queue.occurrences.map { it.id },
            queue.clearUpcoming() to listOf("history", "current"),
            queue.retainCurrentOnly() to listOf("current"),
            queue.removePlayedHistory() to listOf("current", "priority", "generated", "manual"),
        )
        operations.forEach { (updated, expectedIds) ->
            assertEquals(expectedIds, updated.occurrences.map { it.id })
            assertEquals(updated.tracks.size, updated.occurrences.size)
            updated.occurrences.forEach { occurrence ->
                assertEquals(queue.occurrences.single { it.id == occurrence.id }, occurrence)
            }
        }
    }

    @Test
    fun userInsertionsAndHistoryPruningNeverInheritGeneratedAttribution() {
        val queue = PlaybackQueue(
            tracks = listOf(track("history"), track("same"), track("next")),
            currentIndex = 1,
            occurrences = newRadioQueueOccurrences(3, "station", "batch"),
        )
        val appended = queue.appendTracks(listOf(track("same"), track("same")), maxHistory = 0)
        assertEquals(listOf("same", "next", "same", "same"), appended.tracks.map { it.id.value })
        assertEquals(queue.occurrences.drop(1), appended.occurrences.take(2))
        assertTrue(appended.occurrences.takeLast(2).all { !it.generated })
        assertEquals(4, appended.occurrences.map { it.id }.toSet().size)
        val priority = queue.playNextTracks(listOf(track("same"), track("same")), maxHistory = 0)
        assertEquals(queue.occurrences[1], priority.occurrences[0])
        assertTrue(priority.occurrences.drop(1).take(2).all { !it.generated })
        assertEquals(queue.occurrences.last(), priority.occurrences.last())
        val single = queue.playNextTrack(track("same"), maxHistory = 0)
        assertFalse(single.occurrences[1].generated)
        assertEquals(queue.occurrences.last(), single.occurrences.last())
    }

    @Test
    fun generatedReplacementRetainsHistoryCurrentPriorityAndManualDuplicateOccurrences() {
        val queue = PlaybackQueue(
            tracks = listOf(track("history"), track("same"), track("priority"), track("same"), track("same"), track("old")),
            currentIndex = 1,
            playNextCount = 1,
            occurrences = listOf(
                RadioQueueOccurrence("history", "station", "old"),
                RadioQueueOccurrence("current", "station", "old"),
                RadioQueueOccurrence("priority", "station", "old"),
                RadioQueueOccurrence("generated-copy", "station", "old"),
                RadioQueueOccurrence("manual-copy"),
                RadioQueueOccurrence("old", "station", "old"),
            ),
        )
        val replaced = queue.replaceGeneratedUpcomingOccurrences(
            listOf(track("same"), track("new"), track("new")), "station", "fresh",
        )
        assertEquals(listOf("history", "same", "priority", "same", "new"), replaced.tracks.map { it.id.value })
        assertEquals(listOf("history", "current", "priority", "manual-copy"), replaced.occurrences.dropLast(1).map { it.id })
        assertEquals(1, replaced.currentIndex)
        assertEquals(1, replaced.playNextCount)
        assertEquals("fresh", replaced.occurrences.last().batchId)
        val emptied = queue.replaceGeneratedUpcomingOccurrences(emptyList(), "station", "empty")
        assertEquals(replaced.occurrences.dropLast(1), emptied.occurrences)
    }

    @Test
    fun shuffleRoundTripPreservesGeneratedAndManualCopiesOfTheSameTrack() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("priority"), track("same"), track("other"), track("same")),
            currentIndex = 0,
            playNextCount = 1,
            occurrences = listOf(
                RadioQueueOccurrence("current", "station", "batch"), RadioQueueOccurrence("priority"),
                RadioQueueOccurrence("generated", "station", "batch"), RadioQueueOccurrence("other"),
                RadioQueueOccurrence("manual"),
            ),
        )
        val shuffled = assertNotNull(queue.toggleUpcomingShuffle(null, UpcomingShufflePolicy(Random(4))))
        assertEquals(queue.occurrences.take(2), shuffled.queue.occurrences.take(2))
        shuffled.queue.occurrences.forEachIndexed { index, occurrence ->
            assertEquals(queue.tracks[queue.occurrences.indexOf(occurrence)], shuffled.queue.tracks[index])
        }
        val restored = assertNotNull(shuffled.queue.toggleUpcomingShuffle(
            shuffled.shuffledSnapshot, shuffledOccurrences = shuffled.shuffledOccurrences,
        ))
        assertEquals(queue, restored.queue)
    }

    private fun track(id: String): Track =
        Track(
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
