package app.naviamp.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Playback and manual-scroll policy, independent of composition and the presentation backend. */
internal class NaviampPlainLyricsScroll(
    private val nowMillis: () -> Long,
    private val markNow: () -> TimeMark = { TimeSource.Monotonic.markNow() },
) {
    var maxOffset = 0f
        private set
    var motion: NaviampLayerMotion? = null
        private set
    var translationStarted: TimeMark? = null
        private set
    var revision = 0L
        private set
    var isManual = false
        private set

    private var initialized = false
    private var identity: Any? = null
    private var durationMillis: Double? = null
    private var positionMillis: Double? = null
    private var playbackStarted = 0L
    private var motionStarted = 0L
    private var staticOffset = 0f
    private var playing = false
    private var seekGeneration = 0L
    private var geometry: Any? = null

    fun offset(): Float = motion?.let { -it.valueAt(elapsed(motionStarted)) } ?: staticOffset

    fun update(
        identity: Any?, positionSeconds: Double?, durationSeconds: Double?, playing: Boolean,
        seekGeneration: Long, maxOffset: Float, geometry: Any? = null,
    ) {
        val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 1000.0).coerceAtMost(Long.MAX_VALUE.toDouble()) }
        val position = positionSeconds?.takeIf { it.isFinite() }
            ?.let { (it.coerceAtLeast(0.0) * 1000.0).coerceAtMost(duration ?: Long.MAX_VALUE.toDouble()) }
        val maximum = maxOffset.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val sameIdentity = initialized && this.identity == identity
        val reset = !sameIdentity || this.geometry != geometry || this.durationMillis != duration || this.maxOffset != maximum
        val seek = initialized && this.seekGeneration != seekGeneration
        val validityChanged = (positionMillis != null) != (position != null)
        val playbackChanged = this.playing != playing
        val previousOffset = offset()
        // Re-evaluate geometry and pause/resume at the predicted song position, not at
        // stale telemetry. A seek or a different track always establishes a new anchor.
        val targetPosition = if (sameIdentity && !seek && position != null &&
            (playbackChanged || (reset && this.playing && playing))) {
            positionMillis?.let {
                (it + if (this.playing) elapsed(playbackStarted) else 0L).coerceAtMost(duration ?: Long.MAX_VALUE.toDouble())
            } ?: position
        } else position
        this.identity = identity
        this.geometry = geometry
        this.seekGeneration = seekGeneration
        this.maxOffset = maximum
        this.durationMillis = duration
        initialized = true

        if (reset || seek || validityChanged || playbackChanged) {
            this.positionMillis = targetPosition
            playbackStarted = nowMillis()
            this.playing = playing
            val target = when {
                reset || seek || (validityChanged && position != null) -> proportionalOffset(targetPosition)
                else -> previousOffset.coerceIn(0f, maximum)
            }
            // A seek or geometry change resets the manual baseline, but a held gesture still owns input.
            setOffset(target)
            return
        }
        this.playing = playing
        // Ordinary telemetry must not restart or pull back a compositor timeline. Only the
        // explicit end sample can finish it early; seeks are identified even at the same value.
        if (!isManual && playing && position != null && duration != null && position >= duration && offset() < maximum) {
            this.positionMillis = duration
            playbackStarted = nowMillis()
            setOffset(maximum)
        }
    }

    fun beginManual() {
        if (isManual) return
        val current = offset()
        isManual = true
        setOffset(current)
    }

    fun scrollBy(delta: Float): Float {
        if (!delta.isFinite()) return 0f
        val previous = offset()
        val next = (previous + delta).coerceIn(0f, maxOffset)
        if (next != previous) setOffset(next)
        return next - previous
    }

    fun endManual() {
        if (!isManual) return
        isManual = false
        setOffset(staticOffset)
    }

    /** A single wake-up when the visible-plus-adjacent tile set next changes, never a frame timer. */
    fun nextTileBoundaryDelay(viewportHeight: Int, tileHeight: Int = PlainLyricsTileHeight): Long? {
        val motion = motion ?: return null
        if (viewportHeight <= 0) return null
        val current = offset().toDouble()
        if (current >= maxOffset) return null
        val contentHeight = (maxOffset + viewportHeight).toInt()
        val range = plainLyricsTileRange(current.toFloat(), viewportHeight, contentHeight, tileHeight)
        if (range.isEmpty() || range.last == (contentHeight - 1) / tileHeight) return null
        val next = (range.first.toLong() + 2) * tileHeight
        val speed = (motion.values.first() - motion.values.last()).toDouble() / motion.durationMillis
        if (speed <= 0.0) return null
        return ceil((next - current) / speed).toLong().coerceAtLeast(1L)
    }

    private fun proportionalOffset(position: Double?): Float =
        if (position == null || durationMillis == null) 0f
        else (position / requireNotNull(durationMillis)).coerceIn(0.0, 1.0).toFloat() * maxOffset

    private fun elapsed(started: Long): Long = (nowMillis() - started).coerceAtLeast(0L)

    private fun setOffset(offset: Float) {
        staticOffset = offset.coerceIn(0f, maxOffset)
        val duration = durationMillis
        val position = positionMillis
        val remaining = if (duration != null && position != null) {
            (duration - position - if (playing) elapsed(playbackStarted) else 0L).coerceAtLeast(0.0)
        } else 0.0
        if (!isManual && playing && duration != null && position != null && remaining == 0.0) staticOffset = maxOffset
        motion = if (!isManual && playing && staticOffset < maxOffset && remaining > 0.0) {
            NaviampLayerMotion(listOf(-staticOffset, -maxOffset), listOf(0L, ceil(remaining).toLong().coerceAtLeast(1L)))
        } else null
        motionStarted = nowMillis()
        translationStarted = if (motion != null) markNow() else null
        revision++
    }
}

internal const val PlainLyricsTileHeight = 1024

/** A fixed-capacity window retains native slots, with adjacent tiles ready before they appear. */
internal fun plainLyricsTileRange(
    offset: Float, viewportHeight: Int, contentHeight: Int, tileHeight: Int = PlainLyricsTileHeight,
): IntRange {
    require(tileHeight > 0)
    if (viewportHeight <= 0 || contentHeight <= 0) return IntRange.EMPTY
    val bounded = offset.takeIf { it.isFinite() }?.coerceIn(0f, (contentHeight - viewportHeight).coerceAtLeast(0).toFloat()) ?: 0f
    val tileCount = (contentHeight - 1) / tileHeight + 1
    val capacity = minOf(tileCount.toLong(), (viewportHeight.toLong() + tileHeight - 1) / tileHeight + 3).toInt()
    val first = ((bounded / tileHeight).toInt() - 1).coerceIn(0, tileCount - capacity)
    return first until first + capacity
}

/** A bounded working set, not a song-sized bitmap cache. Retained tiles preserve image identity. */
internal class NaviampPlainLyricsTileCache<T>(private val create: (Int) -> T) {
    private val tiles = mutableMapOf<Int, T>()
    val size: Int get() = tiles.size

    fun retain(range: IntRange) {
        val iterator = tiles.keys.iterator()
        while (iterator.hasNext()) if (iterator.next() !in range) iterator.remove()
        for (index in range) if (index !in tiles) tiles[index] = create(index)
    }

    operator fun get(index: Int): T = tiles.getValue(index)
}
