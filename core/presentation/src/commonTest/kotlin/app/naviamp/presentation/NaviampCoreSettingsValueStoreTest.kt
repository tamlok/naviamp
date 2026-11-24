package app.naviamp.presentation

import app.naviamp.domain.settings.NaviampBuiltinTheme
import app.naviamp.domain.settings.NaviampThemeMode
import app.naviamp.domain.settings.NaviampThemes
import app.naviamp.domain.settings.NaviampThemeSettings

import app.naviamp.domain.Genre
import app.naviamp.domain.radio.MaxRecentRadioStreams
import app.naviamp.domain.radio.genreRecentRadioStream
import app.naviamp.domain.settings.InterfaceLanguage
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.NowPlayingDisplaySettings
import app.naviamp.domain.settings.PlaybackSettings
import app.naviamp.domain.settings.QuickAccessKind
import app.naviamp.domain.settings.QuickAccessPin
import app.naviamp.domain.settings.withQuickAccessPin
import app.naviamp.domain.settings.withoutQuickAccessPin
import app.naviamp.domain.settings.effectiveSonicSimilarityEnabled
import app.naviamp.domain.settings.RecentRadioKind
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.settings.SavedAlbum
import app.naviamp.domain.settings.SavedInternetRadioStation
import app.naviamp.domain.settings.SavedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

class NaviampCoreSettingsValueStoreTest {
    @Test fun generatedStationRecipeAndBatchSurviveReopeningWithoutChangingSnapshotOrder() {
        val values = MemorySettingsValues()
        val station = app.naviamp.domain.radio.GeneratedRadioStation("station", "My station", app.naviamp.domain.radio.GeneratedRadioKind.Track,
            sourceId = "source", seedTracks = listOf(SavedTrack("seed", "Seed", artistName = "Artist")), saved = true)
        val batch = app.naviamp.domain.radio.GeneratedRadioBatch("batch", station.id, 1, 42,
            listOf(SavedTrack("b", "B", artistName = "Artist"), SavedTrack("a", "A", artistName = "Artist")), resolvedRecipe = station)
        val library = app.naviamp.domain.radio.GeneratedRadioLibrary(stations = listOf(station), batches = listOf(batch))
        naviampCoreSettingsValueCatalog(values).storedSettings.generatedRadio.save(library)
        val legacyLibrary = Json.parseToJsonElement(values.entries.getValue(KeyGeneratedRadio)).jsonObject
        val legacyStation = JsonObject(legacyLibrary.getValue("stations").jsonArray.single().jsonObject + ("pinned" to JsonPrimitive(true)))
        val legacyBatch = JsonObject(legacyLibrary.getValue("batches").jsonArray.single().jsonObject + ("resolvedRecipe" to legacyStation))
        values.entries[KeyGeneratedRadio] = JsonObject(legacyLibrary + mapOf("stations" to JsonArray(listOf(legacyStation)),
            "batches" to JsonArray(listOf(legacyBatch)))).toString()
        assertEquals(library, naviampCoreSettingsValueCatalog(values).storedSettings.generatedRadio.load())
        val migration = naviampCoreProviderIdentitySettingsMigrationRepository(values)
        migration.migrateProviderIdentitySettings("source", 1) { "new:$it" }
        val migrated = naviampCoreSettingsValueCatalog(values).storedSettings.generatedRadio.load()
        assertEquals("station", migrated.stations.single().id)
        assertEquals(listOf("new:b", "new:a"), migrated.batches.single().tracks.map { it.id })
        assertEquals("new:seed", migrated.stations.single().seedTracks.single().id)
        assertEquals("new:seed", migrated.batches.single().resolvedRecipe?.seedTracks?.single()?.id)
        assertEquals("station", migrated.batches.single().resolvedRecipe?.id)
    }

    @Test
    fun quickAccessPersistsNormalizedAcrossReopeningRenamingAndRemoval() {
        val values = MemorySettingsValues()
        val pins = QuickAccessKind.entries.map { kind -> QuickAccessPin("source-a", kind, "item", kind.name) } +
            QuickAccessPin("source-b", QuickAccessKind.Playlist, "item", "Other account")
        val expected = InterfaceSettings(language = InterfaceLanguage.German, quickAccess = pins)
        naviampCoreSettingsValueCatalog(values).storedSettings.saveInterface(expected.copy(quickAccess =
            listOf(pins.first().copy(sourceId = " source-a ", title = " Playlist ")) + pins +
                pins.first().copy(itemId = " ")))
        val reopened = naviampCoreSettingsValueCatalog(values).storedSettings
        assertEquals(expected, reopened.loadInterface())
        assertEquals(expected, Json.decodeFromString<InterfaceSettings>(values.entries.getValue(KeyInterface)))

        val renamed = pins.first().copy(title = "Renamed")
        reopened.saveInterface(reopened.loadInterface().withQuickAccessPin(renamed))
        val afterRename = naviampCoreSettingsValueCatalog(values).storedSettings
        assertEquals(listOf(renamed) + pins.drop(1), afterRename.loadInterface().quickAccess)
        afterRename.saveInterface(afterRename.loadInterface().withoutQuickAccessPin(pins.first()))
        assertEquals(pins.drop(1), naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().quickAccess)

        values.entries[KeyInterface] = """{"language":"German"}"""
        assertEquals(InterfaceSettings(language = InterfaceLanguage.German),
            naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
        values.entries[KeyInterface] = """{"language":"German","quickAccess":[
            {"sourceId":" source-a ","kind":"Playlist","itemId":" item ","title":" Playlist "},
            {"sourceId":"source-a","kind":"Playlist","itemId":"item","title":"Duplicate"},
            {"sourceId":"source-a","kind":"Artist","itemId":" ","title":"Invalid"}
        ]}"""
        val normalized = naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface()
        assertEquals(InterfaceSettings(language = InterfaceLanguage.German, quickAccess = listOf(pins.first())), normalized)
        naviampCoreSettingsValueCatalog(values).storedSettings.saveInterface(normalized)
        assertEquals(normalized, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
    }

    @Test
    fun registeredThemeSelectionsAndEveryModePersistAcrossReopeningTheLocalCatalog() {
        val values = MemorySettingsValues()
        for (pack in NaviampThemes.packs) for (mode in NaviampThemeMode.entries) {
            val expected = InterfaceSettings(language = InterfaceLanguage.German, keepScreenAwake = true,
                theme = NaviampThemeSettings(mode, pack.id))
            naviampCoreSettingsValueCatalog(values).storedSettings.saveInterface(expected)
            assertEquals(expected, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
        }
        values.entries[KeyInterface] = """{"language":"German"}"""
        val older = naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface()
        assertEquals(InterfaceLanguage.German, older.language)
        assertEquals(NaviampThemeSettings(), older.theme)
    }

    @Test
    fun unavailableThemeFallsBackWithoutResettingModeOrUnrelatedLocalPreferences() {
        for (mode in NaviampThemeMode.entries) {
            val values = MemorySettingsValues().apply {
                entries[KeyInterface] = """{"language":"German","keepScreenAwake":true,"theme":{
                    "mode":"${mode.name}","selectedPackId":"unavailable-theme"
                }}"""
            }
            val expected = InterfaceSettings(language = InterfaceLanguage.German, keepScreenAwake = true,
                theme = NaviampThemeSettings(mode, NaviampBuiltinTheme.id))
            val settings = naviampCoreSettingsValueCatalog(values).storedSettings
            assertEquals(expected, settings.loadInterface())
            settings.saveInterface(settings.loadInterface())
            assertEquals(expected, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
            settings.saveInterface(expected.copy(theme = NaviampThemeSettings(mode, "another-missing-theme")))
            assertEquals(expected, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
        }
    }

    @Test fun oldDuplicateStationsAreReconciledDurablyAndOptionsSurviveReopening() {
        val values = MemorySettingsValues()
        val station = genreRecentRadioStream(Genre("Jazz")).copy(
            sourceId = "account-a", id = "genre:Jazz:session:2", startedAtEpochMillis = 2,
            sessionTracks = listOf(SavedTrack("one", "One", artistName = "Artist")),
        )
        // Store the old JSON directly so the save path cannot pre-reconcile it.
        val catalog = naviampCoreSettingsValueCatalog(values).storedSettings
        catalog.saveRecentRadioStreams(listOf(station))
        val key = KeyRecentRadio
        values.entries[key] = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(RecentRadioStream.serializer()),
            listOf(station, station.copy(id = "genre:Jazz:session:1", startedAtEpochMillis = 1)),
        )
        assertEquals(listOf(station), catalog.loadRecentRadioStreams())
        val persisted = values.entries[key]
        assertEquals(listOf(station), naviampCoreSettingsValueCatalog(values).storedSettings.loadRecentRadioStreams())
        assertEquals(persisted, values.entries[key])
        val configured = station.copy(stationSeedIds = listOf("Jazz", "Rock"),
            generationOptions = app.naviamp.domain.radio.RecentRadioGenerationOptions(minimumTracks = 999))
        catalog.saveRecentRadioStreams(listOf(configured))
        val reopened = naviampCoreSettingsValueCatalog(values).storedSettings.loadRecentRadioStreams().single()
        assertEquals(configured.copy(generationOptions = configured.generationOptions?.normalized()), reopened)
    }

    @Test fun artistSectionLayoutsPersistIndependentlyAndOlderSettingsUseLists() {
        val values = MemorySettingsValues()
        val catalog = naviampCoreSettingsValueCatalog(values)
        for (playlists in app.naviamp.domain.settings.AlbumCollectionLayout.entries) {
            for (appearances in app.naviamp.domain.settings.AlbumCollectionLayout.entries) {
                val settings = InterfaceSettings(artistPlaylistLayout = playlists, artistAppearanceAlbumLayout = appearances)
                catalog.storedSettings.saveInterface(settings)
                assertEquals(settings, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
            }
        }
        values.entries[KeyInterface] = "{}"
        val older = catalog.storedSettings.loadInterface()
        assertEquals(app.naviamp.domain.settings.AlbumCollectionLayout.List, older.artistPlaylistLayout)
        assertEquals(app.naviamp.domain.settings.AlbumCollectionLayout.List, older.artistAppearanceAlbumLayout)
    }
    @Test fun artistPlaylistVisibilityPersistsAndOlderSettingsDefaultOn() {
        val values = MemorySettingsValues()
        val catalog = naviampCoreSettingsValueCatalog(values)
        assertEquals(true, catalog.storedSettings.loadInterface().showArtistPlaylists)
        catalog.storedSettings.saveInterface(InterfaceSettings(showArtistPlaylists = false))
        assertEquals(false, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().showArtistPlaylists)
    }

    @Test fun artistTopTracksPersistAndOlderValuesUseDefaults() {
        val values = MemorySettingsValues()
        val catalog = naviampCoreSettingsValueCatalog(values)
        assertEquals(10, catalog.storedSettings.loadInterface().artistTopTracksLimit)
        catalog.storedSettings.saveInterface(InterfaceSettings(showArtistTopTracks = false, artistTopTracksLimit = 50))
        val restored = naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface()
        assertFalse(restored.showArtistTopTracks)
        assertEquals(50, restored.artistTopTracksLimit)
        values.entries[KeyInterface] = """{"artistTopTracksLimit":18}"""
        assertEquals(20, catalog.storedSettings.loadInterface().artistTopTracksLimit)
        values.entries[KeyInterface] = "{}"
        assertEquals(true, catalog.storedSettings.loadInterface().showArtistTopTracks)
        assertEquals(10, catalog.storedSettings.loadInterface().artistTopTracksLimit)
    }
    @Test
    fun independentRadioAndAutoplaySizesPersistWithDefaultsAndNormalization() {
        val values = MemorySettingsValues()
        val catalog = naviampCoreSettingsValueCatalog(values)
        assertEquals(10, catalog.storedSettings.loadPlayback().radioMinimumTracks)
        assertEquals(10, catalog.storedSettings.loadPlayback().sonicAutoplayMinimumTracks)
        catalog.savePlayback(PlaybackSettings(radioMinimumTracks = 20, sonicAutoplayMinimumTracks = 70))
        val restored = naviampCoreSettingsValueCatalog(values).storedSettings.loadPlayback()
        assertEquals(20, restored.radioMinimumTracks)
        assertEquals(70, restored.sonicAutoplayMinimumTracks)
        values.entries[KeyPlayback] = """{"radioMinimumTracks":-1,"sonicAutoplayMinimumTracks":999}"""
        val normalized = naviampCoreSettingsValueCatalog(values).storedSettings.loadPlayback()
        assertEquals(10, normalized.radioMinimumTracks)
        assertEquals(100, normalized.sonicAutoplayMinimumTracks)
    }

    @Test
    fun olderSonicPreferenceDefaultsToInstantMixAndExplicitChoiceRoundTrips() {
        val values = MemorySettingsValues().apply {
            entries[KeyPlayback] = """{"sonicSimilarityEnabled":false}"""
        }
        val catalog = naviampCoreSettingsValueCatalog(values)

        assertEquals(false, catalog.storedSettings.loadPlayback().effectiveSonicSimilarityEnabled())

        catalog.savePlayback(
            PlaybackSettings(
                sonicSimilarityEnabled = false,
                sonicSimilarityPreferenceConfigured = true,
            ),
        )
        val reloaded = naviampCoreSettingsValueCatalog(values).storedSettings.loadPlayback()
        assertFalse(reloaded.effectiveSonicSimilarityEnabled())
        assertEquals(true, reloaded.sonicSimilarityPreferenceConfigured)
    }

    @Test
    fun languageSurvivesRecreatingTheSettingsCatalog() {
        val values = MemorySettingsValues()
        for (language in app.naviamp.domain.settings.InterfaceLanguage.entries) {
            naviampCoreSettingsValueCatalog(values).storedSettings.saveInterface(
                InterfaceSettings(language = language),
            )
            assertEquals(language, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().language)
        }
    }

    @Test
    fun legacyPlayerLayoutsLoadAndResaveWithoutLosingOtherPreferences() {
        for (layout in listOf("Full", "Split")) {
            for (opacity in listOf(-1, 0, 42, 100, 999)) {
                val values = MemorySettingsValues().apply {
                    entries[KeyInterface] = """{"language":"German","nowPlaying":{
                        "wideLayout":"$layout","splitPaneBackgroundOpacityPercent":$opacity,
                        "showPlaybackSource":true,"fillArtworkArea":true,"showVolumeBar":false
                    }}"""
                }
                val expected = InterfaceSettings(
                    language = InterfaceLanguage.German,
                    nowPlaying = NowPlayingDisplaySettings(
                        showPlaybackSource = true,
                        fillArtworkArea = true,
                        showVolumeBar = false,
                    ),
                )
                val settings = naviampCoreSettingsValueCatalog(values).storedSettings
                val restored = settings.loadInterface()
                assertEquals(expected, restored)
                settings.saveInterface(restored)
                val player = Json.parseToJsonElement(values.entries.getValue(KeyInterface)).jsonObject
                    .getValue("nowPlaying").jsonObject
                assertFalse("wideLayout" in player)
                assertFalse("splitPaneBackgroundOpacityPercent" in player)
                assertEquals(expected, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())
            }
        }
        val empty = MemorySettingsValues().apply { entries[KeyInterface] = "{}" }
        assertEquals(InterfaceSettings(), naviampCoreSettingsValueCatalog(empty).storedSettings.loadInterface())
    }

    @Test
    fun ownsPortableSerializationDefaultsAndNormalization() {
        val values = MemorySettingsValues()
        val catalog = naviampCoreSettingsValueCatalog(values)
        val settings = catalog.storedSettings

        assertEquals(InterfaceSettings(), settings.loadInterface())
        settings.saveInterface(InterfaceSettings(
            albumBlurRadiusDp = 999,
            keepScreenAwake = true,
            squareAlbumArtwork = true,
            nowPlaying = NowPlayingDisplaySettings(
                keepTelevisionControlsVisible = true,
                fillArtworkArea = true,
            ),
        ))
        assertEquals(48, settings.loadInterface().albumBlurRadiusDp)
        assertEquals(true, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().keepScreenAwake)
        assertEquals(true, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().squareAlbumArtwork)
        assertEquals(true, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().nowPlaying.keepTelevisionControlsVisible)
        assertEquals(true, naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface().nowPlaying.fillArtworkArea)
        catalog.savePlayback(PlaybackSettings(crossfadeDurationSeconds = 999))
        assertEquals(12, settings.loadPlayback().crossfadeDurationSeconds)

        settings.saveRecentRadioStreams(
            (1..55).map { index -> genreRecentRadioStream(Genre("Genre $index")) },
        )
        assertEquals(MaxRecentRadioStreams, settings.loadRecentRadioStreams().size)
        assertEquals("genre:Genre 50", settings.loadRecentRadioStreams().last().id)

        val session = genreRecentRadioStream(Genre("Ambient")).copy(
            id = "genre:Ambient:session:123",
            sourceId = "server-a",
            startedAtEpochMillis = 123L,
            sessionTracks = listOf(SavedTrack("track-1", "Track", artistName = "Artist")),
        )
        settings.saveRecentRadioStreams(listOf(session))
        assertEquals(session, settings.loadRecentRadioStreams().single())

        values.entries["naviamp.interface"] = "not-json"
        assertEquals(InterfaceSettings(), settings.loadInterface())
        assertFalse(values.entries.values.any { "NSUserDefaults" in it })
    }

    @Test
    fun providerIdentitySettingsMigrationIsSourceScopedVersionedAndRoundTrips() {
        val values = MemorySettingsValues()
        val settings = naviampCoreSettingsValueCatalog(values).storedSettings
        val oldAlbum = "old-album"
        val oldTrack = "old-track"
        val oldStation = "old-station"
        val migrated = mapOf(oldAlbum to "new-album", oldTrack to "new-track", oldStation to "new-station")
        val pins = QuickAccessKind.entries.flatMap { kind -> listOf(
            QuickAccessPin("server-a", kind, oldAlbum, "Pinned ${kind.name}"),
            QuickAccessPin("server-b", kind, oldAlbum, "Other ${kind.name}"),
        ) }
        val originalInterface = InterfaceSettings(language = InterfaceLanguage.German, quickAccess = pins)
        settings.saveInterface(originalInterface)
        settings.saveRecentRadioStreams(
            listOf(
                RecentRadioStream(
                    id = "album:$oldAlbum",
                    label = "Album radio",
                    kind = RecentRadioKind.Album,
                    album = SavedAlbum(oldAlbum, "Album", "Artist", coverArtId = oldAlbum),
                    coverArtIds = listOf(oldAlbum),
                    sourceId = "server-a",
                    sessionTracks = listOf(SavedTrack(oldTrack, "Track", albumId = oldAlbum, artistName = "Artist")),
                ),
                RecentRadioStream(
                    id = "album:$oldAlbum",
                    label = "Other server",
                    kind = RecentRadioKind.Album,
                    sourceId = "server-b",
                ),
            ),
        )
        settings.saveRecentInternetRadioStations(
            listOf(
                SavedInternetRadioStation(oldStation, "Station", "https://a", sourceId = "server-a"),
                SavedInternetRadioStation(oldStation, "Other", "https://b", sourceId = "server-b"),
                SavedInternetRadioStation(oldStation, "Legacy", "https://legacy"),
            ),
        )
        val migration = naviampCoreProviderIdentitySettingsMigrationRepository(values)
        val transform: (String) -> String = { migrated[it] ?: it }

        assertEquals(null, migration.providerIdentitySettingsVersion("server-a"))
        assertEquals(true, migration.migrateProviderIdentitySettings("server-a", 2L, transform))
        assertEquals(2L, migration.providerIdentitySettingsVersion("server-a"))
        assertEquals(false, migration.migrateProviderIdentitySettings("server-a", 2L, transform))
        assertEquals(KeyProviderIdentityVersions, values.writtenKeys.last())
        val reopenedMigration = naviampCoreProviderIdentitySettingsMigrationRepository(values)
        assertEquals(2L, reopenedMigration.providerIdentitySettingsVersion("server-a"))
        assertEquals(null, reopenedMigration.providerIdentitySettingsVersion("server-b"))
        assertEquals(false, reopenedMigration.migrateProviderIdentitySettings("server-a", 2L) { error("Already migrated") })
        assertEquals(originalInterface.copy(quickAccess = pins.map { pin ->
            if (pin.sourceId == "server-a") pin.copy(itemId = "new-album") else pin
        }), naviampCoreSettingsValueCatalog(values).storedSettings.loadInterface())

        val streams = settings.loadRecentRadioStreams()
        assertEquals("album:new-album", streams[0].id)
        assertEquals("new-album", streams[0].album?.id)
        assertEquals("new-track", streams[0].sessionTracks.single().id)
        assertEquals("album:$oldAlbum", streams[1].id)
        val stations = settings.loadRecentInternetRadioStations()
        assertEquals("new-station", stations[0].id)
        assertEquals(oldStation, stations[1].id)
        assertEquals(oldStation, stations[2].id)
    }
}

private class MemorySettingsValues : NaviampCoreSettingsValueStore {
    val entries = mutableMapOf<String, String>()
    val writtenKeys = mutableListOf<String>()
    override fun read(key: String): String? = entries[key]
    override fun write(key: String, value: String) {
        entries[key] = value
        writtenKeys += key
    }
}
