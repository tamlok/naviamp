package app.naviamp.domain.radio

import app.naviamp.domain.AlbumId
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RadioCandidateSelectionTest {
    @Test
    fun balancesSeedPrefixesWithoutMultiplyingTheBatch() {
        val candidates = (0..2).flatMap { seed -> (1..10).map { index ->
            RadioCandidate(track("$seed-$index", artist = "artist-$seed"), seed, 1.0 - index / 100.0)
        } }
        val selected = selectRadioCandidates(candidates, 7)
        assertEquals(listOf("0-1", "1-1", "2-1", "0-2", "1-2", "2-2", "0-3"), selected.map { it.id.value })
    }

    @Test
    fun removesDuplicatesSeedsQueueAndRecentIdsBeforeSpacing() {
        val candidates = listOf("seed", "queue", "recent", "one", "one", "two").map {
            RadioCandidate(track(it))
        }
        val selected = selectRadioCandidates(candidates, 10,
            excludedTrackIds = setOf("seed", "queue", "recent").mapTo(mutableSetOf(), ::TrackId))
        assertEquals(listOf("one", "two"), selected.map { it.id.value })
        assertEquals(emptyList(), selectRadioCandidates(candidates, 10,
            excludedTrackIds = candidates.mapTo(mutableSetOf()) { it.track.id }))
    }

    @Test
    fun spacesAgainstManualTailAndPrefersAnotherAlbumWhenPossible() {
        val previous = track("manual", artist = "a", album = "one")
        val candidates = listOf(track("a", artist = "a"), track("b", artist = "b"))
        val selected = selectRadioCandidates(candidates.map { RadioCandidate(it) }, 2, previousTrack = previous)
        assertEquals(listOf("b", "a"), selected.map { it.id.value })
        val albumSpaced = selectRadioCandidates(listOf(
            RadioCandidate(track("same-album", artist = "b", album = "one")),
            RadioCandidate(track("other-album", artist = "b", album = "two"))), 1, previousTrack = previous)
        assertEquals("other-album", albumSpaced.single().id.value)
    }

    @Test
    fun broadSpreadAvoidsRecentlyUsedArtistsButNeverImposesAHardQuota() {
        val candidates = listOf(track("a1", "a"), track("a2", "a"), track("b1", "b"),
            track("b2", "b"), track("c1", "c"), track("d1", "d"))
        val broad = selectRadioCandidates(candidates.map { RadioCandidate(it) }, 6,
            tuning = RadioTuningSettings(artistSpread = RadioArtistSpread.Broad))
        assertEquals(listOf("a1", "b1", "c1", "d1"), broad.take(4).map { it.id.value })
        assertEquals(6, broad.size)
        val single = selectRadioCandidates(candidates.map { RadioCandidate(it) }, 10,
            tuning = RadioTuningSettings(artistSpread = RadioArtistSpread.Broad, artistRunMode = RadioArtistRunMode.SingleArtist),
            anchorArtistId = ArtistId("a"))
        assertEquals(listOf("a1", "a2"), single.map { it.id.value })
    }

    @Test
    fun strictArtistAndDecadeNeverBroadenEmptyResultsOrTrustNames() {
        val candidates = listOf(track("unknown", "a").copy(artistId = null), track("wrong", "b", year = 2002))
        val tuning = RadioTuningSettings(sameDecadeOnly = true, artistRunMode = RadioArtistRunMode.SingleArtist)
        assertEquals(emptyList(), selectRadioCandidates(candidates.map { RadioCandidate(it) }, 10, tuning,
            anchorArtistId = ArtistId("a"), decadeStart = 1990))
        assertFailsWith<RadioRecipeValidationException> {
            selectRadioCandidates(emptyList(), 10, tuning, anchorArtistId = ArtistId("a"))
        }
        assertFailsWith<RadioRecipeValidationException> {
            selectRadioCandidates(emptyList(), 10, tuning, decadeStart = 1990)
        }
    }

    @Test
    fun eraConstraintsKeepReissuesAndRejectUnknownEra() {
        val candidates = listOf(track("reissue", year = 2020).copy(originalReleaseYear = 1978),
            track("unknown"), track("modern", year = 2020), track("original", year = 1971))
        val selected = selectRadioCandidates(candidates.map { RadioCandidate(it) }, 10,
            tuning = RadioTuningSettings(sameDecadeOnly = true), decadeStart = 1970)
        assertEquals(listOf("reissue", "original"), selected.map { it.id.value })
    }

    @Test
    fun deepCutsPreferLowPlaysWithoutPromotingNegativeRatings() {
        val candidates = listOf(track("negative").copy(userRating = 1, playCount = 0),
            track("popular").copy(playCount = 100), track("rare").copy(playCount = 2),
            track("favorite").copy(playCount = 0, favoritedAtIso8601 = "2026-01-01T00:00:00Z"))
        val selected = selectRadioCandidates(candidates.map { RadioCandidate(it) }, 10,
            tuning = RadioTuningSettings(familiarity = RadioFamiliarity.DeepCuts))
        assertEquals(listOf("favorite", "rare", "popular", "negative"), selected.map { it.id.value })
    }

    @Test
    fun artistBlocksCarryThePreviousArtistAcrossTheSeam() {
        val candidates = (1..4).map { RadioCandidate(track("a$it", "a")) } +
            (1..3).map { RadioCandidate(track("b$it", "b")) }
        val selected = selectRadioCandidates(candidates, 7,
            tuning = RadioTuningSettings(artistRunMode = RadioArtistRunMode.ArtistBlocks,
                sameArtistRunLength = 2, otherArtistRunLength = 1),
            anchorArtistId = ArtistId("a"), previousTrack = track("previous", "a"))
        assertEquals(listOf("a1", "b1", "a2", "a3", "b2", "a4", "b3"), selected.map { it.id.value })
    }

    @Test
    fun genreYearAndAlbumConstraintsIntersectRatherThanFallback() {
        val candidates = listOf(track("valid", year = 1994).copy(genres = listOf("Jazz")),
            track("wrong-genre", year = 1994).copy(genres = listOf("Rock")),
            track("excluded-album", year = 1994, album = "excluded").copy(genres = listOf("Jazz")),
            track("unknown", year = 1994))
        val selected = selectRadioCandidates(candidates.map { RadioCandidate(it) }, 10,
            fromYear = 1990, toYear = 1999, genres = listOf("jazz"), excludedAlbumIds = setOf("excluded"))
        assertEquals(listOf("valid"), selected.map { it.id.value })
        assertTrue(selectRadioCandidates(listOf(RadioCandidate(track("discovery-preview:42"))), 1).isEmpty())
    }

    private fun track(id: String, artist: String = "artist", album: String = "album", year: Int? = null) = Track(
        id = TrackId(id), title = id, artistId = ArtistId(artist), artistName = artist,
        albumId = AlbumId(album), albumTitle = album, albumReleaseYear = year,
        durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
    )
}
