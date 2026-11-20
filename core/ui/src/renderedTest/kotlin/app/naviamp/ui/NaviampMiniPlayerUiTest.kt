package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import app.naviamp.domain.playback.PlaybackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import app.naviamp.domain.settings.InterfaceFontSize
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.AppBackgroundStyle
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemeSettings
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NaviampMiniPlayerUiTest {
    private fun actions(playback: (NowPlayingPlaybackAction) -> Unit = {}) =
        NaviampNowPlayingActions({ playback(it.action) }, {}, {}, {}, {}, {}, {})

    @Test
    fun emptyPlayerBackgroundTransitionsRemainLiveAcrossStyles() = runComposeUiTest {
        val settings = mutableStateOf(InterfaceSettings(appBackgroundStyle = AppBackgroundStyle.AlbumBlur,
            albumBlurRadiusDp = 36, singleColorHex = "#315577"))
        setContent { NaviampTheme(settings.value.theme) {
            Box(Modifier.width(1000.dp)) {
                NaviampMiniNowPlaying(null, LocalNaviampTheme.current.colors, {}, actions(),
                    rememberNaviampPlayerBackground(settings.value, null, LocalNaviampTheme.current.colors))
            }
        } }
        runOnIdle { settings.value = settings.value.copy(appBackgroundStyle = AppBackgroundStyle.Aurora) }
        mainClock.advanceTimeBy(500)
        runOnIdle { settings.value = settings.value.copy(theme = NaviampThemeSettings(NaviampThemeMode.Light)) }
        mainClock.advanceTimeBy(500)
        runOnIdle { settings.value = settings.value.copy(auroraColorSteps = 5) }
        mainClock.advanceTimeBy(500)
        val gradient = onNodeWithTag("mini-player").captureToImage().toPixelMap()
        assertTrue(gradient[2, 2] != gradient[gradient.width - 3, 2], "Empty player retains its fallback artwork gradient")
        runOnIdle { settings.value = settings.value.copy(appBackgroundStyle = AppBackgroundStyle.SingleColor) }
        val solid = onNodeWithTag("mini-player").captureToImage().toPixelMap()
        assertEquals(Color(0xFF315577), solid[2, 2])
        assertEquals(Color(0xFF315577), solid[solid.width - 3, 2])
        onNodeWithText("Nothing playing").assertIsDisplayed()
        onNodeWithContentDescription("Play").assertIsNotEnabled()
    }

    @Test
    fun backgroundsFollowLiveSystemAppearanceAndExplicitModesWithUnchangedArtwork() = runComposeUiTest {
        val settings = mutableStateOf(InterfaceSettings())
        val systemDark = mutableStateOf(true)
        val track = NowPlayingUi(id = "theme-track", title = "Theme track", subtitle = "Artist", stateLabel = "Paused",
            isPaused = true, coverArtUrl = "naviamp-radio-tile://cover?label=Theme&from=884422&to=224488")
        setContent { NaviampTheme(settings.value.theme, systemDark.value) {
            Box(Modifier.width(1000.dp)) {
                NaviampMiniNowPlaying(track, LocalNaviampTheme.current.colors, {}, actions(),
                    rememberNaviampPlayerBackground(settings.value, track.coverArtUrl, LocalNaviampTheme.current.colors))
            }
        } }
        fun pixel(): Color = onNodeWithTag("mini-player-background").captureToImage().toPixelMap()[2, 2]
        fun settle() { mainClock.advanceTimeBy(1000); waitForIdle() }
        for (pack in listOf("naviamp", "forest")) {
            for (style in listOf(AppBackgroundStyle.Aurora, AppBackgroundStyle.AlbumBlur)) {
                runOnIdle {
                    settings.value = settings.value.copy(appBackgroundStyle = style,
                        theme = NaviampThemeSettings(NaviampThemeMode.System, pack))
                    systemDark.value = true
                }
                // Palette/image loading is asynchronous; wait for the same cached artwork before comparing themes.
                waitUntil(timeoutMillis = 5_000) { pixel().red > pixel().blue }
                settle()
                val dark = pixel().luminance()
                runOnIdle { systemDark.value = false }
                settle()
                val light = pixel().luminance()
                assertTrue(light > dark + 0.05f, "$pack $style must follow System without a settings or artwork change")
                runOnIdle { systemDark.value = true }
                settle()
                assertEquals(dark, pixel().luminance(), 0.01f)
                runOnIdle { settings.value = settings.value.copy(theme = settings.value.theme.copy(mode = NaviampThemeMode.Light)) }
                settle()
                assertEquals(light, pixel().luminance(), 0.01f)
                runOnIdle { systemDark.value = false }
                settle()
                assertEquals(light, pixel().luminance(), 0.01f, "Explicit Light must ignore System")
            }
        }
    }

    @Test
    fun emptyAndSyntheticPlayersStayVisibleWithoutPlayableActions() = runComposeUiTest {
        val state = mutableStateOf<NowPlayingUi?>(null)
        var opened = false
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, { opened = true }, actions(),
                rememberNaviampPlayerBackground(InterfaceSettings(), state.value?.coverArtUrl, NaviampColors.Dark))
        } }
        fun assertEmpty() {
            onNodeWithText("Nothing playing").assertIsDisplayed()
            onNodeWithText("Choose something to play").assertIsDisplayed()
            onNodeWithTag("mini-player-identity").assert(hasClickAction().not())
                .performTouchInput { click() }
            onNodeWithContentDescription("Previous").assertIsNotEnabled()
            onNodeWithContentDescription("Play").assertIsNotEnabled()
            onNodeWithContentDescription("Next").assertIsNotEnabled()
            onNodeWithContentDescription("Playback position").assertDoesNotExist()
            onNodeWithContentDescription("Volume").assertDoesNotExist()
            onNodeWithTag("mini-player-time").assertDoesNotExist()
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
            }, rememberNaviampPlayerBackground(InterfaceSettings(), state.value.coverArtUrl, NaviampColors.Dark))
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
                            NaviampColors.Dark, {}, actions(),
                            rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark))
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
                NaviampColors.Dark, {}, actions(),
                rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark))
        } }
        onNodeWithText("Fixture radio", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Live station", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("mini-player-identity").assertHasClickAction()
        onNodeWithContentDescription("Previous").assertIsNotEnabled()
        onNodeWithContentDescription("Pause").assertIsEnabled()
        onNodeWithContentDescription("Next").assertIsEnabled()
        onNodeWithContentDescription("Playback position").assertDoesNotExist()
        onNodeWithTag("mini-player-time").assertDoesNotExist()
        onNodeWithContentDescription("Volume").assertIsDisplayed()
    }

    @Test
    fun progressFollowsPlaybackTicksAndSeeksWithoutOpeningDetails() = runComposeUiTest {
        val progress = MutableStateFlow(PlaybackProgress(40.0, 200.0))
        val requests = mutableListOf<NowPlayingPlaybackActionRequest>()
        var opened = 0
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(
                NowPlayingUi(id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                    positionSeconds = 10.0, durationSeconds = 200.0, isPaused = true),
                NaviampColors.Dark, { opened++ }, NaviampNowPlayingActions({ requests += it }, {}, {}, {}, {}, {}, {}),
                rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark),
                playbackProgress = progress,
            )
        } }
        val slider = onNodeWithContentDescription("Playback position")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.2f, 0f..1f))
        onNodeWithText("00:40/03:20").assertIsDisplayed()
        runOnIdle { progress.value = PlaybackProgress(80.0, 200.0) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.4f, 0f..1f))
        onNodeWithText("01:20/03:20").assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        runOnIdle {
            assertEquals(NowPlayingPlaybackAction.Seek, requests.single().action)
            assertEquals(150.0, requests.single().seekSeconds)
        }
        slider.performTouchInput {
            down(Offset(width * .25f, centerY))
            moveTo(Offset(width * .6f, centerY))
        }
        onNodeWithText("02:00/03:20").assertIsDisplayed()
        slider.performTouchInput { up() }
        runOnIdle { assertEquals(120.0, requests.last().seekSeconds!!, 1.0) }
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        runOnIdle {
            assertEquals(82.0, requests.last().seekSeconds!!, .01)
            assertEquals(0, opened)
        }
    }

    @Test
    fun timingDiscoversDurationAndKeepsTheStructuralDurationAuthoritative() = runComposeUiTest {
        val progress = MutableStateFlow(PlaybackProgress(61.9, null))
        val state = mutableStateOf(NowPlayingUi(id = "song", title = "Song", subtitle = "Artist",
            stateLabel = "Paused", positionSeconds = 10.0, isPaused = true))
        val seeks = mutableListOf<NowPlayingPlaybackActionRequest>()
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, {},
                NaviampNowPlayingActions({ seeks += it }, {}, {}, {}, {}, {}, {}),
                rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark), playbackProgress = progress)
        } }
        onNodeWithText("01:01/--:--").assertIsDisplayed()
        val slider = onNodeWithContentDescription("Playback position")
        slider.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress).not())
        runOnIdle { progress.value = PlaybackProgress(61.9, 338.0) }
        onNodeWithText("01:01/05:38").assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        runOnIdle { assertEquals(169.0, seeks.single().seekSeconds) }
        runOnIdle { state.value = state.value.copy(durationSeconds = 200.0) }
        onNodeWithText("01:01/03:20").assertIsDisplayed()
        slider.assertRangeInfoEquals(ProgressBarRangeInfo((61.9 / 200.0).toFloat(), 0f..1f))
    }

    @Test
    fun timingRemainsAboveTheRightEndWithoutVolumeInRtl() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(412.dp)) {
                    NaviampMiniNowPlaying(NowPlayingUi(id = "song", title = "Song", subtitle = "Artist",
                        stateLabel = "Paused", positionSeconds = 61.0, durationSeconds = 338.0),
                        NaviampColors.Dark, {}, actions(),
                        rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark), showVolumeBar = false)
                }
            }
        }
        onNodeWithText("01:01/05:38").assertIsDisplayed()
        onNodeWithContentDescription("Volume").assertDoesNotExist()
        val time = onNodeWithTag("mini-player-time").getUnclippedBoundsInRoot()
        val progress = onNodeWithContentDescription("Playback position").getUnclippedBoundsInRoot()
        assertEquals(progress.right, time.right)
        assertTrue(time.bottom <= progress.top)
    }

    @Test
    fun volumeFollowsExternalChangesAndRespectsCapabilitiesAndPreference() = runComposeUiTest {
        val state = mutableStateOf(NowPlayingUi(id = "song", title = "Song", subtitle = "Artist",
            stateLabel = "Paused", durationSeconds = 200.0, volumePercent = 40))
        val showVolume = mutableStateOf(true)
        val requests = mutableListOf<NowPlayingPlaybackActionRequest>()
        var opened = 0
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, { opened++ },
                NaviampNowPlayingActions({ requests += it }, {}, {}, {}, {}, {}, {}),
                rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark),
                showVolumeBar = showVolume.value)
        } }
        val slider = onNodeWithContentDescription("Volume")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.4f, 0f..1f))
        runOnIdle { state.value = state.value.copy(volumePercent = 70) }
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.7f, 0f..1f))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(-1f) }
        slider.performTouchInput { click(center) }
        runOnIdle {
            assertEquals(listOf(100, 0, 50), requests.map { it.volumePercent })
            assertTrue(requests.all { it.action == NowPlayingPlaybackAction.ChangeVolume })
            assertEquals(0, opened)
            showVolume.value = false
        }
        slider.assertDoesNotExist()
        runOnIdle { showVolume.value = true; state.value = state.value.copy(canChangeVolume = false) }
        slider.assertDoesNotExist()
        onNodeWithContentDescription("Playback position").assertIsDisplayed()
    }

    @Test
    fun speakerTogglesWithoutOpeningDetailsAndFollowsVolumeVisibility() = runComposeUiTest {
        val playing = mutableStateOf(NowPlayingUi(id = "song", title = "Song", subtitle = "Artist",
            stateLabel = "Paused", durationSeconds = 200.0, volumePercent = 40))
        val showVolume = mutableStateOf(true)
        var opened = 0
        setContent { Box(Modifier.width(320.dp)) {
            NaviampMiniNowPlaying(playing.value, NaviampColors.Dark, { opened++ },
                NaviampNowPlayingActions({ request ->
                    if (request.action == NowPlayingPlaybackAction.ToggleMute) {
                        playing.value = playing.value.copy(volumePercent = if (playing.value.volumePercent == 0) 40 else 0)
                    }
                }, {}, {}, {}, {}, {}, {}),
                rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark),
                showVolumeBar = showVolume.value)
        } }
        onNodeWithContentDescription("Mute").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
        onNodeWithContentDescription("Volume").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        onNodeWithContentDescription("Unmute").performTouchInput { click(center) }
        onNodeWithContentDescription("Volume").assertRangeInfoEquals(ProgressBarRangeInfo(.4f, 0f..1f))
        runOnIdle { assertEquals(0, opened); showVolume.value = false }
        onNodeWithContentDescription("Mute").assertDoesNotExist()
        runOnIdle { showVolume.value = true; playing.value = playing.value.copy(volumePercent = 0) }
        onNodeWithContentDescription("Unmute").assertIsDisplayed()
        runOnIdle { playing.value = playing.value.copy(canChangeVolume = false) }
        onNodeWithContentDescription("Unmute").assertDoesNotExist()
    }

    @Test
    fun unknownDurationAndUnavailableRemoteSeekingNeverDispatchASeek() = runComposeUiTest {
        val state = mutableStateOf(NowPlayingUi(id = "remote", title = "Remote song", subtitle = "Device",
            stateLabel = "Paused", durationSeconds = 200.0, positionSeconds = 50.0,
            canSeek = false, canChangeVolume = false))
        setContent { Box(Modifier.width(412.dp)) {
            NaviampMiniNowPlaying(state.value, NaviampColors.Dark, {},
                NaviampNowPlayingActions({ error("Disabled control dispatched $it") }, {}, {}, {}, {}, {}, {}),
                rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark))
        } }
        val slider = onNodeWithContentDescription("Playback position")
        slider.assertRangeInfoEquals(ProgressBarRangeInfo(.25f, 0f..1f))
        fun assertNotSeekable() {
            slider.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress).not())
            slider.performTouchInput { click(center) }
        }
        assertNotSeekable()
        onNodeWithContentDescription("Volume").assertDoesNotExist()
        for (duration in listOf(null, 0.0)) {
            runOnIdle { state.value = state.value.copy(canSeek = true, durationSeconds = duration) }
            assertNotSeekable()
        }
        runOnIdle { state.value = state.value.copy(isLive = true) }
        slider.assertDoesNotExist()
        onNodeWithTag("mini-player-time").assertDoesNotExist()
        onNodeWithTag("mini-player").assertHeightIsEqualTo(64.dp)
    }

    @Test
    fun slidersStaySeparateAndReachableAcrossWidthsAndLargeText() = runComposeUiTest {
        val width = mutableStateOf(320.dp)
        var requiredTimeWidth = 0.dp
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                val timingLayout = rememberTextMeasurer().measure("01:01/05:38",
                    LocalTextStyle.current.merge(TextStyle(fontSize = 11.sp)))
                requiredTimeWidth = with(LocalDensity.current) { timingLayout.size.width.toDp() }
                Box(Modifier.width(width.value)) {
                    NaviampMiniNowPlaying(NowPlayingUi(id = "song", title = "A long song title", subtitle = "Artist",
                        stateLabel = "Paused", positionSeconds = 61.0, durationSeconds = 338.0), NaviampColors.Dark, {}, actions(),
                        rememberNaviampPlayerBackground(InterfaceSettings(), null, NaviampColors.Dark))
                }
            }
        }
        for (viewport in listOf(320, 412, 719, 720, 1000)) {
            runOnIdle { width.value = viewport.dp }
            val progress = onNodeWithContentDescription("Playback position")
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            val volume = onNodeWithContentDescription("Volume")
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            val mute = onNodeWithContentDescription("Mute")
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            val identity = onNodeWithTag("mini-player-identity").getUnclippedBoundsInRoot()
            val next = onNodeWithContentDescription("Next").getUnclippedBoundsInRoot()
            val player = onNodeWithTag("mini-player").getUnclippedBoundsInRoot()
            onNodeWithText("01:01/05:38").assertIsDisplayed()
            val time = onNodeWithTag("mini-player-time").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals(progress.right, time.right)
            assertTrue(time.right - time.left >= requiredTimeWidth, "Elapsed/total timing must fit without splitting its digits")
            assertTrue(time.bottom <= progress.top && time.top >= player.top)
            assertEquals(progress.bottom, volume.bottom)
            assertTrue(progress.right <= volume.left && volume.right <= player.right)
            assertTrue(progress.right <= mute.left && mute.right <= volume.left)
            assertTrue(progress.bottom <= player.bottom && volume.bottom <= player.bottom)
            assertTrue(progress.top >= identity.bottom && progress.top >= next.bottom)
        }
    }
}
