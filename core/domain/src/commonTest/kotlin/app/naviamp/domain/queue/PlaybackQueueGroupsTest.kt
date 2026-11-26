package app.naviamp.domain.queue

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.playback.PlaybackProfile
import app.naviamp.domain.playback.PlaybackProfileTarget
import app.naviamp.domain.playback.PlaybackProfileTargetType
import app.naviamp.domain.playback.PlaybackTransitionMode
import app.naviamp.domain.radio.RadioQueueOccurrence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueGroupsTest {
    @Test
    fun radioRegenerationKeepsTheGroupOnTheCurrentAndRetainedTracks() {
        // History "one", current "two", album group over one..four; Radio replaces "three", "four".
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three"), track("four")),
            currentIndex = 1,
            groups = listOf(albumGroup(start = 0, end = 4)),
        )

        val regenerated = queue.replaceUpcomingTracks(track("two"), listOf(track("radio-a"), track("radio-b")))

        assertEquals(listOf("one", "two", "radio-a", "radio-b"), regenerated.tracks.map { it.id.value })
        val group = regenerated.groupAt()
        assertEquals("album", group?.id, "The current track keeps its album group")
        assertEquals(PlaybackTransitionMode.Gapless, group?.profile?.transitionMode)
        assertEquals(0 until 2, group!!.startIndex until group.endIndexExclusive, "Only retained tracks stay in it")
        assertNull(regenerated.groupForTransition(toIndex = 2), "Radio tracks leave the album, so its profile ends")
    }

    @Test
    fun radioRegenerationKeepsPlayNextGroupsAndPrunesHistoryLikeOtherEdits() {
        val queue = PlaybackQueue(
            tracks = listOf(track("old"), track("current"), track("next-1"), track("next-2"), track("ctx")),
            currentIndex = 1,
            playNextCount = 2,
            groups = listOf(albumGroup(id = "queued", start = 2, end = 4), albumGroup(id = "context", start = 4, end = 5)),
        )

        val regenerated = queue.replaceUpcomingTracks(track("current"), listOf(track("radio")), maxHistory = 0)

        assertEquals(listOf("current", "next-1", "next-2", "radio"), regenerated.tracks.map { it.id.value })
        assertEquals(listOf("queued" to (1 until 3)),
            regenerated.normalizedGroups().map { it.id to (it.startIndex until it.endIndexExclusive) },
            "The play-next album survives; the replaced context track's group is dropped")
    }

    @Test
    fun radioRegenerationOfAnUngroupedQueueStaysUngrouped() {
        val queue = PlaybackQueue(listOf(track("one"), track("two"), track("three")), currentIndex = 0)
        val regenerated = queue.replaceUpcomingTracks(track("one"), listOf(track("radio")))
        assertEquals(listOf("one", "radio"), regenerated.tracks.map { it.id.value })
        assertTrue(regenerated.groups.isEmpty())
    }

    @Test
    fun radioRegenerationRemapsGroupsAndDuplicateOccurrencesTogetherWhenPruningHistory() {
        val queue = PlaybackQueue(
            tracks = listOf(track("old"), track("same"), track("history"), track("same"),
                track("same"), track("priority"), track("context")),
            currentIndex = 3,
            playNextCount = 2,
            groups = listOf(
                albumGroup(id = "old", start = 0, end = 2),
                albumGroup(id = "active", start = 2, end = 4),
                albumGroup(id = "queued", start = 4, end = 6),
                albumGroup(id = "context", start = 6, end = 7),
            ),
            occurrences = listOf(
                RadioQueueOccurrence("old", "station", "old"),
                RadioQueueOccurrence("earlier-copy", "station", "old"),
                RadioQueueOccurrence("history", "station", "old"),
                RadioQueueOccurrence("current-copy", "station", "old"),
                RadioQueueOccurrence("priority-copy"),
                RadioQueueOccurrence("priority"),
                RadioQueueOccurrence("context", "station", "old"),
            ),
        )

        val regenerated = queue.replaceUpcomingTracks(
            track("same"), listOf(track("same"), track("history"), track("context"), track("radio")), maxHistory = 1,
        )

        assertEquals(listOf("history", "same", "same", "priority", "context", "radio"),
            regenerated.tracks.map { it.id.value })
        assertEquals(1, regenerated.currentIndex)
        assertEquals(2, regenerated.playNextCount)
        assertEquals(queue.occurrences.subList(2, 6), regenerated.occurrences.take(4))
        assertEquals(listOf(
            queue.groups[1].copy(startIndex = 0, endIndexExclusive = 2),
            queue.groups[2].copy(startIndex = 2, endIndexExclusive = 4),
        ), regenerated.groups)
        assertEquals("active", regenerated.groupAt()?.id)
        assertEquals("queued", regenerated.groupForTransition(fromIndex = 2, toIndex = 3)?.id)
        assertNull(regenerated.groupForTransition(fromIndex = 3, toIndex = 4))
        assertNull(regenerated.groupAt(4), "A replacement with the same track ID must not inherit the old group")
        assertTrue(regenerated.occurrences.drop(4).all { fresh ->
            !fresh.generated && fresh.batchId == null && queue.occurrences.none { it.id == fresh.id }
        })
        assertEquals(regenerated.tracks.size, regenerated.occurrences.size)
        assertEquals(regenerated.occurrences.size, regenerated.occurrences.map { it.id }.toSet().size)
    }

    @Test
    fun generatedRegenerationKeepsManualDuplicateGroupsAndOccurrenceAttributionTogether() {
        val queue = PlaybackQueue(
            tracks = listOf(track("history"), track("current"), track("priority"),
                track("same"), track("same"), track("old")),
            currentIndex = 1,
            playNextCount = 1,
            groups = listOf(
                albumGroup(id = "active", start = 0, end = 2),
                albumGroup(id = "queued", start = 2, end = 3),
                albumGroup(id = "generated", start = 3, end = 4),
                albumGroup(id = "manual", start = 4, end = 5),
                albumGroup(id = "old", start = 5, end = 6),
            ),
            occurrences = listOf(
                RadioQueueOccurrence("history", "station", "old"),
                RadioQueueOccurrence("current", "station", "old"),
                RadioQueueOccurrence("priority", "station", "old"),
                RadioQueueOccurrence("generated-copy", "station", "old"),
                RadioQueueOccurrence("manual-copy"),
                RadioQueueOccurrence("old", "station", "old"),
            ),
        )

        val regenerated = queue.replaceGeneratedUpcomingOccurrences(
            listOf(track("same"), track("new"), track("new")), "station", "fresh",
        )

        assertEquals(listOf("history", "current", "priority", "same", "new"),
            regenerated.tracks.map { it.id.value })
        assertEquals(1, regenerated.currentIndex)
        assertEquals(1, regenerated.playNextCount)
        assertEquals(queue.occurrences.take(3) + queue.occurrences[4], regenerated.occurrences.take(4))
        assertEquals(listOf(queue.groups[0], queue.groups[1],
            queue.groups[3].copy(startIndex = 3, endIndexExclusive = 4)), regenerated.groups)
        assertEquals("manual", regenerated.groupAt(3)?.id)
        assertNull(regenerated.groupAt(4))
        val fresh = regenerated.occurrences.last()
        assertEquals("station", fresh.stationId)
        assertEquals("fresh", fresh.batchId)
        assertTrue(queue.occurrences.none { it.id == fresh.id })
        assertEquals(regenerated.tracks.size, regenerated.occurrences.size)
        assertEquals(regenerated.occurrences.size, regenerated.occurrences.map { it.id }.toSet().size)

        val emptied = queue.replaceGeneratedUpcomingOccurrences(emptyList(), "station", "empty")
        assertEquals(regenerated.groups, emptied.groups)
        assertEquals(regenerated.occurrences.dropLast(1), emptied.occurrences)
    }

    @Test
    fun resolvesTheGroupContainingTheCurrentOccurrence() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three")),
            currentIndex = 1,
            groups = listOf(albumGroup(start = 0, end = 3)),
        )

        assertEquals("album", queue.groupAt()?.id)
        assertEquals(PlaybackTransitionMode.Gapless, queue.groupAt()?.profile?.transitionMode)
        assertEquals("album", queue.groupForTransition(toIndex = 2)?.id)
        assertNull(queue.groupForTransition(toIndex = 3))
    }

    @Test
    fun normalizationClampsRangesAndRejectsOverlaps() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three")),
            currentIndex = 0,
            groups = listOf(
                albumGroup(id = "first", start = -4, end = 2),
                albumGroup(id = "overlap", start = 1, end = 3),
                albumGroup(id = "last", start = 2, end = 20),
            ),
        )

        assertEquals(listOf("first", "last"), queue.normalizedGroups().map { it.id })
        assertEquals(0, queue.normalizedGroups().first().startIndex)
        assertEquals(3, queue.normalizedGroups().last().endIndexExclusive)
        assertNull(queue.groupAt(index = 8))
    }

    @Test
    fun appendingTracksPreservesExistingGroupBoundaries() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two")),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 0, end = 2)),
        )

        val appended = queue.appendTracks(listOf(track("three")))

        assertEquals(0, appended.groups.single().startIndex)
        assertEquals(2, appended.groups.single().endIndexExclusive)
    }

    @Test
    fun appendingAProfiledCollectionAddsItsOwnGroup() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current")),
            currentIndex = 0,
        )

        val appended = queue.appendGroupedTracks(
            tracks = listOf(track("album-one"), track("album-two")),
            group = albumGroup(start = 0, end = 0),
        )

        assertEquals(listOf("current", "album-one", "album-two"), appended.tracks.map { it.id.value })
        assertEquals(1, appended.groups.single().startIndex)
        assertEquals(3, appended.groups.single().endIndexExclusive)
    }

    @Test
    fun playingAProfiledCollectionNextKeepsTheCurrentGroupTogether() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three")),
            currentIndex = 0,
            groups = listOf(albumGroup(id = "current-album", start = 0, end = 3)),
        )

        val inserted = queue.playNextGroupedTracks(
            tracks = listOf(track("inserted-one"), track("inserted-two")),
            group = albumGroup(id = "inserted-album", start = 0, end = 0),
        )

        assertEquals(
            listOf("one", "two", "three", "inserted-one", "inserted-two"),
            inserted.tracks.map { it.id.value },
        )
        assertEquals(
            listOf(0 to 3, 3 to 5),
            inserted.groups.map { it.startIndex to it.endIndexExclusive },
        )
        assertEquals("current-album", inserted.groupAt(1)?.id)
        assertEquals("inserted-album", inserted.groupAt(3)?.id)
        assertEquals(listOf("two", "three", "inserted-one", "inserted-two"), inserted.playNext().map { it.id.value })
    }

    @Test
    fun playNextTrackInterruptsThenResumesTheCurrentGroup() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three"), track("context")),
            currentIndex = 0,
            groups = listOf(albumGroup(id = "current-album", start = 0, end = 3)),
        ).playNextTracks(listOf(track("after-album")))

        val inserted = queue.playNextTrack(track("interrupt"))

        assertEquals(
            listOf("one", "interrupt", "two", "three", "after-album", "context"),
            inserted.tracks.map { it.id.value },
        )
        assertEquals(
            listOf(0 to 1, 2 to 4),
            inserted.groups.map { it.startIndex to it.endIndexExclusive },
        )
        assertEquals(
            listOf("interrupt", "two", "three", "after-album"),
            inserted.playNext().map { it.id.value },
        )
    }

    @Test
    fun repeatedGroupAwarePlayNextRequestsKeepRequestOrderAfterTheGroup() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three"), track("context")),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 0, end = 3)),
        )
            .playNextTracks(listOf(track("first-request")))
            .playNextTracks(listOf(track("second-request")))

        assertEquals(
            listOf("one", "two", "three", "first-request", "second-request", "context"),
            queue.tracks.map { it.id.value },
        )
        assertEquals(
            listOf("two", "three", "first-request", "second-request"),
            queue.playNext().map { it.id.value },
        )
    }

    @Test
    fun playNextDoesNotMergeASeparateLaterLaunchOfTheSameAlbum() {
        val queue = PlaybackQueue(
            tracks = listOf(track("first-one"), track("first-two"), track("second-one"), track("second-two")),
            currentIndex = 0,
            groups = listOf(
                albumGroup(start = 0, end = 2),
                albumGroup(start = 2, end = 4),
            ),
        ).playNextTracks(listOf(track("after-first-launch")))

        assertEquals(
            listOf("first-one", "first-two", "after-first-launch", "second-one", "second-two"),
            queue.tracks.map { it.id.value },
        )
        assertEquals(
            listOf(0 to 2, 3 to 5),
            queue.groups.map { it.startIndex to it.endIndexExclusive },
        )
    }

    @Test
    fun shuffleCannotMoveTheCurrentGroupOrDeferredPlayNextTracks() {
        val queue = PlaybackQueue(
            tracks = listOf(
                track("one"),
                track("two"),
                track("three"),
                track("context-one"),
                track("context-two"),
            ),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 0, end = 3)),
        ).playNextTracks(listOf(track("after-album")))

        val shuffled = queue.shuffleUpcoming()?.first

        assertEquals(
            listOf("two", "three", "after-album"),
            shuffled?.playNext()?.map { it.id.value },
        )
    }

    @Test
    fun movingAnExistingOccurrenceToPlayNextPlacesItAfterTheCurrentGroup() {
        val queue = PlaybackQueue(
            tracks = listOf(
                track("one"),
                track("two"),
                track("three"),
                track("context-before"),
                track("selected"),
                track("context-after"),
            ),
            currentIndex = 0,
            groups = listOf(albumGroup(id = "current-album", start = 0, end = 3)),
        )

        val moved = queue.moveToPlayNext(4)

        assertEquals(
            listOf("one", "two", "three", "selected", "context-before", "context-after"),
            moved.tracks.map { it.id.value },
        )
        assertEquals(listOf("two", "three", "selected"), moved.playNext().map { it.id.value })
        assertEquals(0 to 3, moved.groups.single().let { it.startIndex to it.endIndexExclusive })
    }

    @Test
    fun pruningPlayedHistoryShiftsAndClampsGroupBoundaries() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three")),
            currentIndex = 1,
            groups = listOf(albumGroup(start = 0, end = 3)),
        )

        val pruned = queue.removePlayedHistory()

        assertEquals(0, pruned.groups.single().startIndex)
        assertEquals(2, pruned.groups.single().endIndexExclusive)
    }

    @Test
    fun selectingALaterTrackKeepsTheProfileAttachedToItsAlbumTracks() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three"), track("four")),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 0, end = 4)),
        )

        val selected = queue.jumpTo(2)

        assertEquals(listOf("one", "three", "two", "four"), selected.tracks.map { it.id.value })
        assertEquals("three", selected.current?.id?.value)
        assertEquals("album", selected.groupAt()?.id)
        assertEquals(0, selected.groups.single().startIndex)
        assertEquals(4, selected.groups.single().endIndexExclusive)
    }

    @Test
    fun selectingIntoAProfiledGroupSplitsItAroundSkippedNonGroupTracks() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("unprofiled"), track("album-one"), track("album-two")),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 2, end = 4)),
        )

        val selected = queue.jumpTo(3)

        assertEquals(
            listOf("current", "album-two", "unprofiled", "album-one"),
            selected.tracks.map { it.id.value },
        )
        assertEquals("album", selected.groupAt()?.id)
        assertNull(selected.groupAt(2))
        assertEquals("album:reordered:3", selected.groupAt(3)?.id)
    }

    @Test
    fun structuralQueueEditsDissolveGroups() {
        val queue = PlaybackQueue(
            tracks = listOf(track("one"), track("two"), track("three")),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 0, end = 3)),
        )

        assertTrue(queue.removeAt(2).groups.isEmpty())
        assertTrue(queue.moveToNext(2).groups.isEmpty())
        assertTrue(queue.shuffleUpcoming()!!.first.groups.isEmpty())
    }

    @Test
    fun arbitraryUpcomingMoveCarriesProfiledOccurrencesToTheirNewPositions() {
        val queue = PlaybackQueue(
            tracks = listOf(track("current"), track("unprofiled"), track("album-one"), track("album-two")),
            currentIndex = 0,
            groups = listOf(albumGroup(start = 2, end = 4)),
        )

        val moved = queue.moveUpcoming(fromIndex = 3, toIndex = 1)

        assertEquals(listOf("current", "album-two", "unprofiled", "album-one"), moved.tracks.map { it.id.value })
        assertEquals("album", moved.groupAt(1)?.id)
        assertNull(moved.groupAt(2))
        assertEquals(PlaybackTransitionMode.Gapless, moved.groupAt(3)?.profile?.transitionMode)
    }
}

private fun albumGroup(
    id: String = "album",
    start: Int,
    end: Int,
) = PlaybackQueueGroup(
    id = id,
    target = PlaybackProfileTarget(PlaybackProfileTargetType.Album, "album-id"),
    label = "Album",
    startIndex = start,
    endIndexExclusive = end,
    profile = PlaybackProfile(transitionMode = PlaybackTransitionMode.Gapless),
)

private fun track(id: String) = Track(
    id = TrackId(id),
    title = id,
    artistName = "Artist",
    albumTitle = "Album",
    durationSeconds = null,
    coverArtId = null,
    audioInfo = null,
    replayGain = null,
)
