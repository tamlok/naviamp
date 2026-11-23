package app.naviamp.domain.sonicautoplay

import app.naviamp.domain.provider.isDiscoveryPreview

import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.eraReleaseYear
import app.naviamp.domain.radio.RadioCandidate
import app.naviamp.domain.radio.RadioTuningSettings
import app.naviamp.domain.radio.selectRadioCandidates
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.domain.radio.SimilarityRequest
import app.naviamp.domain.radio.SimilarityService
import app.naviamp.domain.radio.SimilarityEndpoint
import app.naviamp.domain.radio.SimilarityEndpointResult
import app.naviamp.domain.radio.SimilarityResult
import app.naviamp.domain.radio.SimilarityResultKind

class SonicAutoplayService(
    private val provider: () -> MediaProvider?,
    private val seedCount: Int = DefaultSonicAutoplaySeedCount,
    private val candidatesPerSeed: Int = DefaultSonicAutoplayCandidatesPerSeed,
) {
    suspend fun continuationTracks(
        queue: PlaybackQueue,
        limit: Int = DefaultSonicAutoplayAppendCount,
        preferSonicSimilarity: Boolean = true,
        onResult: (app.naviamp.domain.radio.SimilarityResult) -> Unit = {},
        tuning: RadioTuningSettings = RadioTuningSettings(),
        excludedTrackIds: Set<TrackId> = emptySet(),
    ): List<Track> {
        val activeProvider = provider() ?: return emptyList()
        val seeds = queue.sonicAutoplaySeeds(seedCount)
        if (seeds.isEmpty()) return emptyList()

        if (preferSonicSimilarity && !activeProvider.capabilities.supportsSonicSimilarity) {
            onResult(SimilarityResult(SimilarityEndpoint.Sonic, emptyList(),
                failure = SimilarityEndpointResult(SimilarityResultKind.Unsupported)))
            return emptyList()
        }
        val blockedTrackIds = queue.tracks.mapTo(mutableSetOf()) { it.id }.apply { addAll(excludedTrackIds) }
        val targetCount = limit.coerceIn(1, MaxSonicAutoplayAppendCount)
        val result = SimilarityService(activeProvider).load(SimilarityRequest(
            seeds.map { it.id }, excludedTrackIds = blockedTrackIds,
            countPerSeed = maxOf(candidatesPerSeed, targetCount * 2), preferSonic = preferSonicSimilarity,
        ))
        onResult(result)
        return selectRadioCandidates(
            candidates = result.seedMatches.flatMapIndexed { index, seed ->
                seed.matches.map { RadioCandidate(it.track, index, it.similarity ?: 0.5) }
            },
            targetCount = targetCount,
            tuning = tuning,
            excludedTrackIds = blockedTrackIds,
            previousTrack = queue.tracks.lastOrNull(),
            anchorArtistId = seeds.first().artistId,
            decadeStart = seeds.first().eraReleaseYear?.let { (it / 10) * 10 },
        )
    }
}

fun PlaybackQueue.sonicAutoplaySeeds(limit: Int = DefaultSonicAutoplaySeedCount): List<Track> {
    val current = current?.takeUnless { it.id.isDiscoveryPreview() } ?: return emptyList()
    return (listOf(current) + backTo()).filterNot { it.id.isDiscoveryPreview() }
        .distinctBy { track -> track.id }
        .take(limit.coerceAtLeast(1))
}

const val DefaultSonicAutoplaySeedCount = 3
const val DefaultSonicAutoplayCandidatesPerSeed = 12
const val DefaultSonicAutoplayAppendCount = app.naviamp.domain.radio.DefaultRadioMinimumTracks
const val MaxSonicAutoplayAppendCount = app.naviamp.domain.radio.MaxRadioMinimumTracks
