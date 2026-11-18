package app.naviamp.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class CacheSettingsTest {
    @Test
    fun missingWaveformPreferenceDefaultsOffWithoutResettingOtherSettings() {
        val settings = Json.decodeFromString<CacheSettings>("""{"audioPrefetchDepth":4}""").normalized()

        assertFalse(settings.waveformsEnabled)
        assertEquals(4, settings.audioPrefetchDepth)
    }

    @Test
    fun explicitWaveformChoicesSurviveNormalizationAndSerialization() {
        for (enabled in listOf(false, true)) {
            val settings = Json.decodeFromString<CacheSettings>(
                """{"waveformsEnabled":$enabled}""",
            ).normalized()
            val reopened = Json.decodeFromString<CacheSettings>(
                Json.encodeToString(CacheSettings.serializer(), settings),
            ).normalized()

            assertEquals(enabled, reopened.waveformsEnabled)
        }
    }

    @Test
    fun defaultsUseTheSmallestActiveCacheAndDownloadChoices() {
        val settings = CacheSettings()

        assertEquals(2, settings.audioPrefetchDepth)
        assertEquals(256L * 1024L * 1024L, settings.maxAudioCacheBytes)
        assertEquals(512L * 1024L * 1024L, settings.maxDownloadBytes)
    }

    @Test
    fun normalizationUsesTheDefaultBudgetsAsStorageFloors() {
        val settings = CacheSettings(
            maxAudioCacheBytes = 1L,
            maxDownloadBytes = 1L,
        ).normalized()

        assertEquals(DefaultAudioCacheBytes, settings.maxAudioCacheBytes)
        assertEquals(DefaultDownloadStorageBytes, settings.maxDownloadBytes)
    }
}
