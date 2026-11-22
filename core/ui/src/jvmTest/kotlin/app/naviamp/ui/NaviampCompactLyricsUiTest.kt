package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            val firstLine = onNodeWithTag("compact-lyrics-line-0")
            assertTrue(abs(firstLine.fetchSemanticsNode().boundsInRoot.top - viewport.top) <= 1f)
            for (expected in listOf(20, 24, 14, 16)) {
                font.performClick()
                val layouts = mutableListOf<TextLayoutResult>()
                firstLine.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(expected.sp, layouts.single().layoutInput.style.fontSize)
            }
            onNodeWithTag("compact-lyrics-list").performScrollToIndex(29)
            onNodeWithTag("compact-lyrics-line-29").assertIsDisplayed()
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

    private fun fixture() = NowPlayingUi(
        id = "track", title = "Track", subtitle = "Artist", stateLabel = "Paused", isPaused = true,
        lyricsAvailable = true, canRefreshLyrics = true, lyricsSourceId = "source",
    )
}
