package app.naviamp.presentation

import app.naviamp.domain.settings.CacheSettings
import app.naviamp.domain.settings.InterfaceSettings
import app.naviamp.domain.settings.PlaybackSettings
import app.naviamp.domain.settings.ProviderIdentitySettingsMigrationRepository
import app.naviamp.domain.settings.RecentRadioStream
import app.naviamp.domain.radio.MaxRecentRadioStreams
import app.naviamp.domain.settings.SavedInternetRadioStation
import app.naviamp.domain.settings.SettingsSyncRuntimeState
import app.naviamp.domain.settings.VisualizerSettings
import app.naviamp.domain.settings.normalized
import app.naviamp.domain.settings.migratedProviderIdentities
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Opaque string persistence effect; hosts implement only their native key/value API. */
interface NaviampCoreSettingsValueStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** Optional key-removal effect used only after a durable one-way settings migration. */
interface NaviampCoreMutableSettingsValueStore : NaviampCoreSettingsValueStore {
    fun remove(key: String)
}

/** Owns the portable settings keys, serialization, defaults, normalization, and Core mapping. */
data class NaviampCoreSettingsValueCatalog(
    val storedSettings: NaviampCoreStoredSettings,
    val savePlayback: (PlaybackSettings) -> Unit,
)

fun naviampCoreSettingsValueCatalog(
    values: NaviampCoreSettingsValueStore,
    json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
): NaviampCoreSettingsValueCatalog {
    (values as? NaviampCoreLegacySettingsValueStore)?.let { legacy ->
        migrateLegacyNaviampSettings(legacy = legacy, destination = values, json = json)
    }
    return naviampCoreSettingsValueCatalogWithoutMigration(values, json)
}

/** Shared migration for provider-defined IDs stored in portable JSON settings. */
fun naviampCoreProviderIdentitySettingsMigrationRepository(
    values: NaviampCoreSettingsValueStore,
    json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
): ProviderIdentitySettingsMigrationRepository = object : ProviderIdentitySettingsMigrationRepository {
    override fun providerIdentitySettingsVersion(sourceId: String): Long? =
        readProviderIdentitySettingsVersions(values, json)[sourceId]

    override fun migrateProviderIdentitySettings(
        sourceId: String,
        targetVersion: Long,
        transform: (String) -> String,
    ): Boolean {
        val installed = readProviderIdentitySettingsVersions(values, json)
        if ((installed[sourceId] ?: 0L) >= targetVersion) return false

        val interfaceSettings = read(values, json, KeyInterface, InterfaceSettings())
        val migratedInterface = interfaceSettings.copy(
            quickAccess = interfaceSettings.quickAccess.mapNotNull { value ->
                val pin = value.normalized() ?: return@mapNotNull null
                if (pin.sourceId == sourceId) pin.copy(itemId = transform(pin.itemId)) else pin
            },
        ).normalized()

        val recentRadio = read(values, json, KeyRecentRadio, emptyList<RecentRadioStream>())
            .map { it.migratedProviderIdentities(sourceId, transform) }
        val recentInternetRadio = read(values, json, KeyRecentInternetRadio, emptyList<SavedInternetRadioStation>())
            .map { it.migratedProviderIdentities(sourceId, transform) }
        write(values, json, KeyRecentRadio, recentRadio)
        write(values, json, KeyRecentInternetRadio, recentInternetRadio)
        write(values, json, KeyInterface, migratedInterface)
        migrateGeneratedRadioProviderIdentities(values, json, sourceId, transform)
        values.write(
            KeyProviderIdentityVersions,
            json.encodeToString(
                MapSerializer(String.serializer(), Long.serializer()),
                installed + (sourceId to targetVersion),
            ),
        )
        return true
    }
}

private fun migrateGeneratedRadioProviderIdentities(
    values: NaviampCoreSettingsValueStore,
    json: Json,
    sourceId: String,
    transform: (String) -> String,
) {
    val generated = read(values, json, KeyGeneratedRadio, app.naviamp.domain.radio.GeneratedRadioLibrary())
    val stationIds = generated.stations.asSequence().filter { it.sourceId == sourceId }.mapTo(mutableSetOf()) { it.id }
    write(values, json, KeyGeneratedRadio, generated.copy(
        stations = generated.stations.map { if (it.sourceId == sourceId) it.migratedProviderIdentities(transform) else it },
        batches = generated.batches.map { batch -> if (batch.stationId in stationIds)
            batch.copy(tracks = batch.tracks.map { it.migratedProviderIdentities(transform) },
                resolvedRecipe = batch.resolvedRecipe?.migratedProviderIdentities(transform)) else batch },
    ))
}

private fun readProviderIdentitySettingsVersions(
    values: NaviampCoreSettingsValueStore,
    json: Json,
): Map<String, Long> = values.read(KeyProviderIdentityVersions)
    ?.let { encoded ->
        runCatching {
            json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), encoded)
        }.getOrNull()
    }
    .orEmpty()

/** Internal construction seam used by migration to avoid recursively re-entering migration. */
internal fun naviampCoreSettingsValueCatalogWithoutMigration(
    values: NaviampCoreSettingsValueStore,
    json: Json,
): NaviampCoreSettingsValueCatalog {
    return NaviampCoreSettingsValueCatalog(
        savePlayback = { write(values, json, KeyPlayback, it.normalized()) },
        storedSettings = NaviampCoreStoredSettings(
            loadInterface = { read(values, json, KeyInterface, InterfaceSettings()).normalized() },
            saveInterface = { write(values, json, KeyInterface, it.normalized()) },
            loadPlayback = { read(values, json, KeyPlayback, PlaybackSettings()).normalized() },
            loadCache = { read(values, json, KeyCache, CacheSettings()).normalized() },
            saveCache = { write(values, json, KeyCache, it.normalized()) },
            loadVisualizer = { read(values, json, KeyVisualizer, VisualizerSettings()) },
            saveVisualizer = { write(values, json, KeyVisualizer, it) },
            loadRecentRadioStreams = {
                val stored = read(values, json, KeyRecentRadio, emptyList<RecentRadioStream>())
                val reconciled = app.naviamp.domain.radio.reconciledRecentRadioStreams(stored)
                if (stored != reconciled) write(values, json, KeyRecentRadio, reconciled)
                reconciled
            },
            saveRecentRadioStreams = { write(values, json, KeyRecentRadio, app.naviamp.domain.radio.reconciledRecentRadioStreams(it)) },
            loadRecentInternetRadioStations = {
                read(values, json, KeyRecentInternetRadio, emptyList<SavedInternetRadioStation>())
            },
            saveRecentInternetRadioStations = { write(values, json, KeyRecentInternetRadio, it) },
            loadSyncRuntime = {
                read(values, json, KeySyncRuntime, SettingsSyncRuntimeState()).normalized()
            },
            saveSyncRuntime = { write(values, json, KeySyncRuntime, it.normalized()) },
            loadRecentPlaylistIds = { read(values, json, KeyRecentPlaylists, emptyList<String>()) },
            saveRecentPlaylistIds = { write(values, json, KeyRecentPlaylists, it) },
            generatedRadio = object : app.naviamp.domain.radio.GeneratedRadioRepository {
                override fun load() = read(values, json, KeyGeneratedRadio, app.naviamp.domain.radio.GeneratedRadioLibrary()).normalized()
                override fun save(library: app.naviamp.domain.radio.GeneratedRadioLibrary) =
                    write(values, json, KeyGeneratedRadio, library.normalized())
            },
        ),
    )
}

private inline fun <reified T> read(
    values: NaviampCoreSettingsValueStore,
    json: Json,
    key: String,
    default: T,
): T = values.read(key)
    ?.let { encoded -> runCatching { json.decodeFromString<T>(encoded) }.getOrNull() }
    ?: default

private inline fun <reified T> write(
    values: NaviampCoreSettingsValueStore,
    json: Json,
    key: String,
    value: T,
) {
    values.write(key, json.encodeToString(value))
}

internal const val KeyInterface = "naviamp.interface"
internal const val KeyPlayback = "naviamp.playback"
internal const val KeyCache = "naviamp.cache"
internal const val KeyVisualizer = "naviamp.visualizer"
internal const val KeyRecentRadio = "naviamp.recentRadio"
internal const val KeyRecentInternetRadio = "naviamp.recentInternetRadio"
internal const val KeySyncRuntime = "naviamp.syncRuntime"
internal const val KeyRecentPlaylists = "naviamp.recentPlaylists"
internal const val KeyProviderIdentityVersions = "naviamp.providerIdentityVersions"
internal const val KeyGeneratedRadio = "naviamp.generatedRadio"
