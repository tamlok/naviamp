package app.naviamp.domain.home

import app.naviamp.domain.Album
import app.naviamp.domain.settings.FavoriteArtistSort
import app.naviamp.domain.settings.RecentRadioKind
import app.naviamp.domain.settings.RecentRadioStream

/** Radio history must already be source-scoped and newest-first; only explicit album seeds count. */
fun List<Album>.sortedFavoriteAlbums(
    sort: FavoriteArtistSort,
    recentRadioStreams: List<RecentRadioStream> = emptyList(),
): List<Album> {
    val comparator = when (sort) {
        FavoriteArtistSort.Name -> FavoriteAlbumNameComparator
        FavoriteArtistSort.DateFavorited -> compareByDescending<Album> { album ->
            album.favoritedAtIso8601?.takeUnless { it.isBlank() || it == "favorite" }
        }.then(FavoriteAlbumNameComparator)
        FavoriteArtistSort.LastRadioPlayed -> {
            val recency = buildMap<String, Int> {
                recentRadioStreams.forEachIndexed { index, stream ->
                    if (stream.kind == RecentRadioKind.Album || stream.kind == RecentRadioKind.RandomAlbum) {
                        stream.album?.id?.let { id ->
                            if (id.isNotBlank() && id !in this) put(id, index)
                        }
                        for (id in stream.stationSeedIds) {
                            if (id.isNotBlank() && id !in this) put(id, index)
                        }
                    }
                }
            }
            compareBy<Album> { recency[it.id.value] ?: Int.MAX_VALUE }.then(FavoriteAlbumNameComparator)
        }
    }
    return sortedWith(comparator)
}

private val FavoriteAlbumNameComparator =
    compareBy<Album> { it.title.lowercase() }.thenBy { it.title }.thenBy { it.id.value }
