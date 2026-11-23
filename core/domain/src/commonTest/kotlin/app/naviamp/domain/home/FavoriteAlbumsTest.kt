package app.naviamp.domain.home

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.radio.albumRecentRadioStream
import app.naviamp.domain.radio.randomAlbumRecentRadioStream
import app.naviamp.domain.settings.FavoriteArtistSort
import app.naviamp.domain.settings.RecentRadioKind
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.settings.SavedAlbum
import app.naviamp.domain.settings.SavedTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteAlbumsTest {
    @Test
    fun eachModeUsesItsOwnOrdering() {
        val alpha = album("a", "Alpha", "2026-01-01T00:00:00Z")
        val beta = album("b", "Beta", "favorite")
        val zulu = album("z", "Zulu", "2026-02-01T00:00:00Z")
        val albums = listOf(zulu, beta, alpha)
        val history = listOf(randomAlbumRecentRadioStream(beta), albumRecentRadioStream(alpha))

        assertEquals(listOf("a", "b", "z"), albums.sortedFavoriteAlbums(FavoriteArtistSort.Name, history).ids())
        assertEquals(listOf("z", "a", "b"), albums.sortedFavoriteAlbums(FavoriteArtistSort.DateFavorited, history).ids())
        assertEquals(listOf("b", "a", "z"), albums.sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed, history).ids())
    }

    @Test
    fun unknownFavoriteDatesStayLastAndAllTiesUseTitleCaseThenIdentity() {
        val albums = listOf(
            album("lower", "alpha", "2026-01-01T00:00:00Z"),
            album("upper-2", "Alpha", "2026-01-01T00:00:00Z"),
            album("upper-1", "Alpha", "2026-01-01T00:00:00Z"),
            album("sentinel", "C unknown", "favorite"),
            album("blank", "B unknown", "  "),
            album("missing", "A unknown"),
            album("newest", "Zulu", "2026-02-01T00:00:00Z"),
        )

        assertEquals(
            listOf("newest", "upper-1", "upper-2", "lower", "missing", "blank", "sentinel"),
            albums.sortedFavoriteAlbums(FavoriteArtistSort.DateFavorited).ids(),
        )
        val nameOrder = listOf("missing", "upper-1", "upper-2", "lower", "blank", "sentinel", "newest")
        assertEquals(nameOrder, albums.sortedFavoriteAlbums(FavoriteArtistSort.Name).ids())
        assertEquals(nameOrder, albums.sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed).ids())
    }

    @Test
    fun newestPersistedOccurrenceWinsEvenWithoutTimestampsOrWithOlderDuplicates() {
        val alpha = album("a", "Alpha")
        val beta = album("b", "Beta")
        val zulu = album("z", "Zulu")
        val history = listOf(
            randomAlbumRecentRadioStream(zulu),
            albumRecentRadioStream(beta).copy(startedAtEpochMillis = 300L),
            albumRecentRadioStream(zulu).copy(startedAtEpochMillis = 100L),
        )

        assertEquals(
            listOf("z", "b", "a"),
            listOf(alpha, beta, zulu).sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed, history).ids(),
        )
    }

    @Test
    fun explicitCompositeSeedsShareRecencyAndLegacyCompositeIdsAreNotParsed() {
        val alpha = album("a", "Alpha")
        val beta = album("b", "Beta")
        val zulu = album("z", "Zulu")
        val colonId = album("source:album", "Same")
        val sameTitle = album("source:album-2", "Same")
        val history = listOf(
            albumRecentRadioStream(zulu).copy(
                id = "album-mix:z:source:album-2:source:album:a",
                stationSeedIds = listOf("z", sameTitle.id.value, colonId.id.value, "a", "a"),
            ),
            albumRecentRadioStream(beta),
        )
        val albums = listOf(beta, zulu, sameTitle, colonId, alpha)
        assertEquals(
            listOf("a", "source:album", "source:album-2", "z", "b"),
            albums.sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed, history).ids(),
        )

        val legacy = albumRecentRadioStream(zulu).copy(id = "album-mix:z:b")
        assertEquals(
            listOf("z", "a", "b"),
            listOf(alpha, beta, zulu).sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed, listOf(legacy)).ids(),
        )
        val explicitOnly = RecentRadioStream(
            id = "album-mix:b:z",
            label = "Album mix",
            kind = RecentRadioKind.Album,
            stationSeedIds = listOf("z", "b"),
        )
        assertEquals(
            listOf("b", "z", "a"),
            listOf(alpha, beta, zulu).sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed, listOf(explicitOnly)).ids(),
        )
    }

    @Test
    fun unrelatedKindsAndSessionTracksNeverConferAlbumRadioRecency() {
        val alpha = album("a", "Alpha")
        val beta = album("b", "Beta")
        val zulu = album("z", "Zulu")
        val unrelatedTrack = SavedTrack("track", "Track", artistName = "Artist", albumId = zulu.id.value)
        val unrelatedHistory = RecentRadioKind.entries
            .filter { it != RecentRadioKind.Album && it != RecentRadioKind.RandomAlbum }
            .map { kind ->
                RecentRadioStream(
                    id = "unrelated:$kind",
                    label = "Other radio",
                    kind = kind,
                    album = SavedAlbum.fromAlbum(zulu),
                    track = unrelatedTrack,
                    sessionTracks = listOf(unrelatedTrack),
                    stationSeedIds = listOf(zulu.id.value),
                )
            }
        val history = unrelatedHistory + albumRecentRadioStream(beta).copy(sessionTracks = listOf(unrelatedTrack))

        assertEquals(
            listOf("b", "a", "z"),
            listOf(zulu, beta, alpha).sortedFavoriteAlbums(FavoriteArtistSort.LastRadioPlayed, history).ids(),
        )
    }

    @Test
    fun missingFavoriteOrRadioHistoryNeverFallsBackToOtherAlbumDates() {
        val albums = listOf(
            album("z", "Zulu").copy(
                recentlyAddedAtIso8601 = "2026-03-01T00:00:00Z",
                lastPlayedAtIso8601 = "2026-03-02T00:00:00Z",
                playCount = 100,
            ),
            album("a", "Alpha"),
        )

        for (sort in FavoriteArtistSort.entries) {
            assertEquals(listOf("a", "z"), albums.sortedFavoriteAlbums(sort).ids())
            assertEquals(emptyList(), emptyList<Album>().sortedFavoriteAlbums(sort))
        }
    }

    private fun album(id: String, title: String, favoritedAt: String? = null): Album = Album(
        id = AlbumId(id),
        title = title,
        artistName = "Artist",
        coverArtId = null,
        recentlyAddedAtIso8601 = null,
        favoritedAtIso8601 = favoritedAt,
    )

    private fun List<Album>.ids(): List<String> = map { it.id.value }
}
