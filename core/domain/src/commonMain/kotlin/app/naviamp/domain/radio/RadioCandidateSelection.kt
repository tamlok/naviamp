package app.naviamp.domain.radio

import app.naviamp.domain.ArtistId
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.eraReleaseYear
import app.naviamp.domain.isInternetRadioTrack
import app.naviamp.domain.provider.isDiscoveryPreview

/** Seed slots carry provenance through ranking; provider order breaks equal-score ties. */
data class RadioCandidate(val track: Track, val seedIndex: Int = 0, val score: Double = 0.0)

class RadioRecipeValidationException(message: String) : IllegalArgumentException(message)

/** Hard filters never relax on exhaustion. Spacing is a preference, not an artist quota. */
fun selectRadioCandidates(
    candidates: List<RadioCandidate>,
    targetCount: Int,
    tuning: RadioTuningSettings = RadioTuningSettings(),
    excludedTrackIds: Set<TrackId> = emptySet(),
    previousTrack: Track? = null,
    anchorArtistId: ArtistId? = null,
    decadeStart: Int? = null,
    fromYear: Int? = null,
    toYear: Int? = null,
    genres: List<String> = emptyList(),
    excludedAlbumIds: Set<String> = emptySet(),
): List<Track> {
    if (tuning.sameDecadeOnly && decadeStart == null) {
        throw RadioRecipeValidationException("Select a seed with a known release year or disable Same decade.")
    }
    if (tuning.artistRunMode != RadioArtistRunMode.Mixed && anchorArtistId == null) {
        throw RadioRecipeValidationException("Select an artist with a catalog identity or use Mixed artists.")
    }
    if (targetCount <= 0) return emptyList()
    val settings = tuning.normalized()
    val requiredGenres = genres.mapTo(mutableSetOf()) { it.lowercase() }
    val ranked = candidates.filter { candidate ->
        val track = candidate.track
        track.id.value.isNotBlank() && !track.id.isDiscoveryPreview() && !track.isInternetRadioTrack() &&
            track.id !in excludedTrackIds && track.albumId?.value !in excludedAlbumIds &&
            (settings.artistRunMode != RadioArtistRunMode.SingleArtist || track.artistId == anchorArtistId) &&
            (!settings.sameDecadeOnly || track.eraReleaseYear?.let { it in decadeStart!!..(decadeStart + 9) } == true) &&
            (fromYear == null || track.eraReleaseYear?.let { it >= fromYear } == true) &&
            (toYear == null || track.eraReleaseYear?.let { it <= toYear } == true) &&
            (requiredGenres.isEmpty() || track.genres.any { it.lowercase() in requiredGenres })
    }.groupBy { it.seedIndex }.values.map { bucket ->
        bucket.sortedWith(candidateComparator(settings, anchorArtistId)).distinctBy { it.track.id }
    }
    val result = ArrayList<Track>(minOf(targetCount, candidates.size))
    val seen = excludedTrackIds.toMutableSet()
    val contributions = IntArray(ranked.size)
    val history = ArrayList<Track>(4)
    previousTrack?.let(history::add)
    var blockIsAnchor = previousTrack?.artistId?.let { it == anchorArtistId } ?: true
    var blockCount = if (previousTrack == null) 0 else 1
    while (result.size < targetCount) {
        var selected: RadioCandidate? = null
        var selectedBucket = -1
        var selectedPenalty = Int.MAX_VALUE
        var selectedContribution = Int.MAX_VALUE
        ranked.forEachIndexed { index, bucket ->
            for (candidate in bucket) {
                if (candidate.track.id in seen) continue
                val penalty = when (settings.artistRunMode) {
                    RadioArtistRunMode.SingleArtist -> 0
                    RadioArtistRunMode.ArtistBlocks -> {
                        val runLength = if (blockIsAnchor) settings.sameArtistRunLength else settings.otherArtistRunLength
                        val wantedAnchor = if (blockCount >= runLength) !blockIsAnchor else blockIsAnchor
                        if ((candidate.track.artistId == anchorArtistId) == wantedAnchor) 0 else 1
                    }
                    RadioArtistRunMode.Mixed -> spacingPenalty(candidate.track, history, settings.artistSpread)
                }
                if (penalty < selectedPenalty ||
                    (penalty == selectedPenalty && contributions[index] < selectedContribution)) {
                    selected = candidate
                    selectedBucket = index
                    selectedPenalty = penalty
                    selectedContribution = contributions[index]
                }
                // Within a seed, the first equally spaced match retains the provider/taste ranking.
                if (penalty == 0) break
            }
        }
        val track = selected?.track ?: break
        result += track
        seen += track.id
        contributions[selectedBucket]++
        val isAnchor = track.artistId == anchorArtistId
        blockCount = if (blockIsAnchor == isAnchor) blockCount + 1 else 1
        blockIsAnchor = isAnchor
        if (history.size == 3) history.removeAt(0)
        history += track
    }
    return result
}

private fun candidateComparator(tuning: RadioTuningSettings, anchor: ArtistId?): Comparator<RadioCandidate> {
    val taste = when (tuning.familiarity) {
        RadioFamiliarity.Balanced -> compareBy<RadioCandidate> { 0 }
        RadioFamiliarity.Favorites -> compareByDescending<RadioCandidate> { it.track.favoritedAtIso8601 != null }
            .thenByDescending { it.track.userRating ?: 0 }.thenByDescending { it.track.playCount ?: 0 }
        // An explicit low rating is not evidence of an appealing deep cut.
        RadioFamiliarity.DeepCuts -> compareBy<RadioCandidate> { it.track.userRating?.let { rating -> rating in 1..2 } == true }
            .thenBy { (it.track.playCount ?: 0).coerceAtLeast(0) }
    }
    val spread = if (tuning.artistSpread == RadioArtistSpread.Narrow && anchor != null) {
        compareByDescending<RadioCandidate> { it.track.artistId == anchor }.then(taste)
    } else taste
    return spread.thenByDescending { it.score.takeIf(Double::isFinite) ?: 0.0 }
}

private fun spacingPenalty(track: Track, history: List<Track>, spread: RadioArtistSpread): Int {
    val artistWindow = if (spread == RadioArtistSpread.Broad) 3 else 1
    var artistPenalty = 0
    var albumPenalty = 0
    for (offset in history.indices) {
        val previous = history[history.lastIndex - offset]
        if (offset < artistWindow && track.sameRadioArtist(previous)) artistPenalty += artistWindow - offset
        if (offset < 2 && track.sameRadioAlbum(previous)) albumPenalty++
    }
    return artistPenalty * 4 + albumPenalty
}

private fun Track.sameRadioArtist(other: Track): Boolean =
    if (artistId != null && other.artistId != null) artistId == other.artistId
    else artistName.isNotBlank() && artistName.equals(other.artistName, ignoreCase = true)

private fun Track.sameRadioAlbum(other: Track): Boolean =
    if (albumId != null && other.albumId != null) albumId == other.albumId
    else !albumTitle.isNullOrBlank() && albumTitle.equals(other.albumTitle, ignoreCase = true) && sameRadioArtist(other)
