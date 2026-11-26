package app.naviamp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaviampChangelogTest {
    @Test fun release100DescribesTheFirstNavoReleaseAndItsCompatibilityLimits() {
        val about = NaviampAboutUi()
        assertEquals("v1.0.0", about.version)
        assertEquals("66", about.buildNumber)
        assertEquals(listOf("Features", "Upgrade notes", "Known issues", "Thanks"),
            about.changelog.map { it.title })
        val sections = about.changelog.associate { it.title to it.entries }
        val features = sections.getValue("Features")
        assertTrue(features.first().contains("Windows and Android"))
        assertTrue(features.any { it.contains("station recipes") && it.contains("exact order") })
        assertTrue(features.any { it.contains("Home, Playlists, Library, Radio, and My") })
        assertTrue(features.any { it.contains("Resume playlists") && it.contains("Quick Access") })
        assertTrue(features.any { it.contains("lyrics") && it.contains("Forest") })
        val upgrades = sections.getValue("Upgrade notes")
        assertTrue(upgrades.any { it.contains("does not automatically migrate") })
        assertTrue(upgrades.any { it.contains("uninstall") && it.contains("update checker") })
        assertTrue(upgrades.any { it.contains("Aurral requires version 3.x") })
        assertTrue(upgrades.any { it.contains("sync schema 2") })
        val limitations = sections.getValue("Known issues")
        assertTrue(limitations.any { it.contains("not publisher-signed") })
        assertTrue(limitations.any { it.contains("Only Windows x64 and Android") })
        val thanks = sections.getValue("Thanks")
        assertTrue(thanks.any { it.contains("@ursasmar") && it.contains("upstream") })
        assertTrue(thanks.any { it.contains("@crsthn-slv") && it.contains("translation") })
        assertTrue(thanks.any { it.contains("@sewnie") && it.contains("reporting") })
        assertTrue(about.changelog.flatMap { it.entries }.none { it.contains("https://") })
    }
}
