package app.naviamp.domain.queue

import app.naviamp.domain.Track
import kotlin.random.Random

/** Remembers only the last shuffle, across on/off toggles, for this queue owner. */
class UpcomingShufflePolicy(private val random: Random = Random.Default) {
    private var previousOrder: List<Track>? = null

    fun selectOrder(tracks: List<Track>): List<Track> {
        selectIndexes(tracks)
        return requireNotNull(previousOrder)
    }

    /** Returns the actual permutation, so equal track values never obscure queue occurrences. */
    fun selectIndexes(tracks: List<Track>): List<Int> {
        val indexes = tracks.indices.toList()
        fun acceptable(candidate: List<Int>): Boolean =
            candidate.indices.any { tracks[candidate[it]] != tracks[it] } &&
                (previousOrder?.let { previous ->
                    previous.size != candidate.size || candidate.indices.any { tracks[candidate[it]] != previous[it] }
                } != false)
        fun remember(candidate: List<Int>): List<Int> = candidate.also { previousOrder = it.map(tracks::get) }

        repeat(8) {
            val candidate = indexes.shuffled(random)
            if (acceptable(candidate)) return remember(candidate)
        }

        // Bound the work for unlucky random sources and queues with indistinguishable tracks.
        val different = tracks.indexOfFirst { it != tracks.firstOrNull() }
        if (different < 0) return remember(indexes)
        var alternative = indexes
        for (pivot in listOf(0, different)) {
            for (index in tracks.indices) {
                if (tracks[index] == tracks[pivot]) continue
                val candidate = indexes.toMutableList().apply {
                    this[pivot] = index
                    this[index] = pivot
                }
                alternative = candidate
                if (acceptable(candidate)) return remember(candidate)
            }
        }
        return remember(alternative)
    }
}
