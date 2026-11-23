package app.naviamp.ui

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Playlist
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals

class NaviampMediaDurationTest {
    @Test
    fun collectionTotalsKeepMinutePrecisionAcrossHourBoundaries() {
        for ((seconds, expected) in listOf(59 to "0m", 60 to "1m", 3599 to "59m", 3600 to "1h 0m", 7440 to "2h 4m")) {
            assertEquals(expected, listOf(track(0, seconds)).totalDurationLabel())
        }
        assertEquals("1m", listOf(track(0, 45), track(1, 45)).totalDurationLabel())
    }

    @Test
    fun missingDurationsDoNotInventTimeOrHideKnownDurations() {
        assertEquals("", emptyList<Track>().totalDurationLabel())
        assertEquals("", listOf(track(0, null), track(1, 0)).totalDurationLabel())
        assertEquals("3m", listOf(track(0, null), track(1, 180)).totalDurationLabel())
    }

    @Test
    fun largeCollectionsDoNotOverflowTheSecondsAccumulator() {
        assertEquals("1193046h 28m", listOf(track(0, Int.MAX_VALUE), track(1, Int.MAX_VALUE)).totalDurationLabel())
    }

    @Test
    fun albumAndPlaylistDetailsCarryTheTotalOfTheirLoadedTracks() {
        val tracks = List(12) { track(it, 255) }
        val album = AlbumDetails(Album(AlbumId("album"), "Album", "Artist", null, null), tracks)
            .toSharedAlbumDetailUi({ null })
        val playlist = Playlist("playlist", "Playlist", 99).toSharedPlaylistDetailUi(tracks, { null })
        assertEquals("51m", album.totalDurationLabel)
        assertEquals("51m", playlist.totalDurationLabel)
        assertEquals(tracks.map { it.id.value }, playlist.tracks.map { it.id })
    }

    private fun track(index: Int, seconds: Int?) = Track(
        id = TrackId("track-$index"), title = "Track $index", artistName = "Artist", albumTitle = "Album",
        durationSeconds = seconds, coverArtId = null, audioInfo = null, replayGain = null,
    )
}
