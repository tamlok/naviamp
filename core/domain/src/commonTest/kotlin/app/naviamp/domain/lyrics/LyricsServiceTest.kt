package app.naviamp.domain.lyrics

import app.naviamp.domain.LyricLine
import app.naviamp.domain.Lyrics
import app.naviamp.domain.LyricsSource
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.network.SharedHttpClient
import app.naviamp.domain.network.SharedHttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LyricsServiceTest {
    @Test
    fun parsesSyncedLrcLines() {
        val lyrics = lyricsFromText(
            source = LyricsSource.Embedded,
            text = """
                [00:10.50]First line
                [00:12.000]Second line
            """.trimIndent(),
        )

        assertEquals(true, lyrics?.synced)
        assertEquals(listOf(10_500L, 12_000L), lyrics?.lines?.map { it.startMillis })
        assertEquals(listOf("First line", "Second line"), lyrics?.lines?.map { it.text })
    }

    @Test
    fun parsesPlainLyricsAsUnsyncedLines() {
        val lyrics = lyricsFromText(
            source = LyricsSource.Embedded,
            text = """
                First line

                Second line
            """.trimIndent(),
        )

        assertEquals(false, lyrics?.synced)
        assertEquals(listOf(null, null), lyrics?.lines?.map { it.startMillis })
        assertEquals(listOf("First line", "Second line"), lyrics?.lines?.map { it.text })
    }

    @Test
    fun lrclibQueryUsesTrackMetadata() {
        val query = LrclibLyricsQuery.fromTrack(track())!!

        assertEquals(
            listOf(
                "track_name" to "Song",
                "artist_name" to "Artist",
                "album_name" to "Album",
                "duration" to "180",
            ),
            query.parameters,
        )
    }

    @Test
    fun lrclibResponsePrefersSyncedLyricsWithoutSearching() = runTest {
        val client = LyricsHttpClient {
            assertTrue(it.contains("/api/get?"))
            httpResponse(record(plain = "plain", synced = "[00:01.00]synced"))
        }
        val lyrics = LrclibLyricsProvider(client).lyrics(track())

        assertEquals(true, lyrics?.synced)
        assertEquals("Artist", lyrics?.displayArtist)
        assertEquals("Song", lyrics?.displayTitle)
        assertEquals(listOf(LyricLine(1_000L, "synced")), lyrics?.lines)
        assertEquals(1, client.urls.size)
    }

    @Test
    fun lrclibSearchRecoversMissingEmptyAndMalformedExactResults() = runTest {
        for (exact in listOf(httpResponse("", 404), httpResponse(record(plain = " ")), httpResponse("not json"))) {
            val client = searchClient(listOf(record(album = "Other edition", plain = "Found")), exact)
            assertEquals("Found", LrclibLyricsProvider(client).lyrics(track())?.lines?.single()?.text)
            assertEquals(2, client.urls.size)
            assertEquals("https://lrclib.net/api/search?track_name=Song&artist_name=Artist", client.urls.last())
        }
    }

    @Test
    fun lrclibOmitsUnavailableSignatureFieldsAndStillSearches() = runTest {
        for (duration in listOf(null, 0, -1, 3601)) {
            val client = searchClient(listOf(record(duration = duration?.takeIf { it > 0 }?.toDouble() ?: 180.0)))
            val lyrics = LrclibLyricsProvider(client).lyrics(track().copy(albumTitle = " ", durationSeconds = duration))
            assertEquals("Found", lyrics?.lines?.single()?.text)
            assertEquals("https://lrclib.net/api/get?track_name=Song&artist_name=Artist", client.urls.first())
            assertEquals(2, client.urls.size)
        }
    }

    @Test
    fun lrclibAcceptsValidExactLookupWithoutDuration() = runTest {
        val client = LyricsHttpClient { httpResponse(record()) }
        assertEquals("Found", LrclibLyricsProvider(client).lyrics(track().copy(durationSeconds = null))?.lines?.single()?.text)
        assertEquals(1, client.urls.size)
        assertTrue(!client.urls.single().contains("duration="))
    }

    @Test
    fun lrclibSearchesAfterRejectingIncompleteSignatureExactMatch() = runTest {
        for (wrong in listOf(record(title = "Song (Live)"), record(duration = 200.0))) {
            val client = searchClient(listOf(record()), httpResponse(wrong))
            assertEquals("Found", LrclibLyricsProvider(client).lyrics(track().copy(albumTitle = null))?.lines?.single()?.text)
            assertEquals(2, client.urls.size)
        }
    }

    @Test
    fun lrclibEncodesIdentityWithoutNarrowingSearchToAlbumOrDuration() = runTest {
        val title = "Bài hát & you?"
        val artist = "A/B + C"
        val client = searchClient(listOf(record(title = title, artist = artist)))
        assertEquals("Found", LrclibLyricsProvider(client).lyrics(track().copy(title = title, artistName = artist))?.lines?.single()?.text)
        assertEquals(
            "https://lrclib.net/api/search?track_name=B%C3%A0i+h%C3%A1t+%26+you%3F&artist_name=A%2FB+%2B+C",
            client.urls.last(),
        )
    }

    @Test
    fun lrclibRejectsOtherRecordingsAndUnverifiableDurations() = runTest {
        val rejected = listOf(
            record(title = "Other song"), record(artist = "Other artist"), record(title = "Song (Live)"),
            record(title = "Song - Remix"), record(duration = 185.01), record(duration = 174.99),
            record(duration = null), record(duration = 0.0), record(duration = -1.0),
            record(instrumental = true), record(plain = " "),
        )
        for (candidate in rejected) {
            assertNull(LrclibLyricsProvider(searchClient(listOf(candidate))).lyrics(track()), candidate)
        }
        val client = searchClient(rejected + record(title = " SONG! ", artist = " artist ", duration = 185.0))
        assertEquals("Found", LrclibLyricsProvider(client).lyrics(track())?.lines?.single()?.text)
    }

    @Test
    fun lrclibRanksDurationBeforeAlbumThenSyncedLyrics() = runTest {
        val candidates = listOf(
            record(duration = 181.0, plain = "Further album match"),
            record(album = "Compilation", duration = 180.25, synced = "[00:01.00]Other album"),
            record(duration = 180.25, plain = "Plain album match"),
            record(album = " ALBUM! ", duration = 180.25, synced = "[00:01.00]Best"),
        )
        for (ordered in listOf(candidates, candidates.reversed())) {
            assertEquals("Best", LrclibLyricsProvider(searchClient(ordered)).lyrics(track())?.lines?.single()?.text)
        }
    }

    @Test
    fun lrclibRefusesAmbiguousSearchResultsWithoutDuration() = runTest {
        val candidates = listOf(record(plain = "Version one"), record(album = "Other", duration = 300.0, plain = "Version two"))
        for (ordered in listOf(candidates, candidates.reversed())) {
            assertNull(LrclibLyricsProvider(searchClient(ordered)).lyrics(track().copy(durationSeconds = null)))
        }
        val duplicates = listOf(record(), record(album = "Compilation", duration = 300.0))
        assertEquals("Found", LrclibLyricsProvider(searchClient(duplicates)).lyrics(track().copy(durationSeconds = null))?.lines?.single()?.text)
    }

    @Test
    fun lrclibRefusesConflictingTiesButAcceptsBetterCandidate() = runTest {
        val ties = listOf(record(duration = 181.0, plain = "One"), record(duration = 179.0, plain = "Two"))
        for (ordered in listOf(ties, ties.reversed())) {
            assertNull(LrclibLyricsProvider(searchClient(ordered)).lyrics(track()))
            assertEquals("Found", LrclibLyricsProvider(searchClient(ordered + record())).lyrics(track())?.lines?.single()?.text)
        }
    }

    @Test
    fun lrclibAcceptsFormattingEquivalentTiesForReportedTrack() = runTest {
        val reported = track().copy(
            id = TrackId("346GJ27E4EGNzbd0TQ5WSu"),
            title = "男儿当自强",
            artistName = "成龙",
            albumTitle = "10年金马奖最佳电影主题曲光荣岁月",
            durationSeconds = 264,
        )
        val candidates = listOf(
            record(
                title = reported.title, artist = reported.artistName,
                album = "光荣岁月 10年金马奖最佳电影主题曲", duration = 264.0,
                synced = "[00:33.04] First line\n[01:20.46] \n[01:38.47] Second line",
            ),
            record(
                title = reported.title, artist = reported.artistName,
                album = "九大天王之十二出好戏", duration = 264.0,
                synced = "[00:33.040]First line\n[01:20.460]\n[01:38.470]Second line",
            ),
        )
        for (duration in listOf(264, null)) {
            for (ordered in listOf(candidates, candidates.reversed())) {
                val lyrics = LrclibLyricsProvider(searchClient(ordered)).lyrics(reported.copy(durationSeconds = duration))
                assertEquals(true, lyrics?.synced)
                assertEquals(listOf(LyricLine(33_040L, "First line"), LyricLine(98_470L, "Second line")), lyrics?.lines)
            }
        }
    }

    @Test
    fun lrclibStillRejectsEquivalentWordsWithConflictingTiming() = runTest {
        val candidates = listOf(
            record(synced = "[00:33.04] First line"),
            record(synced = "[00:33.05]First line"),
        )
        for (duration in listOf(180, null)) {
            for (ordered in listOf(candidates, candidates.reversed())) {
                assertNull(LrclibLyricsProvider(searchClient(ordered)).lyrics(track().copy(durationSeconds = duration)))
            }
        }
    }

    @Test
    fun lrclibDoesNotSearchAfterRateLimitsServerFailuresOrInstrumentalExactHits() = runTest {
        for (response in listOf(null, httpResponse("", 400), httpResponse("", 429), httpResponse("", 503), httpResponse(record(instrumental = true)))) {
            val client = LyricsHttpClient { response }
            assertNull(LrclibLyricsProvider(client).lyrics(track()))
            assertEquals(1, client.urls.size)
        }
    }

    @Test
    fun lrclibRejectsMalformedEmptyAndFailedSearchResponses() = runTest {
        for (response in listOf(httpResponse("not json"), httpResponse("[]"), httpResponse("{}"), httpResponse("", 429), httpResponse("", 503))) {
            val client = LyricsHttpClient { if (it.contains("/api/get?")) httpResponse("", 404) else response }
            assertNull(LrclibLyricsProvider(client).lyrics(track()))
            assertEquals(2, client.urls.size)
        }
    }

    @Test
    fun lrclibPreservesCancellationAndTransportErrorsWithoutRetrying() = runTest {
        for (duringSearch in listOf(false, true)) {
            for (failure in listOf(CancellationException("Cancelled"), IllegalStateException("Transport failed"))) {
                val client = LyricsHttpClient {
                    if (duringSearch && it.contains("/api/get?")) httpResponse("", 404) else throw failure
                }
                val actual = assertFailsWith<Exception> { LrclibLyricsProvider(client).lyrics(track()) }
                assertSame(failure, actual)
                assertEquals(if (duringSearch) 2 else 1, client.urls.size)
            }
        }
    }

    @Test
    fun lrclibDoesNotRequestUnidentifiedTracks() = runTest {
        val client = LyricsHttpClient { error("Unexpected request") }
        assertNull(LrclibLyricsProvider(client).lyrics(track().copy(title = " ")))
        assertNull(LrclibLyricsProvider(client).lyrics(track().copy(artistName = "")))
        assertTrue(client.urls.isEmpty())
    }

    @Test
    fun preferredLyricsKeepsSyncedLocalLyricsOverLrclib() {
        val local = Lyrics(
            source = LyricsSource.Provider,
            synced = true,
            lines = listOf(LyricLine(1_000L, "provider")),
        )
        val lrclib = Lyrics(
            source = LyricsSource.Lrclib,
            synced = true,
            lines = listOf(LyricLine(1_000L, "lrclib")),
        )

        assertEquals(local, selectPreferredLyrics(local, embeddedLyrics = null, onlineLyrics = lrclib))
    }

    @Test
    fun preferredLyricsKeepsProviderLyricsOverSyncedLrclibLyrics() {
        val provider = Lyrics(
            source = LyricsSource.Provider,
            synced = false,
            lines = listOf(LyricLine(null, "provider")),
        )
        val lrclib = Lyrics(
            source = LyricsSource.Lrclib,
            synced = true,
            lines = listOf(LyricLine(1_000L, "lrclib")),
        )

        assertEquals(provider, selectPreferredLyrics(provider, embeddedLyrics = null, onlineLyrics = lrclib))
    }

    @Test
    fun preferredLyricsUsesEmbeddedLyricsBeforeLrclibLyrics() {
        val embedded = Lyrics(
            source = LyricsSource.Embedded,
            synced = false,
            lines = listOf(LyricLine(null, "embedded")),
        )
        val lrclib = Lyrics(
            source = LyricsSource.Lrclib,
            synced = true,
            lines = listOf(LyricLine(1_000L, "lrclib")),
        )

        assertEquals(embedded, selectPreferredLyrics(providerLyrics = null, embeddedLyrics = embedded, onlineLyrics = lrclib))
    }

    private class LyricsHttpClient(private val respond: (String) -> SharedHttpResponse?) : SharedHttpClient {
        val urls = mutableListOf<String>()

        override suspend fun getResponse(url: String, headers: Map<String, String>): SharedHttpResponse? {
            urls += url
            return respond(url)
        }

        override suspend fun get(url: String, headers: Map<String, String>): String? = error("Unexpected text request")
        override suspend fun getBytes(url: String, headers: Map<String, String>): ByteArray? = error("Unexpected bytes request")
        override suspend fun download(
            url: String,
            headers: Map<String, String>,
            writeChunk: suspend (ByteArray, Int) -> Unit,
        ): Boolean = error("Unexpected download")
    }

    private fun searchClient(records: List<String>, exact: SharedHttpResponse? = httpResponse("", 404)) =
        LyricsHttpClient { url ->
            if (url.contains("/api/get?")) exact else {
                assertTrue(url.contains("/api/search?"))
                httpResponse(records.joinToString(",", "[", "]"))
            }
        }

    private fun httpResponse(body: String, status: Int = 200) = SharedHttpResponse(
        url = "https://lrclib.net", finalUrl = "https://lrclib.net", statusCode = status,
        contentType = "application/json", body = body.encodeToByteArray(),
    )

    private fun record(
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        duration: Double? = 180.0,
        plain: String = "Found",
        synced: String? = null,
        instrumental: Boolean = false,
    ): String = buildJsonObject {
        put("id", 42)
        put("trackName", title)
        put("artistName", artist)
        put("albumName", album)
        put("duration", duration?.let(::JsonPrimitive) ?: JsonNull)
        put("instrumental", instrumental)
        put("plainLyrics", plain)
        put("syncedLyrics", synced?.let(::JsonPrimitive) ?: JsonNull)
        put("hasWordSync", false)
        put("lyricsfile", "version: '1.0'")
    }.toString()

    private fun track(): Track =
        Track(
            id = TrackId("track"),
            title = "Song",
            artistName = "Artist",
            albumTitle = "Album",
            durationSeconds = 180,
            coverArtId = null,
            audioInfo = null,
            replayGain = null,
        )
}
