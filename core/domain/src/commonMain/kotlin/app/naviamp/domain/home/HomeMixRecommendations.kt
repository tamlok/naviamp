package app.naviamp.domain.home

import app.naviamp.domain.Album
import app.naviamp.domain.Artist
import app.naviamp.domain.ArtistCredit
import app.naviamp.domain.Track
import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.eraReleaseYear
import app.naviamp.domain.radio.DefaultRadioMinimumTracks
import app.naviamp.domain.radio.GeneratedRadioHomeRotation
import app.naviamp.domain.radio.GeneratedRadioKind
import app.naviamp.domain.radio.GeneratedRadioRepository
import app.naviamp.domain.radio.GeneratedRadioStation
import app.naviamp.domain.radio.RadioArtistRunMode
import app.naviamp.domain.radio.RadioTuningSettings
import app.naviamp.domain.settings.SavedAlbum
import app.naviamp.domain.settings.SavedArtist
import kotlin.math.exp
import kotlin.random.Random

/** One listening event, not a distinct-track or lifetime-play-count projection. */
data class HomePlaybackActivity(val track: Track, val playedAtEpochMillis: Long)

/** Already scoped metadata only. Building Home never resolves radio tracks or album details. */
data class HomeMixSignals(
    val activity: List<HomePlaybackActivity> = emptyList(),
    val favoriteArtists: List<Artist> = emptyList(),
    val favoriteAlbums: List<Album> = emptyList(),
    val frequentAlbums: List<Album> = emptyList(),
    val recentAlbums: List<Album> = emptyList(),
    val explorationAlbums: List<Album> = emptyList(),
)

class HomeMixRecommendations(
    private val repository: GeneratedRadioRepository,
    private val nowMillis: () -> Long = { 0L },
    private val random: Random = Random.Default,
) {
    fun load(
        sourceId: String,
        sourceIdentity: NaviampConnectSourceIdentity?,
        libraryScope: String,
        date: HomeDate,
        signals: HomeMixSignals,
        refresh: Boolean = false,
        inputsFailed: Boolean = false,
        limit: Int = HomeMixRecommendationLimit,
        sonicSimilarity: Boolean = false,
        tuning: RadioTuningSettings = RadioTuningSettings(),
        batchSize: Int = DefaultRadioMinimumTracks,
    ): List<GeneratedRadioStation> {
        val library = repository.load()
        val scopedStations = library.stations.filter {
            it.libraryScope == libraryScope && it.belongsTo(sourceId, sourceIdentity, libraryScope)
        }
        val stationsById = scopedStations.associateBy { it.id }
        val rotation = library.rotations.firstOrNull { it.sourceId == sourceId && it.libraryScope == libraryScope }
        val previous = rotation?.stationIds.orEmpty().mapNotNull(stationsById::get).filterNot { it.pinned }
        val count = limit.coerceIn(0, HomeMixRecommendationLimit)
        val day = date.year.toLong() * 366 + date.dayOfYear
        if (rotation != null && !refresh && rotation.day == day && previous.isNotEmpty() && previous.size == rotation.stationIds.size) {
            return previous.take(count)
        }
        // A failed refresh must not replace personalized cards with only the surviving random rail.
        if (inputsFailed && previous.isNotEmpty()) return previous.take(count)

        val now = nowMillis().coerceAtLeast(0)
        val candidates = candidates(signals, now).filter { candidate ->
            (!tuning.sameDecadeOnly || candidate.decade != null) &&
                (tuning.artistRunMode != RadioArtistRunMode.SingleArtist || candidate.artist != null) &&
                scopedStations.none { it.pinned && candidate.matches(it) }
        }
        val recentSeeds = rotation?.recentSeedIds.orEmpty().withIndex().associate { it.value to it.index }
        val ranked = candidates.sortedWith(
            compareBy<Candidate> { recentSeeds.containsKey(it.key) }
                .thenByDescending { recentSeeds[it.key] ?: Int.MAX_VALUE }
                .thenByDescending { it.score }
                .thenBy { it.tieBreaker },
        )
        val selected = ArrayList<Candidate>(count)
        val selectedKeys = HashSet<String>()
        val selectedArtists = HashSet<String>()
        fun select(pool: List<Candidate>, targetSize: Int) {
            for (diverse in listOf(true, false)) {
                for (candidate in pool) {
                    if (selected.size >= targetSize) return
                    if (candidate.key in selectedKeys) continue
                    val artistId = candidate.artist?.id?.value
                    if (diverse && artistId != null && artistId in selectedArtists) continue
                    selected += candidate
                    selectedKeys += candidate.key
                    if (artistId != null) selectedArtists += artistId
                }
            }
        }
        val affinityCount = (count * 3 + 3) / 4
        select(ranked.filter { it.score > 0 }, affinityCount)
        select(ranked.filter { it.exploration }, count)
        select(ranked, count)
        val allocatedIds = library.stations.mapTo(HashSet()) { it.id }
        val stations = selected.map { candidate ->
            scopedStations.firstOrNull { candidate.matches(it) && !it.pinned } ?: GeneratedRadioStation(
                id = newId(allocatedIds).also(allocatedIds::add),
                name = candidate.artist?.name.takeIf { candidate.album == null } ?: candidate.album!!.title,
                kind = if (candidate.album == null) GeneratedRadioKind.Artist else GeneratedRadioKind.Album,
                sourceId = sourceId,
                sourceIdentity = sourceIdentity?.normalized(),
                libraryScope = libraryScope,
                updatedAtEpochMillis = now,
                artists = candidate.artist?.let { listOf(SavedArtist.fromArtist(it)) }.orEmpty(),
                albums = candidate.album?.let { listOf(SavedAlbum.fromAlbum(it)) }.orEmpty(),
                fromYear = candidate.decade.takeIf { tuning.sameDecadeOnly },
                toYear = candidate.decade?.plus(9).takeIf { tuning.sameDecadeOnly },
                sonicSimilarity = sonicSimilarity,
                resolvedArtistId = candidate.artist?.id?.value,
                resolvedDecadeStart = candidate.decade.takeIf { tuning.sameDecadeOnly },
                tuning = tuning,
                batchSize = batchSize,
            ).normalized()
        }
        val nextRotation = GeneratedRadioHomeRotation(
            sourceId = sourceId,
            libraryScope = libraryScope,
            day = day,
            stationIds = stations.map { it.id },
            recentSeedIds = (selected.map { it.key } + rotation?.recentSeedIds.orEmpty()).distinct().take(HomeMixRecentSeedLimit),
        )
        val rotations = (listOf(nextRotation) + library.rotations.filterNot {
            it.sourceId == sourceId && it.libraryScope == libraryScope
        }).take(50)
        val retainedIds = rotations.flatMapTo(HashSet()) { it.stationIds }
        library.batches.mapTo(retainedIds) { it.stationId }
        val obsoleteIds = library.rotations.flatMapTo(HashSet()) { it.stationIds } - retainedIds
        val nextStations = library.stations.filterNot { it.id in obsoleteIds && !it.saved && !it.pinned }
        repository.save(library.copy(
            stations = (stations + nextStations).distinctBy { it.id },
            rotations = rotations,
        ))
        return stations
    }

    private fun newId(existing: Set<String>): String {
        var id: String
        do {
            id = "home-${random.nextLong().toULong().toString(16)}-${random.nextLong().toULong().toString(16)}"
        } while (id in existing)
        return id
    }

    private fun candidates(signals: HomeMixSignals, now: Long): List<Candidate> {
        val candidates = LinkedHashMap<String, Candidate>()
        fun add(artist: Artist? = null, album: Album? = null, score: Double, exploration: Boolean = false, decade: Int? = null) {
            val key = if (album != null) "album:${album.id.value}" else "artist:${artist?.id?.value ?: return}"
            val previous = candidates[key]
            candidates[key] = Candidate(
                key, artist ?: previous?.artist, album ?: previous?.album,
                (previous?.score ?: 0.0) + score,
                exploration || previous?.exploration == true,
                decade ?: previous?.decade,
                previous?.tieBreaker ?: random.nextDouble(),
            )
        }
        for (event in signals.activity.take(HomeMixHistoryLimit)) {
            if (event.playedAtEpochMillis <= 0) continue
            val age = (now - event.playedAtEpochMillis.coerceAtMost(now)).coerceAtLeast(0)
            val weight = exp(-age.toDouble() / HomeMixActivityDecayMillis)
            val track = event.track
            val artist = track.artistId?.let { Artist(it, track.artistName) }
            val decade = track.eraReleaseYear?.floorToDecade()
            if (artist != null) add(artist = artist, score = weight, decade = decade)
            val album = track.albumId?.let { id ->
                track.albumTitle?.let { title -> Album(id, title, track.artistName, track.coverArtId, null,
                    track.albumReleaseYear, track.originalReleaseYear,
                    artistCredits = artist?.let { listOf(ArtistCredit(it.id, it.name)) }.orEmpty()) }
            }
            if (album != null) add(artist = artist, album = album, score = weight * 0.8, decade = decade)
        }
        for (artist in signals.favoriteArtists.take(HomeMixHistoryLimit)) add(artist = artist, score = 4.0)
        fun addAlbums(albums: List<Album>, score: Double, exploration: Boolean = false) {
            for (album in albums.take(HomeMixHistoryLimit)) {
                val credit = album.artistCredits.singleOrNull()
                val artist = credit?.id?.let { Artist(it, credit.name) }
                add(artist, album, score, exploration, album.eraReleaseYear?.floorToDecade())
            }
        }
        addAlbums(signals.favoriteAlbums, 4.0)
        addAlbums(signals.frequentAlbums, 1.5)
        addAlbums(signals.recentAlbums, 1.0)
        addAlbums(signals.explorationAlbums, 0.0, exploration = true)
        return candidates.values.toList()
    }
}

private data class Candidate(
    val key: String,
    val artist: Artist?,
    val album: Album?,
    val score: Double,
    val exploration: Boolean,
    val decade: Int?,
    val tieBreaker: Double,
) {
    fun matches(station: GeneratedRadioStation): Boolean = if (album != null) {
        station.kind == GeneratedRadioKind.Album && station.albums.singleOrNull()?.id == album.id.value
    } else {
        station.kind == GeneratedRadioKind.Artist && station.artists.singleOrNull()?.id == artist!!.id.value
    }
}

const val HomeMixRecommendationLimit = 8
const val HomeMixHistoryLimit = 500
private const val HomeMixRecentSeedLimit = 64
private const val HomeMixActivityDecayMillis = 14.0 * 24 * 60 * 60 * 1000
