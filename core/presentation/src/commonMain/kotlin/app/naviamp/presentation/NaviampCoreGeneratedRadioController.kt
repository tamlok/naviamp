package app.naviamp.presentation

import app.naviamp.app.NaviampLivePlaybackController
import app.naviamp.app.NaviampPlaybackQueueCoordinator
import app.naviamp.app.NaviampRadioContinuationController
import app.naviamp.domain.AlbumId
import app.naviamp.domain.ArtistId
import app.naviamp.domain.Track
import app.naviamp.domain.TrackId
import app.naviamp.domain.cache.LocalLibraryIndexRepository
import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.eraReleaseYear
import app.naviamp.domain.playback.PlaybackState
import app.naviamp.domain.queue.RepeatMode
import app.naviamp.domain.radio.*
import app.naviamp.domain.settings.*
import app.naviamp.ui.*
import kotlinx.coroutines.*
import kotlin.uuid.Uuid

/** One shared station owner; preview work never owns playback, and native adapters only execute effects. */
class NaviampCoreGeneratedRadioController(
    private val scope: CoroutineScope,
    private val stateStore: NaviampCoreStateStore,
    private val providerSource: NaviampCoreMediaProviderSource,
    private val playback: NaviampLivePlaybackController,
    private val queue: NaviampPlaybackQueueCoordinator,
    private val effects: NaviampCorePlaybackEffectPort,
    private val repository: GeneratedRadioRepository,
    private val recents: app.naviamp.app.NaviampRecentRadioStreamController,
    private val libraryIndex: LocalLibraryIndexRepository?,
    private val sourceIdentity: () -> NaviampConnectSourceIdentity?,
    private val nowMillis: () -> Long,
    private val publishNowPlaying: () -> Unit,
    private val openNowPlaying: () -> Unit,
    private val settingsChanged: () -> Unit,
    private val openPage: (String) -> Unit,
    private val closePage: () -> Unit,
    private val openSeed: suspend (String) -> Unit,
    private val builderRecipe: suspend (String) -> GeneratedRadioStation?,
) : NaviampCoreCommandController {
    private val continuation = NaviampRadioContinuationController()
    private var current: GeneratedRadioCheckpoint? = null
    private var work: Deferred<Boolean>? = null
    private var wakeup: Job? = null
    private var previewGeneration = 0L
    private var editingQueue = false
    private var terminal = false
    private var observedQueue: app.naviamp.domain.queue.PlaybackQueue? = null
    private var observedState: PlaybackState? = null
    private var observedSeek = -1L
    private var observedRepeat: RepeatMode? = null
    private var sourceEpoch = 0L
    private var requestedFrontier: String? = null
    var routeStation: suspend (GeneratedRadioStation, Boolean) -> Boolean = { _, _ -> false }
    var routeBatch: (GeneratedRadioStation, GeneratedRadioBatch) -> Boolean = { _, _ -> false }
    var onChanged: () -> Unit = {}
    private var liveStatus = GeneratedRadioStatus.Inactive

    val status: GeneratedRadioStatus get() = liveStatus
    val ownsQueue: Boolean get() = current != null
    val activeStation: GeneratedRadioStation? get() = current?.station

    fun checkpoint(): GeneratedRadioCheckpoint? {
        val context = current ?: return null
        val live = playback.state.value
        val paused = live.playbackState == PlaybackState.Paused || context.wasPaused && live.playbackState == PlaybackState.Idle
        if (context.wasPaused == paused && context.occurrences === live.queue.occurrences) return context
        return context.copy(wasPaused = paused, occurrences = live.queue.occurrences).also { current = it }
    }

    private fun previewBatch(): GeneratedRadioBatch? =
        stateStore.state.value.shell.generatedRadio.takeIf { it.open && it.sourceAvailable }?.batch

    fun previewTracksFor(trackId: String): List<Track> {
        val tracks = previewBatch()?.tracks.orEmpty()
        return if (tracks.any { it.id == trackId }) tracks.map(SavedTrack::toTrack) else emptyList()
    }

    fun playPreviewSelection(request: app.naviamp.ui.SharedTrackRowActionRequest, tracks: List<Track>): Boolean {
        if (request.action != app.naviamp.ui.SharedTrackRowAction.Select ||
            previewBatch()?.tracks?.any { it.id == request.track.id } != true) return false
        playFinite(tracks, tracks.indexOfFirst { it.id.value == request.track.id })
        return true
    }

    fun retry(stationId: String, revision: Long): Boolean {
        if (current?.station?.let { it.id == stationId && it.revision == revision } != true) return false
        terminal = false; requestedFrontier = null
        onPlaybackChanged(force = true)
        return true
    }

    fun showTransferred(station: GeneratedRadioStation) {
        openPage(station.id)
        stateStore.updateShell { it.copy(generatedRadio = it.generatedRadio.copy(open = true, station = station,
            sourceAvailable = available(station), offline = providerSource.current() == null)) }
    }

    suspend fun resolveTransferred(station: GeneratedRadioStation, identity: NaviampConnectSourceIdentity, requireRecipe: Boolean = true): GeneratedRadioStation? {
        if (sourceIdentity()?.isCompatibleWith(identity) != true || station.sourceIdentity?.isCompatibleWith(identity) != true) return null
        val provider = providerSource.current() ?: return null
        if (!requireRecipe) return station.copy(sourceId = activeSourceId(), sourceIdentity = identity.normalized(), libraryScope = provider.cacheNamespace)
        return try {
            val seeds = station.seedTracks.map { seed -> provider.track(TrackId(seed.id))?.let(SavedTrack::fromTrack) ?: return null }
            val artists = station.artists.map { provider.artist(ArtistId(it.id)).artist.let(SavedArtist::fromArtist) }
            val albums = station.albums.map { provider.album(AlbumId(it.id)).album.let(SavedAlbum::fromAlbum) }
            if (!providerSource.isCurrent(provider) || sourceIdentity()?.isCompatibleWith(identity) != true) return null
            val local = resolveRecipe(station.copy(sourceId = activeSourceId(), sourceIdentity = identity.normalized(), libraryScope = provider.cacheNamespace,
                seedTracks = seeds, artists = artists, albums = albums))
            RadioService(provider).validateStation(local)
            if (local.sonicSimilarity && !provider.capabilities.supportsSonicSimilarity) return null
            local
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    override fun dispatch(command: NaviampCoreCommand): NaviampCoreImmediateCommandResult = when (command) {
        is NaviampCoreCommand.GeneratedRadio, is NaviampCoreCommand.GeneratedRadioPreferencesChanged -> NaviampCoreImmediateCommandResult.Deferred
        else -> NaviampCoreImmediateCommandResult.Unhandled
    }

    override suspend fun execute(command: NaviampCoreCommand): NaviampCoreCommandResult? {
        if (command is NaviampCoreCommand.GeneratedRadioPreferencesChanged) {
            updateLibrary { it.copy(preferences = command.preferences.normalized()) }
            publish()
            return NaviampCoreCommandResult.Completed
        }
        val request = (command as? NaviampCoreCommand.GeneratedRadio)?.request ?: return null
        if (request.action == GeneratedRadioAction.Open && request.value == "RecentRadio") migrateLegacy()
        val station = request.stationId?.let(::station) ?: stateStore.state.value.shell.generatedRadio.station
        when (request.action) {
            GeneratedRadioAction.Close -> { previewGeneration++; closePage() }
            GeneratedRadioAction.Open -> {
                val selected = if (request.stationId == null) request.value?.let { builderRecipe(it) }?.let(::bind) else station
                if (selected != null) { rememberStation(selected); open(selected.id) }
                else publish(GeneratedRadioStatus.InvalidRecipe)
            }
            GeneratedRadioAction.StartFromMix -> builderRecipe(request.value ?: "SonicMix")?.let { startStation(bind(it)) }
                ?: publish(GeneratedRadioStatus.InvalidRecipe)
            GeneratedRadioAction.Start -> station?.let { startStation(it) }
            GeneratedRadioAction.PlayBatch -> station?.let { selected -> batch(selected.id)?.let { playBatch(selected, it) } }
            GeneratedRadioAction.Refresh -> station?.let { refreshPreview(it) }
            GeneratedRadioAction.Apply -> station?.let { selected -> batch(selected.id)?.let { applyBatch(selected, it) } }
            GeneratedRadioAction.PlayNext, GeneratedRadioAction.AddToQueue -> station?.let { selected -> batch(selected.id)?.let { saved ->
                val tracks = saved.tracks.map(SavedTrack::toTrack)
                val update = if (request.action == GeneratedRadioAction.PlayNext) queue.playNextTracks(tracks, "tracks") else queue.appendTracks(tracks, "tracks")
                if (update.tracksChanged) effects.applyQueue(update.queue, clearPreparedNext = true)
                publishNowPlaying()
            } }
            GeneratedRadioAction.SavePlaylist -> station?.let { selected -> batch(selected.id)?.let { saved ->
                try {
                    providerSource.current()?.createPlaylist(request.value?.trim()?.takeIf(String::isNotEmpty) ?: selected.name, saved.tracks.map { TrackId(it.id) })
                    publish(GeneratedRadioStatus.Active)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { publish(GeneratedRadioStatus.Failed) }
            } }
            GeneratedRadioAction.Save, GeneratedRadioAction.Rename -> station?.let { selected ->
                val name = request.value?.trim()?.takeIf(String::isNotEmpty) ?: selected.name
                rememberStation(selected.copy(name = name, saved = true,
                    updatedAtEpochMillis = nowMillis()))
                publish()
            }
            GeneratedRadioAction.Delete -> station?.let { selected ->
                updateLibrary { library -> library.copy(stations = library.stations.filterNot { it.id == selected.id }, batches = library.batches.filterNot { it.stationId == selected.id }) }
                closePage(); publish()
            }
            GeneratedRadioAction.OpenSeed -> request.value?.let { openSeed(it) }
            GeneratedRadioAction.Retry -> {
                if (current?.station?.id == station?.id) { terminal = false; requestedFrontier = null; onPlaybackChanged() }
                else station?.let { refreshPreview(it) }
            }
        }
        return NaviampCoreCommandResult.Completed
    }

    fun bind(recipe: GeneratedRadioStation): GeneratedRadioStation {
        val provider = providerSource.current()
        val seed = recipe.seedTracks.firstOrNull()?.toTrack()
        return recipe.copy(
            id = recipe.id.ifBlank { Uuid.random().toString() },
            sourceId = activeSourceId(), sourceIdentity = sourceIdentity(), libraryScope = provider?.cacheNamespace.orEmpty(),
            resolvedArtistId = recipe.resolvedArtistId ?: seed?.artistId?.value,
            resolvedDecadeStart = recipe.resolvedDecadeStart ?: seed?.eraReleaseYear?.let { it / 10 * 10 },
            updatedAtEpochMillis = nowMillis(),
        ).normalized()
    }

    suspend fun startSeeded(request: SeededRadioRequest, preserveCurrent: Boolean): Boolean {
        val settings = stateStore.state.value.shell.playback.settings
        val expectedQueue = playback.state.value.queue
        val expectedSource = activeSourceId()
        val template = request.recipe ?: recipeFromRecent(request.recentRadioStream)
            ?: return false
        val recipe = bind(template.copy(
            seedTracks = (listOf(SavedTrack.fromTrack(request.seedTrack)) + template.seedTracks).distinctBy { it.id },
            tuning = settings.radioTuning, batchSize = settings.radioMinimumTracks,
            sonicSimilarity = settings.effectiveSonicSimilarityEnabled() && template.kind !in setOf(GeneratedRadioKind.Library, GeneratedRadioKind.Genres, GeneratedRadioKind.Decade),
        ))
        val started = startStation(recipe, preserveCurrent, initialGuard = {
            val shell = stateStore.state.value.shell
            shell.connectionSettings.currentSourceId == expectedSource && !shell.connect.remotePlaybackAuthorityActive &&
                shell.playback.settings.let { it.radioTuning == settings.radioTuning && it.sonicSimilarityEnabled == settings.sonicSimilarityEnabled && it.radioMinimumTracks == settings.radioMinimumTracks } &&
                (!preserveCurrent || expectedQueue.current?.id != request.seedTrack.id || playback.state.value.queue === expectedQueue)
        })
        if (started && current?.station?.id == recipe.id) {
            recents.remember(request.recentRadioStream.copy(generationOptions = RecentRadioGenerationOptions(
                recipe.batchSize, recipe.tuning, recipe.sonicSimilarity)), batch(recipe.id)?.tracks.orEmpty().map(SavedTrack::toTrack), activeSourceId())
        }
        return started
    }

    suspend fun startStation(requested: GeneratedRadioStation, preserveCurrent: Boolean = false, routeRemote: Boolean = true,
        endlessEnabled: Boolean = repository.load().preferences.endlessEnabled, initialGuard: () -> Boolean = { true }): Boolean {
        if (routeRemote && routeStation(requested, endlessEnabled)) return true
        if (!available(requested)) { publish(GeneratedRadioStatus.Unsupported); return false }
        if (!requested.regenerable) { publish(GeneratedRadioStatus.InvalidRecipe); return false }
        val provider = providerSource.current() ?: return false
        val epoch = ++sourceEpoch
        cancelWork()
        val resolved = try {
            resolveRecipe(executionRecipe(requested).copy(sourceId = activeSourceId())).also {
                RadioService(provider).validateStation(it)
                if (it.sonicSimilarity && !provider.capabilities.supportsSonicSimilarity) throw UnsupportedOperationException()
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: UnsupportedOperationException) { publish(GeneratedRadioStatus.Unsupported); return false }
        catch (_: Exception) { publish(GeneratedRadioStatus.InvalidRecipe); return false }
        if (epoch != sourceEpoch || !providerSource.isCurrent(provider) || !initialGuard()) return false
        val first = seedPrefix(resolved).firstOrNull()
        val live = playback.state.value
        val retain = preserveCurrent && first != null && live.currentTrack?.id == first.id && live.queue.current?.id == first.id
        continuation.start(first?.id, refilling = true)
        terminal = false
        requestedFrontier = null
        observedQueue = null
        current = GeneratedRadioCheckpoint(resolved, endlessEnabled = endlessEnabled,
            wasPaused = retain && live.playbackState == PlaybackState.Paused)
        if (!retain && first != null) {
            editingQueue = true
            try {
                val update = queue.startGeneratedQueue(listOf(first), resolved.id, newBatchId())
                effects.applyProviderQueue(update.queue, update.clearPreparedNext)
                playback.updateCurrentTrack(first)
                effects.playQueueSelection(update.queue, 0)
            } finally { editingQueue = false }
            openNowPlaying()
        }
        rememberStation(resolved)
        val build = launchBuild(resolved, epoch, initial = true, retainCurrent = retain, requestIsCurrent = initialGuard)
        val result = try { build.await() }
        catch (cancelled: CancellationException) {
            build.cancel()
            if (work === build) { work = null; terminal = true; continuation.finishRefill(continuation.state.sessionId) }
            currentCoroutineContext().ensureActive()
            if (epoch == sourceEpoch) throw cancelled
            false
        }
        if (result) openNowPlaying()
        return result
    }

    private fun launchBuild(station: GeneratedRadioStation, epoch: Long, initial: Boolean, retainCurrent: Boolean = false,
        requestIsCurrent: () -> Boolean = { true }): Deferred<Boolean> {
        val provider = providerSource.current()
        val token = continuation.state.sessionId
        fun isCurrent() = epoch == sourceEpoch && continuation.isCurrent(token) && provider != null &&
            providerSource.isCurrent(provider) && available(station) && requestIsCurrent() &&
            !stateStore.state.value.shell.connect.remotePlaybackAuthorityActive
        lateinit var job: Deferred<Boolean>
        job = scope.async(start = CoroutineStart.LAZY) {
            publish(GeneratedRadioStatus.Fetching)
            try {
                val live = playback.state.value
                val excluded = excludedTracks(station) + if (initial) station.seedTracks.map { TrackId(it.id) } else live.queue.tracks.map { it.id }
                val tracks = RadioService(checkNotNull(provider), onSimilarityResult = { result ->
                    if (isCurrent()) recordDiagnostics(result.radioDiagnostics())
                }).stationTracks(station, excluded, if (initial) live.currentTrack else live.queue.tracks.lastOrNull(), nowMillis()).let { scopedTracks(it) }
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) {
                    return@async false
                }
                if (tracks.isEmpty()) { terminal = true; recordDiagnostics(RadioBuildDiagnostics(RadioBuildOutcome.Empty)); publish(GeneratedRadioStatus.Exhausted); return@async false }
                val batchId = newBatchId()
                editingQueue = true
                try {
                    val prefix = if (initial) seedPrefix(station) else emptyList()
                    val first = prefix.firstOrNull()
                    val added = if (initial) prefix.drop(1) + tracks else tracks
                    if (initial && first == null) {
                        val update = queue.startGeneratedQueue(tracks, station.id, batchId)
                        effects.applyProviderQueue(update.queue, update.clearPreparedNext)
                        playback.updateCurrentTrack(update.queue.current)
                        effects.playQueueSelection(update.queue, update.queue.currentIndex)
                    } else if (initial && retainCurrent) {
                        val update = queue.replaceGeneratedUpcomingOccurrences(added, station.id, batchId)
                        effects.applyProviderQueue(update.queue, update.clearPreparedNext)
                    } else {
                        val update = queue.appendGeneratedBatch(added, station.id, batchId)
                        if (update.tracksChanged) effects.applyProviderQueue(update.queue, clearPreparedNext = false)
                    }
                    current = current?.copy(seenTrackIds = (current!!.seenTrackIds + tracks.map { it.id.value }).distinct().takeLast(2_000))
                    val saved = prefix + tracks
                    rememberBatch(station, GeneratedRadioBatch(batchId, station.id, station.revision, nowMillis(), saved.distinctBy { it.id }.map(SavedTrack::fromTrack)))
                    if (playback.state.value.playbackState == PlaybackState.Finished) {
                        val next = queue.selectNext()
                        if (next.changed) {
                            playback.updateCurrentTrack(next.queue.current)
                            effects.applyAutomaticNavigation(app.naviamp.domain.playback.PlaybackQueueNavigationCommand.Next)
                        }
                    }
                } finally { editingQueue = false }
                publish(GeneratedRadioStatus.Active)
                publishNowPlaying()
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: UnsupportedOperationException) {
                if (isCurrent()) { terminal = true; publish(GeneratedRadioStatus.Unsupported) }
                false
            }
            catch (_: IllegalArgumentException) {
                if (isCurrent()) { terminal = true; publish(GeneratedRadioStatus.InvalidRecipe) }
                false
            }
            catch (failure: Exception) {
                if (isCurrent()) {
                    terminal = true
                    recordDiagnostics(RadioBuildDiagnostics(if (failure.isSimilarityTimeout()) RadioBuildOutcome.TimedOut else RadioBuildOutcome.Failed))
                    publish(GeneratedRadioStatus.Failed)
                }
                false
            }
            finally {
                if (work === job && epoch == sourceEpoch) {
                    continuation.finishRefill(token)
                    work = null
                    if (status == GeneratedRadioStatus.Fetching) publish(GeneratedRadioStatus.Active)
                    if (requestIsCurrent()) onPlaybackChanged(force = true)
                }
            }
        }
        work = job
        job.start()
        return job
    }

    private fun recordDiagnostics(diagnostics: RadioBuildDiagnostics?) {
        stateStore.update { state -> state.copy(
            overlays = state.overlays.copy(radioNotice = diagnostics),
            shell = state.shell.copy(playback = state.shell.playback.copy(radioDiagnostics = diagnostics)),
        ) }
    }

    fun onPlaybackChanged(force: Boolean = false) {
        if (editingQueue) return
        val live = playback.state.value
        val context = current ?: return
        if (stateStore.state.value.shell.connect.remotePlaybackAuthorityActive) { cancelWork(); return }
        if (live.currentStation != null) { stop(); return }
        // A queue replaced outside the station owner has no station-generated occurrences.
        val previousQueue = observedQueue
        if (previousQueue != null && previousQueue !== live.queue && previousQueue.occurrences.isNotEmpty() &&
            live.queue.occurrences.none { next -> previousQueue.occurrences.any { it.id == next.id } }) { stop(); return }
        if (!force && observedQueue === live.queue && observedState == live.playbackState && observedSeek == live.progress.seekGeneration && observedRepeat == live.repeatMode) return
        observedQueue = live.queue; observedState = live.playbackState; observedSeek = live.progress.seekGeneration; observedRepeat = live.repeatMode
        if (live.playbackState == PlaybackState.Paused) {
            current = context.copy(wasPaused = true)
            cancelWork(); requestedFrontier = null; publish(GeneratedRadioStatus.Active); return
        }
        if (live.playbackState != PlaybackState.Playing && live.playbackState != PlaybackState.Finished) return
        current = current?.copy(wasPaused = false)
        if (context.mode != GeneratedRadioPlaybackMode.Station || !context.endlessEnabled || live.repeatMode != RepeatMode.Off || terminal || work?.isActive == true) return
        val station = context.station ?: return
        wakeup?.cancel(); wakeup = null
        val upcoming = live.queue.tracks.size - live.queue.currentIndex - 1
        val duration = live.currentTrack?.durationSeconds?.toDouble()
        val remaining = duration?.let { (it - (live.progress.positionSeconds ?: 0.0)).coerceAtLeast(0.0) }
        val upcomingDurations = live.queue.tracks.drop(live.queue.currentIndex + 1).map { it.durationSeconds }
        val totalRemaining = if (remaining != null && upcomingDurations.all { it != null }) remaining + upcomingDurations.sumOf { it!!.toDouble() } else null
        val frontier = live.queue.occurrences.lastOrNull()?.id ?: live.queue.tracks.lastOrNull()?.id?.value
        if (upcoming <= 2 || totalRemaining != null && totalRemaining <= 60.0) {
            if (frontier == requestedFrontier) return
            requestedFrontier = frontier
            continuation.beginRefill(live.currentTrack?.id ?: TrackId(""))
            launchBuild(station, sourceEpoch, initial = false)
        } else if (totalRemaining != null) {
            wakeup = scope.launch { delay(((totalRemaining - 60.0) * 1000).toLong().coerceAtLeast(1)); onPlaybackChanged(force = true) }
        }
    }

    fun onQueueCleared() = stop()
    fun authorityChanged() { cancelWork(); requestedFrontier = null; onPlaybackChanged(force = true) }

    fun stop() {
        ++sourceEpoch; continuation.stop(); cancelWork(); current = null; terminal = false
        requestedFrontier = null; observedQueue = null; observedState = null
        publish(GeneratedRadioStatus.Inactive)
    }

    fun resetForSourceChange() {
        stop(); previewGeneration++
        stateStore.updateShell { it.copy(generatedRadio = NaviampGeneratedRadioUi(preferences = repository.load().preferences)) }
    }

    fun restore(checkpoint: GeneratedRadioCheckpoint?) {
        stop()
        val valid = checkpoint?.takeIf { it.station?.let(::available) != false } ?: return
        current = valid
        continuation.start(valid.station?.seedTracks?.firstOrNull()?.let { TrackId(it.id) }, refilling = false)
        publish(GeneratedRadioStatus.Active)
    }

    fun playFinite(tracks: List<Track>, startIndex: Int = 0) {
        val offline = providerSource.current() == null
        if (offline && !effects.capabilities.supportsDownloadedOnly) { publish(GeneratedRadioStatus.Unsupported); return }
        stop()
        if (tracks.isEmpty()) return
        current = GeneratedRadioCheckpoint(mode = GeneratedRadioPlaybackMode.FiniteBatch, endlessEnabled = false)
        editingQueue = true
        try {
            val update = queue.replaceQueue(app.naviamp.domain.queue.PlaybackQueue(tracks, startIndex).withUserOccurrences())
            if (offline) effects.applyQueue(update.queue, update.clearPreparedNext)
            else effects.applyProviderQueue(update.queue, update.clearPreparedNext)
            playback.updateCurrentTrack(update.queue.current)
            if (offline) effects.playDownloadedQueueSelection(update.queue, update.queue.currentIndex)
            else effects.playQueueSelection(update.queue, update.queue.currentIndex)
        } finally { editingQueue = false }
        publishNowPlaying(); openNowPlaying()
    }

    fun playBatch(station: GeneratedRadioStation, batch: GeneratedRadioBatch) {
        if (!available(station)) { publish(GeneratedRadioStatus.Unsupported); return }
        val tracks = batch.tracks.map(SavedTrack::toTrack)
        if (routeBatch(station, batch)) return
        playFinite(tracks)
        current = current?.copy(station = station, mode = GeneratedRadioPlaybackMode.FiniteBatch, endlessEnabled = false)
        publish(GeneratedRadioStatus.Active)
    }

    fun applyBatch(station: GeneratedRadioStation, batch: GeneratedRadioBatch) {
        if (current?.station?.id != station.id || !available(station)) { publish(GeneratedRadioStatus.InvalidRecipe); return }
        ++sourceEpoch; cancelWork()
        editingQueue = true
        try {
            val update = queue.replaceGeneratedUpcomingOccurrences(batch.tracks.map(SavedTrack::toTrack), station.id, batch.id)
            effects.applyProviderQueue(update.queue, update.clearPreparedNext)
            current = current?.copy(station = batch.resolvedRecipe?.takeIf { it.id == station.id && it.revision == station.revision } ?: station)
        } finally { editingQueue = false }
        terminal = false; requestedFrontier = null
        publish(GeneratedRadioStatus.Active); publishNowPlaying(); onPlaybackChanged(force = true)
    }

    suspend fun open(id: String) {
        migrateLegacy()
        val selected = station(id) ?: return publish(GeneratedRadioStatus.InvalidRecipe)
        openPage(selected.id)
        stateStore.updateShell { it.copy(generatedRadio = it.generatedRadio.copy(open = true, station = selected, batch = batch(selected.id))) }
        publish()
        if (!selected.regenerable) publish(GeneratedRadioStatus.InvalidRecipe, affectPlayback = false)
        else if (batch(selected.id) == null) refreshPreview(selected)
    }

    suspend fun refreshPreview(requested: GeneratedRadioStation) {
        val generation = ++previewGeneration
        val provider = providerSource.current() ?: return publish(GeneratedRadioStatus.Unsupported)
        if (!available(requested)) return publish(GeneratedRadioStatus.Unsupported)
        if (!requested.regenerable) return publish(GeneratedRadioStatus.InvalidRecipe, affectPlayback = false)
        stateStore.updateShell { it.copy(generatedRadio = it.generatedRadio.copy(loading = true)) }
        try {
            val station = resolveRecipe(executionRecipe(requested))
            val tracks = RadioService(provider).stationTracks(station, excludedTracks(station), nowEpochMillis = nowMillis()).let { scopedTracks(it) }
            if (generation != previewGeneration || !providerSource.isCurrent(provider) || !available(station)) return
            if (tracks.isEmpty()) publish(GeneratedRadioStatus.Exhausted, affectPlayback = false)
            else {
                val ordered = (seedPrefix(station) + tracks).distinctBy { it.id }
                rememberBatch(station, GeneratedRadioBatch(newBatchId(), station.id, station.revision, nowMillis(), ordered.map(SavedTrack::fromTrack), station))
                publish(GeneratedRadioStatus.Active, affectPlayback = false)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: UnsupportedOperationException) { if (generation == previewGeneration) publish(GeneratedRadioStatus.Unsupported, affectPlayback = false) }
        catch (_: IllegalArgumentException) { if (generation == previewGeneration) publish(GeneratedRadioStatus.InvalidRecipe, affectPlayback = false) }
        catch (_: Exception) { if (generation == previewGeneration) publish(GeneratedRadioStatus.Failed, affectPlayback = false) }
        finally { if (generation == previewGeneration) stateStore.updateShell { it.copy(generatedRadio = it.generatedRadio.copy(loading = false)) } }
    }

    fun applyDj(tuning: RadioTuningSettings) {
        val station = current?.station ?: return
        ++sourceEpoch; cancelWork()
        val revised = station.copy(revision = station.revision + 1, tuning = tuning.normalized(), updatedAtEpochMillis = nowMillis())
        current = current?.copy(station = revised)
        rememberStation(revised); terminal = false; requestedFrontier = null
        publish(); onPlaybackChanged(force = true)
    }

    private suspend fun scopedTracks(tracks: List<Track>): List<Track> {
        val provider = providerSource.current() ?: return emptyList()
        if (provider.selectedMusicFolderIds.isEmpty()) return tracks
        val source = activeSourceId() ?: return emptyList()
        val unknown = tracks.filter { it.musicFolderId == null }.mapTo(mutableSetOf()) { it.id }
        val allowed = libraryIndex?.generatedRadioCatalogTrackIds(source, unknown, provider.selectedMusicFolderIds).orEmpty().toMutableSet()
        val missing = unknown - allowed
        if (missing.isNotEmpty()) {
            withTimeoutOrNull(SimilarityRequestTimeoutMillis) {
                var request: app.naviamp.domain.provider.MediaPageRequest? = app.naviamp.domain.provider.MediaPageRequest(limit = 100)
                var previousPage: List<TrackId>? = null
                while (request != null && !allowed.containsAll(missing)) {
                    val page = provider.generatedRadioCatalogPage(request) ?: break
                    currentCoroutineContext().ensureActive()
                    if (source != activeSourceId() || !providerSource.isCurrent(provider)) return@withTimeoutOrNull
                    val ids = page.items.map { it.id }
                    if (ids.isEmpty() || ids == previousPage) break
                    previousPage = ids
                    val proven = page.items.filter { it.musicFolderId != null && it.musicFolderId in provider.selectedMusicFolderIds }
                    if (proven.isNotEmpty()) libraryIndex?.upsertLibraryTracks(source, proven)
                    page.items.filter { it.id in missing && (it.musicFolderId == null || it.musicFolderId in provider.selectedMusicFolderIds) }
                        .forEach { allowed += it.id }
                    request = page.nextRequest
                }
            }
        }
        if (source != activeSourceId() || !providerSource.isCurrent(provider)) return emptyList()
        return tracks.filter { track -> track.musicFolderId?.let { it in provider.selectedMusicFolderIds } ?: (track.id in allowed) }
    }

    private suspend fun resolveRecipe(requested: GeneratedRadioStation): GeneratedRadioStation {
        val provider = providerSource.current() ?: throw UnsupportedOperationException()
        val seeds = requested.seedTracks.toMutableList()
        if (requested.sonicSimilarity || seeds.isEmpty()) {
            for (album in requested.albums) if (seeds.none { it.albumId == album.id }) {
                provider.album(AlbumId(album.id)).tracks.firstOrNull()?.let { seeds += SavedTrack.fromTrack(it) }
            }
            for (artist in requested.artists) if (seeds.none { it.artistId == artist.id }) {
                val track = RadioService(provider).artistSeed(artist.toArtist())
                if (track != null) seeds += SavedTrack.fromTrack(track)
            }
        }
        if (provider.selectedMusicFolderIds.isNotEmpty() && scopedTracks(seeds.map(SavedTrack::toTrack)).size != seeds.size) {
            throw RadioRecipeValidationException("The original seeds are not in the selected library.")
        }
        val seed = seeds.firstOrNull()?.toTrack()
        return requested.copy(seedTracks = seeds.distinctBy { it.id },
            resolvedArtistId = requested.resolvedArtistId ?: seed?.artistId?.value,
            resolvedDecadeStart = requested.resolvedDecadeStart ?: seed?.eraReleaseYear?.let { it / 10 * 10 }).normalized()
    }

    private fun seedPrefix(station: GeneratedRadioStation): List<Track> = when {
        station.kind != GeneratedRadioKind.SonicMix -> station.seedTracks.take(1)
        station.includeSeeds -> station.seedTracks
        else -> emptyList()
    }.map(SavedTrack::toTrack)

    suspend fun acceptsTransferredTracks(tracks: List<Track>): Boolean = scopedTracks(tracks).size == tracks.size

    fun recentExclusions(): Set<TrackId> {
        val source = activeSourceId() ?: return emptySet()
        val hours = repository.load().preferences.recentTrackExclusionHours
        if (hours == 0) return emptySet()
        val cutoff = nowMillis() - hours * 3_600_000L
        return libraryIndex?.recentPlaybackHistory(source, 2_000).orEmpty().asSequence()
            .filter { it.playedAtEpochMillis >= cutoff }.map { it.track.id }.toSet()
    }

    private fun excludedTracks(station: GeneratedRadioStation): Set<TrackId> {
        val hours = repository.load().preferences.recentTrackExclusionHours
        val recent = if (hours > 0) station.sourceId?.let { source ->
            val cutoff = nowMillis() - hours * 3_600_000L
            libraryIndex?.recentPlaybackHistory(source, 2_000)?.asSequence()?.filter { it.playedAtEpochMillis >= cutoff }?.map { it.track.id }?.toSet()
        }.orEmpty() else emptySet()
        return recent + current?.takeIf { it.station?.id == station.id }?.seenTrackIds.orEmpty().map(::TrackId)
    }

    private fun available(station: GeneratedRadioStation): Boolean {
        val provider = providerSource.current()
        if (provider == null) {
            val saved = activeSourceId()?.let { libraryIndex?.mediaSource(it) } ?: return false
            val identity = NaviampConnectSourceIdentity(saved.providerId, saved.baseUrl, saved.username, saved.selectedMusicFolderIds)
            return station.belongsTo(saved.id, identity, saved.cacheNamespace)
        }
        return if (station.sourceId == null && station.sourceIdentity == null) station.libraryScope == provider.cacheNamespace
        else station.belongsTo(activeSourceId(), sourceIdentity(), provider.cacheNamespace)
    }

    private fun activeSourceId() = stateStore.state.value.shell.connectionSettings.currentSourceId
    private fun newBatchId() = Uuid.random().toString()
    private fun station(id: String) = repository.load().stations.firstOrNull { it.id == id || it.legacyRecentId == id }
    private fun batch(id: String) = repository.load().batches.firstOrNull { it.stationId == id }
    private fun executionRecipe(station: GeneratedRadioStation): GeneratedRadioStation =
        batch(station.id)?.takeIf { it.stationRevision == station.revision }?.resolvedRecipe
            ?.takeIf { it.id == station.id && it.revision == station.revision && available(it) }
            ?.copy(name = station.name, saved = station.saved, updatedAtEpochMillis = station.updatedAtEpochMillis)
            ?: station
    private fun updateLibrary(transform: (GeneratedRadioLibrary) -> GeneratedRadioLibrary) {
        repository.save(transform(repository.load()).normalized()); settingsChanged()
    }
    private fun rememberStation(station: GeneratedRadioStation) = updateLibrary { library ->
        library.copy(stations = library.stations.filterNot { it.id == station.id } + station)
    }
    private fun rememberBatch(station: GeneratedRadioStation, batch: GeneratedRadioBatch) = updateLibrary { library ->
        library.copy(batches = listOf(batch) + library.batches.filterNot { it.stationId == station.id })
    }
    private fun cancelWork() { wakeup?.cancel(); wakeup = null; work?.cancel(); work = null }

    fun publish(status: GeneratedRadioStatus? = null, affectPlayback: Boolean = true) {
        if (affectPlayback && status != null) liveStatus = status
        val library = repository.load()
        val provider = providerSource.current()
        stateStore.updateShell { shell ->
            val old = shell.generatedRadio
            val selected = old.station?.let { previous -> library.stations.firstOrNull { it.id == previous.id } ?: previous }
            val batch = selected?.let { station -> library.batches.firstOrNull { it.stationId == station.id } }
            shell.copy(generatedRadio = old.copy(station = selected, batch = batch,
                stations = library.stations.filter { it.saved && available(it) },
                tracks = batch?.tracks.orEmpty().map { it.toTrack().toSharedTrackRowUi(coverArtUrl = { id -> id?.let { provider?.coverArtUrl(it) } }) },
                activeStationId = current?.station?.id, preferences = library.preferences,
                sourceAvailable = selected?.let(::available) ?: (provider != null),
                offline = provider == null,
                artworkUrl = selected?.takeUnless { it.kind == GeneratedRadioKind.Artist || it.kind == GeneratedRadioKind.Artists }
                    ?.let { it.albums.firstOrNull()?.coverArtId ?: it.seedTracks.firstOrNull()?.coverArtId }?.let { provider?.coverArtUrl(it) },
                status = status ?: old.status))
        }
        publishNowPlaying()
        onChanged()
    }

    suspend fun migrateLegacy() {
        val source = activeSourceId() ?: return
        val library = repository.load()
        if (source in library.migratedSourceIds) return
        val migrated = recents.current().filter { it.sourceId == source }.map { recent ->
            val recipe = recipeFromRecent(recent)
            val options = recent.generationOptions
            val template = recipe ?: GeneratedRadioStation("", recent.label, GeneratedRadioKind.Track)
            bind(template.copy(legacyRecentId = recent.id, regenerable = recipe != null && options != null,
                tuning = options?.tuning ?: RadioTuningSettings(), sonicSimilarity = options?.sonicSimilarity ?: false,
                batchSize = options?.minimumTracks ?: DefaultRadioMinimumTracks)) to recent
        }
        repository.save(library.copy(
            stations = library.stations + migrated.map { it.first }.filterNot { value -> library.stations.any { it.legacyRecentId == value.legacyRecentId && it.sourceId == source } },
            batches = library.batches + migrated.filter { it.second.sessionTracks.isNotEmpty() }.map { (station, recent) ->
                GeneratedRadioBatch(newBatchId(), station.id, station.revision, recent.startedAtEpochMillis ?: 0, recent.sessionTracks)
            }, migratedSourceIds = library.migratedSourceIds + source))
    }

    private fun recipeFromRecent(recent: RecentRadioStream): GeneratedRadioStation? {
        val kind = when {
            recent.id.startsWith("artist-mix:") -> GeneratedRadioKind.Artists
            recent.id.startsWith("album-mix:") -> GeneratedRadioKind.Albums
            recent.id.startsWith("genre-mix:") -> GeneratedRadioKind.Genres
            else -> when (recent.kind) {
                RecentRadioKind.Track -> GeneratedRadioKind.Track
                RecentRadioKind.Artist -> GeneratedRadioKind.Artist
                RecentRadioKind.Album -> GeneratedRadioKind.Album
                RecentRadioKind.RandomAlbum -> GeneratedRadioKind.RandomAlbum
                RecentRadioKind.Genre -> GeneratedRadioKind.Genres
                RecentRadioKind.Decade -> GeneratedRadioKind.Decade
                RecentRadioKind.Library -> GeneratedRadioKind.Library
            }
        }
        if (kind in setOf(GeneratedRadioKind.Artists, GeneratedRadioKind.Albums) && recent.stationSeedIds.isEmpty()) return null
        return GeneratedRadioStation("", recent.label, kind,
            seedTracks = listOfNotNull(recent.track),
            artists = if (kind == GeneratedRadioKind.Artists) recent.stationSeedIds.map { id -> recent.artist?.takeIf { it.id == id } ?: SavedArtist(id, id) } else listOfNotNull(recent.artist),
            albums = if (kind == GeneratedRadioKind.Albums) recent.stationSeedIds.map { id -> recent.album?.takeIf { it.id == id } ?: SavedAlbum(id, id, "") } else listOfNotNull(recent.album),
            genres = if (kind == GeneratedRadioKind.Genres) recent.stationSeedIds.ifEmpty { listOfNotNull(recent.genre) } else emptyList(),
            fromYear = recent.fromYear, toYear = recent.toYear)
    }
}

internal fun naviampCoreGeneratedRadioActions(handler: NaviampCoreCommandHandler) = app.naviamp.ui.NaviampGeneratedRadioActions(
    onAction = { handler.dispatch(NaviampCoreCommand.GeneratedRadio(it)) },
    onPreferencesChanged = { handler.dispatch(NaviampCoreCommand.GeneratedRadioPreferencesChanged(it)) },
)
