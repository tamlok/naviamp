package app.naviamp.domain.radio

import app.naviamp.domain.connect.NaviampConnectSourceIdentity
import app.naviamp.domain.settings.SavedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedRadioStationTest {
    @Test fun transientRecipesAreBoundedWithoutEvictingSavedStationsOrReferencedBatches() {
        val old = GeneratedRadioStation("old", "Old batch", GeneratedRadioKind.Track)
        val saved = old.copy(id = "saved", saved = true)
        val recent = (1..200).map { old.copy(id = "station-$it", updatedAtEpochMillis = it.toLong()) }
        val batch = GeneratedRadioBatch("batch", old.id, 1, 1, listOf(SavedTrack("track", "Track", artistName = "Artist")))
        val result = GeneratedRadioLibrary(stations = listOf(old, saved) + recent, batches = listOf(batch)).normalized()
        assertEquals(102, result.stations.size)
        assertTrue(result.stations.any { it.id == old.id })
        assertTrue(result.stations.any { it.id == saved.id })
        assertFalse(result.stations.any { it.id == "station-1" })
        assertEquals(batch, result.batches.single())
    }

    @Test fun canonicalAccountMatchDoesNotAuthorizeAnotherLibraryScope() {
        val identity = NaviampConnectSourceIdentity("subsonic", "https://music.test", "listener", listOf("one"))
        val station = GeneratedRadioStation("station", "Station", GeneratedRadioKind.Track,
            sourceId = "local", sourceIdentity = identity, libraryScope = "scope-one")
        assertTrue(station.belongsTo("remapped", identity, "scope-one"))
        assertFalse(station.belongsTo("local", identity, "scope-two"))
        assertFalse(station.belongsTo("local", identity.copy(accountIdentity = "another"), "scope-one"))
        assertFalse(station.belongsTo("local", null, "scope-one"))
    }
}
