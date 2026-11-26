package app.naviamp.presentation

import app.naviamp.domain.playback.PlaybackEngine
import app.naviamp.domain.playback.PlaybackProgress
import app.naviamp.domain.playback.PlaybackRequest
import app.naviamp.domain.playback.PlaybackState
import app.naviamp.domain.playback.PlaybackStreamMetadata
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.domain.settings.PlaybackSettings
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NaviampPausedSeekConcurrencyTest {
    @Test fun inFlightNativeProgressCannotOverwriteTheLatestPausedSeek() = runTest {
        val provider = FakeCoreMediaProvider()
        val engine = ConcurrentProgressEngine()
        val parkNextSettingsRead = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val adapter = NaviampCorePlaybackEngineAdapter(this, engine,
            NaviampCoreMediaProviderSource { provider }, settings = {
                if (parkNextSettingsRead.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release native progress" }
                }
                PlaybackSettings()
            })
        val progress = ConcurrentLinkedQueue<PlaybackProgress>()
        adapter.attach(object : NaviampCorePlaybackObserver {
            override fun onStateChanged(state: PlaybackState) = Unit
            override fun onMetadataChanged(metadata: PlaybackStreamMetadata) = Unit
            override fun onProgressChanged(value: PlaybackProgress) { progress.add(value) }
        })
        try {
            adapter.playQueueSelection(PlaybackQueue(listOf(provider.track), 0), 0)
            runCurrent()
            progress.clear()
            parkNextSettingsRead.set(true)
            val oldCallback = worker.submit {
                engine.emitProgress(PlaybackProgress(0.0, 180.0, decodedPositionSeconds = 0.2))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Native callback did not reach settings")
            adapter.pause()
            adapter.seek(90.0)
            adapter.seek(5.0)
            assertEquals(listOf(90.0, 5.0), progress.map { it.positionSeconds })
            assertEquals(1, engine.playCalls, "Paused scrubbing must not start audio")
            release.countDown()
            oldCallback.get(10, TimeUnit.SECONDS)
            assertEquals(listOf(90.0, 5.0), progress.map { it.positionSeconds },
                "An already-admitted native poll must not replace the pending seek")
            adapter.resume()
            runCurrent()
            assertEquals(2, engine.playCalls)
            assertEquals(5.0, engine.request?.startPositionSeconds)
        } finally {
            release.countDown()
            worker.shutdownNow()
            try {
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "Native callback thread leaked")
            } finally { adapter.stop() }
        }
    }
}

private class ConcurrentProgressEngine : PlaybackEngine {
    override val name = "Concurrent progress fixture"
    override val supportsPause = true
    override val supportsSeek = true
    override val supportsGapless = false
    override val supportsCrossfade = false
    override val supportsReplayGain = false
    override val supportsSoftwareVolume = true
    override val prefersOriginalStream = true
    var request: PlaybackRequest? = null
    var playCalls = 0
    private var state: ((PlaybackState) -> Unit)? = null
    private var progress: ((PlaybackProgress) -> Unit)? = null

    override fun play(scope: CoroutineScope, request: PlaybackRequest,
        onStateChanged: (PlaybackState) -> Unit, onProgressChanged: (PlaybackProgress) -> Unit,
        onMetadataChanged: (PlaybackStreamMetadata) -> Unit) {
        this.request = request
        playCalls++
        state = onStateChanged
        progress = onProgressChanged
        onStateChanged(PlaybackState.Playing)
        onProgressChanged(PlaybackProgress(request.startPositionSeconds ?: 0.0, 180.0))
    }
    override fun pause() { state?.invoke(PlaybackState.Paused) }
    override fun resume() = error("A deferred seek must restart at its pending position")
    override fun seek(positionSeconds: Double) = error("A paused seek must not seek native output")
    override fun setVolume(percent: Int) = Unit
    override fun stop() {
        progress?.invoke(PlaybackProgress.Unknown)
        state?.invoke(PlaybackState.Stopped)
    }
    fun emitProgress(value: PlaybackProgress) { progress?.invoke(value) }
}
