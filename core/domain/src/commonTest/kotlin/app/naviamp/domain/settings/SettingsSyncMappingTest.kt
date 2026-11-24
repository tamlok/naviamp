package app.naviamp.domain.settings
import app.naviamp.domain.radio.withRadioSimilarityMethod

import app.naviamp.domain.cache.MediaSourceRepository
import app.naviamp.domain.cache.ProviderMediaSourceConnection
import app.naviamp.domain.cache.ProviderMediaSourceRepository
import app.naviamp.domain.provider.ProviderIdAurral
import app.naviamp.domain.provider.ProviderIdJellyfin
import app.naviamp.domain.provider.ProviderIdNavidrome
import app.naviamp.domain.provider.ProviderIdSubsonic
import app.naviamp.domain.playback.ReplayGainMode
import app.naviamp.domain.radio.RadioDjPreset
import app.naviamp.domain.radio.RadioDjPresetRepository
import app.naviamp.domain.source.MediaSourceIdentity
import app.naviamp.domain.source.ConnectionHeaderDefinition
import app.naviamp.domain.source.ConnectionSecondaryUrl
import app.naviamp.domain.source.ConnectionTlsSettings
import app.naviamp.domain.source.SavedMediaSource
import app.naviamp.domain.source.stableMediaSourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class SettingsSyncMappingTest {
    @Test fun generatedStationsRoundTripAndLegacyImportsCannotEraseThem() {
        val station = app.naviamp.domain.radio.GeneratedRadioStation("station", "My mix", app.naviamp.domain.radio.GeneratedRadioKind.Artists,
            artists = listOf(SavedArtist("artist:one", "One"), SavedArtist("artist:two", "Two")), saved = true)
        val batch = app.naviamp.domain.radio.GeneratedRadioBatch("batch", station.id, station.revision, 42,
            listOf(SavedTrack("b", "B", artistName = "Artist"), SavedTrack("a", "A", artistName = "Artist")), station)
        val library = app.naviamp.domain.radio.GeneratedRadioLibrary(stations = listOf(station), batches = listOf(batch),
            preferences = app.naviamp.domain.radio.GeneratedRadioPreferences(false, 48))
        val exported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(generatedRadio = library), 3L, "sender")
        val decoded = SettingsSyncJson.decode(SettingsSyncJson.encode(exported))
        assertEquals(2, decoded.schemaVersion)
        val repository = RecordingProviderMediaSourceRepository()
        val djs = RecordingRadioDjPresetRepository()
        assertEquals(library, applySettingsSyncDocument(decoded, repository, djs).generatedRadio)
        val legacyDocument = Json.parseToJsonElement(SettingsSyncJson.encode(exported)).jsonObject
        val legacyPreferences = legacyDocument.getValue("preferences").jsonObject
        val legacyLibrary = legacyPreferences.getValue("generatedRadio").jsonObject
        val legacyStation = JsonObject(legacyLibrary.getValue("stations").jsonArray.single().jsonObject + ("pinned" to JsonPrimitive(true)))
        val legacyBatch = JsonObject(legacyLibrary.getValue("batches").jsonArray.single().jsonObject + ("resolvedRecipe" to legacyStation))
        val withObsoletePin = JsonObject(legacyDocument + ("preferences" to JsonObject(legacyPreferences +
            ("generatedRadio" to JsonObject(legacyLibrary + mapOf("stations" to JsonArray(listOf(legacyStation)),
                "batches" to JsonArray(listOf(legacyBatch))))))))
        assertEquals(library, applySettingsSyncDocument(SettingsSyncJson.decode(withObsoletePin.toString()), repository, djs).generatedRadio)
        val old = SettingsSyncJson.decode("""{"preferences":{}}""")
        assertEquals(library, applySettingsSyncDocument(old, repository, djs, localGeneratedRadio = library).generatedRadio)
        val cleared = decoded.copy(preferences = decoded.preferences.copy(generatedRadio = app.naviamp.domain.radio.GeneratedRadioLibrary()))
        assertTrue(applySettingsSyncDocument(cleared, repository, djs, localGeneratedRadio = library).generatedRadio.stations.isEmpty())
    }

    @Test
    fun freshImportsUseExactProviderNamespacesAndStableIdsForEveryLibraryScope() {
        for (providerId in listOf(ProviderIdNavidrome, ProviderIdSubsonic, ProviderIdAurral, ProviderIdJellyfin)) {
            val scopes = listOf(emptyList(), listOf(" folder-two ", "folder-one", "folder-two"), listOf("folder-three"))
            val profiles = scopes.mapIndexed { index, scope ->
                savedSource().toSettingsSyncServerProfile().copy(
                    id = "exported-$index",
                    providerId = providerId,
                    selectedMusicFolderIds = scope,
                )
            }
            val pins = profiles.flatMap { profile -> QuickAccessKind.entries.map { kind ->
                QuickAccessPin(profile.id, kind, "shared-item", "${profile.id} ${kind.name}")
            } }
            val exported = SettingsSyncDocument(serverProfiles = profiles, preferences = SettingsSyncPreferences(
                interfaceSettings = InterfaceSettings(language = InterfaceLanguage.German, quickAccess = pins),
            ))
            val repository = RecordingProviderMediaSourceRepository()
            val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(exported)),
                repository, RecordingRadioDjPresetRepository())
            val baseNamespace = "$providerId:https://navidrome.lan:ursasmar"
            val scopePrefix = if (providerId == ProviderIdJellyfin) ":libraries=" else ":folders="
            val namespaces = listOf(baseNamespace, "$baseNamespace${scopePrefix}folder-two,folder-one",
                "$baseNamespace${scopePrefix}folder-three")
            val sourceIds = namespaces.map(::stableMediaSourceId)

            assertEquals(namespaces, repository.cacheNamespaces)
            assertEquals(listOf<String?>(null, null, null), repository.preferredSourceIds)
            assertEquals(3, sourceIds.toSet().size)
            assertEquals(profiles.mapIndexed { index, profile -> profile.id to sourceIds[index] }.toMap(),
                applied.importedServerProfiles.sourceIdByProfileId)
            val expectedPins = pins.map { pin -> pin.copy(sourceId = sourceIds[profiles.indexOfFirst { it.id == pin.sourceId }]) }
            assertEquals(expectedPins, applied.interfaceSettings.quickAccess)
            assertEquals(InterfaceLanguage.German, applied.interfaceSettings.language)
            assertEquals(listOf(emptyList(), listOf("folder-two", "folder-one"), listOf("folder-three")),
                repository.connections.map { it.selectedMusicFolderIds })

            val reexported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(
                serverProfiles = repository.mediaSources(), interfaceSettings = applied.interfaceSettings,
            ), 2L, "receiver")
            val restored = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(reexported)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            assertEquals(expectedPins, restored.interfaceSettings.quickAccess)
        }
    }

    @Test
    fun existingIdentityAndNamespaceAreReusedAndPinsAreDeduplicatedAfterRemapping() {
        val local = savedSource().copy(id = "receiving-device-source")
        val otherScope = local.copy(id = "other-scope", cacheNamespace = "${local.cacheNamespace}:folders=other",
            selectedMusicFolderIds = listOf("other"))
        val repository = RecordingProviderMediaSourceRepository(listOf(otherScope, local))
        val profile = local.toSettingsSyncServerProfile().copy(id = "exported-source",
            selectedMusicFolderIds = listOf("folder-two", "folder-one"))
        val first = QuickAccessPin(profile.id, QuickAccessKind.Playlist, "item", "First title")
        val document = SettingsSyncDocument(
            serverProfiles = listOf(profile, profile.copy(id = "exported-alias")),
            preferences = SettingsSyncPreferences(interfaceSettings = InterfaceSettings(quickAccess = listOf(
                first.copy(sourceId = " exported-source ", itemId = " item ", title = " First title "),
                first.copy(sourceId = "exported-alias", title = "Duplicate target"),
                first.copy(kind = QuickAccessKind.Artist, title = "Artist"),
                first.copy(itemId = " "),
            ))),
        )

        val applied = applySettingsSyncDocument(document, repository, RecordingRadioDjPresetRepository())

        assertEquals(listOf<String?>(local.id, local.id), repository.preferredSourceIds)
        assertEquals(listOf(local.cacheNamespace, local.cacheNamespace), repository.cacheNamespaces)
        assertEquals(mapOf(profile.id to local.id, "exported-alias" to local.id),
            applied.importedServerProfiles.sourceIdByProfileId)
        assertEquals(listOf(first.copy(sourceId = local.id),
            first.copy(sourceId = local.id, kind = QuickAccessKind.Artist, title = "Artist")),
            applied.interfaceSettings.quickAccess)
        assertTrue(repository.connections.all { it.token == local.token && it.salt == local.salt && it.nativeToken == local.nativeToken })
    }

    @Test
    fun unmappedPinsAreRejectedEvenWhenAnExportedIdCoincidesWithALocalSource() {
        val local = savedSource().copy(id = "coincident-local-id")
        val repository = RecordingProviderMediaSourceRepository(listOf(local))
        val profile = local.toSettingsSyncServerProfile().copy(id = "exported-profile")
        val pin = QuickAccessPin(local.id, QuickAccessKind.Album, "item", "Must not cross-bind")
        val document = SettingsSyncDocument(
            serverProfiles = listOf(profile, profile.copy(id = "invalid-profile", primaryUrl = " ")),
            preferences = SettingsSyncPreferences(interfaceSettings = InterfaceSettings(
                language = InterfaceLanguage.German,
                quickAccess = listOf(pin, pin.copy(sourceId = "unknown"), pin.copy(sourceId = "invalid-profile")),
            )),
        )

        val applied = applySettingsSyncDocument(document, repository, RecordingRadioDjPresetRepository())

        assertEquals(mapOf(profile.id to local.id), applied.importedServerProfiles.sourceIdByProfileId)
        assertEquals(InterfaceSettings(language = InterfaceLanguage.German), applied.interfaceSettings)
        val withoutProfiles = applySettingsSyncDocument(document.copy(serverProfiles = emptyList()),
            repository, RecordingRadioDjPresetRepository())
        assertTrue(withoutProfiles.interfaceSettings.quickAccess.isEmpty())
        assertTrue(withoutProfiles.importedServerProfiles.sourceIdByProfileId.isEmpty())
    }

    @Test
    fun playlistResumeAppliesFromPortableSettingsAndSurvivesReexport() {
        for (enabled in listOf(true, false)) {
            val settings = PlaybackSettings(resumePlaylists = enabled, crossfadeDurationSeconds = 99)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = settings), 1L, "sender")
            val applied = applySettingsSyncDocument(
                SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
                RecordingProviderMediaSourceRepository(),
                RecordingRadioDjPresetRepository(),
                localPlayback = PlaybackSettings(resumePlaylists = !enabled),
            ).playbackSettings
            assertEquals(enabled, applied.resumePlaylists)
            assertEquals(12, applied.crossfadeDurationSeconds)
            val reexported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = applied), 2L, "receiver")
            assertEquals(enabled, SettingsSyncJson.decode(SettingsSyncJson.encode(reexported)).preferences.playback.resumePlaylists)
        }
        val older = applySettingsSyncDocument(
            SettingsSyncJson.decode("""{"preferences":{"playback":{"gaplessEnabled":false}}}"""),
            RecordingProviderMediaSourceRepository(),
            RecordingRadioDjPresetRepository(),
            localPlayback = PlaybackSettings(resumePlaylists = false),
        ).playbackSettings
        assertTrue(older.resumePlaylists)
        assertFalse(older.gaplessEnabled)
    }

    @Test
    fun themePreferencesApplyFromPortableSnapshotAndNormalizeAtTheApplyBoundary() {
        val settings = InterfaceSettings(language = InterfaceLanguage.German,
            theme = NaviampThemeSettings(NaviampThemeMode.System, NaviampForestTheme.id))
        val exported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
        val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(exported)),
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
        assertEquals(settings, applied.interfaceSettings)

        val invalid = exported.copy(preferences = exported.preferences.copy(
            interfaceSettings = settings.copy(theme = NaviampThemeSettings(NaviampThemeMode.Light, "missing"))))
        val normalized = applySettingsSyncDocument(invalid,
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository()).interfaceSettings
        assertEquals(NaviampThemeSettings(NaviampThemeMode.Light, NaviampBuiltinTheme.id), normalized.theme)
        assertEquals(InterfaceLanguage.German, normalized.language)
    }

    @Test fun portableMixerPreferencesSurviveImportReexportAndNormalization() {
        for (gapless in listOf(false, true)) {
            val requested = PlaybackSettings(gaplessEnabled = gapless, crossfadeDurationSeconds = 99,
                stereoDownmixEnabled = true, replayGainPreampDb = 999f)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = requested), 1L, "sender")
            val imported = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            val reexported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = imported.playbackSettings), 2L, "receiver")
            val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(reexported)).preferences.playback
            assertEquals(gapless, restored.gaplessEnabled)
            assertEquals(12, restored.crossfadeDurationSeconds)
            assertTrue(restored.stereoDownmixEnabled)
            assertEquals(MaxReplayGainPreampDb, restored.replayGainPreampDb)
        }
    }

    @Test fun importKeepsTheReceivingDevicesLocalPlaybackSettings() {
        val sender = PlaybackSettings(volumePercent = 20, debugLoggingEnabled = true, allowMobileDownloads = true,
            outputDevice = AudioOutputDevicePreference(AudioOutputDeviceMode.Pinned, "sender-device", "Sender speakers"), gaplessEnabled = false)
        val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = sender), 1L, "sender")
        val local = PlaybackSettings(volumePercent = 34, debugLoggingEnabled = true, allowMobileDownloads = true,
            outputDevice = AudioOutputDevicePreference(AudioOutputDeviceMode.Pinned, "local-device", "Local speakers"))
        val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository(), localPlayback = local).playbackSettings
        assertEquals(34, applied.volumePercent)
        assertEquals(local.outputDevice, applied.outputDevice)
        assertTrue(applied.debugLoggingEnabled)
        assertTrue(applied.allowMobileDownloads)
        assertFalse(applied.gaplessEnabled, "Portable playback preferences still come from the document")
        val older = applySettingsSyncDocument(SettingsSyncJson.decode("""{"preferences":{"playback":{"volumePercent":100}}}"""),
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository(), localPlayback = local).playbackSettings
        assertEquals(34, older.volumePercent, "Older exports never override this device's volume")
    }

    @Test fun artworkFillAndCornersAreIndependentPortablePreferences() {
        for (square in listOf(false, true)) for (fill in listOf(false, true)) {
            val settings = InterfaceSettings(squareAlbumArtwork = square,
                nowPlaying = NowPlayingDisplaySettings(fillArtworkArea = fill))
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            assertEquals(square, applied.interfaceSettings.squareAlbumArtwork)
            assertEquals(fill, applied.interfaceSettings.nowPlaying.fillArtworkArea)
        }
        assertFalse(SettingsSyncJson.decode("{}").preferences.interfaceSettings.nowPlaying.fillArtworkArea)
    }
    @Test fun squareAlbumArtworkAppliesThroughPortableExportImportAndDefaultsForOlderExports() {
        for (square in listOf(false, true)) {
            val settings = InterfaceSettings(squareAlbumArtwork = square)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            assertEquals(settings, applied.interfaceSettings)
        }
        assertFalse(SettingsSyncJson.decode("{}").preferences.interfaceSettings.squareAlbumArtwork)
    }
    @Test fun aurralConnectionIdentityAndTlsPreferenceRoundTripWithoutSecrets() {
        val source = savedSource().copy(providerId = app.naviamp.domain.provider.ProviderIdAurral,
            password = "account-secret", nativeToken = "session-secret",
            tlsSettings = app.naviamp.domain.source.ConnectionTlsSettings(insecureSkipTlsVerification = true))
        val encoded = SettingsSyncJson.encode(buildSettingsSyncDocument(
            SettingsSyncLocalSnapshot(serverProfiles = listOf(source)), 1L, "test"))
        assertFalse(encoded.contains("account-secret"))
        assertFalse(encoded.contains("session-secret"))
        val restored = SettingsSyncJson.decode(encoded).serverProfiles.single().toConnectionFormState()
        assertEquals(app.naviamp.domain.provider.ProviderIdAurral, restored.providerId)
        assertTrue(restored.skipTlsVerification)
        assertEquals(listOf("folder-one", "folder-two"), restored.selectedMusicFolderIds)
    }
    @Test fun independentArtistSectionLayoutsApplyThroughSettingsSync() {
        for (playlists in AlbumCollectionLayout.entries) for (appearances in AlbumCollectionLayout.entries) {
            val settings = InterfaceSettings(artistPlaylistLayout = playlists, artistAppearanceAlbumLayout = appearances)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            assertEquals(settings, applied.interfaceSettings)
        }
    }
    @Test fun artistPlaylistVisibilityIsAppliedFromPortableExportsAndOlderDefaults() {
        for (enabled in listOf(false, true)) {
            val exported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(
                interfaceSettings = InterfaceSettings(showArtistPlaylists = enabled)), 1L, "test")
            val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(exported)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            assertEquals(enabled, applied.interfaceSettings.showArtistPlaylists)
        }
        val older = applySettingsSyncDocument(SettingsSyncJson.decode("{}"),
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
        assertTrue(older.interfaceSettings.showArtistPlaylists)
    }

    @Test fun artistTopTracksApplyThroughExportImportAndSync() {
        for (visible in listOf(true, false)) for (limit in ArtistTopTracksLimits) {
            val settings = InterfaceSettings(showArtistTopTracks = visible, artistTopTracksLimit = limit)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val applied = applySettingsSyncDocument(SettingsSyncJson.decode(SettingsSyncJson.encode(document)),
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
            assertEquals(settings, applied.interfaceSettings)
        }
    }
    @Test fun explicitRadioMethodsAndAutoplaySurvivePortableExportImportAndSync() {
        for (method in app.naviamp.domain.radio.RadioSimilarityMethod.entries) {
            val settings = PlaybackSettings().withRadioSimilarityMethod(method)
                .copy(sonicAutoplayEnabled = true, radioMinimumTracks = 40, sonicAutoplayMinimumTracks = 70)
            val exported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = settings), 1L, "test")
            val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(exported))
            val applied = applySettingsSyncDocument(restored,
                RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository()).playbackSettings
            assertEquals(settings.sonicSimilarityEnabled, applied.sonicSimilarityEnabled)
            assertTrue(applied.sonicSimilarityPreferenceConfigured)
            assertTrue(applied.sonicAutoplayEnabled)
            assertEquals(40, applied.radioMinimumTracks)
            assertEquals(70, applied.sonicAutoplayMinimumTracks)
        }
        assertFalse(SettingsSyncJson.decode("{}").preferences.playback.effectiveSonicSimilarityEnabled())
        assertTrue(SettingsSyncJson.decode("""{"preferences":{"playback":{"sonicSimilarityEnabled":true}}}""")
            .preferences.playback.effectiveSonicSimilarityEnabled())
    }

    @Test
    fun independentRadioBatchSizesArePortableAndAppliedNormalized() {
        val exported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(
            playback = PlaybackSettings(radioMinimumTracks = 30, sonicAutoplayMinimumTracks = 80)), 1L, "test")
        val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(exported))
        val applied = applySettingsSyncDocument(restored,
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository())
        assertEquals(30, applied.playbackSettings.radioMinimumTracks)
        assertEquals(80, applied.playbackSettings.sonicAutoplayMinimumTracks)
        val raw = restored.copy(preferences = restored.preferences.copy(playback =
            PlaybackSettings(radioMinimumTracks = -1, sonicAutoplayMinimumTracks = 999)))
        val normalized = applySettingsSyncDocument(raw,
            RecordingProviderMediaSourceRepository(), RecordingRadioDjPresetRepository()).playbackSettings
        assertEquals(10, normalized.radioMinimumTracks)
        assertEquals(100, normalized.sonicAutoplayMinimumTracks)
    }

    @Test
    fun apiKeyProfileRoundTripsModeWithoutExportingTheKey() {
        val source = savedSource().copy(
            authenticationMode = app.naviamp.domain.source.SubsonicAuthApiKey,
            token = "nds_secret", password = null,
        )
        val encoded = SettingsSyncJson.encode(
            buildSettingsSyncDocument(SettingsSyncLocalSnapshot(serverProfiles = listOf(source)), 1L, "test"),
        )
        assertFalse(encoded.contains("nds_secret"))
        val restored = SettingsSyncJson.decode(encoded).serverProfiles.single()
        assertEquals(app.naviamp.domain.source.SubsonicAuthApiKey, restored.authenticationMode)
        assertEquals(app.naviamp.domain.source.SubsonicAuthApiKey, restored.toConnectionFormState().authenticationMode)
        assertEquals("", restored.toConnectionFormState().apiKey)
        assertEquals(app.naviamp.domain.source.SubsonicAuthToken,
            SettingsSyncJson.decode("""{"serverProfiles":[{"id":"old","displayName":"Old","username":"a","primaryUrl":"https://example.test"}]}""")
                .serverProfiles.single().authenticationMode)
    }

    @Test
    fun legacySyncedProfileWithoutProviderIdDefaultsToNavidrome() {
        val document = SettingsSyncJson.decode(
            """
            {
              "serverProfiles": [
                {
                  "id": "legacy",
                  "displayName": "Legacy server",
                  "username": "demo",
                  "primaryUrl": "https://legacy.example"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(ProviderIdNavidrome, document.serverProfiles.single().providerId)
        assertTrue(document.serverProfiles.single().selectedMusicFolderIds.isEmpty())
        assertEquals(
            ProviderIdNavidrome,
            document.serverProfiles.single().toConnectionFormState().providerId,
        )
    }

    @Test
    fun buildsPortableDocumentFromLocalSnapshotWithoutSecretsOrDeviceLocalPlaybackSettings() {
        val document = buildSettingsSyncDocument(
            snapshot = SettingsSyncLocalSnapshot(
                serverProfiles = listOf(savedSource()),
                playback = PlaybackSettings(
                    replayGainMode = ReplayGainMode.Track,
                    outputDevice = AudioOutputDevicePreference(
                        mode = AudioOutputDeviceMode.Pinned,
                        deviceId = "built-in-output",
                        deviceName = "Built-in Output",
                    ),
                    crossfadeDurationSeconds = 5,
                    volumePercent = 42,
                    debugLoggingEnabled = true,
                    allowMobileDownloads = true,
                ),
                visualizer = VisualizerSettings(selectedVisualizer = "AudioBars"),
            ),
            nowEpochMillis = 123L,
            deviceId = " desktop ",
        )

        val profile = document.serverProfiles.single()

        assertEquals(123L, document.updatedAtEpochMillis)
        assertEquals("desktop", document.lastWriterDeviceId)
        assertEquals("source_1", profile.id)
        assertEquals(ProviderIdNavidrome, profile.providerId)
        assertEquals("Goosepod", profile.displayName)
        assertEquals("ursasmar", profile.username)
        assertEquals("https://navidrome.lan", profile.primaryUrl)
        assertEquals("https://navidrome.tailnet", profile.secondaryUrls.single().url)
        assertEquals("Tailscale", profile.secondaryUrls.single().label)
        assertEquals(listOf("folder-one", "folder-two"), profile.selectedMusicFolderIds)
        val nonSecretHeader = profile.customHeaders.first { it.name == "X-Proxy-User" }
        assertEquals("ursasmar", nonSecretHeader.value)
        assertEquals(null, profile.customHeaders.first { it.name == "X-Secret" }.value)
        assertTrue(profile.tls.insecureSkipTlsVerification)
        assertEquals("/certs/navidrome.pem", profile.tls.customCertificatePath)
        assertEquals("/certs/client.p12", profile.tls.clientCertificateKeyStorePath)
        assertEquals(ReplayGainMode.Track, document.preferences.playback.replayGainMode)
        assertEquals(AudioOutputDevicePreference(), document.preferences.playback.outputDevice)
        assertEquals(5, document.preferences.playback.crossfadeDurationSeconds)
        assertEquals(100, document.preferences.playback.volumePercent)
        assertFalse(document.preferences.playback.debugLoggingEnabled)
        assertFalse(document.preferences.playback.allowMobileDownloads)
        assertEquals("AudioBars", document.preferences.visualizer.selectedVisualizer)
        assertFalse(SettingsSyncJson.encode(document).contains("native-token"))
        assertFalse(SettingsSyncJson.encode(document).contains("secret-password"))
        assertFalse(SettingsSyncJson.encode(document).contains("do-not-sync"))
    }

    @Test
    fun importedProfileBuildsConnectionFormThatStillRequiresLocalPassword() {
        val form = savedSource()
            .toSettingsSyncServerProfile()
            .toConnectionFormState(password = "new-password")

        assertEquals("Goosepod", form.displayName)
        assertEquals(ProviderIdNavidrome, form.providerId)
        assertEquals("https://navidrome.lan", form.serverUrl)
        assertEquals("ursasmar", form.username)
        assertEquals("new-password", form.password)
        assertTrue(form.skipTlsVerification)
        assertEquals("/certs/navidrome.pem", form.customCertificatePath)
        assertEquals("/certs/client.p12", form.clientCertificatePath)
        assertEquals("", form.clientCertificatePassword)
        assertEquals("https://navidrome.tailnet", form.secondaryUrls.single().url)
        assertEquals("X-Proxy-User", form.customHeaders.first().name)
        assertEquals(listOf("folder-one", "folder-two"), form.selectedMusicFolderIds)
    }

    @Test
    fun importedProfileCarriesItsProviderIdentityIntoTheConnectionForm() {
        val form = savedSource()
            .toSettingsSyncServerProfile()
            .copy(providerId = ProviderIdSubsonic)
            .toConnectionFormState()

        assertEquals(ProviderIdSubsonic, form.providerId)
    }

    @Test
    fun importsAllSyncedServerProfilesWithoutSecrets() {
        val repository = RecordingProviderMediaSourceRepository()

        val result = importSettingsSyncServerProfiles(
            serverProfiles = listOf(
                savedSource().toSettingsSyncServerProfile(),
                savedSource(
                    id = "source_2",
                    displayName = "Away Music",
                    baseUrl = "https://away.example",
                    username = "demo2",
                ).toSettingsSyncServerProfile(),
            ),
            repository = repository,
        )

        assertEquals(2, result.importedCount)
        assertEquals("Goosepod", result.firstConnectionForm?.displayName)
        assertEquals(
            listOf(
                "navidrome:https://navidrome.lan:ursasmar:folders=folder-one,folder-two",
                "navidrome:https://away.example:demo2:folders=folder-one,folder-two",
            ),
            repository.cacheNamespaces,
        )
        assertTrue(repository.connections.all { it.token.isEmpty() })
        assertTrue(repository.connections.all { it.salt.isEmpty() })
        assertTrue(repository.connections.all { it.nativeToken == null })
        assertTrue(repository.connections.all { it.selectedMusicFolderIds == listOf("folder-one", "folder-two") })
        assertTrue(repository.connections.all { it.tlsSettings.clientCertificateKeyStorePassword == null })
    }

    @Test
    fun appliesPortablePreferencesAndRepositoryMutationsAsOnePlan() {
        val mediaSources = RecordingProviderMediaSourceRepository()
        val radioDjs = RecordingRadioDjPresetRepository()
        val document = SettingsSyncDocument(
            serverProfiles = listOf(savedSource().toSettingsSyncServerProfile()),
            preferences = SettingsSyncPreferences(
                playback = PlaybackSettings(
                    crossfadeDurationSeconds = 7,
                    radioDjs = listOf(RadioDjPreset(id = "wide", name = "Wide")),
                ),
                visualizer = VisualizerSettings(selectedVisualizer = "AudioBars"),
            ),
        )

        val applied = applySettingsSyncDocument(
            document = document,
            mediaSourceRepository = mediaSources,
            radioDjPresetRepository = radioDjs,
        )

        assertEquals(7, applied.playbackSettings.crossfadeDurationSeconds)
        assertEquals("wide", applied.playbackSettings.radioDjs.single().id)
        assertEquals("AudioBars", applied.visualizer.selectedVisualizer)
        assertEquals(1, applied.importedServerProfiles.importedCount)
        assertEquals("wide", radioDjs.radioDjPresets().single().id)
        assertEquals(1, mediaSources.connections.size)
    }

    @Test
    fun importNeedsAPasswordOnlyWhenNoMatchingSavedConnectionHoldsCredentials() {
        val local = savedSource()
        val document = SettingsSyncDocument(serverProfiles = listOf(local.toSettingsSyncServerProfile()))
        val withoutCredentials = local.copy(token = "", salt = "", nativeToken = null, password = null)
        val otherAccount = savedSource(id = "source_2", displayName = "Other", baseUrl = "https://navidrome.lan/", username = "guest")

        // The device already holds this exact account: reconnect it, no password prompt.
        assertEquals(local, settingsSyncImportedConnection(document, listOf(otherAccount, local)))
        assertFalse(settingsSyncImportNeedsCredentials(document, listOf(otherAccount, local)))
        // A matching connection without credentials (a fresh device) still asks for the password.
        assertTrue(settingsSyncImportNeedsCredentials(document, listOf(withoutCredentials)))
        // Credentials for a different account on the same server are never reused.
        assertNull(settingsSyncImportedConnection(document, listOf(otherAccount)))
        assertTrue(settingsSyncImportNeedsCredentials(document, listOf(otherAccount)))
        // A document without server profiles never asks for a password.
        assertFalse(settingsSyncImportNeedsCredentials(SettingsSyncDocument(), listOf(local)))
    }

    private fun savedSource(): SavedMediaSource =
        savedSource(
            id = "source_1",
            displayName = "Goosepod",
            baseUrl = "https://navidrome.lan/",
            username = "ursasmar",
        )

    private fun savedSource(
        id: String,
        displayName: String,
        baseUrl: String,
        username: String,
    ): SavedMediaSource =
        SavedMediaSource(
            id = id,
            providerId = ProviderIdNavidrome,
            cacheNamespace = "navidrome:${baseUrl.trimEnd('/')}:$username",
            displayName = displayName,
            baseUrl = baseUrl,
            username = username,
            token = "token",
            salt = "salt",
            nativeToken = "native-token",
            secondaryUrls = listOf(
                ConnectionSecondaryUrl(
                    url = "https://navidrome.tailnet",
                    label = "Tailscale",
                ),
            ),
            customHeaders = listOf(
                ConnectionHeaderDefinition(
                    name = "X-Proxy-User",
                    value = "ursasmar",
                ),
                ConnectionHeaderDefinition(
                    name = "X-Secret",
                    value = "do-not-sync",
                    valueIsSecret = true,
                ),
            ),
            selectedMusicFolderIds = listOf(" folder-one ", "folder-two", "folder-one"),
            tlsSettings = ConnectionTlsSettings(
                insecureSkipTlsVerification = true,
                customCertificatePath = "/certs/navidrome.pem",
                clientCertificateKeyStorePath = "/certs/client.p12",
                clientCertificateKeyStorePassword = "secret-password",
            ),
            createdAtEpochMillis = 1L,
            lastConnectedAtEpochMillis = 2L,
            lastSyncStartedAtEpochMillis = 3L,
            lastSyncCompletedAtEpochMillis = 4L,
        )

    private class RecordingProviderMediaSourceRepository(
        initialSources: List<SavedMediaSource> = emptyList(),
    ) : ProviderMediaSourceRepository, MediaSourceRepository {
        val connections = mutableListOf<ProviderMediaSourceConnection>()
        val cacheNamespaces = mutableListOf<String>()
        val preferredSourceIds = mutableListOf<String?>()
        private val sources = initialSources.associateBy { it.id }.toMutableMap()

        override fun latestMediaSource(): SavedMediaSource? = sources.values.lastOrNull()
        override fun mediaSources(): List<SavedMediaSource> = sources.values.toList()
        override fun mediaSource(sourceId: String): SavedMediaSource? = sources[sourceId]
        override fun deleteMediaSource(sourceId: String) {
            sources.remove(sourceId)
        }

        override fun upsertProviderMediaSource(
            connection: ProviderMediaSourceConnection,
            cacheNamespace: String,
            providerId: String,
            preferredSourceId: String?,
        ): MediaSourceIdentity {
            connections += connection
            cacheNamespaces += cacheNamespace
            preferredSourceIds += preferredSourceId
            val id = preferredSourceId ?: sources.values.firstOrNull { it.cacheNamespace == cacheNamespace }?.id
                ?: stableMediaSourceId(cacheNamespace)
            sources[id] = SavedMediaSource(
                id = id,
                providerId = providerId,
                cacheNamespace = cacheNamespace,
                displayName = connection.displayName,
                baseUrl = connection.baseUrl,
                username = connection.username,
                token = connection.token,
                salt = connection.salt,
                nativeToken = connection.nativeToken,
                password = connection.password,
                authenticationMode = connection.authenticationMode,
                tlsSettings = connection.tlsSettings,
                secondaryUrls = connection.secondaryUrls,
                customHeaders = connection.customHeaders,
                selectedMusicFolderIds = connection.selectedMusicFolderIds,
                aurralCompanion = connection.aurralCompanion,
                createdAtEpochMillis = 1L,
                lastConnectedAtEpochMillis = null,
                lastSyncStartedAtEpochMillis = null,
                lastSyncCompletedAtEpochMillis = null,
            )
            return MediaSourceIdentity(
                id = id,
                cacheNamespace = cacheNamespace,
                displayName = connection.displayName,
            )
        }
    }

    private class RecordingRadioDjPresetRepository : RadioDjPresetRepository {
        private val presets = mutableListOf<RadioDjPreset>()

        override fun radioDjPresets(): List<RadioDjPreset> = presets.toList()

        override fun replaceRadioDjPresets(presets: List<RadioDjPreset>) {
            this.presets.clear()
            this.presets += presets
        }

        override fun upsertRadioDjPreset(preset: RadioDjPreset) {
            deleteRadioDjPreset(preset.id)
            presets += preset
        }

        override fun deleteRadioDjPreset(id: String) {
            presets.removeAll { it.id == id }
        }
    }

}
