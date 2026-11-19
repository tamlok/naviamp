package app.naviamp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NaviampPopupToggleTest {
    @Test fun firstHoverOnEachItemKeepsTheMenuInPlace() = runComposeUiTest {
        setContent {
            NaviampRowOverflowMenu(NaviampColors(), List(6) {
                NaviampRowMenuItem("Action $it", NaviampTransportIcons.MoreVertical, {})
            })
        }
        onNodeWithContentDescription("More actions").performMouseInput { click() }
        val initialBounds = onNodeWithText("Action 0").fetchSemanticsNode().boundsInRoot
        repeat(6) { index ->
            onNodeWithText("Action $index").performMouseInput { moveTo(center) }
            waitForIdle()
            assertEquals(initialBounds, onNodeWithText("Action 0").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun overflowButtonClosesAnAlreadyOpenMenu() = runComposeUiTest {
        setContent {
            NaviampRowOverflowMenu(NaviampColors(), listOf(
                NaviampRowMenuItem(label = "Test action", icon = NaviampTransportIcons.MoreVertical, onClick = {}),
            ))
        }
        onNodeWithContentDescription("More actions").performMouseInput { click() }
        onNodeWithText("Test action").assertIsDisplayed()
        onNodeWithContentDescription("More actions").performMouseInput { click() }
        onNodeWithText("Test action").assertDoesNotExist()
    }

    @Test fun rightClickUsesExistingMenuAndDismissal() = verifyRowContextMenu(owned = false)
    @Test fun rightClickUsesOwnedMenuAndDismissal() = verifyRowContextMenu(owned = true)

    private fun verifyRowContextMenu(owned: Boolean) = runComposeUiTest {
        var selected = 0
        var actionCount = 0
        val item = mutableStateOf(SharedMediaItemUi("album", "Album", "Artist"))
        setContent {
            CompositionLocalProvider(LocalNaviampOwnedPopupWindows provides owned) {
                Column(Modifier.size(700.dp, 500.dp)) {
                    Spacer(Modifier.height(60.dp))
                    SharedMediaRow(
                        item = item.value,
                        colors = NaviampColors(),
                        mediaKind = SharedMediaItemKind.Album,
                        onClick = { selected++ },
                        menuItems = listOf(
                            NaviampRowMenuItem("Queue album", NaviampIcons.Queue, { actionCount++ }),
                            NaviampRowMenuItem("Unavailable", NaviampIcons.Queue, { actionCount += 100 }, enabled = false),
                        ),
                    )
                    SharedMediaRow(
                        item = SharedMediaItemUi("other", "Other album", "Artist"),
                        colors = NaviampColors(),
                        mediaKind = SharedMediaItemKind.Album,
                        menuItems = listOf(NaviampRowMenuItem("Other action", NaviampIcons.Queue, {})),
                    )
                }
            }
        }
        onNodeWithText("Album").performMouseInput {
            moveTo(center)
            press(MouseButton.Secondary)
        }
        // A refresh while the button is held must not cancel the opening gesture.
        runOnIdle { item.value = item.value.copy(subtitle = "Refreshed while pressed") }
        onNodeWithText("Album").performMouseInput { release(MouseButton.Secondary) }
        onNodeWithText("Queue album").assertIsDisplayed()
        onNodeWithText("Other action").assertDoesNotExist()
        onNodeWithText("Unavailable").assertIsNotEnabled().performMouseInput { click() }
        runOnIdle { assertEquals(0, actionCount); assertEquals(0, selected) }
        // A metadata refresh must keep the menu attached to the same item.
        runOnIdle { item.value = item.value.copy(subtitle = "Updated artist") }
        onNodeWithText("Queue album").assertIsDisplayed().performMouseInput { click() }
        onNodeWithText("Queue album").assertDoesNotExist()
        runOnIdle { assertEquals(1, actionCount); assertEquals(0, selected) }

        onAllNodesWithContentDescription("More actions")[0].performMouseInput { click() }
        onNodeWithText("Queue album").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Queue album").assertDoesNotExist()

        onNodeWithText("Album").performMouseInput { click(button = MouseButton.Secondary) }
        onNodeWithText("Queue album").assertIsDisplayed()
        onAllNodes(isRoot()).onLast().performMouseInput { click(Offset(8f, 8f)) }
        onNodeWithText("Queue album").assertDoesNotExist()

        onNodeWithText("Album").performMouseInput { click(); exit() }
        onNodeWithText("Album").performTouchInput { click() }
        runOnIdle { assertEquals(2, selected) }

        onNodeWithText("Album").performMouseInput { click(button = MouseButton.Secondary) }
        runOnIdle { item.value = item.value.copy(id = "replacement", title = "Replacement") }
        onNodeWithText("Queue album").assertDoesNotExist()
    }

    @Test fun secondaryDragDoesNotSwipeOrSelectTrack() = runComposeUiTest {
        var swiped = 0
        val requests = mutableListOf<SharedTrackRowActionRequest>()
        setContent {
            Box(Modifier.size(600.dp, 400.dp)) {
                SwipeActionContainer(
                    swipeRight = TrackSwipeActionVisual("Queue", NaviampIcons.Queue, Color.Green, onTriggered = { swiped++ }),
                ) { modifier ->
                    TrackRow(
                        track = SharedTrackRowUi("song", "Song", "Artist"), colors = NaviampColors(),
                        onTrackAction = { requests += it }, canSelect = true, canStartRadio = false,
                        canAddToQueue = true, canDownload = false, canAddToPlaylist = false,
                        modifier = modifier.testTag("track"),
                    )
                }
            }
        }
        onNodeWithTag("track").performMouseInput {
            moveTo(Offset(100f, center.y))
            press(MouseButton.Secondary)
            moveTo(Offset(260f, center.y), delayMillis = 200)
            release(MouseButton.Secondary)
        }
        onNodeWithText("Play Immediately Next").assertDoesNotExist()
        runOnIdle { assertEquals(0, swiped); assertEquals(emptyList(), requests) }
        onNodeWithText("Song").performMouseInput { click(button = MouseButton.Secondary) }
        onNodeWithText("Play Immediately Next").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(SharedTrackRowAction.PlayNextTrack, requests.single().action) }
    }

    @Test fun rowWithoutActionsDoesNotOpenMenuOrSelectOnRightClick() = runComposeUiTest {
        var selected = 0
        setContent {
            SharedMediaRow(
                item = SharedMediaItemUi("album", "Album", "Artist"), colors = NaviampColors(),
                mediaKind = SharedMediaItemKind.Album, onClick = { selected++ },
            )
        }
        onNodeWithText("Album").performMouseInput { click(button = MouseButton.Secondary) }
        onNodeWithContentDescription("More actions").assertDoesNotExist()
        runOnIdle { assertEquals(0, selected) }
        onNodeWithText("Album").performMouseInput { click() }
        runOnIdle { assertEquals(1, selected) }
    }
}
