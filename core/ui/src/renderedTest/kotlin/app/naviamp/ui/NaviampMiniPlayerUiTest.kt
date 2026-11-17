package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.naviamp.domain.settings.InterfaceFontSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampMiniPlayerUiTest {
    private fun actions(playback: (NowPlayingPlaybackAction) -> Unit = {}) =
        NaviampNowPlayingActions({ playback(it.action) }, {}, {}, {}, {}, {}, {})

    @Test
    fun emptyAndSyntheticPlayersStayVisibleWithoutPlayableActions() = runComposeUiTest {
        val state = mutableStateOf<NowPlayingUi?>(null)
        var opened = false
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, { opened = true }, actions())
        } }
        fun assertEmpty() {
            onNodeWithText("Nothing playing").assertIsDisplayed()
            onNodeWithText("Choose something to play").assertIsDisplayed()
            onNodeWithTag("mini-player-identity").assert(hasClickAction().not())
                .performTouchInput { click() }
            onNodeWithContentDescription("Previous").assertIsNotEnabled()
            onNodeWithContentDescription("Play").assertIsNotEnabled()
            onNodeWithContentDescription("Next").assertIsNotEnabled()
            runOnIdle { assertEquals(false, opened) }
        }
        assertEmpty()
        runOnIdle { state.value = NowPlayingUi(id = "  ", title = "Stale title", subtitle = "Stale artist",
            stateLabel = "Playing", isPlaying = true, hasPrevious = true, hasNext = true) }
        assertEmpty()
        onNodeWithText("Stale title").assertDoesNotExist()
    }

    @Test
    fun transportTransitionsAndIdentityKeepTheCurrentTrack() = runComposeUiTest {
        val track = NowPlayingUi(id = "track", title = "Song", subtitle = "Artist", stateLabel = "Playing",
            isPlaying = true, hasPrevious = true, hasNext = true)
        val state = mutableStateOf(track)
        val dispatched = mutableListOf<NowPlayingPlaybackAction>()
        var opened = 0
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, { opened++ }, actions {
                dispatched += it
                state.value = when (it) {
                    NowPlayingPlaybackAction.Pause -> state.value.copy(isPlaying = false, isPaused = true)
                    NowPlayingPlaybackAction.Resume, NowPlayingPlaybackAction.PlayCurrent -> state.value.copy(isPlaying = true, isPaused = false)
                    else -> state.value
                }
            })
        } }
        onNodeWithContentDescription("Pause").assertIsEnabled().performClick()
        onNodeWithContentDescription("Play").performClick()
        onNodeWithContentDescription("Pause").assertIsDisplayed()
        runOnIdle { state.value = state.value.copy(isPlaying = false, isPaused = false) }
        onNodeWithContentDescription("Play").performClick()
        onNodeWithContentDescription("Previous").performClick()
        onNodeWithContentDescription("Next").performClick()
        onNodeWithTag("mini-player-identity").performClick().performClick()
        runOnIdle {
            assertEquals(listOf(NowPlayingPlaybackAction.Pause, NowPlayingPlaybackAction.Resume,
                NowPlayingPlaybackAction.PlayCurrent, NowPlayingPlaybackAction.Previous, NowPlayingPlaybackAction.Next), dispatched)
            assertEquals(track.id, state.value.id)
            assertEquals(2, opened)
            state.value = state.value.copy(hasPrevious = false, hasNext = false, canPlayPause = false)
        }
        onNodeWithContentDescription("Previous").assertIsNotEnabled()
        onNodeWithContentDescription("Pause").assertIsNotEnabled()
        onNodeWithContentDescription("Next").assertIsNotEnabled()
    }

    @Test
    fun narrowLargeTextKeepsTitleAndTransportTargetsSeparate() = runComposeUiTest {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NaviampFontSizeScope(InterfaceFontSize.Large, includeStandardScale = true) {
                    Box(Modifier.width(320.dp)) {
                        NaviampMiniNowPlaying(NowPlayingUi(id = "track", title = "A long current track title",
                            subtitle = "Artist", stateLabel = "Paused", isPaused = true, hasNext = true),
                            NaviampColors.Dark, {}, actions())
                    }
                }
            }
        }
        onNodeWithContentDescription("Previous").assertDoesNotExist()
        onNodeWithText("A long current track title", useUnmergedTree = true).assertIsDisplayed()
        val identity = onNodeWithTag("mini-player-identity").getUnclippedBoundsInRoot()
        val play = onNodeWithContentDescription("Play").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
        val next = onNodeWithContentDescription("Next").assertIsDisplayed().assertIsEnabled().getUnclippedBoundsInRoot()
        assertTrue(play.right - play.left >= 48.dp && play.bottom - play.top >= 48.dp)
        assertTrue(next.right - next.left >= 48.dp && next.bottom - next.top >= 48.dp)
        assertTrue(identity.right <= play.left && play.right <= next.left)
    }

    @Test
    fun liveRadioWithNoTrackIdUsesStationIdentityAndSuppliedCapabilities() = runComposeUiTest {
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(NowPlayingUi(title = "Fixture radio", subtitle = "Live station", stateLabel = "Live",
                isLive = true, isPlaying = true, hasPrevious = false, hasNext = true, canSeek = false),
                NaviampColors.Dark, {}, actions())
        } }
        onNodeWithText("Fixture radio", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Live station", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("mini-player-identity").assertHasClickAction()
        onNodeWithContentDescription("Previous").assertIsNotEnabled()
        onNodeWithContentDescription("Pause").assertIsEnabled()
        onNodeWithContentDescription("Next").assertIsEnabled()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
    }
}
