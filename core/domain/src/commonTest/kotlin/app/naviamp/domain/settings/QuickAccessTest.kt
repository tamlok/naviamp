package app.naviamp.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickAccessTest {
    private val playlist = QuickAccessPin("source-a", QuickAccessKind.Playlist, "item-1", "My playlist")

    @Test
    fun normalizationTrimsValuesAndRejectsMissingIdentityOrTitle() {
        assertEquals(playlist, playlist.copy(sourceId = " source-a ", itemId = " item-1 ", title = " My playlist ").normalized())
        assertNull(playlist.copy(sourceId = " \n ").normalized())
        assertNull(playlist.copy(itemId = "\t").normalized())
        assertNull(playlist.copy(title = " ").normalized())
        assertEquals(playlist, playlist.normalized())
    }

    @Test
    fun targetIdentityIncludesSourceKindAndItemButNotTitle() {
        assertTrue(playlist.sameTarget(playlist.copy(title = "Renamed")))
        assertFalse(playlist.sameTarget(playlist.copy(sourceId = "source-b")))
        assertFalse(playlist.sameTarget(playlist.copy(kind = QuickAccessKind.Album)))
        assertFalse(playlist.sameTarget(playlist.copy(itemId = "item-2")))
    }

    @Test
    fun interfaceNormalizationKeepsFirstTargetAndInsertionOrderAcrossSourcesAndKinds() {
        val album = playlist.copy(kind = QuickAccessKind.Album, title = "Album")
        val artist = playlist.copy(kind = QuickAccessKind.Artist, title = "Artist")
        val otherSource = playlist.copy(sourceId = "source-b")
        val settings = InterfaceSettings(quickAccess = listOf(
            playlist.copy(sourceId = " source-a ", title = " My playlist "),
            album,
            playlist.copy(title = "Duplicate"),
            artist,
            otherSource,
            playlist.copy(itemId = ""),
            playlist.copy(itemId = "item-2", title = " "),
        )).normalized()

        assertEquals(listOf(playlist, album, artist, otherSource), settings.quickAccess)
        assertEquals(settings, settings.normalized())
    }

    @Test
    fun pinIsIdempotentAndRefreshesRenamedTitlesInPlace() {
        val album = playlist.copy(kind = QuickAccessKind.Album, title = "Album")
        val settings = InterfaceSettings(language = InterfaceLanguage.German)
            .withQuickAccessPin(playlist)
            .withQuickAccessPin(album)
        assertEquals(settings, settings.withQuickAccessPin(playlist))
        assertEquals(settings, settings.withQuickAccessPin(playlist.copy(title = " ")))

        val renamed = playlist.copy(title = "Renamed playlist")
        val updated = settings.withQuickAccessPin(renamed.copy(sourceId = " source-a ", title = " Renamed playlist "))
        assertEquals(listOf(renamed, album), updated.quickAccess)
        assertEquals(InterfaceLanguage.German, updated.language)
        val unnormalized = settings.copy(quickAccess = listOf(playlist.copy(itemId = " item-1 "), album))
        assertEquals(updated, unnormalized.withQuickAccessPin(renamed))
    }

    @Test
    fun unpinUsesIdentityEvenWhenTheTitleIsMissingOrChanged() {
        val album = playlist.copy(kind = QuickAccessKind.Album, title = "Album")
        val otherSource = playlist.copy(sourceId = "source-b")
        val settings = InterfaceSettings(quickAccess = listOf(playlist, album, otherSource))
        val removed = settings.withoutQuickAccessPin(playlist.copy(sourceId = " source-a ", itemId = " item-1 ", title = ""))
        assertEquals(listOf(album, otherSource), removed.quickAccess)
        assertEquals(removed, removed.withoutQuickAccessPin(playlist))
        assertTrue(removed.withoutQuickAccessPin(album).withoutQuickAccessPin(otherSource).quickAccess.isEmpty())
    }
}
