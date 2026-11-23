package app.naviamp.domain.playback

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistPlaybackBookmarksTest {
    @Test
    fun resolvesTrackIdentityAfterPlaylistReordering() {
        val bookmark = PlaylistPlaybackBookmark(TrackId("current"), occurrence = 0, positionSeconds = 42.5)

        assertEquals(
            PlaylistPlaybackResume(index = 0, positionSeconds = 42.5),
            resolvePlaylistPlaybackBookmark(listOf(track("current"), track("before"), track("after")), bookmark),
        )
        assertEquals(
            PlaylistPlaybackResume(index = 2, positionSeconds = 42.5),
            resolvePlaylistPlaybackBookmark(listOf(track("after"), track("before"), track("current")), bookmark),
        )
    }

    @Test
    fun resolvesTheRequestedDuplicateOccurrenceNotTheFirstMatchingTrack() {
        val tracks = listOf(track("duplicate"), track("between"), track("duplicate"), track("duplicate"))

        assertEquals(
            PlaylistPlaybackResume(index = 2, positionSeconds = 19.0),
            resolvePlaylistPlaybackBookmark(tracks, PlaylistPlaybackBookmark(TrackId("duplicate"), 1, 19.0)),
        )
        assertEquals(
            PlaylistPlaybackResume(index = 3, positionSeconds = 23.0),
            resolvePlaylistPlaybackBookmark(tracks, PlaylistPlaybackBookmark(TrackId("duplicate"), 2, 23.0)),
        )
    }

    @Test
    fun absentBookmarksTracksOrOccurrencesReturnNoResume() {
        val tracks = listOf(track("present"))
        val bookmark = PlaylistPlaybackBookmark(TrackId("present"), 0, 10.0)

        assertNull(resolvePlaylistPlaybackBookmark(tracks, null))
        assertNull(resolvePlaylistPlaybackBookmark(emptyList(), bookmark))
        assertNull(resolvePlaylistPlaybackBookmark(tracks, bookmark.copy(trackId = TrackId("removed"))))
        assertNull(resolvePlaylistPlaybackBookmark(tracks, bookmark.copy(occurrence = 1)))
        assertNull(resolvePlaylistPlaybackBookmark(tracks, bookmark.copy(occurrence = -1)))
    }

    @Test
    fun invalidPositionsResumeTheResolvedTrackFromZero() {
        val tracks = listOf(track("before"), track("current"))
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { position ->
            assertEquals(
                PlaylistPlaybackResume(index = 1, positionSeconds = 0.0),
                resolvePlaylistPlaybackBookmark(tracks, PlaylistPlaybackBookmark(TrackId("current"), 0, position)),
                "position=$position",
            )
        }
    }

    @Test
    fun knownDurationAdvancesToTheNextOccurrenceAtZero() {
        val tracks = listOf(track("duplicate", durationSeconds = 60), track("duplicate"), track("next"))
        listOf(60.0, 90.0).forEach { position ->
            assertEquals(
                PlaylistPlaybackResume(index = 1, positionSeconds = 0.0),
                resolvePlaylistPlaybackBookmark(tracks, PlaylistPlaybackBookmark(TrackId("duplicate"), 0, position)),
            )
        }
        assertEquals(
            PlaylistPlaybackResume(index = 0, positionSeconds = 59.5),
            resolvePlaylistPlaybackBookmark(tracks, PlaylistPlaybackBookmark(TrackId("duplicate"), 0, 59.5)),
        )
    }

    @Test
    fun aCompletedLastTrackReturnsNoResumeSoWholePlayStartsFresh() {
        val tracks = listOf(track("first"), track("last", durationSeconds = 60))
        listOf(60.0, 90.0).forEach { position ->
            assertNull(resolvePlaylistPlaybackBookmark(tracks, PlaylistPlaybackBookmark(TrackId("last"), 0, position)))
        }
    }

    @Test
    fun unknownOrInvalidDurationDoesNotDiscardAValidPosition() {
        listOf(null, 0, -1).forEach { duration ->
            assertEquals(
                PlaylistPlaybackResume(index = 0, positionSeconds = 500.0),
                resolvePlaylistPlaybackBookmark(
                    listOf(track("current", durationSeconds = duration)),
                    PlaylistPlaybackBookmark(TrackId("current"), 0, 500.0),
                ),
            )
        }
    }

    @Test
    fun memoryRepositoryIsolatesPlaylistAndSourceKeysAndDeletesOnlyTheRequestedBookmark() {
        val repository = MemoryPlaylistPlaybackBookmarkRepository()
        val first = PlaylistPlaybackBookmark(TrackId("first"), 1, 10.5)
        val second = PlaylistPlaybackBookmark(TrackId("second"), 0, 20.0)
        val otherSource = PlaylistPlaybackBookmark(TrackId("other"), 2, 30.0)
        repository.saveBookmark("source", "first", first)
        repository.saveBookmark("source", "second", second)
        repository.saveBookmark("other-source", "first", otherSource)

        assertEquals(first, repository.loadBookmark("source", "first"))
        assertEquals(second, repository.loadBookmark("source", "second"))
        assertEquals(otherSource, repository.loadBookmark("other-source", "first"))
        assertNull(repository.loadBookmark("missing-source", "first"))
        repository.saveBookmark("source", "first", first.copy(positionSeconds = 11.0))
        assertEquals(first.copy(positionSeconds = 11.0), repository.loadBookmark("source", "first"))
        repository.saveBookmark("source", "first", null)
        assertNull(repository.loadBookmark("source", "first"))
        assertEquals(second, repository.loadBookmark("source", "second"))
        assertEquals(otherSource, repository.loadBookmark("other-source", "first"))
    }

    private fun track(id: String, durationSeconds: Int? = 120) = Track(
        id = TrackId(id),
        title = id,
        artistName = "Artist",
        albumTitle = null,
        durationSeconds = durationSeconds,
        coverArtId = null,
        audioInfo = null,
        replayGain = null,
    )
}
