package app.naviamp.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampPlayerAccessibilityTest {
    @Test fun progressHasANameAndRetainsLiveRangeAndSeekAction() = runComposeUiTest {
        val progress = mutableFloatStateOf(.2f)
        val seeks = mutableListOf<Float>()
        setContent {
            WaveformScrubber(emptyList(), .2f, drawValue = { progress.floatValue }, enabled = true,
                colors = NaviampColors(), onValueChange = { progress.floatValue = it },
                onValueChangeFinished = { seeks += it },
                modifier = Modifier.width(200.dp).height(30.dp).testTag("progress"))
        }
        val slider = onNodeWithContentDescription("Playback position")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.2f, 0f..1f))
        runOnIdle { progress.floatValue = .4f }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.4f, 0f..1f))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(.7f) }
        runOnIdle { assertEquals(listOf(.7f), seeks) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.7f, 0f..1f))
    }

    @Test fun disabledProgressRetainsItsNameWithoutExposingSeek() = runComposeUiTest {
        setContent {
            WaveformScrubber(emptyList(), .3f, enabled = false, colors = NaviampColors(),
                onValueChange = { error("Disabled progress changed") }, onValueChangeFinished = {},
                modifier = Modifier.width(200.dp).height(30.dp))
        }
        val slider = onNodeWithContentDescription("Playback position")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.3f, 0f..1f))
        assertFalse(SemanticsActions.SetProgress in slider.fetchSemanticsNode().config)
    }

    @Test fun volumeExposesItsOwnNameLiveRangeAndBoundedAction() = runDesktopComposeUiTest(1280, 720) {
        val volumes = mutableListOf<Int>()
        setContent {
            NaviampNowPlayingPanel(
                NowPlayingUi(id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                    volumePercent = 50, canChangeVolume = true), colors = NaviampColors(),
                actions = NaviampNowPlayingActions(onPlaybackAction = { request ->
                    if (request.action == NowPlayingPlaybackAction.ChangeVolume) volumes += checkNotNull(request.volumePercent)
                }, {}, {}, {}, {}, {}, {}),
            )
        }
        onNodeWithTag("now-playing-volume-slider").assertDoesNotExist()
        onNodeWithTag("volume-popup-button").performClick()
        val slider = onNodeWithTag("now-playing-volume-slider")
        slider.assertContentDescriptionEquals("Volume")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.5f, 0f..1f))
        slider.assertIsFocused()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(-1f) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        assertEquals(listOf(100, 0), volumes)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(Float.NaN)) }
        assertEquals(listOf(100, 0), volumes)
    }

    @Test fun speakerOpensVolumeWithoutMutingAndTracksExternalVolume() = runDesktopComposeUiTest(1280, 720) {
        val playing = mutableStateOf(NowPlayingUi(id = "song", title = "Song", subtitle = "Artist",
            stateLabel = "Paused", volumePercent = 65, canChangeVolume = true))
        val requests = mutableListOf<NowPlayingPlaybackActionRequest>()
        setContent {
            NaviampNowPlayingPanel(playing.value, colors = NaviampColors(),
                actions = NaviampNowPlayingActions({ requests += it }, {}, {}, {}, {}, {}, {}))
        }
        val speaker = onNodeWithTag("volume-popup-button")
        speaker.assertContentDescriptionEquals("Volume").assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performMouseInput { click() }
        val slider = onNodeWithTag("now-playing-volume-slider")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.65f, 0f..1f))
        val bounds = slider.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top > bounds.right - bounds.left)
        runOnIdle { playing.value = playing.value.copy(volumePercent = 0) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        slider.performKeyInput { pressKey(Key.Escape) }
        slider.assertDoesNotExist()
        speaker.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.Enter) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        speaker.performMouseInput { click() }
        slider.assertDoesNotExist()
        runOnIdle { assertTrue(requests.isEmpty()) }
        speaker.performClick()
        runOnIdle { playing.value = playing.value.copy(canChangeVolume = false) }
        speaker.assertDoesNotExist()
        slider.assertDoesNotExist()
        runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun volumePopupKeyboardChangesAndDismissalWorkInBothLayoutDirections() {
        for (direction in LayoutDirection.entries) runDesktopComposeUiTest(1280, 720) {
            val volumes = mutableListOf<Int>()
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    NaviampNowPlayingPanel(
                        NowPlayingUi(id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                            volumePercent = 50, canChangeVolume = true), colors = NaviampColors(),
                        actions = NaviampNowPlayingActions(onPlaybackAction = { request ->
                            if (request.action == NowPlayingPlaybackAction.ChangeVolume) volumes += checkNotNull(request.volumePercent)
                        }, {}, {}, {}, {}, {}, {}),
                    )
                }
            }
            onNodeWithTag("volume-popup-button").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                .performKeyInput { pressKey(Key.Enter) }
            val slider = onNodeWithTag("now-playing-volume-slider")
            slider.assertIsFocused()
            slider.performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionUp); pressKey(Key.DirectionUp) }
            assertEquals(listOf(55, 60, 65), volumes, "Up follows the visible bottom-to-top track in $direction")
            slider.performKeyInput { pressKey(Key.MoveEnd); pressKey(Key.DirectionUp) }
            slider.assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
            slider.performKeyInput { pressKey(Key.MoveHome); pressKey(Key.DirectionDown) }
            slider.assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
            slider.performKeyInput { pressKey(Key.PageUp) }
            slider.assertRangeInfoEquals(ProgressBarRangeInfo(.1f, 0f..1f))
            slider.performKeyInput { pressKey(Key.Escape) }
            slider.assertDoesNotExist()
        }
    }
}
