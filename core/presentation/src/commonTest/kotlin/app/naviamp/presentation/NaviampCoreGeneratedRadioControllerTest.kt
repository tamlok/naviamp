package app.naviamp.presentation

import app.naviamp.app.*
import app.naviamp.domain.*
import app.naviamp.domain.playback.*
import app.naviamp.domain.provider.*
import app.naviamp.domain.queue.*
import app.naviamp.domain.radio.*
import app.naviamp.domain.settings.*
import app.naviamp.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

class NaviampCoreGeneratedRadioControllerTest {
    @Test fun seededRadioUndoSurvivesItsInitialRefillAndRestoresOrdinaryPlayback() = runTest {
        val fixture = Fixture(backgroundScope)
        val old = PlaybackQueue(listOf(fixture.track("old"), fixture.track("next")), currentIndex = 0)
        fixture.live.replace(fixture.live.state.value.copy(queue = old, currentTrack = old.current,
            progress = PlaybackProgress(37.0, 180.0), playbackState = PlaybackState.Playing))
        assertTrue(fixture.controller.startStation(fixture.station))
        assertEquals(11, fixture.live.state.value.queue.tracks.size)
        assertNotNull(fixture.store.state.value.overlays.queueUndo)
        fixture.undoLastEdit()
        assertEquals(old, fixture.live.state.value.queue)
        assertEquals(37.0, fixture.live.state.value.progress.positionSeconds)
        assertEquals("old", fixture.live.state.value.currentTrack?.id?.value)
        assertNull(fixture.controller.checkpoint())
        assertNull(fixture.store.state.value.overlays.queueUndo)
    }

    @Test fun undoDuringSeedBuildCancelsTheStationBeforeItsLateResult() = runTest {
        val fixture = Fixture(backgroundScope)
        val old = PlaybackQueue(listOf(fixture.track("old"), fixture.track("next")), currentIndex = 0)
        fixture.live.replace(fixture.live.state.value.copy(queue = old, currentTrack = old.current,
            playbackState = PlaybackState.Playing))
        fixture.gate = CompletableDeferred()
        val start = async { fixture.controller.startStation(fixture.station) }
        runCurrent()
        assertEquals("seed", fixture.live.state.value.queue.current?.id?.value)
        fixture.undoLastEdit()
        fixture.gate!!.complete(Unit)
        runCurrent()
        assertFalse(start.await())
        assertEquals(old, fixture.live.state.value.queue)
        assertEquals("old", fixture.live.state.value.currentTrack?.id?.value)
        assertNull(fixture.controller.checkpoint())
    }

    @Test fun finiteBatchUndoRestoresThePausedStationRecipeAndOccurrences() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.startStation(fixture.station)
        fixture.live.updatePlaybackState(PlaybackState.Paused)
        fixture.controller.onPlaybackChanged()
        val oldQueue = fixture.live.state.value.queue
        val oldCheckpoint = fixture.controller.checkpoint()
        fixture.controller.playFinite(listOf(fixture.track("finite")))
        fixture.undoLastEdit()
        assertEquals(oldQueue, fixture.live.state.value.queue)
        assertEquals(oldCheckpoint, fixture.controller.checkpoint())
        assertEquals(PlaybackState.Paused, fixture.live.state.value.playbackState)
        assertEquals("station", fixture.store.state.value.shell.generatedRadio.activeStationId)
        fixture.moveNearEnd()
        fixture.controller.onPlaybackChanged()
        runCurrent()
        assertEquals(listOf("seed", "seed"), fixture.seeds)
        assertEquals(21, fixture.live.state.value.queue.tracks.size)
    }

    @Test fun clearingThenUndoingTheQueueDoesNotRestartCancelledStationWork() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.startStation(fixture.station)
        fixture.gate = CompletableDeferred()
        fixture.moveNearEnd()
        fixture.controller.onPlaybackChanged()
        runCurrent()
        val old = fixture.live.state.value.queue
        val checkpoint = fixture.controller.checkpoint()
        val capture = fixture.undo.capture()
        fixture.controller.onQueueCleared()
        val update = fixture.queue.retainCurrentOnly()
        fixture.undo.record(capture, NaviampQueueUndoKind.ClearedQueue, update.queue)
        fixture.undoLastEdit()
        fixture.gate!!.complete(Unit)
        fixture.controller.onPlaybackChanged(force = true)
        runCurrent()
        assertEquals(old, fixture.live.state.value.queue)
        assertEquals(checkpoint, fixture.controller.checkpoint())
        assertEquals(2, fixture.seeds.size)
    }

    @Test fun undoRestoresTerminalStationWithoutRetryingItsFailedContinuation() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.startStation(fixture.station)
        fixture.radioFailure = IllegalStateException("offline")
        fixture.moveNearEnd()
        fixture.controller.onPlaybackChanged()
        runCurrent()
        val old = fixture.live.state.value.queue
        val checkpoint = fixture.controller.checkpoint()
        fixture.controller.playFinite(listOf(fixture.track("finite")))
        fixture.undoLastEdit()
        fixture.controller.onPlaybackChanged(force = true)
        runCurrent()
        assertEquals(old, fixture.live.state.value.queue)
        assertEquals(checkpoint, fixture.controller.checkpoint())
        assertEquals(2, fixture.seeds.size)
    }

    @Test fun anOrdinaryUnscopedTrackPageCannotProveSelectedLibraryMembership() = runTest {
        val fixture = Fixture(backgroundScope, folderScoped = true, proveFolderScope = false)
        assertFalse(fixture.controller.startStation(fixture.station))
        assertTrue(fixture.live.state.value.queue.tracks.isEmpty())
    }

    @Test fun staleTypedFailuresCannotTerminateTheOwnedStation() = runTest {
        for (failure in listOf(UnsupportedOperationException(), IllegalArgumentException())) {
            val fixture = Fixture(backgroundScope)
            fixture.radioFailure = failure
            val gate = CompletableDeferred<Unit>()
            fixture.gate = gate
            var current = true
            val start = async { fixture.controller.startStation(fixture.station, initialGuard = { current }) }
            runCurrent()
            current = false
            gate.complete(Unit)
            assertFalse(start.await())
            assertEquals("station", fixture.controller.checkpoint()?.station?.id)
            assertEquals(GeneratedRadioStatus.Active, fixture.controller.status)
            assertNull(fixture.store.state.value.overlays.radioNotice)
        }
    }

    @Test fun finitePlaybackCheckpointRestoresItsPauseAndNoAutoplayPolicy() = runTest {
        val fixture = Fixture(backgroundScope)
        val tracks = listOf(fixture.track("same"), fixture.track("same"), fixture.track("last"))
        fixture.controller.playFinite(tracks, 1)
        fixture.live.updatePlaybackState(PlaybackState.Paused)
        val checkpoint = assertNotNull(fixture.controller.checkpoint())
        assertEquals(3, checkpoint.occurrences.map { it.id }.toSet().size)
        val saved = assertNotNull(playbackSessionFromQueue(fixture.live.state.value.queue, 12.0, checkpoint))
        val restored = assertNotNull(saved.restoredTrackSession())
        assertEquals(checkpoint, restored.generatedRadio)
        assertEquals(listOf("same", "same", "last"), restored.tracks.map { it.id.value })
        assertEquals(1, restored.currentIndex)
        assertFalse(planPlaybackSessionRestoreEffects(true, true, true, restored.generatedRadio).startPlayback)
        fixture.controller.restore(restored.generatedRadio)
        fixture.live.updatePlaybackState(PlaybackState.Finished)
        fixture.controller.onPlaybackChanged()
        runCurrent()
        assertTrue(fixture.seeds.isEmpty())
        assertEquals(GeneratedRadioPlaybackMode.FiniteBatch, fixture.controller.checkpoint()?.mode)
    }

    @Test fun previewResolutionNeverRewritesSavedSeedsOrAnchors() = runTest {
        val fixture = Fixture(backgroundScope)
        val recipe = fixture.station.copy(saved = true, seedTracks = listOf(SavedTrack.fromTrack(
            fixture.track("seed").copy(artistId = ArtistId("artist"), albumReleaseYear = 1992))))
        fixture.repository.save(GeneratedRadioLibrary(stations = listOf(recipe)))
        fixture.controller.refreshPreview(recipe)
        assertEquals(recipe, fixture.repository.load().stations.single())
        val resolved = assertNotNull(fixture.repository.load().batches.single().resolvedRecipe)
        assertEquals("artist", resolved.resolvedArtistId)
        assertEquals(1990, resolved.resolvedDecadeStart)
        assertTrue(fixture.live.state.value.queue.tracks.isEmpty())
    }

    @Test fun discardedInitialResultDoesNotTurnStationIntoOrdinaryAutoplay() = runTest {
        val fixture = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        fixture.gate = gate
        var valid = true
        val start = async { fixture.controller.startStation(fixture.station, initialGuard = { valid }) }
        runCurrent()
        valid = false
        gate.complete(Unit)
        assertFalse(start.await())
        assertEquals("station", fixture.controller.checkpoint()?.station?.id)
        assertEquals(listOf("seed"), fixture.live.state.value.queue.tracks.map { it.id.value })
        assertTrue(fixture.controller.ownsQueue)
    }

    @Test fun cancellingTheStartCancelsItsDetachedGenerationBeforeALateResponse() = runTest {
        val fixture = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        fixture.gate = gate
        val start = async { fixture.controller.startStation(fixture.station) }
        runCurrent()
        val queue = fixture.live.state.value.queue
        start.cancel()
        start.join()
        gate.complete(Unit)
        runCurrent()
        assertEquals(queue, fixture.live.state.value.queue)
        assertTrue(fixture.repository.load().batches.isEmpty())
    }

    @Test fun sonicStationIncludesAllSeedsOnlyWhenRequested() = runTest {
        for (include in listOf(false, true)) {
            val fixture = Fixture(backgroundScope, sonic = true)
            val seeds = listOf(fixture.track("seed"), fixture.track("second-seed"))
            val station = fixture.station.copy(kind = GeneratedRadioKind.SonicMix, sonicSimilarity = true,
                seedTracks = seeds.map(SavedTrack::fromTrack), includeSeeds = include)
            assertTrue(fixture.controller.startStation(station, endlessEnabled = false))
            val queue = fixture.live.state.value.queue.tracks
            assertEquals(if (include) 12 else 10, queue.size)
            if (include) assertEquals(seeds, queue.take(2))
            else assertTrue(queue.none { track -> seeds.any { it.id == track.id } })
        }
    }

    @Test fun anUncachedSelectedLibraryResolvesCandidateMembershipBeforePlaying() = runTest {
        val fixture = Fixture(backgroundScope, folderScoped = true)
        assertTrue(fixture.controller.startStation(fixture.station))
        assertEquals(11, fixture.live.state.value.queue.tracks.size)
        assertEquals((1..10).map { "first-$it" }, fixture.live.state.value.queue.tracks.drop(1).map { it.id.value })
    }

    @Test fun nativePreparingStopDoesNotCancelAnExplicitStationRequest() = runTest {
        val fixture = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        fixture.gate = gate
        val start = async { fixture.controller.startStation(fixture.station) }
        runCurrent()
        fixture.live.updatePlaybackState(PlaybackState.Stopped)
        fixture.controller.onPlaybackChanged()
        fixture.live.updatePlaybackState(PlaybackState.Loading)
        fixture.controller.onPlaybackChanged()
        gate.complete(Unit)
        assertTrue(start.await())
        assertEquals(11, fixture.live.state.value.queue.tracks.size)
        assertEquals("station", fixture.controller.checkpoint()?.station?.id)
    }

    @Test fun proactivelyRefillsFromOriginalSeedWithoutRestartingCurrentAudio() = runTest {
        val fixture = Fixture(backgroundScope)
        assertTrue(fixture.controller.startStation(fixture.station))
        assertEquals(11, fixture.live.state.value.queue.tracks.size)
        val initialStarts = fixture.effects.starts
        fixture.moveNearEnd()
        fixture.controller.onPlaybackChanged()
        runCurrent()
        assertEquals(listOf("seed", "seed"), fixture.seeds)
        assertEquals(21, fixture.live.state.value.queue.tracks.size)
        assertEquals(initialStarts, fixture.effects.starts)
        assertEquals("first-8", fixture.live.state.value.currentTrack?.id?.value)
        assertEquals("station", fixture.controller.checkpoint()?.station?.id)
    }

    @Test fun pausedPendingRefillCannotAppendAndResumeCanRequestAgain() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.startStation(fixture.station)
        val gate = CompletableDeferred<Unit>()
        fixture.gate = gate
        fixture.moveNearEnd()
        fixture.controller.onPlaybackChanged()
        runCurrent()
        val queueBefore = fixture.live.state.value.queue
        fixture.live.updatePlaybackState(PlaybackState.Paused)
        fixture.controller.onPlaybackChanged()
        gate.complete(Unit)
        runCurrent()
        assertEquals(queueBefore, fixture.live.state.value.queue)
        assertTrue(fixture.controller.checkpoint()!!.wasPaused)
        fixture.live.updatePlaybackState(PlaybackState.Playing)
        fixture.controller.onPlaybackChanged()
        runCurrent()
        assertEquals(21, fixture.live.state.value.queue.tracks.size)
        assertFalse(fixture.controller.checkpoint()!!.wasPaused)
    }

    @Test fun refreshingPreviewDoesNotPlayAndApplyingPreservesManualDuplicateOccurrence() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.controller.startStation(fixture.station)
        val manualTrack = fixture.live.state.value.queue.tracks[1]
        fixture.queue.playNextTracks(listOf(manualTrack), "manual")
        val before = fixture.live.state.value.queue
        val manual = before.occurrences[1]
        val starts = fixture.effects.starts
        fixture.controller.refreshPreview(fixture.station)
        assertEquals(before, fixture.live.state.value.queue)
        assertEquals(starts, fixture.effects.starts)
        val batch = fixture.repository.load().batches.single()
        fixture.controller.applyBatch(fixture.station, batch)
        val after = fixture.live.state.value.queue
        assertEquals(before.current, after.current)
        assertEquals(manual, after.occurrences[1])
        assertEquals(manualTrack, after.tracks[1])
        assertEquals(starts, fixture.effects.starts)
    }

    @Test fun exactBatchReplayKeepsDuplicatesAndNeverStartsAutoplay() = runTest {
        val fixture = Fixture(backgroundScope)
        val saved = listOf(fixture.track("b"), fixture.track("a"), fixture.track("b"))
        fixture.controller.playBatch(fixture.station, GeneratedRadioBatch("batch", "station", 1, 1, saved.map(SavedTrack::fromTrack)))
        fixture.live.updatePlaybackState(PlaybackState.Finished)
        fixture.controller.onPlaybackChanged()
        runCurrent()
        assertEquals(listOf("b", "a", "b"), fixture.live.state.value.queue.tracks.map { it.id.value })
        assertEquals(emptyList(), fixture.seeds)
        assertEquals(GeneratedRadioPlaybackMode.FiniteBatch, fixture.controller.checkpoint()?.mode)
        assertTrue(fixture.controller.ownsQueue)
    }

    @Test fun clearQueueAndSourceChangeInvalidatePendingGeneration() = runTest {
        for (change in listOf("queue", "source", "library")) {
            val fixture = Fixture(backgroundScope)
            fixture.controller.startStation(fixture.station)
            fixture.gate = CompletableDeferred()
            fixture.moveNearEnd()
            fixture.controller.onPlaybackChanged()
            runCurrent()
            if (change != "queue") {
                if (change == "source") fixture.store.updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "other")) }
                else fixture.folders = listOf("another-folder")
                fixture.controller.resetForSourceChange()
            } else fixture.controller.onQueueCleared()
            fixture.queue.retainCurrentOnly()
            val expected = fixture.live.state.value.queue
            fixture.gate!!.complete(Unit)
            runCurrent()
            assertEquals(expected, fixture.live.state.value.queue)
            assertNull(fixture.controller.checkpoint())
        }
    }

    private class Fixture(scope: CoroutineScope, folderScoped: Boolean = false, sonic: Boolean = false, proveFolderScope: Boolean = true) {
        val store = NaviampCoreStateStore().apply {
            updateShell { it.copy(connectionSettings = it.connectionSettings.copy(currentSourceId = "source")) }
        }
        val live = NaviampLivePlaybackController()
        val queue = NaviampPlaybackQueueCoordinator(live)
        val repository = MemoryGeneratedRadioRepository()
        val seeds = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var radioFailure: Exception? = null
        private var rounds = 0
        var folders = if (folderScoped) listOf("folder") else emptyList()
        val provider = object : MediaProvider by FakeCoreMediaProvider() {
            override val selectedMusicFolderIds get() = folders
            override suspend fun tracksPage(request: MediaPageRequest): MediaPage<Track> =
                request.toMediaPage(if (request.offset == 0) listOf(track("seed")) + (1..10).map { track("first-$it") } else emptyList())
            override suspend fun generatedRadioCatalogPage(request: MediaPageRequest): MediaPage<Track>? =
                if (proveFolderScope) tracksPage(request) else null
            override val capabilities = ProviderCapabilities(supportsStreamingTranscode = false, supportsDownloadTranscode = false,
                supportsArtistRadio = false, supportsAlbumRadio = false, supportsTrackRadio = true, supportsSonicSimilarity = sonic)
            override suspend fun sonicSimilarTrackMatches(trackId: TrackId, count: Int): List<SonicSimilarTrack> =
                instantMixTracks(trackId, count).map { SonicSimilarTrack(it, 0.9) }
            override suspend fun instantMixTracks(trackId: TrackId, count: Int): List<Track> {
                seeds += trackId.value
                gate?.await()
                radioFailure?.let { throw it }
                val prefix = if (++rounds == 1) "first" else "round-$rounds"
                return (1..10).map { track("$prefix-$it") }
            }
        }
        val effects = Effects(live)
        val undo = NaviampCoreQueueUndoController(scope, store, live, queue, effects)
        val station = GeneratedRadioStation("station", "Seed station", GeneratedRadioKind.Track,
            sourceId = "source", libraryScope = provider.cacheNamespace,
            seedTracks = listOf(SavedTrack.fromTrack(track("seed"))))
        val controller = NaviampCoreGeneratedRadioController(scope, store, { provider }, live, queue, effects,
            repository, NaviampRecentRadioStreamController({ emptyList() }, {}), null, { null }, { 10_000L },
            {}, {}, {}, {}, {}, {}, { null }, queueUndo = undo).also { undo.generatedRadio = it }
        fun track(id: String) = Track(TrackId(id), id, artistName = "artist-$id", albumTitle = null,
            durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null)
        fun undoLastEdit() {
            val notice = assertNotNull(store.state.value.overlays.queueUndo)
            undo.dispatch(NaviampCoreCommand.NowPlaying.QueueUndo(NaviampQueueUndoActionRequest(notice.id, NaviampQueueUndoAction.Undo)))
        }
        fun moveNearEnd() {
            val changed = live.state.value.queue.copy(currentIndex = 8)
            live.replace(live.state.value.copy(queue = changed, currentTrack = changed.current, playbackState = PlaybackState.Playing))
        }
    }

    private class Effects(private val live: NaviampLivePlaybackController) : NaviampCorePlaybackEffectPort {
        override val capabilities = NaviampCorePlaybackCapabilities()
        override val playbackSource = PlaybackSource.ProviderStream
        var starts = 0
        override fun pause() = live.updatePlaybackState(PlaybackState.Paused)
        override fun resume() = live.updatePlaybackState(PlaybackState.Playing)
        override fun startOrRestore(): Boolean { resume(); return true }
        override fun stop() = live.updatePlaybackState(PlaybackState.Stopped)
        override fun seek(positionSeconds: Double) = Unit
        override fun replayCurrent(positionSeconds: Double) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun applyQueue(queue: PlaybackQueue, clearPreparedNext: Boolean) = Unit
        override fun applyNavigation(command: PlaybackQueueNavigationCommand) = Unit
        override fun applyRepeatMode(mode: RepeatMode) = Unit
        override fun playQueueSelection(queue: PlaybackQueue, index: Int) { starts++; live.updatePlaybackState(PlaybackState.Playing) }
    }
}
