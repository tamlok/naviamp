package app.naviamp.domain.lyrics

import app.naviamp.domain.Lyrics
import app.naviamp.domain.LyricsSource
import app.naviamp.domain.Track
import app.naviamp.domain.network.NaviampProjectUserAgent
import app.naviamp.domain.network.SharedHttpClient
import app.naviamp.domain.network.urlEncodedParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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

    override suspend fun lyrics(track: Track, onCandidate: (LyricsSearchCandidate) -> Unit): Lyrics? {
        val query = LrclibLyricsQuery.fromTrack(track) ?: return null
        val exact = httpClient.getResponse(requestUrl("get", query.parameters), JsonHeaders) ?: return null
        if (exact.statusCode in 200..299) {
            val response = runCatching {
                LrclibJson.decodeFromString<LrclibLyricsResponse>(exact.bodyText())
            }.getOrNull()
            val completeSignature = query.albumName.isNotBlank() && query.durationSeconds in 1..3600
            if (response != null && (completeSignature || response.matchesLyricsIdentity(
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
        return selectLyricsSearchResult(candidates, track, onCandidate)
    }

    private fun requestUrl(endpoint: String, parameters: List<Pair<String, String>>): String =
        "$baseUrl/api/$endpoint?" + parameters.joinToString("&") { (key, value) ->
            "$key=${value.urlEncodedParameter()}"
        }
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
    override val duration: Double? = null,
    override val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) : LyricsSearchCandidate {
    override val title: String? get() = trackName
    override val artist: String? get() = artistName
    override val album: String? get() = albumName
    override val hasSyncedLyrics: Boolean get() = !syncedLyrics.isNullOrBlank()

    @Transient
    private var parsed: Lyrics? = null

    @Transient
    private var parsedLoaded = false

    override fun lyricsText(): String? =
        syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics?.takeIf { it.isNotBlank() }

    override fun toLyrics(): Lyrics? {
        if (!parsedLoaded) {
            parsed = lyricsText()?.let { text ->
                lyricsFromText(
                    source = LyricsSource.Lrclib,
                    text = text,
                    displayArtist = artistName,
                    displayTitle = trackName,
                )
            }
            parsedLoaded = true
        }
        return parsed
    }
}
