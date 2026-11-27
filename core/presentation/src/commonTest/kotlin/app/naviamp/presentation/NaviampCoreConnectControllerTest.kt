package app.naviamp.presentation

import app.naviamp.app.NaviampConnectAdvertisingEffect
import app.naviamp.app.NaviampConnectAdvertisingListener
import app.naviamp.app.NaviampConnectAdvertisingStartResult
import app.naviamp.app.NaviampConnectAuthenticatedCipher
import app.naviamp.app.NaviampConnectAuthenticatedCipherFactory
import app.naviamp.app.NaviampConnectDeviceIdentity
import app.naviamp.app.NaviampConnectDeviceIdentityEffect
import app.naviamp.app.NaviampConnectDiscoveryEffect
import app.naviamp.app.NaviampConnectDiscoveryListener
import app.naviamp.app.NaviampConnectDiscoveryStartResult
import app.naviamp.app.NaviampConnectDiscoveredTarget
import app.naviamp.app.NaviampConnectIdentityVerifier
import app.naviamp.app.NaviampConnectPakeFactory
import app.naviamp.app.NaviampConnectPakeRole
import app.naviamp.app.NaviampConnectPakeSession
import app.naviamp.app.NaviampConnectRegistrationService
import app.naviamp.app.NaviampConnectResolvedService
import app.naviamp.app.NaviampConnectControllerConnectionStatus
import app.naviamp.app.NaviampConnectControllerSession
import app.naviamp.app.NaviampConnectRequestIdFactory
import app.naviamp.app.NaviampConnectSessionTransport
import app.naviamp.app.NaviampConnectSessionSecret
import app.naviamp.app.NaviampConnectTransportConnection
import app.naviamp.app.NaviampConnectTransportFactory
import app.naviamp.app.NaviampConnectTransportListener
import app.naviamp.app.NaviampConnectTrustRepository
import app.naviamp.app.NaviampConnectTrustStorageEffect
import app.naviamp.app.NaviampLivePlaybackController
import app.naviamp.app.NaviampLivePlaybackState
import app.naviamp.app.NaviampPlaybackQueueCoordinator
import app.naviamp.domain.TrackId
import app.naviamp.domain.connect.NaviampConnectCapability
import app.naviamp.domain.connect.NaviampConnectCommandRequest
import app.naviamp.domain.connect.NaviampConnectEnvelope
import app.naviamp.domain.connect.NaviampConnectHandoffQueue
import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.playback.PlaybackState
import app.naviamp.domain.queue.PlaybackQueue
import app.naviamp.ui.NaviampConnectPairingUiPhase
import app.naviamp.ui.NaviampConnectUiRole
import app.naviamp.domain.connect.NaviampConnectAdvertisement
import app.naviamp.domain.connect.NaviampConnectDevice
import app.naviamp.domain.connect.NaviampConnectDeviceCapability
import app.naviamp.domain.connect.NaviampConnectDeviceRole
import app.naviamp.domain.connect.NaviampConnectDiscoveryMetadata
import app.naviamp.domain.connect.NaviampConnectProtocolRange
import app.naviamp.domain.connect.NaviampConnectPlaybackSnapshot
import app.naviamp.domain.connect.NaviampConnectPlaybackState
import app.naviamp.domain.connect.NaviampConnectTargetSnapshot
import app.naviamp.domain.connect.NaviampConnectTrustRecord
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NaviampCoreConnectControllerTest {
    @Test
    fun finiteBatchReplayRequiresNegotiationAndTransfersExactOrderWithAutoplayDisabled() = runTest {
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val station = app.naviamp.domain.radio.GeneratedRadioStation("station", "Saved batch",
            app.naviamp.domain.radio.GeneratedRadioKind.Library, sourceIdentity = source, revision = 2)
        val tracks = listOf("second", "first", "first").map {
            app.naviamp.domain.settings.SavedTrack(id = it, title = it, artistName = "Artist")
        }
        val batch = app.naviamp.domain.radio.GeneratedRadioBatch("batch", station.id, station.revision, 0, tracks)
        for (version in listOf(0, 1)) {
            val sent = mutableListOf<NaviampConnectEnvelope>()
            val (controller, _, _) = remoteFixture(queueHandoffSupported = true, stationSessionVersion = version,
                sourceIdentity = { source }) { sent += it }
            assertTrue(controller.playGeneratedBatchOnSelectedDevice(station, batch))
            runCurrent()
            if (version == 0) assertTrue(sent.isEmpty()) else {
                val handoff = (sent.single().message as NaviampConnectCommandRequest).command as
                    app.naviamp.domain.connect.NaviampConnectStationHandoff
                assertEquals(app.naviamp.domain.radio.GeneratedRadioPlaybackMode.FiniteBatch, handoff.checkpoint.mode)
                assertFalse(handoff.checkpoint.endlessEnabled)
                assertEquals(tracks.map { it.id }, handoff.queue.occurrences.map { it.mediaId })
                assertEquals(3, handoff.checkpoint.occurrences.map { it.id }.distinct().size)
                assertTrue(handoff.checkpoint.occurrences.all { it.stationId == station.id && it.batchId == batch.id })
                assertEquals(0L, handoff.positionMillis)
                assertTrue(handoff.playing)
            }
            controller.close()
        }
    }

    @Test
    fun stationHandoffNeverDowngradesToLegacyQueueOnUnsupportedTargets() = runTest {
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val track = FakeCoreMediaProvider().track
        val station = app.naviamp.domain.radio.GeneratedRadioStation("station", "Station",
            app.naviamp.domain.radio.GeneratedRadioKind.Library, sourceIdentity = source)
        val occurrences = listOf(app.naviamp.domain.radio.RadioQueueOccurrence("generated", station.id, "batch"),
            app.naviamp.domain.radio.RadioQueueOccurrence("manual"))
        val checkpoint = app.naviamp.domain.radio.GeneratedRadioCheckpoint(station, wasPaused = true, occurrences = occurrences)
        for (version in listOf(0, 1)) {
            val local = NaviampLivePlaybackController(NaviampLivePlaybackState(
                queue = PlaybackQueue(listOf(track, track), 0, occurrences = occurrences), currentTrack = track,
                playbackState = PlaybackState.Paused, progress = app.naviamp.domain.playback.PlaybackProgress(19.0, 180.0)))
            val before = local.state.value
            val sent = mutableListOf<NaviampConnectEnvelope>()
            val (controller, _, _) = remoteFixture(queueHandoffSupported = true, stationSessionVersion = version,
                sourceIdentity = { source }, localPlayback = local, generatedRadio = RecordingGeneratedRadioPort(checkpoint)) { sent += it }
            controller.actions.onRemoteHandoffQueue()
            runCurrent()
            if (version == 0) assertTrue(sent.isEmpty()) else {
                val handoff = (sent.single().message as NaviampConnectCommandRequest).command as
                    app.naviamp.domain.connect.NaviampConnectStationHandoff
                assertEquals(checkpoint, handoff.checkpoint)
                assertEquals(19_000L, handoff.positionMillis)
                assertFalse(handoff.playing)
            }
            assertEquals(before, local.state.value)
            controller.close()
        }
    }

    @Test
    fun anonymousFiniteHandoffPreservesPolicyOnlyOnNegotiatedTargets() = runTest {
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val track = FakeCoreMediaProvider().track
        val occurrences = listOf(app.naviamp.domain.radio.RadioQueueOccurrence("first"),
            app.naviamp.domain.radio.RadioQueueOccurrence("second"))
        val checkpoint = app.naviamp.domain.radio.GeneratedRadioCheckpoint(
            mode = app.naviamp.domain.radio.GeneratedRadioPlaybackMode.FiniteBatch, wasPaused = true,
            endlessEnabled = false, seenTrackIds = listOf("heard"), occurrences = occurrences)
        for (version in listOf(0, 1)) {
            val local = NaviampLivePlaybackController(NaviampLivePlaybackState(
                queue = PlaybackQueue(listOf(track, track), 1, occurrences = occurrences), currentTrack = track,
                playbackState = PlaybackState.Paused, progress = app.naviamp.domain.playback.PlaybackProgress(19.0, 180.0)))
            val before = local.state.value
            val sent = mutableListOf<NaviampConnectEnvelope>()
            val (controller, store, _) = remoteFixture(queueHandoffSupported = true, stationSessionVersion = version,
                sourceIdentity = { source }, localPlayback = local, generatedRadio = RecordingGeneratedRadioPort(checkpoint)) { sent += it }
            controller.actions.onRemoteHandoffQueue()
            runCurrent()
            if (version == 0) {
                assertTrue(sent.isEmpty())
                assertEquals(app.naviamp.ui.NaviampConnectStatusText.GeneratedStationUpgradeRequired,
                    store.state.value.shell.connect.statusMessage?.text)
            } else {
                val handoff = (sent.single().message as NaviampConnectCommandRequest).command as
                    app.naviamp.domain.connect.NaviampConnectStationHandoff
                assertEquals(checkpoint, handoff.checkpoint)
                assertEquals(listOf("first", "second"), handoff.queue.occurrences.map { it.occurrenceId })
                assertEquals(1, handoff.queue.currentIndex)
                assertEquals(19_000L, handoff.positionMillis)
                assertFalse(handoff.playing)
            }
            assertEquals(before, local.state.value)
            controller.close()
        }
    }

    @Test
    fun returningStationPausesTargetAndWaitsForFreshCheckpointBeforeTakingAuthority() = returningGeneratedQueue(false)

    @Test
    fun returningAnonymousFiniteQueueRetainsCheckpointPolicy() = returningGeneratedQueue(true)

    private fun returningGeneratedQueue(anonymous: Boolean) = runTest {
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val station = if (anonymous) null else app.naviamp.domain.radio.GeneratedRadioStation("station", "Original seeds",
            app.naviamp.domain.radio.GeneratedRadioKind.Library, sourceIdentity = source, revision = 6)
        val checkpoint = app.naviamp.domain.radio.GeneratedRadioCheckpoint(station,
            mode = if (anonymous) app.naviamp.domain.radio.GeneratedRadioPlaybackMode.FiniteBatch
                else app.naviamp.domain.radio.GeneratedRadioPlaybackMode.Station,
            endlessEnabled = !anonymous,
            occurrences = listOf(app.naviamp.domain.radio.RadioQueueOccurrence("entry", station?.id, if (anonymous) null else "batch")))
        val remote = NaviampConnectTargetSnapshot(1,
            NaviampConnectDevice("tv", "TV", NaviampConnectDeviceRole.Target), emptySet(), sourceIdentity = source,
            playback = app.naviamp.domain.connect.NaviampConnectPlaybackSnapshot(
                state = app.naviamp.domain.connect.NaviampConnectPlaybackState.Playing, positionMillis = 1_000),
            queue = app.naviamp.domain.connect.NaviampConnectQueueSnapshot(listOf(
                app.naviamp.domain.connect.NaviampConnectQueueOccurrence("entry", "song", "Song", "Artist")), 0),
            stationSessionVersion = 1, generatedRadio = checkpoint)
        lateinit var activeSession: NaviampConnectControllerSession
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectCommand>()
        val port = RecordingGeneratedRadioPort(null)
        val localTrack = FakeCoreMediaProvider().track
        val local = NaviampLivePlaybackController(NaviampLivePlaybackState(queue = PlaybackQueue(listOf(localTrack), 0),
            currentTrack = localTrack, playbackState = PlaybackState.Paused))
        val (controller, store, session) = remoteFixture(queueHandoffSupported = true, stationSessionVersion = 1,
            extraCapabilities = setOf(NaviampConnectCapability.QueueRead), sourceIdentity = { source },
            localPlayback = local, generatedRadio = port) { envelope ->
            val command = (envelope.message as NaviampConnectCommandRequest).command
            sent += command
            val revision = if (command == app.naviamp.domain.connect.NaviampConnectPause) 2L else 3L
            activeSession.receive(NaviampConnectEnvelope(1, "session", revision,
                responseToRequestId = envelope.requestId,
                message = app.naviamp.domain.connect.NaviampConnectAcknowledgement(revision)))
        }
        activeSession = session
        session.receive(NaviampConnectEnvelope(1, "session", 1,
            message = app.naviamp.domain.connect.NaviampConnectSnapshotMessage(remote)))
        controller.actions.onReceiveRemoteQueue()
        runCurrent()
        assertEquals(listOf(app.naviamp.domain.connect.NaviampConnectPause,
            app.naviamp.domain.connect.NaviampConnectRequestSnapshot), sent)
        assertTrue(port.handoffs.isEmpty(), "Acknowledgement alone cannot authorize a stale checkpoint")
        val fresh = remote.copy(revision = 3, playback = remote.playback.copy(
            state = app.naviamp.domain.connect.NaviampConnectPlaybackState.Paused, positionMillis = 34_567),
            generatedRadio = checkpoint.copy(wasPaused = true, seenTrackIds = listOf("last-heard")))
        session.receive(NaviampConnectEnvelope(1, "session", 4,
            message = app.naviamp.domain.connect.NaviampConnectSnapshotMessage(fresh)))
        runCurrent()
        val handoff = port.handoffs.single()
        assertEquals(34_567L, handoff.positionMillis)
        assertEquals(listOf("last-heard"), handoff.checkpoint.seenTrackIds)
        assertEquals(station, handoff.checkpoint.station)
        assertEquals(checkpoint.copy(seenTrackIds = listOf("last-heard")), handoff.checkpoint)
        assertTrue(handoff.playing)
        assertFalse(handoff.checkpoint.wasPaused)
        assertFalse(store.state.value.shell.connect.remotePlaybackAuthorityActive)
        controller.close()
    }

    private class RecordingGeneratedRadioPort(
        private val current: app.naviamp.domain.radio.GeneratedRadioCheckpoint?,
    ) : NaviampCoreConnectGeneratedRadioPort {
        val handoffs = mutableListOf<app.naviamp.domain.connect.NaviampConnectStationHandoff>()
        override fun checkpoint() = current
        override fun status() = app.naviamp.domain.radio.GeneratedRadioStatus.Active
        override fun open(station: app.naviamp.domain.radio.GeneratedRadioStation) = error("Not an inspection test")
        override suspend fun retry(stationId: String, revision: Long): Boolean = error("Not a retry test")
        override suspend fun start(command: app.naviamp.domain.connect.NaviampConnectStationStart): Boolean = error("Not a start test")
        override suspend fun handoff(command: app.naviamp.domain.connect.NaviampConnectStationHandoff): Boolean {
            handoffs += command
            return true
        }
    }

    @Test
    fun generatedStationUsesNegotiatedFullRecipeOrRejectsWithoutLocalFallback() = runTest {
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val station = app.naviamp.domain.radio.GeneratedRadioStation(
            "station", "Two artists", app.naviamp.domain.radio.GeneratedRadioKind.Artists,
            sourceIdentity = source, revision = 4, sonicSimilarity = true,
            artists = listOf(app.naviamp.domain.settings.SavedArtist("b", "B"), app.naviamp.domain.settings.SavedArtist("a", "A")),
        )
        for (version in listOf(0, 1)) {
            val sent = mutableListOf<NaviampConnectEnvelope>()
            val (controller, store, _) = remoteFixture(sourceIdentity = { source }, stationSessionVersion = version) { sent += it }
            assertTrue(controller.startGeneratedStationOnSelectedDevice(station, endlessEnabled = false))
            runCurrent()
            if (version == 0) {
                assertTrue(sent.isEmpty())
                assertEquals(app.naviamp.ui.NaviampConnectStatusText.GeneratedStationUpgradeRequired,
                    store.state.value.shell.connect.statusMessage?.text)
            } else {
                val command = (sent.single().message as NaviampConnectCommandRequest).command
                val start = command as app.naviamp.domain.connect.NaviampConnectStationStart
                assertEquals(station, start.station)
                assertFalse(start.endlessEnabled)
            }
            controller.actions.onPlaybackDeviceSelected(null)
            assertFalse(controller.startGeneratedStationOnSelectedDevice(station, endlessEnabled = true))
            controller.close()
        }
    }

    @Test
    fun radioIntentsDeferToCompleteCoreRecipesRatherThanSendingLegacyStartRadio() = runTest {
        val sent = mutableListOf<NaviampConnectEnvelope>()
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val (controller, _, _) = remoteFixture(sourceIdentity = { source }, stationSessionVersion = 1) { sent += it }
        val command = NaviampCoreCommand.Media.TrackAction(app.naviamp.ui.SharedTrackRowActionRequest(
            app.naviamp.ui.SharedTrackRowUi("song", "Song", "Artist"), app.naviamp.ui.SharedTrackRowAction.StartRadio))
        assertFalse(controller.routeProductCommand(command))
        runCurrent()
        assertTrue(sent.isEmpty())
        controller.close()
    }

    @Test
    fun manualTrustedReconnectUsesChangedAddressWithoutDiscoveryOrAutomaticRetry() = runTest {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        val target = NaviampConnectDevice("tv", "Living Room TV", NaviampConnectDeviceRole.Target)
        trust.upsert(NaviampConnectTrustRecord("trusted-tv", target, "fingerprint", "public-key", 1L))
        val credentials = app.naviamp.app.NaviampConnectSessionCredentialRepository(
            object : app.naviamp.app.NaviampConnectSessionCredentialStorageEffect {
                override fun read(peerDeviceId: String) = byteArrayOf(1, 2, 3)
                override fun write(peerDeviceId: String, value: ByteArray) = Unit
                override fun remove(peerDeviceId: String) = Unit
                override fun contains(peerDeviceId: String) = true
            },
        )
        val discovery = RecordingDiscoveryEffect()
        val attemptedAddresses = mutableListOf<Pair<String, Int>>()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(this, store, NaviampCoreConnectServices(
            networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
            displayName = "Pixel", identity = FakeIdentity, identityVerifier = FakeIdentityVerifier,
            transport = object : NaviampConnectTransportFactory {
                override suspend fun connect(host: String, port: Int): NaviampConnectTransportConnection {
                    attemptedAddresses += host to port
                    error("unreachable")
                }
                override fun listen(port: Int): NaviampConnectTransportListener = error("unused")
            },
            pake = UnusedPakeFactory, cipher = UnusedCipherFactory,
            trust = trust, credentials = credentials, discovery = discovery,
            newOpaqueId = { "opaque" }, newPairingCode = { "123456" },
            nowEpochMillis = { testScheduler.currentTime }, pairingHandshakeTimeoutMillis = 100L,
        ))
        val device = store.state.value.shell.connect.trustedDevices.single()
        controller.actions.onManualTrustedEndpointSelected(device, "missing-port")
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ManualEndpointInvalid,
            store.state.value.shell.connect.statusMessage?.text)
        controller.actions.onManualTrustedEndpointSelected(device, "100.101.102.103:45678")
        runCurrent()
        assertEquals(listOf("100.101.102.103" to 45678), attemptedAddresses)
        assertEquals(0, discovery.startCount)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ManualEndpointUnreachable,
            store.state.value.shell.connect.statusMessage?.text)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, attemptedAddresses.size)
        controller.close()
    }

    @Test
    fun manualAddressCanOpenCodePairingWithoutDiscovery() = runTest {
        val store = NaviampCoreStateStore()
        var attemptedAddress: Pair<String, Int>? = null
        val controller = NaviampCoreConnectController(this, store, NaviampCoreConnectServices(
            networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
            displayName = "Controller",
            identity = FakeIdentity, identityVerifier = FakeIdentityVerifier,
            transport = object : NaviampConnectTransportFactory {
                override suspend fun connect(host: String, port: Int): NaviampConnectTransportConnection {
                    attemptedAddress = host to port
                    error("unreachable")
                }
                override fun listen(port: Int): NaviampConnectTransportListener = error("unused")
            },
            pake = UnusedPakeFactory, cipher = UnusedCipherFactory,
            trust = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}),
            newOpaqueId = { "opaque" }, newPairingCode = { "123456" },
            nowEpochMillis = { testScheduler.currentTime },
        ))
        controller.actions.onManualEndpointSelected("missing-port")
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ManualEndpointInvalid,
            store.state.value.shell.connect.statusMessage?.text)
        controller.actions.onManualEndpointSelected("100.101.102.103:45678")
        assertTrue(store.state.value.shell.connect.manualEndpointAwaitingCode)
        assertEquals(NaviampConnectPairingUiPhase.AwaitingCode, store.state.value.shell.connect.pairingPhase)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ManualEndpointEnterCode,
            store.state.value.shell.connect.statusMessage?.text)
        controller.actions.onManualEndpointSelected("bad-address")
        assertFalse(store.state.value.shell.connect.manualEndpointAwaitingCode)
        controller.actions.onManualEndpointSelected("100.101.102.103:45678")
        controller.actions.onPairingCodeChanged("123456")
        controller.actions.onSubmitPairingCode()
        runCurrent()
        assertEquals("100.101.102.103" to 45678, attemptedAddress)
        assertEquals(NaviampConnectPairingUiPhase.Failed, store.state.value.shell.connect.pairingPhase)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ManualEndpointUnreachable,
            store.state.value.shell.connect.statusMessage?.text)
        controller.close()
    }

    @Test
    fun advertisingPermissionRecoveryClosesFailedListenerAndCreatesFreshPairingOffer() = runTest {
        var denied = true
        var opens = 0
        val listeners = mutableListOf<WaitingListener>()
        val store = NaviampCoreStateStore()
        var nextId = 0
        val controller = NaviampCoreConnectController(this, store, NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = NaviampCorePlaybackTargetConnectCapabilities, displayName = "TV",
            identity = FakeIdentity, identityVerifier = FakeIdentityVerifier,
            transport = object : NaviampConnectTransportFactory {
                override suspend fun connect(host: String, port: Int) = error("unused")
                override fun listen(port: Int) = WaitingListener(42424).also { listeners += it }
            },
            pake = UnusedPakeFactory, cipher = UnusedCipherFactory,
            trust = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}),
            advertising = object : NaviampConnectAdvertisingEffect {
                override fun start(service: NaviampConnectRegistrationService, listener: NaviampConnectAdvertisingListener)
                    : NaviampConnectAdvertisingStartResult {
                    if (denied) return NaviampConnectAdvertisingStartResult.PermissionDenied
                    listener.onServiceRegistered(service.serviceName)
                    return NaviampConnectAdvertisingStartResult.Started
                }
                override fun stop() = Unit
            },
            permissionSettings = app.naviamp.app.NaviampConnectPermissionSettingsEffect { opens++; true },
            newOpaqueId = { "id-${++nextId}" }, newPairingCode = { "123456" },
            nowEpochMillis = { testScheduler.currentTime },
        ))
        controller.actions.onStartPairingMode()
        runCurrent()
        assertEquals(app.naviamp.ui.NaviampConnectRecoveryProblem.LocalNetworkPermission, store.state.value.shell.connect.recovery?.problem)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.AdvertisingPermissionRequired,
            store.state.value.shell.connect.statusMessage?.text, "Key-valued advertising failures remain localized")
        assertTrue(listeners.single().closed)
        assertNull(store.state.value.shell.connect.pairingCode)
        controller.actions.onOpenPermissionSettings()
        assertEquals(1, opens)
        assertNotNull(store.state.value.shell.connect.recovery) // Opening settings does not grant permission.
        denied = false
        controller.actions.onRetryConnection()
        runCurrent()
        assertNull(store.state.value.shell.connect.recovery)
        assertEquals(NaviampConnectPairingUiPhase.Advertising, store.state.value.shell.connect.pairingPhase)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ReadyEnterThisCode,
            store.state.value.shell.connect.statusMessage?.text)
        assertEquals(2, listeners.size)
        assertEquals(42424, store.state.value.shell.connect.listeningPort)
        controller.close()
    }

    private fun secureStorageFixture(
        scope: CoroutineScope,
        store: NaviampCoreStateStore,
        identity: NaviampConnectDeviceIdentityEffect,
        identityDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        listeners: MutableList<WaitingListener>,
        now: () -> Long,
    ) = NaviampCoreConnectController(scope, store, NaviampCoreConnectServices(
        networkDispatcher = identityDispatcher,
        deviceCapabilities = NaviampCorePlaybackTargetConnectCapabilities, displayName = "TV",
        identity = identity, identityVerifier = FakeIdentityVerifier,
        transport = object : NaviampConnectTransportFactory {
            override suspend fun connect(host: String, port: Int) = error("unused")
            override fun listen(port: Int) = WaitingListener(42424).also { listeners += it }
        },
        pake = UnusedPakeFactory, cipher = UnusedCipherFactory,
        trust = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}),
        advertising = object : NaviampConnectAdvertisingEffect {
            override fun start(service: NaviampConnectRegistrationService, listener: NaviampConnectAdvertisingListener)
                : NaviampConnectAdvertisingStartResult {
                listener.onServiceRegistered(service.serviceName)
                return NaviampConnectAdvertisingStartResult.Started
            }
            override fun stop() = Unit
        },
        newOpaqueId = { "opaque" }, newPairingCode = { "123456" },
        nowEpochMillis = now,
    ))

    @Test
    fun constructionDoesNotWaitForASlowSecureStoreAndPairingStartsOnceTheIdentityLoads() = runTest {
        var loads = 0
        val identity = object : NaviampConnectDeviceIdentityEffect {
            override fun loadOrCreate(): NaviampConnectDeviceIdentity { loads++; return FakeIdentity.loadOrCreate() }
            override fun sign(payload: ByteArray) = byteArrayOf(1)
        }
        val store = NaviampCoreStateStore()
        val listeners = mutableListOf<WaitingListener>()
        // The secure store runs on its own dispatcher; nothing on the caller's thread may wait for it.
        val secureStore = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = secureStorageFixture(this, store, identity, secureStore, listeners) { testScheduler.currentTime }
        assertEquals(0, loads, "constructing Connect must not touch the secure store on the caller's thread")
        controller.actions.onStartPairingMode()
        assertTrue(listeners.isEmpty(), "pairing waits for the identity instead of blocking")
        assertNull(store.state.value.shell.connect.recovery)
        runCurrent()
        assertEquals(1, loads)
        assertEquals(1, listeners.size)
        assertEquals(NaviampConnectPairingUiPhase.Advertising, store.state.value.shell.connect.pairingPhase)
        controller.close()
    }

    @Test
    fun unavailableSecureStoreIsARetryableRecoveryProblemInsteadOfACrash() = runTest {
        var locked = true
        var loads = 0
        val identity = object : NaviampConnectDeviceIdentityEffect {
            override fun loadOrCreate(): NaviampConnectDeviceIdentity {
                loads++
                if (locked) throw IllegalStateException("Secure credential service failed with exit code 1")
                return FakeIdentity.loadOrCreate()
            }
            override fun sign(payload: ByteArray) = byteArrayOf(1)
        }
        val store = NaviampCoreStateStore()
        val listeners = mutableListOf<WaitingListener>()
        val controller = secureStorageFixture(this, store, identity, kotlinx.coroutines.Dispatchers.Unconfined, listeners) {
            testScheduler.currentTime
        }
        runCurrent()
        assertEquals(app.naviamp.ui.NaviampConnectRecoveryProblem.SecureStorageUnavailable,
            store.state.value.shell.connect.recovery?.problem)
        controller.actions.onStartPairingMode()
        runCurrent()
        assertTrue(listeners.isEmpty(), "pairing must not start without a device identity")
        locked = false
        controller.actions.onRetryConnection()
        runCurrent()
        assertEquals(2, loads)
        assertNull(store.state.value.shell.connect.recovery)
        controller.actions.onStartPairingMode()
        runCurrent()
        assertEquals(1, listeners.size)
        assertEquals(NaviampConnectPairingUiPhase.Advertising, store.state.value.shell.connect.pairingPhase)
        controller.close()
    }

    @Test
    fun discoveryPermissionRetryPreservesRemoteSessionAndReportsSettingsLaunchFailure() = runTest {
        val discovery = RecordingDiscoveryEffect()
        val (controller, store, _) = remoteFixture(discovery,
            app.naviamp.app.NaviampConnectPermissionSettingsEffect { false }) { }
        discovery.listener.onPermissionDenied()
        runCurrent()
        val selected = store.state.value.shell.connect.selectedPlaybackDeviceId
        controller.actions.onOpenPermissionSettings()
        assertEquals(true, store.state.value.shell.connect.recovery?.settingsOpenFailed)
        val starts = discovery.startCount
        controller.actions.onRetryConnection()
        runCurrent()
        assertEquals(starts + 1, discovery.startCount)
        assertNull(store.state.value.shell.connect.recovery)
        assertEquals(selected, store.state.value.shell.connect.selectedPlaybackDeviceId)
        assertEquals("TV", store.state.value.shell.connect.connectedTargetName)
        controller.close()
    }

    @Test
    fun unavailableDiscoveryHasRetryWithoutPermissionSettings() = runTest {
        val discovery = RecordingDiscoveryEffect()
        val (controller, store, _) = remoteFixture(discovery) { }
        controller.actions.onConnectScreenVisibilityChanged(true)
        discovery.listener.onDiscoveryFailed("native detail")
        runCurrent()
        assertEquals(app.naviamp.ui.NaviampConnectRecoveryProblem.DiscoveryUnavailable, store.state.value.shell.connect.recovery?.problem)
        assertEquals(false, store.state.value.shell.connect.recovery?.canOpenSettings)
        controller.actions.onRetryConnection()
        runCurrent()
        assertNull(store.state.value.shell.connect.recovery)
        controller.close()
    }

    @Test
    fun reconnectResumesAuthorityForActiveTargetButLeavesIdleTargetArmed() {
        val target = NaviampConnectDevice("tv", "Living Room TV", NaviampConnectDeviceRole.Target)

        assertFalse(
            NaviampConnectTargetSnapshot(
                revision = 0,
                target = target,
                capabilities = emptySet(),
                playback = NaviampConnectPlaybackSnapshot(state = NaviampConnectPlaybackState.Idle),
            ).shouldResumeNaviampConnectPlaybackAuthority(),
        )
        assertTrue(
            NaviampConnectTargetSnapshot(
                revision = 0,
                target = target,
                capabilities = emptySet(),
                playback = NaviampConnectPlaybackSnapshot(state = NaviampConnectPlaybackState.Playing),
            ).shouldResumeNaviampConnectPlaybackAuthority(),
        )
        assertTrue(
            NaviampConnectTargetSnapshot(
                revision = 0,
                target = target,
                capabilities = emptySet(),
                playback = NaviampConnectPlaybackSnapshot(state = NaviampConnectPlaybackState.Paused),
            ).shouldResumeNaviampConnectPlaybackAuthority(),
        )
    }

    @Test
    fun armedRemoteUsesTheLocalQueueUntilFirstPlayRegardlessOfTheTargetsOldQueue() {
        assertEquals(
            NaviampCoreConnectPlaybackRoute.Local,
            naviampCoreConnectPlaybackRoute(
                remoteAuthorityActive = false,
                hasLocalCurrent = true,
                action = app.naviamp.ui.NowPlayingPlaybackAction.Pause,
            ),
        )
        assertEquals(
            NaviampCoreConnectPlaybackRoute.InitialHandoff,
            naviampCoreConnectPlaybackRoute(
                remoteAuthorityActive = false,
                hasLocalCurrent = true,
                action = app.naviamp.ui.NowPlayingPlaybackAction.PlayCurrent,
            ),
        )
        assertEquals(
            NaviampCoreConnectPlaybackRoute.InitialHandoff,
            naviampCoreConnectPlaybackRoute(
                remoteAuthorityActive = false,
                hasLocalCurrent = true,
                action = app.naviamp.ui.NowPlayingPlaybackAction.TogglePlayPause,
            ),
        )
        assertEquals(
            NaviampCoreConnectPlaybackRoute.Remote,
            naviampCoreConnectPlaybackRoute(
                remoteAuthorityActive = true,
                hasLocalCurrent = true,
                action = app.naviamp.ui.NowPlayingPlaybackAction.PlayCurrent,
            ),
        )
    }

    @Test
    fun dualCapabilityDeviceListensWithoutSelectingARemotePlaybackTarget() {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        val deviceCapabilities = setOf(
            NaviampConnectDeviceCapability.ControlPlayback,
            NaviampConnectDeviceCapability.PlaybackTarget,
        )
        trust.upsert(
            NaviampConnectTrustRecord(
                trustedDeviceId = "trusted-peer",
                peerDevice = NaviampConnectDevice(
                    deviceId = "peer",
                    displayName = "Other Naviamp",
                    role = NaviampConnectDeviceRole.Target,
                    deviceCapabilities = deviceCapabilities,
                ),
                identityFingerprint = "peer-fingerprint",
                publicKeyBase64 = "peer-key",
                pairedAtEpochMillis = 1L,
            ),
        )
        val credentials = app.naviamp.app.NaviampConnectSessionCredentialRepository(
            object : app.naviamp.app.NaviampConnectSessionCredentialStorageEffect {
                private var credential: ByteArray? = byteArrayOf(1, 2, 3)
                override fun read(peerDeviceId: String) = credential?.copyOf()
                override fun write(peerDeviceId: String, value: ByteArray) { credential = value.copyOf() }
                override fun remove(peerDeviceId: String) { credential = null }
                override fun contains(peerDeviceId: String) = credential != null
            },
        )
        val advertising = RecordingAdvertisingEffect()
        val discovery = RecordingDiscoveryEffect()
        val listener = WaitingListener(42_425)
        var listenCount = 0
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = deviceCapabilities,
                displayName = "Office Phone",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = object : NaviampConnectTransportFactory {
                    override suspend fun connect(host: String, port: Int) = error("unused")
                    override fun listen(port: Int): NaviampConnectTransportListener {
                        listenCount += 1
                        return listener
                    }
                },
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = trust,
                credentials = credentials,
                discovery = discovery,
                advertising = advertising,
                newOpaqueId = generateSequence(0) { it + 1 }
                    .map { "opaque-$it" }
                    .iterator()::next,
                newPairingCode = { "123456" },
                nowEpochMillis = { 1_000L },
            ),
        )

        assertEquals(1, listenCount)
        assertEquals(0, discovery.startCount)
        assertEquals(
            app.naviamp.ui.NaviampConnectPlaybackDestinationUiStatus.Local,
            store.state.value.shell.connect.playbackDestinationStatus,
        )
        controller.actions.onPlaybackDeviceSelected("trusted-peer")
        assertEquals(1, discovery.startCount)
        assertEquals(
            deviceCapabilities,
            advertising.service?.let { service ->
                NaviampConnectDiscoveryMetadata.decode(
                    attributes = service.textAttributes,
                    port = service.port,
                    expiresAtEpochMillis = Long.MAX_VALUE,
                )?.deviceCapabilities
            },
        )
        assertEquals(NaviampConnectUiRole.ControllerAndTarget, store.state.value.shell.connect.role)
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun trustedTargetListensWithoutShowingACode() = runTest {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        trust.upsert(
            NaviampConnectTrustRecord(
                "trusted-phone",
                NaviampConnectDevice("phone", "Pixel", NaviampConnectDeviceRole.Controller),
                "phone-fingerprint",
                "phone-key",
                1L,
            ),
        )
        val advertising = RecordingAdvertisingEffect()
        val listener = WaitingListener(42425)
        var listenCount = 0
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = this,
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = NaviampCorePlaybackTargetConnectCapabilities,
                displayName = "Living Room TV",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = object : NaviampConnectTransportFactory {
                    override suspend fun connect(host: String, port: Int) = error("unused")
                    override fun listen(port: Int): NaviampConnectTransportListener {
                        listenCount += 1
                        return listener
                    }
                },
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = trust,
                advertising = advertising,
                newOpaqueId = generateSequence(0) { it + 1 }
                    .map { index -> "opaque-$index" }
                    .iterator()::next,
                newPairingCode = { "123456" },
                nowEpochMillis = { testScheduler.currentTime + 1_000L },
                pairingLifetimeMillis = 1_000L,
            ),
        )

        runCurrent()

        val service = assertNotNull(advertising.service)
        assertEquals("0", service.textAttributes["code"], "Trusted presence must not offer a code")
        assertNull(store.state.value.shell.connect.pairingCode)
        assertFalse(store.state.value.shell.connect.codeVisible)
        controller.ensureTrustedTargetListener()
        assertEquals(1, listenCount)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, listenCount)
        assertNull(store.state.value.shell.connect.pairingCode, "No background code after the old lifetime")
        assertTrue(!listener.closed)
        controller.close()
    }

    @Test
    fun trustedControllerStaysLocalUntilTheUserSelectsAPlaybackDevice() {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        val target = NaviampConnectDevice("tv", "Living Room TV", NaviampConnectDeviceRole.Target)
        trust.upsert(NaviampConnectTrustRecord("trusted-tv", target, "fingerprint", "public-key", 1L))
        var storedCredential: ByteArray? = null
        val credentials = app.naviamp.app.NaviampConnectSessionCredentialRepository(
            object : app.naviamp.app.NaviampConnectSessionCredentialStorageEffect {
                override fun read(peerDeviceId: String) = storedCredential?.copyOf()
                override fun write(peerDeviceId: String, value: ByteArray) { storedCredential = value.copyOf() }
                override fun remove(peerDeviceId: String) { storedCredential = null }
                override fun contains(peerDeviceId: String) = storedCredential != null
            },
        )
        credentials.write("tv", byteArrayOf(1, 2, 3))
        val discovery = RecordingDiscoveryEffect()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
                displayName = "Pixel",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = UnusedTransportFactory,
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = trust,
                credentials = credentials,
                discovery = discovery,
                newOpaqueId = { "unused" },
                newPairingCode = { "123456" },
                nowEpochMillis = { 1_000L },
            ),
        )

        assertEquals(0, discovery.startCount)
        assertEquals(
            app.naviamp.ui.NaviampConnectPlaybackDestinationUiStatus.Local,
            store.state.value.shell.connect.playbackDestinationStatus,
        )
        controller.actions.onPlaybackDeviceSelected("trusted-tv")
        assertEquals(1, discovery.startCount)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.LookingForDeviceStartPairingModeOnTheTvIfNeeded, store.state.value.shell.connect.statusMessage?.text)
        controller.actions.onConnectScreenVisibilityChanged(true)
        assertEquals(0, discovery.stopCount, "Opening the connect panel must not restart a running discovery")
        assertEquals(1, discovery.startCount)
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun automaticTrustedReconnectRetriesWhenTheTvIsDiscoveredBeforeItsListenerIsReady() = runTest {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        val target = NaviampConnectDevice("tv", "Living Room TV", NaviampConnectDeviceRole.Target)
        trust.upsert(NaviampConnectTrustRecord("trusted-tv", target, "fingerprint", "public-key", 1L))
        var storedCredential: ByteArray? = null
        val credentials = app.naviamp.app.NaviampConnectSessionCredentialRepository(
            object : app.naviamp.app.NaviampConnectSessionCredentialStorageEffect {
                override fun read(peerDeviceId: String) = storedCredential?.copyOf()
                override fun write(peerDeviceId: String, value: ByteArray) { storedCredential = value.copyOf() }
                override fun remove(peerDeviceId: String) { storedCredential = null }
                override fun contains(peerDeviceId: String) = storedCredential != null
            },
        )
        credentials.write("tv", byteArrayOf(1, 2, 3))
        val discovery = RecordingDiscoveryEffect()
        var connectCount = 0
        val controller = NaviampCoreConnectController(
            scope = this,
            stateStore = NaviampCoreStateStore(),
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
                displayName = "Pixel",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = object : NaviampConnectTransportFactory {
                    override suspend fun connect(host: String, port: Int): NaviampConnectTransportConnection {
                        connectCount += 1
                        return StalledConnection()
                    }
                    override fun listen(port: Int) = error("unused")
                },
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = trust,
                credentials = credentials,
                discovery = discovery,
                newOpaqueId = { "unused" },
                newPairingCode = { "123456" },
                nowEpochMillis = { testScheduler.currentTime + 1_000L },
                pairingHandshakeTimeoutMillis = 100L,
            ),
        )
        controller.actions.onPlaybackDeviceSelected("trusted-tv")
        discovery.listener.onServiceResolved(
            NaviampConnectResolvedService(
                serviceName = "Living Room",
                addresses = listOf("192.0.2.10"),
                port = 42_425,
                textAttributes = NaviampConnectDiscoveryMetadata.encode(
                    NaviampConnectAdvertisement(
                        instanceId = "tv-instance",
                        displayName = "Living Room TV",
                        protocolRange = NaviampConnectProtocolRange(),
                        capabilities = emptySet(),
                        port = 42_425,
                        identityFingerprint = "fingerprint",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                ),
            ),
        )

        runCurrent()
        advanceTimeBy(100L)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(2, connectCount)
        advanceTimeBy(100L)
        runCurrent()
        controller.actions.onConnectScreenVisibilityChanged(true)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(3, connectCount, "Opening the connect panel must not cancel an automatic reconnect")
        controller.close()
    }

    @Test
    fun stopControllingClosesOnlyTheLiveSessionAndPreservesTrust() = runTest {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        val target = NaviampConnectDevice("tv", "Living Room TV", NaviampConnectDeviceRole.Target)
        val record = NaviampConnectTrustRecord("trusted-tv", target, "fingerprint", "public-key", 1L)
        trust.upsert(record)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = this,
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
                displayName = "Pixel",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = UnusedTransportFactory,
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = trust,
                newOpaqueId = { "unused" },
                newPairingCode = { "123456" },
                nowEpochMillis = { 1L },
            ),
        )
        val session = NaviampConnectControllerSession(
            transport = NaviampConnectSessionTransport {},
            requestIds = NaviampConnectRequestIdFactory { "request" },
        )
        session.connect("session", 1, target, emptySet(), NaviampConnectTargetSnapshot(0, target, emptySet()))
        controller.adoptControllerSession(session)

        controller.actions.onStopControlling()

        assertEquals(NaviampConnectControllerConnectionStatus.Disconnected, session.state.value.status)
        assertEquals(listOf(record), trust.load())
        assertNull(store.state.value.shell.connect.connectedTargetName)
        assertEquals(
            app.naviamp.ui.NaviampConnectStatusText.StoppedControllingDeviceTheTvWillKeepPlaying,
            store.state.value.shell.connect.statusMessage?.text,
        )
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun outboundCommandFailureImmediatelyEntersTheReconnectLifecycle() = runTest {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        val target = NaviampConnectDevice("tv", "Living Room TV", NaviampConnectDeviceRole.Target)
        trust.upsert(NaviampConnectTrustRecord("trusted-tv", target, "fingerprint", "public-key", 1L))
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = this,
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
                displayName = "Pixel",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = UnusedTransportFactory,
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = trust,
                newOpaqueId = { "unused" },
                newPairingCode = { "123456" },
                nowEpochMillis = { 1L },
            ),
        )
        val session = NaviampConnectControllerSession(
            transport = NaviampConnectSessionTransport { error("socket write failed") },
            requestIds = NaviampConnectRequestIdFactory { "request" },
        )
        session.connect(
            "session",
            1,
            target,
            setOf(app.naviamp.domain.connect.NaviampConnectCapability.TransportControls),
            NaviampConnectTargetSnapshot(0, target, emptySet()),
        )
        controller.adoptControllerSession(session)

        controller.actions.onRemotePlayPause()
        runCurrent()

        assertEquals(NaviampConnectControllerConnectionStatus.Disconnected, session.state.value.status)
        assertEquals(
            app.naviamp.ui.NaviampConnectPlaybackDestinationUiStatus.Reconnecting,
            store.state.value.shell.connect.playbackDestinationStatus,
        )
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.Reconnecting, store.state.value.shell.connect.statusMessage?.text)
        controller.close()
    }

    @Test
    fun trustedReconnectUsesTheLastWorkingEndpointUntilDiscoveryRefreshesIt() {
        val recent = NaviampConnectDiscoveredTarget(
            serviceName = "Living Room",
            addresses = listOf("192.0.2.10"),
            advertisement = NaviampConnectAdvertisement(
                instanceId = "recent",
                displayName = "Living Room TV",
                protocolRange = NaviampConnectProtocolRange(),
                capabilities = emptySet(),
                port = 42_425,
                identityFingerprint = "fingerprint",
                expiresAtEpochMillis = 2_000L,
            ),
        )
        val refreshed = recent.copy(
            addresses = listOf("192.0.2.11"),
            advertisement = recent.advertisement.copy(instanceId = "refreshed", expiresAtEpochMillis = 3_000L),
        )

        assertEquals(
            recent,
            selectNaviampConnectReconnectTarget("fingerprint", emptyList(), recent),
        )
        assertEquals(
            refreshed,
            selectNaviampConnectReconnectTarget("fingerprint", listOf(refreshed), recent),
        )
        assertEquals(
            recent,
            selectNaviampConnectReconnectTarget("fingerprint", emptyList(), recent),
        )
    }

    @Test
    fun queueHandoffExplainsWhyAnEmptyLocalQueueCannotBeSent() {
        assertEquals(
            app.naviamp.ui.NaviampConnectStatusText.StartSomethingBeforeSendingQueue,
            naviampCoreConnectQueueHandoffProblem(
                hasPlayback = true,
                hasCurrentQueueItem = false,
                hasSourceIdentity = true,
                connected = true,
            ),
        )
        assertNull(
            naviampCoreConnectQueueHandoffProblem(
                hasPlayback = true,
                hasCurrentQueueItem = true,
                hasSourceIdentity = true,
                connected = true,
            ),
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun socketOperationDeadlineClosesANonCooperativeNativeEffect() = runTest {
        var closed = false
        val result = async {
            awaitNaviampConnectSocketOperation(
                scope = this@runTest,
                timeoutMillis = 1_000L,
                close = { closed = true },
            ) {
                awaitCancellation()
            }
        }

        advanceTimeBy(1_000L)
        runCurrent()

        assertNull(result.await())
        assertTrue(closed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun targetStartsRealListenerAdvertisesItsPortAndPublishesCode() = runTest {
        val advertising = RecordingAdvertisingEffect()
        val listener = WaitingListener(42425)
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = this,
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = NaviampCorePlaybackTargetConnectCapabilities,
                displayName = "Living Room TV",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = object : NaviampConnectTransportFactory {
                    override suspend fun connect(host: String, port: Int) = error("unused")
                    override fun listen(port: Int) = listener
                },
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}),
                advertising = advertising,
                newOpaqueId = sequenceOf("instance", "session").iterator()::next,
                newPairingCode = { "123456" },
                nowEpochMillis = { 1_000L },
            ),
        )

        controller.actions.onStartPairingMode()
        runCurrent()

        val service = assertNotNull(advertising.service)
        assertEquals(42425, service.port)
        assertTrue(service.serviceName.contains("Living Room TV"))
        assertEquals("123456", store.state.value.shell.connect.pairingCode)
        assertEquals(NaviampConnectPairingUiPhase.Advertising, store.state.value.shell.connect.pairingPhase)
        assertTrue(service.textAttributes.isNotEmpty())

        controller.close()
        assertTrue(listener.closed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun incompletePairingClientTimesOutClosesAndDoesNotMonopolizeListener() = runTest {
        val listener = QueuedListener(42425)
        val stalled = StalledConnection()
        listener.connections.trySend(stalled)
        val store = NaviampCoreStateStore()
        val controller = targetController(
            listener = listener,
            store = store,
            nowEpochMillis = { testScheduler.currentTime + 1_000L },
            pairingHelloTimeoutMillis = 1_000L,
            pairingLifetimeMillis = 10_000L,
        )

        controller.actions.onStartPairingMode()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(stalled.closed)
        assertEquals(2, listener.acceptCount)
        assertEquals(NaviampConnectPairingUiPhase.Advertising, store.state.value.shell.connect.pairingPhase)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.IncompleteConnectionRequestTimedOut, store.state.value.shell.connect.statusMessage?.text)
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun clientThatClosesBeforeHelloDoesNotTearDownListener() = runTest {
        val listener = QueuedListener(42425)
        val closedClient = ClosedConnection()
        listener.connections.trySend(closedClient)
        val store = NaviampCoreStateStore()
        val controller = targetController(
            listener = listener,
            store = store,
            nowEpochMillis = { testScheduler.currentTime + 1_000L },
            pairingHelloTimeoutMillis = 1_000L,
            pairingLifetimeMillis = 10_000L,
        )

        controller.actions.onStartPairingMode()
        runCurrent()

        assertTrue(closedClient.closed)
        assertEquals(2, listener.acceptCount)
        assertTrue(!listener.closed)
        assertEquals(NaviampConnectPairingUiPhase.Advertising, store.state.value.shell.connect.pairingPhase)
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun stoppingPairingClosesAnAcceptedStalledConnection() = runTest {
        val listener = QueuedListener(42425)
        val stalled = StalledConnection()
        listener.connections.trySend(stalled)
        val controller = targetController(
            listener = listener,
            store = NaviampCoreStateStore(),
            nowEpochMillis = { testScheduler.currentTime + 1_000L },
            pairingHelloTimeoutMillis = 10_000L,
            pairingLifetimeMillis = 20_000L,
        )

        controller.actions.onStartPairingMode()
        runCurrent()
        controller.actions.onStopPairingMode()
        runCurrent()

        assertTrue(stalled.closed)
        assertTrue(listener.closed)
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun visibleCodeRegeneratesOnExpiry() = runTest {
        val listener = WaitingListener(42425)
        val store = NaviampCoreStateStore()
        val codes = sequenceOf("111111", "222222").iterator()
        val controller = targetController(
            listener = listener,
            store = store,
            nowEpochMillis = { testScheduler.currentTime + 1_000L },
            pairingHelloTimeoutMillis = 10_000L,
            pairingLifetimeMillis = 1_000L,
            newPairingCode = codes::next,
        )

        controller.actions.onStartPairingMode()
        runCurrent()
        assertEquals("111111", store.state.value.shell.connect.pairingCode)
        assertTrue(store.state.value.shell.connect.codeVisible)
        assertEquals(2_000L, store.state.value.shell.connect.pairingCodeExpiresAtEpochMillis)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals("222222", store.state.value.shell.connect.pairingCode)
        assertFalse(listener.closed)
        controller.actions.onStopPairingMode()
        assertNull(store.state.value.shell.connect.pairingCode)
        assertFalse(store.state.value.shell.connect.codeVisible)
        assertTrue(listener.closed, "Without trust, hiding the code closes the listener")
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun pairingRequestWithoutVisibleCodeIsClosed() = runTest {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        trust.upsert(NaviampConnectTrustRecord("trusted-phone",
            NaviampConnectDevice("phone", "Pixel", NaviampConnectDeviceRole.Controller), "phone-fp", "phone-key", 1L))
        val listener = QueuedListener(42425)
        val stranger = ScriptedConnection(app.naviamp.domain.connect.NaviampConnectEnvelope(
            protocolVersion = 1,
            sequence = 0,
            message = app.naviamp.domain.connect.NaviampConnectHello(
                device = NaviampConnectDevice("stranger", "Stranger", NaviampConnectDeviceRole.Controller),
                identity = app.naviamp.domain.connect.NaviampConnectPublicIdentity("stranger", "stranger-fp", "stranger-key"),
                protocolRange = NaviampConnectProtocolRange(),
                capabilities = emptySet(),
            ),
        ))
        listener.connections.trySend(stranger)
        val store = NaviampCoreStateStore()
        val controller = targetController(
            listener = listener,
            store = store,
            nowEpochMillis = { testScheduler.currentTime + 1_000L },
            pairingHelloTimeoutMillis = 1_000L,
            pairingLifetimeMillis = 10_000L,
            trust = trust,
        )
        runCurrent()

        assertTrue(stranger.closed)
        assertEquals(2, listener.acceptCount)
        assertFalse(listener.closed)
        assertEquals(NaviampConnectPairingUiPhase.Inactive, store.state.value.shell.connect.pairingPhase)
        controller.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun discoveredTargetExpiresWithoutAnotherNativeCallback() = runTest {
        val discovery = RecordingDiscoveryEffect()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(
            scope = this,
            stateStore = store,
            services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
                deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
                displayName = "Pixel",
                identity = FakeIdentity,
                identityVerifier = FakeIdentityVerifier,
                transport = UnusedTransportFactory,
                pake = UnusedPakeFactory,
                cipher = UnusedCipherFactory,
                trust = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}),
                discovery = discovery,
                newOpaqueId = { "unused" },
                newPairingCode = { "123456" },
                nowEpochMillis = { testScheduler.currentTime + 1_000L },
            ),
        )

        controller.actions.onConnectScreenVisibilityChanged(true)
        discovery.listener.onServiceResolved(
            NaviampConnectResolvedService(
                serviceName = "Living Room",
                addresses = listOf("192.0.2.10"),
                port = 42_425,
                textAttributes = NaviampConnectDiscoveryMetadata.encode(
                    NaviampConnectAdvertisement(
                        instanceId = "tv-instance",
                        displayName = "Living Room",
                        protocolRange = NaviampConnectProtocolRange(),
                        capabilities = emptySet(),
                        port = 42_425,
                        identityFingerprint = "tv-fingerprint",
                        expiresAtEpochMillis = Long.MAX_VALUE,
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(1, store.state.value.shell.connect.discoveredTargets.size)

        advanceTimeBy(120_000L)
        runCurrent()

        assertTrue(store.state.value.shell.connect.discoveredTargets.isEmpty())
        controller.close()
    }

    @Test
    fun disconnectedRemoteSelectionBlocksPlaybackButAllowsBrowsingAndExplicitLocalOutput() = runTest {
        val (controller, store, _) = remoteFixture { error("write failed") }
        controller.actions.onRemotePlayPause()
        runCurrent()
        val select = NaviampCoreCommand.Media.TrackAction(app.naviamp.ui.SharedTrackRowActionRequest(
            app.naviamp.ui.SharedTrackRowUi("song", "Song", "Artist"), app.naviamp.ui.SharedTrackRowAction.Select))
        val queue = NaviampCoreCommand.Media.TrackAction(app.naviamp.ui.SharedTrackRowActionRequest(
            app.naviamp.ui.SharedTrackRowUi("song", "Song", "Artist"), app.naviamp.ui.SharedTrackRowAction.AddToQueue))
        val resume = NaviampCoreCommand.NowPlaying.Playback(app.naviamp.ui.NowPlayingPlaybackActionRequest(
            app.naviamp.ui.NowPlayingPlaybackAction.Resume))
        assertTrue(controller.routeProductCommand(select))
        assertTrue(controller.routeProductCommand(queue))
        assertTrue(controller.routeProductCommand(resume))
        assertFalse(controller.routeProductCommand(NaviampCoreCommand.Media.TrackAction(
            app.naviamp.ui.SharedTrackRowActionRequest(app.naviamp.ui.SharedTrackRowUi("song", "Song", "Artist"),
                app.naviamp.ui.SharedTrackRowAction.Download))))
        assertEquals(app.naviamp.ui.NaviampConnectStatusNotice.RemoteUnavailable, store.state.value.shell.connect.notice)
        controller.actions.onPlaybackDeviceSelected(null)
        assertFalse(controller.routeProductCommand(select))
        controller.close()
    }

    @Test
    fun remoteAlbumTrackSelectionSendsTheChosenTrackWithinItsAlbum() = runTest {
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
        val registry = NaviampCoreMediaRegistry()
        val tracks = (1..4).map { n ->
            app.naviamp.domain.Track(id = app.naviamp.domain.TrackId("album-$n"), title = "Track $n", artistName = "Artist",
                albumTitle = "Album", durationSeconds = 180, coverArtId = null, audioInfo = null, replayGain = null)
        }
        registry.updateAlbum(app.naviamp.domain.AlbumDetails(app.naviamp.domain.Album(app.naviamp.domain.AlbumId("album"),
            "Album", "Artist", coverArtId = null, recentlyAddedAtIso8601 = null), tracks))
        val identity = app.naviamp.domain.connect.NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val (controller, _, _) = remoteFixture(sourceIdentity = { identity },
            trackSelectionContext = registry::connectTrackSelectionContext) { sent += it }

        assertTrue(controller.routeProductCommand(NaviampCoreCommand.Detail.AlbumTrack(
            app.naviamp.ui.SharedTrackRowActionRequest(app.naviamp.ui.SharedTrackRowUi("album-3", "Track 3", "Artist"),
                app.naviamp.ui.SharedTrackRowAction.Select))))
        runCurrent()

        val start = sent.map { it.message }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectCommandRequest>()
            .map { it.command }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectStartMedia>().single()
        assertEquals(app.naviamp.domain.connect.NaviampConnectMediaType.Track, start.mediaType)
        assertEquals("album-3", start.mediaId)
        assertEquals(app.naviamp.domain.connect.NaviampConnectPlaybackContext(
            app.naviamp.domain.connect.NaviampConnectMediaType.Album, "album"), start.context)
        assertEquals(identity, start.sourceIdentity)
        controller.close()
    }

    @Test
    fun remoteAlbumAndPlaylistStartsCarryTheControllersSavedPlaybackProfile() = runTest {
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
        val gapless = app.naviamp.domain.playback.PlaybackProfile(app.naviamp.domain.playback.PlaybackTransitionMode.Gapless)
        val saved = mapOf(
            app.naviamp.domain.playback.PlaybackProfileTarget(app.naviamp.domain.playback.PlaybackProfileTargetType.Album, "album") to gapless,
            app.naviamp.domain.playback.PlaybackProfileTarget(app.naviamp.domain.playback.PlaybackProfileTargetType.Playlist, "mix") to gapless,
            app.naviamp.domain.playback.PlaybackProfileTarget(app.naviamp.domain.playback.PlaybackProfileTargetType.Playlist, "inherited") to
                app.naviamp.domain.playback.PlaybackProfile(),
        )
        val identity = app.naviamp.domain.connect.NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val (controller, _, _) = remoteFixture(sourceIdentity = { identity }, savedPlaybackProfile = { saved[it] }) { sent += it }
        fun starts() = sent.map { it.message }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectCommandRequest>()
            .map { it.command }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectStartMedia>()

        assertTrue(controller.routeProductCommand(NaviampCoreCommand.Detail.Album(app.naviamp.ui.NaviampAlbumDetailActionRequest(
            app.naviamp.ui.SharedMediaItemUi("album", "Album", "Artist"), app.naviamp.ui.NaviampAlbumDetailCommand.Play(shuffle = false)))))
        assertFalse(controller.routeProductCommand(NaviampCoreCommand.Detail.Album(app.naviamp.ui.NaviampAlbumDetailActionRequest(
            app.naviamp.ui.SharedMediaItemUi("album", "Album", "Artist"), app.naviamp.ui.NaviampAlbumDetailCommand.StartRadio))),
            "Radio defers to Core's complete generated-station recipe")
        assertTrue(controller.routeProductCommand(NaviampCoreCommand.Playlists.Detail(app.naviamp.ui.NaviampPlaylistDetailActionRequest(
            app.naviamp.ui.SharedMediaItemUi("mix", "Mix", ""), app.naviamp.ui.NaviampPlaylistDetailCommand.Play(shuffle = false)))))
        assertTrue(controller.routeProductCommand(NaviampCoreCommand.Playlists.Detail(app.naviamp.ui.NaviampPlaylistDetailActionRequest(
            app.naviamp.ui.SharedMediaItemUi("inherited", "Inherited", ""), app.naviamp.ui.NaviampPlaylistDetailCommand.Play(shuffle = false)))))
        runCurrent()

        val (album, playlist, inherited) = starts()
        assertEquals(gapless, album.playbackProfile?.toPlaybackProfile())
        assertEquals(gapless, playlist.playbackProfile?.toPlaybackProfile())
        assertEquals(null, inherited.playbackProfile, "An inherited profile is not sent, so the target keeps its own")
        controller.close()
    }

    @Test
    fun remoteTracksChosenInsideCollectionsCarryTheirProfiles() = runTest {
        val gapless = app.naviamp.domain.playback.PlaybackProfile(app.naviamp.domain.playback.PlaybackTransitionMode.Gapless)
        val identity = app.naviamp.domain.connect.NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        for ((type, targetType) in listOf(
            app.naviamp.domain.connect.NaviampConnectMediaType.Album to app.naviamp.domain.playback.PlaybackProfileTargetType.Album,
            app.naviamp.domain.connect.NaviampConnectMediaType.Playlist to app.naviamp.domain.playback.PlaybackProfileTargetType.Playlist,
        )) {
            val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
            val target = app.naviamp.domain.playback.PlaybackProfileTarget(targetType, "collection")
            val (controller, _, _) = remoteFixture(
                sourceIdentity = { identity },
                trackSelectionContext = { app.naviamp.domain.connect.NaviampConnectPlaybackContext(type, "collection") },
                savedPlaybackProfile = { gapless.takeIf { _ -> it == target } },
            ) { sent += it }
            val request = app.naviamp.ui.SharedTrackRowActionRequest(
                app.naviamp.ui.SharedTrackRowUi("track-3", "Track 3", "Artist"), app.naviamp.ui.SharedTrackRowAction.Select)
            val command = if (type == app.naviamp.domain.connect.NaviampConnectMediaType.Album)
                NaviampCoreCommand.Detail.AlbumTrack(request) else NaviampCoreCommand.Detail.PlaylistTrack(request)
            assertTrue(controller.routeProductCommand(command))
            runCurrent()

            val start = sent.map { it.message }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectCommandRequest>()
                .map { it.command }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectStartMedia>().single()
            assertEquals(gapless, start.playbackProfile?.toPlaybackProfile(), "A selected track carries its $type profile")
            controller.close()
        }
    }

    @Test
    fun remoteQueueAddsCarryTheControllersCollectionProfileAfterPlaybackAuthorityIsActive() = runTest {
        val gapless = app.naviamp.domain.playback.PlaybackProfile(app.naviamp.domain.playback.PlaybackTransitionMode.Gapless)
        val identity = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectCommand>()
        lateinit var activeSession: NaviampConnectControllerSession
        var revision = 0L
        val (controller, store, session) = remoteFixture(
            sourceIdentity = { identity },
            savedPlaybackProfile = { if (it.id == "inherited") app.naviamp.domain.playback.PlaybackProfile() else gapless },
            extraCapabilities = setOf(NaviampConnectCapability.QueueEdit),
        ) { envelope ->
            sent += (envelope.message as NaviampConnectCommandRequest).command
            revision++
            activeSession.receive(NaviampConnectEnvelope(1, "session", revision,
                responseToRequestId = envelope.requestId,
                message = app.naviamp.domain.connect.NaviampConnectAcknowledgement(revision)))
        }
        activeSession = session
        assertTrue(controller.routeProductCommand(NaviampCoreCommand.Media.TrackAction(
            app.naviamp.ui.SharedTrackRowActionRequest(app.naviamp.ui.SharedTrackRowUi("track", "Track", "Artist"),
                app.naviamp.ui.SharedTrackRowAction.Select))))
        runCurrent()
        assertTrue(store.state.value.shell.connect.remotePlaybackAuthorityActive)

        assertTrue(controller.routeProductCommand(NaviampCoreCommand.Detail.Album(app.naviamp.ui.NaviampAlbumDetailActionRequest(
            app.naviamp.ui.SharedMediaItemUi("album", "Album", "Artist"), app.naviamp.ui.NaviampAlbumDetailCommand.AddToQueue))))
        for (id in listOf("mix", "inherited")) {
            assertTrue(controller.routeProductCommand(NaviampCoreCommand.Playlists.Detail(app.naviamp.ui.NaviampPlaylistDetailActionRequest(
                app.naviamp.ui.SharedMediaItemUi(id, id, ""), app.naviamp.ui.NaviampPlaylistDetailCommand.AddToQueue))))
        }
        runCurrent()

        val queued = sent.filterIsInstance<app.naviamp.domain.connect.NaviampConnectQueueMedia>()
        assertEquals(listOf("album", "mix", "inherited"), queued.map { it.mediaId })
        assertEquals(listOf(gapless, gapless, null), queued.map { it.playbackProfile?.toPlaybackProfile() })
        controller.close()
    }

    @Test
    fun anIdlePeerThatStopsRespondingTriggersReconnect() = runTest {
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
        val (controller, store, session) = remoteFixture { sent += it }
        advanceTimeBy(15_001); runCurrent()
        assertTrue(sent.any { it.message is app.naviamp.domain.connect.NaviampConnectPing })
        assertEquals(NaviampConnectControllerConnectionStatus.Disconnected, session.state.value.status)
        assertEquals(app.naviamp.ui.NaviampConnectPlaybackDestinationUiStatus.Reconnecting,
            store.state.value.shell.connect.playbackDestinationStatus)
        assertEquals(app.naviamp.ui.NaviampConnectStatusNotice.ConnectionTimedOut, store.state.value.shell.connect.notice)
        controller.close()
    }

    @Test
    fun ordinaryCommandsHaveAcknowledgementDeadlinesEvenWhenThePeerAnswersHeartbeats() = runTest {
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
        val (controller, store, session) = remoteFixture { sent += it }
        controller.actions.onRemotePlayPause()
        runCurrent()
        advanceTimeBy(5_001); runCurrent()
        val ping = sent.map { it.message }.filterIsInstance<app.naviamp.domain.connect.NaviampConnectPing>().single()
        session.receive(app.naviamp.domain.connect.NaviampConnectEnvelope(1, sessionId = "session", sequence = 0,
            message = app.naviamp.domain.connect.NaviampConnectPong(ping.sentAtEpochMillis)))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(app.naviamp.ui.NaviampConnectStatusNotice.ConnectionTimedOut, store.state.value.shell.connect.notice)
        assertEquals(app.naviamp.app.NaviampConnectRequestTerminalResult.TimedOut,
            session.state.value.terminalResults[sent.first().requestId])
        controller.close()
    }

    @Test
    fun aBlockedWriteHasADeadlineAndCannotLeaveTheOutputConnected() = runTest {
        val (controller, store, session) = remoteFixture { awaitCancellation() }
        controller.actions.onRemotePlayPause()
        runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(NaviampConnectControllerConnectionStatus.Disconnected, session.state.value.status)
        assertEquals(app.naviamp.ui.NaviampConnectStatusNotice.ConnectionTimedOut, store.state.value.shell.connect.notice)
        controller.close()
    }

    @Test
    fun selectingLocalCancelsHeartbeatAndOldCommandDeadlines() = runTest {
        val (controller, store, _) = remoteFixture { }
        controller.actions.onRemotePlayPause()
        runCurrent()
        controller.actions.onPlaybackDeviceSelected(null)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(app.naviamp.ui.NaviampConnectPlaybackDestinationUiStatus.Local,
            store.state.value.shell.connect.playbackDestinationStatus)
        assertNull(store.state.value.shell.connect.notice)
        controller.close()
    }

    @Test
    fun missingSetupCredentialCanBeCancelledAndRetriedWithoutSendingAnOffer() = runTest {
        val port = SetupSourcePort("")
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
        val (controller, store, _) = remoteFixture(providerSessions = port) { sent += it }
        controller.actions.onProvisionTarget()
        runCurrent()
        assertTrue(store.state.value.shell.connect.needsProvisioningCredential)
        assertFalse(store.state.value.shell.connect.provisioningBusy)
        assertTrue(sent.isEmpty())
        controller.actions.onCancelProvisioningCredential()
        assertFalse(store.state.value.shell.connect.needsProvisioningCredential)
        controller.actions.onProvisionTarget()
        runCurrent()
        assertTrue(store.state.value.shell.connect.needsProvisioningCredential)
        controller.close()
    }

    @Test
    fun provisioningHasOneOutstandingOfferAndATimeoutAllowsRecovery() = runTest {
        val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
        lateinit var session: NaviampConnectControllerSession
        var sequence = 0L
        val fixture = remoteFixture(providerSessions = SetupSourcePort("secret")) { envelope ->
            val ping = envelope.message as? app.naviamp.domain.connect.NaviampConnectPing
            if (ping == null) sent += envelope else session.receive(app.naviamp.domain.connect.NaviampConnectEnvelope(
                1, "session", ++sequence, message = app.naviamp.domain.connect.NaviampConnectPong(ping.sentAtEpochMillis)))
        }
        val (controller, store, connectedSession) = fixture
        session = connectedSession
        controller.actions.onProvisionTarget()
        controller.actions.onProvisionTarget()
        runCurrent()
        assertTrue(store.state.value.shell.connect.provisioningBusy)
        val request = sent.single().message as app.naviamp.domain.connect.NaviampConnectCommandRequest
        val offer = request.command as app.naviamp.domain.connect.NaviampConnectOfferConnectionProvisioning
        assertFalse(offer.initialSetup, "An adopted/trusted session has no initial setup grant")
        assertNotNull(offer.setupId)
        assertEquals("secret", offer.profile.password)
        advanceTimeBy(60_001)
        runCurrent()
        assertFalse(store.state.value.shell.connect.provisioningBusy)
        assertFalse(store.state.value.shell.connect.needsProvisioningCredential)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.SetupTimedOut, store.state.value.shell.connect.statusMessage?.text)
        controller.close()
    }

    @Test
    fun disconnectCancelsCredentialLoadingWithoutPublishingIntoTheClosedSession() = runTest {
        val port = SetupSourcePort("secret", suspendExport = true)
        val (controller, store, _) = remoteFixture(providerSessions = port) { error("must not send") }
        controller.actions.onProvisionTarget()
        runCurrent()
        assertTrue(store.state.value.shell.connect.provisioningBusy)
        controller.actions.onStopControlling()
        runCurrent()
        assertTrue(port.cancelled)
        assertFalse(store.state.value.shell.connect.provisioningBusy)
        assertFalse(store.state.value.shell.connect.needsProvisioningCredential)
        assertNull(store.state.value.shell.connect.connectedTargetName)
        controller.close()
    }

    @Test fun companionTransferRequiresTargetSupportAndSendsTheIndependentAccount() = runTest {
        val companion = app.naviamp.domain.source.AurralCompanion(true, "https://aurral.example", "secondary", "companion-secret")
        for (supported in listOf(false, true)) {
            val sent = mutableListOf<app.naviamp.domain.connect.NaviampConnectEnvelope>()
            val (controller, store, _) = remoteFixture(providerSessions = SetupSourcePort("primary-secret", companion = companion),
                companionProvisioningSupported = supported) { sent += it }
            controller.actions.onProvisionTarget()
            runCurrent()
            if (supported) {
                val request = sent.single().message as app.naviamp.domain.connect.NaviampConnectCommandRequest
                val offer = request.command as app.naviamp.domain.connect.NaviampConnectOfferConnectionProvisioning
                assertEquals("companion-secret", offer.profile.aurralCompanion?.password)
                assertEquals("primary-secret", offer.profile.password)
            } else {
                assertTrue(sent.isEmpty(), "An older target must not silently receive only the primary connection")
                assertFalse(store.state.value.shell.connect.provisioningBusy)
            }
            controller.close()
        }
    }

    @Test
    fun favoriteTracksAreHandedOffPlayingWithoutReplacingTheLocalQueue() = runTest {
        val source = NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener")
        val track = FakeCoreMediaProvider().track
        val favorites = listOf("favorite-a", "favorite-b").map { track.copy(id = TrackId(it), title = it) }
        val local = NaviampLivePlaybackController(NaviampLivePlaybackState(
            queue = PlaybackQueue(listOf(track), 0), currentTrack = track, playbackState = PlaybackState.Paused,
        ))
        val before = local.state.value
        val sent = mutableListOf<NaviampConnectEnvelope>()
        val (controller, _, _) = remoteFixture(
            queueHandoffSupported = true, sourceIdentity = { source }, localPlayback = local,
        ) { sent += it }

        assertTrue(controller.playTracksOnSelectedDevice(favorites, index = 1))
        runCurrent()

        val request = sent.single().message as NaviampConnectCommandRequest
        val handoff = request.command as NaviampConnectHandoffQueue
        assertEquals(favorites.map { it.id.value }, handoff.queue.occurrences.map { it.mediaId })
        assertEquals(source, handoff.sourceIdentity)
        assertEquals(1, handoff.queue.currentIndex)
        assertEquals(0L, handoff.positionMillis)
        assertTrue(handoff.playing, "A newly selected favorite queue must play even when local playback is paused")
        assertFalse(handoff.shuffled)
        assertEquals(before, local.state.value)
        controller.close()
    }

    @Test
    fun unavailableSelectedTargetConsumesFavoritePlaybackWithoutLocalFallback() = runTest {
        val track = FakeCoreMediaProvider().track
        val local = NaviampLivePlaybackController(NaviampLivePlaybackState(
            queue = PlaybackQueue(listOf(track), 0), currentTrack = track,
        ))
        val (controller, store, session) = remoteFixture(
            queueHandoffSupported = true,
            sourceIdentity = { NaviampConnectSourceIdentity("navidrome", "https://music.example", "listener") },
            localPlayback = local,
        ) { error("Target is unavailable") }
        controller.actions.onRemotePlayPause()
        runCurrent()
        assertEquals(NaviampConnectControllerConnectionStatus.Disconnected, session.state.value.status)
        assertNotNull(store.state.value.shell.connect.selectedPlaybackDeviceId)
        val before = local.state.value

        assertTrue(controller.playTracksOnSelectedDevice(listOf(track.copy(id = TrackId("favorite")))))
        runCurrent()

        assertEquals(before, local.state.value)
        assertEquals(app.naviamp.ui.NaviampConnectStatusNotice.RemoteUnavailable, store.state.value.shell.connect.notice)
        controller.actions.onPlaybackDeviceSelected(null)
        assertFalse(controller.playTracksOnSelectedDevice(listOf(track)))
        assertEquals(before, local.state.value)
        controller.close()
    }

    @Test
    fun selectedTargetWithoutSourceIdentityConsumesFavoritesWithoutSendingAQueue() = runTest {
        val sent = mutableListOf<NaviampConnectEnvelope>()
        val (controller, _, _) = remoteFixture(queueHandoffSupported = true) { sent += it }

        assertTrue(controller.playTracksOnSelectedDevice(listOf(FakeCoreMediaProvider().track)))
        runCurrent()

        assertTrue(sent.isEmpty())
        controller.close()
    }

    private fun kotlinx.coroutines.test.TestScope.remoteFixture(
        discovery: NaviampConnectDiscoveryEffect? = null,
        permissionSettings: app.naviamp.app.NaviampConnectPermissionSettingsEffect? = null,
        providerSessions: NaviampCoreProviderSessionPort? = null,
        companionProvisioningSupported: Boolean = false,
        queueHandoffSupported: Boolean = false,
        localPlayback: NaviampLivePlaybackController? = null,
        sourceIdentity: () -> app.naviamp.domain.connect.NaviampConnectSourceIdentity? = { null },
        trackSelectionContext: (NaviampCoreCommand) -> app.naviamp.domain.connect.NaviampConnectPlaybackContext? = { null },
        savedPlaybackProfile: (app.naviamp.domain.playback.PlaybackProfileTarget) -> app.naviamp.domain.playback.PlaybackProfile? = { null },
        extraCapabilities: Set<app.naviamp.domain.connect.NaviampConnectCapability> = emptySet(),
        stationSessionVersion: Int = 0,
        generatedRadio: NaviampCoreConnectGeneratedRadioPort? = null,
        send: suspend (app.naviamp.domain.connect.NaviampConnectEnvelope) -> Unit,
    ): Triple<NaviampCoreConnectController, NaviampCoreStateStore, NaviampConnectControllerSession> {
        var stored: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = stored
            override fun write(value: String) { stored = value }
        })
        val target = NaviampConnectDevice("tv", "TV", NaviampConnectDeviceRole.Target)
        trust.upsert(NaviampConnectTrustRecord("trusted-tv", target, "fingerprint", "public-key", 1L))
        val store = NaviampCoreStateStore()
        val playback = localPlayback?.let { live ->
            val defaults = fakeCoreServices()
            val queue = NaviampPlaybackQueueCoordinator(live)
            NaviampCorePlaybackController(
                scope = this,
                stateStore = store,
                providerSource = defaults.content.providerSource,
                playback = live,
                queue = queue,
                effects = defaults.playback.effects,
                settings = defaults.playback.settings,
                sidecars = defaults.playback.sidecars,
                sessions = defaults.playback.sessions,
                presenter = NaviampCoreNowPlayingPresenter(store, defaults.content.providerSource,
                    live, queue, defaults.playback.effects, defaults.playback.sidecars),
                nowEpochMillis = { testScheduler.currentTime },
            )
        }
        val controller = NaviampCoreConnectController(this, store, NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback), displayName = "Controller",
            identity = FakeIdentity, identityVerifier = FakeIdentityVerifier, transport = UnusedTransportFactory,
            pake = UnusedPakeFactory, cipher = UnusedCipherFactory, trust = trust,
            discovery = discovery, permissionSettings = permissionSettings,
            newOpaqueId = { "unused" }, newPairingCode = { "123456" }, nowEpochMillis = { testScheduler.currentTime },
        ), providerSessions = providerSessions, sourceIdentity = sourceIdentity, localPlayback = playback,
            trackSelectionContext = trackSelectionContext, generatedRadio = generatedRadio,
            savedPlaybackProfile = savedPlaybackProfile)
        if (discovery != null) controller.actions.onConnectScreenVisibilityChanged(true)
        var next = 0
        val session = NaviampConnectControllerSession(NaviampConnectSessionTransport(send),
            NaviampConnectRequestIdFactory { "request-${++next}" })
        session.connect("session", 1, target, buildSet {
            add(NaviampConnectCapability.TransportControls)
            add(NaviampConnectCapability.ConnectionProvisioning)
            add(NaviampConnectCapability.CatalogPlayback)
            addAll(extraCapabilities)
            if (queueHandoffSupported) add(NaviampConnectCapability.QueueHandoff)
        },
            NaviampConnectTargetSnapshot(0, target, emptySet(), stationSessionVersion = stationSessionVersion),
            stationSessionVersion = stationSessionVersion)
        controller.adoptControllerSession(session, companionProvisioningSupported)
        return Triple(controller, store, session)
    }

    @Test
    fun openingConnectScreenStartsAndClosingStopsDiscovery() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        assertEquals(1, fixture.discovery.startCount)
        assertTrue(fixture.store.state.value.shell.connect.connectScreenVisible)
        fixture.controller.actions.onConnectScreenVisibilityChanged(false)
        assertTrue(fixture.discovery.stopCount >= 1)
        assertFalse(fixture.store.state.value.shell.connect.connectScreenVisible)
        fixture.controller.close()
    }

    @Test
    fun closingConnectScreenKeepsDiscoveryForAPendingTrustedReconnect() = runTest {
        val fixture = connectScreenFixture(trustedTv = true)
        fixture.controller.actions.onTrustedDeviceSelected(fixture.store.state.value.shell.connect.trustedDevices.single())
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        val stops = fixture.discovery.stopCount
        fixture.controller.actions.onConnectScreenVisibilityChanged(false)
        assertEquals(stops, fixture.discovery.stopCount, "A pending trusted reconnect still needs discovery")
        fixture.controller.close()
    }

    @Test
    fun sixthDigitSubmitsToTheOnlyDeviceShowingACode() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.resolve("Living Room", "192.0.2.10", "tv-fp", pairable = true)
        fixture.resolve("Kitchen", "192.0.2.11", "kitchen-fp", pairable = false)
        runCurrent()
        assertEquals(listOf("Living Room"), fixture.store.state.value.shell.connect.discoveredTargets.map { it.displayName })
        fixture.controller.actions.onPairingCodeChanged("12345")
        runCurrent()
        assertTrue(fixture.attempts.isEmpty())
        fixture.controller.actions.onPairingCodeChanged("123456")
        runCurrent()
        assertEquals(listOf("192.0.2.10" to 42_425), fixture.attempts)
        fixture.controller.close()
    }

    @Test
    fun codeTypedBeforeDiscoveryWaitsForTheDevice() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.controller.actions.onPairingCodeChanged("123456")
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(fixture.attempts.isEmpty())
        fixture.resolve("Living Room", "192.0.2.10", "tv-fp", pairable = true)
        runCurrent()
        assertEquals(listOf("192.0.2.10" to 42_425), fixture.attempts)
        fixture.controller.close()
    }

    @Test
    fun codeTypedWithNoDeviceShowingACodeReportsItAfterWaiting() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.controller.actions.onPairingCodeChanged("123456")
        advanceTimeBy(5_001L)
        runCurrent()
        assertTrue(fixture.attempts.isEmpty())
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.NoDeviceShowingACode,
            fixture.store.state.value.shell.connect.statusMessage?.text)
        fixture.controller.close()
    }

    @Test
    fun multipleDevicesShowingCodesRequireAChoice() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.resolve("Living Room", "192.0.2.10", "tv-fp", pairable = true)
        fixture.resolve("Bedroom", "192.0.2.12", "bedroom-fp", pairable = true)
        runCurrent()
        fixture.controller.actions.onPairingCodeChanged("123456")
        runCurrent()
        assertTrue(fixture.attempts.isEmpty())
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ChooseTheDeviceShowingThisCode,
            fixture.store.state.value.shell.connect.statusMessage?.text)
        assertEquals("123456", fixture.store.state.value.shell.connect.enteredPairingCode)
        fixture.controller.actions.onTargetSelected(
            fixture.store.state.value.shell.connect.discoveredTargets.single { it.displayName == "Bedroom" })
        runCurrent()
        assertEquals(listOf("192.0.2.12" to 42_425), fixture.attempts)
        fixture.controller.close()
    }

    @Test
    fun aRequestArrivingAfterExpiryKeepsAcceptingConnectionsAndShowsANewCode() = runTest {
        var wallClock = 1_000L
        val listener = QueuedListener(42425)
        val store = NaviampCoreStateStore()
        val codes = sequenceOf("111111", "222222").iterator()
        val controller = targetController(
            listener = listener,
            store = store,
            nowEpochMillis = { wallClock },
            pairingHelloTimeoutMillis = 1_000L,
            pairingLifetimeMillis = 10_000L,
            newPairingCode = codes::next,
        )
        controller.actions.onStartPairingMode()
        runCurrent()
        assertEquals("111111", store.state.value.shell.connect.pairingCode)
        wallClock = 20_000L // The wall clock jumped past expiry before the expiry job ran.
        listener.connections.trySend(ScriptedConnection(app.naviamp.domain.connect.NaviampConnectEnvelope(
            protocolVersion = 1,
            sequence = 0,
            message = app.naviamp.domain.connect.NaviampConnectHello(
                device = NaviampConnectDevice("late", "Late phone", NaviampConnectDeviceRole.Controller),
                identity = app.naviamp.domain.connect.NaviampConnectPublicIdentity("late", "late-fp", "late-key"),
                protocolRange = NaviampConnectProtocolRange(),
                capabilities = emptySet(),
            ),
        )))
        runCurrent()
        val next = ClosedConnection()
        listener.connections.trySend(next)
        runCurrent()
        assertTrue(next.closed, "The listener must keep accepting after a late request")
        assertEquals("222222", store.state.value.shell.connect.pairingCode)
        controller.close()
    }

    @Test
    fun typingACodeStopsAutomaticReconnectToAnotherDevice() = runTest {
        val fixture = connectScreenFixture(trustedTv = true)
        fixture.controller.actions.onTrustedDeviceSelected(fixture.store.state.value.shell.connect.trustedDevices.single())
        fixture.resolve("Living Room", "192.0.2.10", "tv-fp", pairable = false)
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()
        assertTrue(fixture.attempts.any { it.first == "192.0.2.10" }, "The selected TV is being reconnected")
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.resolve("Bedroom", "192.0.2.12", "bedroom-fp", pairable = true)
        runCurrent()
        fixture.controller.actions.onPairingCodeChanged("123456")
        runCurrent()
        fixture.attempts.clear()
        advanceTimeBy(6_000L)
        runCurrent()
        assertTrue(fixture.attempts.none { it.first == "192.0.2.10" }, "Entering a code for another device stops the old reconnect")
        fixture.controller.close()
    }

    @Test
    fun theOneDeviceAnnouncingACodeIsChosenOverOlderDevices() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.resolve("Living Room", "192.0.2.10", "tv-fp", pairable = true)
        fixture.resolveLegacy("Old Bedroom TV", "192.0.2.13", "old-fp")
        runCurrent()
        assertEquals(2, fixture.store.state.value.shell.connect.discoveredTargets.size)
        fixture.controller.actions.onPairingCodeChanged("123456")
        runCurrent()
        assertEquals(listOf("192.0.2.10" to 42_425), fixture.attempts)
        fixture.controller.close()
    }

    @Test
    fun aCodeIsNeverSentToAnOlderDeviceWithoutATap() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        // The device showing the code is briefly missing; only an older device is listed.
        fixture.resolveLegacy("Old Bedroom TV", "192.0.2.13", "old-fp")
        runCurrent()
        fixture.controller.actions.onPairingCodeChanged("123456")
        advanceTimeBy(5_001L)
        runCurrent()
        assertTrue(fixture.attempts.isEmpty(), "An older device cannot say it shows this code")
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ChooseTheDeviceShowingThisCode,
            fixture.store.state.value.shell.connect.statusMessage?.text)
        fixture.controller.actions.onTargetSelected(fixture.store.state.value.shell.connect.discoveredTargets.single())
        runCurrent()
        assertEquals(listOf("192.0.2.13" to 42_425), fixture.attempts, "Tapping it sends the code")
        fixture.controller.close()
    }

    @Test
    fun theDeviceShowingTheCodeIsUsedOnceItReappears() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        fixture.resolveLegacy("Old Bedroom TV", "192.0.2.13", "old-fp")
        runCurrent()
        fixture.controller.actions.onPairingCodeChanged("123456")
        runCurrent()
        fixture.resolve("Living Room", "192.0.2.10", "tv-fp", pairable = true)
        runCurrent()
        assertEquals(listOf("192.0.2.10" to 42_425), fixture.attempts)
        fixture.controller.close()
    }

    @Test
    fun aTrustedDeviceWhoseSecretCannotBeReadAsksForANewCode() = runTest {
        val fixture = connectScreenFixture(trustedTv = true, readableCredential = false)
        val device = fixture.store.state.value.shell.connect.trustedDevices.single()
        fixture.controller.actions.onManualTrustedEndpointSelected(device, "192.0.2.10:42425")
        runCurrent()
        assertTrue(fixture.attempts.isEmpty())
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.ConnectAgainWithANewCode,
            fixture.store.state.value.shell.connect.statusMessage?.text)
        fixture.controller.close()
    }

    @Test
    fun closingTheConnectPanelClearsTheSearchingStatus() = runTest {
        val fixture = connectScreenFixture()
        fixture.controller.actions.onConnectScreenVisibilityChanged(true)
        assertEquals(app.naviamp.ui.NaviampConnectStatusText.SearchingThisLocalNetwork,
            fixture.store.state.value.shell.connect.statusMessage?.text)
        fixture.controller.actions.onConnectScreenVisibilityChanged(false)
        assertNull(fixture.store.state.value.shell.connect.statusMessage)
        fixture.controller.close()
    }

    @Test
    fun closingThePanelWhileTheIdentityLoadsStartsNoOffer() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val identity = object : NaviampConnectDeviceIdentityEffect {
            override fun loadOrCreate(): NaviampConnectDeviceIdentity {
                kotlinx.coroutines.runBlocking { gate.await() }
                return FakeIdentity.loadOrCreate()
            }
            override fun sign(payload: ByteArray) = byteArrayOf(1)
        }
        val store = NaviampCoreStateStore()
        val listeners = mutableListOf<WaitingListener>()
        val identityDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val controller = secureStorageFixture(this, store, identity, identityDispatcher, listeners) { testScheduler.currentTime }
        controller.actions.onStartPairingMode()
        controller.actions.onStopPairingMode()
        gate.complete(Unit)
        runCurrent()
        assertTrue(listeners.isEmpty(), "A panel closed before the identity loaded must not start an offer")
        assertNull(store.state.value.shell.connect.pairingCode)
        controller.close()
    }

    @Test
    fun retryingSecureStorageShowsTheCodeForTheStillOpenPanel() = runTest {
        var locked = true
        val identity = object : NaviampConnectDeviceIdentityEffect {
            override fun loadOrCreate(): NaviampConnectDeviceIdentity {
                if (locked) throw IllegalStateException("locked")
                return FakeIdentity.loadOrCreate()
            }
            override fun sign(payload: ByteArray) = byteArrayOf(1)
        }
        val store = NaviampCoreStateStore()
        val listeners = mutableListOf<WaitingListener>()
        val controller = secureStorageFixture(this, store, identity, kotlinx.coroutines.Dispatchers.Unconfined, listeners) {
            testScheduler.currentTime
        }
        runCurrent()
        controller.actions.onStartPairingMode()
        runCurrent()
        locked = false
        controller.actions.onRetryConnection()
        runCurrent()
        assertEquals("123456", store.state.value.shell.connect.pairingCode)
        controller.close()
    }

    @Test
    fun aValidRequestJustAfterExpiryStillLeavesANewCode() = runTest {
        var wallClock = 1_000L
        val listener = QueuedListener(42425)
        val store = NaviampCoreStateStore()
        val codes = sequenceOf("111111", "222222").iterator()
        val controller = targetController(
            listener = listener, store = store, nowEpochMillis = { wallClock },
            pairingHelloTimeoutMillis = 1_000L, pairingLifetimeMillis = 10_000L, newPairingCode = codes::next,
        )
        controller.actions.onStartPairingMode()
        runCurrent()
        wallClock = 20_000L
        listener.connections.trySend(ScriptedConnection(app.naviamp.domain.connect.NaviampConnectEnvelope(
            protocolVersion = 1, sequence = 0,
            message = app.naviamp.domain.connect.NaviampConnectHello(
                device = NaviampConnectDevice("late", "Late phone", NaviampConnectDeviceRole.Controller),
                // FakeIdentityVerifier fingerprints every key as "fingerprint": this hello is valid.
                identity = app.naviamp.domain.connect.NaviampConnectPublicIdentity("late", "fingerprint", "late-key"),
                protocolRange = NaviampConnectProtocolRange(),
                capabilities = emptySet(),
            ),
        )))
        runCurrent()
        assertEquals("222222", store.state.value.shell.connect.pairingCode)
        controller.close()
    }

    @Test
    fun aStalledHandshakeReleasesCodeEntry() = runTest {
        val stalled = StalledConnection()
        val discovery = RecordingDiscoveryEffect()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(this, store, NaviampCoreConnectServices(
            networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
            displayName = "Pixel", identity = FakeIdentity, identityVerifier = FakeIdentityVerifier,
            transport = object : NaviampConnectTransportFactory {
                override suspend fun connect(host: String, port: Int): NaviampConnectTransportConnection = stalled
                override fun listen(port: Int): NaviampConnectTransportListener = error("unused")
            },
            pake = UnusedPakeFactory, cipher = UnusedCipherFactory,
            trust = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}), discovery = discovery,
            newOpaqueId = { "opaque" }, newPairingCode = { "123456" },
            nowEpochMillis = { testScheduler.currentTime + 1_000L }, pairingHandshakeTimeoutMillis = 1_000L,
        ))
        controller.actions.onConnectScreenVisibilityChanged(true)
        discovery.listener.onServiceResolved(NaviampConnectResolvedService(
            serviceName = "Living Room", addresses = listOf("192.0.2.10"), port = 42_425,
            textAttributes = NaviampConnectDiscoveryMetadata.encode(NaviampConnectAdvertisement(
                instanceId = "tv-instance", displayName = "Living Room", protocolRange = NaviampConnectProtocolRange(),
                capabilities = emptySet(), port = 42_425, identityFingerprint = "tv-fp", expiresAtEpochMillis = Long.MAX_VALUE,
            )),
        ))
        runCurrent()
        controller.actions.onPairingCodeChanged("123456")
        runCurrent()
        assertTrue(store.state.value.shell.connect.codeEntryBusy)
        advanceTimeBy(1_001L)
        runCurrent()
        assertFalse(store.state.value.shell.connect.codeEntryBusy, "A peer that stops responding must not block code entry")
        assertTrue(stalled.closed)
        controller.close()
    }

    private class ConnectScreenFixture(
        val controller: NaviampCoreConnectController,
        val store: NaviampCoreStateStore,
        val discovery: RecordingDiscoveryEffect,
        val attempts: MutableList<Pair<String, Int>>,
    ) {
        /** A protocol-v1 device: its TXT has no "showing a code" flag at all. */
        fun resolveLegacy(name: String, address: String, fingerprint: String) {
            discovery.listener.onServiceResolved(NaviampConnectResolvedService(
                serviceName = name, addresses = listOf(address), port = 42_425,
                textAttributes = NaviampConnectDiscoveryMetadata.encode(NaviampConnectAdvertisement(
                    instanceId = "$fingerprint-instance", displayName = name,
                    protocolRange = NaviampConnectProtocolRange(), capabilities = emptySet(), port = 42_425,
                    identityFingerprint = fingerprint, expiresAtEpochMillis = Long.MAX_VALUE,
                )) - "code",
            ))
        }

        fun resolve(name: String, address: String, fingerprint: String, pairable: Boolean) {
            discovery.listener.onServiceResolved(NaviampConnectResolvedService(
                serviceName = name, addresses = listOf(address), port = 42_425,
                textAttributes = NaviampConnectDiscoveryMetadata.encode(NaviampConnectAdvertisement(
                    instanceId = "$fingerprint-instance", displayName = name,
                    protocolRange = NaviampConnectProtocolRange(), capabilities = emptySet(), port = 42_425,
                    identityFingerprint = fingerprint, expiresAtEpochMillis = Long.MAX_VALUE, pairable = pairable,
                )),
            ))
        }
    }

    private fun kotlinx.coroutines.test.TestScope.connectScreenFixture(
        trustedTv: Boolean = false,
        readableCredential: Boolean = true,
    ): ConnectScreenFixture {
        var storedTrust: String? = null
        val trust = NaviampConnectTrustRepository(object : NaviampConnectTrustStorageEffect {
            override fun read() = storedTrust
            override fun write(value: String) { storedTrust = value }
        })
        if (trustedTv) trust.upsert(NaviampConnectTrustRecord("trusted-tv",
            NaviampConnectDevice("tv", "Living Room", NaviampConnectDeviceRole.Target), "tv-fp", "tv-key", 1L))
        val discovery = RecordingDiscoveryEffect()
        val attempts = mutableListOf<Pair<String, Int>>()
        val store = NaviampCoreStateStore()
        val controller = NaviampCoreConnectController(this, store, NaviampCoreConnectServices(
            networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = setOf(NaviampConnectDeviceCapability.ControlPlayback),
            displayName = "Pixel", identity = FakeIdentity, identityVerifier = FakeIdentityVerifier,
            transport = object : NaviampConnectTransportFactory {
                override suspend fun connect(host: String, port: Int): NaviampConnectTransportConnection {
                    attempts += host to port
                    error("unreachable")
                }
                override fun listen(port: Int): NaviampConnectTransportListener = error("unused")
            },
            pake = UnusedPakeFactory, cipher = UnusedCipherFactory, trust = trust,
            credentials = app.naviamp.app.NaviampConnectSessionCredentialRepository(
                object : app.naviamp.app.NaviampConnectSessionCredentialStorageEffect {
                    override fun read(peerDeviceId: String) = byteArrayOf(1, 2, 3).takeIf { readableCredential }
                    override fun write(peerDeviceId: String, value: ByteArray) = Unit
                    override fun remove(peerDeviceId: String) = Unit
                    override fun contains(peerDeviceId: String) = true
                },
            ),
            discovery = discovery,
            newOpaqueId = { "opaque" }, newPairingCode = { "123456" },
            nowEpochMillis = { testScheduler.currentTime + 1_000L },
        ))
        return ConnectScreenFixture(controller, store, discovery, attempts)
    }

    private fun CoroutineScope.targetController(
        listener: NaviampConnectTransportListener,
        store: NaviampCoreStateStore,
        nowEpochMillis: () -> Long,
        pairingHelloTimeoutMillis: Long,
        pairingLifetimeMillis: Long,
        newPairingCode: () -> String = { "123456" },
        trust: NaviampConnectTrustRepository = NaviampConnectTrustRepository(NaviampConnectTrustStorageEffect {}),
        advertising: NaviampConnectAdvertisingEffect = RecordingAdvertisingEffect(),
    ) = NaviampCoreConnectController(
        scope = this,
        stateStore = store,
        services = NaviampCoreConnectServices(
                networkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            deviceCapabilities = NaviampCorePlaybackTargetConnectCapabilities,
            displayName = "Living Room TV",
            identity = FakeIdentity,
            identityVerifier = FakeIdentityVerifier,
            transport = object : NaviampConnectTransportFactory {
                override suspend fun connect(host: String, port: Int) = error("unused")
                override fun listen(port: Int) = listener
            },
            pake = UnusedPakeFactory,
            cipher = UnusedCipherFactory,
            trust = trust,
            advertising = advertising,
            newOpaqueId = generateSequence(0) { it + 1 }.map { "opaque-$it" }.iterator()::next,
            newPairingCode = newPairingCode,
            nowEpochMillis = nowEpochMillis,
            pairingLifetimeMillis = pairingLifetimeMillis,
            pairingHelloTimeoutMillis = pairingHelloTimeoutMillis,
        ),
    )
}

private class RecordingAdvertisingEffect : NaviampConnectAdvertisingEffect {
    var service: NaviampConnectRegistrationService? = null

    override fun start(
        service: NaviampConnectRegistrationService,
        listener: NaviampConnectAdvertisingListener,
    ): NaviampConnectAdvertisingStartResult {
        this.service = service
        listener.onServiceRegistered(service.serviceName)
        return NaviampConnectAdvertisingStartResult.Started
    }

    override fun stop() = Unit
}

private class WaitingListener(override val port: Int) : NaviampConnectTransportListener {
    var closed = false
    override suspend fun accept(): NaviampConnectTransportConnection = awaitCancellation()
    override fun close() { closed = true }
}

private class QueuedListener(override val port: Int) : NaviampConnectTransportListener {
    val connections = Channel<NaviampConnectTransportConnection>(Channel.UNLIMITED)
    var acceptCount = 0
    var closed = false

    override suspend fun accept(): NaviampConnectTransportConnection {
        acceptCount += 1
        return connections.receive()
    }

    override fun close() {
        closed = true
        connections.close()
    }
}

private class StalledConnection : NaviampConnectTransportConnection {
    override val remoteAddress = "stalled.test"
    var closed = false

    override suspend fun send(frame: ByteArray) = Unit
    override suspend fun receive(): ByteArray? = awaitCancellation()
    override fun close() { closed = true }
}

private class ScriptedConnection(envelope: app.naviamp.domain.connect.NaviampConnectEnvelope) : NaviampConnectTransportConnection {
    override val remoteAddress = "scripted.test"
    var closed = false
    private val frames = ArrayDeque(listOf(app.naviamp.app.NaviampConnectTransportPacketCodec.encode(
        app.naviamp.app.NaviampConnectTransportPacket.Plaintext(envelope))))

    override suspend fun send(frame: ByteArray) = Unit
    override suspend fun receive(): ByteArray? = if (closed) null else frames.removeFirstOrNull() ?: awaitCancellation()
    override fun close() { closed = true }
}

private class ClosedConnection : NaviampConnectTransportConnection {
    override val remoteAddress = "closed.test"
    var closed = false

    override suspend fun send(frame: ByteArray) = Unit
    override suspend fun receive(): ByteArray? = null
    override fun close() { closed = true }
}

private class RecordingDiscoveryEffect : NaviampConnectDiscoveryEffect {
    lateinit var listener: NaviampConnectDiscoveryListener
    var startCount = 0
    var stopCount = 0

    override fun start(listener: NaviampConnectDiscoveryListener): NaviampConnectDiscoveryStartResult {
        this.listener = listener
        startCount += 1
        return NaviampConnectDiscoveryStartResult.Started
    }

    override fun stop() {
        stopCount += 1
    }
}

internal object UnusedTransportFactory : NaviampConnectTransportFactory {
    override suspend fun connect(host: String, port: Int): NaviampConnectTransportConnection = error("unused")
    override fun listen(port: Int): NaviampConnectTransportListener = error("unused")
}

internal object FakeIdentity : NaviampConnectDeviceIdentityEffect {
    override fun loadOrCreate() = NaviampConnectDeviceIdentity("target-id", "fingerprint", "public-key")
    override fun sign(payload: ByteArray) = byteArrayOf(1)
}

internal object FakeIdentityVerifier : NaviampConnectIdentityVerifier {
    override fun fingerprint(publicKeyBase64: String) = "fingerprint"
    override fun verify(publicKeyBase64: String, payload: ByteArray, signature: ByteArray) = true
}

internal object UnusedPakeFactory : NaviampConnectPakeFactory {
    override fun create(
        pairingSessionId: String,
        protocolVersion: Int,
        localRole: NaviampConnectPakeRole,
        localDeviceId: String,
        remoteDeviceId: String,
        pairingCode: CharArray,
    ): NaviampConnectPakeSession = error("unused")
}

internal object UnusedCipherFactory : NaviampConnectAuthenticatedCipherFactory {
    override fun create(
        sessionSecret: NaviampConnectSessionSecret,
        role: NaviampConnectPakeRole,
        protocolVersion: Int,
        sessionId: String,
    ): NaviampConnectAuthenticatedCipher = error("unused")
}

private class SetupSourcePort(private val password: String, private val suspendExport: Boolean = false,
    private val companion: app.naviamp.domain.source.AurralCompanion? = null) : NaviampCoreProviderSessionPort {
    var cancelled = false
    override fun currentSourceId() = "source"
    override suspend fun currentProvisioningConnection(): NaviampCoreEditableConnection {
        if (suspendExport) try { awaitCancellation() } finally { cancelled = true }
        return editableConnection("source")
    }
    override suspend fun editableConnection(id: String) = NaviampCoreEditableConnection(
        app.naviamp.domain.settings.ConnectionFormState(serverUrl = "https://fixture.invalid", username = "fixture", password = password,
            aurralCompanion = companion))
    override suspend fun connect(request: NaviampCoreConnectionRequest, plan: app.naviamp.app.NaviampConnectionAttemptPlan): NaviampCoreConnectedSession = error("unused")
    override suspend fun deleteConnection(id: String) = NaviampCoreConnectionInventory()
    override suspend fun smartPlaylistProvider(password: String?) = null
    override suspend fun refreshActiveSession() = true
    override suspend fun persistActiveSession() = Unit
    override suspend fun clearActiveSession() = Unit
}
