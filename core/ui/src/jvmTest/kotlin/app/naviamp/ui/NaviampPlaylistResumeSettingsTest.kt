package app.naviamp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import app.naviamp.domain.settings.PlaybackSettings
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.settings_resume_playlists
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NaviampPlaylistResumeSettingsTest {
    @Test
    fun playlistResumeToggleEmitsOnlyTheChangedPreference() = runDesktopComposeUiTest(480, 800) {
        val initial = PlaybackSettings(gaplessEnabled = false, crossfadeDurationSeconds = 4)
        val settings = mutableStateOf(initial)
        val emitted = mutableListOf<PlaybackSettings>()
        val label = runBlocking { getString(Res.string.settings_resume_playlists) }
        setContent {
            Column {
                NaviampPlaybackSettingsSection(
                    colors = NaviampColors(),
                    playbackSettings = settings.value,
                    supportsReplayGain = false,
                    supportsGapless = false,
                    supportsCrossfade = false,
                    supportsEqualizer = false,
                    onPlaybackSettingsChanged = {
                        emitted += it
                        settings.value = it
                    },
                )
            }
        }

        onNodeWithText(label).assertIsDisplayed().performClick()
        runOnIdle {
            assertEquals(listOf(initial.copy(resumePlaylists = false)), emitted)
            assertEquals(initial.copy(resumePlaylists = false), settings.value)
        }

        onNodeWithText(label).performClick()
        runOnIdle {
            assertEquals(listOf(initial.copy(resumePlaylists = false), initial), emitted)
            assertEquals(initial, settings.value)
        }
    }
}
