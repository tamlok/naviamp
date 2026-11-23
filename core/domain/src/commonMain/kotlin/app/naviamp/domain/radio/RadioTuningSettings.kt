package app.naviamp.domain.radio

import app.naviamp.domain.Track
import app.naviamp.domain.eraReleaseYear
import kotlinx.serialization.Serializable

@Serializable
data class RadioTuningSettings(
    val familiarity: RadioFamiliarity = RadioFamiliarity.Balanced,
    val artistSpread: RadioArtistSpread = RadioArtistSpread.Balanced,
    val sameDecadeOnly: Boolean = false,
    val artistRunMode: RadioArtistRunMode = RadioArtistRunMode.Mixed,
    val sameArtistRunLength: Int = 2,
    val otherArtistRunLength: Int = 3,
) {
    fun normalized(): RadioTuningSettings =
        copy(
            familiarity = familiarity,
            artistSpread = artistSpread,
            sameArtistRunLength = sameArtistRunLength.coerceIn(MinArtistRunLength, MaxArtistRunLength),
            otherArtistRunLength = otherArtistRunLength.coerceIn(MinArtistRunLength, MaxArtistRunLength),
        )
}

@Serializable
data class RadioDjPreset(
    val id: String,
    val name: String,
    val tuning: RadioTuningSettings = RadioTuningSettings(),
) {
    fun normalized(): RadioDjPreset =
        copy(
            name = name.trim().ifBlank { "Untitled DJ" },
            tuning = tuning.normalized(),
        )
}

interface RadioDjPresetRepository {
    fun radioDjPresets(): List<RadioDjPreset>
    fun replaceRadioDjPresets(presets: List<RadioDjPreset>)
    fun upsertRadioDjPreset(preset: RadioDjPreset)
    fun deleteRadioDjPreset(id: String)
}

@Serializable
enum class RadioFamiliarity(val label: String) {
    Balanced("Balanced"),
    Favorites("Favorites"),
    DeepCuts("Deep cuts"),
}

@Serializable
enum class RadioArtistSpread(val label: String) {
    Narrow("Narrow"),
    Balanced("Balanced"),
    Broad("Broad"),
}

@Serializable
enum class RadioArtistRunMode(val label: String) {
    Mixed("Mixed"),
    SingleArtist("Single artist"),
    ArtistBlocks("Artist blocks"),
}

fun tunedRadioTracks(
    seedTrack: Track?,
    tracks: List<Track>,
    tuning: RadioTuningSettings = RadioTuningSettings(),
    targetCount: Int = tracks.size,
): List<Track> = selectRadioCandidates(
    candidates = tracks.map { RadioCandidate(it) },
    targetCount = targetCount,
    tuning = tuning,
    anchorArtistId = seedTrack?.artistId,
    decadeStart = seedTrack?.eraReleaseYear?.let { (it / 10) * 10 },
)

const val MinArtistRunLength = 1
const val MaxArtistRunLength = 8
