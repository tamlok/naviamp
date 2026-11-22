package app.naviamp.domain.network

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.lyrics.LrclibLyricsProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SharedHttpClientTest {
    @Test
    fun errorStatusRemainsVisibleWhileTextAndBytesStaySuccessOnly() = runTest {
        withServer({ exchange ->
            exchange.responseHeaders.add("Retry-After", "60")
            exchange.reply(exchange.requestURI.path.removePrefix("/").toInt(), "Unavailable")
        }) { baseUrl ->
            val calls = mutableListOf<SharedHttpCall>()
            KtorSharedHttpClient(callRecorder = calls::add).use { client ->
                for (status in listOf(404, 429, 503)) {
                    val response = client.getResponse("$baseUrl/$status")
                    assertEquals(status, response?.statusCode)
                    assertEquals("Unavailable", response?.bodyText())
                    assertNull(client.get("$baseUrl/$status"))
                    assertNull(client.getBytes("$baseUrl/$status"))
                    assertEquals(status, calls.last().statusCode)
                    assertEquals("HTTP $status.", calls.last().errorMessage)
                    assertFalse(calls.last().success)
                }
            }
        }
    }

    @Test
    fun successfulBodiesAndCallRecordingArePreserved() = runTest {
        withServer({ it.reply(200, "Lyrics") }) { baseUrl ->
            val calls = mutableListOf<SharedHttpCall>()
            KtorSharedHttpClient(callRecorder = calls::add).use { client ->
                assertEquals("Lyrics", client.get(baseUrl))
                assertContentEquals("Lyrics".encodeToByteArray(), client.getBytes(baseUrl))
                assertEquals(200, client.getResponse(baseUrl)?.statusCode)
                assertTrue(calls.all { it.success && it.errorMessage == null })
            }
        }
    }

    @Test
    fun realLyricsRequestsSearchAfterNotFoundButNotAfterThrottling() = runTest {
        for (exactStatus in listOf(404, 429, 503)) {
            val paths = mutableListOf<String>()
            withServer({ exchange ->
                paths += exchange.requestURI.path
                when (exchange.requestURI.path) {
                    "/api/get" -> exchange.reply(exactStatus, "Unavailable")
                    "/api/search" -> exchange.reply(200, """
                        [{"trackName":"Song","artistName":"Artist","albumName":"Compilation",
                          "duration":180.25,"instrumental":false,"plainLyrics":"Recovered lyrics"}]
                    """.trimIndent())
                    else -> exchange.reply(404, "Unexpected path")
                }
            }) { baseUrl ->
                KtorSharedHttpClient().use { client ->
                    val lyrics = LrclibLyricsProvider(client, baseUrl).lyrics(
                        Track(
                            id = TrackId("track"), title = "Song", artistName = "Artist", albumTitle = "Album",
                            durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
                        ),
                    )
                    if (exactStatus == 404) {
                        assertEquals("Recovered lyrics", lyrics?.lines?.single()?.text)
                        assertEquals(listOf("/api/get", "/api/search"), paths)
                    } else {
                        assertNull(lyrics)
                        assertEquals(listOf("/api/get"), paths)
                    }
                }
            }
        }
    }

    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.reply(status: Int, text: String) {
        val bytes = text.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }
}
