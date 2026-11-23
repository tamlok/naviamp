package app.naviamp.domain.sonichome

import app.naviamp.domain.Track
import app.naviamp.domain.provider.MediaProvider
import app.naviamp.domain.provider.SonicSimilarTrack
import app.naviamp.domain.sonicmix.SonicMixBias
import app.naviamp.domain.sonicmix.blendSonicMix
import kotlinx.coroutines.CancellationException

const val SonicHomeDiscoveryRowLimit = 12

enum class SonicHomeDiscoveryRowId(val value: String) {
    MoreLikeRecentPlays("more-like-recent-plays"),
    SonicDeepCuts("sonic-deep-cuts"),
    SimilarToStarredTracks("similar-to-starred-tracks"),
}

data class SonicHomeDiscoveryRow(
    val id: SonicHomeDiscoveryRowId,
    val title: String,
    val tracks: List<Track>,
)

data class SonicHomeDiscoveryRows(
    val rows: List<SonicHomeDiscoveryRow> = emptyList(),
    val failedRowIds: Set<SonicHomeDiscoveryRowId> = emptySet(),
) {
    fun row(id: SonicHomeDiscoveryRowId): SonicHomeDiscoveryRow? =
        rows.firstOrNull { row -> row.id == id }

    fun retainFailedRows(previous: SonicHomeDiscoveryRows): SonicHomeDiscoveryRows {
        if (failedRowIds.isEmpty()) return this
        val retained = rows.map { incoming ->
            if (incoming.id in failedRowIds && incoming.tracks.isEmpty()) previous.row(incoming.id) ?: incoming
            else incoming
        } + previous.rows.filter { cached -> cached.id in failedRowIds && rows.none { it.id == cached.id } }
        return copy(rows = retained)
    }
}

class SonicHomeDiscoveryService(
    private val provider: MediaProvider,
) {
    suspend fun loadRows(
        libraryTracks: List<Track>,
        recentTracks: List<Track> = emptyList(),
        starredTracks: List<Track> = emptyList(),
    ): SonicHomeDiscoveryRows {
        if (!provider.capabilities.supportsSonicSimilarity) return SonicHomeDiscoveryRows()
        val candidates = libraryTracks.distinctBy { track -> track.id }
        val explicitRecentSeeds = recentTracks.distinctBy { track -> track.id }
        val explicitStarredSeeds = starredTracks.distinctBy { track -> track.id }
        if (candidates.isEmpty() && explicitRecentSeeds.isEmpty() && explicitStarredSeeds.isEmpty()) {
            return SonicHomeDiscoveryRows()
        }

        val recentSeeds = explicitRecentSeeds
            .take(SonicHomeDiscoverySeedLimit)
            .ifEmpty {
                candidates
                    .filter { track -> track.lastPlayedAtIso8601 != null }
                    .sortedByDescending { track -> track.lastPlayedAtIso8601 }
                    .take(SonicHomeDiscoverySeedLimit)
            }
        val starredSeeds = explicitStarredSeeds
            .sortedByDescending { track -> track.favoritedAtIso8601 }
            .take(SonicHomeDiscoverySeedLimit)
            .ifEmpty {
                candidates
                    .filter { track -> track.favoritedAtIso8601 != null }
                    .sortedByDescending { track -> track.favoritedAtIso8601 }
                    .take(SonicHomeDiscoverySeedLimit)
            }
        val deepCutSeeds = (recentSeeds + starredSeeds)
            .distinctBy { track -> track.id }
            .ifEmpty {
                candidates
                    .sortedWith(compareBy<Track> { track -> track.playCount ?: Int.MAX_VALUE }
                        .thenBy { track -> track.artistName }
                        .thenBy { track -> track.title })
                    .take(SonicHomeDiscoverySeedLimit)
            }

        val failedRows = mutableSetOf<SonicHomeDiscoveryRowId>()
        val rows = listOfNotNull(
            buildRow(
                id = SonicHomeDiscoveryRowId.MoreLikeRecentPlays,
                title = "More Like Recent Plays",
                seeds = recentSeeds,
                bias = SonicMixBias.Recent,
                failedRows = failedRows,
            ),
            buildRow(
                id = SonicHomeDiscoveryRowId.SonicDeepCuts,
                title = "Sonic Deep Cuts",
                seeds = deepCutSeeds,
                bias = SonicMixBias.Unplayed,
                failedRows = failedRows,
                postProcess = { tracks -> tracks.deepCutSorted() },
            ),
            buildRow(
                id = SonicHomeDiscoveryRowId.SimilarToStarredTracks,
                title = "Similar To Starred Tracks",
                seeds = starredSeeds,
                bias = SonicMixBias.Favorites,
                failedRows = failedRows,
            ),
        )
        return SonicHomeDiscoveryRows(rows, failedRows.toSet())
    }

    private suspend fun buildRow(
        id: SonicHomeDiscoveryRowId,
        title: String,
        seeds: List<Track>,
        bias: SonicMixBias,
        failedRows: MutableSet<SonicHomeDiscoveryRowId>,
        postProcess: (List<Track>) -> List<Track> = { tracks -> tracks },
    ): SonicHomeDiscoveryRow? {
        if (seeds.isEmpty()) return null
        val matchesBySeed = seeds.map { seed ->
            val matches = try {
                provider.sonicSimilarTrackMatches(seed.id, count = SonicHomeDiscoveryMatchCount)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedRows += id
                emptyList()
            }
            seed to matches
        }
        val tracks = postProcess(
            blendSonicMix(
                seeds = seeds,
                matchesBySeed = matchesBySeed,
                targetLength = SonicHomeDiscoveryRowLimit * 2,
                bias = bias,
            ),
        ).take(SonicHomeDiscoveryRowLimit)
        return tracks.takeIf { it.isNotEmpty() }?.let { SonicHomeDiscoveryRow(id, title, it) }
    }
}

private fun List<Track>.deepCutSorted(): List<Track> =
    sortedWith(compareBy<Track> { track -> track.playCount ?: 0 }
        .thenBy { track -> track.favoritedAtIso8601 != null }
        .thenByDescending { track -> track.lastPlayedAtIso8601 }
        .thenBy { track -> track.artistName }
        .thenBy { track -> track.title })

private const val SonicHomeDiscoverySeedLimit = 4
private const val SonicHomeDiscoveryMatchCount = 35
