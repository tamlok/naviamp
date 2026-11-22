package app.naviamp.domain.lyrics

import app.naviamp.domain.LyricLine
import app.naviamp.domain.Lyrics
import app.naviamp.domain.LyricsSource
import app.naviamp.domain.Track
import app.naviamp.domain.network.NaviampProjectUserAgent
import app.naviamp.domain.network.SharedHttpClient
import app.naviamp.domain.network.urlEncodedParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

class LrcApiLyricsProvider(
    private val httpClient: SharedHttpClient,
    baseUrl: String = "https://api.lrc.cx",
) : LyricsProvider {
    override val id: String = "lrcapi"
    override val capabilities: Set<LyricsTiming> = setOf(LyricsTiming.Plain, LyricsTiming.LineSynced)
    private val endpoint = "${baseUrl.trimEnd('/')}/jsonapi"

    override suspend fun lyrics(track: Track): Lyrics? {
        if (track.title.isBlank() || track.artistName.isBlank()) return null
        val parameters = buildList {
            add("title" to track.title)
            add("artist" to track.artistName)
            track.albumTitle?.takeIf { it.isNotBlank() }?.let { add("album" to it) }
        }
        val url = "$endpoint?" + parameters.joinToString("&") { (key, value) ->
            "$key=${value.urlEncodedParameter()}"
        }
        val response = httpClient.getResponse(url, LrcApiHeaders) ?: return null
        if (response.statusCode !in 200..299) return null
        val candidates = runCatching {
            LrcApiJson.decodeFromString<List<LrcApiLyricsResponse>>(response.bodyText())
        }.getOrNull() ?: return null
        return selectLyricsSearchResult(candidates, track, allowMissingDuration = true)
    }
}

@Serializable
private class LrcApiLyricsResponse(
    override val title: String? = null,
    override val artist: String? = null,
    override val album: String? = null,
    override val duration: Double? = null,
    override val instrumental: Boolean = false,
    val lyrics: String? = null,
    val lrc: String? = null,
) : LyricsSearchCandidate {
    // The documented API uses lyrics; the public deployment uses lrc and marks plain text.
    @Transient
    private val payload = (lyrics?.takeIf { it.isNotBlank() } ?: lrc)?.trimStart()

    @Transient
    private val text = payload?.removePrefix("[!text]")?.takeIf { it.isNotBlank() }

    @Transient
    private var parsed: Lyrics? = null

    override val hasSyncedLyrics: Boolean
        get() = toLyrics()?.synced == true

    override fun lyricsText(): String? = payload?.takeIf { text != null }

    override fun toLyrics(): Lyrics? = parsed ?: text?.let {
        if (payload?.startsWith("[!text]") == true) {
            Lyrics(
                source = LyricsSource.LrcApi,
                synced = false,
                lines = it.lineSequence().map(String::trim).filter(String::isNotBlank)
                    .map { line -> LyricLine(null, line) }.toList(),
                displayArtist = artist,
                displayTitle = title,
            )
        } else {
            lyricsFromText(LyricsSource.LrcApi, it, displayArtist = artist, displayTitle = title)
        }
    }.also { parsed = it }
}

private val LrcApiJson = Json { ignoreUnknownKeys = true }
private val LrcApiHeaders = mapOf(
    "Accept" to "application/json",
    "User-Agent" to NaviampProjectUserAgent,
)
