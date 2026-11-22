package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.settings.nextCompactLyricsFontSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class NaviampCompactLyricsUiTest {
    @Test
    fun fontCyclesAndControlsStayBelowScrollablePlainLyricsInBothDirections() {
        for (direction in LayoutDirection.entries) runDesktopComposeUiTest(320, 280) {
            var playing by mutableStateOf(fixture().copy(lyricsLines = List(30) {
                NaviampLyricLineUi(null, "Plain lyric $it that can wrap at larger font sizes")
            }))
            var refreshes = 0
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.size(300.dp, 260.dp)) {
                        NaviampCompactLyricsCard(playing, null, NaviampColors.Dark,
                            onCycleFontSize = { playing = playing.copy(compactLyricsFontSizeSp = nextCompactLyricsFontSize(playing.compactLyricsFontSizeSp)) },
                            onRefresh = { refreshes++ })
                    }
                }
            }
            val viewport = onNodeWithTag("compact-lyrics-viewport").fetchSemanticsNode().boundsInRoot
            val card = onNodeWithTag("now-playing-lyrics-card").fetchSemanticsNode().boundsInRoot
            val font = onNodeWithTag("compact-lyrics-font-size")
            val refresh = onNodeWithTag("compact-lyrics-refresh")
            font.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            refresh.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            onAllNodes(hasClickAction()).assertCountEquals(2)
            val controls = listOf(font, refresh).map { it.fetchSemanticsNode().boundsInRoot }
            assertTrue(controls.all { it.top >= viewport.bottom && it.bottom <= card.bottom })
            assertTrue(abs(controls.maxOf { it.right } - (card.right - 16f)) <= 1f, "physical right in $direction")
            val lyrics = onNodeWithTag("compact-lyrics-plain-text")
            val range = lyrics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(0f, range.value())
            assertTrue(range.maxValue() > viewport.height)
            assertEquals(playing.lyricsLines.joinToString("\n") { it.text },
                lyrics.fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
            for (expected in listOf(20, 24, 48, 64, 14)) {
                font.performClick()
                val layouts = mutableListOf<TextLayoutResult>()
                lyrics.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(expected.sp, layouts.single().layoutInput.style.fontSize)
                assertEquals((expected * 1.5f).sp, layouts.single().layoutInput.style.lineHeight)
                assertTrue(layouts.single().size.height > viewport.height)
                val pixels = lyrics.captureToImage().toPixelMap()
                assertTrue((0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x, y].red > .6f && pixels[x, y].alpha > .9f } },
                    "font $expected must render actual glyph pixels")
            }
            val before = lyrics.captureToImage().pixelValues()
            val footerBefore = refresh.captureToImage().pixelValues()
            lyrics.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
            val scrolled = lyrics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(scrolled.maxValue(), scrolled.value())
            assertTrue(!before.contentEquals(lyrics.captureToImage().pixelValues()), "scrolling must change visible glyphs")
            assertContentEquals(footerBefore, refresh.captureToImage().pixelValues())
            val cardPixels = onNodeWithTag("now-playing-lyrics-card").captureToImage().toPixelMap()
            assertEquals(NaviampColors.Dark.playerSurface, cardPixels[24, cardPixels.height - 24], "lyrics must not paint below their viewport")
            lyrics.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            lyrics.performKeyInput { pressKey(Key.MoveHome) }
            assertEquals(0f, lyrics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
            lyrics.performTouchInput { swipeUp() }
            assertTrue(lyrics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            refresh.performClick()
            assertEquals(1, refreshes)
        }
    }

    @Test
    fun loadingAndMissingLyricsKeepBothControlsAndAllowRetryOnlyWhenEligible() = runDesktopComposeUiTest(320, 220) {
        var playing by mutableStateOf(fixture().copy(lyricsLines = emptyList()))
        var refreshes = 0
        setContent {
            NaviampCompactLyricsCard(playing, null, NaviampColors.Dark, {}, { refreshes++ }, Modifier.size(300.dp, 200.dp))
        }
        onNodeWithTag("compact-lyrics-font-size").assertIsDisplayed()
        onNodeWithTag("compact-lyrics-refresh").assertIsEnabled().performClick()
        assertEquals(1, refreshes)
        runOnIdle { playing = playing.copy(lyricsLoading = true) }
        onNodeWithTag("compact-lyrics-refresh").assertIsNotEnabled()
        runOnIdle { playing = playing.copy(lyricsLoading = false, lyricsStatus = "Failed source") }
        onNodeWithTag("compact-lyrics-refresh").assertIsEnabled().performClick()
        assertEquals(2, refreshes)
        runOnIdle { playing = playing.copy(canRefreshLyrics = false) }
        onNodeWithTag("compact-lyrics-refresh").assertIsNotEnabled()
        onNodeWithTag("compact-lyrics-font-size").assertIsEnabled()
        runOnIdle { playing = playing.copy(lyricsAvailable = false) }
        onNodeWithTag("compact-lyrics-font-size").assertIsNotEnabled()
    }

    @Test
    fun pausedSeeksOffsetsAndFontChangesCenterTheCorrectWrappedLyric() = runDesktopComposeUiTest(260, 340) {
        var playing by mutableStateOf(fixture().copy(
            lyricsLines = listOf(
                NaviampLyricLineUi(0, "First lyric"),
                NaviampLyricLineUi(1_000, "The second lyric wraps onto several rows within this narrow card"),
                NaviampLyricLineUi(2_000, "Last lyric"),
            ),
            lyricsOffsetMillis = 500,
        ))
        val progress = MutableStateFlow(PlaybackProgress(1.6, 30.0))
        setContent {
            NaviampCompactLyricsCard(playing, progress, NaviampColors.Dark,
                { playing = playing.copy(compactLyricsFontSizeSp = nextCompactLyricsFontSize(playing.compactLyricsFontSizeSp)) },
                {}, Modifier.size(240.dp, 320.dp))
        }
        fun assertCentered(index: Int) {
            val line = onNodeWithTag("compact-lyrics-line-$index").assertIsSelected().fetchSemanticsNode().boundsInRoot
            val viewport = onNodeWithTag("compact-lyrics-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue(abs(line.center.y - viewport.center.y) <= 1.5f, "line $index must center after layout")
        }
        assertCentered(1)
        onNodeWithTag("compact-lyrics-font-size").performClick()
        assertCentered(1)
        runOnIdle { progress.value = PlaybackProgress(0.7, 30.0, seekGeneration = 1) }
        assertCentered(0)
        runOnIdle { playing = playing.copy(lyricsOffsetMillis = -500) }
        assertCentered(1)
        runOnIdle { progress.value = PlaybackProgress(2.0, 30.0, seekGeneration = 2) }
        assertCentered(2)
    }

    @Test
    fun boundarySchedulingUsesSavedOffsetAndDoesNotPollBetweenLines() = runTest {
        val lines = listOf(NaviampLyricLineUi(1_000, "First"), NaviampLyricLineUi(2_000, "Second"))
        val active = mutableListOf<Int>()
        val following = launch {
            followCompactLyricLines(lines, 0, 500, true, { testScheduler.currentTime }, active::add)
        }
        runCurrent()
        assertEquals(listOf(-1), active)
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(listOf(-1), active)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(-1, 0), active)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(-1, 0, 1), active)
        following.join()
        val paused = mutableListOf<Int>()
        followCompactLyricLines(lines, 600, -500, false, { error("Paused lyrics must not interpolate") }, paused::add)
        assertEquals(listOf(0), paused)
        followCompactLyricLines(lines, 1_600, -500, false, { 0 }, paused::add)
        assertEquals(listOf(0, 1), paused)
    }

    @Test
    fun pausedPlainSeeksAndGeometryChangesExposeRealLayoutAndProportionalScroll() = runDesktopComposeUiTest(340, 320) {
        var playing by mutableStateOf(fixture().copy(compactLyricsFontSizeSp = 64,
            lyricsLines = List(40) { NaviampLyricLineUi(null, "Untimed lyric $it") }))
        val progress = MutableStateFlow(PlaybackProgress(0.0, 100.0))
        var height by mutableStateOf(300.dp)
        setContent {
            NaviampCompactLyricsCard(playing, progress, NaviampColors.Dark, {}, {}, Modifier.size(320.dp, height))
        }
        val lyrics = onNodeWithTag("compact-lyrics-plain-text")
        fun range() = lyrics.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, range().value())
        val topPixels = lyrics.captureToImage().pixelValues()
        runOnIdle { progress.value = PlaybackProgress(50.0, 100.0, seekGeneration = 1) }
        assertEquals(range().maxValue() / 2, range().value(), 1f)
        assertTrue(!topPixels.contentEquals(lyrics.captureToImage().pixelValues()))
        lyrics.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 50f) }
        val chosen = range().value()
        runOnIdle { progress.value = PlaybackProgress(50.1, 100.0, seekGeneration = 1) }
        assertEquals(chosen, range().value())
        runOnIdle { progress.value = PlaybackProgress(50.1, 100.0, seekGeneration = 2) }
        assertEquals(range().maxValue() * .501f, range().value(), 1f)
        runOnIdle { playing = playing.copy(compactLyricsFontSizeSp = 24) }
        assertEquals(range().maxValue() * .501f, range().value(), 1f)
        lyrics.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 50f) }
        runOnIdle { playing = playing.copy(lyricsSourceId = "replacement source") }
        assertEquals(range().maxValue() * .501f, range().value(), 1f)
        val beforeResize = mutableListOf<TextLayoutResult>()
        lyrics.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(beforeResize) }
        runOnIdle { height = 260.dp }
        val afterResize = mutableListOf<TextLayoutResult>()
        lyrics.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(afterResize) }
        assertSame(beforeResize.single(), afterResize.single(), "height-only changes must not remeasure text")
        assertEquals(range().maxValue() * .501f, range().value(), 1f)
        runOnIdle { progress.value = PlaybackProgress(50.1, 200.0, seekGeneration = 2) }
        assertEquals(range().maxValue() * .2505f, range().value(), 1f)
        runOnIdle {
            playing = playing.copy(durationSeconds = 200.0)
            progress.value = PlaybackProgress(50.1, null, seekGeneration = 2)
        }
        assertEquals(range().maxValue() * .2505f, range().value(), 1f, "use known track length when telemetry omits it")
        runOnIdle { playing = playing.copy(isPlaying = true, isPaused = false) }
        runOnIdle {
            playing = playing.copy(lyricsSourceId = "different account with the same track ID", isPlaying = false, isPaused = true)
            progress.value = PlaybackProgress(0.0, 200.0, seekGeneration = 2)
        }
        assertEquals(0f, range().value(), "a different source must not inherit the previous song clock")
        runOnIdle { progress.value = PlaybackProgress(100.0, 100.0, seekGeneration = 3) }
        assertEquals(range().maxValue(), range().value())
        runOnIdle { playing = playing.copy(lyricsLines = listOf(NaviampLyricLineUi(null, "Fits"))) }
        assertEquals(0f, range().value())
        assertEquals(0f, range().maxValue())
        onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun fontScalingAndTelemetryKeepParentCompositionAndCachedImagesStable() = runDesktopComposeUiTest(340, 320) {
        val playing = fixture().copy(isPlaying = true, isPaused = false, compactLyricsFontSizeSp = 64,
            lyricsLines = List(100) { NaviampLyricLineUi(null, "Scaled lyric $it") })
        val progress = MutableStateFlow(PlaybackProgress(0.0, 1000.0))
        val presenter = PlainLyricsRecordingPresenter()
        var compositions = 0
        setContent {
            CompositionLocalProvider(LocalNaviampRasterPresenter provides presenter, LocalDensity provides Density(1f, 1.5f)) {
                SideEffect { compositions++ }
                NaviampCompactLyricsCard(playing, progress, NaviampColors.Dark, {}, {}, Modifier.size(320.dp, 300.dp))
            }
        }
        val lyrics = onNodeWithTag("compact-lyrics-plain-text")
        val layouts = mutableListOf<TextLayoutResult>()
        lyrics.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(64.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals(1.5f, layouts.single().layoutInput.density.fontScale)
        val images = presenter.layers.map { it.image }
        val presentations = presenter.presentations
        val originalCompositions = compositions
        assertTrue(images.isNotEmpty())
        assertTrue(images.all { it.height <= PlainLyricsTileHeight })
        assertTrue(presenter.layers.all { it.translationAxis == NaviampRasterTranslationAxis.Vertical && it.translation != null })
        repeat(5) { index -> runOnIdle { progress.value = PlaybackProgress(index / 10.0, 1000.0) } }
        runOnIdle {
            assertEquals(originalCompositions, compositions)
            assertEquals(presentations, presenter.presentations, "equivalent telemetry must not resubmit native timelines")
            images.zip(presenter.layers).forEach { (image, layer) -> assertSame(image, layer.image) }
        }
    }

    @Test
    fun automaticPlainPlaybackMovesVisibleGlyphsWithoutAnotherPlaybackSample() = runComposeUiTest {
        mainClock.autoAdvance = false
        val playing = fixture().copy(isPlaying = true, isPaused = false, positionSeconds = 0.0, durationSeconds = 100.0,
            lyricsLines = List(40) { NaviampLyricLineUi(null, "Moving untimed lyric $it") })
        setContent {
            NaviampCompactLyricsCard(playing, null, NaviampColors.Dark, {}, {}, Modifier.size(320.dp, 300.dp))
        }
        mainClock.advanceTimeBy(100)
        val lyrics = onNodeWithTag("compact-lyrics-plain-text")
        val before = lyrics.captureToImage().pixelValues()
        mainClock.advanceTimeBy(1000)
        assertTrue(!before.contentEquals(lyrics.captureToImage().pixelValues()), "the shared fallback must move actual glyphs between samples")
    }

    @Test
    fun tileReplacementUsesTheControllersOriginalClockAndRetainsCachedGlyphs() = runTest {
        val scope = backgroundScope
        val clock = TestTimeSource()
        var now = 0L
        val controller = NaviampPlainLyricsScroll({ now }, clock::markNow)
        runDesktopComposeUiTest(260, 300) {
            lateinit var layout: TextLayoutResult
            setContent {
                layout = rememberTextMeasurer().measure(
                    AnnotatedString(List(100) { "Measured tiled lyric $it" }.joinToString("\n")),
                    TextStyle(fontSize = 64.sp, lineHeight = 96.sp), constraints = Constraints(maxWidth = 220))
            }
            runOnIdle {
                val owner = NaviampPlainLyricsContentOwner(layout, IntSize(220, 220), Density(1f), LayoutDirection.Ltr,
                    Color.White, scope, controller)
                try {
                    val initial = owner.render(PlaybackProgress(0.0, 100.0), true)
                    val motion = assertNotNull(controller.motion)
                    val started = assertNotNull(controller.translationStarted)
                    val delay = assertNotNull(controller.nextTileBoundaryDelay(220))
                    now += delay + 1
                    clock += (delay + 1).milliseconds
                    val replacement = owner.render(PlaybackProgress(now / 1000.0, 100.0), true)
                    assertTrue(replacement.any { next -> initial.none { it.origin == next.origin } })
                    assertTrue(replacement.size <= 4)
                    assertEquals(initial.size, replacement.size)
                    assertEquals(1, replacement.indices.count { initial[it].image !== replacement[it].image },
                        "a tile boundary must upload only its new tile, not shift retained images between native slots")
                    for (tile in replacement) {
                        assertSame(motion, tile.translation)
                        assertSame(started, tile.translationStarted)
                        initial.firstOrNull { it.origin == tile.origin }?.let { assertSame(it.image, tile.image) }
                        assertEquals(-controller.offset(), assertNotNull(tile.forPresentation().translation).valueAt(0), .1f)
                    }
                    val unchanged = owner.render(PlaybackProgress(now / 1000.0 + .1, 100.0), true)
                    assertSame(replacement, unchanged)
                    now = 100_000
                    clock += (100_000 - delay - 1).milliseconds
                    val finished = owner.render(PlaybackProgress(100.0, 100.0), true)
                    for (tile in finished) {
                        assertNull(tile.forPresentation().translation)
                        assertTrue(tile.image.height <= PlainLyricsTileHeight)
                    }
                } finally { owner.close() }
            }
        }
    }

    private fun ImageBitmap.pixelValues() = IntArray(width * height).also { readPixels(it) }

    private class PlainLyricsRecordingPresenter : NaviampRasterPresenter {
        var layers = emptyList<NaviampRasterLayer>()
        var presentations = 0
        override fun create() = object : NaviampRasterRegion {
            override fun present(layers: List<NaviampRasterLayer>, bounds: Rect, clip: Rect, cornerRadius: Float): Boolean {
                this@PlainLyricsRecordingPresenter.layers = layers
                presentations++
                return true
            }
            override fun close() = Unit
        }
    }

    private fun fixture() = NowPlayingUi(
        id = "track", title = "Track", subtitle = "Artist", stateLabel = "Paused", isPaused = true,
        lyricsAvailable = true, canRefreshLyrics = true, lyricsSourceId = "source",
    )
}
