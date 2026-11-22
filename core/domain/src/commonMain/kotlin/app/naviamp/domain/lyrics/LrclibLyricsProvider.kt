package app.naviamp.domain.lyrics

import app.naviamp.domain.Lyrics
import app.naviamp.domain.LyricsSource
import app.naviamp.domain.Track
import app.naviamp.domain.network.NaviampProjectUserAgent
import app.naviamp.domain.network.SharedHttpClient
import app.naviamp.domain.network.urlEncodedParameter
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LrclibLyricsProvider(
    private val httpClient: SharedHttpClient,
    private val baseUrl: String = "https://lrclib.net",
) : LyricsProvider {
    override val id: String = "lrclib"
    override val capabilities: Set<LyricsTiming> = setOf(
        LyricsTiming.Plain,
        LyricsTiming.LineSynced,
    )

    override suspend fun lyrics(track: Track): Lyrics? {
        val query = LrclibLyricsQuery.fromTrack(track) ?: return null
        val exact = httpClient.getResponse(requestUrl("get", query.parameters), JsonHeaders) ?: return null
        if (exact.statusCode in 200..299) {
            val response = runCatching {
                LrclibJson.decodeFromString<LrclibLyricsResponse>(exact.bodyText())
            }.getOrNull()
            val completeSignature = query.albumName.isNotBlank() && query.durationSeconds in 1..3600
            if (response != null && (completeSignature || response.matches(
                    track.title.normalizedLyricsIdentity(),
                    track.artistName.normalizedLyricsIdentity(),
                    track.durationSeconds?.takeIf { it > 0 },
                ))
            ) {
                if (response.instrumental) return null
                response.toLyrics()?.let { return it }
            }
        } else if (exact.statusCode != 404) {
            // A rate limit or server failure is not a miss. Do not immediately ask again.
            return null
        }

        val search = httpClient.getResponse(
            requestUrl("search", listOf("track_name" to query.trackName, "artist_name" to query.artistName)),
            JsonHeaders,
        ) ?: return null
        if (search.statusCode !in 200..299) return null
        val candidates = runCatching {
            LrclibJson.decodeFromString<List<LrclibLyricsResponse>>(search.bodyText())
        }.getOrNull() ?: return null
        return selectSearchResult(candidates, track)
    }

    private fun requestUrl(endpoint: String, parameters: List<Pair<String, String>>): String =
        "$baseUrl/api/$endpoint?" + parameters.joinToString("&") { (key, value) ->
            "$key=${value.urlEncodedParameter()}"
        }
}

private fun selectSearchResult(candidates: List<LrclibLyricsResponse>, track: Track): Lyrics? {
    val title = track.title.normalizedLyricsIdentity()
    val artist = track.artistName.normalizedLyricsIdentity()
    val album = track.albumTitle.orEmpty().normalizedLyricsIdentity()
    val duration = track.durationSeconds?.takeIf { it > 0 }
    var best: LrclibLyricsResponse? = null
    var bestDifference = Double.POSITIVE_INFINITY
    var bestAlbum = false
    var bestSynced = false
    var bestText: String? = null
    var bestParsed: Lyrics? = null
    var ambiguous = false
    for (candidate in candidates) {
        if (candidate.instrumental || !candidate.matches(title, artist, duration)) continue
        val text = candidate.lyricsText()?.trim() ?: continue
        val difference = if (duration != null) abs(candidate.duration!! - duration) else 0.0
        val albumMatch = album.isNotEmpty() && candidate.albumName?.normalizedLyricsIdentity() == album
        val synced = !candidate.syncedLyrics.isNullOrBlank()
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

private fun LrclibLyricsResponse.matches(title: String, artist: String, requestedDuration: Int?): Boolean {
    if (title.isEmpty() || artist.isEmpty() ||
        trackName?.normalizedLyricsIdentity() != title || artistName?.normalizedLyricsIdentity() != artist
    ) return false
    if (requestedDuration == null) return true
    val seconds = duration?.takeIf { it.isFinite() && it > 0 } ?: return false
    return abs(seconds - requestedDuration) <= 5.0
}

private fun String.normalizedLyricsIdentity(): String = buildString(length) {
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

private fun LrclibLyricsResponse.lyricsText(): String? =
    syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics?.takeIf { it.isNotBlank() }

private fun LrclibLyricsResponse.toLyrics(): Lyrics? =
    lyricsText()?.let { text ->
        lyricsFromText(
            source = LyricsSource.Lrclib,
            text = text,
            displayArtist = artistName,
            displayTitle = trackName,
        )
    }

data class LrclibLyricsQuery(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Int?,
) {
    val parameters: List<Pair<String, String>>
        get() = buildList {
            add("track_name" to trackName)
            add("artist_name" to artistName)
            if (albumName.isNotBlank()) add("album_name" to albumName)
            if (durationSeconds in 1..3600) add("duration" to durationSeconds.toString())
        }

    companion object {
        fun fromTrack(track: Track): LrclibLyricsQuery? {
            if (track.title.isBlank() || track.artistName.isBlank()) return null
            return LrclibLyricsQuery(
                trackName = track.title,
                artistName = track.artistName,
                albumName = track.albumTitle.orEmpty(),
                durationSeconds = track.durationSeconds,
            )
        }
    }
}

private val LrclibJson = Json { ignoreUnknownKeys = true }

private val JsonHeaders = mapOf(
    "Accept" to "application/json",
    "User-Agent" to NaviampProjectUserAgent,
)

@Serializable
private data class LrclibLyricsResponse(
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)
