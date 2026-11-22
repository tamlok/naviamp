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
import app.naviamp.domain.lyrics.LyricsTiming
import app.naviamp.domain.provider.ConnectionValidation
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.MediaSearchResults
import app.naviamp.domain.provider.ProviderCapabilities
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val service = LyricsSidecarCacheService(store = store, nowMillis = { 42L })

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
        val service = LyricsSidecarCacheService(store = store, nowMillis = { 42L })

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
        val cache = LyricsSidecarCacheService(store, nowMillis = { 42L })
        val provider = RecordingLyricsProvider(karaokeLyrics())
        var onlineResult = karaokeLyrics().copy(source = LyricsSource.Lrclib)
        var onlineRequests = 0
        val online = object : LyricsProvider {
            override val id = "online"
            override val capabilities = LyricsTiming.entries.toSet()
            override suspend fun lyrics(track: Track): Lyrics {
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
        val cache = LyricsSidecarCacheService(InMemoryLyricsSidecarStore(), nowMillis = { 42L })
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
) : LyricsSidecarStore {
    private val providerRows = mutableMapOf<Pair<String, String>, CachedLyricsRow>().apply {
        cached?.let { put("source" to "track", it) }
    }
    private val onlineRows = mutableMapOf<Triple<String, String, String>, CachedLyricsRow>()
    var upserts: Int = 0
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
        onlineRows[Triple(sourceId, trackId, onlineProviderId)] = CachedLyricsRow(
            lyricSource, synced, linesJson, displayArtist, displayTitle, language, offsetMillis,
        )
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
