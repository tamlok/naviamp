package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.naviamp.domain.smartplaylist.SmartPlaylistDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NaviampPlaylistArtworkPlayUiTest {
    @Test
    fun artworkPlayTracksLoadedContentAndCurrentPlaylist() = runComposeUiTest {
        val firstPlaylist = SharedMediaItemUi("first", "First playlist", "0 tracks")
        val detail = mutableStateOf(SharedPlaylistDetailUi(firstPlaylist, emptyList()))
        val requests = mutableListOf<NaviampPlaylistDetailActionRequest>()
        val track = SharedTrackRowUi("track", "Track", "Artist")
        setContent {
            Box(Modifier.size(width = 360.dp, height = 480.dp)) {
                NaviampPlaylistDetailContent(
                    colors = NaviampColors(),
                    screen = NaviampPlaylistDetailScreenUi(
                        selectedPlaylist = detail.value.playlist,
                        detail = detail.value,
                    ),
                    actions = NaviampPlaylistDetailActions(
                        onBack = {},
                        onPlaylistAction = { requests += it },
                        onUpdateStandardPlaylist = { _, _, _ -> },
                        onTrackAction = {},
                    ),
                    playlistsActions = NaviampPlaylistsActions(
                        onRefresh = {},
                        onSortModeChanged = {},
                        smartPlaylist = NaviampSmartPlaylistActions(
                            onSave = {},
                            onUpdate = { _, _ -> },
                            onSaveWithPassword = { _, _ -> },
                            onUpdateWithPassword = { _, _, _ -> },
                            onLoad = { SmartPlaylistDefinition(name = "Unused", rules = emptyList()) },
                            onLoadWithPassword = { _, _ -> SmartPlaylistDefinition(name = "Unused", rules = emptyList()) },
                        ),
                    ),
                    playlistChoices = emptyList(),
                )
            }
        }

        val overlay = onNodeWithTag("playlist-artwork-play")
        overlay.assertIsDisplayed().assertContentDescriptionEquals("Play playlist")
        overlay.assertIsNotEnabled().performTouchInput { click() }
        runOnIdle {
            assertEquals(emptyList(), requests)
            detail.value = detail.value.copy(tracks = listOf(track))
        }
        overlay.assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(
                listOf(NaviampPlaylistDetailActionRequest(firstPlaylist, NaviampPlaylistDetailCommand.Play(false))),
                requests,
            )
            detail.value = SharedPlaylistDetailUi(
                SharedMediaItemUi("second", "Second playlist", "1 track", isSmartPlaylist = true),
                listOf(track),
            )
        }
        overlay.assertIsEnabled().performClick()
        runOnIdle {
            assertEquals(
                listOf(
                    NaviampPlaylistDetailActionRequest(firstPlaylist, NaviampPlaylistDetailCommand.Play(false)),
                    NaviampPlaylistDetailActionRequest(detail.value.playlist, NaviampPlaylistDetailCommand.Play(false)),
                ),
                requests,
            )
            detail.value = detail.value.copy(tracks = emptyList())
        }
        overlay.assertIsNotEnabled().performTouchInput { click() }
        runOnIdle { assertEquals(2, requests.size) }
    }
}
