package app.naviamp.domain.sonicmix

import app.naviamp.domain.Track
import app.naviamp.domain.eraReleaseYear
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.SonicSimilarTrack
import app.naviamp.domain.TrackId
import app.naviamp.domain.radio.RadioCandidate
import app.naviamp.domain.radio.RadioTuningSettings
import app.naviamp.domain.radio.SimilarityRequest
import app.naviamp.domain.radio.SimilarityService
import app.naviamp.domain.radio.selectRadioCandidates
import kotlin.math.exp
import kotlin.time.Clock
import kotlin.time.Instant

const val SonicMixDefaultTargetLength = 50
const val SonicMixMinTargetLength = 5
const val SonicMixMaxTargetLength = 100
const val SonicMixMinSeeds = 2
const val SonicMixMaxSeeds = 8

enum class SonicMixBias {
    Balanced,
    Favorites,
    Unplayed,
    Recent,
}

data class SonicMixRequest(
    val seedTracks: List<Track>,
    val targetLength: Int = SonicMixDefaultTargetLength,
    val bias: SonicMixBias = SonicMixBias.Balanced,
    val includeSeeds: Boolean = false,
) {
    val normalizedSeeds: List<Track>
        get() = seedTracks.distinctBy { track -> track.id }.take(SonicMixMaxSeeds)

    val normalizedTargetLength: Int
        get() = targetLength.coerceIn(SonicMixMinTargetLength, SonicMixMaxTargetLength)
}

class SonicMixService(
    private val provider: MediaProvider,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun buildMix(request: SonicMixRequest): List<Track> {
        if (!provider.capabilities.supportsSonicSimilarity) return emptyList()
        val seeds = request.normalizedSeeds
        if (seeds.size < SonicMixMinSeeds) return emptyList()
        val perSeedCount = (request.normalizedTargetLength * 2)
            .coerceAtLeast(20)
            .coerceAtMost(SonicMixMaxTargetLength)
        val result = SimilarityService(provider).load(SimilarityRequest(
            seeds = seeds.map { it.id }, countPerSeed = perSeedCount, preferSonic = true,
        ))
        val matchesById = result.getOrThrow().associateBy { it.seedId }
        val matchesBySeed = seeds.map { seed -> seed to matchesById[seed.id]?.matches.orEmpty() }
        val seedPrefix = if (request.includeSeeds) seeds else emptyList()
        val requestedMatches = (request.normalizedTargetLength - seedPrefix.size).coerceAtLeast(0)
        val matches = blendSonicMix(
            seeds = seeds,
            matchesBySeed = matchesBySeed,
            targetLength = requestedMatches,
            bias = request.bias,
            nowEpochMillis = nowEpochMillis(),
            previousTrack = seedPrefix.lastOrNull(),
        )
        return (seedPrefix + matches)
            .distinctBy { track -> track.id }
            .take(request.normalizedTargetLength)
    }
}

fun blendSonicMix(
    seeds: List<Track>,
    matchesBySeed: List<Pair<Track, List<SonicSimilarTrack>>>,
    targetLength: Int,
    bias: SonicMixBias = SonicMixBias.Balanced,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    tuning: RadioTuningSettings = RadioTuningSettings(),
    excludedTrackIds: Set<TrackId> = emptySet(),
    previousTrack: Track? = null,
): List<Track> = selectRadioCandidates(
    candidates = matchesBySeed.flatMapIndexed { index, (_, matches) ->
        matches.map { RadioCandidate(it.track, index, sonicMixCandidateScore(it, bias, nowEpochMillis)) }
    },
    targetCount = targetLength,
    tuning = tuning,
    excludedTrackIds = excludedTrackIds + seeds.map { it.id },
    previousTrack = previousTrack,
    anchorArtistId = seeds.firstOrNull()?.artistId,
    decadeStart = seeds.firstOrNull()?.eraReleaseYear?.let { (it / 10) * 10 },
)

internal fun sonicMixCandidateScore(match: SonicSimilarTrack, bias: SonicMixBias, nowEpochMillis: Long): Double {
    val similarity = match.similarity?.takeIf(Double::isFinite) ?: 0.5
    val track = match.track
    val biasScore = when (bias) {
        SonicMixBias.Balanced -> 0.0
        SonicMixBias.Favorites -> if (track.favoritedAtIso8601 != null) 0.25 else 0.0
        SonicMixBias.Unplayed -> if ((track.playCount ?: 0) <= 0) 0.25 else 0.0
        SonicMixBias.Recent -> {
            val played = track.lastPlayedAtIso8601?.let { value ->
                runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
            }
            if (played == null || played < 0 || nowEpochMillis <= 0) 0.0
            else {
                // Future timestamps are treated as now, never as a larger-than-current bonus.
                val ageDays = (nowEpochMillis - played.coerceAtMost(nowEpochMillis)).toDouble() / 86_400_000.0
                0.25 * exp(-ageDays / 30.0)
            }
        }
    }
    return similarity + biasScore
}
