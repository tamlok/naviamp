package app.naviamp.domain.cache

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumDetails
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistDetails
import app.naviamp.domain.ArtistId
import app.naviamp.domain.LyricCue
import app.naviamp.domain.LyricCueLine
import app.naviamp.domain.LyricLine
import app.naviamp.domain.Lyrics
import app.naviamp.domain.LyricsMatchQuality
import app.naviamp.domain.LyricsSource
import app.naviamp.domain.ProviderId
import app.naviamp.domain.StreamRequest
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.StreamQuality
import app.naviamp.domain.audio.AudioMetadataSidecarService
import app.naviamp.domain.lyrics.LyricsSidecarService
import app.naviamp.domain.playback.PlaybackAudioAssetRepository
import app.naviamp.domain.playback.PlaybackLocalAudio
import app.naviamp.domain.lyrics.LyricsProvider
import app.naviamp.domain.lyrics.LyricsSearchCandidate
import app.naviamp.domain.lyrics.LyricsTiming
import app.naviamp.domain.lyrics.naviampOnlineLyricsProviders
import app.naviamp.domain.network.SharedHttpClient
import app.naviamp.domain.network.SharedHttpResponse
import app.naviamp.domain.provider.ConnectionValidation
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.ProviderCapabilities
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LyricsSidecarCacheServiceTest {
    @Test
    fun refreshesLegacyLineOnlyCacheOnceToDiscoverKaraokeCues() = runTest {
        val store = InMemoryLyricsSidecarStore(
            cached = CachedLyricsRow(
                lyricSource = LyricsSource.Provider.name,
                synced = true,
                linesJson = """[{"startMillis":1000,"text":"Old line"}]""",
                displayArtist = null,
                displayTitle = null,
                language = null,
                offsetMillis = 0,
            ),
        )
        val provider = RecordingLyricsProvider(karaokeLyrics())
        val service = LyricsSidecarCacheService(store = store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))

        val first = service.providerLyrics("source", provider, TrackId("track"))
        val second = service.providerLyrics("source", provider, TrackId("track"))

        assertTrue(first?.hasKaraokeCues == true)
        assertTrue(second?.hasKaraokeCues == true)
        assertEquals(1, provider.lyricsRequests)
        assertEquals(1, store.upserts)
    }

    @Test
    fun preservesLegacyLyricsWhenProviderHasNoReplacementAndMarksThemChecked() = runTest {
        val store = InMemoryLyricsSidecarStore(
            cached = CachedLyricsRow(
                lyricSource = LyricsSource.Provider.name,
                synced = true,
                linesJson = """[{"startMillis":1000,"text":"Keep me"}]""",
                displayArtist = null,
                displayTitle = null,
                language = null,
                offsetMillis = 0,
            ),
        )
        val provider = RecordingLyricsProvider(null)
        val service = LyricsSidecarCacheService(store = store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))

        val first = service.providerLyrics("source", provider, TrackId("track"))
        val second = service.providerLyrics("source", provider, TrackId("track"))

        assertEquals("Keep me", first?.lines?.single()?.text)
        assertEquals("Keep me", second?.lines?.single()?.text)
        assertEquals(1, provider.lyricsRequests)
        assertEquals(1, store.upserts)
    }

    @Test
    fun targetedClearReloadsProviderAndOnlineLyricsWithoutDiscardingOtherKeys() = runTest {
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val provider = RecordingLyricsProvider(karaokeLyrics())
        var onlineResult = karaokeLyrics().copy(source = LyricsSource.Lrclib)
        var onlineRequests = 0
        val online = object : LyricsProvider {
            override val id = "online"
            override val capabilities = LyricsTiming.entries.toSet()
            override suspend fun lyrics(track: Track, onCandidate: (LyricsSearchCandidate) -> Unit): Lyrics {
                onlineRequests++
                return onlineResult
            }
        }
        val repository = CachedLyricsSidecarRepository(cache, listOf(online))
        val track = Track(
            id = TrackId("track"), title = "Track", artistName = "Artist", albumTitle = "Album",
            durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
        )
        repository.providerLyrics("source", provider, track.id)
        repository.onlineLyrics("source", track, online)
        repository.cacheEmbeddedLyrics("other", track.id, karaokeLyrics().copy(source = LyricsSource.Embedded))
        repository.cacheEmbeddedLyrics("source", TrackId("other"), karaokeLyrics().copy(source = LyricsSource.Embedded))
        repository.onlineLyrics("other", track, online)
        repository.onlineLyrics("source", track.copy(id = TrackId("other")), online)
        provider.result = karaokeLyrics().copy(lines = listOf(LyricLine(0, "Fresh provider")))
        onlineResult = onlineResult.copy(lines = listOf(LyricLine(0, "Fresh online")))

        repository.clearCachedLyrics("source", track.id)

        assertEquals(null, repository.cachedLyrics("source", track.id))
        assertEquals(null, repository.cachedOnlineLyrics("source", track.id, online.id))
        assertEquals("Fresh provider", repository.providerLyrics("source", provider, track.id)?.lines?.single()?.text)
        assertEquals("Fresh online", repository.onlineLyrics("source", track, online)?.lines?.single()?.text)
        assertEquals(2, provider.lyricsRequests)
        assertEquals(4, onlineRequests)
        assertEquals(LyricsSource.Embedded, repository.cachedLyrics("other", track.id)?.source)
        assertEquals(LyricsSource.Embedded, repository.cachedLyrics("source", TrackId("other"))?.source)
        assertEquals("New line", repository.cachedOnlineLyrics("other", track.id, online.id)?.lines?.single()?.text)
        assertEquals("New line", repository.cachedOnlineLyrics("source", TrackId("other"), online.id)?.lines?.single()?.text)
    }

    @Test
    fun forcedResolverRefreshBypassesCompletedAndStoredLyricsAndDoesNotRestoreMisses() = runTest {
        val cache = LyricsSidecarCacheService(InMemoryLyricsSidecarStore(), nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val repository = CachedLyricsSidecarRepository(cache, emptyList())
        val provider = RecordingLyricsProvider(karaokeLyrics())
        val assets = object : PlaybackAudioAssetRepository {
            override suspend fun downloadedAudio(sourceId: String, trackId: TrackId): PlaybackLocalAudio? = null
            override suspend fun downloadedAudio(sourceId: String, trackId: TrackId, quality: StreamQuality): PlaybackLocalAudio? = null
            override suspend fun cachedAudio(sourceId: String, trackId: TrackId, quality: StreamQuality): PlaybackLocalAudio? = null
        }
        val resolver = LyricsSidecarService(repository, assets, AudioMetadataSidecarService(assets) { emptyList() })
        val track = Track(
            id = TrackId("track"), title = "Track", artistName = "Artist", albumTitle = "Album",
            durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
        )
        suspend fun load(refresh: Boolean = false) = resolver.loadLyrics(
            "source", provider, track, StreamQuality.Original,
            audioCachingEnabled = false, onlineLyricsEnabled = false, forceRefresh = refresh,
        ).lyrics
        assertEquals("New line", load()?.lines?.single()?.text)
        provider.result = karaokeLyrics().copy(lines = listOf(LyricLine(0, "Fresh")))
        assertEquals("New line", load()?.lines?.single()?.text)
        assertEquals("Fresh", load(refresh = true)?.lines?.single()?.text)
        assertEquals("Fresh", repository.cachedLyrics("source", track.id)?.lines?.single()?.text)
        provider.result = null
        assertEquals(null, load(refresh = true))
        assertEquals(null, repository.cachedLyrics("source", track.id))
        provider.result = karaokeLyrics()
        assertEquals("New line", load()?.lines?.single()?.text)
        assertEquals(4, provider.lyricsRequests)
    }

    @Test
    fun sharedCatalogLoadsLrcApiAfterMissesAndPreservesItAcrossResolvers() = runTest {
        val http = CatalogLyricsHttpClient()
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val repository = CachedLyricsSidecarRepository(cache, naviampOnlineLyricsProviders(http))
        val assets = object : PlaybackAudioAssetRepository {
            override suspend fun downloadedAudio(sourceId: String, trackId: TrackId): PlaybackLocalAudio? = null
            override suspend fun downloadedAudio(sourceId: String, trackId: TrackId, quality: StreamQuality): PlaybackLocalAudio? = null
            override suspend fun cachedAudio(sourceId: String, trackId: TrackId, quality: StreamQuality): PlaybackLocalAudio? = null
        }
        val track = Track(
            id = TrackId("track"), title = "Track", artistName = "Artist", albumTitle = "Album",
            durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
        )
        suspend fun load(online: Boolean) = LyricsSidecarService(
            repository, assets, AudioMetadataSidecarService(assets) { emptyList() },
        ).loadLyrics(
            "source", RecordingLyricsProvider(null), track, StreamQuality.Original,
            audioCachingEnabled = false, onlineLyricsEnabled = online,
        ).lyrics

        assertEquals(null, load(online = false))
        assertTrue(http.urls.isEmpty())
        val first = load(online = true)
        assertEquals(LyricsSource.LrcApi, first?.source)
        assertEquals(listOf(LyricLine(1_000, "LrcAPI line")), first?.lines)
        val requests = http.urls.toList()
        assertEquals(4, requests.size)
        assertTrue(requests.last().startsWith("https://api.lrc.cx/jsonapi?"))
        assertEquals(first, load(online = true))
        assertEquals(requests, http.urls)
        assertEquals(first, repository.cachedOnlineLyrics("source", track.id, "lrcapi"))
        assertEquals(null, repository.cachedOnlineLyrics("other-source", track.id, "lrcapi"))
        assertEquals(null, load(online = false))
        assertEquals(requests, http.urls)
    }

    @Test
    fun earlierCatalogHitAvoidsContactingLrcApi() = runTest {
        val http = CatalogLyricsHttpClient(
            lrclibBody = """{"trackName":"Track","artistName":"Artist","syncedLyrics":"[00:01]LRCLIB line"}""",
        )
        val repository = CachedLyricsSidecarRepository(
            LyricsSidecarCacheService(InMemoryLyricsSidecarStore(), nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler)),
            naviampOnlineLyricsProviders(http),
        )
        val assets = object : PlaybackAudioAssetRepository {
            override suspend fun downloadedAudio(sourceId: String, trackId: TrackId): PlaybackLocalAudio? = null
            override suspend fun downloadedAudio(sourceId: String, trackId: TrackId, quality: StreamQuality): PlaybackLocalAudio? = null
            override suspend fun cachedAudio(sourceId: String, trackId: TrackId, quality: StreamQuality): PlaybackLocalAudio? = null
        }
        val track = Track(
            id = TrackId("track"), title = "Track", artistName = "Artist", albumTitle = "Album",
            durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
        )
        val result = LyricsSidecarService(repository, assets, AudioMetadataSidecarService(assets) { emptyList() })
            .loadLyrics("source", RecordingLyricsProvider(null), track, StreamQuality.Original, false, true)
        assertEquals(LyricsSource.Lrclib, result.lyrics?.source)
        assertEquals(listOf(LyricLine(1_000, "LRCLIB line")), result.lyrics?.lines)
        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().startsWith("https://lrclib.net/api/get?"))
    }

    @Test
    fun bestEffortOnlineLyricsRoundTripRichPayloadAndRemainKeyIsolated() = runTest {
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val repository = CachedLyricsSidecarRepository(cache, emptyList())
        val track = onlineTrack()
        val fallback = karaokeLyrics().copy(
            source = LyricsSource.LrcApi,
            displayArtist = "Artist",
            displayTitle = "Track",
            language = "en",
            offsetMillis = 125,
            kind = "karaoke",
            matchQuality = LyricsMatchQuality.BestEffort,
        )
        val otherSource = fallback.copy(lines = listOf(LyricLine(1_000, "Other source")))
        val otherTrack = fallback.copy(lines = listOf(LyricLine(1_000, "Other track")))
        val otherProvider = fallback.copy(source = LyricsSource.Lrclib)

        assertEquals(fallback, repository.cacheOnlineLyrics("source", track.id, "online", fallback))
        repository.cacheOnlineLyrics("other-source", track.id, "online", otherSource)
        repository.cacheOnlineLyrics("source", TrackId("other-track"), "online", otherTrack)
        repository.cacheOnlineLyrics("source", track.id, "other-provider", otherProvider)

        val restored = LyricsSidecarCacheService(store, nowMillis = { 43L }, workContext = StandardTestDispatcher(testScheduler))
        assertEquals(fallback, restored.cachedOnlineLyrics("source", track.id, "online"))
        assertEquals(otherSource, restored.cachedOnlineLyrics("other-source", track.id, "online"))
        assertEquals(otherTrack, restored.cachedOnlineLyrics("source", TrackId("other-track"), "online"))
        assertEquals(otherProvider, restored.cachedOnlineLyrics("source", track.id, "other-provider"))
        assertEquals(null, restored.cachedOnlineLyrics("missing", track.id, "online"))
        val payload = store.cachedOnlineLyrics("source", track.id.value, "online")?.linesJson.orEmpty()
        assertTrue(payload.contains("\"matchQuality\":\"BestEffort\""))
        assertTrue(payload.contains("\"payloadVersion\":2"))
    }

    @Test
    fun legacyConfidenceDefaultsToExactAndUnknownConfidenceDefaultsToBestEffort() = runTest {
        val payloads = listOf(
            """[{"startMillis":1000,"text":"Cached line"}]""" to LyricsMatchQuality.Exact,
            """{"payloadVersion":2,"lines":[{"startMillis":1000,"text":"Cached line"}]}""" to LyricsMatchQuality.Exact,
            """{"lines":[{"startMillis":1000,"text":"Cached line"}],"matchQuality":null}""" to LyricsMatchQuality.Exact,
            """{"lines":[{"startMillis":1000,"text":"Cached line"}],"matchQuality":"Exact"}""" to LyricsMatchQuality.Exact,
            """{"lines":[{"startMillis":1000,"text":"Cached line"}],"matchQuality":"BestEffort"}""" to LyricsMatchQuality.BestEffort,
            """{"lines":[{"startMillis":1000,"text":"Cached line"}],"matchQuality":"FutureQuality"}""" to LyricsMatchQuality.BestEffort,
        )
        for ((payload, expectedQuality) in payloads) {
            val store = InMemoryLyricsSidecarStore(
                onlineCached = CachedLyricsRow(
                    lyricSource = LyricsSource.Lrclib.name,
                    synced = true,
                    linesJson = payload,
                    displayArtist = "Artist",
                    displayTitle = "Track",
                    language = "en",
                    offsetMillis = 125,
                ),
            )
            val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))

            val restored = cache.cachedOnlineLyrics("source", TrackId("track"), "online")

            assertEquals(expectedQuality, restored?.matchQuality, payload)
            assertEquals(listOf(LyricLine(1_000, "Cached line")), restored?.lines)
            assertEquals(LyricsSource.Lrclib, restored?.source)
            assertEquals(125, restored?.offsetMillis)
        }
    }

    @Test
    fun cachedBestEffortRechecksProviderForwardsCandidatesAndKeepsRowOnMiss() = runTest {
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val track = onlineTrack()
        val fallback = karaokeLyrics().copy(source = LyricsSource.Lrclib, matchQuality = LyricsMatchQuality.BestEffort)
        val candidate = object : LyricsSearchCandidate {
            override val title = "Track"
            override val artist = "Artist"
            override val album = "Album"
            override val duration = 180.0
            override val instrumental = false
            override val hasSyncedLyrics = true
            override fun lyricsText() = "[00:01]New line"
            override fun toLyrics() = fallback
        }
        val online = RecordingOnlineLyricsProvider(null, listOf(candidate))
        val repository = CachedLyricsSidecarRepository(cache, listOf(online))
        repository.cacheOnlineLyrics("source", track.id, online.id, fallback)
        val stored = store.cachedOnlineLyrics("source", track.id.value, online.id)
        val candidates = mutableListOf<LyricsSearchCandidate>()

        repeat(2) {
            assertEquals(null, repository.onlineLyrics("source", track, online, onCandidate = { candidates += it }))
        }

        assertEquals(2, online.lyricsRequests)
        assertEquals(listOf<LyricsSearchCandidate>(candidate, candidate), candidates)
        assertEquals(stored, store.cachedOnlineLyrics("source", track.id.value, online.id))
        assertEquals(fallback, repository.cachedOnlineLyrics("source", track.id, online.id))
        assertEquals(1, store.onlineUpserts)
    }

    @Test
    fun failedBestEffortRecheckKeepsFallbackRow() = runTest {
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val track = onlineTrack()
        val fallback = karaokeLyrics().copy(source = LyricsSource.Lrclib, matchQuality = LyricsMatchQuality.BestEffort)
        val online = RecordingOnlineLyricsProvider(null).apply { failure = IllegalStateException("Provider unavailable") }
        cache.cacheOnlineLyrics("source", track.id, online.id, fallback)
        val stored = store.cachedOnlineLyrics("source", track.id.value, online.id)

        assertFailsWith<IllegalStateException> { cache.onlineLyrics("source", track, online) }

        assertEquals(1, online.lyricsRequests)
        assertEquals(stored, store.cachedOnlineLyrics("source", track.id.value, online.id))
        assertEquals(fallback, cache.cachedOnlineLyrics("source", track.id, online.id))
        assertEquals(1, store.onlineUpserts)
    }

    @Test
    fun laterExactReplacesBestEffortAndSkipsSubsequentProviderRequests() = runTest {
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val track = onlineTrack()
        val fallback = karaokeLyrics().copy(source = LyricsSource.Lrclib, matchQuality = LyricsMatchQuality.BestEffort)
        val exact = fallback.copy(lines = listOf(LyricLine(1_000, "Exact line")), matchQuality = LyricsMatchQuality.Exact)
        val online = RecordingOnlineLyricsProvider(exact)
        cache.cacheOnlineLyrics("source", track.id, online.id, fallback)

        assertEquals(exact, cache.onlineLyrics("source", track, online))
        assertEquals(exact, cache.onlineLyrics("source", track, online))

        assertEquals(1, online.lyricsRequests)
        assertEquals(2, store.onlineUpserts)
        assertEquals(exact, cache.cachedOnlineLyrics("source", track.id, online.id))
    }

    @Test
    fun cachedExactRechecksProviderWhenTimingIsNotAccepted() = runTest {
        val cache = LyricsSidecarCacheService(InMemoryLyricsSidecarStore(), nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val track = onlineTrack()
        val exact = karaokeLyrics().copy(source = LyricsSource.Lrclib)
        val plain = exact.copy(synced = false, lines = listOf(LyricLine(null, "Plain line")), cueLines = emptyList())
        val online = RecordingOnlineLyricsProvider(exact)
        cache.cacheOnlineLyrics("source", track.id, online.id, plain)

        assertEquals(exact, cache.onlineLyrics("source", track, online, acceptedTimings = setOf(LyricsTiming.WordSynced)))

        assertEquals(1, online.lyricsRequests)
        assertEquals(exact, cache.cachedOnlineLyrics("source", track.id, online.id))
    }

    @Test
    fun fallbackWritesCannotReplaceExactAndReturnStoredExact() = runTest {
        val store = InMemoryLyricsSidecarStore()
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val repository = CachedLyricsSidecarRepository(cache, emptyList())
        val track = onlineTrack()
        val exact = karaokeLyrics().copy(source = LyricsSource.Lrclib)
        val fallback = exact.copy(lines = listOf(LyricLine(1_000, "Fallback line")), matchQuality = LyricsMatchQuality.BestEffort)
        val online = RecordingOnlineLyricsProvider(fallback)
        repository.cacheOnlineLyrics("source", track.id, online.id, exact)
        val stored = store.cachedOnlineLyrics("source", track.id.value, online.id)

        assertEquals(exact, repository.cacheOnlineLyrics("source", track.id, online.id, fallback))
        assertEquals(exact, repository.onlineLyrics("source", track, online, acceptedTimings = setOf(LyricsTiming.Plain)))

        assertEquals(1, online.lyricsRequests)
        assertEquals(1, store.onlineUpserts)
        assertEquals(stored, store.cachedOnlineLyrics("source", track.id.value, online.id))
        assertEquals(exact, repository.cachedOnlineLyrics("source", track.id, online.id))
    }

    @Test
    fun clearingBestEffortAllowsFreshOnlineLookupWithoutRestoringFallback() = runTest {
        val cache = LyricsSidecarCacheService(InMemoryLyricsSidecarStore(), nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
        val track = onlineTrack()
        val fallback = karaokeLyrics().copy(source = LyricsSource.Lrclib, matchQuality = LyricsMatchQuality.BestEffort)
        val online = RecordingOnlineLyricsProvider(null)
        cache.cacheOnlineLyrics("source", track.id, online.id, fallback)

        cache.clearCachedLyrics("source", track.id)

        assertEquals(null, cache.cachedOnlineLyrics("source", track.id, online.id))
        assertEquals(null, cache.onlineLyrics("source", track, online))
        assertEquals(null, cache.cachedOnlineLyrics("source", track.id, online.id))
        online.result = fallback.copy(matchQuality = LyricsMatchQuality.Exact)
        assertEquals(online.result, cache.onlineLyrics("source", track, online))
        assertEquals(2, online.lyricsRequests)
    }

    @Test
    fun cancelledProviderAndOnlineLoadsCannotResurrectLyricsAfterRefresh() = runTest {
        for (useOnline in listOf(false, true)) {
            val store = InMemoryLyricsSidecarStore()
            val cache = LyricsSidecarCacheService(store, nowMillis = { 42L }, workContext = StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source = if (useOnline) LyricsSource.LrcApi else LyricsSource.Provider
            val old = karaokeLyrics().copy(source = source)
            val fresh = old.copy(lines = listOf(LyricLine(1_000, "Refreshed")))
            var delayed = true
            suspend fun fetched(): Lyrics = if (delayed) {
                withContext(NonCancellable) { entered.complete(Unit); release.await(); old }
            } else fresh
            val provider = object : MediaProvider by RecordingLyricsProvider(null) {
                override suspend fun lyrics(trackId: TrackId): Lyrics = fetched()
            }
            val online = object : LyricsProvider {
                override val id = "online"
                override val capabilities = LyricsTiming.entries.toSet()
                override suspend fun lyrics(track: Track, onCandidate: (LyricsSearchCandidate) -> Unit): Lyrics = fetched()
            }
            val track = Track(
                id = TrackId("track"), title = "Track", artistName = "Artist", albumTitle = "Album",
                durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
            )
            suspend fun load() = if (useOnline) cache.onlineLyrics("source", track, online)
                else cache.providerLyrics("source", provider, track.id)
            val prefetch = async { load() }
            entered.await()
            prefetch.cancel()
            cache.clearCachedLyrics("source", track.id)
            delayed = false
            assertEquals(fresh, load())
            release.complete(Unit)
            prefetch.join()
            assertTrue(prefetch.isCancelled)
            val cached = if (useOnline) cache.cachedOnlineLyrics("source", track.id, online.id)
                else cache.cachedLyrics("source", track.id)
            assertEquals(fresh, cached)
        }
    }

    private fun onlineTrack(): Track = Track(
        id = TrackId("track"), title = "Track", artistName = "Artist", albumTitle = "Album",
        durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null,
    )

    private fun karaokeLyrics(): Lyrics = Lyrics(
        source = LyricsSource.Provider,
        synced = true,
        lines = listOf(LyricLine(1_000, "New line")),
        cueLines = listOf(
            LyricCueLine(
                lineIndex = 0,
                startMillis = 1_000,
                endMillis = 2_000,
                text = "New line",
                cues = listOf(LyricCue(1_000, 2_000, "New", 0, 2)),
            ),
        ),
    )
}

private class InMemoryLyricsSidecarStore(
    cached: CachedLyricsRow? = null,
    onlineCached: CachedLyricsRow? = null,
) : LyricsSidecarStore {
    private val providerRows = mutableMapOf<Pair<String, String>, CachedLyricsRow>().apply {
        cached?.let { put("source" to "track", it) }
    }
    private val onlineRows = mutableMapOf<Triple<String, String, String>, CachedLyricsRow>().apply {
        onlineCached?.let { put(Triple("source", "track", "online"), it) }
    }
    var upserts: Int = 0
        private set
    var onlineUpserts: Int = 0
        private set

    override fun clearCachedLyrics(sourceId: String, trackId: String) {
        providerRows.remove(sourceId to trackId)
        onlineRows.keys.removeAll { it.first == sourceId && it.second == trackId }
    }

    override fun cachedLyrics(sourceId: String, trackId: String): CachedLyricsRow? = providerRows[sourceId to trackId]
    override fun touchCachedLyrics(sourceId: String, trackId: String, lastAccessedEpochMillis: Long) = Unit
    override fun upsertCachedLyrics(
        sourceId: String,
        trackId: String,
        lyricSource: String,
        synced: Boolean,
        linesJson: String,
        displayArtist: String?,
        displayTitle: String?,
        language: String?,
        offsetMillis: Long,
        sizeBytes: Long,
        createdAtEpochMillis: Long,
        lastAccessedEpochMillis: Long,
    ) {
        upserts += 1
        providerRows[sourceId to trackId] = CachedLyricsRow(
            lyricSource = lyricSource,
            synced = synced,
            linesJson = linesJson,
            displayArtist = displayArtist,
            displayTitle = displayTitle,
            language = language,
            offsetMillis = offsetMillis,
        )
    }

    override fun cachedOnlineLyrics(sourceId: String, trackId: String, onlineProviderId: String): CachedLyricsRow? =
        onlineRows[Triple(sourceId, trackId, onlineProviderId)]
    override fun touchCachedOnlineLyrics(
        sourceId: String,
        trackId: String,
        onlineProviderId: String,
        lastAccessedEpochMillis: Long,
    ) = Unit
    override fun upsertCachedOnlineLyrics(
        sourceId: String,
        trackId: String,
        onlineProviderId: String,
        lyricSource: String,
        synced: Boolean,
        linesJson: String,
        displayArtist: String?,
        displayTitle: String?,
        language: String?,
        offsetMillis: Long,
        sizeBytes: Long,
        createdAtEpochMillis: Long,
        lastAccessedEpochMillis: Long,
    ) {
        onlineUpserts += 1
        onlineRows[Triple(sourceId, trackId, onlineProviderId)] = CachedLyricsRow(
            lyricSource, synced, linesJson, displayArtist, displayTitle, language, offsetMillis,
        )
    }
}

private class RecordingOnlineLyricsProvider(
    var result: Lyrics?,
    private val candidates: List<LyricsSearchCandidate> = emptyList(),
) : LyricsProvider {
    override val id = "online"
    override val capabilities = LyricsTiming.entries.toSet()
    var lyricsRequests: Int = 0
        private set
    var failure: Throwable? = null

    override suspend fun lyrics(track: Track, onCandidate: (LyricsSearchCandidate) -> Unit): Lyrics? {
        lyricsRequests += 1
        failure?.let { throw it }
        candidates.forEach(onCandidate)
        return result
    }
}

private class RecordingLyricsProvider(
    var result: Lyrics?,
) : MediaProvider {
    var lyricsRequests: Int = 0
        private set

    override val id = ProviderId("fake")
    override val displayName = "Fake"
    override val capabilities = ProviderCapabilities(
        supportsStreamingTranscode = true,
        supportsDownloadTranscode = true,
        supportsArtistRadio = false,
        supportsAlbumRadio = false,
        supportsTrackRadio = false,
    )

    override suspend fun validateConnection() = ConnectionValidation(null, null)
    override suspend fun recentlyAddedAlbums(limit: Int): List<Album> = emptyList()
    override suspend fun album(albumId: AlbumId): AlbumDetails = error("Not used")
    override suspend fun artist(artistId: ArtistId): ArtistDetails = error("Not used")
    override suspend fun artists(limit: Int): List<Artist> = emptyList()
    override suspend fun tracks(limit: Int): List<Track> = emptyList()
    override suspend fun search(query: String, limit: Int) = MediaSearchResults()
    override suspend fun lyrics(trackId: TrackId): Lyrics? {
        lyricsRequests += 1
        return result
    }
    override suspend fun streamUrl(request: StreamRequest) = "https://example.test/stream"
    override fun coverArtUrl(coverArtId: String) = "https://example.test/cover/$coverArtId"
}

private class CatalogLyricsHttpClient(private val lrclibBody: String? = null) : SharedHttpClient {
    val urls = mutableListOf<String>()
    override suspend fun getResponse(url: String, headers: Map<String, String>): SharedHttpResponse {
        urls += url
        val body = when {
            url.startsWith("https://api.lrc.cx/jsonapi?") ->
                """[{"title":"Track","artist":"Artist","lrc":"[00:01]LrcAPI line"}]"""
            url.startsWith("https://lrclib.net/api/get?") -> lrclibBody
            else -> null
        }
        return SharedHttpResponse(url, url, if (body == null) 404 else 200, "application/json", body.orEmpty().encodeToByteArray())
    }
    override suspend fun get(url: String, headers: Map<String, String>): String? =
        getResponse(url, headers).takeIf { it.statusCode in 200..299 }?.bodyText()
    override suspend fun getBytes(url: String, headers: Map<String, String>): ByteArray? = error("Unexpected bytes request")
    override suspend fun download(url: String, headers: Map<String, String>, writeChunk: suspend (ByteArray, Int) -> Unit): Boolean =
        error("Unexpected download")
}
