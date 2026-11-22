package app.naviamp.domain.settings

import app.naviamp.domain.playback.ReplayGainMode
import app.naviamp.domain.radio.RadioDjPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class SettingsSyncDocumentTest {
    @Test
    fun compactLyricsFontSizesSurviveExportImportAndSyncDocumentRebuild() {
        for (size in listOf(14, 20, 24, 48, 64)) {
            val settings = PlaybackSettings(compactLyricsFontSizeSp = size, lrclibLyricsEnabled = true)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = settings), 1L, "source")
            val imported = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.playback
            assertEquals(size, imported.compactLyricsFontSizeSp)
            assertTrue(imported.lrclibLyricsEnabled)

            val synced = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(playback = imported), 2L, "destination")
            val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(synced)).preferences.playback
            assertEquals(size, restored.compactLyricsFontSizeSp)
            assertTrue(restored.lrclibLyricsEnabled)
        }
    }

    @Test
    fun compactLyricsFontSizeDefaultsForOlderExportsAndNormalizesMalformedValues() {
        val older = SettingsSyncJson.decode("""{"preferences":{"playback":{"volumePercent":37}}}""")
            .preferences.playback
        assertEquals(14, older.compactLyricsFontSizeSp)
        assertEquals(37, older.volumePercent)
        assertEquals(14, SettingsSyncJson.decode("""{}""").preferences.playback.compactLyricsFontSizeSp)

        for (size in listOf(Int.MIN_VALUE, -1, 0, 15, 16, 18, 25, Int.MAX_VALUE)) {
            val imported = SettingsSyncJson.decode(
                """{"preferences":{"playback":{"compactLyricsFontSizeSp":$size,"volumePercent":37}}}""",
            ).preferences.playback
            assertEquals(14, imported.compactLyricsFontSizeSp)
            assertEquals(37, imported.volumePercent)

            val document = buildSettingsSyncDocument(
                SettingsSyncLocalSnapshot(playback = PlaybackSettings(compactLyricsFontSizeSp = size)),
                1L,
                "test",
            )
            assertEquals(14, document.preferences.playback.compactLyricsFontSizeSp)
        }
    }

    @Test
    fun allThemeModesAndRegisteredSelectionsRoundTripWithoutChangingOtherPreferences() {
        for (pack in NaviampThemes.packs) for (mode in NaviampThemeMode.entries) {
            val settings = InterfaceSettings(language = InterfaceLanguage.German, keepScreenAwake = true,
                theme = NaviampThemeSettings(mode, pack.id))
            val exported = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(exported)).preferences.interfaceSettings
            assertEquals(settings, restored)
            assertEquals(pack, NaviampThemes.selectedPack(restored.theme.selectedPackId))
        }
        assertEquals(NaviampThemeSettings(), SettingsSyncJson.decode("{}").preferences.interfaceSettings.theme)
        assertEquals(NaviampThemeSettings(), SettingsSyncJson.decode(
            """{"preferences":{"interfaceSettings":{"language":"German"}}}""").preferences.interfaceSettings.theme)
    }

    @Test
    fun unknownThemeSelectionFallsBackWithoutDiscardingModeOrUnrelatedPreferences() {
        for (mode in NaviampThemeMode.entries) {
            val imported = SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{
                "language":"German","keepScreenAwake":true,
                "theme":{"mode":"${mode.name}","selectedPackId":"unavailable-theme"}
            }}}""").preferences.interfaceSettings
            assertEquals(InterfaceLanguage.German, imported.language)
            assertTrue(imported.keepScreenAwake)
            assertEquals(NaviampThemeSettings(mode, NaviampBuiltinTheme.id), imported.theme)
        }
    }

    @Test fun everyInterfaceLanguageSurvivesExportImportAndOlderDefaultsRemainCompatible() {
        for (language in InterfaceLanguage.entries) {
            val settings = InterfaceSettings(language = language)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.interfaceSettings.normalized()
            assertEquals(settings, restored)
        }
        for (name in listOf("English", "Spanish")) {
            val restored = SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"language":"$name"}}}""")
            assertEquals(name, restored.preferences.interfaceSettings.language.name)
        }
        assertEquals(InterfaceLanguage.System, SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{}}}""")
            .preferences.interfaceSettings.language)
        kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
            SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"language":"Unknown"}}}""")
        }
    }
    @Test
    fun artistPlaylistVisibilityRoundTripsThroughSyncAndDefaultsOnForOlderExports() {
        for (enabled in listOf(false, true)) {
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(
                interfaceSettings = InterfaceSettings(showArtistPlaylists = enabled)), 1L, "test")
            val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.interfaceSettings.normalized()
            assertEquals(enabled, restored.showArtistPlaylists)
        }
        assertTrue(SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{}}}""")
            .preferences.interfaceSettings.normalized().showArtistPlaylists)
        kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
            SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"showArtistPlaylists":"invalid"}}}""")
        }
    }
    @Test
    fun independentRadioBatchSizesRoundTripWithOlderDefaultsAndImportedNormalization() {
        val document = SettingsSyncDocument(preferences = SettingsSyncPreferences(
            playback = PlaybackSettings(radioMinimumTracks = 40, sonicAutoplayMinimumTracks = 90)))
        val restored = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.playback
        assertEquals(40, restored.radioMinimumTracks)
        assertEquals(90, restored.sonicAutoplayMinimumTracks)
        val older = SettingsSyncJson.decode("""{"preferences":{"playback":{"sonicAutoplayEnabled":true}}}""").preferences.playback
        assertEquals(10, older.radioMinimumTracks)
        assertEquals(10, older.sonicAutoplayMinimumTracks)
        for ((raw, expected) in listOf(Int.MIN_VALUE to 10, 14 to 10, 15 to 20, 95 to 100, Int.MAX_VALUE to 100)) {
            val imported = SettingsSyncJson.decode("""{"preferences":{"playback":{"radioMinimumTracks":$raw,"sonicAutoplayMinimumTracks":$raw}}}""").preferences.playback
            assertEquals(expected, imported.radioMinimumTracks)
            assertEquals(expected, imported.sonicAutoplayMinimumTracks)
        }
    }

    @Test
    fun cachedAudioUpgradeRoundTripsAndOlderExportsDefaultOff() {
        val document = buildSettingsSyncDocument(
            SettingsSyncLocalSnapshot(
                playback = PlaybackSettings(upgradeCachedAudioOnWifi = true),
            ),
            1L,
            "test",
        )

        val imported = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.playback
        assertTrue(imported.upgradeCachedAudioOnWifi)
        assertFalse(
            SettingsSyncJson.decode("""{"preferences":{"playback":{}}}""")
                .preferences.playback.upgradeCachedAudioOnWifi,
        )
    }

    @Test
    fun everyFontSizeCombinationSurvivesExportNormalizationAndIndependentReset() {
        for (general in InterfaceFontSize.entries) for (player in InterfaceFontSize.entries) {
            val settings = InterfaceSettings(generalFontSize = general, nowPlayingFontSize = player)
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(interfaceSettings = settings), 1L, "test")
            val imported = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.interfaceSettings.normalized()
            assertEquals(settings, imported)
            assertEquals(player, imported.copy(generalFontSize = InterfaceFontSize.Standard).normalized().nowPlayingFontSize)
            assertEquals(general, imported.copy(nowPlayingFontSize = InterfaceFontSize.Standard).normalized().generalFontSize)
        }
        for (field in listOf("generalFontSize", "nowPlayingFontSize")) {
            kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
                SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"$field":"Unknown"}}}""")
            }
        }
    }

    @Test
    fun independentFontSizesRoundTripAndOlderExportsUseStandard() {
        val settings = InterfaceSettings(
            generalFontSize = InterfaceFontSize.Large,
            nowPlayingFontSize = InterfaceFontSize.Small,
        )
        val decoded = SettingsSyncJson.decode(SettingsSyncJson.encode(
            SettingsSyncDocument(preferences = SettingsSyncPreferences(interfaceSettings = settings)),
        )).preferences.interfaceSettings

        assertEquals(InterfaceFontSize.Large, decoded.generalFontSize)
        assertEquals(InterfaceFontSize.Small, decoded.nowPlayingFontSize)

        val older = SettingsSyncJson.decode(
            """{"preferences":{"interfaceSettings":{}}}""",
        ).preferences.interfaceSettings
        assertEquals(InterfaceFontSize.Standard, older.generalFontSize)
        assertEquals(InterfaceFontSize.Standard, older.nowPlayingFontSize)
    }

    @Test fun keepTelevisionControlsVisibleRoundTripsAndOlderExportsDefaultOff() {
        for (enabled in listOf(false, true)) {
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(
                interfaceSettings = InterfaceSettings(
                    nowPlaying = NowPlayingDisplaySettings(keepTelevisionControlsVisible = enabled),
                )), 1L, "test")
            val imported = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.interfaceSettings.normalized()
            assertEquals(enabled, imported.nowPlaying.keepTelevisionControlsVisible)
        }
        assertFalse(SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"nowPlaying":{}}}}""")
            .preferences.interfaceSettings.nowPlaying.keepTelevisionControlsVisible)
    }

    @Test fun keepScreenAwakeRoundTripsAndOlderExportsDefaultOff() {
        for (enabled in listOf(false, true)) {
            val document = buildSettingsSyncDocument(SettingsSyncLocalSnapshot(
                interfaceSettings = InterfaceSettings(keepScreenAwake = enabled)), 1L, "test")
            val imported = SettingsSyncJson.decode(SettingsSyncJson.encode(document)).preferences.interfaceSettings.normalized()
            assertEquals(enabled, imported.keepScreenAwake)
        }
        assertFalse(SettingsSyncJson.decode("{}") .preferences.interfaceSettings.keepScreenAwake)
        assertFalse(SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{}}}""")
            .preferences.interfaceSettings.keepScreenAwake)
        kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
            SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"keepScreenAwake":"invalid"}}}""")
        }
    }

    @Test
    fun interfaceLanguageRoundTripsMissingValuesDefaultAndUnknownValuesAreRejected() {
        for (language in InterfaceLanguage.entries) {
            val document = SettingsSyncDocument(preferences = SettingsSyncPreferences(
                interfaceSettings = InterfaceSettings(language = language),
            ))
            assertEquals(language, SettingsSyncJson.decode(SettingsSyncJson.encode(document))
                .preferences.interfaceSettings.language)
        }
        assertEquals(InterfaceLanguage.System, SettingsSyncJson.decode("""{"preferences":{}}""")
            .preferences.interfaceSettings.language)
        kotlin.test.assertFailsWith<kotlinx.serialization.SerializationException> {
            SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"language":"FutureLanguage"}}}""")
        }
    }

    @Test
    fun legacyPlayerLayoutsAreIgnoredWithoutLosingOtherPreferences() {
        for (layout in listOf("Full", "Split")) {
            for (opacity in listOf(-1, 0, 42, 100, 999)) {
                val document = SettingsSyncJson.decode(
                    """{"preferences":{"interfaceSettings":{"language":"German","nowPlaying":{
                        "wideLayout":"$layout","splitPaneBackgroundOpacityPercent":$opacity,
                        "showPlaybackSource":true,"fillArtworkArea":true,"showVolumeBar":false
                    }}}}""",
                )
                val expected = InterfaceSettings(
                    language = InterfaceLanguage.German,
                    nowPlaying = NowPlayingDisplaySettings(
                        showPlaybackSource = true,
                        fillArtworkArea = true,
                        showVolumeBar = false,
                    ),
                )
                assertEquals(expected, document.preferences.interfaceSettings)
                val exported = SettingsSyncJson.encode(document)
                val player = SettingsSyncJson.format.parseToJsonElement(exported).jsonObject
                    .getValue("preferences").jsonObject.getValue("interfaceSettings").jsonObject
                    .getValue("nowPlaying").jsonObject
                assertFalse("wideLayout" in player)
                assertFalse("splitPaneBackgroundOpacityPercent" in player)
                assertEquals(expected, SettingsSyncJson.decode(exported).preferences.interfaceSettings)
            }
        }
        for (older in listOf("{}", """{"preferences":{}}""", """{"preferences":{"interfaceSettings":{}}}""")) {
            assertEquals(InterfaceSettings(), SettingsSyncJson.decode(older).preferences.interfaceSettings)
        }
    }

    @Test
    fun playbackSourcePreferenceRoundTripsAndOlderExportsDefaultOff() {
        for (enabled in listOf(false, true)) {
            val document = buildSettingsSyncDocument(
                SettingsSyncLocalSnapshot(
                    interfaceSettings = InterfaceSettings(
                        nowPlaying = NowPlayingDisplaySettings(showPlaybackSource = enabled),
                    ),
                ),
                1L,
                "test",
            )
            assertEquals(
                enabled,
                SettingsSyncJson.decode(SettingsSyncJson.encode(document))
                    .preferences.interfaceSettings.nowPlaying.showPlaybackSource,
            )
        }
        assertFalse(SettingsSyncJson.decode("""{"preferences":{"interfaceSettings":{"nowPlaying":{}}}}""")
            .preferences.interfaceSettings.nowPlaying.showPlaybackSource)
    }

    @Test
    fun sonicSimilarityChoiceRoundTripsAndOlderExportsUseStoredPreference() {
        val disabled = PlaybackSettings(
            sonicSimilarityEnabled = false,
            sonicSimilarityPreferenceConfigured = true,
        )
        val decoded = SettingsSyncJson.decode(
            SettingsSyncJson.encode(
                SettingsSyncDocument(preferences = SettingsSyncPreferences(playback = disabled)),
            ),
        ).preferences.playback

        assertFalse(decoded.effectiveSonicSimilarityEnabled())
        assertTrue(decoded.sonicSimilarityPreferenceConfigured)

        val older = SettingsSyncJson.decode(
            """{"preferences":{"playback":{"sonicSimilarityEnabled":false}}}""",
        ).preferences.playback
        assertFalse(older.effectiveSonicSimilarityEnabled())
        assertTrue(older.sonicSimilarityPreferenceConfigured)

        val legacyAutomatic = older.copy(sonicSimilarityEnabled = false, sonicSimilarityPreferenceConfigured = false)
        val explicitExport = SettingsSyncJson.decode(SettingsSyncJson.encode(
            SettingsSyncDocument(preferences = SettingsSyncPreferences(playback = legacyAutomatic))))
            .preferences.playback
        assertFalse(explicitExport.effectiveSonicSimilarityEnabled())
        assertTrue(explicitExport.sonicSimilarityPreferenceConfigured)
    }

    @Test
    fun legacyAuroraTonesAreIgnoredWithoutLosingBackgroundOrThemePreferences() {
        for (tone in listOf("Light", "Dark", "DeepDark")) {
            for (style in AppBackgroundStyle.entries) {
                for (pack in NaviampThemes.packs) for (mode in NaviampThemeMode.entries) {
                    val document = SettingsSyncJson.decode(
                        """{"preferences":{"interfaceSettings":{
                            "auroraTone":"$tone","appBackgroundStyle":"${style.name}",
                            "auroraColorSteps":4,"auroraAngleDegrees":135,
                            "albumBlurRadiusDp":40,"singleColorHex":"#123456",
                            "language":"German","keepScreenAwake":true,
                            "theme":{"mode":"${mode.name}","selectedPackId":"${pack.id}"}
                        }}}""",
                    )
                    val expected = InterfaceSettings(
                        appBackgroundStyle = style,
                        auroraColorSteps = 4,
                        auroraAngleDegrees = 135,
                        albumBlurRadiusDp = 40,
                        singleColorHex = "#123456",
                        language = InterfaceLanguage.German,
                        keepScreenAwake = true,
                        theme = NaviampThemeSettings(mode, pack.id),
                    )
                    assertEquals(expected, document.preferences.interfaceSettings)
                    val exported = SettingsSyncJson.encode(document)
                    val settings = SettingsSyncJson.format.parseToJsonElement(exported).jsonObject
                        .getValue("preferences").jsonObject.getValue("interfaceSettings").jsonObject
                    assertFalse("auroraTone" in settings)
                    assertEquals(expected, SettingsSyncJson.decode(exported).preferences.interfaceSettings)
                }
            }
        }
    }

    @Test
    fun olderExportsUseBackgroundDefaultsAndImportedValuesAreBounded() {
        for (older in listOf("{}", """{"preferences":{"interfaceSettings":{}}}""")) {
            val document = SettingsSyncJson.decode(older)
            assertEquals(InterfaceSettings(), document.preferences.interfaceSettings)
            assertEquals(InterfaceSettings(), SettingsSyncJson.decode(SettingsSyncJson.encode(document))
                .preferences.interfaceSettings)
        }
        for (tone in listOf("Light", "Dark", "DeepDark")) {
            val defaults = SettingsSyncJson.decode(
                """{"preferences":{"interfaceSettings":{"auroraTone":"$tone"}}}""",
            )
            assertEquals(InterfaceSettings(), defaults.preferences.interfaceSettings)
            val invalid = SettingsSyncJson.decode(
                """{"preferences":{"interfaceSettings":{
                    "auroraTone":"$tone","auroraColorSteps":20,"auroraAngleDegrees":-45,
                    "albumBlurRadiusDp":999,"singleColorHex":" a1b2c3 "
                }}}""",
            )
            val expected = InterfaceSettings(
                auroraColorSteps = 5,
                auroraAngleDegrees = 0,
                albumBlurRadiusDp = MaxAlbumBlurRadiusDp,
                singleColorHex = "#A1B2C3",
            )
            assertEquals(expected, invalid.preferences.interfaceSettings)
            assertEquals(expected, SettingsSyncJson.decode(SettingsSyncJson.encode(invalid))
                .preferences.interfaceSettings)
        }
    }

    @Test
    fun artistReleasePreferencesRoundTripAndOlderExportsUseSharedDefaults() {
        val settings = InterfaceSettings(
            albumSortOrder = AlbumSortOrder.ReleaseYearDescending,
            libraryAlbumSortOrder = LibraryAlbumSortOrder.RecentlyAdded,
            groupAlbumsByReleaseType = false,
        )
        val decoded = SettingsSyncJson.decode(SettingsSyncJson.encode(
            SettingsSyncDocument(preferences = SettingsSyncPreferences(interfaceSettings = settings)),
        )).preferences.interfaceSettings

        assertEquals(AlbumSortOrder.ReleaseYearDescending, decoded.albumSortOrder)
        assertEquals(LibraryAlbumSortOrder.RecentlyAdded, decoded.libraryAlbumSortOrder)
        assertFalse(decoded.groupAlbumsByReleaseType)

        val older = SettingsSyncJson.decode("{}").preferences.interfaceSettings
        assertEquals(AlbumSortOrder.ReleaseYearAscending, older.albumSortOrder)
        assertEquals(LibraryAlbumSortOrder.Title, older.libraryAlbumSortOrder)
        assertTrue(older.groupAlbumsByReleaseType)
    }

    @Test
    fun roundTripsPortableSettingsSyncDocument() {
        val document = SettingsSyncDocument(
            updatedAtEpochMillis = 123L,
            lastWriterDeviceId = " desktop ",
            serverProfiles = listOf(
                SettingsSyncServerProfile(
                    id = " goosepod ",
                    displayName = " Goosepod Navidrome ",
                    username = " ursasmar ",
                    primaryUrl = " https://navidrome.lan/ ",
                    secondaryUrls = listOf(
                        SettingsSyncServerEndpoint(
                            url = " https://navidrome.tailnet.example/ ",
                            label = " Tailscale ",
                            priority = 2,
                        ),
                    ),
                    tls = SettingsSyncTlsSettings(
                        insecureSkipTlsVerification = true,
                        customCertificatePath = " /certs/navidrome.pem ",
                    ),
                    customHeaders = listOf(
                        SettingsSyncHeaderDefinition(
                            name = " X-Proxy-User ",
                            value = " ursasmar ",
                        ),
                    ),
                    selectedMusicFolderIds = listOf(" collection ", "music", "collection"),
                ),
            ),
            preferences = SettingsSyncPreferences(
                interfaceSettings = InterfaceSettings(
                    checkForUpdates = true,
                    applicationUpdateChannel = ApplicationUpdateChannel.Beta,
                    startPlayingOnLaunch = true,
                    showArtistInformation = false,
                    showAlbumInformation = false,
                    appBackgroundStyle = AppBackgroundStyle.AlbumBlur,
                    auroraColorSteps = 5,
                    auroraAngleDegrees = 135,
                    albumBlurRadiusDp = 40,
                    singleColorHex = "#123456",
                    homeSectionPresentations = mapOf(
                        "navibeat-mixes" to HomeSectionPresentationSettings(
                            homeLayout = HomeSectionLayout.Grid,
                            pageLayout = HomeSectionPageLayout.List,
                            visible = false,
                        ),
                    ),
                    homeSectionOrder = listOf("navibeat-mixes", "mixes-for-you", "future-section"),
                    globalKeyboardShortcuts = GlobalKeyboardShortcutSettings(
                        enabled = true,
                        bindingsByPlatform = mapOf(
                            DesktopShortcutPlatform.Windows to mapOf(
                                GlobalShortcutAction.PlayPause to KeyboardShortcutBinding(
                                    key = KeyboardShortcutKey.F8,
                                    control = true,
                                    alt = true,
                                ),
                            ),
                        ),
                    ),
                    nowPlaying = NowPlayingDisplaySettings(
                        showAlbumYear = false,
                        albumYearPreference = NowPlayingAlbumYearPreference.Release,
                        showTrackCover = true,
                        showAudioInfo = false,
                        showPlaybackSource = true,
                        showVolumeBar = false,
                        scrollTrackTitle = false,
                        scrollArtistName = true,
                        scrollAlbumName = true,
                    ),
                    trackSwipes = TrackSwipeSettings(
                        libraryRight = TrackSwipeAction.AddToPlaylist,
                        libraryLeft = TrackSwipeAction.ToggleFavorite,
                        queueRight = TrackSwipeAction.GoToAlbum,
                        queueLeft = TrackSwipeAction.Remove,
                        relatedLeft = TrackSwipeAction.GoToArtist,
                        playlistEditRight = TrackSwipeAction.MoveDown,
                        playlistEditLeft = TrackSwipeAction.MoveToBottom,
                    ),
                ),
                playback = PlaybackSettings(
                    replayGainMode = ReplayGainMode.Album,
                    sampleRateConverter = SampleRateConverter.Sinc32,
                    sampleRateMatching = SampleRateMatching.Strict,
                    crossfadeDurationSeconds = 6,
                    lyricsTimingPreference = LyricsTimingPreference.WordSynced,
                    lyricsDisplayPreference = LyricsDisplayPreference.LineSynced,
                    sonicSimilarityEnabled = false,
                    sonicSimilarityPreferenceConfigured = true,
                    radioDjs = listOf(RadioDjPreset(id = "dj", name = " Road DJ ")),
                ),
                visualizer = VisualizerSettings(selectedVisualizer = "Waveform"),
                recentRadioStreams = listOf(
                    RecentRadioStream(
                        id = "library:session:123",
                        label = "Library radio",
                        kind = RecentRadioKind.Library,
                        sourceId = "goosepod",
                        startedAtEpochMillis = 123L,
                        stationSeedIds = listOf("seed-a", "seed-b"),
                        generationOptions = app.naviamp.domain.radio.RecentRadioGenerationOptions(minimumTracks = 30),
                        sessionTracks = listOf(
                            SavedTrack("track-1", "Track", artistName = "Artist"),
                        ),
                    ),
                ),
                recentInternetRadioStations = listOf(
                    SavedInternetRadioStation(
                        id = "radio-1",
                        name = "Radio",
                        streamUrl = "https://radio.example/stream",
                        sourceId = "goosepod",
                    ),
                ),
            ),
        )

        val decoded = SettingsSyncJson.decode(SettingsSyncJson.encode(document))
        val expected = document.normalized()
        val profile = decoded.serverProfiles.single()

        assertEquals(CurrentSettingsSyncSchemaVersion, decoded.schemaVersion)
        assertEquals("desktop", decoded.lastWriterDeviceId)
        assertEquals("goosepod", profile.id)
        assertEquals("Goosepod Navidrome", profile.displayName)
        assertEquals("ursasmar", profile.username)
        assertEquals("https://navidrome.lan", profile.primaryUrl)
        assertEquals("https://navidrome.tailnet.example", profile.secondaryUrls.single().url)
        assertEquals("Tailscale", profile.secondaryUrls.single().label)
        assertTrue(profile.tls.insecureSkipTlsVerification)
        assertEquals("/certs/navidrome.pem", profile.tls.customCertificatePath)
        assertEquals("X-Proxy-User", profile.customHeaders.single().name)
        assertEquals("ursasmar", profile.customHeaders.single().value)
        assertEquals(listOf("collection", "music"), profile.selectedMusicFolderIds)
        assertEquals(ReplayGainMode.Album, decoded.preferences.playback.replayGainMode)
        assertEquals(SampleRateConverter.Sinc32, decoded.preferences.playback.sampleRateConverter)
        assertEquals(SampleRateMatching.Strict, decoded.preferences.playback.sampleRateMatching)
        assertFalse(decoded.preferences.playback.effectiveSonicSimilarityEnabled())
        assertTrue(decoded.preferences.playback.sonicSimilarityPreferenceConfigured)
        assertEquals("Road DJ", decoded.preferences.playback.radioDjs.single().name)
        assertEquals("Waveform", decoded.preferences.visualizer.selectedVisualizer)
        assertEquals("goosepod", decoded.preferences.recentRadioStreams.single().sourceId)
        assertEquals(listOf("seed-a", "seed-b"), decoded.preferences.recentRadioStreams.single().stationSeedIds)
        assertEquals(30, decoded.preferences.recentRadioStreams.single().generationOptions?.minimumTracks)
        assertEquals("track-1", decoded.preferences.recentRadioStreams.single().sessionTracks.single().id)
        assertEquals("goosepod", decoded.preferences.recentInternetRadioStations.single().sourceId)
        assertTrue(decoded.preferences.interfaceSettings.checkForUpdates)
        assertEquals(ApplicationUpdateChannel.Beta, decoded.preferences.interfaceSettings.applicationUpdateChannel)
        assertTrue(decoded.preferences.interfaceSettings.startPlayingOnLaunch)
        assertEquals(
            HomeSectionPresentationSettings(
                HomeSectionLayout.Grid,
                HomeSectionPageLayout.List,
                visible = false,
            ),
            decoded.preferences.interfaceSettings.homeSectionPresentation("navibeat-mixes"),
        )
        assertEquals(
            listOf("navibeat-mixes", "mixes-for-you", "future-section"),
            decoded.preferences.interfaceSettings.homeSectionOrder,
        )
        assertTrue(decoded.preferences.interfaceSettings.globalKeyboardShortcuts.enabled)
        assertEquals(
            KeyboardShortcutBinding(KeyboardShortcutKey.F8, control = true, alt = true),
            decoded.preferences.interfaceSettings.globalKeyboardShortcuts
                .resolvedBindings(DesktopShortcutPlatform.Windows)[GlobalShortcutAction.PlayPause],
        )
        assertFalse(decoded.preferences.interfaceSettings.showArtistInformation)
        assertFalse(decoded.preferences.interfaceSettings.showAlbumInformation)
        assertEquals(AppBackgroundStyle.AlbumBlur, decoded.preferences.interfaceSettings.appBackgroundStyle)
        assertEquals(5, decoded.preferences.interfaceSettings.auroraColorSteps)
        assertEquals(135, decoded.preferences.interfaceSettings.auroraAngleDegrees)
        assertEquals(40, decoded.preferences.interfaceSettings.albumBlurRadiusDp)
        assertEquals("#123456", decoded.preferences.interfaceSettings.singleColorHex)
        assertFalse(decoded.preferences.interfaceSettings.nowPlaying.showAlbumYear)
        assertFalse(decoded.preferences.interfaceSettings.nowPlaying.showAudioInfo)
        assertTrue(decoded.preferences.interfaceSettings.nowPlaying.showPlaybackSource)
        assertFalse(decoded.preferences.interfaceSettings.nowPlaying.showVolumeBar)
        assertFalse(decoded.preferences.interfaceSettings.nowPlaying.scrollTrackTitle)
        assertTrue(decoded.preferences.interfaceSettings.nowPlaying.scrollArtistName)
        assertTrue(decoded.preferences.interfaceSettings.nowPlaying.scrollAlbumName)
        assertEquals(TrackSwipeAction.AddToPlaylist, decoded.preferences.interfaceSettings.trackSwipes.libraryRight)
        assertEquals(TrackSwipeAction.ToggleFavorite, decoded.preferences.interfaceSettings.trackSwipes.libraryLeft)
        assertEquals(TrackSwipeAction.GoToAlbum, decoded.preferences.interfaceSettings.trackSwipes.queueRight)
        assertEquals(TrackSwipeAction.Remove, decoded.preferences.interfaceSettings.trackSwipes.queueLeft)
        assertEquals(TrackSwipeAction.GoToArtist, decoded.preferences.interfaceSettings.trackSwipes.relatedLeft)
        assertEquals(TrackSwipeAction.MoveDown, decoded.preferences.interfaceSettings.trackSwipes.playlistEditRight)
        assertEquals(TrackSwipeAction.MoveToBottom, decoded.preferences.interfaceSettings.trackSwipes.playlistEditLeft)
        assertEquals(LyricsTimingPreference.WordSynced, decoded.preferences.playback.lyricsTimingPreference)
        assertEquals(LyricsDisplayPreference.LineSynced, decoded.preferences.playback.lyricsDisplayPreference)
        assertEquals(expected.preferences.interfaceSettings, decoded.preferences.interfaceSettings)
        assertEquals(expected.preferences.playback, decoded.preferences.playback)
    }

    @Test
    fun interfaceBackgroundDefaultsAndNormalizesHexColor() {
        assertEquals(AppBackgroundStyle.Aurora, InterfaceSettings().appBackgroundStyle)
        assertEquals(3, InterfaceSettings().auroraColorSteps)
        assertEquals(45, InterfaceSettings().auroraAngleDegrees)
        assertEquals(2, InterfaceSettings(auroraColorSteps = 1).normalized().auroraColorSteps)
        assertEquals(5, InterfaceSettings(auroraColorSteps = 9).normalized().auroraColorSteps)
        assertEquals(0, InterfaceSettings(auroraAngleDegrees = -1).normalized().auroraAngleDegrees)
        assertEquals(180, InterfaceSettings(auroraAngleDegrees = 999).normalized().auroraAngleDegrees)
        assertEquals(DefaultAlbumBlurRadiusDp, InterfaceSettings().albumBlurRadiusDp)
        assertEquals(MaxAlbumBlurRadiusDp, InterfaceSettings(albumBlurRadiusDp = 999).normalized().albumBlurRadiusDp)
        assertEquals(MinAlbumBlurRadiusDp, InterfaceSettings(albumBlurRadiusDp = -1).normalized().albumBlurRadiusDp)
        assertEquals("#A1B2C3", InterfaceSettings(singleColorHex = "a1b2c3").normalized().singleColorHex)
        assertEquals(DefaultSingleColorHex, InterfaceSettings(singleColorHex = "not-a-color").normalized().singleColorHex)
        assertEquals(NowPlayingAlbumYearPreference.Original, NowPlayingDisplaySettings().albumYearPreference)
        assertEquals(false, NowPlayingDisplaySettings().showTrackCover)
    }

    @Test
    fun normalizationDropsBlankProfilesAndDoesNotPersistSecretHeaderValues() {
        val document = SettingsSyncDocument(
            lastWriterDeviceId = " ",
            serverProfiles = listOf(
                SettingsSyncServerProfile(
                    id = "blank",
                    displayName = "Blank",
                    username = "",
                    primaryUrl = " ",
                ),
                SettingsSyncServerProfile(
                    id = "source",
                    displayName = "",
                    username = "user",
                    primaryUrl = "https://server.example/",
                    secondaryUrls = listOf(
                        SettingsSyncServerEndpoint("https://server.example"),
                        SettingsSyncServerEndpoint(" "),
                    ),
                    customHeaders = listOf(
                        SettingsSyncHeaderDefinition(name = "Authorization", value = "Bearer secret", valueIsSecret = true),
                        SettingsSyncHeaderDefinition(name = " "),
                    ),
                ),
            ),
        ).normalized()

        val profile = document.serverProfiles.single()
        val secretHeader = profile.customHeaders.single()

        assertNull(document.lastWriterDeviceId)
        assertEquals("https://server.example", profile.primaryUrl)
        assertEquals("https://server.example", profile.displayName)
        assertTrue(profile.secondaryUrls.isEmpty())
        assertEquals("Authorization", secretHeader.name)
        assertNull(secretHeader.value)
        assertTrue(secretHeader.valueIsSecret)
        assertFalse(SettingsSyncJson.encode(document).contains("Bearer secret"))
    }
}
