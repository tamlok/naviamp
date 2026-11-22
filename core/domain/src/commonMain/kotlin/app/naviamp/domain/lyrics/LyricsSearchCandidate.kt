package app.naviamp.domain.lyrics

import app.naviamp.domain.Lyrics
import app.naviamp.domain.Track
import kotlin.math.abs

/** Provider DTOs expose metadata without copying their payloads into another candidate model. */
internal interface LyricsSearchCandidate {
    val title: String?
    val artist: String?
    val album: String?
    val duration: Double?
    val instrumental: Boolean
    val hasSyncedLyrics: Boolean

    fun lyricsText(): String?
    fun toLyrics(): Lyrics?
}

/** Missing durations are allowed only for services whose protocol does not require them. */
internal fun selectLyricsSearchResult(
    candidates: List<LyricsSearchCandidate>,
    track: Track,
    allowMissingDuration: Boolean = false,
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
    var ambiguous = false
    for (candidate in candidates) {
        if (candidate.instrumental || !candidate.matchesLyricsIdentity(title, artist, duration, allowMissingDuration)) continue
        val text = candidate.lyricsText()?.trim() ?: continue
        val difference = when {
            duration == null -> 0.0
            candidate.duration == null -> Double.POSITIVE_INFINITY
            else -> abs(candidate.duration!! - duration)
        }
        val albumMatch = album.isNotEmpty() && candidate.album?.normalizedLyricsIdentity() == album
        val synced = candidate.hasSyncedLyrics
        val better = best == null || difference < bestDifference ||
            (difference == bestDifference && (albumMatch && !bestAlbum ||
                albumMatch == bestAlbum && synced && !bestSynced))
        val sameRank = difference == bestDifference && albumMatch == bestAlbum && synced == bestSynced
        var candidateParsed: Lyrics? = null
        if (best != null && text != bestText && (duration == null || sameRank)) {
            val previous = bestParsed ?: best.toLyrics().also { bestParsed = it }
            candidateParsed = candidate.toLyrics()
            // LRC spacing and timestamp precision may differ without changing text or timing.
            val equivalent = previous != null && candidateParsed != null &&
                previous.synced == candidateParsed.synced && previous.lines == candidateParsed.lines
            if (!equivalent) {
                if (duration == null) return null
                ambiguous = true
            }
        }
        if (better) {
            best = candidate
            bestDifference = difference
            bestAlbum = albumMatch
            bestSynced = synced
            bestText = text
            bestParsed = candidateParsed
            ambiguous = false
        }
    }
    return if (ambiguous) null else bestParsed ?: best?.toLyrics()
}

internal fun LyricsSearchCandidate.matchesLyricsIdentity(
    title: String,
    artist: String,
    requestedDuration: Int?,
    allowMissingDuration: Boolean = false,
): Boolean {
    if (title.isEmpty() || artist.isEmpty() ||
        this.title?.normalizedLyricsIdentity() != title || this.artist?.normalizedLyricsIdentity() != artist
    ) return false
    if (requestedDuration == null) return true
    val seconds = duration ?: return allowMissingDuration
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
