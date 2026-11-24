package app.naviamp.domain.home

import app.naviamp.domain.Album
import app.naviamp.domain.AlbumId
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistCredit
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.radio.GeneratedRadioKind
import app.naviamp.domain.radio.GeneratedRadioLibrary
import app.naviamp.domain.radio.GeneratedRadioRepository
import app.naviamp.domain.radio.GeneratedRadioStation
import app.naviamp.domain.radio.MemoryGeneratedRadioRepository
import app.naviamp.domain.radio.RadioArtistRunMode
import app.naviamp.domain.radio.RadioTuningSettings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeMixRecommendationsTest {
    @Test fun initiallyEmptyCatalogCanPopulateLaterWithinTheSameDay() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        assertTrue(engine.load("source", Identity, "scope", HomeDate(2026, 100), HomeMixSignals()).isEmpty())
        val available = engine.load("source", Identity, "scope", HomeDate(2026, 100),
            HomeMixSignals(explorationAlbums = albums("available", 2)))
        assertEquals(2, available.size)
    }

    @Test fun favoritesAndListeningProduceDifferentAffinityRecipesWithExplorationMinority() {
        val first = recommend(HomeMixSignals(favoriteArtists = (1..12).map { artist("first-$it") },
            explorationAlbums = albums("explore", 12)))
        val second = recommend(HomeMixSignals(favoriteArtists = (1..12).map { artist("second-$it") },
            explorationAlbums = albums("explore", 12)))
        assertEquals(6, first.count { it.kind == GeneratedRadioKind.Artist })
        assertEquals(2, first.count { it.kind == GeneratedRadioKind.Album })
        assertTrue(first.filter { it.kind == GeneratedRadioKind.Artist }.all { it.artists.single().id.startsWith("first-") })
        assertTrue(second.filter { it.kind == GeneratedRadioKind.Artist }.all { it.artists.single().id.startsWith("second-") })
        assertTrue(first.all { it.seedTracks.isEmpty() })
    }

    @Test fun timestampAgeAndDuplicateEventsAffectRankingInsteadOfLifetimePlayCounts() {
        val old = activity("old", Now - 180 * Day).copy(track = track("old").copy(playCount = 1_000_000))
        val recent = activity("recent", Now - Day)
        val frequent = activity("frequent", Now - Day)
        val signal = HomeMixSignals(activity = List(100) { old } + recent + List(3) { frequent })
        val result = recommend(signal, limit = 1)
        assertEquals("frequent", result.single().artists.single().id)
        val changed = recommend(HomeMixSignals(activity = List(4) { recent } + frequent), limit = 1)
        assertEquals("recent", changed.single().artists.single().id)
    }

    @Test fun invalidTimestampsAreIgnoredAndFutureActivityDoesNotGetAnUnboundedBoost() {
        val result = recommend(HomeMixSignals(activity = listOf(
            activity("invalid", -1), activity("future", Long.MAX_VALUE),
            activity("actual", Now), activity("actual", Now),
        )), limit = 1)
        assertEquals("actual", result.single().artists.single().id)
    }

    @Test fun sameDayRecreationUsesStoredRecipesEvenIfSignalsAndRandomnessChange() {
        val repository = MemoryGeneratedRadioRepository()
        val original = engine(repository).load("source", Identity, "scope", HomeDate(2026, 100),
            HomeMixSignals(favoriteArtists = (1..12).map { artist("a-$it") }))
        val restored = HomeMixRecommendations(repository, { Now }, Random(999)).load(
            "source", Identity, "scope", HomeDate(2026, 100), HomeMixSignals(explorationAlbums = albums("different", 12)))
        assertEquals(original, restored)
        assertEquals(original.map { it.id }, repository.load().rotations.single().stationIds)
    }

    @Test fun dailyAndExplicitRefreshAvoidPreviouslyShownSeedsWhenAlternativesExist() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        val signals = HomeMixSignals(explorationAlbums = albums("large", 30))
        val first = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals)
        val refresh = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals, refresh = true)
        val nextDay = engine.load("source", Identity, "scope", HomeDate(2026, 101), signals)
        assertTrue(first.seedIds().intersect(refresh.seedIds()).isEmpty())
        assertTrue((first.seedIds() + refresh.seedIds()).intersect(nextDay.seedIds()).isEmpty())
    }

    @Test fun coldStartAndSmallLibraryRemainUsableWithoutManufacturedDuplicateRecipes() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        val signals = HomeMixSignals(explorationAlbums = albums("small", 2))
        val first = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals)
        val refreshed = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals, refresh = true)
        assertEquals(2, first.size)
        assertEquals(first.seedIds(), refreshed.seedIds())
        assertEquals(first.map { it.id }.toSet(), refreshed.map { it.id }.toSet())
    }

    @Test fun savedStationsRemainStoredAndOutsideTheRotatingShelf() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        val signals = HomeMixSignals(explorationAlbums = albums("album", 12))
        val initial = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals)
        val savedStation = initial.first().copy(saved = true, name = "My station")
        repository.save(repository.load().copy(stations = repository.load().stations.map { if (it.id == savedStation.id) savedStation else it }))
        val refreshed = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals, refresh = true)
        assertFalse(refreshed.any { it.id == savedStation.id || it.albums == savedStation.albums })
        assertEquals(savedStation, repository.load().stations.single { it.id == savedStation.id })
        assertEquals(8, refreshed.size)
    }

    @Test fun failedRefreshKeepsPreviousCardsAndDoesNotAdvanceRotation() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        val original = engine.load("source", Identity, "scope", HomeDate(2026, 100),
            HomeMixSignals(favoriteArtists = listOf(artist("favorite"))))
        val saved = repository.load()
        val failed = engine.load("source", Identity, "scope", HomeDate(2026, 101),
            HomeMixSignals(explorationAlbums = albums("random", 12)), refresh = true, inputsFailed = true)
        assertEquals(original, failed)
        assertEquals(saved, repository.load())
        val emptied = engine.load("source", Identity, "scope", HomeDate(2026, 101), HomeMixSignals(), refresh = true)
        assertTrue(emptied.isEmpty())
    }

    @Test fun sourceAccountAndLibraryScopeNeverReusePreviousRecipeIdentities() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        val signals = HomeMixSignals(explorationAlbums = albums("shared-id", 2))
        val first = engine.load("source", Identity, "scope", HomeDate(2026, 100), signals)
        val folder = engine.load("source", Identity, "other-scope", HomeDate(2026, 100), signals)
        val account = engine.load("other-source", Identity.copy(accountIdentity = "other"), "scope", HomeDate(2026, 100), signals)
        assertTrue(first.map { it.id }.intersect(folder.map { it.id }.toSet()).isEmpty())
        assertTrue(first.map { it.id }.intersect(account.map { it.id }.toSet()).isEmpty())
        assertTrue(folder.all { it.libraryScope == "other-scope" })
        assertTrue(account.all { it.sourceId == "other-source" })
    }

    @Test fun strictRecipeDefaultsRequireProvableAnchorsWithoutDetailRequests() {
        val tuning = RadioTuningSettings(sameDecadeOnly = true, artistRunMode = RadioArtistRunMode.SingleArtist)
        val known = album("known").copy(originalReleaseYear = 1982, releaseYear = 2020)
        val unknown = album("unknown").copy(artistCredits = emptyList())
        val result = engine(MemoryGeneratedRadioRepository()).load("source", Identity, "scope", HomeDate(2026, 100),
            HomeMixSignals(favoriteAlbums = listOf(known, unknown), favoriteArtists = listOf(artist("no-era"))),
            tuning = tuning, batchSize = 25, sonicSimilarity = true)
        assertEquals(1, result.size)
        val station = result.single()
        assertEquals(1980, station.resolvedDecadeStart)
        assertEquals("known", station.resolvedArtistId)
        assertEquals(tuning, station.tuning)
        assertEquals(30, station.batchSize)
        assertTrue(station.sonicSimilarity)
        assertTrue(station.seedTracks.isEmpty())
    }

    @Test fun repositoryWriteFailureDoesNotPretendToPublishANewStableGeneration() {
        val repository = object : GeneratedRadioRepository {
            override fun load() = GeneratedRadioLibrary()
            override fun save(library: GeneratedRadioLibrary) { error("disk full") }
        }
        assertFailsWith<IllegalStateException> {
            engine(repository).load("source", Identity, "scope", HomeDate(2026, 100), HomeMixSignals(explorationAlbums = albums("album", 8)))
        }
    }

    @Test fun rotationHistoryAndTransientRecipesRemainBounded() {
        val repository = MemoryGeneratedRadioRepository()
        val engine = engine(repository)
        repeat(30) { day ->
            engine.load("source", Identity, "scope", HomeDate(2026, day + 1), HomeMixSignals(explorationAlbums = albums("day-$day", 14)))
        }
        assertTrue(repository.load().rotations.single().recentSeedIds.size <= 64)
        assertTrue(repository.load().stations.size <= 8)
    }

    private fun recommend(signals: HomeMixSignals, limit: Int = 8) = engine(MemoryGeneratedRadioRepository()).load(
        "source", Identity, "scope", HomeDate(2026, 100), signals, limit = limit)

    private fun engine(repository: GeneratedRadioRepository) = HomeMixRecommendations(repository, { Now }, Random(42))
    private fun artist(id: String) = Artist(ArtistId(id), "Artist $id")
    private fun album(id: String) = Album(AlbumId(id), "Album $id", "Artist $id", null, null,
        artistCredits = listOf(ArtistCredit(ArtistId(id), "Artist $id")))
    private fun albums(prefix: String, count: Int) = (1..count).map { album("$prefix-$it") }
    private fun activity(id: String, at: Long) = HomePlaybackActivity(track(id), at)
    private fun track(id: String) = Track(TrackId(id), "Track $id", artistId = ArtistId(id), artistName = "Artist $id",
        albumTitle = null, durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null)
    private fun List<GeneratedRadioStation>.seedIds() = flatMap { it.albums.map { album -> album.id } + it.artists.map { artist -> artist.id } }.toSet()

    private companion object {
        const val Day = 24 * 60 * 60 * 1000L
        const val Now = 300 * Day
        val Identity = NaviampConnectSourceIdentity("navidrome", "https://example.test", "user")
    }
}
