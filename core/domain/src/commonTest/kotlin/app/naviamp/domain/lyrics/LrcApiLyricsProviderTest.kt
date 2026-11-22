package app.naviamp.domain.lyrics

import app.naviamp.domain.LyricLine
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LrcApiLyricsProviderTest {
    @Test
    fun documentedLyricsWithoutDurationRemainUsable() = runTest {
        val http = RecordingHttp("""[{"id":"one","title":"Song","artist":"Artist","lyrics":"[00:01.25]First"}]""")
        val lyrics = LrcApiLyricsProvider(http).lyrics(track())
        assertEquals(LyricsSource.LrcApi, lyrics?.source)
        assertEquals(true, lyrics?.synced)
        assertEquals(listOf(LyricLine(1_250, "First")), lyrics?.lines)
        assertEquals("https://api.lrc.cx/jsonapi?title=Song&artist=Artist&album=Album", http.urls.single())
    }

    @Test
    fun liveLrcAndPlainMarkerAreDecodedWithoutDisplayingProtocolTags() = runTest {
        val body = """[{"title":"Song","artist":"Artist","duration":180.25,"lrc":"[!text]First\nSecond","lrc_ttml":"ignored"}]"""
        val lyrics = LrcApiLyricsProvider(RecordingHttp(body)).lyrics(track())
        assertEquals(false, lyrics?.synced)
        assertEquals(listOf(LyricLine(null, "First"), LyricLine(null, "Second")), lyrics?.lines)
    }

    @Test
    fun explicitPlainMarkerPreservesTimestampLikeTextAndUntimedLines() = runTest {
        val marked = record(text = "[!text][00:01]First\nSecond")
        val lyrics = LrcApiLyricsProvider(RecordingHttp(array(marked))).lyrics(track())
        assertEquals(false, lyrics?.synced)
        assertEquals(listOf(LyricLine(null, "[00:01]First"), LyricLine(null, "Second")), lyrics?.lines)
        val conflicting = array(marked, record(text = "[00:01]First\nSecond"))
        assertNull(LrcApiLyricsProvider(RecordingHttp(conflicting)).lyrics(track().copy(durationSeconds = null)))
    }

    @Test
    fun liveLrcIsUsedWhenDocumentedFieldIsBlank() = runTest {
        val body = """[{"title":"Song","artist":"Artist","lyrics":" ","lrc":"[00:01]Live"}]"""
        assertEquals(listOf(LyricLine(1_000, "Live")), LrcApiLyricsProvider(RecordingHttp(body)).lyrics(track())?.lines)
    }

    @Test
    fun requestsEncodeMetadataAndOmitUnavailableAlbum() = runTest {
        val item = track().copy(title = "何日 & you?", artistName = "A/B + C", albumTitle = " ", durationSeconds = null)
        val http = RecordingHttp(array(record(title = item.title, artist = item.artistName)))
        assertEquals("First", LrcApiLyricsProvider(http, "https://lyrics.example/").lyrics(item)?.lines?.single()?.text)
        assertEquals("https://lyrics.example/jsonapi?title=%E4%BD%95%E6%97%A5+%26+you%3F&artist=A%2FB+%2B+C", http.urls.single())
    }

    @Test
    fun unrelatedPublicSearchHitsCannotBecomeLyricsForHeRi() = runTest {
        val http = RecordingHttp(array(
            record(title = "何日不再来", artist = "邓丽欣", duration = 200.727),
            record(title = "何日君再来", artist = "李月", duration = 195.0),
            record(title = "何日", artist = "Other artist", duration = 195.0),
        ))
        assertNull(LrcApiLyricsProvider(http).lyrics(track().copy(title = "何日", artistName = "李丽芬", durationSeconds = 195)))
    }

    @Test
    fun identityAndKnownDurationRejectOtherRecordings() = runTest {
        for (bad in listOf(
            record(title = "Other"), record(artist = "Other"), record(title = "Song (Live)"),
            record(duration = 185.001), record(duration = 174.999), record(duration = -1.0), record(duration = 0.0),
            record(instrumental = true),
        )) {
            assertNull(LrcApiLyricsProvider(RecordingHttp(array(bad))).lyrics(track()), bad)
        }
        val valid = record(title = " SONG! ", artist = "artist", duration = 185.0)
        assertEquals("First", LrcApiLyricsProvider(RecordingHttp(array(valid))).lyrics(track())?.lines?.single()?.text)
    }

    @Test
    fun verifiedDurationWinsBeforeAlbumAndSyncedPreference() = runTest {
        val records = listOf(
            record(duration = null, text = "[00:01]Missing duration"),
            record(duration = 181.0, text = "[00:01]Further"),
            record(album = "Other album", text = "[00:01]Other album"),
            record(text = "[!text]Plain"),
            record(album = " ALBUM! ", text = "[00:01]Selected"),
        )
        for (ordered in listOf(records, records.reversed())) {
            assertEquals("Selected", LrcApiLyricsProvider(RecordingHttp(array(*ordered.toTypedArray()))).lyrics(track())?.lines?.single()?.text)
        }
    }

    @Test
    fun duplicateFormattingDoesNotCreateAmbiguity() = runTest {
        val records = listOf(
            record(duration = null, text = "[00:01.00] First"),
            record(duration = null, text = "[00:01.000]First"),
        )
        for (duration in listOf(180, null)) {
            for (ordered in listOf(records, records.reversed())) {
                val lyrics = LrcApiLyricsProvider(RecordingHttp(array(*ordered.toTypedArray()))).lyrics(track().copy(durationSeconds = duration))
                assertEquals(listOf(LyricLine(1_000, "First")), lyrics?.lines)
            }
        }
    }

    @Test
    fun genuineConflictsRemainUnavailableWhenDurationCannotDistinguishThem() = runTest {
        for (otherText in listOf("[00:01]Other words", "[00:02]First")) {
            val body = array(record(duration = null), record(duration = null, text = otherText))
            for (duration in listOf(180, null)) {
                assertNull(LrcApiLyricsProvider(RecordingHttp(body)).lyrics(track().copy(durationSeconds = duration)))
            }
        }
    }

    @Test
    fun malformedEmptyAndFailedResponsesRemainMisses() = runTest {
        for (body in listOf("not JSON", "{}", "[]", "[null]", """[{"title":"Song","artist":"Artist","lrc":"[!text]"}]""")) {
            assertNull(LrcApiLyricsProvider(RecordingHttp(body)).lyrics(track()))
        }
        for (status in listOf(403, 404, 429, 503)) {
            val http = RecordingHttp(array(record()), status)
            assertNull(LrcApiLyricsProvider(http).lyrics(track()))
            assertEquals(1, http.urls.size)
        }
    }

    @Test
    fun cancellationAndTransportFailuresPropagateWithoutRetries() = runTest {
        for (failure in listOf(CancellationException("Cancelled"), IllegalStateException("Transport failed"))) {
            val http = RecordingHttp("[]", failure = failure)
            assertSame(failure, assertFailsWith<Exception> { LrcApiLyricsProvider(http).lyrics(track()) })
            assertEquals(1, http.urls.size)
        }
    }

    @Test
    fun blankIdentityNeverSendsARequest() = runTest {
        val http = RecordingHttp("[]")
        assertNull(LrcApiLyricsProvider(http).lyrics(track().copy(title = " ")))
        assertNull(LrcApiLyricsProvider(http).lyrics(track().copy(artistName = "")))
        assertEquals(emptyList(), http.urls)
    }

    private fun record(
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        duration: Double? = 180.0,
        text: String = "[00:01]First",
        instrumental: Boolean = false,
    ): String = buildJsonObject {
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("duration", duration?.let(::JsonPrimitive) ?: JsonNull)
        put("lrc", text)
        put("instrumental", instrumental)
    }.toString()

    private fun array(vararg records: String): String = records.joinToString(",", "[", "]")

    private fun track() = Track(
        id = TrackId("track"), title = "Song", artistName = "Artist", albumTitle = "Album",
        durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
    )

    private class RecordingHttp(
        private val body: String,
        private val status: Int = 200,
        private val failure: Exception? = null,
    ) : SharedHttpClient {
        val urls = mutableListOf<String>()
        override suspend fun getResponse(url: String, headers: Map<String, String>): SharedHttpResponse {
            urls += url
            failure?.let { throw it }
            return SharedHttpResponse(url, url, status, "application/json", body.encodeToByteArray())
        }
        override suspend fun get(url: String, headers: Map<String, String>): String? = error("Unexpected text request")
        override suspend fun getBytes(url: String, headers: Map<String, String>): ByteArray? = error("Unexpected bytes request")
        override suspend fun download(url: String, headers: Map<String, String>, writeChunk: suspend (ByteArray, Int) -> Unit): Boolean =
            error("Unexpected download")
    }
}
