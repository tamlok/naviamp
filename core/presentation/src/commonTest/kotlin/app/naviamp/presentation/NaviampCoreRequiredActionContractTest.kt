package app.naviamp.presentation

import app.naviamp.domain.smartplaylist.SmartPlaylistCondition
import app.naviamp.domain.smartplaylist.SmartPlaylistDefinition
import app.naviamp.domain.smartplaylist.SmartPlaylistOperator
import app.naviamp.domain.smartplaylist.SmartPlaylistValue
import app.naviamp.ui.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NaviampCoreRequiredActionContractTest {
    @Test
    fun everyRequiredSuspendingActionTerminatesInOneTypedCoreCommand() = runTest {
        val definition = smartDefinition()
        val handler = CompleteActionRecordingHandler(
            result = NaviampCoreCommandResult.SmartPlaylistLoaded(definition),
        )
        val actions = createNaviampCoreActions(handler).shell
        val playlist = SharedMediaItemUi("playlist", "Playlist", "")
        val track = SharedTrackRowUi("track", "Track", "Artist")

        actions.playlistsActions.smartPlaylist.apply {
            onSave(definition)
            onUpdate(playlist, definition)
            onSaveWithPassword(definition, "password")
            onUpdateWithPassword(playlist, definition, "password")
            assertEquals(definition, onLoad(playlist))
            assertEquals(definition, onLoadWithPassword(playlist, "password"))
        }
        actions.playlistDetailActions.onUpdateStandardPlaylist(playlist, listOf(track), listOf(track))

        assertEquals(7, handler.executed.size)
        assertIs<NaviampCoreCommand.SmartPlaylist.Save>(handler.executed[0])
        assertIs<NaviampCoreCommand.SmartPlaylist.Update>(handler.executed[1])
        assertIs<NaviampCoreCommand.SmartPlaylist.Save>(handler.executed[2])
        assertIs<NaviampCoreCommand.SmartPlaylist.Update>(handler.executed[3])
        assertIs<NaviampCoreCommand.SmartPlaylist.Load>(handler.executed[4])
        assertIs<NaviampCoreCommand.SmartPlaylist.Load>(handler.executed[5])
        assertEquals(listOf(track.id), assertIs<NaviampCoreCommand.Playlists.UpdateTracks>(handler.executed[6]).expectedTrackIds)
    }

    @Test
    fun osAvailabilityOnlyAddsPickerActionsAndNeverRemovesProductActions() {
        val unavailableHandler = CompleteActionRecordingHandler()
        val availableHandler = CompleteActionRecordingHandler()
        val unavailable = createNaviampCoreActions(unavailableHandler)
        val available = createNaviampCoreActions(
            availableHandler,
            NaviampCoreActionAvailability(true, true, true, true),
        )

        unavailable.shell.navigationActions.onRouteSelected(SharedRoute.Home)
        available.shell.navigationActions.onRouteSelected(SharedRoute.Home)
        unavailable.shell.homeActions.onRefresh()
        available.shell.homeActions.onRefresh()
        unavailable.shell.nowPlayingActions.onPlaybackAction(
            NowPlayingPlaybackActionRequest(NowPlayingPlaybackAction.Pause),
        )
        available.shell.nowPlayingActions.onPlaybackAction(
            NowPlayingPlaybackActionRequest(NowPlayingPlaybackAction.Pause),
        )

        assertEquals(unavailableHandler.dispatched, availableHandler.dispatched)
        assertEquals(null, unavailable.settingsSync.onImportFile)
        assertNotNull(available.settingsSync.onImportFile)
    }

    private fun smartDefinition() = SmartPlaylistDefinition(
        name = "Smart",
        rules = listOf(
            SmartPlaylistCondition(SmartPlaylistOperator.Contains, "genre", SmartPlaylistValue.Text("ambient")),
        ),
    )
}

private class CompleteActionRecordingHandler(
    private val result: NaviampCoreCommandResult = NaviampCoreCommandResult.Completed,
) : NaviampCoreCommandHandler {
    val dispatched = mutableListOf<NaviampCoreCommand>()
    val executed = mutableListOf<NaviampCoreCommand>()

    override fun dispatch(command: NaviampCoreCommand) {
        dispatched += command
    }

    override suspend fun execute(command: NaviampCoreCommand): NaviampCoreCommandResult {
        executed += command
        return result
    }
}
