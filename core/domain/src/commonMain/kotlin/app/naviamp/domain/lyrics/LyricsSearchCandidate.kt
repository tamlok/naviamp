package app.naviamp.domain.lyrics

import app.naviamp.domain.Lyrics
import app.naviamp.domain.LyricsMatchQuality
import app.naviamp.domain.Track
import kotlin.math.abs

/** Provider DTOs expose metadata without copying their payloads into another candidate model. */
interface LyricsSearchCandidate {
    val title: String?
    val artist: String?
    val album: String?
    val duration: Double?
    val instrumental: Boolean
    val hasSyncedLyrics: Boolean
    val allowsMissingDuration: Boolean get() = false

    fun lyricsText(): String?
    fun toLyrics(): Lyrics?
}

internal data class RecordedLyricsCandidate(
    val providerId: String,
    val sourceIndex: Int,
    val providerIndex: Int,
    val candidate: LyricsSearchCandidate,
)

internal data class SelectedLyricsCandidate(
    val providerId: String,
    val lyrics: Lyrics,
)

/** Return a validated match, or retain every plausible result for lookup-wide selection. */
internal fun selectLyricsSearchResult(
    candidates: List<LyricsSearchCandidate>,
    track: Track,
    onCandidate: (LyricsSearchCandidate) -> Unit = {},
): Lyrics? {
    val title = track.title.normalizedLyricsIdentity()
    val artist = track.artistName.normalizedLyricsIdentity()
    val album = track.albumTitle.orEmpty().normalizedLyricsIdentity()
    val duration = track.durationSeconds?.takeIf { it > 0 }
    var best: LyricsSearchCandidate? = null
    var bestDifference = Double.POSITIVE_INFINITY
    var bestAlbum = false
    var bestSynced = false
    var bestText: String? = null
    var bestParsed: Lyrics? = null
    var bestParsedLoaded = false
    var ambiguous = false
    var unknownDurationConflict = false
    for (candidate in candidates) {
        val text = candidate.eligibleLyricsText(title, artist, duration)?.trim() ?: continue
        val difference = candidate.durationDifference(duration)
        val albumMatch = album.isNotEmpty() && candidate.album?.normalizedLyricsIdentity() == album
        val synced = candidate.hasSyncedLyrics
        val metadataRank = compareLyricsMetadataRank(difference, albumMatch, bestDifference, bestAlbum)
        val better = best == null || metadataRank > 0 || metadataRank == 0 && synced && !bestSynced
        val sameRank = metadataRank == 0 && synced == bestSynced
        var candidateParsed: Lyrics? = null
        var candidateParsedLoaded = false
        if (!unknownDurationConflict && best != null && text != bestText && (duration == null || sameRank)) {
            if (!bestParsedLoaded) {
                bestParsed = best.toLyrics()
                bestParsedLoaded = true
            }
            candidateParsed = candidate.toLyrics()
            candidateParsedLoaded = true
            // LRC spacing and timestamp precision may differ without changing text or timing.
            val equivalent = bestParsed != null && candidateParsed != null &&
                bestParsed.synced == candidateParsed.synced && bestParsed.lines == candidateParsed.lines
            if (!equivalent) {
                if (duration == null) unknownDurationConflict = true else ambiguous = true
            }
        }
        if (better) {
            best = candidate
            bestDifference = difference
            bestAlbum = albumMatch
            bestSynced = synced
            bestText = text
            bestParsed = candidateParsed
            bestParsedLoaded = candidateParsedLoaded
            ambiguous = false
        }
    }
    if (!ambiguous && !unknownDurationConflict) {
        val result = if (bestParsedLoaded) bestParsed else best?.toLyrics()
        if (result != null) return result
    }
    for (candidate in candidates) {
        if (candidate.eligibleLyricsText(title, artist, duration) != null) onCandidate(candidate)
    }
    return null
}

internal fun selectBestEffortLyrics(
    candidates: List<RecordedLyricsCandidate>,
    track: Track,
): SelectedLyricsCandidate? {
    val title = track.title.normalizedLyricsIdentity()
    val artist = track.artistName.normalizedLyricsIdentity()
    val album = track.albumTitle.orEmpty().normalizedLyricsIdentity()
    val duration = track.durationSeconds?.takeIf { it > 0 }
    var best: RecordedLyricsCandidate? = null
    var bestLyrics: Lyrics? = null
    var bestDifference = Double.POSITIVE_INFINITY
    var bestAlbum = false
    var bestSynced = false
    for (recorded in candidates) {
        val candidate = recorded.candidate
        if (candidate.eligibleLyricsText(title, artist, duration) == null) continue
        val difference = candidate.durationDifference(duration)
        val albumMatch = album.isNotEmpty() && candidate.album?.normalizedLyricsIdentity() == album
        val metadataRank = compareLyricsMetadataRank(difference, albumMatch, bestDifference, bestAlbum)
        if (best != null && metadataRank < 0) continue
        val earlier = best == null || recorded.sourceIndex < best.sourceIndex ||
            recorded.sourceIndex == best.sourceIndex && recorded.providerIndex < best.providerIndex
        // Some providers parse to determine timing; skip candidates that cannot improve the rank first.
        if (best != null && metadataRank == 0 && bestSynced && !earlier) continue
        val synced = candidate.hasSyncedLyrics
        if (best != null && metadataRank == 0 &&
            (bestSynced && !synced || synced == bestSynced && !earlier)
        ) continue
        val lyrics = candidate.toLyrics() ?: continue
        best = recorded
        bestLyrics = lyrics
        bestDifference = difference
        bestAlbum = albumMatch
        bestSynced = synced
    }
    val selected = best ?: return null
    return SelectedLyricsCandidate(
        providerId = selected.providerId,
        lyrics = bestLyrics!!.copy(matchQuality = LyricsMatchQuality.BestEffort),
    )
}

private fun LyricsSearchCandidate.eligibleLyricsText(
    title: String,
    artist: String,
    requestedDuration: Int?,
): String? {
    if (instrumental || !matchesLyricsIdentity(title, artist, requestedDuration)) return null
    return lyricsText()?.takeIf { it.isNotBlank() }
}

private fun LyricsSearchCandidate.durationDifference(requestedDuration: Int?): Double {
    if (requestedDuration == null) return 0.0
    val seconds = duration ?: return Double.POSITIVE_INFINITY
    return abs(seconds - requestedDuration)
}

/** Positive means the candidate ranks ahead, before timing and provider order break ties. */
private fun compareLyricsMetadataRank(
    difference: Double,
    album: Boolean,
    bestDifference: Double,
    bestAlbum: Boolean,
): Int {
    val durationRank = bestDifference.compareTo(difference)
    return if (durationRank != 0) durationRank else album.compareTo(bestAlbum)
}

internal fun LyricsSearchCandidate.matchesLyricsIdentity(
    title: String,
    artist: String,
    requestedDuration: Int?,
): Boolean {
    if (title.isEmpty() || artist.isEmpty() ||
        this.title?.normalizedLyricsIdentity() != title || this.artist?.normalizedLyricsIdentity() != artist
    ) return false
    if (requestedDuration == null) return true
    val seconds = duration ?: return allowsMissingDuration
    if (!seconds.isFinite() || seconds <= 0) return false
    return abs(seconds - requestedDuration) <= 5.0
}

internal fun String.normalizedLyricsIdentity(): String = buildString(length) {
    var separator = false
    for (character in this@normalizedLyricsIdentity) {
        if (character.isLetterOrDigit()) {
            if (separator && isNotEmpty()) append(' ')
            append(character.lowercaseChar())
            separator = false
        } else {
            separator = true
        }
    }
}
