package app.naviamp.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import app.naviamp.domain.settings.DesktopShortcutPlatform
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NaviampWindowUiTest {
    @Test fun narrowPlayerHasBackAndNoFullscreenEntryInTrackActions() = runDesktopComposeUiTest(320, 668) {
        val requests = mutableListOf<NaviampWindowSnapshot>()
        val initial = NaviampWindowSnapshot()
        val controller = NaviampWindowController({ requests += it; true }, initial)
        val display = mutableListOf<NowPlayingDisplayAction>()
        setContent {
            NaviampWindowEnvironment(controller, DesktopShortcutPlatform.MacOS) {
                NaviampNowPlayingPanel(
                    nowPlaying = NowPlayingUi(
                        id = "song", title = "Song", subtitle = "Artist", stateLabel = "Paused",
                        menuEnabled = true, canStartRadio = true,
                    ),
                    colors = NaviampColors(),
                    actions = NaviampNowPlayingActions({}, { display += it.action }, {}, {}, {}, {}, {}),
                )
            }
        }
        onAllNodesWithContentDescription("Back").assertCountEquals(1)
        onNodeWithTag("now-playing-back").assertIsDisplayed().assertContentDescriptionEquals("Back").performClick()
        onNodeWithContentDescription("Collapse player").assertDoesNotExist()
        onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
        onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
        onNodeWithContentDescription("Track actions").assertIsEnabled().performClick()
        onNodeWithText("Start Radio").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Enter fullscreen").assertDoesNotExist()
        onNodeWithText("Exit fullscreen").assertDoesNotExist()
        runOnIdle {
            assertEquals(listOf(NowPlayingDisplayAction.Collapse), display)
            assertEquals(initial, controller.state)
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun shortcutsUseTheSameWindowStateAndRestoreWindowGeometry() = runDesktopComposeUiTest(1000, 740) {
        val requests = mutableListOf<NaviampWindowSnapshot>()
        val initial = NaviampWindowSnapshot(width = 1100f, height = 700f, x = 40f, y = 60f)
        val fullscreen = initial.copy(placement = NaviampWindowPlacement.Fullscreen)
        val controller = NaviampWindowController({ requests.add(it); true }, initial)
        setContent { NaviampWindowEnvironment(controller, DesktopShortcutPlatform.MacOS) {} }
        val window = onNodeWithTag("application-window")
        window.performKeyInput { pressKey(Key.F11) }
        runOnIdle {
            assertEquals(fullscreen, controller.state)
            assertEquals(listOf(fullscreen), requests)
        }
        window.performKeyInput { pressKey(Key.Escape) }
        runOnIdle {
            assertEquals(initial, controller.state)
            assertEquals(listOf(fullscreen, initial), requests)
        }
        window.performKeyInput { pressKey(Key.F11) }
        runOnIdle { assertEquals(fullscreen, controller.state) }
        window.performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.MetaLeft); pressKey(Key.F); keyUp(Key.MetaLeft); keyUp(Key.CtrlLeft)
        }
        runOnIdle {
            assertEquals(initial, controller.state)
            assertEquals(initial, controller.windowedSnapshot)
            assertEquals(listOf(fullscreen, initial, fullscreen, initial), requests)
        }
    }

    @Test fun escapeDismissesMenuBeforeExitingFullscreen() = runDesktopComposeUiTest(1000, 740) {
        val controller = NaviampWindowController({ true }, NaviampWindowSnapshot())
        controller.toggle()
        setContent {
            NaviampRasterEnvironment(null, true, false) {
                NaviampWindowEnvironment(controller, DesktopShortcutPlatform.Linux) {
                    NaviampRowOverflowMenu(NaviampColors(), listOf(
                        NaviampRowMenuItem("Test action", NaviampTransportIcons.MoreVertical, {}),
                    ))
                }
            }
        }
        onNodeWithContentDescription("More actions").performClick()
        onNodeWithText("Test action").assertIsDisplayed()
        onNodeWithText("Test action").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        onNodeWithText("Test action").performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Test action").assertDoesNotExist()
        runOnIdle { assertEquals(NaviampWindowPlacement.Fullscreen, controller.state.placement) }
        onNodeWithTag("application-window").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(NaviampWindowPlacement.Floating, controller.state.placement) }
    }

    @Test fun nonFocusableTooltipDoesNotBlockFullscreenEscape() = runDesktopComposeUiTest(1000, 740) {
        val controller = NaviampWindowController({ true }, NaviampWindowSnapshot())
        controller.toggle()
        setContent {
            NaviampRasterEnvironment(null, true, false) {
                NaviampPopupPresence(blocksWindowEscape = false)
                NaviampWindowEnvironment(controller, DesktopShortcutPlatform.Windows) {}
            }
        }
        onNodeWithTag("application-window").performKeyInput { pressKey(Key.Escape) }
        runOnIdle { assertEquals(NaviampWindowPlacement.Floating, controller.state.placement) }
    }
    @Test fun heldShortcutDoesNotRepeatedlyToggleTheWindow() = runDesktopComposeUiTest(1000, 740) {
        var requests = 0
        val controller = NaviampWindowController({ requests++; true }, NaviampWindowSnapshot())
        setContent { NaviampWindowEnvironment(controller, DesktopShortcutPlatform.Windows) {} }
        onNodeWithTag("application-window").performKeyInput {
            keyDown(Key.F11)
            advanceEventTime(600)
            keyUp(Key.F11)
        }
        runOnIdle {
            assertEquals(1, requests)
            assertEquals(NaviampWindowPlacement.Fullscreen, controller.state.placement)
        }
        onNodeWithTag("application-window").performKeyInput { pressKey(Key.F11) }
        runOnIdle {
            assertEquals(2, requests)
            assertEquals(NaviampWindowPlacement.Floating, controller.state.placement)
        }
    }
}
