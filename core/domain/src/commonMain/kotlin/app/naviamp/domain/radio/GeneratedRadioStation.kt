package app.naviamp.domain.radio

import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.settings.SavedAlbum
import app.naviamp.domain.settings.SavedArtist
import app.naviamp.domain.settings.SavedTrack
import kotlinx.serialization.Serializable

/** A recipe ID is local/portable application identity, never a provider media ID. */
@Serializable
data class GeneratedRadioStation(
    val id: String,
    val name: String,
    val kind: GeneratedRadioKind,
    val sourceId: String? = null,
    val sourceIdentity: NaviampConnectSourceIdentity? = null,
    val libraryScope: String = "",
    val revision: Long = 1,
    val pinned: Boolean = false,
    val saved: Boolean = false,
    val updatedAtEpochMillis: Long = 0,
    val seedTracks: List<SavedTrack> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val genres: List<String> = emptyList(),
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val sonicSimilarity: Boolean = false,
    val resolvedArtistId: String? = null,
    val resolvedDecadeStart: Int? = null,
    val excludedAlbumIds: List<String> = emptyList(),
    val tuning: RadioTuningSettings = RadioTuningSettings(),
    val batchSize: Int = DefaultRadioMinimumTracks,
    val sonicBias: String = "Balanced",
    val includeSeeds: Boolean = false,
    val legacyRecentId: String? = null,
    val regenerable: Boolean = true,
) {
    fun normalized(): GeneratedRadioStation = copy(
        name = name.trim(),
        revision = revision.coerceAtLeast(1),
        sourceIdentity = sourceIdentity?.normalized(),
        seedTracks = seedTracks.distinctBy { it.id },
        artists = artists.distinctBy { it.id },
        albums = albums.distinctBy { it.id },
        genres = genres.map(String::trim).filter(String::isNotEmpty).distinct(),
        tuning = tuning.normalized(),
        batchSize = normalizedRadioMinimumTracks(batchSize),
    )

    fun belongsTo(sourceId: String?, identity: NaviampConnectSourceIdentity?, scope: String): Boolean =
        libraryScope == scope && if (sourceIdentity != null) {
            identity != null && sourceIdentity.normalized() == identity.normalized()
        } else this.sourceId != null && this.sourceId == sourceId
}

@Serializable
enum class GeneratedRadioKind { Track, Artist, Album, Artists, Albums, Genres, Decade, Library, RandomAlbum, SonicMix }

@Serializable
data class GeneratedRadioBatch(
    val id: String,
    val stationId: String,
    val stationRevision: Long,
    val generatedAtEpochMillis: Long,
    val tracks: List<SavedTrack>,
    val resolvedRecipe: GeneratedRadioStation? = null,
)

@Serializable
data class GeneratedRadioPreferences(
    val endlessEnabled: Boolean = true,
    val recentTrackExclusionHours: Int = 24,
) {
    fun normalized() = copy(recentTrackExclusionHours = recentTrackExclusionHours.coerceIn(0, 168))
}

@Serializable
data class GeneratedRadioHomeRotation(
    val sourceId: String,
    val libraryScope: String,
    val day: Long,
    val stationIds: List<String>,
    val recentSeedIds: List<String> = emptyList(),
)

/** Portable library state; active jobs, queue cursors and playback positions never belong here. */
@Serializable
data class GeneratedRadioLibrary(
    val preferences: GeneratedRadioPreferences = GeneratedRadioPreferences(),
    val stations: List<GeneratedRadioStation> = emptyList(),
    val batches: List<GeneratedRadioBatch> = emptyList(),
    val rotations: List<GeneratedRadioHomeRotation> = emptyList(),
    val migratedSourceIds: List<String> = emptyList(),
) {
    fun normalized(): GeneratedRadioLibrary {
        val retainedBatches = batches.distinctBy { it.stationId }.sortedByDescending { it.generatedAtEpochMillis }.take(50)
        val retainedRotations = rotations.distinctBy { it.sourceId to it.libraryScope }.take(50)
        val ordered = stations.distinctBy { it.id }.map(GeneratedRadioStation::normalized).sortedByDescending { it.updatedAtEpochMillis }
        val retainedIds = ordered.filterNot { it.saved || it.pinned }.take(100).mapTo(mutableSetOf()) { it.id }
        retainedBatches.mapTo(retainedIds) { it.stationId }
        retainedRotations.forEach { retainedIds.addAll(it.stationIds) }
        return copy(preferences = preferences.normalized(), stations = ordered.filter { it.saved || it.pinned || it.id in retainedIds },
            batches = retainedBatches, rotations = retainedRotations, migratedSourceIds = migratedSourceIds.distinct())
    }
}

interface GeneratedRadioRepository {
    fun load(): GeneratedRadioLibrary
    fun save(library: GeneratedRadioLibrary)
}

/** Useful for ephemeral Core compositions; production supplies the existing durable settings store. */
class MemoryGeneratedRadioRepository(initial: GeneratedRadioLibrary = GeneratedRadioLibrary()) : GeneratedRadioRepository {
    private var library = initial
    override fun load(): GeneratedRadioLibrary = library
    override fun save(library: GeneratedRadioLibrary) { this.library = library.normalized() }
}

@Serializable
data class RadioQueueOccurrence(
    val id: String,
    val stationId: String? = null,
    val batchId: String? = null,
) {
    val generated: Boolean get() = stationId != null
}

@Serializable
enum class GeneratedRadioPlaybackMode { Station, FiniteBatch }

/** Local SQL checkpoint, atomically saved with the queue. No runtime cancellation token is persisted. */
@Serializable
data class GeneratedRadioCheckpoint(
    val station: GeneratedRadioStation? = null,
    val mode: GeneratedRadioPlaybackMode = GeneratedRadioPlaybackMode.Station,
    val wasPaused: Boolean = false,
    val endlessEnabled: Boolean = true,
    val seenTrackIds: List<String> = emptyList(),
    val occurrences: List<RadioQueueOccurrence> = emptyList(),
) {
    fun normalized() = copy(
        station = station?.normalized(),
        seenTrackIds = seenTrackIds.distinct().takeLast(2_000),
    )
}

enum class GeneratedRadioStatus { Inactive, Active, Fetching, Exhausted, Failed, Unsupported, InvalidRecipe }
