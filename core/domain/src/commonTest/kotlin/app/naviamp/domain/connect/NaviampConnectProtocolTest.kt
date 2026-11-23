package app.naviamp.domain.connect

import app.naviamp.domain.radio.GeneratedRadioCheckpoint
import app.naviamp.domain.radio.GeneratedRadioKind
import app.naviamp.domain.radio.GeneratedRadioPlaybackMode
import app.naviamp.domain.radio.GeneratedRadioStation
import app.naviamp.domain.radio.RadioQueueOccurrence
import app.naviamp.domain.radio.RadioTuningSettings
import app.naviamp.domain.settings.SavedArtist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NaviampConnectProtocolTest {
    @Test
    fun legacyWelcomeAndSnapshotDoNotClaimStationSupport() {
        val welcome = NaviampConnectWelcome("session", 1, targetDevice(), emptySet(),
            snapshot(0).copy(stationSessionVersion = 1), stationSessionVersion = 1)
        val encoded = NaviampConnectWireCodec.encode(NaviampConnectEnvelope(1, "session", 1, message = welcome))
        val legacy = encoded.replace(",\"stationSessionVersion\":1", "")
        val decoded = assertIs<NaviampConnectWelcome>(NaviampConnectWireCodec.decode(legacy).message)
        assertEquals(0, decoded.stationSessionVersion)
        assertEquals(0, decoded.snapshot.stationSessionVersion)
        assertEquals(emptySet(), decoded.capabilities)
    }

    @Test
    fun fullMultiSeedRecipeAndFiniteCheckpointHaveDistinctWireCommands() {
        val recipe = GeneratedRadioStation(
            id = "station-local-id", name = "Evening", kind = GeneratedRadioKind.Artists,
            sourceIdentity = sourceIdentity(), revision = 9,
            artists = listOf(SavedArtist("second", "Second"), SavedArtist("first", "First")),
            fromYear = 1980, toYear = 1989, sonicSimilarity = true, batchSize = 40,
            tuning = RadioTuningSettings(sameDecadeOnly = true, sameArtistRunLength = 4),
        )
        val checkpoint = GeneratedRadioCheckpoint(
            station = recipe, mode = GeneratedRadioPlaybackMode.FiniteBatch, wasPaused = true,
            endlessEnabled = false, seenTrackIds = listOf("heard-1", "heard-2"),
            occurrences = listOf(RadioQueueOccurrence("occurrence-1", recipe.id, "batch-7")),
        )
        val commands = listOf<NaviampConnectCommand>(
            NaviampConnectStationStart(sourceIdentity(), recipe, endlessEnabled = false),
            NaviampConnectStationHandoff(sourceIdentity(), checkpoint, snapshot(0).queue,
                12_345, NaviampConnectRepeatMode.All, shuffled = true, playing = false),
            NaviampConnectStationRetry(recipe.id, recipe.revision),
        )
        commands.forEach { command ->
            val envelope = NaviampConnectEnvelope(1, "session", 1, message = NaviampConnectCommandRequest(command, 3))
            val encoded = NaviampConnectWireCodec.encode(envelope)
            assertEquals(envelope, NaviampConnectWireCodec.decode(encoded))
            assertFalse(encoded.contains("handoff_queue"))
            assertFalse(encoded.contains("start_media"))
            assertTrue(command.requiresStationSession())
        }
        val legacy = NaviampConnectHandoffQueue(sourceIdentity(), snapshot(0).queue, 0,
            NaviampConnectRepeatMode.Off, shuffled = false)
        assertFalse(legacy.requiresStationSession())
    }

    @Test
    fun anonymousFiniteHandoffRoundTripsItsExactCheckpointWithoutLegacyDowngrade() {
        val checkpoint = GeneratedRadioCheckpoint(mode = GeneratedRadioPlaybackMode.FiniteBatch,
            wasPaused = true, endlessEnabled = false, seenTrackIds = listOf("heard"),
            occurrences = listOf(RadioQueueOccurrence("occurrence-1")))
        val handoff = NaviampConnectStationHandoff(sourceIdentity(), checkpoint, snapshot(0).queue,
            42_000, NaviampConnectRepeatMode.Off, shuffled = false, playing = false)
        val envelope = NaviampConnectEnvelope(1, "session", 1, message = NaviampConnectCommandRequest(handoff, 3))
        val encoded = NaviampConnectWireCodec.encode(envelope)
        val decoded = assertIs<NaviampConnectStationHandoff>(
            assertIs<NaviampConnectCommandRequest>(NaviampConnectWireCodec.decode(encoded).message).command)
        assertEquals(handoff, decoded)
        assertEquals(checkpoint, decoded.checkpoint)
        assertNull(decoded.checkpoint.station)
        assertTrue(decoded.requiresStationSession())
        assertFalse(encoded.contains("handoff_queue"))
    }

    @Test
    fun anonymousTransferStillRequiresFiniteModeSourceAndMatchingOccurrences() {
        val checkpoint = GeneratedRadioCheckpoint(mode = GeneratedRadioPlaybackMode.FiniteBatch,
            endlessEnabled = false, occurrences = listOf(RadioQueueOccurrence("occurrence-1")))
        fun transfer(value: GeneratedRadioCheckpoint, identity: NaviampConnectSourceIdentity = sourceIdentity()) =
            NaviampConnectStationHandoff(identity, value, snapshot(0).queue,
                0, NaviampConnectRepeatMode.Off, shuffled = false)
        assertFailsWith<IllegalArgumentException> { transfer(checkpoint.copy(mode = GeneratedRadioPlaybackMode.Station)) }
        assertFailsWith<IllegalArgumentException> { transfer(checkpoint.copy(occurrences = emptyList())) }
        assertFailsWith<IllegalArgumentException> {
            transfer(checkpoint.copy(occurrences = listOf(RadioQueueOccurrence("wrong-occurrence"))))
        }
        assertFailsWith<IllegalArgumentException> { transfer(checkpoint, sourceIdentity().copy(accountIdentity = " ")) }
    }

    @Test
    fun stationTransferRejectsWrongSourceAndMismatchedOccurrenceAttribution() {
        val recipe = GeneratedRadioStation("station", "Station", GeneratedRadioKind.Library,
            sourceIdentity = sourceIdentity())
        assertFailsWith<IllegalArgumentException> {
            NaviampConnectStationStart(sourceIdentity().copy(accountIdentity = "other"), recipe)
        }
        assertFailsWith<IllegalArgumentException> {
            NaviampConnectStationHandoff(sourceIdentity(), GeneratedRadioCheckpoint(station = recipe,
                occurrences = listOf(RadioQueueOccurrence("wrong-occurrence", recipe.id, "batch"))),
                snapshot(0).queue, 0, NaviampConnectRepeatMode.Off, shuffled = false)
        }
        assertFailsWith<IllegalArgumentException> {
            NaviampConnectStationStart(sourceIdentity(), recipe.copy(artists = List(65) { SavedArtist("$it", "$it") }))
        }
    }

    @Test fun companionNegotiationIsAdditiveAndOlderOffersHaveNoCompanion() {
        val welcome = NaviampConnectWelcome("session", 1, targetDevice(), emptySet(), snapshot(0),
            companionProvisioningSupported = true)
        val envelope = NaviampConnectEnvelope(1, "session", 1, message = welcome)
        val encoded = NaviampConnectWireCodec.encode(envelope)
        assertEquals(envelope, NaviampConnectWireCodec.decode(encoded))
        val legacy = encoded.replace(",\"companionProvisioningSupported\":true", "")
        assertFalse((NaviampConnectWireCodec.decode(legacy).message as NaviampConnectWelcome).companionProvisioningSupported)
        val oldOffer = NaviampConnectEnvelope(1, "session", 1, message = NaviampConnectCommandRequest(
            NaviampConnectOfferConnectionProvisioning(NaviampConnectProvisioningProfile(
                "navidrome", "Home", "https://primary.example", "primary", "secret"))))
        val decoded = NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(oldOffer))
        assertNull(((decoded.message as NaviampConnectCommandRequest).command as NaviampConnectOfferConnectionProvisioning).profile.aurralCompanion)
    }

    @Test
    fun trackSelectionContextIsAdditiveAndLegacyCommandsStaySingleTrack() {
        val selected = NaviampConnectStartMedia(
            mediaType = NaviampConnectMediaType.Track,
            mediaId = "track-3",
            sourceIdentity = sourceIdentity(),
            context = NaviampConnectPlaybackContext(NaviampConnectMediaType.Album, "album"),
        )
        val envelope = NaviampConnectEnvelope(1, "session", 1, message = NaviampConnectCommandRequest(selected))
        val encoded = NaviampConnectWireCodec.encode(envelope)
        assertEquals(envelope, NaviampConnectWireCodec.decode(encoded))

        val legacyEncoded = encoded.replace(",\"context\":{\"mediaType\":\"Album\",\"mediaId\":\"album\"}", "")
        assertFalse("context" in legacyEncoded)
        val legacy = (NaviampConnectWireCodec.decode(legacyEncoded).message as NaviampConnectCommandRequest)
            .command as NaviampConnectStartMedia
        assertNull(legacy.context)
        assertEquals(selected.copy(context = null), legacy)

        val withoutContext = NaviampConnectWireCodec.encode(
            NaviampConnectEnvelope(1, "session", 1, message = NaviampConnectCommandRequest(selected.copy(context = null))),
        )
        assertFalse("context" in withoutContext, "An absent context must stay off the wire for older peers")
    }

    @Test
    fun setupRejectionIsAdditiveAndLegacyFailureRemainsUnclassified() {
        val result = NaviampConnectConnectionProvisioningResult(false, "", "setup", rejected = true)
        val envelope = NaviampConnectEnvelope(1, "session", 1, message = result)
        val encoded = NaviampConnectWireCodec.encode(envelope)
        assertEquals(envelope, NaviampConnectWireCodec.decode(encoded))
        val legacy = encoded.replace(",\"rejected\":true", "")
        val decoded = NaviampConnectWireCodec.decode(legacy).message as NaviampConnectConnectionProvisioningResult
        assertFalse(decoded.rejected)
        assertFalse(decoded.succeeded)
        assertEquals("setup", decoded.setupId)
    }

    @Test
    fun initialSetupAuthorizationAndResultsAreAdditiveAndCorrelated() {
        val oldWelcome = NaviampConnectWelcome("session", 1, targetDevice(), emptySet(), snapshot(0))
        val offered = oldWelcome.copy(initialSetupAllowed = true)
        for (message in listOf(oldWelcome, offered, NaviampConnectConnectionProvisioningResult(true, "", "setup-1"))) {
            val envelope = NaviampConnectEnvelope(1, "session", 1, message = message)
            assertEquals(envelope, NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(envelope)))
        }
        val envelope = NaviampConnectEnvelope(1, "session", 1, message = offered)
        val legacy = NaviampConnectWireCodec.encode(envelope).replace(",\"initialSetupAllowed\":true", "")
        assertFalse((NaviampConnectWireCodec.decode(legacy).message as NaviampConnectWelcome).initialSetupAllowed)
    }

    @Test
    fun deviceCapabilitiesAreIndependentFromTheActiveSessionRole() {
        val capabilities = setOf(
            NaviampConnectDeviceCapability.ControlPlayback,
            NaviampConnectDeviceCapability.PlaybackTarget,
        )
        val controllingPhone = NaviampConnectDevice(
            deviceId = "phone",
            displayName = "Pixel",
            role = NaviampConnectDeviceRole.Controller,
            deviceCapabilities = capabilities,
        )
        val playingPhone = controllingPhone.copy(role = NaviampConnectDeviceRole.Target)

        assertTrue(controllingPhone.canActAs(NaviampConnectDeviceRole.Controller))
        assertTrue(controllingPhone.canActAs(NaviampConnectDeviceRole.Target))
        assertEquals(capabilities, playingPhone.deviceCapabilities)
        assertFailsWith<IllegalArgumentException> {
            NaviampConnectDevice(
                deviceId = "tv",
                displayName = "Television",
                role = NaviampConnectDeviceRole.Controller,
                deviceCapabilities = setOf(NaviampConnectDeviceCapability.PlaybackTarget),
            )
        }
    }

    @Test
    fun negotiationSelectsTheHighestMutuallySupportedVersion() {
        assertEquals(
            3,
            negotiateNaviampConnectProtocol(
                NaviampConnectProtocolRange(1, 3),
                NaviampConnectProtocolRange(2, 4),
            ),
        )
        assertNull(
            negotiateNaviampConnectProtocol(
                NaviampConnectProtocolRange(1, 2),
                NaviampConnectProtocolRange(3, 4),
            ),
        )
    }

    @Test
    fun sourceIdentityIgnoresCaseOrderAndPresentationNoise() {
        val controller = NaviampConnectSourceIdentity(
            providerId = " Navidrome ",
            canonicalServerOrigin = "HTTPS://MUSIC.EXAMPLE.TEST/",
            accountIdentity = "Listener",
            libraryIds = listOf("rock", "jazz", "rock"),
        )
        val target = NaviampConnectSourceIdentity(
            providerId = "navidrome",
            canonicalServerOrigin = "https://music.example.test",
            accountIdentity = "listener",
            libraryIds = listOf("jazz", "rock"),
        )

        assertTrue(controller.isCompatibleWith(target))
        assertFalse(controller.isCompatibleWith(target.copy(accountIdentity = "someone-else")))
    }

    @Test
    fun versionedEnvelopeRoundTripsQueueOccurrencesAndGroups() {
        val original = NaviampConnectEnvelope(
            protocolVersion = 1,
            sessionId = "session",
            sequence = 7,
            requestId = "request",
            message = NaviampConnectSnapshotMessage(snapshot(revision = 9)),
        )

        val decoded = NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(original))

        assertEquals(original, decoded)
        val snapshot = assertIs<NaviampConnectSnapshotMessage>(decoded.message).snapshot
        assertEquals("occurrence-1", snapshot.queue.occurrences.single().occurrenceId)
        assertEquals("group-1", snapshot.queue.groups.single().groupId)
    }

    @Test
    fun clearUpNextCommandRoundTripsOnTheAuthenticatedWire() {
        val original = NaviampConnectEnvelope(
            protocolVersion = 1,
            sessionId = "session",
            sequence = 8,
            requestId = "clear",
            message = NaviampConnectCommandRequest(NaviampConnectClearUpNext, expectedRevision = 4),
        )

        assertEquals(original, NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(original)))
    }

    @Test
    fun pairingWireMessageContainsOnlyAnOpaqueHandshakePayload() {
        val encoded = NaviampConnectWireCodec.encode(
            NaviampConnectEnvelope(
                protocolVersion = 1,
                sequence = 0,
                message = NaviampConnectPairingHandshake(
                    pairingSessionId = "pairing",
                    step = 1,
                    payloadBase64 = "opaque-pake-output",
                ),
            ),
        )

        assertTrue(encoded.contains("opaque-pake-output"))
        assertFalse(encoded.contains("displayCode"))
        assertFalse(encoded.contains("password"))
    }

    @Test
    fun pairingOfferAndIdentityProofRoundTripPublicMaterialOnly() {
        val identity = NaviampConnectPublicIdentity(
            deviceId = "target",
            identityFingerprint = "fingerprint",
            publicKeyBase64 = "public-key",
        )
        val messages = listOf<NaviampConnectMessage>(
            NaviampConnectPairingOffer("pairing", targetDevice(), identity),
            NaviampConnectPairingIdentityProof(identity, "signature"),
            NaviampConnectPairingConfirmation("fingerprint"),
            NaviampConnectSessionReplaced,
            NaviampConnectConnectionProvisioningResult(true, "TV setup completed securely."),
        )

        messages.forEachIndexed { index, message ->
            val original = NaviampConnectEnvelope(1, "session", index.toLong(), message = message)
            assertEquals(original, NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(original)))
        }
    }

    @Test
    fun resumptionWireMessagesBindTheFreshControllerChallenge() {
        val controller = NaviampConnectDevice(
            deviceId = "controller",
            displayName = "Phone",
            role = NaviampConnectDeviceRole.Controller,
        )
        val target = targetDevice()
        val controllerIdentity = NaviampConnectPublicIdentity("controller", "controller-fingerprint", "controller-key")
        val targetIdentity = NaviampConnectPublicIdentity("target", "target-fingerprint", "target-key")
        val messages = listOf<NaviampConnectMessage>(
            NaviampConnectResumeHello(
                controller,
                controllerIdentity,
                NaviampConnectProtocolRange(),
                controllerNonce = "fresh-controller-challenge",
            ),
            NaviampConnectResumeOffer(
                sessionId = "resume-session",
                controllerNonce = "fresh-controller-challenge",
                protocolVersion = 1,
                target = target,
                identity = targetIdentity,
            ),
        )

        messages.forEachIndexed { index, message ->
            val original = NaviampConnectEnvelope(1, sequence = index.toLong(), message = message)
            assertEquals(original, NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(original)))
        }
        assertFailsWith<IllegalArgumentException> {
            NaviampConnectResumeHello(controller, controllerIdentity, NaviampConnectProtocolRange(), "")
        }
    }

    @Test
    fun wireRoundTripPreservesADeviceThatCanControlAndPlay() {
        val device = NaviampConnectDevice(
            deviceId = "desktop",
            displayName = "Office Mac",
            role = NaviampConnectDeviceRole.Controller,
            deviceCapabilities = setOf(
                NaviampConnectDeviceCapability.ControlPlayback,
                NaviampConnectDeviceCapability.PlaybackTarget,
            ),
        )
        val identity = NaviampConnectPublicIdentity("desktop", "fingerprint", "public-key")
        val envelope = NaviampConnectEnvelope(
            protocolVersion = 1,
            sequence = 0,
            message = NaviampConnectHello(
                device = device,
                identity = identity,
                protocolRange = NaviampConnectProtocolRange(),
                capabilities = emptySet(),
            ),
        )

        val decoded = NaviampConnectWireCodec.decode(NaviampConnectWireCodec.encode(envelope))

        assertEquals(device, assertIs<NaviampConnectHello>(decoded.message).device)
    }

    @Test
    fun protocolV1DeviceWithoutCapabilitiesUsesItsSessionRoleAsTheFallback() {
        val decoded = NaviampConnectWireCodec.decode(
            """{
                "protocolVersion":1,
                "sequence":0,
                "message":{
                    "type":"hello",
                    "device":{"deviceId":"legacy","displayName":"Legacy Phone","role":"Controller"},
                    "identity":{
                        "deviceId":"legacy",
                        "identityFingerprint":"fingerprint",
                        "publicKeyBase64":"public-key"
                    },
                    "protocolRange":{"minimum":1,"maximum":1},
                    "capabilities":[]
                }
            }""".trimIndent(),
        )

        val device = assertIs<NaviampConnectHello>(decoded.message).device
        assertEquals(
            setOf(NaviampConnectDeviceCapability.ControlPlayback),
            device.deviceCapabilities,
        )
    }

    @Test
    fun everyRemoteCommandDeclaresItsRequiredCapability() {
        val commands = listOf<NaviampConnectCommand>(
            NaviampConnectPlay,
            NaviampConnectSeek(1_000),
            NaviampConnectSetFavorite("track", true),
            NaviampConnectSetRepeat(NaviampConnectRepeatMode.All),
            NaviampConnectSetShuffle(true),
            NaviampConnectSelectQueueOccurrence("occurrence"),
            NaviampConnectMoveQueueOccurrence("occurrence"),
            NaviampConnectRemoveQueueOccurrence("occurrence"),
            NaviampConnectClearUpNext,
            NaviampConnectRequestSnapshot,
            NaviampConnectShowSurface(NaviampConnectTargetSurface.Queue),
            NaviampConnectStartMedia(
                mediaType = NaviampConnectMediaType.Album,
                mediaId = "album",
                sourceIdentity = sourceIdentity(),
            ),
            NaviampConnectQueueMedia(
                mediaType = NaviampConnectMediaType.Track,
                mediaId = "track",
                placement = NaviampConnectQueuePlacement.PlayNext,
                sourceIdentity = sourceIdentity(),
            ),
            NaviampConnectOfferConnectionProvisioning(
                NaviampConnectProvisioningProfile(
                    providerId = "navidrome",
                    displayName = "Home",
                    serverUrl = "https://music.example.test",
                    username = "listener",
                    password = "secret",
                ),
            ),
            NaviampConnectHandoffQueue(
                sourceIdentity = sourceIdentity(),
                queue = NaviampConnectQueueSnapshot(),
                positionMillis = 0,
                repeatMode = NaviampConnectRepeatMode.Off,
                shuffled = false,
            ),
        )

        assertTrue(commands.all { it.requiredCapability() != null })
    }

    @Test
    fun queueSnapshotsRejectAmbiguousOccurrencesAndInvalidIndexes() {
        val occurrence = NaviampConnectQueueOccurrence(
            occurrenceId = "same-occurrence",
            mediaId = "track",
            title = "Track",
            artistName = "Artist",
        )

        assertFailsWith<IllegalArgumentException> {
            NaviampConnectQueueSnapshot(
                occurrences = listOf(occurrence, occurrence),
                currentIndex = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NaviampConnectQueueSnapshot(
                occurrences = listOf(occurrence),
                currentIndex = -1,
            )
        }
    }

    private fun snapshot(revision: Long) = NaviampConnectTargetSnapshot(
        revision = revision,
        target = targetDevice(),
        capabilities = setOf(NaviampConnectCapability.QueueRead),
        sourceIdentity = sourceIdentity(),
        queue = NaviampConnectQueueSnapshot(
            occurrences = listOf(
                NaviampConnectQueueOccurrence(
                    occurrenceId = "occurrence-1",
                    mediaId = "track-1",
                    title = "Track",
                    artistName = "Artist",
                ),
            ),
            currentIndex = 0,
            groups = listOf(
                NaviampConnectQueueGroup(
                    groupId = "group-1",
                    startIndex = 0,
                    endIndexExclusive = 1,
                    targetType = app.naviamp.domain.playback.PlaybackProfileTargetType.Album,
                    targetId = "album-1",
                ),
            ),
        ),
    )

    private fun sourceIdentity() = NaviampConnectSourceIdentity(
        providerId = "navidrome",
        canonicalServerOrigin = "https://music.example.test",
        accountIdentity = "listener",
    )

    private fun targetDevice() = NaviampConnectDevice(
        deviceId = "target",
        displayName = "Living Room",
        role = NaviampConnectDeviceRole.Target,
    )
}
