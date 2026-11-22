package app.naviamp.ui

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class NaviampPlainLyricsScrollTest {
    @Test fun finitePlaybackMapsTopMiddleAndSongEnd() {
        for ((position, expected) in listOf(-1.0 to 0f, 0.0 to 0f, 50.0 to 500f, 100.0 to 1000f, 110.0 to 1000f)) {
            val clock = Clock()
            clock.update(position = position, playing = false)
            assertEquals(expected, clock.scroll.offset(), .001f)
            assertNull(clock.scroll.motion)
        }
        val clock = Clock()
        clock.update()
        assertEquals(0f, clock.scroll.offset())
        clock.advance(50_000)
        assertEquals(500f, clock.scroll.offset(), .001f)
        clock.advance(60_000)
        assertEquals(1000f, clock.scroll.offset())
        assertFalse(requireNotNull(clock.scroll.motion).repeat)
    }

    @Test fun noisyAndRepeatedTelemetryNeverRestartsOrReversesMotion() {
        val clock = Clock()
        clock.update()
        val motion = clock.scroll.motion
        val started = clock.scroll.translationStarted
        val revision = clock.scroll.revision
        for (second in 1..50) {
            clock.advance(1000)
            clock.update(position = second - .25)
            assertEquals(second * 10f, clock.scroll.offset(), .001f)
            clock.update(position = second - .5)
            assertSame(motion, clock.scroll.motion)
            assertSame(started, clock.scroll.translationStarted)
            assertEquals(revision, clock.scroll.revision)
        }
    }

    @Test fun pauseFreezesPredictedPixelsAndResumeContinuesWithoutJumping() {
        val clock = Clock()
        clock.update()
        clock.advance(20_000)
        clock.update(position = 19.8, playing = false)
        assertEquals(200f, clock.scroll.offset(), .001f)
        assertNull(clock.scroll.motion)
        clock.advance(30_000)
        clock.update(position = 19.9, playing = false)
        assertEquals(200f, clock.scroll.offset(), .001f)
        clock.update(position = 20.0)
        assertEquals(200f, clock.scroll.offset(), .001f)
        clock.advance(40_000)
        assertEquals(600f, clock.scroll.offset(), .001f)
    }

    @Test fun samePositionAndSmallSeeksReplaceTheClockEvenWhilePaused() {
        val clock = Clock()
        clock.update(position = 10.0)
        val motion = clock.scroll.motion
        clock.advance(500)
        clock.update(position = 10.0, seek = 1)
        assertEquals(100f, clock.scroll.offset(), .001f)
        assertNotSame(motion, clock.scroll.motion)
        clock.update(position = 10.1, seek = 2)
        assertEquals(101f, clock.scroll.offset(), .001f)
        clock.update(position = 10.1, playing = false)
        clock.scroll.beginManual()
        clock.scroll.scrollBy(200f)
        clock.scroll.endManual()
        clock.update(position = 10.1, seek = 3, playing = false)
        assertEquals(101f, clock.scroll.offset(), .001f)
    }

    @Test fun identityDurationAndGeometryChangesResetTheManualBaseline() {
        val clock = Clock()
        clock.update(position = 25.0, playing = false)
        clock.scroll.beginManual()
        clock.scroll.scrollBy(500f)
        clock.scroll.endManual()
        clock.update(position = 25.0, playing = false, identity = "new track/source/text/font/viewport")
        assertEquals(250f, clock.scroll.offset(), .001f)
        clock.update(position = 25.0, duration = 200.0, playing = false, identity = "new track/source/text/font/viewport")
        assertEquals(125f, clock.scroll.offset(), .001f)
        clock.update(position = 25.0, duration = 200.0, maximum = 2000f, playing = false, identity = "new track/source/text/font/viewport")
        assertEquals(250f, clock.scroll.offset(), .001f)
    }

    @Test fun playingGeometryResetsUsePredictionButTrackChangesUseTheNewSample() {
        val clock = Clock()
        clock.update(position = 10.0)
        clock.advance(20_000)
        clock.update(position = 10.0, maximum = 2000f)
        assertEquals(600f, clock.scroll.offset(), .001f)
        assertEquals(70_000L, requireNotNull(clock.scroll.motion).durationMillis)
        clock.update(position = 10.0, maximum = 2000f, duration = 200.0)
        assertEquals(300f, clock.scroll.offset(), .001f)
        clock.update(position = 0.0, identity = "different track")
        assertEquals(0f, clock.scroll.offset())
    }

    @Test fun unknownInvalidDurationsAndPositionsRemainManualOnly() {
        for (duration in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val clock = Clock()
            clock.update(position = 20.0, duration = duration)
            assertNull(clock.scroll.motion)
            clock.scroll.beginManual()
            assertEquals(150f, clock.scroll.scrollBy(150f))
            clock.scroll.endManual()
            clock.advance(30_000)
            clock.update(position = 50.0, duration = duration)
            assertEquals(150f, clock.scroll.offset())
            assertNull(clock.scroll.motion)
        }
        for (position in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val clock = Clock()
            clock.update(position = position)
            assertNull(clock.scroll.motion)
            clock.scroll.beginManual()
            clock.scroll.scrollBy(150f)
            clock.scroll.endManual()
            clock.advance(10_000)
            assertEquals(150f, clock.scroll.offset())
            clock.update(position = 50.0)
            assertEquals(500f, clock.scroll.offset(), .001f)
        }
    }

    @Test fun fittingContentNeverMovesAndNonfiniteGeometryIsSafe() {
        for (maximum in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val clock = Clock()
            clock.update(maximum = maximum)
            clock.advance(10_000)
            clock.scroll.beginManual()
            assertEquals(0f, clock.scroll.scrollBy(200f))
            clock.scroll.endManual()
            assertEquals(0f, clock.scroll.offset())
            assertNull(clock.scroll.motion)
            assertNull(clock.scroll.nextTileBoundaryDelay(100))
        }
    }

    @Test fun releasingManualScrollRebasesToBottomOverRemainingPlayback() {
        val clock = Clock()
        clock.update(position = 10.0)
        clock.advance(10_000)
        clock.scroll.beginManual()
        assertNull(clock.scroll.motion)
        assertEquals(300f, clock.scroll.scrollBy(300f), .001f)
        clock.advance(10_000)
        clock.update(position = 30.0)
        assertEquals(500f, clock.scroll.offset(), .001f)
        clock.scroll.endManual()
        assertEquals(500f, clock.scroll.offset(), .001f)
        assertEquals(70_000L, requireNotNull(clock.scroll.motion).durationMillis)
        clock.advance(35_000)
        clock.update(position = 64.8)
        assertEquals(750f, clock.scroll.offset(), .001f)
        clock.advance(35_000)
        assertEquals(1000f, clock.scroll.offset())
    }

    @Test fun pausedManualPositionSurvivesSamplesAndResumesOrSeeksExplicitly() {
        val clock = Clock()
        clock.update(position = 20.0, playing = false)
        clock.scroll.beginManual()
        assertEquals(400f, clock.scroll.scrollBy(400f))
        clock.scroll.endManual()
        clock.advance(30_000)
        clock.update(position = 20.1, playing = false)
        assertEquals(600f, clock.scroll.offset(), .001f)
        clock.update(position = 20.0)
        assertEquals(600f, clock.scroll.offset(), .001f)
        clock.advance(40_000)
        assertEquals(800f, clock.scroll.offset(), .001f)
        clock.update(position = 20.0, seek = 1)
        assertEquals(200f, clock.scroll.offset(), .001f)
    }

    @Test fun manualOffsetsClampAndReleaseAtSongEndFinishesAtBottom() {
        val clock = Clock()
        clock.update(position = 99.0)
        clock.scroll.beginManual()
        assertEquals(-990f, clock.scroll.scrollBy(-10_000f), .001f)
        assertEquals(0f, clock.scroll.scrollBy(Float.NaN))
        clock.advance(2000)
        clock.scroll.endManual()
        assertEquals(1000f, clock.scroll.offset())
        assertNull(clock.scroll.motion)
    }

    @Test fun onlyTileBoundaryDeadlinesAreScheduled() {
        val clock = Clock()
        clock.update(duration = 8.192, maximum = 8192f)
        assertEquals(2048L, clock.scroll.nextTileBoundaryDelay(400))
        clock.advance(2048)
        assertEquals(1024L, clock.scroll.nextTileBoundaryDelay(400))
        clock.scroll.beginManual()
        assertNull(clock.scroll.nextTileBoundaryDelay(400))
        clock.scroll.endManual()
        clock.advance(6144)
        assertNull(clock.scroll.nextTileBoundaryDelay(400))
    }

    @Test fun tileRangesAndCacheStayBoundedAndRetainExistingImages() {
        assertEquals(IntRange.EMPTY, plainLyricsTileRange(0f, 0, 10_000))
        assertEquals(0..0, plainLyricsTileRange(0f, 400, 100))
        var allocations = 0
        val cache = NaviampPlainLyricsTileCache { _: Int -> allocations++; Any() }
        cache.retain(0..2)
        val retained = cache[2]
        cache.retain(1..3)
        assertSame(retained, cache[2])
        assertEquals(4, allocations)
        assertEquals(3, cache.size)
        for (viewport in listOf(400, 1024, 2500)) {
            val capacity = plainLyricsTileRange(0f, viewport, 100_000).count()
            for (offset in 0..100_000 step 257) {
                val range = plainLyricsTileRange(offset.toFloat(), viewport, 100_000)
                cache.retain(range)
                assertTrue(cache.size <= ceil(viewport / 1024.0).toInt() + 3)
                assertEquals(capacity, range.count(), "keep native slot count stable while scrolling")
                assertTrue(range.first >= 0 && range.last < ceil(100_000 / 1024.0).toInt())
            }
        }
        cache.retain(IntRange.EMPTY)
        assertEquals(0, cache.size)
    }

    private class Clock {
        private var millis = 0L
        private val timeSource = TestTimeSource()
        val scroll = NaviampPlainLyricsScroll({ millis }, timeSource::markNow)
        fun advance(delta: Long) { millis += delta; timeSource += delta.milliseconds }
        fun update(
            position: Double? = 0.0, duration: Double? = 100.0, playing: Boolean = true,
            seek: Long = 0L, maximum: Float = 1000f, identity: Any? = "track/source/text/font/viewport",
        ) = scroll.update(identity, position, duration, playing, seek, maximum)
    }
}
