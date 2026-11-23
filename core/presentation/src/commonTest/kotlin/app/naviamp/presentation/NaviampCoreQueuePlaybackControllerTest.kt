package app.naviamp.presentation

import app.naviamp.app.NaviampLivePlaybackController
import app.naviamp.app.NaviampPlaybackQueueCoordinator
import app.naviamp.app.NaviampPlaylistBookmarkController
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.playback.PlaybackProfile
import app.naviamp.domain.playback.PlaybackProfileAssignment
import app.naviamp.domain.playback.PlaybackProfileRepository
import app.naviamp.domain.playback.PlaybackProfileTarget
import app.naviamp.domain.playback.PlaybackProfileTargetType
import app.naviamp.domain.playback.PlaybackReplayGainMode
import app.naviamp.domain.playback.PlaybackSource
import app.naviamp.domain.playback.PlaybackTransitionMode
import app.naviamp.domain.playback.PlaybackQueueNavigationCommand
import app.naviamp.domain.playback.MemoryPlaylistPlaybackBookmarkRepository
import app.naviamp.domain.playback.PlaylistPlaybackBookmark
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.playback.PlaybackState
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.domain.queue.RepeatMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaviampCoreQueuePlaybackControllerTest {
    @Test
    fun switchingPlaylistsResumesEachCanonicalTrackAndClockBeforeStartingAudio() {
        val fixture = fixture()
        val first = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "first")
        val second = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "second")
        fixture.controller.play(testTracks(), index = 1, groupTarget = first)
        fixture.live.updatePlaybackState(PlaybackState.Playing)
        fixture.live.updateProgress(PlaybackProgress(42.25, 180.0))
        fixture.controller.play(listOf(testTrack("other")), groupTarget = second, resumePlaylist = true)
        fixture.live.updateProgress(PlaybackProgress(19.5, 180.0))

        fixture.controller.play(testTracks(), groupTarget = first, resumePlaylist = true)

        assertEquals(1, fixture.live.state.value.queue.currentIndex)
        assertEquals(TrackId("two"), fixture.live.state.value.currentTrack?.id)
        assertEquals(42.25, fixture.live.state.value.progress.positionSeconds)
        assertEquals(TrackId("two"), fixture.effects.startedTrack)
        assertEquals(42.25, fixture.effects.startPosition)
        fixture.controller.play(listOf(testTrack("other")), groupTarget = second, resumePlaylist = true)
        assertEquals(TrackId("other"), fixture.effects.startedTrack)
        assertEquals(19.5, fixture.effects.startPosition)
    }

    @Test
    fun disabledResumeAndExplicitTrackSelectionStartFreshButKeepRecording() {
        var enabled = false
        val fixture = fixture(resumePlaylists = { enabled })
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "playlist")
        fixture.bookmarks.saveBookmark("source-1", "playlist", PlaylistPlaybackBookmark(TrackId("two"), 0, 51.0))
        fixture.controller.play(testTracks(), groupTarget = target, resumePlaylist = true)
        assertEquals(TrackId("one"), fixture.effects.startedTrack)
        assertEquals(0.0, fixture.effects.startPosition)
        fixture.live.updatePlaybackState(PlaybackState.Playing)
        fixture.live.updateProgress(PlaybackProgress(23.0, 180.0))
        enabled = true
        fixture.controller.play(testTracks(), groupTarget = target, resumePlaylist = true)
        assertEquals(TrackId("one"), fixture.effects.startedTrack)
        assertEquals(23.0, fixture.effects.startPosition)

        fixture.controller.play(testTracks(), index = 1, groupTarget = target)
        assertEquals(TrackId("two"), fixture.effects.startedTrack)
        assertEquals(0.0, fixture.effects.startPosition)
    }

    @Test
    fun shuffleNeverAppliesABookmarkedOffset() {
        val fixture = fixture()
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "playlist")
        fixture.bookmarks.saveBookmark("source-1", "playlist", PlaylistPlaybackBookmark(TrackId("two"), 0, 51.0))
        fixture.controller.play(testTracks(), shuffle = true, groupTarget = target, resumePlaylist = true)
        assertEquals(0, fixture.live.state.value.queue.currentIndex)
        assertEquals(0.0, fixture.effects.startPosition)
    }

    @Test
    fun explicitDuplicateSelectionAtZeroUpdatesItsOccurrenceBeforeAnotherClockTick() {
        val fixture = fixture()
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "duplicates")
        val track = testTrack("same")
        val tracks = listOf(track, track)
        fixture.controller.play(tracks, groupTarget = target)
        fixture.live.updatePlaybackState(PlaybackState.Playing)
        fixture.controller.play(tracks, index = 1, groupTarget = target)
        fixture.live.updatePlaybackState(PlaybackState.Paused)
        assertEquals(PlaylistPlaybackBookmark(track.id, 1, 0.0),
            fixture.bookmarks.loadBookmark("source-1", "duplicates"))
    }

    @Test
    fun configuredPlaylistLaunchCreatesProfiledQueueGroup() {
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "playlist-1")
        val profile = PlaybackProfile(
            transitionMode = PlaybackTransitionMode.Gapless,
            replayGainMode = PlaybackReplayGainMode.Album,
        )
        val fixture = fixture(mapOf(target to profile))

        assertTrue(fixture.controller.play(testTracks(), groupTarget = target, groupLabel = "Live set"))

        val queue = fixture.live.state.value.queue
        assertEquals(1, queue.groups.size)
        assertEquals(target, queue.groups.single().target)
        assertEquals(profile, queue.groups.single().profile)
        assertEquals(0, queue.groups.single().startIndex)
        assertEquals(2, queue.groups.single().endIndexExclusive)
        assertEquals(queue, fixture.effects.appliedQueue)
    }

    @Test
    fun unconfiguredPlaylistAndShuffledLaunchRetainContextWithoutApplyingProfile() {
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "playlist-1")
        val fixture = fixture()

        fixture.controller.play(testTracks(), groupTarget = target, groupLabel = "Playlist")
        assertEquals("Playlist", fixture.live.state.value.queue.groups.single().label)
        assertEquals(PlaybackProfile(), fixture.live.state.value.queue.groups.single().profile)

        fixture.controller.play(
            testTracks(),
            shuffle = true,
            groupTarget = target,
            groupLabel = "Playlist",
        )
        assertEquals("Playlist", fixture.live.state.value.queue.groups.single().label)
    }

    @Test
    fun addingConfiguredAlbumToQueueCarriesItsProfile() {
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Album, "album-1")
        val profile = PlaybackProfile(transitionMode = PlaybackTransitionMode.Gapless)
        val fixture = fixture(mapOf(target to profile))
        fixture.live.replace(
            fixture.live.state.value.copy(
                currentTrack = testTrack("current"),
                queue = PlaybackQueue(listOf(testTrack("current")), currentIndex = 0),
            ),
        )

        val update = fixture.controller.addToQueue(testTracks(), target, "Profiled album")

        val group = update.queue.groups.single()
        assertEquals(target, group.target)
        assertEquals(profile, group.profile)
        assertEquals(1, group.startIndex)
        assertEquals(3, group.endIndexExclusive)
    }

    @Test
    fun playingConfiguredPlaylistNextCarriesItsProfile() {
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Playlist, "playlist-1")
        val profile = PlaybackProfile(replayGainMode = PlaybackReplayGainMode.Album)
        val fixture = fixture(mapOf(target to profile))
        fixture.live.replace(
            fixture.live.state.value.copy(
                currentTrack = testTrack("current"),
                queue = PlaybackQueue(listOf(testTrack("current"), testTrack("later")), currentIndex = 0),
            ),
        )

        val update = fixture.controller.playNext(testTracks(), target, "Profiled playlist")

        val group = update.queue.groups.single()
        assertEquals(target, group.target)
        assertEquals(profile, group.profile)
        assertEquals(1, group.startIndex)
        assertEquals(3, group.endIndexExclusive)
    }

    @Test
    fun addingUnconfiguredCollectionRetainsItsContext() {
        val target = PlaybackProfileTarget(PlaybackProfileTargetType.Album, "album-1")
        val fixture = fixture()

        val update = fixture.controller.addToQueue(testTracks(), target, "Album")

        assertEquals("Album", update.queue.groups.single().label)
        assertEquals(PlaybackProfile(), update.queue.groups.single().profile)
    }
}

private data class QueuePlaybackFixture(
    val live: NaviampLivePlaybackController,
    val effects: QueuePlaybackEffects,
    val controller: NaviampCoreQueuePlaybackController,
    val bookmarks: MemoryPlaylistPlaybackBookmarkRepository,
)

private fun fixture(
    profiles: Map<PlaybackProfileTarget, PlaybackProfile> = emptyMap(),
    resumePlaylists: () -> Boolean = { true },
): QueuePlaybackFixture {
    val live = NaviampLivePlaybackController()
    val effects = QueuePlaybackEffects()
    val bookmarks = MemoryPlaylistPlaybackBookmarkRepository()
    val bookmarkController = NaviampPlaylistBookmarkController(bookmarks)
    live.observe { bookmarkController.observe("source-1", it, nowMillis = 1_000L) }
    val controller = NaviampCoreQueuePlaybackController(
        playback = live,
        queue = NaviampPlaybackQueueCoordinator(live),
        effects = effects,
        publishNowPlaying = {},
        openNowPlaying = { effects.playerOpenCount++ },
        activeSourceId = { "source-1" },
        profiles = MapPlaybackProfileRepository(profiles),
        playlistBookmarks = bookmarkController,
        resumePlaylists = resumePlaylists,
    )
    return QueuePlaybackFixture(live, effects, controller, bookmarks)
}

private class MapPlaybackProfileRepository(
    private val profiles: Map<PlaybackProfileTarget, PlaybackProfile>,
) : PlaybackProfileRepository {
    override fun playbackProfile(sourceId: String, target: PlaybackProfileTarget) = profiles[target]

    override fun playbackProfiles(sourceId: String) = profiles.map { PlaybackProfileAssignment(it.key, it.value) }

    override fun savePlaybackProfile(sourceId: String, target: PlaybackProfileTarget, profile: PlaybackProfile?) = Unit
}

private class QueuePlaybackEffects : NaviampCorePlaybackEffectPort {
    override val capabilities = NaviampCorePlaybackCapabilities()
    override val playbackSource = PlaybackSource.ProviderStream
    var appliedQueue: PlaybackQueue? = null
    var playerOpenCount = 0
    var pauses = 0
    var resumes = 0
    var startedTrack: TrackId? = null
    var startPosition = 0.0
    private var restoredQueue: PlaybackQueue? = null

    override fun pause() { pauses++ }
    override fun resume() { resumes++ }
    override fun startOrRestore(): Boolean {
        startedTrack = restoredQueue?.current?.id
        return restoredQueue?.current != null
    }
    override fun seek(positionSeconds: Double) = Unit
    override fun replayCurrent(positionSeconds: Double) = Unit
    override fun setVolume(percent: Int) = Unit
    override fun stop() = Unit
    override fun applyQueue(queue: PlaybackQueue, clearPreparedNext: Boolean) { appliedQueue = queue }
    override fun restoreQueue(queue: PlaybackQueue, startPositionSeconds: Double?) {
        restoredQueue = queue
        startPosition = startPositionSeconds ?: 0.0
    }
    override fun applyNavigation(command: PlaybackQueueNavigationCommand) = Unit
    override fun applyRepeatMode(mode: RepeatMode) = Unit
    override fun playQueueSelection(queue: PlaybackQueue, index: Int) {
        startedTrack = queue.tracks[index].id
        startPosition = 0.0
    }
}

private fun testTracks() = listOf("one", "two").map { id ->
    testTrack(id)
}

private fun testTrack(id: String) = Track(
        id = TrackId(id),
        title = id,
        artistName = "Artist",
        albumTitle = "Album",
        durationSeconds = 180,
        coverArtId = null,
        audioInfo = null,
        replayGain = null,
    )
